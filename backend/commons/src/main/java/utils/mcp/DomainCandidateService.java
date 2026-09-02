package utils.mcp;

import dto.TripRequest;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import utils.AgentPrompts;
import utils.AgentUtils;
import utils.CandidateUtil;
import utils.Config;
import utils.JsonUtils;
import utils.LimitedMemory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 三域候选并行调度服务（PDF §一/§五：景点/酒店/餐饮并行取候选；§十七：域间不自由聊天，结果结构化汇聚）。
 * 在行程子服务进程内运行：每个域持有【常驻】ReAct 专家 Agent（各自工具集+域专属提示词，首次使用时构建、跨请求复用），
 * 三域并行执行后合并为结构化候选 JSON（含来源/置信度/证据字段）。
 * 置信度门槛（PDF §十八）：域均值 < 0.70 自动带失败信号重查一次，仍低则逐候选标注 needsConfirm。
 * Agent 生命周期： LIMITED_MEMORY 轮转记忆防跨请求串上下文（域任务自带完整 TravelState 简报，Agent 本身无状态）。
 */
@Slf4j
public class DomainCandidateService {

    /** 三域并行线程池（域数固定为 3，服务长期运行不关闭） */
    private static final ExecutorService DOMAIN_POOL = Executors.newFixedThreadPool(3);

    /** 常驻域专家池：domain → Agent+记忆（懒构建，进程生命周期内复用）。
     *  并发安全：同域由 DOMAIN_LOCKS 互斥串行（防两请求并发调用同一实例造成上下文串），
     *  跨请求隔离：每次任务前 memory.clear()（任务文本自带完整 TravelState 简报，Agent 本身无状态）。 */
    private static final Map<String, ReActAgent> AGENT_POOL = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每域互斥锁：保证同一域任一时刻只有一个任务在跑（跨请求并发时同域排队） */
    private static final Map<String, Object> DOMAIN_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每域 Agent 的记忆实例（与 AGENT_POOL 同生命周期，任务前 clear 用） */
    private static final Map<String, LimitedMemory> DOMAIN_MEMORY = new java.util.concurrent.ConcurrentHashMap<>();

    /** 单域 Agent 超时（秒）：域专家只需 1~2 轮工具调用，超时即降级该域 */
    private static final int DOMAIN_TIMEOUT_SEC = 120;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 并行调度三域候选，返回【可读摘要 + 域候选JSON块】（门面Agent直接作为工具结果消费）。
     *
     * @param destination 目的地城市
     * @param days        天数（提示用）
     * @param preference  行程偏好
     * @param waypoints   跨城途经城市（逗号分隔，空=单城）
     * @param brief       TravelState 状态简报（任务文本权威快照，域专家围绕它决策）
     */
    public static String run(String destination, String days, String preference,
                             String waypoints, String brief) {
        long t0 = System.currentTimeMillis();
        List<String> toRun = new ArrayList<>();
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String d : CandidateUtil.DOMAINS) toRun.add(d);

        // 三域并行提交
        Map<String, Future<String>> futures = new LinkedHashMap<>();
        Map<String, String> tasks = new LinkedHashMap<>();
        for (String domain : toRun) {
            String task = buildDomainTask(domain, destination, days, preference, waypoints, brief);
            tasks.put(domain, task);
            futures.put(domain, DOMAIN_POOL.submit(() -> runDomainAgent(domain, task)));
        }

        // 收集结果（超时/异常 → 域降级标记）
        List<String> degradedDomains = new ArrayList<>();
        List<String> lowConfDomains = new ArrayList<>();
        for (Map.Entry<String, Future<String>> en : futures.entrySet()) {
            String domain = en.getKey();
            String out;
            try {
                out = en.getValue().get(DOMAIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (Exception e) {
                en.getValue().cancel(true);
                log.warn("[域专家] {} 执行失败: {}", domain, e.getMessage());
                out = "";
            }
            Map<String, Object> node = CandidateUtil.parseDomainNode(out, domain);
            if (node == null && out != null && !out.isBlank()) {
                // ★诊断日志：域专家有输出但未提取到可用候选JSON（记录头部，便于定位提示词/解析问题）
                String head = out.length() > 220 ? out.substring(0, 220) + "…" : out;
                log.warn("[域专家] {} 输出无可解析候选JSON(长度{}): {}", domain, out.length(), head.replace('\n', ' '));
            }
            if (node == null || CandidateUtil.degraded(node, domain)) {
                // 域失败：写入降级标记
                String head = out == null ? "null"
                        : (out.length() > 300 ? out.substring(0, 300) + "…" : out);
                log.warn("[域专家] {} 降级（{}）输出头部: {}", domain,
                        node == null ? (out == null || out.isBlank() ? "无输出" : "无可解析候选") : "自报degraded",
                        head.replace('\n', ' '));
                Map<String, Object> dg = new LinkedHashMap<>();
                dg.put("domain", domain);
                dg.put("degraded", true);
                dg.put("reason", "域专家查询失败，本域候选缺失");
                merged.put(domain, dg);
                degradedDomains.add(domain);
                continue;
            }
            // 置信度门槛（PDF §十八）：均值<0.70 带失败信号重查一次
            double avg = CandidateUtil.avgConfidence(CandidateUtil.items(node, domain));
            if (avg >= 0 && avg < 0.70) {
                String retry = runDomainAgent(domain, tasks.get(domain)
                        + "\n★上一轮候选平均置信度仅 " + String.format("%.2f", avg)
                        + "（阈值0.70）。请更换关键词/换数据源重新查询，确保候选主要来自真实工具数据；若仍需依赖常识请如实降低 confidence。");
                Map<String, Object> node2 = CandidateUtil.parseDomainNode(retry, domain);
                if (node2 != null && !CandidateUtil.degraded(node2, domain)) {
                    double avg2 = CandidateUtil.avgConfidence(CandidateUtil.items(node2, domain));
                    if (avg2 > avg) node = node2;
                }
            }
            // 低置信候选逐条标注（PDF §十八：<0.70 提示"出发前确认"；域均值<0.70 已在上方触发重查）
            if (CandidateUtil.markNeedsConfirm(node, domain)) {
                lowConfDomains.add(domain);
            }
            // 证据字段：数据来自工具链（高德API+本地知识库），分级 A（S 级预留官方站点）
            CandidateUtil.attachEvidence(node, "amap+local_db", "A", LocalDateTime.now().format(TS));
            merged.put(domain, node.get(domain)); // 存内层域节点（parseDomainNode 返回包裹形）
        }

        String json = JsonUtils.toJson(merged);
        String digest = buildDigest(merged, degradedDomains, lowConfDomains);
        utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "domain_candidates",
                "三域并行候选完成: " + String.join("/", futures.keySet())
                        + (degradedDomains.isEmpty() ? "" : " · 降级:" + degradedDomains)
                        + " · 耗时" + (System.currentTimeMillis() - t0) + "ms",
                degradedDomains.size() < toRun.size(), System.currentTimeMillis() - t0);
        return digest + "\n\n【域候选JSON】\n" + json + "\n【域候选JSON结束】";
    }

    /** 单域专家 Agent 获取（常驻池懒构建）：首次构建后跨请求复用，节省 build/挂工具/加载提示词开销 */
    private static ReActAgent pooledAgent(String domain, String name, String prompt) {
        return AGENT_POOL.computeIfAbsent(domain, d -> {
            long t0 = System.currentTimeMillis();
            Toolkit toolkit = new Toolkit();
            AmapEnhancedToolFactory.register(toolkit, switch (d) {
                case "attraction" -> AmapEnhancedToolFactory.AgentType.ATTRACTION;
                case "hotel" -> AmapEnhancedToolFactory.AgentType.HOTEL;
                default -> AmapEnhancedToolFactory.AgentType.DINING;
            });
            LimitedMemory memory = new LimitedMemory(8); // 任务内对话轮转窗口（每次任务前 clear，轮转仅兜底防工具循环撑爆）
            DOMAIN_MEMORY.put(d, memory);
            ReActAgent agent = AgentUtils.getReActAgentBuilder(name, d + " 域候选专家")
                    .sysPrompt(prompt)
                    .memory(memory)
                    .toolkit(toolkit)
                    .maxIters(6) // 域专家任务单一：查数→选Top3，不需要长链路
                    .build();
            log.info("[域专家池] {} 常驻Agent首次构建完成（{}ms），后续请求复用", d, System.currentTimeMillis() - t0);
            return agent;
        });
    }

    /** 单域专家 Agent 执行（常驻池复用 + 同域互斥 + 任务前清记忆；阻塞至最终回复） */
    private static String runDomainAgent(String domain, String task) {
        Object lock = DOMAIN_LOCKS.computeIfAbsent(domain, k -> new Object());
        synchronized (lock) { // 同域互斥：跨请求并发时同域排队，绝不并发驱动同一实例
            try {
                String name = switch (domain) {
                    case "attraction" -> "AttractionAgent";
                    case "hotel" -> "HotelAgent";
                    case "dining" -> "DiningAgent";
                    default -> domain + "Agent";
                };
                String prompt = switch (domain) {
                    case "attraction" -> AgentPrompts.domainAttractionAgent();
                    case "hotel" -> AgentPrompts.domainHotelAgent();
                    case "dining" -> AgentPrompts.domainDiningAgent();
                    default -> AgentPrompts.domainAttractionAgent();
                };
                ReActAgent agent = pooledAgent(domain, name, prompt);
                LimitedMemory memory = DOMAIN_MEMORY.get(domain);
                if (memory != null) {
                    memory.clear(); // ★跨请求隔离：上一请求的对话/输出不进本次上下文
                }
                long agentStart = System.currentTimeMillis();
                Msg resp = agent.call(
                        Msg.builder().role(MsgRole.USER)
                                .content(TextBlock.builder().text(task).build())
                                .build()
                ).block(Duration.ofSeconds(DOMAIN_TIMEOUT_SEC));
                String text = resp == null ? "" : resp.getTextContent();
                log.info("[域专家] {} 完成: 耗时{}ms 输出{}字符",
                        domain, System.currentTimeMillis() - agentStart, text == null ? -1 : text.length());
                if (text == null || text.isBlank()) {
                    log.warn("[域专家] {} 空回复（模型未产出文本内容）", domain);
                }
                return text;
            } catch (Exception e) {
                log.warn("[域专家] {} Agent 异常（锁内丢弃重建，重试一次）: {}", domain, e.getMessage());
                // 常驻实例异常（如上下文状态卡死）→ 在锁内丢弃重建一次，仍失败则降级该域
                try {
                    AGENT_POOL.remove(domain);
                    DOMAIN_MEMORY.remove(domain);
                    ReActAgent fresh = pooledAgent(domain,
                            switch (domain) {
                                case "attraction" -> "AttractionAgent";
                                case "hotel" -> "HotelAgent";
                                case "dining" -> "DiningAgent";
                                default -> domain + "Agent";
                            },
                            switch (domain) {
                                case "attraction" -> AgentPrompts.domainAttractionAgent();
                                case "hotel" -> AgentPrompts.domainHotelAgent();
                                case "dining" -> AgentPrompts.domainDiningAgent();
                                default -> AgentPrompts.domainAttractionAgent();
                            });
                    LimitedMemory memory2 = DOMAIN_MEMORY.get(domain);
                    if (memory2 != null) memory2.clear();
                    Msg resp = fresh.call(
                            Msg.builder().role(MsgRole.USER)
                                    .content(TextBlock.builder().text(task).build())
                                    .build()
                    ).block(Duration.ofSeconds(DOMAIN_TIMEOUT_SEC));
                    String text2 = resp == null ? "" : resp.getTextContent();
                    log.info("[域专家] {} 重建后完成: 输出{}字符", domain, text2 == null ? -1 : text2.length());
                    return text2;
                } catch (Exception e2) {
                    log.warn("[域专家] {} 重建后仍异常: {}", domain, e2.getMessage());
                    return "";
                }
            }
        }
    }

    /** 域专家任务文本：状态简报 + 域职责 + 输出契约提示 */
    private static String buildDomainTask(String domain, String destination, String days, String preference,
                                          String waypoints, String brief) {
        StringBuilder sb = new StringBuilder();
        if (brief != null && !brief.isBlank()) {
            sb.append(brief.trim()).append("\n\n");
        }
        sb.append("【本域任务】查询 ").append(domainLabel(domain)).append("域 Top3 候选。\n");
        sb.append("目的地: ").append(destination == null || destination.isBlank() ? "未提供" : destination).append("\n");
        if (days != null && !days.isBlank()) sb.append("天数: ").append(days).append("\n");
        if (preference != null && !preference.isBlank()) sb.append("行程偏好: ").append(preference).append("\n");
        if (waypoints != null && !waypoints.isBlank()) {
            sb.append("跨城途经城市: ").append(waypoints)
              .append("（景点/餐饮域请对每个途经城市分别查询；酒店域查终点城市）\n");
        }
        return sb.toString();
    }

    /** 工具结果可读摘要（门面 Agent 据此撰写行程；JSON 块原样透传给主管） */
    private static String buildDigest(Map<String, Object> merged, List<String> degraded,
                                      List<String> lowConf) {
        StringBuilder sb = new StringBuilder();
        sb.append("【三域候选获取】\n");
        for (String d : CandidateUtil.DOMAINS) {
            Map<String, Object> node = CandidateUtil.domain(merged, d);
            List<Map<String, Object>> items = CandidateUtil.items(merged, d);
            sb.append("\n◆").append(CandidateUtil.domainLabel(d));
            if (node == null || items.isEmpty()) {
                sb.append(": 无候选（").append(node == null ? "缺数据" : str(node.get("reason"))).append("）\n");
                continue;
            }
            double avg = CandidateUtil.avgConfidence(items);
            sb.append("（").append(items.size()).append("条 均值置信度")
              .append(avg < 0 ? "未知" : String.format("%.2f", avg)).append("）\n");
            for (Map<String, Object> it : items) {
                sb.append("- ").append(str(it.get("name")));
                String price = str(it.get("price"));
                if (!price.isBlank()) sb.append("（").append(price).append("）");
                String area = str(it.get("area"));
                if (!area.isBlank()) sb.append(" @").append(area);
                String reason = str(it.get("reason"));
                if (!reason.isBlank()) sb.append(" — ").append(reason);
                if (Boolean.TRUE.equals(it.get("needsConfirm"))) sb.append(" ⚠建议出发前确认");
                sb.append("\n");
            }
        }
        if (!degraded.isEmpty()) {
            sb.append("\n⚠️ 降级域: ").append(String.join("、", degraded))
              .append("（规划时该域如实标注数据缺失，禁止编造）\n");
        }
        if (!lowConf.isEmpty()) {
            sb.append("⚠️ 低置信域: ").append(String.join("、", lowConf))
              .append("（相关候选已标注，输出中注明\"建议出发前再次确认\"）\n");
        }
        return sb.toString();
    }

    /** 域中文名（域任务文本用） */
    private static String domainLabel(String domain) {
        return CandidateUtil.domainLabel(domain);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 便捷构造：从 TripRequest 派生默认 brief（跨服务复用） */
    public static String briefOf(TripRequest req) {
        return req == null ? "" : req.toStateBrief();
    }
}
