package managerAgent.tool;

import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import utils.CallTrace;
import managerAgent.hook.EventStore;
import utils.NacosUtil;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将远程Agent封装为工具供主管调用
 */
@Slf4j  // @Slf4j：生成 log
public class RemoteAgentTool {  // 类声明：RemoteAgentTool

    private String sessionId = "default"; // 会话ID（结果事件按会话隔离）  // 实例字段 sessionId，默认 default：调用结果事件与调用链都按它隔离

    /**
     * 作用：设置本工具实例的会话 ID，调用结果事件与调用链按会话隔离
     * 参数：sessionId 会话 ID，默认 default
     * 返回值：无
     * 异常：无
     */
    public void setSessionId(String sessionId) {  // setSessionId 签名
        this.sessionId = sessionId;  // 赋值（TripOrchestrator 构造时调用）
    }  // 方法结束

    // ★2026-09-05 删缓存：同一 A2aAgent 实例并发 call 会抛 "Agent is still running"（多方案3套并行定稿=2/3套降级兜底的根因）。
    //   A2aAgent 为协议层轻对象（Nacos 连接池在 NacosUtil 单例共享），每次调用新建独立实例成本可接受，换来并发安全。
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(300); // ★2026-08-24 云端240s→300s: 路线Agent多轮工具调用常超240s  // REMOTE_TIMEOUT=300 秒：常量里留的默认预算（实际用 RemoteTimeout 动态值，本常量仅作参考）

    /**
     * 作用：单次"阻塞等待"窗口（秒）——到点就不再死等，转入轮询回捞（pollRefetch）。
     * 为什么：实测子 Agent 早已跑完（子 Agent 日志有 Agent execution completed successfully、
     *        结果已存 /lastResult），但主管的阻塞读要等满 RemoteTimeout（默认 300s）才抛超时，
     *        然后靠回捞把结果捡回来 —— 等于每天白等 300s，3 天就是 5~15 分钟的总耗时。
     *        把"死等"改成"短等 + 边等边捞"后，慢任务在子 Agent 跑完的瞬间就能拿到结果。
     * 取值：默认 45 秒；可用环境变量/配置 A2A_READ_WINDOW_SECONDS 覆盖，
     *       实际使用时会被夹在 [15 秒, 远程超时预算] 之间；小于 45 秒的快速任务行为完全不变。
     */
    private static final int A2A_READ_WINDOW_SECONDS = utils.Config.getInt("A2A_READ_WINDOW_SECONDS", 300);  // ★A/B 实验：窗口回到 300 秒（与旧版一致），单独隔离“流式 vs 非流式”这一个变量

    /**
     * 作用：算出本次阻塞等待真正给多久。
     * 参数：budgetMs —— 本次 attempt 的剩余预算（毫秒，来自 RemoteTimeout 动态值）
     * 返回值：阻塞窗口毫秒数，取「窗口配置」与「剩余预算」的较小者，且不低于 5 秒（避免配置写错导致 0 等待）
     * 为什么单独抽方法：窗口配置和预算都不是常量（Nacos 可热更），调用点只需一行，判定集中在一处。
     */
    private static long readWindowMs(long budgetMs) {  // readWindowMs：阻塞窗口 = min(配置窗口, 剩余预算)
        long configured = java.util.concurrent.TimeUnit.SECONDS.toMillis(A2A_READ_WINDOW_SECONDS);  // configured：配置的窗口毫秒数
        return Math.max(5_000L, Math.min(budgetMs, configured));  // 下限 5 秒，防止预算/配置异常导致立即超时
    }  // readWindowMs 结束

    // ==================== 子Agent健康统计（管理端展示） ====================
    /** 按 agent 名累计：总调用次数 / 失败次数 / 累计耗时ms（进程内存值） */
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_CALLS = new java.util.concurrent.ConcurrentHashMap<>();  // AGENT_CALLS：每个 Agent 的调用总次数
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_FAILS = new java.util.concurrent.ConcurrentHashMap<>();  // AGENT_FAILS：失败次数
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_COST_MS = new java.util.concurrent.ConcurrentHashMap<>();  // AGENT_COST_MS：累计耗时毫秒

    /**
     * 作用：记录一次远程调用的健康统计（总次数/失败次数/累计耗时）
     * 参数：name Agent 名称；success 是否成功；costMs 本次耗时毫秒
     * 返回值：无
     * 异常：无
     */
    private static void recordCall(String name, boolean success, long costMs) {  // recordCall 签名
        AGENT_CALLS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();  // 调用总次数 +1（computeIfAbsent 保证首次创建）
        if (!success) {  // 失败时
            AGENT_FAILS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();  // 失败次数 +1
        }  // if 结束
        AGENT_COST_MS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(costMs);  // 累计耗时加上本次
    }  // 方法结束

    /**
     * 作用：把 A2A 失败原文归类成可读原因（超时/空返回/连接失败等），用于事件与调用链
     * 参数：raw 原始失败信息
     * 返回值：中文原因标签
     * 异常：无
     */
    static String classifyFail(String raw) {  // classifyFail 签名：包级可见（同包测试可用）
        return utils.SafeModel.classifyErrorText(raw);  // 委托给 SafeModel.classifyErrorText 统一归类（超时/402/断流/限流…）
    }  // 方法结束

    /**
     * 作用：静默 HTTP GET（用于回捞子 Agent 的结果，失败返回空串不抛异常）
     * 参数：url 目标地址；timeoutMs 超时毫秒
     * 返回值：响应体文本；失败返回空串
     * 异常：无
     */
    private static String httpGetQuiet(String url, int timeoutMs) {  // httpGetQuiet 签名
        try {  // try
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(  // 新建 HttpClient 发 GET，超时由 timeoutMs 控制
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))  // 构造请求：URI + 超时 + GET
                            .timeout(Duration.ofMillis(timeoutMs)).GET().build(),  // 继续参数续行
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));  // 响应体按 UTF-8 读成字符串
            return r.statusCode() == 200 ? r.body() : null;  // 只有 200 返回 body，其他状态返回 null
        } catch (Exception e) {  // 捕获任何异常
            return null;  // 失败一律返回 null（调用方据此判定“没捞到”）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：回捞子 Agent 最近一次任务结果：A2A 超时/空返回时直接向子 Agent 的结果接口取
     * 参数：agentName Agent 名称；sessionId 会话 ID；task 原任务文本（用于匹配结果键）；sinceMs 只接受该时间之后的结果
     * 返回值：回捞到的结果文本；取不到返回空串
     * 异常：无
     */
    private static String refetchLastResult(String agentName, String sessionId, String task, long sinceMs) {  // refetchLastResult 签名：静态，不依赖实例状态
        try {  // try
            if (task == null || task.isBlank()) return null;  // 任务为空就无法算指纹，直接放弃
            String base = utils.AgentEndpoints.forAgent(agentName);  // 取该 Agent 的 base 地址（AgentEndpoints 统一配置）
            if (base == null || base.isBlank()) return null;  // 地址取不到就放弃
            String[] keys = {  // 准备三个候选指纹（按优先级）
                    // ★关键：子Agent 是按【收到的原文】存指纹的，而主管发出去的是带 trace 前缀的任务
                    // （attempt1）或带重试提示的任务（attempt2）——当初回捞拿"裸 task"去查，两边算的不是
                    // 同一个字符串，必然全部 miss（实测：存档 3 条、回捞 3 次全 hit=false）。
                    utils.LastResultStore.keyOf("【trace:" + sessionId + "】" + task),  // 指纹 1：带【trace:sessionId】前缀的原文（第 1 次发送的形态）
                    utils.LastResultStore.keyOf(task  // 指纹 2：原文 + 重试提示（第 2 次发送的形态）
                            + "\n\n【重试提示】上一次调用超时或返回空。请务必调用你的工具完成查询并输出完整结果。"),  // 续行：重试提示的固定文案必须与 callRemoteAgent 里逐字一致
                    utils.LastResultStore.keyOf(task) // 兼容：万一将来发送文本又变了  // 指纹 3：裸 task（兼容将来发送文本又变了）
            };  // 数组结束
            // ★诊断日志：以前回捞是静默的，失败时无法判断"到底跑没跑、为什么没命中"
            StringBuilder kb = new StringBuilder();  // 拼一个 StringBuilder 打印所有 key
            for (String k : keys) kb.append(k).append(' ');  // 逐个 key 追加空格分隔
            org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 打 warn 日志：
                    .warn("[回捞] {} 开始回捞 keys=[{}] sinceMs={}", agentName, kb.toString().trim(), sinceMs);  // 输出“开始回捞 + keys + sinceMs”
            // ★诊断（关键）：把"主管实际发出去的文本"也打出来（长度 + 首尾），
            // 与子Agent 侧 [lastResult指纹] 日志逐字比对，确认两边是否同一个字符串。
            String sentText = "【trace:" + sessionId + "】" + task;  // 重建成实际发送的文本（trace 前缀 + 原文）
            org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 日志调用：
                    .warn("[回捞指纹] 发出的任务原文: len={} 首=<<{}>> 尾=<<{}>>", sentText.length(),  // 输出长度 + 前 60 字符
                            sentText.length() > 60 ? sentText.substring(0, 60).replace('\n', '⏎') : sentText.replace('\n', '⏎'),  // 首部：超过 60 就截前 60，换行替换成 ⏎
                            sentText.length() > 60 ? sentText.substring(sentText.length() - 40).replace('\n', '⏎') : "");  // 尾部：超过 60 就取最后 40 字符，换行替换成 ⏎
            for (String k : keys) {  // 逐个 key 尝试
                String r = fetchResultField(base + "/lastResult?key=" + k);  // 请求 /lastResult?key=…，取 result 字段
                if (r != null) {  // 命中（非 null）
                    org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 打日志：
                            .warn("[回捞] {} 命中(key={}) {} 字符", agentName, k, r.length());  // 输出命中 key 与结果长度
                    return r;  // 直接返回结果
                }  // if 结束
            }  // for 结束
            String r = fetchResultField(base + "/lastResult?sinceMs=" + sinceMs); // 兜底  // 都没命中：用 sinceMs 兜底（取该时间之后最后一条结果）
            org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 打日志：
                    .warn("[回捞] {} 未命中（子Agent 无该任务结果，或本次它自己也没跑完）", agentName);  // 说明“未命中（子 Agent 无该任务结果，或它自己也没跑完）”
            if (r != null) {  // 兜底也没拿到（null）
                org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 打日志：
                        .warn("[回捞] {} 兜底 sinceMs 命中({} 字符)", agentName, r.length());  // 输出 sinceMs 命中的长度
            }  // if 结束
            return r;  // 返回兜底结果（可能为 null）
        } catch (Exception e) {  // 任何异常
            return null; // 回捞失败静默：不能因补救失败影响主流程  // 返回 null：回捞失败必须静默，不能因补救失败影响主流程
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：轮询回捞：在限定时间内反复尝试取结果，适配子 Agent 仍在收尾的场景
     * 参数：agentName Agent 名称；sessionId 会话 ID；task 原任务文本；sinceMs 起始时间；maxWaitMs 最长等待毫秒；intervalMs 轮询间隔毫秒
     * 返回值：回捞到的结果文本；超时返回空串
     * 异常：无（等待被中断时恢复中断标志并返回空串）
     */
    private static String pollRefetch(String agentName, String sessionId, String task,  // pollRefetch 签名第 1 行
                                      long sinceMs, long maxWaitMs, long intervalMs) {  // 签名第 2 行：sinceMs、maxWaitMs、intervalMs
        long deadline = System.currentTimeMillis() + maxWaitMs;  // 算截止时间（现在 + 最长等待）
        int round = 0;  // 轮次计数
        while (System.currentTimeMillis() < deadline) {  // 只要没到截止时间就继续
            round++;  // 轮次 +1
            String r = refetchLastResult(agentName, sessionId, task, sinceMs);  // 试一次回捞
            if (r != null && !r.isBlank()) {  // 捞到非空结果
                org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 打日志：
                        .warn("[轮询回捞] {} 第{}轮命中 {} 字符（空返回后等它跑完，省掉一次重发）",  // 输出第几轮命中、长度
                                agentName, round, r.length());  // 参数续行
                return r;  // 直接返回
            }  // if 结束
            try {  // 没捞到就睡一会儿
                Thread.sleep(intervalMs);  // sleep(intervalMs)：默认 10 秒
            } catch (InterruptedException ie) {  // 被中断
                Thread.currentThread().interrupt();  // 恢复中断标志（让上层感知）
                return null;  // 返回 null
            }  // catch 结束
        }  // while 结束
        org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)  // 等待超时：打日志
                .warn("[轮询回捞] {} 等待 {}ms（{} 轮）未命中，转入重发", agentName, maxWaitMs, round);  // 输出等待时长与轮数，说明转入重发
        return null;  // 返回 null
    }  // 方法结束

    /**
     * 作用：从子 Agent 的结果接口响应中取出 result 字段
     * 参数：url 结果接口地址
     * 返回值：result 字段文本；取不到返回空串
     * 异常：无
     */
    private static String fetchResultField(String url) {  // fetchResultField 签名
        try {  // try
            String body = httpGetQuiet(url, 3000);  // 静默 GET，超时 3 秒（本地进程间调用，给短）
            if (body == null || body.isBlank()) return null;  // 空响应直接 null
            Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);  // 用 Jackson 解析成 Map
            Object r = m.get("result");  // 取 result 字段
            if (r == null) return null;  // 没有该字段返回 null
            String s = r.toString();  // 转成字符串
            return s.isBlank() ? null : s;  // 空白串也算没捞到
        } catch (Exception e) {  // 任何异常
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：向子 Agent 查询它最近一次失败原因（用于把根因带回主管）
     * 参数：agentName Agent 名称；sessionId 会话 ID；sinceMs 只接受该时间之后的错误
     * 返回值：失败原因文本；取不到返回空串
     * 异常：无
     */
    private static String queryRemoteLastError(String agentName, String sessionId, long sinceMs) {  // queryRemoteLastError 签名
        try {  // try
            // ★2026-09-11 端点集中配置：不再拼 localhost:端口，统一走 AgentEndpoints
            String url = utils.AgentEndpoints.forAgent(agentName) + "/lastError?sessionId="  // 拼 URL：base + /lastError?sessionId=
                    + java.net.URLEncoder.encode(sessionId == null ? "" : sessionId,  // sessionId 做 URL 编码
                            java.nio.charset.StandardCharsets.UTF_8);  // 续行：编码用 UTF-8
            String body = httpGetQuiet(url, 2000);  // 静默 GET，超时 2 秒
            if (body == null || body.isBlank()) return null;  // 空响应放弃
            Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);  // 解析 JSON
            Object err = m.get("error");  // 取 error 字段
            if (err == null) return null;  // 没有就放弃
            String s = err.toString();  // 转字符串
            if (s.isBlank()) return null;  // 空白放弃
            // 去掉 "epochMs|" 前缀，只留分类原因；并用时间戳过滤上次任务残留的旧错误
            int bar = s.indexOf('|');  // 找分隔符位置
            if (bar > 0) {  // 有前缀
                try {  // try 解析时间戳
                    long ts = Long.parseLong(s.substring(0, bar).trim());  // 取毫秒前缀
                    if (ts < sinceMs - 5000) return null; // 旧错误（早于本次调用）→ 不报，避免误导  // 早于本次调用开始 5 秒以上 → 是上次任务的旧错误，不报（避免误导）
                } catch (NumberFormatException ignored) { }  // 解析失败就忽略前缀
                return bar < s.length() - 1 ? s.substring(bar + 1) : null;  // 返回分隔符后的原因部分（空则 null）
            }  // if 结束
            return s;  // 没有前缀格式：整串当原因
        } catch (Exception e) {  // 任何异常
            return null; // 查询失败静默：不能因诊断失败影响主流程  // 返回 null（诊断失败不能影响主流程）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：获取（并按名称缓存复用）远程 A2A Agent 代理，避免每次重建 Nacos 连接
     * 参数：name 注册在 Nacos 的 Agent 名称
     * 返回值：A2aAgent 代理实例
     * 异常：NacosException 连接注册中心或解析名片失败时抛出
     */
    private A2aAgent getAgent(String name) throws NacosException {  // getAgent 签名
        var builder = A2aAgent.builder()  // 开始构建 A2aAgent
                .name(name)  // 设置 Agent 名（Nacos 里的注册名）
                .agentCardResolver(new NacosAgentCardResolver(NacosUtil.getNacosClient()));  // 名片解析器：用共享的 Nacos 客户端去查该 Agent 的地址与能力
        try {  // try：下面的客户端配置失败要能兜住
            // ★2026-09-11 实测结论：非流式（setStreaming(false)+setPolling(true)）在本项目服务端**走不通**——
            //   配置确实生效（日志打印"非流式/轮询"），但 A2A 调用发出后 10 分钟无响应、E2E 卡死（agentscope
            //   A2A server 按流式推送事件，非流式拿不到完成响应）。而 SSE 流式虽然会被 40s 空闲切断，但
            //   **重发能拿到完整结果**（实测天骨架 3/3 全部成功）。
            //   所以默认为**流式**（保持原有可用行为），把非流式留作可热切换的实验开关（a2aStreaming=false 时启用）。
            // ★2026-11-12 实测定案（推翻 09-12 的非流式结论）：默认走【流式 SSE】。
            //   同机同用例（川西环线 7 日）对照：
            //     ① 非流式 + 45s 窗口：884s（7 天完整但有 2 次 5xx 重发）、2812s（46 分 52 秒，15 次调用/30 次重发/10 次 5xx）
            //     ② 流式 + 300s 窗口：517s（8 分 37 秒），7 天完整、降级标志空、失败标志空、0 次 5xx
            //   原因：非流式要等整个任务跑完才回包，单天 100~250 秒、整单 4 分钟以上，窗口一到就判失败 →
            //        回捞常 miss → 重发 → 8085 并发（上限 3）被打满 → 5xx 雪崩。流式有持续数据回流，不会静默超时。
            //   回退开关仍保留：Nacos 运行时配置 a2aStreaming=false 可切回非流式（排障用）。
            boolean streaming = utils.AgentRuntimeConfig.toolEnabled("a2aStreaming", true);  // 默认流式（实测 517s 完整交付）；Nacos 可切回非流式
            //   实测证据（对 8085 发原始 A2A 请求）：
            //     ① message/stream（流式）：结果在 60.4s 才到，中间静默 54s；且多次出现"流提前关闭、
            //        无终态"→ 主管侧拿到的就是 len=0 空串（靠回捞兜，白等 100-300s）。
            //     ② message/send（非流式）：单次 HTTP 请求 43.6s 返回 7647 字节完整结果，没有长连接，
            //        物理上不存在"流被切断"。
            //   注：早先"非流式走不通"的结论用的是 setPolling(true)（轮询 tasks/get，会挂），
            //   不是本路径；正确组合是 streaming=false + polling=false（一次性阻塞取结果）。
            //   保留 Nacos 开关 a2aStreaming=false 可切回非流式（排障用）。
            io.a2a.client.config.ClientConfig clientConfig = io.a2a.client.config.ClientConfig.builder()  // 构造 ClientConfig
                    .setStreaming(streaming)  // setStreaming：是否流式（由开关决定）
                    .setPolling(!streaming)  // setPolling：非流式时开轮询取结果
                    .build();  // build
            io.agentscope.core.a2a.agent.A2aAgentConfig cfg =  // 构造 A2aAgentConfig
                    io.agentscope.core.a2a.agent.A2aAgentConfig.builder()  // builder
                            .clientConfig(clientConfig)  // 挂 clientConfig
                            .build();  // build
            builder.a2aAgentConfig(cfg);  // 装回 A2aAgent builder
            log.info("[A2A] {} 客户端模式={}（a2aStreaming 开关；非流式=message/send 一次性取结果，无长连接）",  // 打日志说明当前客户端模式
                    name, streaming ? "SSE流式" : "非流式(send)");  // 参数：模式名（SSE流式 / 非流式(send)）
        } catch (Throwable t) {  // 捕获任何 Throwable
            // 配置失败退回 SDK 默认（流式），绝不影响主流程
            log.warn("[A2A] {} 客户端模式配置失败，退回 SDK 默认: {}", name, t.toString());  // warn 日志
        }  // catch 结束
        return builder.build();  // 构建并返回 A2aAgent（每次调用都是新实例）
    }  // 方法结束

    /**
     * 作用：通用远程 Agent 调用：Nacos 开关校验 → 查名片发消息 → 阻塞取回复 → 失败重发一次 → 仍失败则回捞结果 → 记录健康统计与调用链
     * 参数：name 远程 Agent 注册名；task 发给子 Agent 的自然语言任务；label 中文标签（用于事件与日志）
     * 返回值：子 Agent 返回的文本；彻底失败时返回占位提示文本
     * 异常：NacosException 连接注册中心/解析名片失败时抛出；执行中的 A2A 异常内部捕获，用户中断转 CancelledException
     */
    private String callRemoteAgent(String name, String task, String label) throws NacosException {  // callRemoteAgent 签名
        log.info("============");  // 日志分隔线
        log.info("工具方法：{}...正在调用中", label);  // 日志：正在调用哪个 Agent
        log.info("============");  // 日志分隔线

        String switchName = "RouteMakingAgent".equals(name) ? "remoteRouteAgent" : "remoteTripAgent";  // 映射开关名：RouteMakingAgent → remoteRouteAgent，其余 → remoteTripAgent
        if (!utils.AgentRuntimeConfig.toolEnabled(switchName, true)) {  // 开关关闭（AgentRuntimeConfig 读 Nacos 运行时配置）
            String result = label + "已通过Nacos开关关闭";  // 拼结果文案“已通过Nacos开关关闭”
            EventStore.add(sessionId, "Agent结果", label + " ⏸ 已通过Nacos开关关闭");  // 写事件：让前端知道这一路被运维关掉了
            log.info("{} disabled by Nacos agent runtime config", label);  // 日志记录
            return result;  // 直接返回该文案（不抛异常，编排照常继续）
        }  // if 结束

        Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();  // 读编排配置
        RemoteTimeout.configure(orchestration);  // RemoteTimeout.configure：刷新 A2A 等待预算（可热更）
        A2aAgent agent = getAgent(name); // 复用缓存的A2A远程Agent代理  // getAgent：新建该 Agent 的 A2A 代理

        log.info("============");  // 日志：
        log.info("获取到的远程Agent描述：" + agent.getDescription());  // 打印远程 Agent 的描述（名片解析是否正常）
        log.info("============");  // 日志分隔线

        // ★A2A传输级重试：超时/异常/空返回 → 带失败信号重发1次（代码级，与子Agent内工具重试相互独立）
        String emptyMsg0 = label + "没有返回内容";  // emptyMsg0：空返回的占位文案（各分支都往 result 里塞它）
        String result = null;  // result 初始 null
        String lastFailReason = ""; // ★2026-09-11 错误语义透传：记录最后一次失败的真实原因（原来一律显示"超时/空"，402/超时/断流真因被吞）  // lastFailReason：记录最后一次失败的真实原因（原来一律显示“超时/空”，真因被吞）
        long callStartMs = System.currentTimeMillis(); // 健康统计：整次调用（含重试）计时  // callStartMs：整次调用（含重试）开始时间，用于健康统计
        utils.CallTrace.Span a2aSpan = utils.CallTrace.begin(sessionId, "A2A", name, ""); // ★调用链：每轮尝试记入 span.attempts  // 开一条 A2A span（调用链），后面每次尝试都往里加 attempt
        log.info("[A2A] {} 开始调用（超时上限 {}s，最多重试1次，任务长度 {} 字符）",  // 日志：
                label, RemoteTimeout.timeout().toSeconds(), task == null ? 0 : task.length());  // 打印超时上限、最多重试 1 次、任务长度
        for (int attempt = 1; attempt <= 2; attempt++) {  // 最多两次尝试：1=首调，2=重发
            // ★2026-09-11 根因探针（回答"大任务为什么返回空串"）：
            //   记录每次 attempt 的【任务长度 / 实际耗时 / 返回长度 / 成败】。
            //   用途：把"成功"和"空返回"两组按任务长度和耗时对照，验证/否定"任务越大越易空返回"，
            //   并看清空返回究竟发生在"提交后多久"（是立即空、还是等了一段时间才空）。
            long attemptStartMs = System.currentTimeMillis();  // attemptStartMs：本次尝试开始时间
            // ★2026-09-05 每 attempt 独立预算：deadline 在循环内重算——原 300s 总预算被 attempt1 耗尽后 attempt2 秒弃(0ms 直接失败)，超时重发形同虚设
            long deadline = System.nanoTime() + RemoteTimeout.timeout().toNanos();  // 原来 300s 总预算被 attempt1 耗尽后 attempt2 秒弃，超时重发形同虚设
            // ★任务级调用链：会话标记随任务文本传给子Agent，子Agent进程的 LLM/工具 span 据此归位
            String taskToSend = "【trace:" + sessionId + "】" + task;  // 拼实际发送文本：【trace:sessionId】+ 任务
            if (attempt > 1) {  // 第二次尝试
                // ★注意：必须同样带上【trace:】前缀——原先重试把前缀丢了，导致子Agent 侧的任务文本
                // 与主管回捞时算的指纹不一致（回捞必然 miss），也丢了子Agent 进程内的 span 归位标记。
                taskToSend = "【trace:" + sessionId + "】" + task  // 重试文本 = trace 前缀 + 原任务
                        + "\n\n【重试提示】上一次调用超时或返回空。请务必调用你的工具完成查询并输出完整结果。";  // 再追加【重试提示】要求它务必调工具并输出完整结果
                // 兜底：异常路径未捕获到原因时也要给出可读说明（否则出现空的 "( )"，等于白改）
                String shown = lastFailReason.isBlank() ? "未捕获到具体原因(传输层空返回/中断)" : lastFailReason;  // shown：有原因用原因，没有就用固定说明
                EventStore.add(sessionId, "Agent结果", label + " ⚠️ 第1次调用失败(" + shown + ")，主管重发(第2次)...");  // 写事件：第 1 次失败（原因），主管重发
                log.warn("{} 第1次A2A调用失败({})，重发中", label, shown);  // warn 日志
            }  // if 结束
            long blockWindowMs = 0L;  // 本次阻塞窗口（毫秒）；0 = 预算已耗尽未真正发起调用，catch 里打诊断时用它
            try {  // try 本次尝试
                Duration remaining = Duration.ofNanos(deadline - System.nanoTime());  // 算剩余时间
                if (remaining.isNegative() || remaining.isZero()) {  // 已到/超期
                    result = emptyMsg0;  // 不发起调用，结果置空占位
                    lastFailReason = classifyFail("剩余超时(超时上限已到)");  // 失败原因记为“剩余超时（超时上限已到）”
                    a2aSpan.addAttempt(attempt, false, 0, "剩余超时(超时上限已到)");  // span 记一次失败尝试
                } else {  // 否则真正发起调用
                    // ★本轮改动（治本）：阻塞等待切成"短窗口"。原 `.block(remaining)` 会死等满 300s，
                    //   而子 Agent 常常早已跑完、结果已存 /lastResult；到点没回就转 pollRefetch 边等边捞。
                    blockWindowMs = readWindowMs(remaining.toMillis());  // 本次实际阻塞窗口（默认 45s，夹在预算内）
                    log.warn("[A2A诊断] {} attempt={} 预算={}ms 阻塞窗口={}ms 任务长度={}（窗口内未回则转轮询回捞）",
                            label, attempt, remaining.toMillis(), blockWindowMs, taskToSend.length());  // 诊断：把预算/窗口/任务长度对齐，后续可判断"空返回"是立即空还是等窗口后空
                    EventStore.setInterruptCallback(sessionId, agent::interrupt);  // 注册中断回调（用户点中断时打断这个远程调用）
                    Msg response;  // 声明 response
                    try {  // try 包住调用
                        response = agent.call(  // agent.call(Msg)：构造 USER 角色的文本消息
                                Msg.builder()  // 消息构造：role=USER
                                        .role(MsgRole.USER)  // 内容块：TextBlock
                                        .content(TextBlock.builder()  // 文本 = taskToSend
                                                .text(taskToSend)  // build 内容块
                                                .build())  // build 消息
                                        .build()  // block(remaining)：阻塞等待，最多到本次 deadline
                                ).block(Duration.ofMillis(blockWindowMs));  // 续行：只用短窗口（默认 45s）阻塞，到点没回就转轮询回捞，不再死等 300s
                    } finally {  // finally
                        EventStore.clearInterruptCallback(sessionId);  // 清掉中断回调（避免误打断后续调用）
                    }  // finally 结束
                    result = (response != null && response.getTextContent() != null)  // 取返回文本：
                            ? response.getTextContent()  // 有文本就用文本
                            : emptyMsg0;  // 否则用空占位
                    // ★2026-09-11 记账漏洞修复：原来只有"恰好等于 emptyMsg0"才记失败原因，而 A2A 返回
                    // 【空白串】（非 null 的 "" / "   "）时，既不会在这里记账、也不会在下面的 break 处放行 ——
                    // 于是进了重试却查不到原因，日志只能显示"未捕获到具体原因"（把「没记账」误报成「不知道原因」）。
                    // 判定与 break 处统一按 isBlank()，并区分是"空对象"还是"空白文本（流被截断）"。
                    boolean blankReply = result == null || result.isBlank();  // blankReply：null 或空白都算空返回
                    // ★2026-09-11 根因探针：每次 attempt 一行，把「任务长度 / 实际耗时 / 返回长度 / 成败」对齐，
                    //   用数据回答"大任务为什么返回空串"（对照成功组与空返回组的任务长度和耗时分布）。
                    log.warn("[A2A探针] {} attempt={} 耗时={}ms 任务长度={} 返回长度={} 结果={}",  // log.warn 探针日志
                            label, attempt, System.currentTimeMillis() - attemptStartMs,  // 参数：label、attempt、耗时
                            taskToSend.length(), result == null ? -1 : result.length(),  // 参数：任务长度
                            blankReply ? "空返回" : "成功");  // 参数：返回长度（null 记 -1）、结果（空返回/成功）
                    if (blankReply) {  // 是空返回
                        String why = (result == null || result.equals(emptyMsg0))  // 区分原因：
                                ? "返回为空(A2A返回空对象)"  // 空对象 → “返回为空（A2A返回空对象）”
                                : "返回为空白文本(len=" + result.length()  // 空白文本 → “返回为空白文本(len=…)
                                  + (result.length() == 0 ? ",A2A返回空串→子Agent无产出/流被掐断"  // 续：长度为 0 说明 A2A 返回空串→子 Agent 无产出/流被掐断
                                                          : ",长度>0但全空白→A2A流被截断/中断") + ")";  // 续：长度>0 但全空白说明流被截断/中断
                        lastFailReason = why;  // lastFailReason 记下原因
                        a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs, why);  // span 记失败尝试与原因
                        // ★2026-09-11 针对性补救：空返回发生得比任务真正完成早得多（实测 38s 断 / 72s 才跑完），
                        //   说明子Agent 多半还在跑。此时先"边等边回捞"（最多 90s、每 10s 一次），
                        //   捞到就直接用——省掉一次完整重发（那是再起一个任务、再等 70~90s）。
                        if (attempt == 1) {  // 只对第 1 次尝试做
                            // ★2026-09-12 窗口 90s→240s：实测子Agent 单天任务常在 100~200s，
                            //   90s 窗口必然 miss → 触发"重发"，而重发会与仍在跑的旧任务抢 8085 并发，
                            //   形成"越重发越慢"的恶性循环。窗口给足，等它跑完直接回捞（比重发省 100~300s）。
                            // ★本轮改动：空返回/短窗口超时后，用「本次 attempt 剩余预算」当轮询窗口（原来固定写 240s）。
                            //   子 Agent 一跑完就能命中，同时保证轮询不会越过本次调用的总预算。
                            long pollBudgetMs = Math.max(120_000L, (deadline - System.nanoTime()) / 1_000_000L);  // 轮询窗口：至少 2 分钟，最多到本次预算
                            String polled = pollRefetch(name, sessionId, task, callStartMs, pollBudgetMs, 3_000L);  // 边等边捞：每 3 秒查一次子 Agent 的 /lastResult（原 10 秒，空返回后实际白等 10 秒才拿到）
                            if (polled != null && !polled.isBlank()) {  // pollRefetch：最多等 240s，每 10s 试一次
                                result = polled;  // 捞到非空
                                lastFailReason = "";  // 直接采用回捞结果
                                a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "空返回后轮询回捞成功");  // 清空失败原因（这次算成功）
                                EventStore.add(sessionId, "Agent结果",  // span 记一次成功尝试（说明是回捞救回来的）
                                        label + " ↻ A2A返回空，未重发而是等子Agent跑完后回捞到结果(" + polled.length() + " 字符)");  // 写事件：A2A 返回空，但等子 Agent 跑完后回捞到结果（字符数）
                                break;  // 跳出重试循环
                            }  // if 结束
                        }  // if(attempt==1) 结束
                        result = emptyMsg0;  // 没捞到：结果置空占位（准备进入第 2 次尝试或结束）
                    } else {  // else（拿到非空文本）
                        lastFailReason = "";  // 清空失败原因
                        a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "");  // span 记成功
                    }  // else 结束
                }  // if blankReply 结束
            } catch (Exception e) {  // 捕获异常（超时/连接断开等）
                // ★用户中断：InterruptedException 或取消标志已置位 → 不重试，直接终止（否则中断会被当成失败重发第2次，永远停不下来）
                if (e instanceof InterruptedException || EventStore.isCancelRequested(sessionId)) {  // 是中断异常，或取消标志已置位
                    Thread.currentThread().interrupt();  // 恢复中断标志
                    a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs, "用户中断");  // span 记失败（用户中断）
                    throw new managerAgent.hook.CancelledException();  // 抛 CancelledException，让上层走取消流程
                }  // if 结束
                log.warn("{} A2A调用异常(attempt={}): {}", label, attempt, e.getMessage());  // warn 日志：第几次尝试、异常消息
                // ★本轮改动（二级探针）：把"没拿到响应"这件事记全 —— 异常类型 / 阻塞窗口 / 实际耗时 / 任务长度。
                //   判据：耗时≈阻塞窗口 ⇒ 窗口内一个字节都没回（不是空串，是根本没响应）；
                //        耗时远小于窗口 ⇒ 立即失败（连接被拒/流刚开就被掐断）。两者修法不同，必须先用数据分清。
                log.warn("[A2A诊断] {} attempt={} 异常类型={} 消息={} 阻塞窗口={}ms 实际耗时={}ms 任务长度={}",
                        label, attempt, e.getClass().getSimpleName(),  // 异常类名：TimeoutException=没等到响应，IOException/其他=链路问题
                        e.getMessage() == null ? "" : e.getMessage(), blockWindowMs,  // 异常消息 + 本次阻塞窗口（毫秒）
                        System.currentTimeMillis() - attemptStartMs, taskToSend.length());  // 实际耗时 + 任务长度
                EventStore.add(sessionId, "Agent结果", label + " ⏳ 窗口内未收到响应，转入轮询回捞（每 10 秒一次）");  // 进度可见：让前端知道不是死了，而是在等子 Agent 跑完
                // ★2026-09-12 根治改动：超时/传输异常后【不再立刻中断远端任务】。
                //   实测依据：子Agent 往往已经跑完或快跑完，只是回程（A2A 流式响应）被切断；
                //   一中断就等于销毁"已产出的结果"——回捞必然 miss，只能整个重发（每次多花 100~300s）。
                //   正确顺序：先回捞（给子Agent 一点时间把结果存进 /lastResult），捞不到才中断。
                String em = e.getMessage() == null ? "" : e.getMessage();  // em：异常消息（null 归一空串）
                lastFailReason = classifyFail(em.isBlank() ? "异常(无消息)" : em);  // classifyFail 归类失败原因
                a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs,  // span 记失败尝试
                        em.isBlank() ? "异常" : (em.length() > 120 ? em.substring(0, 120) : em));  // 续行：消息超过 120 字截断
                // ★2026-09-12 根治：异常后先"边等边回捞"（子Agent 多半还在跑/刚跑完），
                //   捞到就直接用——既不销毁子Agent 的劳动，也省掉一次完整重发。
                if (attempt == 1) {  // 只对第 1 次尝试做
                    // ★本轮改动：异常（短窗口超时/断流）后同样用剩余预算轮询回捞，命中即返回。
                    long catchPollBudgetMs = Math.max(120_000L, (deadline - System.nanoTime()) / 1_000_000L);  // 轮询窗口：至少 2 分钟，最多到本次预算
                    String caught = pollRefetch(name, sessionId, task, callStartMs, catchPollBudgetMs, 3_000L);  // 轮询回捞（窗口 = 剩余预算；间隔 3 秒，缩短空返回后的白等）
                    if (caught != null && !caught.isBlank()) {  // 捞到非空
                        result = caught;  // 直接采用
                        a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "异常后回捞成功");  // span 记成功（异常后回捞成功）
                        EventStore.add(sessionId, "Agent结果", label + " ↻ A2A异常(" + lastFailReason  // 写事件：A2A 异常（原因），未中断子 Agent，等它跑完回捞到结果
                                + ")，未中断子Agent，等它跑完回捞到结果(" + caught.length() + " 字符)");  // 参数续行：字符数
                        lastFailReason = "";  // 清空失败原因
                        break;  // 跳出循环
                    }  // if 结束
                }  // if(attempt==1) 结束
                // 回捞不到，此时才中断远端任务（避免它继续空跑、与重发的任务叠加）
                try {  // try
                    agent.interrupt();  // agent.interrupt()：打断远端任务
                } catch (Exception ignored) { }  // 异常忽略（它可能已经结束）
                result = emptyMsg0;  // 结果置空占位
            }  // catch 结束
            if (result != null && !result.isBlank() && !result.equals(emptyMsg0)) {  // 拿到有效结果（非空且不是占位文案）
                break; // 拿到有效结果  // 跳出重试循环
            }  // if 结束
        }  // for 结束
        String emptyMsg = emptyMsg0;  // emptyMsg：占位文案（后面判断用）
        // ★2026-09-11 结果回捞（方案①）：A2A 超时/空返回时，先向子Agent 回取"它其实已产出的结果"。
        //   子Agent 常常已经跑完了，只是回程（A2A 流式响应）中断，主管只拿到开头 14~128 字符就判失败。
        //   捞到即按成功处理，避免"重发→再失败→天骨架失败→回退整单→降级"这条链。
        if (result == null || result.isBlank() || result.equals(emptyMsg0)) {  // 两次都失败（null / 空白 / 占位）
            String salvaged = refetchLastResult(name, sessionId, task, callStartMs);  // 最后再回捞一次（按指纹 + sinceMs 兜底）
            if (salvaged != null && !salvaged.isBlank()) {  // 捞到了
                result = salvaged;  // 采用
                lastFailReason = ""; // 已救回，不再算失败  // 清空失败原因（已救回）
                log.warn("{} A2A调用失败，但已从子Agent回捞到结果({} 字符)", label, salvaged.length());  // warn 日志：A2A 失败但已从子 Agent 回捞到结果（字符数）
            }  // if 结束
        }  // if 结束
        // 健康统计：按最终结果记录成功/失败与耗时
        boolean callOk = result != null && !result.isBlank() && !result.equals(emptyMsg);  // callOk：有有效结果就算成功
        long callCostMs = System.currentTimeMillis() - callStartMs;  // callCostMs：整次调用（含重试与回捞）耗时
        recordCall(name, callOk, callCostMs);  // recordCall：累计调用数/失败数/耗时
        // ★任务级调用链：A2A 调用明细（每轮尝试已入 span.attempts，管理端叶子内下拉展示）
        a2aSpan.success = callOk;  // span 标记成功与否
        a2aSpan.costMs = callCostMs;  // span 记录总耗时
        int failedTries = 0;  // 统计失败尝试次数
        for (java.util.Map<String, Object> at : a2aSpan.attempts) {  // 遍历每次尝试
            if (!Boolean.TRUE.equals(at.get("ok"))) failedTries++;  // 不是 ok 就计一次失败
        }  // for 结束
        a2aSpan.detail = callOk  // span.detail：成功（重试后 / 直接）/ 失败
                ? (failedTries > 0 ? "子Agent调用成功(重试后)" : "子Agent调用成功")  // 续行
                : "子Agent调用失败(重试后仍失败)";  // 续行
        // 记录子Agent调用结果事件（★2026-09-11 失败时透出真因，便于区分 402余额/超时/断流/空返回）
        String displayReason = lastFailReason.isBlank() ? "重试后仍空" : lastFailReason;  // displayReason：有原因用原因，没有用“重试后仍空”
        if (!callOk) { // ★方案B：A2A 失败时向子Agent查最近 LLM 错误（402 等在子Agent进程内被 A2A 吞成空响应）  // 调用失败时
            String remoteErr = queryRemoteLastError(name, sessionId, callStartMs);  // 向子 Agent 查最近一次 LLM 错误（402 这类在子 Agent 内部被吞成空响应）
            if (remoteErr != null && !remoteErr.isBlank()) {  // 查到了
                displayReason = displayReason + " ← 子Agent真因: " + remoteErr;  // 把真因拼进展示原因
                log.warn("{} 子Agent上报真因: {}", label, remoteErr);  // warn 日志
            }  // if 结束
        }  // if 结束
        EventStore.add(sessionId, "Agent结果", callOk  // 写事件：✅ 调用成功（返回 N 字符）或 ❌ 调用失败（原因）
                ? label + " ✅ 调用成功(返回" + result.length() + "字符)"  // 参数：label + 成功文案
                : label + " ❌ 调用失败(" + displayReason + ")");  // 参数：失败文案（含真因）
        log.info("============");  // 日志分隔线
        log.info(label + "返回结果：" + result);  // 日志：返回结果全文
        log.info("============");  // 日志分隔线
        return result;  // 返回结果（失败也是返回占位文本，不抛异常）
    }  // 方法结束

    /**
     * 作用：调用路线制定子 Agent，返回自驾路线规划结果（供 ReAct 工具调用）
     * 参数：task 具体自驾路线任务描述
     * 返回值：路线规划文本；失败时为占位提示文本
     * 异常：NacosException 远程 Agent 不可达时抛出；业务失败在 callRemoteAgent 内处理
     */
    @Tool(description = "从Nacos注册中心获取路线制定Agent，返回自驾游路线规划结果")  // @Tool：把这个方法注册成模型可调用的工具（description 是给模型看的）
    public String callRouteMakingAgent(  // 方法签名（public，因为要作为工具暴露）
            @ToolParam(name = "task", description = "用户需要规划的具体自驾路线任务描述，例如：请规划从广州到珠海的自驾路线，包含每日行车路线、途经景点和驾驶时间")  // @ToolParam：给模型看的 task 参数说明（越具体，模型生成的任务越准）
            String task) throws NacosException {  // 任务描述示例
        return callRemoteAgent("RouteMakingAgent", task, "路线Agent");  // 唯一一行业务：转 callRemoteAgent("RouteMakingAgent", task, "路线Agent")
    }  // 方法结束

    /**
     * 调用行程规划Agent，返回规划结果
     */
    /**
     * ★2026-09-11 8085(行程Agent) 并发硬限：代码原注释记录"实测 4+ 并发 A2A 时后提交的任务会失败"。
     * 天骨架（批内并行）与多方案（3 套并行）会从多条路径调用同一个 8085，靠 batchSize=3 这种局部约定
     * 守不住（例如审查阶段天级重编若改成并行就会悄悄越线）。这里对"同时打到行程Agent 的请求数"做
     * 进程级硬限，超出的请求排队等待而不是失败。可用 TRIP_AGENT_MAX_CONCURRENCY 调整（默认 3）。
     */
    private static final java.util.concurrent.Semaphore TRIP_AGENT_SLOTS =  // 声明静态信号量 TRIP_AGENT_SLOTS
            new java.util.concurrent.Semaphore(utils.Config.getInt("TRIP_AGENT_MAX_CONCURRENCY", 3));  // 许可数 = Config.getInt("TRIP_AGENT_MAX_CONCURRENCY", 3)
    /**
     * 作用：调用行程规划子 Agent，返回景点与每日行程规划结果（供 ReAct 工具调用）
     * 参数：task 具体行程规划任务描述
     * 返回值：行程规划文本；失败时为占位提示文本
     * 异常：NacosException 远程 Agent 不可达时抛出；业务失败在 callRemoteAgent 内处理
     */
    @Tool(description = "从Nacos注册中心获取行程规划Agent，返回指定目的地的景点和行程规划结果")  // @Tool：注册成模型可调用工具
    public String callTripPlannerAgent(  // 方法签名
            @ToolParam(name = "task", description = "用户需要规划的具体旅行任务描述，例如：请规划珠海2日游的景点、每日行程安排、酒店和美食推荐")  // @ToolParam：给模型看的任务描述示例
            String task) throws NacosException {  // 续行
        try {  // try
            TRIP_AGENT_SLOTS.acquire(); // 超过并发上限则排队（而非像原来那样直接失败）  // acquire()：抢一个 8085 许可，超限就排队（而不是快速失败）
        } catch (InterruptedException ie) {  // 被中断
            Thread.currentThread().interrupt();  // 恢复中断标志
            throw new managerAgent.hook.CancelledException();  // 抛 CancelledException（用户取消）
        }  // catch 结束
        try {  // try
            return callRemoteAgent("TripPlannerAgent", task, "行程Agent");  // 调 callRemoteAgent("TripPlannerAgent", …)
        } finally {  // finally
            TRIP_AGENT_SLOTS.release();  // 无论成功失败都归还许可（避免许可泄漏把 8085 通道永久堵死）
        }  // finally 结束
    }  // 方法结束
}  // 类结束

