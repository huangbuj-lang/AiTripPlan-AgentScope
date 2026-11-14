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
 * 在行程子服务进程内运行：为每个域构建独立的 ReAct 专家 Agent（各自工具集+域专属提示词），
 * 三域并行执行后合并为结构化候选 JSON（含来源/置信度/证据字段）。
 * 置信度门槛（PDF §十八）：域均值 < 0.70 自动带失败信号重查一次，仍低则逐候选标注 needsConfirm。
 */
@Slf4j  // @Slf4j：注入日志对象
public class DomainCandidateService {  // 类声明：三域候选专家调度服务（景点/酒店/餐饮并行）

    /** 三域并行线程池（域数固定为 3，服务长期运行不关闭） */
    private static final ExecutorService DOMAIN_POOL = Executors.newFixedThreadPool(3);  // DOMAIN_POOL：固定 3 线程（三个域各一线程）

    /** 单域 Agent 超时（秒）：域专家只需 1~2 轮工具调用，超时即降级该域 */
    private static final int DOMAIN_TIMEOUT_SEC = 120;  // DOMAIN_TIMEOUT_SEC：单域超时 120 秒

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");  // TS：时间戳格式（证据字段用）

    /**
     * 作用：三域候选编排（全量）：并发调度景点/酒店/餐饮三个域候选 Agent，合并去重后返回统一候选块
     * 参数：destination 目的地；days 天数；preference 偏好；waypoints 途经城市；brief 需求简要
     * 返回值：合并后的候选数据文本（含降级与低置信标注）
     * 异常：无（单域失败只降级该域，不影响其余域）
     */
    public static String run(String destination, String days, String preference,  // run（五参）：不带域筛选的入口
                             String waypoints, String brief) {  // 续行
        return run(destination, days, preference, waypoints, brief, null);  // 委托六参版本，onlyDomains=null（全域）
    }  // 方法结束

    /**
     * 作用：三域候选编排（指定域）：只跑 onlyDomains 列出的域，用于局部重取
     * 参数：destination 目的地；days 天数；preference 偏好；waypoints 途经城市；brief 需求简要；onlyDomains 需要执行的域名列表
     * 返回值：合并后的候选数据文本
     * 异常：无（单域失败只降级该域）
     */
    public static String run(String destination, String days, String preference,  // run（六参）：三域并行调度主流程
                             String waypoints, String brief, java.util.List<String> onlyDomains) {  // 续：onlyDomains 只跑指定域（局部重查用，null=全域）
        long t0 = System.currentTimeMillis();  // t0：开始时间
        List<String> toRun = new ArrayList<>();  // toRun：本次要跑的域
        Map<String, Object> merged = new LinkedHashMap<>();  // merged：合并后的三域结果
        for (String d : CandidateUtil.DOMAINS) {  // 遍历全部候选域
            if (onlyDomains == null || onlyDomains.isEmpty() || onlyDomains.contains(d)) toRun.add(d);  // 没有筛选或筛选命中就加入
        }  // for 结束
        if (toRun.isEmpty()) {  // 一个都没选上
            for (String d : CandidateUtil.DOMAINS) toRun.add(d); // 防御：请求域全非法时回退全域  // 兜底跑全域（防请求域全非法）
        }  // if 结束

        // 三域并行提交
        // ★span 归位修复：setSessionId 是 ThreadLocal，不跨 DOMAIN_POOL 线程——
        //   必须把 sessionId 捕获进 lambda，在各域专家线程内重新设置，
        //   否则域专家的全部 LLM/MCP span 的 sessionId 为空、调用链整体丢失（stage-test-001 实证）
        String sid = utils.SafeModel.currentSessionId();  // sid：捕获当前会话 ID（ThreadLocal 不跨线程）
        Map<String, Future<String>> futures = new LinkedHashMap<>();  // futures：域 → 任务句柄
        Map<String, String> tasks = new LinkedHashMap<>();  // tasks：域 → 任务文本（重查时复用）
        for (String domain : toRun) {  // 逐域提交
            String task = buildDomainTask(domain, destination, days, preference, waypoints, brief);  // 构造该域的任务文本
            tasks.put(domain, task);  // 记下任务文本
            futures.put(domain, DOMAIN_POOL.submit(() -> {  // 提交到域线程池
                utils.SafeModel.setSessionId(sid); // ★域专家线程内归位会话  // 在域线程内重新绑定会话 ID（否则调用链丢失）
                return runDomainAgent(domain, task);  // 跑该域专家，返回文本
            }));  // lambda 结束
        }  // for 结束

        // 收集结果（超时/异常 → 域降级标记）
        List<String> degradedDomains = new ArrayList<>();  // degradedDomains：降级的域
        List<String> lowConfDomains = new ArrayList<>();  // lowConfDomains：含低置信候选的域
        for (Map.Entry<String, Future<String>> en : futures.entrySet()) {  // 逐域收集结果
            String domain = en.getKey();  // 域名
            String out;  // out：专家输出
            try {  // try
                out = en.getValue().get(DOMAIN_TIMEOUT_SEC, TimeUnit.SECONDS);  // 等结果，最多 120 秒
            } catch (Exception e) {  // 捕获异常（超时/执行失败）
                en.getValue().cancel(true);  // 取消该任务
                log.warn("[域专家] {} 执行失败: {}", domain, e.getMessage());  // warn：执行失败
                out = "";  // 输出按空串处理
            }  // catch 结束
            Map<String, Object> node = CandidateUtil.parseDomainNode(out, domain);  // node：解析出的域节点
            if (node == null && out != null && !out.isBlank()) {  // 有输出但解不出候选
                // ★诊断日志：域专家有输出但未提取到可用候选JSON（记录头部，便于定位提示词/解析问题）
                String head = out.length() > 220 ? out.substring(0, 220) + "…" : out;  // head：输出头部（截 220 字）
                log.warn("[域专家] {} 输出无可解析候选JSON(长度{}): {}", domain, out.length(), head.replace('\n', ' '));  // warn：无可解析候选 JSON（便于定位提示词/解析问题）
            }  // if 结束
            if (node == null || CandidateUtil.degraded(node, domain)) {  // 解析失败，或专家自报降级
                // 域失败：写入降级标记
                String head = out == null ? "null"  // head：输出头部（截 300 字）
                        : (out.length() > 300 ? out.substring(0, 300) + "…" : out);  // 续行
                log.warn("[域专家] {} 降级（{}）输出头部: {}", domain,  // warn：该域降级 + 原因
                        node == null ? (out == null || out.isBlank() ? "无输出" : "无可解析候选") : "自报degraded",  // 续：原因取值
                        head.replace('\n', ' '));  // 续行
                Map<String, Object> dg = new LinkedHashMap<>();  // dg：降级节点
                dg.put("domain", domain);  // 域名
                dg.put("degraded", true);  // degraded=true
                dg.put("reason", "域专家查询失败，本域候选缺失");  // 降级原因
                merged.put(domain, dg);  // 写入合并结果
                degradedDomains.add(domain);  // 记入降级列表
                continue;  // 跳过本域后续处理
            }  // if 结束
            // 置信度门槛（PDF §十八）：均值<0.70 带失败信号重查一次
            double avg = CandidateUtil.avgConfidence(CandidateUtil.items(node, domain));  // avg：本域候选平均置信度
            if (avg >= 0 && avg < 0.70) {  // 均值低于 0.70
                String retry = runDomainAgent(domain, tasks.get(domain)  // 带“上次置信度太低”提示重查一次
                        + "\n★上一轮候选平均置信度仅 " + String.format("%.2f", avg)  // 续：告知阈值与要求
                        + "（阈值0.70）。请更换关键词/换数据源重新查询，确保候选主要来自真实工具数据；若仍需依赖常识请如实降低 confidence。");  // 续：要求换关键词或换数据源
                Map<String, Object> node2 = CandidateUtil.parseDomainNode(retry, domain);  // 解析重查结果
                if (node2 != null && !CandidateUtil.degraded(node2, domain)) {  // 重查有效
                    double avg2 = CandidateUtil.avgConfidence(CandidateUtil.items(node2, domain));  // 算新均值
                    if (avg2 > avg) node = node2;  // 更好才替换
                }  // if 结束
            }  // if 结束
            // 低置信候选逐条标注（PDF §十八：<0.70 提示"出发前确认"；域均值<0.70 已在上方触发重查）
            if (CandidateUtil.markNeedsConfirm(node, domain)) {  // 给低置信候选逐条打“建议出发前确认”
                lowConfDomains.add(domain);  // 记入低置信域
            }  // if 结束
            // 证据字段：数据来自工具链（高德API+本地知识库），分级 A（S 级预留官方站点）
            CandidateUtil.attachEvidence(node, "amap+local_db", "A", LocalDateTime.now().format(TS));  // 附证据字段（来源 amap+local_db、等级 A、时间）
            merged.put(domain, node.get(domain)); // 存内层域节点（parseDomainNode 返回包裹形）  // 只存内层域节点（parseDomainNode 返回的是包裹形）
        }  // for 结束

        String json = JsonUtils.toJson(merged);  // json：三域候选 JSON
        String digest = buildDigest(merged, degradedDomains, lowConfDomains);  // digest：给人看的摘要
        utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "domain_candidates",  // 记调用链：TOOL 层
                "三域并行候选完成: " + String.join("/", futures.keySet())  // 续：参与的域
                        + (degradedDomains.isEmpty() ? "" : " · 降级:" + degradedDomains)  // 续：降级域
                        + " · 耗时" + (System.currentTimeMillis() - t0) + "ms",  // 续：耗时
                degradedDomains.size() < toRun.size(), System.currentTimeMillis() - t0);  // 续：只要有域成功就算成功
        // ★观测代码化：候选 JSON 进程级存档（按 sessionId），主管收割在回复文本提取不到块时经
        //   /candidate-block 端点代码回取——结构化候选传递不再依赖门面 LLM 把块原样抄进回复
        utils.CandidateBlockStore.put(utils.SafeModel.lastTraceId(), json);  // 候选 JSON 进程级存档（主管可经 /candidate-block 回取）
        return digest + "\n\n【域候选JSON】\n" + json + "\n【域候选JSON结束】";  // 返回：摘要 + 【域候选JSON】块
    }  // 方法结束

    /**
     * 作用：调用单个域候选 Agent 并收集其候选数据
     * 参数：domain 域名（attraction/hotel/dining）；task 该域任务描述
     * 返回值：该域候选数据文本；失败返回空串
     * 异常：无（异常内部捕获）
     */
    private static String runDomainAgent(String domain, String task) {  // runDomainAgent：跑单个域专家
        try {  // try
            utils.SafeModel.setStage("域专家·" + domainLabel(domain)); // ★调用链域标注  // 标记当前阶段（调用链域标注）
            String name = switch (domain) {  // name：Agent 名
                case "attraction" -> "AttractionAgent";  // 景点 → AttractionAgent
                case "hotel" -> "HotelAgent";  // 酒店 → HotelAgent
                case "dining" -> "DiningAgent";  // 餐饮 → DiningAgent
                default -> domain + "Agent";  // 其它 → xxxAgent
            };  // switch 结束
            String prompt = switch (domain) {  // prompt：该域系统提示词
                case "attraction" -> AgentPrompts.domainAttractionAgent();  // 景点提示词
                case "hotel" -> AgentPrompts.domainHotelAgent();  // 酒店提示词
                case "dining" -> AgentPrompts.domainDiningAgent();  // 餐饮提示词
                default -> AgentPrompts.domainAttractionAgent();  // 兜底用景点提示词
            };  // switch 结束
            Toolkit toolkit = new Toolkit();  // toolkit：新建工具包
            AmapEnhancedToolFactory.register(toolkit, switch (domain) {  // 按域类型装配高德工具
                case "attraction" -> AmapEnhancedToolFactory.AgentType.ATTRACTION;  // 景点 → 搜索 + 详情
                case "hotel" -> AmapEnhancedToolFactory.AgentType.HOTEL;  // 酒店 → 搜索
                default -> AmapEnhancedToolFactory.AgentType.DINING;  // 餐饮 → 搜索
            });  // register 调用结束
            ReActAgent agent = AgentUtils.getReActAgentBuilder(name, domain + " 域候选专家")  // 构建 ReAct Agent
                    .sysPrompt(prompt)  // 绑定该域系统提示词
                    .toolkit(toolkit)  // 绑定工具包
                    .maxIters(6) // 域专家任务单一：查数→按任务指定数量选候选，不需要长链路  // 最多 6 轮（域任务单一，不需要长链路）
                    .build();  // 构建完成
            long agentStart = System.currentTimeMillis();  // agentStart：开始时间
            Msg resp = agent.call(  // 同步调用 Agent
                    Msg.builder().role(MsgRole.USER)  // 构造用户消息
                            .content(TextBlock.builder().text(task).build())  // 内容 = 任务文本
                            .build()  // 消息构造结束
            ).block(Duration.ofSeconds(DOMAIN_TIMEOUT_SEC));  // 阻塞等结果，超时 120 秒
            String text = resp == null ? "" : resp.getTextContent();  // 取文本（响应为空归空串）
            log.info("[域专家] {} 完成: 耗时{}ms 输出{}字符",  // info 日志：完成耗时与输出长度
                    domain, System.currentTimeMillis() - agentStart, text == null ? -1 : text.length());  // 续行
            if (text == null || text.isBlank()) {  // 回复为空
                log.warn("[域专家] {} 空回复（模型未产出文本内容）", domain);  // warn：模型没产出文本
            }  // if 结束
            return text;  // 返回文本
        } catch (Exception e) {  // 捕获异常
            log.warn("[域专家] {} Agent 异常: {}", domain, e.getMessage());  // warn：Agent 异常
            return "";  // 返回空串（上层按降级处理）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：构造单个域候选 Agent 的任务描述（限定该域只输出本域候选）
     * 参数：domain 域名；destination 目的地；days 天数；preference 偏好；waypoints 途经城市；brief 需求简要
     * 返回值：任务描述文本
     * 异常：无
     */
    private static String buildDomainTask(String domain, String destination, String days, String preference,  // buildDomainTask：构造单域任务文本
                                          String waypoints, String brief) {  // 续行
        StringBuilder sb = new StringBuilder();  // sb：任务缓冲
        if (brief != null && !brief.isBlank()) {  // 有 TravelState 状态块
            sb.append(brief.trim()).append("\n\n");  // 原样附上（作为权威快照）
        }  // if 结束
        sb.append("【本域任务】查询 ").append(domainLabel(domain)).append("域 Top")  // 本域任务：查 X 域 Top
          .append(utils.CandidateUtil.candidateCountPerDomain(days)).append(" 候选。\n");  // 续：候选数量（随天数放大）
        sb.append("目的地: ").append(destination == null || destination.isBlank() ? "未提供" : destination).append("\n");  // 目的地
        if (days != null && !days.isBlank()) sb.append("天数: ").append(days).append("\n");  // 有天数就写
        if (preference != null && !preference.isBlank()) sb.append("行程偏好: ").append(preference).append("\n");  // 有偏好就写
        if (waypoints != null && !waypoints.isBlank()) {  // 有途经城市
            sb.append("跨城沿途范围: ").append(waypoints)  // 跨城沿途范围 + 查询要求
              .append("（景点/餐饮域请用 query_trip 查询该路线【沿途】的景点/餐饮分布取候选，覆盖沿途停靠点，不要只查两端城市；酒店域查终点城市）\n");  // 续：景点/餐饮查沿途，酒店查终点城市
        }  // if 结束
        return sb.toString();  // 返回任务文本
    }  // 方法结束

    /**
     * 作用：生成候选摘要块：标注各域条数、降级域与低置信项
     * 参数：merged 合并后的候选数据；degraded 降级域名列表；lowConf 低置信域名列表
     * 返回值：摘要文本
     * 异常：无
     */
    private static String buildDigest(Map<String, Object> merged, List<String> degraded,  // buildDigest：生成人读的三域摘要
                                      List<String> lowConf) {  // 续行
        StringBuilder sb = new StringBuilder();  // sb：摘要缓冲
        sb.append("【三域候选获取】\n");  // 标题
        for (String d : CandidateUtil.DOMAINS) {  // 逐个域输出
            Map<String, Object> node = CandidateUtil.domain(merged, d);  // 该域的节点
            List<Map<String, Object>> items = CandidateUtil.items(merged, d);  // 该域的条目
            sb.append("\n◆").append(CandidateUtil.domainLabel(d));  // ◆域名
            if (node == null || items.isEmpty()) {  // 没有候选
                sb.append(": 无候选（").append(node == null ? "缺数据" : str(node.get("reason"))).append("）\n");  // 说明缺失原因
                continue;  // 跳过该域
            }  // if 结束
            double avg = CandidateUtil.avgConfidence(items);  // 平均置信度
            sb.append("（").append(items.size()).append("条 均值置信度")  // （条数 + 
              .append(avg < 0 ? "未知" : String.format("%.2f", avg)).append("）\n");  // 续：均值置信度）
            for (Map<String, Object> it : items) {  // 逐条输出
                sb.append("- ").append(str(it.get("name")));  // - 名称
                String price = str(it.get("price"));  // 价格
                if (!price.isBlank()) sb.append("（").append(price).append("）");  // 有价格就带括号
                String area = str(it.get("area"));  // 区域
                if (!area.isBlank()) sb.append(" @").append(area);  // 有区域就带 @
                String reason = str(it.get("reason"));  // 推荐理由
                if (!reason.isBlank()) sb.append(" — ").append(reason);  // 有理由就用 — 引出
                if (Boolean.TRUE.equals(it.get("needsConfirm"))) sb.append(" ⚠建议出发前确认");  // 需要确认的加 ⚠ 提示
                sb.append("\n");  // 换行
            }  // for 结束
        }  // for 结束
        if (!degraded.isEmpty()) {  // 有降级域
            sb.append("\n⚠️ 降级域: ").append(String.join("、", degraded))  // 写明降级域
              .append("（规划时该域如实标注数据缺失，禁止编造）\n");  // 续：要求如实标注、禁止编造
        }  // if 结束
        if (!lowConf.isEmpty()) {  // 有低置信域
            sb.append("⚠️ 低置信域: ").append(String.join("、", lowConf))  // 写明低置信域
              .append("（相关候选已标注，输出中注明\"建议出发前再次确认\"）\n");  // 续：要求输出注明“建议出发前再次确认”
        }  // if 结束
        return sb.toString();  // 返回摘要
    }  // 方法结束

    /**
     * 作用：域名转中文标签
     * 参数：domain 域名
     * 返回值：中文标签（景点/酒店/餐饮）
     * 异常：无
     */
    private static String domainLabel(String domain) {  // domainLabel：域的中文名
        return CandidateUtil.domainLabel(domain);  // 委托 CandidateUtil
    }  // 方法结束
    /**
     * 作用：安全转字符串
     * 参数：o 任意对象
     * 返回值：对象字符串；null 返回空串
     * 异常：无
     */

    private static String str(Object o) {  // str：null 安全字符串
        return o == null ? "" : String.valueOf(o);  // null 归空串
    }  // 方法结束

    /**
     * 作用：从需求对象生成一行需求简要（供候选 Agent 使用）
     * 参数：req 需求对象
     * 返回值：需求简要文本
     * 异常：无
     */
    public static String briefOf(TripRequest req) {  // briefOf：把需求转成 TravelState 状态块文本
        return req == null ? "" : req.toStateBrief();  // 需求为空返回空串
    }  // 方法结束
}  // 类结束
