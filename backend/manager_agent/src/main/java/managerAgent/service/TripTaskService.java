package managerAgent.service;

import dto.TripPlanData;
import dto.TripRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.orchestrator.TripOrchestrator;
import managerAgent.tool.ConcurrencyGate;
import org.springframework.stereotype.Service;

import java.util.Map;
import utils.JsonUtils;

/**
 * 行程任务的提交与观测业务：并发闸门、后台编排执行、结果读取、中断、终态落库。
 * AppController 只做 HTTP 适配，这里不含任何请求/响应处理。
 */
@Slf4j  // @Slf4j：生成 log（下面 catch 里用 log.error）
@Service  // @Service：注册为 Spring Bean，供 AppController 注入
public class TripTaskService {  // 类声明：TripTaskService

    @Resource  // @Resource：注入画像服务
    private ProfileService profileService;  // 字段 ProfileService：取“一行画像文本”和结构化偏好

    /** 任务池：core=0 / max=无上限 / SynchronousQueue（容量 0 的交接队列，不缓冲）。
     *  并发上限由 TASK_GATE 在 submit 前把关（默认 1，管理端/Nacos 可调）；不设拒绝策略，
     *  否则长任务会被甩回 HTTP 线程导致请求超时。PARALLEL_POOL 负责任务内部的并行子步骤。 */
    private static final java.util.concurrent.ExecutorService TASK_POOL =  // 声明静态常量 TASK_POOL：整个进程共用一个任务池
            new java.util.concurrent.ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,  // 构造 ThreadPoolExecutor：核心线程 0，最大 Integer.MAX_VALUE，空闲 60 秒回收
                    java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());  // 队列用 SynchronousQueue（容量 0，不缓冲）：提交即交接给新线程，等于“来一个任务开一个线程”——真正的限流靠闸门，不靠池容量

    //并发闸门：支持 Nacos 运行时调整，非法值不生效
    private static final ConcurrencyGate TASK_GATE = new ConcurrencyGate(initialMax());  // 静态初始化 TASK_GATE：用 initialMax() 作为初始上限（构造时会夹紧到 [1,32]）

    /**
     * 作用：读取当前允许的最大并发任务数
     * 参数：无
     * 返回值：并发上限
     * 异常：无
     */
    public static int maxConcurrent() {  // 方法签名：public static int maxConcurrent()
        syncConcurrency();  // 先 syncConcurrency() 同步最新配置（管理端刚改的值立刻生效）
        return TASK_GATE.max();  // 返回 TASK_GATE.max()
    }  // 方法结束

    /**
     * 作用：读取当前已占用的并发槽数（管理端监控用）
     * 参数：无
     * 返回值：占用槽数
     * 异常：无
     */
    public static int inUseSlots() {  // 方法签名：inUseSlots()
        return TASK_GATE.active();  // 直接返回闸门的 active 计数（注意这里不同步配置，只读当前值）
    }  // 方法结束

    /**
     * 作用：提交规划任务：并发闸限流 → 清旧事件 → 后台线程创建编排器执行（单方案或多方案）→ 结果写入会话缓存并落库观测
     * 参数：sessionId 会话 ID；prompt 用户需求文本；multiScheme 是否生成多方案；skipPrefs 是否跳过个人画像与偏好注入；userId 登录用户 ID，可为 null
     * 返回值：提示文本（已接收/并发已满）
     * 异常：无（执行异常在后台线程内捕获并写入失败结果）
     */
    public String submit(String sessionId, String prompt, String multiScheme, boolean skipPrefs, Long userId) {  // 方法签名：注意它是同步返回的——只负责受理，不等行程跑完
        //获取并发槽位，失败则拒绝
        syncConcurrency();  // syncConcurrency()：每次提交前把 Nacos 的并发上限同步进闸门
        if (!TASK_GATE.tryAcquire()) {  // tryAcquire() 抢槽：抢不到就走下面的拒绝分支（不排队）
            return "⚠️ 有任务正在执行中（最多同时 " + TASK_GATE.max() + " 个），请等当前任务完成后再试！";  // 返回“⚠️ 有任务正在执行中（最多同时 N 个），请等当前任务完成后再试！”——N 是当前上限
        }  // if 结束
        //绑定会话归属：防止他人凭 sessionId 越权读取/取消本用户任务
        if (userId != null) {  // 只有登录用户（userId 非 null）才绑定
            EventStore.bindOwner(sessionId, userId);  // EventStore.bindOwner：把 sessionId→userId 记进内存映射
        }  // if 结束
        EventStore.clear(sessionId); // 清空该会话的旧事件  // EventStore.clear：清空该会话旧事件（同一会话重新提交不能看到上一轮残留）
        utils.CallTrace.clear(sessionId); // 清空调用链旧span，防重复提交时新旧混染  // CallTrace.clear：清空该会话旧调用链 span，防止新旧混染
        EventStore.startTask(sessionId, userId == null ? -1 : userId); // 任务监控：登记开始  // EventStore.startTask：登记任务开始（监控、耗时、模型调用计数从这里起算）

        TASK_POOL.submit(() -> {  // TASK_POOL.submit：把整段编排丢进任务池，HTTP 请求在这之后立即返回--异步
            String taskStatus = "DONE"; // 任务最终状态（异常路径改写）  // taskStatus 默认 DONE，异常/取消分支会改写，finally 里用它落库
            try {  // try 开始
                TripOrchestrator orchestrator = new TripOrchestrator(sessionId); // 确定性编排器（会话隔离）  // new TripOrchestrator(sessionId)：每个任务一个编排器实例，事件与取消按会话隔离
                //用户画像注入：与用户输入放一起
                String profileLine = (userId != null && !skipPrefs) ? profileService.profileLineForPlanning(userId) : "";  // 登录用户且未跳过偏好时取一行画像文本；否则空串
                String finalPrompt = profileLine + prompt;  // 把画像拼在用户需求前面，作为最终提示词
                if ("true".equalsIgnoreCase(multiScheme)) { // 多方案模式  // 多方案分支：multiScheme 忽略大小写等于 "true"
                    dto.PlanSet planSet = orchestrator.runMultiScheme(finalPrompt);  // 调 runMultiScheme：并行产出多套风格方案（PlanSet）
                    EventStore.setResult(sessionId, JsonUtils.toJson(planSet)); // 缓存多方案 JSON  // 把 PlanSet 序列化成 JSON 写进结果缓存（前端轮询拿到的就是这个）
                } else {  // 否则走单方案
                    TripPlanData plan;  // 声明 plan：单方案结构化行程
                    if (userId != null && !skipPrefs) { // 注入个人中心沉淀偏好  // 登录用户且未跳过偏好
                        TripRequest.Preferences prefs = profileService.preferencesForPlanning(userId);  // 取结构化 Preferences（常去/偏好/风格等）
                        plan = orchestrator.run(finalPrompt, prefs);  // run(finalPrompt, prefs)：带偏好执行编排
                    } else {  // 否则
                        plan = orchestrator.run(finalPrompt); // 解析->偏好分类->并行DAG->聚合  // run(finalPrompt)：不带偏好执行编排
                    }  // if 结束
                    EventStore.setResult(sessionId, JsonUtils.toJson(plan)); // 缓存结构化 JSON 结果（含降级 markdown 字段）  // 把 TripPlanData 序列化成 JSON 写进结果缓存（含降级 markdown 字段）
                }  // else 结束
            } catch (CancelledException e) {  // 捕获 CancelledException：用户点了中断
                taskStatus = "CANCELLED";  // 终态标记 CANCELLED
                EventStore.setResult(sessionId, "⏹ 任务已由用户中断。"); // 用户取消的友好提示（类型化判断）  // 结果缓存写“⏹ 任务已由用户中断。”——前端轮询拿到这句就不再转圈
            } catch (Exception e) {  // 捕获其他异常
                taskStatus = "FAILED";  // 终态标记 FAILED
                EventStore.setResult(sessionId, "❌ 任务执行出错：" + e.getMessage()); // 缓存错误信息  // 结果缓存写“❌ 任务执行出错：消息”——异常不抛出去，用户能看到失败原因
                log.error("任务执行失败: {}", e.getMessage(), e);  // 同时打 error 日志（含堆栈），方便排查
            } finally {  // finally：无论成功失败都执行
                TASK_GATE.release(); // 释放并发槽位  // release()：释放并发槽（漏了会永久占位，之后所有任务都被拒）
                EventStore.TaskMeta doneMeta = EventStore.finishTask(sessionId, taskStatus); // 登记结束并计数  // finishTask：登记任务结束并返回统计元信息（步数、模型调用数、提交/活跃时间）
                persistObservation(sessionId, taskStatus, userId, doneMeta); // 观测落库：任务历史 + 调用链span  // persistObservation：把任务历史、三进程 token 成本、调用链 span 落库（内部全部 try-catch，失败不影响交付）
            }  // finally 结束
        });  // lambda 结束，后台任务完成
        return "✅ 任务已接收，正在后台执行中！请查看思考过程，完成后结果自动显示。";  // 返回受理成功提示（此时任务刚开始跑）
    }  // 方法结束

    /**
     * 作用：轮询任务结果：校验会话归属后返回最终结果
     * 参数：sessionId 会话 ID；userId 登录用户 ID，可为 null
     * 返回值：结果文本；未就绪时返回进行中提示
     * 异常：无
     */
    public String result(String sessionId, Long userId) {  // 方法签名：result(String sessionId, Long userId)
        if (!EventStore.isKnown(sessionId)) {  // 先判任务是否存在（服务重启后内存清空，旧任务会丢）
            return "❌ 任务不存在或已失效，请重新提交"; // 服务重启后旧任务丢失：让前端清理恢复状态  // 不存在：返回“❌ 任务不存在或已失效，请重新提交”，让前端清理状态、别一直转圈
        }  // if 结束
        if (userId == null || !EventStore.isOwner(sessionId, userId)) {  // userId 为空（游客）或不是该会话归属人
            return "⏳ 任务还在执行中..."; // 非本人会话：不泄露他人结果  // 返回“⏳ 任务还在执行中…”而不是“无权”，不泄露“这个会话存在且产出过结果”
        }  // if 结束
        String r = EventStore.getResult(sessionId);  // 取结果缓存
        return r == null ? "⏳ 任务还在执行中..." : r;  // null 表示还没产出，回“执行中”；否则返回结果文本（单方案 JSON / 多方案 JSON / 失败文案）
    }  // 方法结束

    /**
     * 作用：请求中断任务：校验归属后置取消标志并打断正在执行的远程调用
     * 参数：sessionId 会话 ID；userId 登录用户 ID，可为 null
     * 返回值：操作提示文本
     * 异常：无
     */
    public String cancel(String sessionId, Long userId) {  // 方法签名：cancel(sessionId, userId)
        if (userId == null || !EventStore.isOwner(sessionId, userId)) {  // 归属校验不过（未登录或不是本人）
            return "❌ 无权操作该任务";  // 返回“❌ 无权操作该任务”
        }  // if 结束
        EventStore.requestCancel(sessionId);  // EventStore.requestCancel：置 cancelRequested=true，并执行已注册的中断回调（A2A 期间是 agent::interrupt）
        return "✅ 已发送中断请求，任务将在下一步停止。";  // 返回“✅ 已发送中断请求，任务将在下一步停止。”——真正停下发生在编排器下次 checkCancel 或 A2A 抛中断时
    }  // 方法结束

    /**
     * 作用：任务终态观测落库：写任务历史、汇总三进程的模型成本与调用链 span
     * 参数：sessionId 会话 ID；taskStatus 终态；userId 用户 ID；meta 任务元信息
     * 返回值：无
     * 异常：无（全部内部捕获，落库失败不影响交付）
     */
    private static void persistObservation(String sessionId, String taskStatus, Long userId,  // 签名第 1 行：private static void persistObservation(…
                                           EventStore.TaskMeta meta) {  // 签名第 2 行：… EventStore.TaskMeta meta)
        try {  // try 包住全部落库动作：任何一步失败都不能影响任务结果
            long uid = userId == null ? -1 : userId;  // uid：未登录用 -1 占位（数据库列是非空数字）
            if (meta == null) return;  // meta 为空（任务没登记成功）就直接返回
            int modelCalls = meta.modelCallsUsed > 0 ? (int) meta.modelCallsUsed : 0;  // modelCalls：元信息里的模型调用数，负数归零
            utils.TracePersistence.upsertTask(sessionId, uid, taskStatus, meta.currentStep,  // upsertTask：写任务历史表（会话、用户、终态、当前步骤、提交时间、最后活跃、模型调用数、备注）
                    meta.submitMs, meta.lastActiveMs, modelCalls, "");  // 参数续行：submitMs、lastActiveMs、modelCalls、""（空备注）
            //成本落库：任务终态把主管内存累计+两子Agent tokens 写当日维度表
            try {  // 内层 try：成本落库失败不影响任务历史
                for (var e : utils.SafeModel.dimensionStatsSnapshot().entrySet()) {  // 遍历主管进程的维度统计快照（按模型/阶段维度累计）
                    long[] st = e.getValue();  // 取出该维度的三元组：调用数、输入 token、输出 token
                    utils.TracePersistence.upsertLlmCost(e.getKey(), st[0], st[1], st[2]);  // upsertLlmCost：按维度累加写入当日成本表
                }  // 主管维度循环结束
                for (String[] agent : new String[][]{{"行程Agent", String.valueOf(utils.AgentEndpoints.tripPort())}, {"路线Agent", String.valueOf(utils.AgentEndpoints.routePort())}}) { // 子Agent维度  // 遍历两个子 Agent：[名称, 端口]（行程 Agent、路线 Agent）
                    try {  // 内层 try：单个子 Agent 取不到不影响整体
                        java.net.URL u = new java.net.URL(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/stats");  // 拼地址：该 Agent 的 base + /stats
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();  // 打开 HTTP 连接
                        conn.setConnectTimeout(1500); conn.setReadTimeout(1500);  // 连接/读取超时都给 1500ms——这是健康统计，不能拖慢任务收尾
                        if (conn.getResponseCode() == 200) {  // 只有 200 才解析
                            Map<?, ?> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(conn.getInputStream(), Map.class);  // 用 Jackson 把 /stats 的 JSON 读成 Map
                            long mc = json.get("model_calls") instanceof Number n ? n.longValue() : 0;  // 取 model_calls（不是数字就 0）
                            long ti = json.get("tokens_in") instanceof Number n ? n.longValue() : 0;  // 取 tokens_in
                            long to = json.get("tokens_out") instanceof Number n ? n.longValue() : 0;  // 取 tokens_out
                            if (mc > 0) utils.TracePersistence.upsertLlmCost(agent[0], mc, ti, to);  // 调用数大于 0 才写成本表（避免把“没调用”当 0 成本刷一行）
                        }  // if 结束
                        conn.disconnect();  // 断开连接（及时释放 socket）
                    } catch (Exception ignore) { }  // 异常只 ignore：子 Agent 没起/端口不通都不影响
                }  // 子 Agent 循环结束
            } catch (Exception ignored) { }  // 外层 catch ignored：整段成本统计失败直接跳过
            //调用链 span：合并主管(内存) + 两子Agent(/trace)，终态后一次性落库
            java.util.List<Map<String, Object>> spans = new java.util.ArrayList<>(  // 初始化 spans 列表，先放入主管进程内存里的调用链
                    utils.CallTrace.traceAsMaps(sessionId));  // 参数续行：CallTrace.traceAsMaps(sessionId)
            for (String[] agent : new String[][]{{"路线", String.valueOf(utils.AgentEndpoints.routePort())}, {"行程", String.valueOf(utils.AgentEndpoints.tripPort())}}) {  // 遍历两个子 Agent（路线、行程）
                try {  // 内层 try
                    java.net.URL u = new java.net.URL(utils.AgentEndpoints.forAgentLabel(agent[0]) + "/trace?sessionId="  // 拼地址：base + /trace?sessionId=
                            + java.net.URLEncoder.encode(sessionId, java.nio.charset.StandardCharsets.UTF_8));  // sessionId 做 URL 编码（中文/特殊字符安全）
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();  // 打开连接
                    conn.setConnectTimeout(2000); conn.setReadTimeout(2000);  // 超时 2000ms（trace 数据比 stats 大一点）
                    if (conn.getResponseCode() == 200) {  // 只处理 200
                        Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper()  // 解析 JSON
                                .readValue(conn.getInputStream(), Map.class);  // 读取响应体为 Map
                        if (parsed.get("spans") instanceof java.util.List<?> list) {  // 确认 spans 是列表
                            for (Object o : list) {  // 逐条遍历
                                if (o instanceof Map<?, ?> m) {  // 确认每条是 Map
                                    Map<String, Object> mm = new java.util.HashMap<>(m.size() + 1);  // 复制一份（不直接改子 Agent 返回的对象）
                                    mm.putAll((Map<String, Object>) m);  // putAll 原字段
                                    mm.put("agent", agent[0]);  // 打上来源标记 agent（路线/行程），便于前端区分
                                    spans.add(mm);  // 加入总列表
                                }  // if 结束
                            }  // for 结束
                        }  // if 结束
                    }  // if 200 结束
                    conn.disconnect();  // 断开连接
                } catch (Exception ignored) { }  // 异常 ignore
            }  // 子 Agent 循环结束
            spans.sort((a, b) -> Long.compare(  // 按 ts 升序排序（时间线展示需要）
                    ((Number) a.getOrDefault("ts", 0L)).longValue(),  // 取 a 的 ts，默认 0
                    ((Number) b.getOrDefault("ts", 0L)).longValue()));  // 取 b 的 ts，默认 0
            utils.TracePersistence.saveSpans(sessionId, spans);  // saveSpans：整批写入库（终态后一次性落，不做逐条写）
        } catch (Exception e) {  // 最外层 catch
            System.err.println("[persistObservation] 落库失败(忽略): " + e.getMessage());  // 落库失败只打 stderr，绝不向上抛
        }  // catch 结束
    }  // 方法结束
    /**
     * 作用：读取并规整初始并发上限（夹紧到合法区间）
     * 参数：无
     * 返回值：初始并发上限
     * 异常：无
     */

    private static int initialMax() {  // 方法签名：private static int initialMax()
        try {  // try
            return Integer.parseInt(System.getenv().getOrDefault("MAX_CONCURRENT_TASKS", "1").trim());  // 从环境变量 MAX_CONCURRENT_TASKS 取，默认 "1"，trim 后 parseInt 返回
        } catch (NumberFormatException e) {  // 解析失败（填了非数字）
            return 1;  // 回落 1：保证服务一定能启动，不会因为环境变量写错就起不来
        }  // catch 结束
    }  // 方法结束
    /**
     * 作用：把配置中的并发上限同步到并发闸（配置热更后调用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private static void syncConcurrency() {  // 方法签名：private static void syncConcurrency()
        java.util.Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();  // 取 Nacos 内存里的 orchestration 配置段（LinkedHashMap 复制一小段，几乎没有开销）
        if (orchestration == null) {  // 一次都没拉到配置
            return;  // 直接返回：保持启动时的值，不用空配置覆盖
        }  // if 结束
        if (orchestration.get("maxConcurrentTasks") instanceof Number number) {  // 配置里有 maxConcurrentTasks 且是数字
            TASK_GATE.setMax(number.intValue());  // setMax 更新闸门（内部夹紧到 [1,32]）
        } else {  // 否则（配置缺这一项 / 类型不对）
            TASK_GATE.setMax(initialMax());  // 回落到环境变量值，避免沿用脏值
        }  // if 结束
    }  // 方法结束
}  // 类结束

