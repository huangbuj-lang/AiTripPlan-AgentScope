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
@Slf4j
public class RemoteAgentTool {

    private String sessionId = "default"; // 会话ID（结果事件按会话隔离）

    /** 设置会话ID */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    // ★2026-09-05 删缓存：同一 A2aAgent 实例并发 call 会抛 "Agent is still running"（多方案3套并行定稿=2/3套降级兜底的根因）。
    //   A2aAgent 为协议层轻对象（Nacos 连接池在 NacosUtil 单例共享），每次调用新建独立实例成本可接受，换来并发安全。
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(300); // ★2026-08-24 云端240s→300s: 路线Agent多轮工具调用常超240s

    // ==================== 子Agent健康统计（管理端展示） ====================
    /** 按 agent 名累计：总调用次数 / 失败次数 / 累计耗时ms（进程内存值） */
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_CALLS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_FAILS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> AGENT_COST_MS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录一次远程调用结果（成功/失败 + 耗时） */
    private static void recordCall(String name, boolean success, long costMs) {
        AGENT_CALLS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if (!success) {
            AGENT_FAILS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        }
        AGENT_COST_MS.computeIfAbsent(name, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(costMs);
    }

    /**
     * ★2026-09-11 失败原因归类：委托 commons 的 SafeModel.classifyErrorText（与子Agent 内 LLM 失败分类同一套），
     * 避免 manager 与 commons 各写一份分类逻辑。原实现内联在此，已移除。
     */
    static String classifyFail(String raw) {
        return utils.SafeModel.classifyErrorText(raw);
    }

    /** 短超时 HTTP GET（失败返回 null，绝不抛）：供查子Agent真因用，2s 到位不影响主流程 */
    private static String httpGetQuiet(String url, int timeoutMs) {
        try {
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .timeout(Duration.ofMillis(timeoutMs)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ★2026-09-11 结果回捞（方案①）：向子Agent 索取"它其实已产出的结果"。
     * 一次主管任务会并发多次调同一子Agent（如天骨架 3 天并行），所以按【任务指纹】精确匹配，
     * 而不是"拿最近一条"（那会串天）。attempt1 用原 task、attempt2 带重试提示，两个指纹都试一遍；
     * 都对不上再用 sinceMs 兜底。命中即取走（子Agent 侧 take），不会被反复捞。
     */
    private static String refetchLastResult(String agentName, String sessionId, String task, long sinceMs) {
        try {
            if (task == null || task.isBlank()) return null;
            String base = utils.AgentEndpoints.forAgent(agentName);
            if (base == null || base.isBlank()) return null;
            String[] keys = {
                    // ★关键：子Agent 是按【收到的原文】存指纹的，而主管发出去的是带 trace 前缀的任务
                    // （attempt1）或带重试提示的任务（attempt2）——当初回捞拿"裸 task"去查，两边算的不是
                    // 同一个字符串，必然全部 miss（实测：存档 3 条、回捞 3 次全 hit=false）。
                    utils.LastResultStore.keyOf("【trace:" + sessionId + "】" + task),
                    utils.LastResultStore.keyOf(task
                            + "\n\n【重试提示】上一次调用超时或返回空。请务必调用你的工具完成查询并输出完整结果。"),
                    utils.LastResultStore.keyOf(task) // 兼容：万一将来发送文本又变了
            };
            // ★诊断日志：以前回捞是静默的，失败时无法判断"到底跑没跑、为什么没命中"
            StringBuilder kb = new StringBuilder();
            for (String k : keys) kb.append(k).append(' ');
            org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                    .warn("[回捞] {} 开始回捞 keys=[{}] sinceMs={}", agentName, kb.toString().trim(), sinceMs);
            // ★诊断（关键）：把"主管实际发出去的文本"也打出来（长度 + 首尾），
            // 与子Agent 侧 [lastResult指纹] 日志逐字比对，确认两边是否同一个字符串。
            String sentText = "【trace:" + sessionId + "】" + task;
            org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                    .warn("[回捞指纹] 发出的任务原文: len={} 首=<<{}>> 尾=<<{}>>", sentText.length(),
                            sentText.length() > 60 ? sentText.substring(0, 60).replace('\n', '⏎') : sentText.replace('\n', '⏎'),
                            sentText.length() > 60 ? sentText.substring(sentText.length() - 40).replace('\n', '⏎') : "");
            for (String k : keys) {
                String r = fetchResultField(base + "/lastResult?key=" + k);
                if (r != null) {
                    org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                            .warn("[回捞] {} 命中(key={}) {} 字符", agentName, k, r.length());
                    return r;
                }
            }
            String r = fetchResultField(base + "/lastResult?sinceMs=" + sinceMs); // 兜底
            org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                    .warn("[回捞] {} 未命中（子Agent 无该任务结果，或本次它自己也没跑完）", agentName);
            if (r != null) {
                org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                        .warn("[回捞] {} 兜底 sinceMs 命中({} 字符)", agentName, r.length());
            }
            return r;
        } catch (Exception e) {
            return null; // 回捞失败静默：不能因补救失败影响主流程
        }
    }

    /**
     * ★2026-09-11 「空返回」的针对性补救：**边等边回捞**。
     *
     * <p>实测依据（A2A 探针）：
     * <pre>
     * 行程Agent 耗时=38134ms 任务长度=7050 返回长度=0    空返回   ← 38s 就断
     * 行程Agent 耗时=72345ms 任务长度=5772 返回长度=1906 成功     ← 同类任务要 72s
     * </pre>
     * 空返回发生得**比任务真正完成早得多** → 说明子Agent 当时还在跑，只是这一跳的连接提前断了。
     * 而子Agent 跑完会经 ResultArchiveHook 落盘，所以此时**不该立刻重发**（那会再起一个任务、白等一遍），
     * 而应"等它跑完再回捞"。这里按固定间隔轮询 /lastResult，命中即返回。
     *
     * @param maxWaitMs  最多等多久（默认给到单次任务正常耗时的上限）
     * @param intervalMs 轮询间隔
     * @return 捞到的结果；超时/中断返回 null
     */
    private static String pollRefetch(String agentName, String sessionId, String task,
                                      long sinceMs, long maxWaitMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            round++;
            String r = refetchLastResult(agentName, sessionId, task, sinceMs);
            if (r != null && !r.isBlank()) {
                org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                        .warn("[轮询回捞] {} 第{}轮命中 {} 字符（空返回后等它跑完，省掉一次重发）",
                                agentName, round, r.length());
                return r;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        org.slf4j.LoggerFactory.getLogger(RemoteAgentTool.class)
                .warn("[轮询回捞] {} 等待 {}ms（{} 轮）未命中，转入重发", agentName, maxWaitMs, round);
        return null;
    }

    /** 解析 /lastResult 返回里的 result 字段；空串/未命中返回 null */
    private static String fetchResultField(String url) {
        try {
            String body = httpGetQuiet(url, 3000);
            if (body == null || body.isBlank()) return null;
            Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            Object r = m.get("result");
            if (r == null) return null;
            String s = r.toString();
            return s.isBlank() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ★2026-09-11 错误语义透传(方案B)：A2A 失败时向子Agent查"最近 LLM 错误"。
     * 背景：402/超时等错误发生在子Agent进程内，被 A2A 框架变成"空响应"，manager 侧只看得到"返回为空"。
     * 子Agent 的 StatsController 暴露 /lastError?sessionId= 返回本进程最近一次 LLM 失败原因。
     */
    private static String queryRemoteLastError(String agentName, String sessionId, long sinceMs) {
        try {
            // ★2026-09-11 端点集中配置：不再拼 localhost:端口，统一走 AgentEndpoints
            String url = utils.AgentEndpoints.forAgent(agentName) + "/lastError?sessionId="
                    + java.net.URLEncoder.encode(sessionId == null ? "" : sessionId,
                            java.nio.charset.StandardCharsets.UTF_8);
            String body = httpGetQuiet(url, 2000);
            if (body == null || body.isBlank()) return null;
            Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            Object err = m.get("error");
            if (err == null) return null;
            String s = err.toString();
            if (s.isBlank()) return null;
            // 去掉 "epochMs|" 前缀，只留分类原因；并用时间戳过滤上次任务残留的旧错误
            int bar = s.indexOf('|');
            if (bar > 0) {
                try {
                    long ts = Long.parseLong(s.substring(0, bar).trim());
                    if (ts < sinceMs - 5000) return null; // 旧错误（早于本次调用）→ 不报，避免误导
                } catch (NumberFormatException ignored) { }
                return bar < s.length() - 1 ? s.substring(bar + 1) : null;
            }
            return s;
        } catch (Exception e) {
            return null; // 查询失败静默：不能因诊断失败影响主流程
        }
    }

    /**
     * 按 name 新建 A2A 代理（★不缓存复用：并发安全，见 AGENT_CACHE 删除注释）
     *
     * <p>★2026-09-11 修复「A2A 返回空串(len=0)」—— 证据链：
     * <ol>
     *   <li>实测两次失败都发生在**提交后 39s / 40s**（10:30:16→10:30:55、10:32:26→10:33:06），
     *       而任务本身要跑 100~200s → 不是任务失败，是**连接被提前切断**。</li>
     *   <li>SDK 字节码显示：流式走 {@code A2AHttpClient$PostBuilder.postAsyncSSE}，非流式走 {@code post()}。</li>
     *   <li>{@code ClientConfig} 提供 setStreaming/setPolling 开关。</li>
     * </ol>
     * 所以把 A2A 客户端从「SSE 流式」切成「非流式/轮询」，避免长任务因流空闲被切断。
     * 用 Nacos 开关 {@code a2aStreaming} 控制（默认 false=非流式），可随时热切换回退。
     */
    private A2aAgent getAgent(String name) throws NacosException {
        var builder = A2aAgent.builder()
                .name(name)
                .agentCardResolver(new NacosAgentCardResolver(NacosUtil.getNacosClient()));
        try {
            // ★2026-09-11 实测结论：非流式（setStreaming(false)+setPolling(true)）在本项目服务端**走不通**——
            //   配置确实生效（日志打印"非流式/轮询"），但 A2A 调用发出后 10 分钟无响应、E2E 卡死（agentscope
            //   A2A server 按流式推送事件，非流式拿不到完成响应）。而 SSE 流式虽然会被 40s 空闲切断，但
            //   **重发能拿到完整结果**（实测天骨架 3/3 全部成功）。
            //   所以默认为**流式**（保持原有可用行为），把非流式留作可热切换的实验开关（a2aStreaming=false 时启用）。
            // ★2026-09-12 实测定案（根治 len=0）：默认改走【非流式 message/send】。
            //   实测证据（对 8085 发原始 A2A 请求）：
            //     ① message/stream（流式）：结果在 60.4s 才到，中间静默 54s；且多次出现"流提前关闭、
            //        无终态"→ 主管侧拿到的就是 len=0 空串（靠回捞兜，白等 100-300s）。
            //     ② message/send（非流式）：单次 HTTP 请求 43.6s 返回 7647 字节完整结果，没有长连接，
            //        物理上不存在"流被切断"。
            //   注：早先"非流式走不通"的结论用的是 setPolling(true)（轮询 tasks/get，会挂），
            //   不是本路径；正确组合是 streaming=false + polling=false（一次性阻塞取结果）。
            //   保留 Nacos 开关 a2aStreaming=true 可热切回流式（排障用）。
            boolean streaming = utils.AgentRuntimeConfig.toolEnabled("a2aStreaming", false);
            // ★2026-09-12 补最后一块：polling 必须为 true。
            //   实测：非流式但仍 polling=false 时，服务端阻塞窗口(约 30~40s)一过就返回 state=working 的 task
            //   （无结果）→ 客户端拿空串。探针佐证：耗时=38622ms 返回长度=0 结果=空返回 / 耗时=57967ms 返回长度=0。
            //   polling=true 后客户端会追问 tasks/get 直到任务 completed 再取结果。
            io.a2a.client.config.ClientConfig clientConfig = io.a2a.client.config.ClientConfig.builder()
                    .setStreaming(streaming)
                    .setPolling(!streaming)
                    .build();
            io.agentscope.core.a2a.agent.A2aAgentConfig cfg =
                    io.agentscope.core.a2a.agent.A2aAgentConfig.builder()
                            .clientConfig(clientConfig)
                            .build();
            builder.a2aAgentConfig(cfg);
            log.info("[A2A] {} 客户端模式={}（a2aStreaming 开关；非流式=message/send 一次性取结果，无长连接）",
                    name, streaming ? "SSE流式" : "非流式(send)");
        } catch (Throwable t) {
            // 配置失败退回 SDK 默认（流式），绝不影响主流程
            log.warn("[A2A] {} 客户端模式配置失败，退回 SDK 默认: {}", name, t.toString());
        }
        return builder.build();
    }

    /** 通用远程Agent调用：查名片 → 发消息 → 阻塞取回复 → 记录事件 */
    private String callRemoteAgent(String name, String task, String label) throws NacosException {
        log.info("============");
        log.info("工具方法：{}...正在调用中", label);
        log.info("============");

        String switchName = "RouteMakingAgent".equals(name) ? "remoteRouteAgent" : "remoteTripAgent";
        if (!utils.AgentRuntimeConfig.toolEnabled(switchName, true)) {
            String result = label + "已通过Nacos开关关闭";
            EventStore.add(sessionId, "Agent结果", label + " ⏸ 已通过Nacos开关关闭");
            log.info("{} disabled by Nacos agent runtime config", label);
            return result;
        }

        Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
        RemoteTimeout.configure(orchestration);
        A2aAgent agent = getAgent(name); // 复用缓存的A2A远程Agent代理

        log.info("============");
        log.info("获取到的远程Agent描述：" + agent.getDescription());
        log.info("============");

        // ★A2A传输级重试：超时/异常/空返回 → 带失败信号重发1次（代码级，与子Agent内工具重试相互独立）
        String emptyMsg0 = label + "没有返回内容";
        String result = null;
        String lastFailReason = ""; // ★2026-09-11 错误语义透传：记录最后一次失败的真实原因（原来一律显示"超时/空"，402/超时/断流真因被吞）
        long callStartMs = System.currentTimeMillis(); // 健康统计：整次调用（含重试）计时
        utils.CallTrace.Span a2aSpan = utils.CallTrace.begin(sessionId, "A2A", name, ""); // ★调用链：每轮尝试记入 span.attempts
        log.info("[A2A] {} 开始调用（超时上限 {}s，最多重试1次，任务长度 {} 字符）",
                label, RemoteTimeout.timeout().toSeconds(), task == null ? 0 : task.length());
        for (int attempt = 1; attempt <= 2; attempt++) {
            // ★2026-09-11 根因探针（回答"大任务为什么返回空串"）：
            //   记录每次 attempt 的【任务长度 / 实际耗时 / 返回长度 / 成败】。
            //   用途：把"成功"和"空返回"两组按任务长度和耗时对照，验证/否定"任务越大越易空返回"，
            //   并看清空返回究竟发生在"提交后多久"（是立即空、还是等了一段时间才空）。
            long attemptStartMs = System.currentTimeMillis();
            // ★2026-09-05 每 attempt 独立预算：deadline 在循环内重算——原 300s 总预算被 attempt1 耗尽后 attempt2 秒弃(0ms 直接失败)，超时重发形同虚设
            long deadline = System.nanoTime() + RemoteTimeout.timeout().toNanos();
            // ★任务级调用链：会话标记随任务文本传给子Agent，子Agent进程的 LLM/工具 span 据此归位
            String taskToSend = "【trace:" + sessionId + "】" + task;
            if (attempt > 1) {
                // ★注意：必须同样带上【trace:】前缀——原先重试把前缀丢了，导致子Agent 侧的任务文本
                // 与主管回捞时算的指纹不一致（回捞必然 miss），也丢了子Agent 进程内的 span 归位标记。
                taskToSend = "【trace:" + sessionId + "】" + task
                        + "\n\n【重试提示】上一次调用超时或返回空。请务必调用你的工具完成查询并输出完整结果。";
                // 兜底：异常路径未捕获到原因时也要给出可读说明（否则出现空的 "( )"，等于白改）
                String shown = lastFailReason.isBlank() ? "未捕获到具体原因(传输层空返回/中断)" : lastFailReason;
                EventStore.add(sessionId, "Agent结果", label + " ⚠️ 第1次调用失败(" + shown + ")，主管重发(第2次)...");
                log.warn("{} 第1次A2A调用失败({})，重发中", label, shown);
            }
            try {
                Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
                if (remaining.isNegative() || remaining.isZero()) {
                    result = emptyMsg0;
                    lastFailReason = classifyFail("剩余超时(超时上限已到)");
                    a2aSpan.addAttempt(attempt, false, 0, "剩余超时(超时上限已到)");
                } else {
                    EventStore.setInterruptCallback(sessionId, agent::interrupt);
                    Msg response;
                    try {
                        response = agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder()
                                                .text(taskToSend)
                                                .build())
                                        .build()
                                ).block(remaining);
                    } finally {
                        EventStore.clearInterruptCallback(sessionId);
                    }
                    result = (response != null && response.getTextContent() != null)
                            ? response.getTextContent()
                            : emptyMsg0;
                    // ★2026-09-11 记账漏洞修复：原来只有"恰好等于 emptyMsg0"才记失败原因，而 A2A 返回
                    // 【空白串】（非 null 的 "" / "   "）时，既不会在这里记账、也不会在下面的 break 处放行 ——
                    // 于是进了重试却查不到原因，日志只能显示"未捕获到具体原因"（把「没记账」误报成「不知道原因」）。
                    // 判定与 break 处统一按 isBlank()，并区分是"空对象"还是"空白文本（流被截断）"。
                    boolean blankReply = result == null || result.isBlank();
                    // ★2026-09-11 根因探针：每次 attempt 一行，把「任务长度 / 实际耗时 / 返回长度 / 成败」对齐，
                    //   用数据回答"大任务为什么返回空串"（对照成功组与空返回组的任务长度和耗时分布）。
                    log.warn("[A2A探针] {} attempt={} 耗时={}ms 任务长度={} 返回长度={} 结果={}",
                            label, attempt, System.currentTimeMillis() - attemptStartMs,
                            taskToSend.length(), result == null ? -1 : result.length(),
                            blankReply ? "空返回" : "成功");
                    if (blankReply) {
                        String why = (result == null || result.equals(emptyMsg0))
                                ? "返回为空(A2A返回空对象)"
                                : "返回为空白文本(len=" + result.length()
                                  + (result.length() == 0 ? ",A2A返回空串→子Agent无产出/流被掐断"
                                                          : ",长度>0但全空白→A2A流被截断/中断") + ")";
                        lastFailReason = why;
                        a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs, why);
                        // ★2026-09-11 针对性补救：空返回发生得比任务真正完成早得多（实测 38s 断 / 72s 才跑完），
                        //   说明子Agent 多半还在跑。此时先"边等边回捞"（最多 90s、每 10s 一次），
                        //   捞到就直接用——省掉一次完整重发（那是再起一个任务、再等 70~90s）。
                        if (attempt == 1) {
                            // ★2026-09-12 窗口 90s→240s：实测子Agent 单天任务常在 100~200s，
                            //   90s 窗口必然 miss → 触发"重发"，而重发会与仍在跑的旧任务抢 8085 并发，
                            //   形成"越重发越慢"的恶性循环。窗口给足，等它跑完直接回捞（比重发省 100~300s）。
                            String polled = pollRefetch(name, sessionId, task, callStartMs, 240_000L, 10_000L);
                            if (polled != null && !polled.isBlank()) {
                                result = polled;
                                lastFailReason = "";
                                a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "空返回后轮询回捞成功");
                                EventStore.add(sessionId, "Agent结果",
                                        label + " ↻ A2A返回空，未重发而是等子Agent跑完后回捞到结果(" + polled.length() + " 字符)");
                                break;
                            }
                        }
                        result = emptyMsg0;
                    } else {
                        lastFailReason = "";
                        a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "");
                    }
                }
            } catch (Exception e) {
                // ★用户中断：InterruptedException 或取消标志已置位 → 不重试，直接终止（否则中断会被当成失败重发第2次，永远停不下来）
                if (e instanceof InterruptedException || EventStore.isCancelRequested(sessionId)) {
                    Thread.currentThread().interrupt();
                    a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs, "用户中断");
                    throw new managerAgent.hook.CancelledException();
                }
                log.warn("{} A2A调用异常(attempt={}): {}", label, attempt, e.getMessage());
                // ★2026-09-12 根治改动：超时/传输异常后【不再立刻中断远端任务】。
                //   实测依据：子Agent 往往已经跑完或快跑完，只是回程（A2A 流式响应）被切断；
                //   一中断就等于销毁"已产出的结果"——回捞必然 miss，只能整个重发（每次多花 100~300s）。
                //   正确顺序：先回捞（给子Agent 一点时间把结果存进 /lastResult），捞不到才中断。
                String em = e.getMessage() == null ? "" : e.getMessage();
                lastFailReason = classifyFail(em.isBlank() ? "异常(无消息)" : em);
                a2aSpan.addAttempt(attempt, false, System.currentTimeMillis() - callStartMs,
                        em.isBlank() ? "异常" : (em.length() > 120 ? em.substring(0, 120) : em));
                // ★2026-09-12 根治：异常后先"边等边回捞"（子Agent 多半还在跑/刚跑完），
                //   捞到就直接用——既不销毁子Agent 的劳动，也省掉一次完整重发。
                if (attempt == 1) {
                    String caught = pollRefetch(name, sessionId, task, callStartMs, 240_000L, 10_000L);
                    if (caught != null && !caught.isBlank()) {
                        result = caught;
                        a2aSpan.addAttempt(attempt, true, System.currentTimeMillis() - callStartMs, "异常后回捞成功");
                        EventStore.add(sessionId, "Agent结果", label + " ↻ A2A异常(" + lastFailReason
                                + ")，未中断子Agent，等它跑完回捞到结果(" + caught.length() + " 字符)");
                        lastFailReason = "";
                        break;
                    }
                }
                // 回捞不到，此时才中断远端任务（避免它继续空跑、与重发的任务叠加）
                try {
                    agent.interrupt();
                } catch (Exception ignored) { }
                result = emptyMsg0;
            }
            if (result != null && !result.isBlank() && !result.equals(emptyMsg0)) {
                break; // 拿到有效结果
            }
        }
        String emptyMsg = emptyMsg0;
        // ★2026-09-11 结果回捞（方案①）：A2A 超时/空返回时，先向子Agent 回取"它其实已产出的结果"。
        //   子Agent 常常已经跑完了，只是回程（A2A 流式响应）中断，主管只拿到开头 14~128 字符就判失败。
        //   捞到即按成功处理，避免"重发→再失败→天骨架失败→回退整单→降级"这条链。
        if (result == null || result.isBlank() || result.equals(emptyMsg0)) {
            String salvaged = refetchLastResult(name, sessionId, task, callStartMs);
            if (salvaged != null && !salvaged.isBlank()) {
                result = salvaged;
                lastFailReason = ""; // 已救回，不再算失败
                log.warn("{} A2A调用失败，但已从子Agent回捞到结果({} 字符)", label, salvaged.length());
            }
        }
        // 健康统计：按最终结果记录成功/失败与耗时
        boolean callOk = result != null && !result.isBlank() && !result.equals(emptyMsg);
        long callCostMs = System.currentTimeMillis() - callStartMs;
        recordCall(name, callOk, callCostMs);
        // ★任务级调用链：A2A 调用明细（每轮尝试已入 span.attempts，管理端叶子内下拉展示）
        a2aSpan.success = callOk;
        a2aSpan.costMs = callCostMs;
        int failedTries = 0;
        for (java.util.Map<String, Object> at : a2aSpan.attempts) {
            if (!Boolean.TRUE.equals(at.get("ok"))) failedTries++;
        }
        a2aSpan.detail = callOk
                ? (failedTries > 0 ? "子Agent调用成功(重试后)" : "子Agent调用成功")
                : "子Agent调用失败(重试后仍失败)";
        // 记录子Agent调用结果事件（★2026-09-11 失败时透出真因，便于区分 402余额/超时/断流/空返回）
        String displayReason = lastFailReason.isBlank() ? "重试后仍空" : lastFailReason;
        if (!callOk) { // ★方案B：A2A 失败时向子Agent查最近 LLM 错误（402 等在子Agent进程内被 A2A 吞成空响应）
            String remoteErr = queryRemoteLastError(name, sessionId, callStartMs);
            if (remoteErr != null && !remoteErr.isBlank()) {
                displayReason = displayReason + " ← 子Agent真因: " + remoteErr;
                log.warn("{} 子Agent上报真因: {}", label, remoteErr);
            }
        }
        EventStore.add(sessionId, "Agent结果", callOk
                ? label + " ✅ 调用成功(返回" + result.length() + "字符)"
                : label + " ❌ 调用失败(" + displayReason + ")");
        log.info("============");
        log.info(label + "返回结果：" + result);
        log.info("============");
        return result;
    }

    /**
     * 调用路线制定Agent，返回规划结果
     */
    @Tool(description = "从Nacos注册中心获取路线制定Agent，返回自驾游路线规划结果")
    public String callRouteMakingAgent(
            @ToolParam(name = "task", description = "用户需要规划的具体自驾路线任务描述，例如：请规划从广州到珠海的自驾路线，包含每日行车路线、途经景点和驾驶时间")
            String task) throws NacosException {
        return callRemoteAgent("RouteMakingAgent", task, "路线Agent");
    }

    /**
     * 调用行程规划Agent，返回规划结果
     */
    /**
     * ★2026-09-11 8085(行程Agent) 并发硬限：代码原注释记录"实测 4+ 并发 A2A 时后提交的任务会失败"。
     * 天骨架（批内并行）与多方案（3 套并行）会从多条路径调用同一个 8085，靠 batchSize=3 这种局部约定
     * 守不住（例如审查阶段天级重编若改成并行就会悄悄越线）。这里对"同时打到行程Agent 的请求数"做
     * 进程级硬限，超出的请求排队等待而不是失败。可用 TRIP_AGENT_MAX_CONCURRENCY 调整（默认 3）。
     */
    private static final java.util.concurrent.Semaphore TRIP_AGENT_SLOTS =
            new java.util.concurrent.Semaphore(utils.Config.getInt("TRIP_AGENT_MAX_CONCURRENCY", 3));

    @Tool(description = "从Nacos注册中心获取行程规划Agent，返回指定目的地的景点和行程规划结果")
    public String callTripPlannerAgent(
            @ToolParam(name = "task", description = "用户需要规划的具体旅行任务描述，例如：请规划珠海2日游的景点、每日行程安排、酒店和美食推荐")
            String task) throws NacosException {
        try {
            TRIP_AGENT_SLOTS.acquire(); // 超过并发上限则排队（而非像原来那样直接失败）
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new managerAgent.hook.CancelledException();
        }
        try {
            return callRemoteAgent("TripPlannerAgent", task, "行程Agent");
        } finally {
            TRIP_AGENT_SLOTS.release();
        }
    }
}
