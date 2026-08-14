package managerAgent.orchestrator;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.tool.RemoteAgentTool;
import utils.AgentUtils;
import utils.JsonUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 确定性编排器（阶段三核心）：把「自由 ReAct」替换为「解析 → 并行 DAG → 聚合」。
 * 端到端传结构化 JSON（TripRequest / TripPlanData），LLM 只负责内容生成，流程由 Java 确定。
 */
@Slf4j
public class TripOrchestrator {

    /** 并行调用远程专家 Agent 的共享线程池（有界，服务长期运行不复用也不关闭） */
    private static final ExecutorService PARALLEL_POOL = Executors.newFixedThreadPool(4);

    private final String sessionId;
    private final Model model;               // 解析/聚合用裸模型
    private final RemoteAgentTool remoteTool; // 专家 Agent 调用封装

    public TripOrchestrator(String sessionId) {
        this.sessionId = sessionId;
        this.model = AgentUtils.buildModel();
        this.remoteTool = new RemoteAgentTool();
        this.remoteTool.setSessionId(sessionId);
    }

    /**
     * 执行编排：解析需求 → 并行调用专家 → 聚合为结构化行程。
     * @param prefs 用户沉淀偏好（P0 注入，可为 null 表示未设置/本次不使用）
     */
    public TripPlanData run(String userPrompt, TripRequest.Preferences prefs) {
        // ① 需求解析（单次结构化 LLM 调用）
        EventStore.add(sessionId, "解析", "正在解析旅行需求...");
        TripRequest req = parseRequest(userPrompt);
        // ★P0：偏好注入——在 parseRequest() 之后合并，不走 LLM 解析偏好（避免幻觉）
        if (prefs != null && !prefs.isEmpty()) {
            req.setPreferences(prefs);
        }
        EventStore.add(sessionId, "解析", "需求：" + req.getOrigin() + " → " + req.getDestination()
                + "，" + req.getDays() + " 天" + (req.getPreference().isBlank() ? "" : "（" + req.getPreference() + "）")
                + (req.getPreferences() == null || req.getPreferences().isEmpty() ? "" : "，偏好已注入"));
        checkCancel();

        // ② 并行 DAG：路线 + 行程 同时调用，无先后依赖
        Future<String> routeFuture = PARALLEL_POOL.submit(() ->
                remoteTool.callRouteMakingAgent(routeTask(req)));
        Future<String> planFuture = PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(planTask(req)));
        String routeResult = await(routeFuture, "路线Agent");
        String planResult = await(planFuture, "行程Agent");
        checkCancel();

        // ③ 聚合：结构化 JSON（失败退回 markdown 原文兜底）
        EventStore.add(sessionId, "整合", "正在整合路线与行程数据...");
        TripPlanData plan = aggregate(req, routeResult, planResult);
        if (plan == null) {
            plan = fallbackPlan(req, routeResult, planResult); // 兜底已含完整 markdown 原文
        } else {
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段
        }
        EventStore.add(sessionId, "完成", "结构化行程生成完成");
        return plan;
    }

    // ==================== ① 需求解析 ====================

    private TripRequest parseRequest(String prompt) {
        String sysPrompt = "你是旅行需求解析器。从用户输入提取结构化信息，只输出一个 JSON 对象，字段名固定如下：\n"
                + "{\"origin\":\"起点城市\",\"destination\":\"目的地城市\",\"days\":整数天数,"
                + "\"date\":\"出行日期\",\"preference\":\"偏好(如自驾/亲子/美食)\"}\n"
                + "缺失字段给空字符串或默认值。除 JSON 外不要输出任何文字。";
        String text = callModelJson(sysPrompt, prompt);
        TripRequest req = JsonUtils.extractAndParse(text, TripRequest.class);
        if (req == null) { // 解析失败：从原文粗提取兜底
            req = fallbackParse(prompt);
        }
        if (req.getDays() < 1) {
            req.setDays(1);
        }
        req.setRaw(prompt);
        return req;
    }

    /** 解析失败时从原文粗提取起点/终点/天数 */
    private TripRequest fallbackParse(String prompt) {
        TripRequest req = new TripRequest();
        String[] citys = prompt.replaceAll("帮我|制定|计划|规划|旅游|旅行|游|自驾|路线", " ").trim().split("\\s+");
        int cityIdx = 0;
        for (String token : citys) {
            if (token.isBlank() || token.length() > 6 || token.matches(".*\\d.*")) {
                continue;
            }
            if (cityIdx == 0) {
                req.setOrigin(token);
            } else if (cityIdx == 1) {
                req.setDestination(token);
            } else {
                break;
            }
            cityIdx++;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*[日天]").matcher(prompt);
        if (m.find()) {
            req.setDays(Integer.parseInt(m.group(1)));
        }
        return req;
    }

    // ==================== ② 并行 DAG ====================

    private String routeTask(TripRequest req) {
        return "请规划从 " + req.getOrigin() + " 到 " + req.getDestination() + " 的自驾路线，"
                + req.getDays() + " 天，包含每日行车路线、途经景点、驾驶时间与费用。"
                + prefsSuffix(req);
    }

    private String planTask(TripRequest req) {
        return "请规划 " + req.getDestination() + " " + req.getDays() + " 日游的景点与每日行程，"
                + "包含景点、门票、住宿、餐饮美食" + (req.getPreference().isBlank() ? "" : "，偏好：" + req.getPreference())
                + prefsSuffix(req) + "。";
    }

    /** ★P0：把用户沉淀偏好拼进提示词（路线 + 行程两个子 Agent 都带上，个性化收敛在编排层） */
    private String prefsSuffix(TripRequest req) {
        TripRequest.Preferences p = req.getPreferences();
        if (p == null || p.isEmpty()) {
            return "";
        }
        return "，用户偏好：" + p.toPromptText();
    }

    /** 检查中断请求（替代原 planHook 的事件级取消，编排器在步骤边界检查） */
    private void checkCancel() {
        if (EventStore.isCancelRequested(sessionId)) {
            throw new CancelledException();
        }
    }

    /** 等待 Future 结果，超时/异常返回空串并记录事件 */
    private String await(Future<String> future, String label) {
        try {
            String r = future.get(180, TimeUnit.SECONDS);
            return r == null ? "" : r;
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 调用失败: {}", label, e.getMessage());
            EventStore.add(sessionId, "Agent结果", label + " ❌ 调用失败(" + e.getMessage() + ")");
            return "";
        }
    }

    // ==================== ③ 聚合 ====================

    private TripPlanData aggregate(TripRequest req, String routeResult, String planResult) {
        String sysPrompt = "你是行程聚合器。根据需求与专家数据，输出一个结构化 JSON 对象，字段名固定如下：\n"
                + "{\"title\":\"行程标题\",\"destination\":\"目的地\",\"days\":天数,\"date\":\"日期\",\"weather\":\"天气\","
                + "\"sections\":[{\"type\":\"timeline|transport|stay|tips\",\"title\":\"区块标题\",\"items\":[{\"time\":\"\","
                + "\"activity\":\"\",\"place\":\"\",\"category\":\"\",\"cost\":\"\",\"note\":\"\",\"from\":\"\",\"to\":\"\","
                + "\"way\":\"\",\"duration\":\"\",\"name\":\"\",\"price\":\"\",\"kind\":\"\",\"text\":\"\"}]}]}\n"
                + "sections 按「每日行程(timeline)/交通(transport)/住宿餐饮(stay)/实用信息(tips)」组织，"
                + "每天的行程放在一个 type=timeline 的 section 里，title 用「第N天」。"
                + "内容必须具体（真实景点名/价格/餐厅酒店名）。除 JSON 外不要输出任何文字。";
        String userPrompt = "用户需求：" + req.getRaw() + "\n\n【路线数据】\n" + routeResult + "\n\n【行程数据】\n" + planResult;
        String text = callModelJson(sysPrompt, userPrompt);
        TripPlanData plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        if (plan == null) {
            return null;
        }
        if (plan.getTitle().isBlank()) {
            plan.setTitle(req.getDestination() + req.getDays() + "日游");
        }
        if (plan.getDestination().isBlank()) {
            plan.setDestination(req.getDestination());
        }
        if (plan.getDays() < 1) {
            plan.setDays(req.getDays());
        }
        return plan;
    }

    /** 聚合失败：退回原文 markdown，sections 留空供前端 markdown 兜底 */
    private TripPlanData fallbackPlan(TripRequest req, String routeResult, String planResult) {
        TripPlanData plan = new TripPlanData();
        plan.setTitle(req.getDestination() + req.getDays() + "日游计划");
        plan.setDestination(req.getDestination());
        plan.setDays(req.getDays());
        plan.setMarkdown(joinRaw(req, routeResult, planResult));
        log.warn("结构化聚合失败，退回 markdown 原文兜底");
        return plan;
    }

    private String joinRaw(TripRequest req, String routeResult, String planResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(req.getDestination()).append(req.getDays()).append("日游计划\n\n");
        if (!routeResult.isBlank()) {
            sb.append("## 路线\n\n").append(routeResult).append("\n\n");
        }
        if (!planResult.isBlank()) {
            sb.append("## 行程\n\n").append(planResult).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 工具方法 ====================

    /** 请求 JSON 输出的单次裸 LLM 调用 */
    private String callModelJson(String sysPrompt, String userPrompt) {
        Msg system = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(sysPrompt).build()).build();
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(userPrompt).build()).build();
        // 注入 response_format: json_object（若模型不支持则被忽略，靠 prompt + 鲁棒提取兜底）
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of("response_format", Map.of("type", "json_object")))
                .build();
        try {
            ChatResponse resp = model.stream(List.of(system, user), null, options)
                    .blockLast(Duration.ofSeconds(120));
            return textOf(resp);
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return "";
        }
    }

    /** 从 ChatResponse 提取文本（遍历 TextBlock） */
    private String textOf(ChatResponse resp) {
        if (resp == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    /** 结构化行程 → markdown（降级字段 + 旧前端兼容） */
    private String renderMarkdown(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.getTitle()).append("\n\n");
        if (!plan.getWeather().isBlank()) {
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");
        }
        for (Section sec : plan.getSections()) {
            sb.append("## ").append(sec.getTitle()).append("\n");
            for (SectionItem it : sec.getItems()) {
                sb.append("- ");
                switch (sec.getType()) {
                    case "timeline" -> sb.append("**").append(it.getTime()).append("** ")
                            .append(it.getPlace()).append(" ").append(it.getActivity());
                    case "transport" -> sb.append(it.getFrom()).append(" → ").append(it.getTo())
                            .append("（").append(it.getWay()).append("，").append(it.getDuration()).append("）");
                    case "stay" -> {
                        String kind = it.getKind().isBlank() ? it.getCategory() : it.getKind();
                        String detail = (kind + " " + it.getPrice()).trim();
                        sb.append(it.getName());
                        if (!detail.isBlank()) sb.append("（").append(detail).append("）");
                    }
                    case "tips" -> sb.append(it.getKind().isBlank() ? "" : it.getKind() + "：").append(it.getText());
                    default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
