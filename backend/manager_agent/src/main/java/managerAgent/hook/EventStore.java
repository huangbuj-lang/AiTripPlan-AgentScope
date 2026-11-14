package managerAgent.hook;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 内存事件库：按会话保存Agent运行事件，供 /events 查询
 */
public class EventStore {  // 类声明：EventStore（全静态工具类）

    /** 单会话状态：事件队列 + 最终结果 + 取消标志 */
    public static class SessionState {  // 内部类 SessionState：一个会话的全部内存状态
        final ConcurrentLinkedQueue<Map<String, String>> events = new ConcurrentLinkedQueue<>(); // 事件队列（线程安全）  // events：事件队列（ConcurrentLinkedQueue，多线程读写安全）
        volatile String result = null;        // 最终结果缓存  // result：最终结果缓存（volatile，写后立即可见）
        volatile boolean cancelRequested = false; // 中断请求标志  // cancelRequested：中断请求标志（volatile）
        volatile Runnable interruptCallback = null; // 取消时回调（用于中断A2A远程调用）  // interruptCallback：取消时执行的回调（A2A 期间注册，用于打断远程调用）
        volatile long lastAccessMs = System.currentTimeMillis(); // 最后访问时间（TTL清理用）  // lastAccessMs：最后访问时间，供 TTL 清理
        public final java.util.concurrent.ConcurrentHashMap<String, Object> extras =  // extras：结构化观测数据容器（如 REVISE 汇总、聚类统计）
                new java.util.concurrent.ConcurrentHashMap<>(); // 结构化观测数据（REVISE汇总/聚类统计）  // extras 初始化：并发 Map，键值对形式挂任意对象
    }  // 内部类结束

    private static final ConcurrentHashMap<String, SessionState> SESSIONS = new ConcurrentHashMap<>(); // 会话隔离容器  // SESSIONS：sessionId → SessionState 的全局容器（会话隔离）

    // ==================== 任务监控注册表 ====================

    /** 单条任务的监控元数据 */
    public static class TaskMeta {  // 内部类 TaskMeta：任务级监控信息
        public final String sessionId;  // sessionId：所属会话（final，创建后不变）
        public volatile long userId = -1;          // 提交人（-1=匿名）  // userId：提交人，-1 表示匿名
        public final long submitMs = System.currentTimeMillis(); // 提交时间  // submitMs：提交时间（final，创建即定）
        public volatile long lastActiveMs = System.currentTimeMillis(); // 最后活动时间  // lastActiveMs：最后活动时间（每步刷新）
        public volatile String status = "RUNNING"; // RUNNING/DONE/FAILED/CANCELLED  // status：任务状态 RUNNING/DONE/FAILED/CANCELLED
        public volatile String currentStep = "排队中"; // 当前步骤（取最近一条事件的类型）  // currentStep：当前步骤，写法是“取最近一条事件的类型”
        public final long modelCallsStart = utils.SafeModel.MODEL_CALLS.get(); // 任务开始时的模型调用总数  // modelCallsStart：任务开始时的模型调用总数（结束时相减得出本任务消耗）
        public volatile long modelCallsUsed = -1;  // 本任务消耗的模型调用轮数（结束时=结束值-开始值，-1=未统计）  // modelCallsUsed：本任务消耗的模型调用轮数，-1 表示未统计
        public volatile int reviseRounds = 0;      // Reviewer 闭环实际返工轮数（0=首轮通过）  // reviseRounds：评审闭环实际返工轮数，0 表示首轮通过
        public volatile String reviseOutcome = ""; // pass/maxed/parse_error/rework_failed/rework_no_improvement  // reviseOutcome：返工结果标签（pass/maxed/parse_error/…）

        TaskMeta(String sessionId) { this.sessionId = sessionId; }  // TaskMeta 构造：只接受 sessionId，其余字段用默认值
    }  // 内部类结束

    /** 正在运行/近期任务：sessionId → 元数据 */
    private static final ConcurrentHashMap<String, TaskMeta> TASKS = new ConcurrentHashMap<>();  // TASKS：sessionId → TaskMeta（任务结束后移除）

    /** 历史任务环形队列（最多200条，新的在前） */
    private static final java.util.concurrent.ConcurrentLinkedDeque<TaskMeta> HISTORY = new java.util.concurrent.ConcurrentLinkedDeque<>();  // HISTORY：双端队列，addFirst 入、pollLast 出
    private static final int MAX_HISTORY = 200;  // MAX_HISTORY=200：历史上限

    /** 任务结果计数器（进程内存值，重启清零） */
    public static final java.util.concurrent.atomic.AtomicLong COUNT_DONE = new java.util.concurrent.atomic.AtomicLong();  // COUNT_DONE：成功任务数
    public static final java.util.concurrent.atomic.AtomicLong COUNT_FAILED = new java.util.concurrent.atomic.AtomicLong();  // COUNT_FAILED：失败任务数
    public static final java.util.concurrent.atomic.AtomicLong COUNT_CANCELLED = new java.util.concurrent.atomic.AtomicLong();  // COUNT_CANCELLED：取消任务数

    /**
     * 作用：登记任务开始：写入会话的任务元信息并初始化计数，供任务监控与超时清理
     * 参数：sessionId 会话 ID；userId 用户 ID（-1 表示未登录）
     * 返回值：无
     * 异常：无
     */
    public static void startTask(String sessionId, long userId) {  // startTask 签名
        TaskMeta meta = new TaskMeta(key(sessionId));  // new TaskMeta(key(sessionId))：用规整后的键构造（提交时间自动记为现在）
        meta.userId = userId;  // meta.userId = userId：记提交人
        TASKS.put(meta.sessionId, meta);  // TASKS.put：放进运行中任务表（finishTask 时移除）
    }  // 方法结束

    /**
     * 作用：登记任务终态：记录耗时与模型调用数、累加统计计数并写入历史队列
     * 参数：sessionId 会话 ID；status 终态（DONE/FAILED/CANCELLED）
     * 返回值：任务元信息快照；会话不存在时返回 null
     * 异常：无
     */
    public static TaskMeta finishTask(String sessionId, String status) {  // finishTask 签名
        String k = key(sessionId);  // 取规整后的键
        TaskMeta meta = TASKS.remove(k);  // 从运行中任务表移除并取出元信息（移除即“任务结束”）
        if (meta == null) {  // 没登记过（异常路径）
            meta = new TaskMeta(k); // 兜底：未登记过也计入历史  // 兜底新建一个，保证历史里也有这条记录
        }  // if 结束
        meta.status = status;  // 写入终态
        meta.lastActiveMs = System.currentTimeMillis();  // 刷新最后活动时间
        meta.modelCallsUsed = utils.SafeModel.MODEL_CALLS.get() - meta.modelCallsStart; // 本任务LLM轮数  // modelCallsUsed = 当前总数 - 开始时总数：本任务真实消耗的模型轮数
        switch (status) {  // 按终态分支
            case "DONE" -> COUNT_DONE.incrementAndGet();  // DONE → 成功计数 +1
            case "FAILED" -> COUNT_FAILED.incrementAndGet();  // FAILED → 失败计数 +1
            case "CANCELLED" -> COUNT_CANCELLED.incrementAndGet();  // CANCELLED → 取消计数 +1
            default -> { }  // 其他状态不计数
        }  // switch 结束
        HISTORY.addFirst(meta);  // addFirst：新任务放在历史最前（前端最新在上）
        while (HISTORY.size() > MAX_HISTORY) {  // 超过 200 条
            HISTORY.pollLast();  // 丢弃最旧的一条（pollLast）
        }  // while 结束
        return meta;  // 返回元信息（TripTaskService 用它落库观测）
    }  // 方法结束

    /**
     * 作用：读取任务历史（最近的在前）
     * 参数：无
     * 返回值：任务元信息列表
     * 异常：无
     */
    public static java.util.List<TaskMeta> listHistory() {  // listHistory 签名
        return new java.util.ArrayList<>(HISTORY);  // 复制一份返回（避免调用方直接改内部队列）
    }  // 方法结束

    /** sessionId → 归属用户ID（/app 提交时绑定，防止跨用户越权读取/取消） */
    private static final ConcurrentHashMap<String, Long> OWNERS = new ConcurrentHashMap<>();  // OWNERS：归属映射表

    /**
     * 作用：判断会话是否已注册（防越权读取/取消他人任务）
     * 参数：sessionId 会话 ID
     * 返回值：true=会话存在
     * 异常：无
     */
    public static boolean isKnown(String sessionId) {  // isKnown 签名
        return sessionId != null && (SESSIONS.containsKey(sessionId) || OWNERS.containsKey(sessionId));  // sessionId 非空，且事件容器或归属表里有它，才算存在
    }  // 方法结束

    /** sessionId → SSE 订阅者（实时推送事件，替代轮询） */
    private static final ConcurrentHashMap<String, Set<SseEmitter>> SUBSCRIBERS = new ConcurrentHashMap<>();  // SUBSCRIBERS：订阅者表

    private static final int MAX_EVENTS = 100; // 最多保留的事件条数（超出丢弃最旧的）  // MAX_EVENTS=100：单会话最多保留 100 条事件

    private static final long SESSION_TTL_MS = 30 * 60 * 1000L; // 会话30分钟无活动即清理  // SESSION_TTL_MS=30 分钟：无活动会话的清理阈值
    private static final int MAX_SESSIONS = 1000;               // 会话数量上限  // MAX_SESSIONS=1000：会话数量上限（超过触发清理）

    private static final String DEFAULT_SESSION = "default"; // 未传 sessionId 时的兜底会话  // DEFAULT_SESSION="default"：未传 sessionId 时的兜底键

    /**
     * 作用：把外部 sessionId 规整成内部键（null/空串统一为 default）
     * 参数：sessionId 原始会话 ID
     * 返回值：内部键字符串
     * 异常：无
     */
    private static String key(String sessionId) {  // key 签名：所有对外方法都先过它，保证键统一
        return (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;  // null 或空白 → "default"，否则原样返回
    }  // 方法结束

    /**
     * 作用：往会话的扩展信息里写入一个键值（任务级元数据，供前端/管理端读取）
     * 参数：sessionId 会话 ID；key 键名；value 任意值
     * 返回值：无
     * 异常：无
     */
    public static void putExtra(String sessionId, String key, Object value) {  // putExtra 签名
        state(sessionId).extras.put(key, value);  // 确保会话状态存在后，往 extras 里放值（如 clusterStats）
    }  // 方法结束

    /**
     * 作用：读取会话扩展信息中的某个键
     * 参数：sessionId 会话 ID；key 键名
     * 返回值：值对象；不存在返回 null
     * 异常：无
     */
    public static Object getExtra(String sessionId, String key) {  // getExtra 签名
        SessionState s = SESSIONS.get(key(sessionId));  // 直接查 SESSIONS（不创建会话，读不到就 null）
        return s == null ? null : s.extras.get(key);  // 会话不存在返回 null，否则取 extras 里的值
    }  // 方法结束

    /**
     * 作用：获取（不存在则创建）会话状态对象
     * 参数：sessionId 会话 ID
     * 返回值：该会话的 SessionState
     * 异常：无
     */
    private static SessionState state(String sessionId) {  // state 签名：私有，内部方法都靠它拿会话
        SessionState s = SESSIONS.computeIfAbsent(key(sessionId), k -> new SessionState());  // computeIfAbsent：没有就新建一个 SessionState（原子操作，并发安全）
        s.lastAccessMs = System.currentTimeMillis();  // 刷新最后访问时间（TTL 清理依据）
        if (SESSIONS.size() > MAX_SESSIONS) { // 超出上限时惰性清理过期会话  // 会话数超过上限
            cleanupExpired();  // 惰性清理过期会话（不是定时任务，省线程）
        }  // if 结束
        return s;  // 返回会话状态
    }  // 方法结束

    /**
     * 作用：清理超时未活动的会话状态与订阅者，防止内存泄漏
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    private static void cleanupExpired() {  // cleanupExpired 签名
        long now = System.currentTimeMillis();  // 取当前时间
        SESSIONS.entrySet().removeIf(e -> now - e.getValue().lastAccessMs > SESSION_TTL_MS);  // 移除超过 30 分钟没访问的会话
        OWNERS.keySet().removeIf(k -> !SESSIONS.containsKey(k));  // 归属表里已经没对应会话的键一并清掉
        SUBSCRIBERS.keySet().removeIf(k -> !SESSIONS.containsKey(k));  // 订阅者表同样清理孤儿键
    }  // 方法结束

    /**
     * 作用：为会话注册 SSE 订阅者：先推送历史事件再保持长连接
     * 参数：sessionId 会话 ID；emitter SSE 发射器
     * 返回值：无
     * 异常：无（推送失败时移除该订阅者）
     */
    public static void subscribe(String sessionId, SseEmitter emitter) {  // subscribe 签名
        SUBSCRIBERS.computeIfAbsent(key(sessionId), k -> ConcurrentHashMap.newKeySet()).add(emitter);  // 没有集合就建一个线程安全的 Set，再把 emitter 加进去
    }  // 方法结束

    /**
     * 作用：移除会话的 SSE 订阅者
     * 参数：sessionId 会话 ID；emitter SSE 发射器
     * 返回值：无
     * 异常：无
     */
    public static void unsubscribe(String sessionId, SseEmitter emitter) {  // unsubscribe 签名
        Set<SseEmitter> set = SUBSCRIBERS.get(key(sessionId));  // 取该会话的订阅者集合
        if (set != null) {  // 存在才处理
            set.remove(emitter);  // 从集合里删除这个 emitter
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：向会话的全部订阅者广播一条事件（无订阅者则只落内存）
     * 参数：sessionId 会话 ID；event 事件 Map
     * 返回值：无
     * 异常：无（单个订阅者发送失败只移除该订阅者）
     */
    private static void notify(String sessionId, Map<String, String> event) {  // notify 签名
        Set<SseEmitter> set = SUBSCRIBERS.get(key(sessionId));  // 取订阅者集合
        if (set == null) {  // 没有订阅者（前端没开 SSE）
            return;  // 直接返回，事件只留在内存队列里
        }  // if 结束
        for (SseEmitter emitter : set) {  // 逐个订阅者发送
            try {  // try：客户端可能已断开
                emitter.send(SseEmitter.event().name("event").data(event));  // 发一条 SSE，事件名 event，数据是这条事件
            } catch (Exception e) {  // 捕获发送异常
                set.remove(emitter); // 客户端断开，移除订阅  // 发送失败说明连接断了，把它从订阅表移除
            }  // catch 结束
        }  // for 结束
    }  // 方法结束

    /**
     * 作用：绑定会话归属用户，防止他人凭 sessionId 越权
     * 参数：sessionId 会话 ID；userId 用户 ID
     * 返回值：无
     * 异常：无
     */
    public static void bindOwner(String sessionId, long userId) {  // bindOwner 签名
        OWNERS.put(key(sessionId), userId);  // OWNERS.put：写入归属映射（只在登录用户提交时调用）
    }  // 方法结束

    /** 校验 sessionId 是否属于该用户（无归属视为允许——此时无数据） */
    /** 任务提交时间（供调用链过滤子Agent残留 span）；无记录返回 0 */
    /**
     * 作用：读取会话的任务元信息
     * 参数：sessionId 会话 ID
     * 返回值：任务元信息；不存在返回 null
     * 异常：无
     */
    public static TaskMeta metaOf(String sessionId) {  // metaOf 签名
        return sessionId == null ? null : TASKS.get(sessionId);  // sessionId 为空返回 null，否则查 TASKS（不移除，只读）
    }  // 方法结束
    /**
     * 作用：读取任务开始时间戳
     * 参数：sessionId 会话 ID
     * 返回值：开始毫秒时间戳；不存在返回 0
     * 异常：无
     */

    public static long taskStartMs(String sessionId) {  // taskStartMs 签名
        TaskMeta meta = TASKS.get(sessionId);  // 查运行中任务表
        return meta == null ? 0L : meta.submitMs;  // 没有就返回 0，否则返回 submitMs（供调用链过滤残留 span）
    }  // 方法结束
    /**
     * 作用：判断用户是否有权操作该会话
     * 参数：sessionId 会话 ID；userId 用户 ID
     * 返回值：true=归属匹配
     * 异常：无
     */

    public static boolean isOwner(String sessionId, long userId) {  // isOwner 签名
        Long owner = OWNERS.get(key(sessionId));  // 取归属人（可能为 null，游客提交）
        return owner == null || owner == userId;  // 没有归属人或就是本人 → true；否则 false
    }  // 方法结束

    /**
     * 作用：缓存任务最终结果（结构化 JSON 或错误文案），供前端轮询/SSE 取走
     * 参数：sessionId 会话 ID；result 结果文本
     * 返回值：无
     * 异常：无
     */
    public static void setResult(String sessionId, String result) {  // setResult 签名
        state(sessionId).result = result;  // 覆盖 result 字段（同会话重新提交只保留最新一份）
    }  // 方法结束

    /**
     * 作用：读取任务最终结果
     * 参数：sessionId 会话 ID
     * 返回值：结果文本；尚未产出返回 null
     * 异常：无
     */
    public static String getResult(String sessionId) {  // getResult 签名
        return state(sessionId).result;  // 返回 result（state 会顺带刷新访问时间）
    }  // 方法结束

    /**
     * 作用：追加一条过程事件并实时广播给订阅者（解析/行程/路线/校验/完成等阶段）
     * 参数：sessionId 会话 ID；type 事件类型（前端卡片分组）；content 事件文案
     * 返回值：无
     * 异常：无
     */
    public static void add(String sessionId, String type, String content) {  // add 签名：这是整个编排过程最常调用的方法
        SessionState s = state(sessionId);  // 拿到会话状态（不存在就创建）
        long nowMs = System.currentTimeMillis();  // 记录当前毫秒时间
        Map<String, String> event = Map.of(  // 构造事件 Map（Map.of 不可变）
                "time", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),  // time：HH:mm:ss 格式的显示时间
                "type", type,  // type：事件类型（解析/行程/路线/校验/审查/完成…）
                "content", content == null ? "" : content,  // content：正文，null 归一成空串
                "ts", String.valueOf(nowMs) // ★2026-09-05 供终态落库 task_events（重启后历史事件流从库恢复）  // ts：毫秒时间戳，终态落库 task_events 用（重启后可从库恢复）
        );  // Map.of 结束
        s.events.add(event);  // 入队（事件队列尾部）
        while (s.events.size() > MAX_EVENTS) { // 超出上限丢弃最旧事件  // 超过 100 条
            s.events.poll();  // poll 掉最旧的一条（只丢展示用的事件，不影响结果）
        }  // while 结束
        // 同步任务监控：更新当前步骤与最后活动时间
        TaskMeta meta = TASKS.get(key(sessionId));  // 取该会话的任务元信息
        if (meta != null) {  // 有监控记录才更新
            meta.currentStep = type;  // 当前步骤 = 这次事件的类型（管理端看“卡在哪一步”）
            meta.lastActiveMs = System.currentTimeMillis();  // 刷新最后活动时间
        }  // if 结束
        notify(sessionId, event); // 实时推送给 SSE 订阅者  // notify：老订阅者立刻收到这条推送
    }  // 方法结束

    /**
     * 作用：读取会话的全部过程事件（重连时回放）
     * 参数：sessionId 会话 ID
     * 返回值：事件 Map 列表
     * 异常：无
     */
    public static List<Map<String, String>> all(String sessionId) {  // all 签名
        return state(sessionId).events.stream().collect(Collectors.toList());  // 把队列转成 List（按入队顺序）
    }  // 方法结束

    /**
     * 作用：读取会话的全部过程事件但不触发任何广播（供内部诊断/落库使用）
     * 参数：sessionId 会话 ID
     * 返回值：事件 Map 列表
     * 异常：无
     */
    public static List<Map<String, String>> allQuiet(String sessionId) {  // allQuiet 签名
        SessionState s = SESSIONS.get(key(sessionId));  // 直接查容器，不创建会话
        return s == null ? null : s.events.stream().collect(Collectors.toList());  // 不存在返回 null，否则转 List
    }  // 方法结束

    /**
     * 作用：清空会话的旧事件（重复提交时避免新旧混染）
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无
     */
    public static void clear(String sessionId) {  // clear 签名：submit 抢到槽后立刻调用
        SessionState s = state(sessionId);  // 取会话
        s.events.clear();  // 清空事件队列
        s.result = null;  // 结果置空（上一轮的行程不能残留）
        s.cancelRequested = false;  // 取消标志复位（上一轮的中断请求不能带到这一轮）
    }  // 方法结束

    /**
     * 作用：请求中断任务：置取消标志并触发已注册的中断回调
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无（回调执行失败内部捕获）
     */
    public static void requestCancel(String sessionId) {  // requestCancel 签名
        SessionState s = state(sessionId);  // 取会话
        s.cancelRequested = true;  // 置取消标志（编排器 checkCancel/await 轮询会读它）
        Runnable cb = s.interruptCallback;  // 取出当前中断回调
        if (cb != null) {  // 有回调才执行
            try { cb.run(); } catch (Exception ignored) {}  // 执行回调（A2A 期间是 agent::interrupt），异常忽略
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：注册中断回调（A2A 调用期间用它打断远程 Agent）
     * 参数：sessionId 会话 ID；callback 中断动作
     * 返回值：无
     * 异常：无
     */
    public static void setInterruptCallback(String sessionId, Runnable callback) {  // setInterruptCallback 签名
        state(sessionId).interruptCallback = callback;  // 把回调存进会话（A2A 调用前注册）
    }  // 方法结束

    /**
     * 作用：清除中断回调（远程调用结束后必须清，避免误打断）
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无
     */
    public static void clearInterruptCallback(String sessionId) {  // clearInterruptCallback 签名
        state(sessionId).interruptCallback = null;  // 置空（在 RemoteAgentTool 的 finally 里调用）
    }  // 方法结束

    /**
     * 作用：查询会话是否已被请求中断
     * 参数：sessionId 会话 ID
     * 返回值：true=已请求中断
     * 异常：无
     */
    public static boolean isCancelRequested(String sessionId) {  // isCancelRequested 签名
        return state(sessionId).cancelRequested;  // 返回取消标志（A2A 异常分支用它区分“用户中断”和“真失败”）
    }  // 方法结束

    /**
     * 作用：列出当前活跃会话的概要（管理端任务监控用）
     * 参数：无
     * 返回值：会话概要 Map 列表
     * 异常：无
     */
    public static java.util.List<java.util.Map<String, Object>> listSessions() {  // listSessions 签名
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();  // 准备结果列表
        for (java.util.Map.Entry<String, SessionState> e : SESSIONS.entrySet()) {  // 遍历所有会话
            SessionState s = e.getValue();  // 取会话状态
            java.util.Map<String, Object> m = new java.util.HashMap<>();  // 准备一行概要
            m.put("sessionId", e.getKey());  // sessionId
            m.put("eventCount", s.events.size());  // 事件条数
            m.put("hasResult", s.result != null);  // 是否已出结果
            m.put("cancelled", s.cancelRequested);  // 是否被请求取消
            m.put("ownerId", OWNERS.get(e.getKey()));  // 归属用户 ID
            m.put("lastAccessMs", s.lastAccessMs);  // 最后访问时间
            m.put("running", s.result == null && !s.events.isEmpty()); // 有事件但无结果 = 进行中  // running：有事件但还没结果 → 正在进行中
            TaskMeta meta = TASKS.get(e.getKey()); // 任务监控：附带当前步骤与提交时间  // 附带任务监控信息
            if (meta != null) {  // 有监控记录时
                m.put("currentStep", meta.currentStep);  // 当前步骤
                m.put("submitMs", meta.submitMs);  // 提交时间
            }  // if 结束
            out.add(m);  // 加入结果列表
        }  // for 结束
        return out;  // 返回全部概要
    }  // 方法结束
}  // 类结束

