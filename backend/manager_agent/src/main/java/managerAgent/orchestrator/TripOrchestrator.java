package managerAgent.orchestrator;

import dto.PlanSet;
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

/** 确定性编排器：解析 → 并行 DAG → 聚合，流程由 Java 确定，LLM 只负责内容生成。 */
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
     */
    public TripPlanData run(String userPrompt) {
        return run(userPrompt, null);
    }

    /**
     * 执行编排（带偏好参数）：解析需求 → 并行调用专家 → 聚合为结构化行程。
     */

    /**
     * 执行编排（带个人中心沉淀偏好 P0）：解析需求 → 偏好注入 → 并行调用专家 → 聚合。
     */
    public TripPlanData run(String userPrompt, TripRequest.Preferences prefs) {
        return runCore(userPrompt, prefs);
    }

    /** 执行编排核心（个人中心沉淀偏好可 null）：解析需求 → 偏好分类 → 并行调用专家 → 聚合。 */
    private TripPlanData runCore(String userPrompt, TripRequest.Preferences prefs) {
        ReqAndData rd = resolveAndFetch(userPrompt, prefs);
        // ③ 聚合：结构化 JSON（失败退回 markdown 原文兜底）
        EventStore.add(sessionId, "整合", "正在整合路线与行程数据...");
        TripPlanData plan = aggregate(rd.req, rd.routeResult, rd.planResult);
        if (plan == null) {
            plan = fallbackPlan(rd.req, rd.routeResult, rd.planResult); // 兜底已含完整 markdown 原文
        } else {
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段
        }
        EventStore.add(sessionId, "完成", "结构化行程生成完成");
        return plan;
    }

    /** 多方案编排：并行聚合出美食向/出片向/省钱向 3 套方案供前端对比。 */
    public PlanSet runMultiScheme(String userPrompt) {
        ReqAndData rd = resolveAndFetch(userPrompt, null);
        EventStore.add(sessionId, "整合", "正在生成 3 套差异化方案（美食向/出片向/省钱向）...");
        PlanSet set = aggregateMulti(rd.req, rd.routeResult, rd.planResult);
        // 每套方案补降级 markdown（前端打印/导出用）
        if (set != null) {
            for (TripPlanData p : set.getPlans()) {
                if (p != null && p.getMarkdown().isBlank()) {
                    p.setMarkdown(renderMarkdown(p));
                }
            }
        }
        EventStore.add(sessionId, "完成", "多方案生成完成（" + (set == null ? 0 : set.getPlans().size()) + " 套）");
        return set;
    }

    /** 解析需求 + 并行 DAG（单方案/多方案共享的前置步骤；prefs=个人中心沉淀偏好，可 null） */
    private ReqAndData resolveAndFetch(String userPrompt, TripRequest.Preferences prefs) {
        // ① 需求解析（单次结构化 LLM 调用）
        EventStore.add(sessionId, "解析", "正在解析旅行需求...");
        TripRequest req = parseRequest(userPrompt);
        if (prefs != null && !prefs.isEmpty()) {
            req.setPreferences(prefs);
        }
        EventStore.add(sessionId, "解析", "需求：" + req.getOrigin() + " → " + req.getDestination()
                + "，" + req.getDays() + " 天" + (req.getPreference().isBlank() ? "" : "（" + req.getPreference() + "）")
                + buildPreferenceLabel(req));
        checkCancel();

        // ② 偏好分流：LLM 把文本+个人中心偏好分为行程/路线/聚合三类
        boolean hasTextPref = req.getPreference() != null && !req.getPreference().isBlank();
        boolean hasStoredPref = req.getPreferences() != null && !req.getPreferences().isEmpty();
        if (hasTextPref || hasStoredPref) {
            classifyPreference(req);
        }
        // ③ 编排分支：跨城=先路线(途经点)再行程；市内=先行程(景点)再路线
        boolean crossCity = req.getOrigin() != null && !req.getOrigin().isBlank()
                && req.getDestination() != null && !req.getDestination().isBlank()
                && !req.getOrigin().trim().equals(req.getDestination().trim());
        boolean cityTour = !crossCity;  // 单城 / 起点=终点 / 无起点 → 市内
        if (crossCity) {
            EventStore.add(sessionId, "路线", "跨城自驾，先规划路线提取途经城市...");
            String routeResult = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callRouteMakingAgent(routeTask(req, ""))), "路线Agent");
            checkCancel();
            String waypoints = extractWaypoints(routeResult);
            String planResult = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callTripPlannerAgent(planTask(req, waypoints))), "行程Agent");
            checkCancel();
            return new ReqAndData(req, routeResult, planResult);
        }
        EventStore.add(sessionId, "行程", "市内游，先规划行程提取景点清单...");
        String planFirst = await(PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(planTask(req, ""))), "行程Agent");
        checkCancel();
        String sights = extractSights(planFirst, req.getDestination());
        String routeResult2 = await(PARALLEL_POOL.submit(() ->
                remoteTool.callRouteMakingAgent(routeTask(req, sights))), "路线Agent");
        checkCancel();
        return new ReqAndData(req, routeResult2, planFirst);
    }

    /** 解析+DAG 结果载体 */
    private static class ReqAndData {
        final TripRequest req;
        final String routeResult;
        final String planResult;
        ReqAndData(TripRequest req, String routeResult, String planResult) {
            this.req = req; this.routeResult = routeResult; this.planResult = planResult;
        }
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

    /** 偏好标签（事件流展示用，如「步行≤1万米 · 早起 · 博物馆多」） */
    private String buildPreferenceLabel(TripRequest req) {
        return ""; // 前端选项卡已删，偏好仅来自用户输入+个人中心
    }

    /** 偏好约束（聚合 prompt 用，引导模型按偏好安排行程节奏） */
    private String buildPreferencePrompt(TripRequest req) {
        return ""; // 预检约束不再拼入聚合提示词
    }

    // ==================== ② 并行 DAG ====================

    private String routeTask(TripRequest req, String sightList) {
        java.util.List<String> routePrefs = new java.util.ArrayList<>();
        if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) routePrefs.add(req.getRoutePref().trim());
        String routePrefStr = routePrefs.isEmpty() ? "" : String.join(" ", routePrefs);
        // sightList 非空 → 市内游且已由行程Agent定好景点，路线按这些景点查衔接
        String dest = (req.getDestination() == null || req.getDestination().isBlank()) ? "" : req.getDestination();
        boolean noOrigin = req.getOrigin() == null || req.getOrigin().isBlank();
        if (noOrigin || sightList != null && !sightList.isBlank()) {
            // 市内/行程先行：路线Agent针对"行程已定的景点"查衔接，不再自己猜景点
            String base = "这是 " + dest + " 的" + req.getDays() + "天市内/周边游（用户未提供出发地）。\n";
            if (sightList != null && !sightList.isBlank()) {
                base += "行程Agent已确定景点清单：" + sightList + "。\n"
                        + "★请用 query_route_unified(mode=urban" + (routePrefStr.isBlank() ? "" : ", routePref=" + routePrefStr)
                        + ") 只查询这些景点彼此之间的衔接方式"
                        + "（短距步行/长距公交地铁），不要额外添加不在清单里的景点，也不要编造跨城里程。\n";
            } else {
                base += "请规划在市内及周边的行程：\n"
                        + "★调用 query_route_unified，mode 固定传 urban" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                        + "（市内衔接：步行/公交），查询市内相邻景点/地点间的衔接方式，不要编造跨城里程。\n";
            }
            base += "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数。\n"
                    + "最终回复：完整可读的市内行程，含每日安排、景点间衔接方式、大致耗时。";
            return base;
        }
        return "请规划从 " + req.getOrigin() + " 到 " + dest + " 的自驾路线，"
                + req.getDays() + " 天，包含每日行车路线、途经景点、驾驶时间与费用。\n"
                + "★调用 query_route_unified，mode 参数driving" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                + "（自驾路线与距离），查询各段自驾线路。\n"
                + "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数、不得扩展为更长的环线。\n"
                + "如果 " + req.getDays() + " 天跑不完某条长线，请选择适合 " + req.getDays() + " 天的缩短路线（或减少景点），"
                + "天数只允许少不允许多。";
    }

    private String planTask(TripRequest req, String waypoints) {
        // 行程偏好：LLM 分类出的 tripPref
        java.util.List<String> tripPrefs = new java.util.ArrayList<>();
        if (req.getTripPref() != null && !req.getTripPref().isBlank()) tripPrefs.add(req.getTripPref().trim());
        String prefStr = tripPrefs.isEmpty() ? "" : String.join(" ", tripPrefs);
        if (waypoints != null && !waypoints.isBlank()) {
            // 跨城自驾：查沿途城市景点
            return "请规划从 " + req.getOrigin() + " 到 " + req.getDestination() + " 的" + req.getDays() + "天自驾行程，"
                    + "重点规划【路途沿途】的景点。\n"
                    + "★调用 query_trip 时，把途经城市传 waypoints 参数（逗号分隔）：" + waypoints + "，"
                    + "只查这些途经点的景点/美食，不要查起点或终点城市内部的景点。\n"
                    + "结合路线按 Day 每日给出途经景点行程与安排。"
                    + (prefStr.isBlank() ? "" : "★把行程偏好作为 preference 参数传入：" + prefStr + "。");
        }
        return "请规划 " + req.getDestination() + " " + req.getDays() + " 日游的景点与每日行程，"
                + "包含景点、门票、住宿、餐饮美食"
                + (prefStr.isBlank() ? "" : "，偏好：" + prefStr) + "。\n"
                + "★调用 query_trip 时，把上述偏好作为 preference 参数传入，"
                + "让固定链按偏好查询更贴合的数据(如'亲子景点'/'美食餐厅')，不要忽略偏好查通用清单。";
    }

    /** 从路线结果提取途经城市名（带市/区/县后缀，排除起终点，最多5个）。 */
    private String extractWaypoints(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return "";
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\\u4e00-\\u9fa5]{2,6}?(?:市|区|县|镇|州)")
                .matcher(routeResult);
        while (m.find() && seen.size() < 5) {
            seen.add(m.group());
        }
        // 剔除起终点城市名本身
        seen.removeIf(n -> n.contains("起点") || n.contains("终点"));
        return String.join(",", seen);
    }

    /** 从行程结果提取景点名（带景区/园/馆等后缀，最多5个）。 */
    private String extractSights(String planResult, String dest) {
        if (planResult == null || planResult.isBlank()) return "";
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\u4e00-\u9fa5]{2,8}?(?:景区|公园|古镇|山庄|博物馆|街|塔|山|湾|洞|寺|宫|观景台|馆|广场|坊|陵|岛|湖|院|园|桥|河)")
                .matcher(planResult);
        while (m.find() && seen.size() < 5) {
            String name = m.group();
            if (name.equals(dest)) continue; // 排除纯城市名
            seen.add(name);
        }
        return String.join(",", seen);
    }

    /** 偏好分类：1次LLM把偏好分为行程(tripPref)/路线(routePref)/聚合(aggPref)三类。 */
    private void classifyPreference(TripRequest req) {
        String sys = "你是旅行偏好分类器。把用户偏好分类到三个字段，只输出一个 JSON 对象，字段名固定：\n"
                + "{\"tripPref\":\"适合行程Agent的偏好(与景点美食住宿相关，如亲子/美食/小众/博物馆)；无则空\",\n"
                + "\"routePref\":\"适合路线Agent的偏好(与出行方式道路相关，如公交/骑行/步行/不走高速)；无则空\",\n"
                + "\"aggPref\":\"不适合二者、只影响整体编排的偏好(如放松休闲/性价比紧凑/节假日)；无则空\"}\n"
                + "只输出 JSON 不要多余文字。";
        // 个人中心偏好并入分类输入
        String prefInput = req.getPreference();
        if (req.getPreferences() != null && !req.getPreferences().isEmpty()) {
            prefInput = (prefInput == null || prefInput.isBlank() ? "" : prefInput + "；")
                    + "个人中心设置：" + req.getPreferences().toPromptText();
        }
        String out = callModelJson(sys, "用户偏好：" + prefInput);
        try {
            var node = JsonUtils.extractAndParse(out, java.util.Map.class);
            if (node != null) {
                req.setTripPref(str(node.get("tripPref")));
                req.setRoutePref(str(node.get("routePref")));
                req.setAggPref(str(node.get("aggPref")));
                return;
            }
        } catch (Exception ignored) { }
        // 解析失败兜底：全部留给聚合
        req.setAggPref(req.getPreference());
    }

    /** 对象安全转字符串 */
    private String str(Object o) {
        return o == null ? "" : o.toString();
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
        return aggregateWithStyle(req, routeResult, planResult, null);
    }

    /** 单套方案聚合（style=null 表示默认无风格；否则注入风格约束） */
    private TripPlanData aggregateWithStyle(TripRequest req, String routeResult, String planResult, String style) {
        String sysPrompt = "你是行程聚合器。根据需求与专家数据，输出一个结构化 JSON 对象，字段名固定如下：\n"
                + "{\"title\":\"行程标题\",\"destination\":\"目的地\",\"days\":天数,\"date\":\"日期\",\"weather\":\"天气\","
                + "\"sections\":[{\"type\":\"timeline|transport|stay|tips\",\"title\":\"区块标题\",\"items\":[{\"time\":\"\","
                + "\"activity\":\"\",\"place\":\"\",\"category\":\"\",\"cost\":\"\",\"note\":\"\",\"from\":\"\",\"to\":\"\","
                + "\"way\":\"\",\"duration\":\"\",\"name\":\"\",\"price\":\"\",\"kind\":\"\",\"text\":\"\"}]}]}\n"
                + "sections 按「每日行程(timeline)/交通(transport)/住宿餐饮(stay)/实用信息(tips)」组织，"
                + "每天的行程放在一个 type=timeline 的 section 里，title 用「第N天」。"
                + "内容必须具体（真实景点名/价格/餐厅酒店名）。除 JSON 外不要输出任何文字。"
                + "★place硬规则：timeline 的 items 中 place 字段只填地点名称（如「星海广场」），括号内门牌号/地铁口/道路名等地址信息一律放 note 字段，禁止把地址当地名。\n"
                + "★sections 必须包含住宿餐饮(stay)区块（含酒店/餐厅推荐），不得省略。\n"
                + buildStyleHint(style);
        String userPrompt = "用户需求：" + req.getRaw()
                + buildPreferencePrompt(req)  // 偏好约束
                + "\n\n【路线数据】\n" + clip(routeResult) + "\n\n【行程数据】\n" + clip(planResult);
        String text = callModelJson(sysPrompt, userPrompt);
        TripPlanData plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        if (plan == null) {
            // 首次解析失败：带精简概览+修改指示重试一次（不整段回灌）
            String fix = "你是行程聚合器。上次结构化 JSON 解析失败。请基于【用户需求】与【数据概要】只输出一份合法 JSON 行程（字段同前），重点是 sections 每天 timeline。数据较大已截断，可省次要 detail，保留每天真实安排。\n"
                    + "【用户需求】" + req.getRaw() + "\n"
                    + "【路线数据概要】\n" + clip(routeResult, 800) + "\n"
                    + "【行程数据概要】\n" + clip(planResult, 1500);
            text = callModelJson(fix, "请输出合法 JSON 行程。");
            plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        }
        if (plan == null) {
            return null;
        }
        // 聚合评分（纯代码）：缺关键内容→带缺失提示重试1次→仍缺返回null外层兜底
        String missing = aggregateGap(plan);
        if (!missing.isBlank()) {
            EventStore.add(sessionId, "整合", "聚合结果缺失(" + missing + ")，聚合Agent重试1次...");
            String fix = "你是行程聚合器。上次聚合结果缺失：" + missing
                    + "。请重新整合【用户需求】与【数据】，补齐缺失内容，输出完整合法 JSON 行程（字段同前）。\n"
                    + "【用户需求】" + req.getRaw() + "\n"
                    + "【路线数据概要】\n" + clip(routeResult, 800) + "\n"
                    + "【行程数据概要】\n" + clip(planResult, 1500);
            String retryText = callModelJson(fix, "请补齐缺失内容，输出完整合法 JSON 行程。");
            TripPlanData retry = JsonUtils.extractAndParse(retryText, TripPlanData.class);
            if (retry != null && aggregateGap(retry).isBlank()) {
                plan = retry; // 重试补齐成功
            } else {
                // 仍缺失 → 返回null，外层降级markdown兜底
                EventStore.add(sessionId, "整合", "聚合重试后仍缺失，降级为原文兜底");
                return null;
            }
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

    /** 聚合评分：检查结构化行程缺失项（空串=合格），纯代码不调LLM。 */
    private String aggregateGap(TripPlanData plan) {
        if (plan == null) return "整体为空";
        java.util.List<String> gaps = new java.util.ArrayList<>();
        if (plan.getSections() == null || plan.getSections().isEmpty()) {
            gaps.add("sections(行程区块)为空");
        } else {
            boolean hasTimeline = plan.getSections().stream()
                    .anyMatch(s -> "timeline".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty());
            if (!hasTimeline) gaps.add("缺少每日行程timeline区块");
            boolean hasStay = plan.getSections().stream()
                    .anyMatch(s -> "stay".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty());
            if (!hasStay) gaps.add("缺少住宿餐饮(stay)区块");
        }
        if (plan.getTitle() == null || plan.getTitle().isBlank()) gaps.add("标题");
        if (plan.getDestination() == null || plan.getDestination().isBlank()) gaps.add("目的地");
        if (plan.getDays() < 1) gaps.add("天数");
        return String.join("、", gaps);
    }

    /** 3 套差异化方案并行聚合（美食向/出片向/省钱向） */
    private PlanSet aggregateMulti(TripRequest req, String routeResult, String planResult) {
        String[][] styles = {
                {"food", "美食向", "本次方案为「美食向」：以地道美食为主线，突出必吃餐厅、夜市、小吃街，行程围绕用餐地点安排，每个时段尽量给出具体餐厅/小吃推荐。"},
                {"photo", "出片向", "本次方案为「出片向」：以拍照出片为主线，突出网红打卡点、绝美风景、日出日落最佳机位，每个景点标注拍摄建议。"},
                {"budget", "省钱向", "本次方案为「省钱向」：以性价比为主线，突出免费景点、经济住宿、公共交通、平价美食，控制总预算。"},
        };
        // 并行提交 3 套聚合（PARALLEL_POOL 已 4 线程）
        @SuppressWarnings("unchecked")
        Future<TripPlanData>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures[i] = PARALLEL_POOL.submit(() ->
                    aggregateWithStyle(req, routeResult, planResult, styles[idx][2]));
        }
        PlanSet set = new PlanSet();
        int successCount = 0;
        for (int i = 0; i < 3; i++) {
            TripPlanData plan = null;
            try {
                plan = futures[i].get(180, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("方案「{}」聚合失败: {}", styles[i][1], e.getMessage());
            }
            if (plan == null) {
                plan = fallbackPlan(req, routeResult, planResult); // 该套失败退回 markdown 兜底
                plan.setTitle(req.getDestination() + req.getDays() + "日游（" + styles[i][1] + "）");
            }
            set.getPlans().add(plan);
            set.getStyles().add(styles[i][0]);
            set.getSlogans().add(styles[i][1]);
            if (plan.getSections() != null && !plan.getSections().isEmpty()) {
                successCount++;
            }
        }
        // 全部结构化失败则返回 null（调用方兜底为单方案）
        return successCount > 0 ? set : null;
    }

    /** 风格约束文案（style=null 返回空串） */
    private String buildStyleHint(String style) {
        if (style == null) {
            return "";
        }
        return "\n【风格约束】" + style + "\n";
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
        // 所有裸 LLM 调用统一走 LlmClient
        return utils.LlmClient.chatJson(sysPrompt, userPrompt);
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

    /** 聚合输入精简：超长文本截断至 maxLen，固定 prompt 规模，防上下文爆炸/超时 */
    private String clip(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n…(数据过长已截断)…";
    }

    /** 聚合输入精简：默认上限 */
    private String clip(String s) {
        return clip(s, 6000);
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
                    case "stay" -> sb.append(it.getName()).append("（").append(it.getCategory())
                            .append(it.getPrice().isBlank() ? "" : " " + it.getPrice()).append("）");
                    case "tips" -> sb.append(it.getKind()).append("：").append(it.getText());
                    default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
