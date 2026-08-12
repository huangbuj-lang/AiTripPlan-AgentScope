package managerAgent.controller;

import managerAgent.agents.ManagerAgent;
import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户和Agent互动的API接口
 * POST /app?prompt=任务  → 提交任务（异步，立即返回）
 * GET /result           → 获取最终结果
 */
@RestController
public class AppController {

    // ★全局并发闸门：同时执行的任务数上限（默认1=保持原有"一次一个任务"行为；云端多线程可调大，如2-3）
    //  ⚠️ 调大前必须确认 DeepSeek/DashScope 账号并发配额（SafeModel 限速器会兜底请求频率，但并发任务数仍受配额约束）
    private static final int MAX_CONCURRENT_TASKS = Integer.parseInt(
            System.getenv().getOrDefault("MAX_CONCURRENT_TASKS", "1"));

    // 异步线程池：固定大小=并发上限（不是单线程了；防并发混流由 MAX_CONCURRENT_TASKS 控制）
    private static final java.util.concurrent.ExecutorService TASK_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(MAX_CONCURRENT_TASKS);

    // 并发信号量：实时计数"正在执行的任务"，达到上限拒绝新任务（防止线程池排队堆积）
    private static final java.util.concurrent.Semaphore TASK_SLOTS =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_TASKS);

    /**
     * HTTP入口：提交任务给主管Agent处理（异步，立即返回）
     * @param sessionId 会话ID（前端localStorage生成，多窗口隔离；不传回退默认会话）
     */
    @RequestMapping(value = "/app", method = RequestMethod.POST)
    public String app(@RequestParam(name = "prompt", required = false, defaultValue = "帮我制定2026年元旦,深圳到惠州3日游自驾游计划，请包含吃住行，天气，酒店，餐饮美食。") String prompt,
                      @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {

        // ★并发闸门：信号量获取（达到并发上限则拒绝，不排队——保持原有"拒绝"语义，不损坏现有行为）
        if (!TASK_SLOTS.tryAcquire()) {
            return "⚠️ 有任务正在执行中（最多同时 " + MAX_CONCURRENT_TASKS + " 个），请等当前任务完成后再试！";
        }

        EventStore.clear(sessionId); // 新任务开始：清空该会话的旧事件（/events只显示本次任务）

        TASK_POOL.submit(() -> { // 异步执行：立即返回，任务在后台线程池运行
            try {
                ManagerAgent manager = new ManagerAgent(sessionId); // 每次请求新实例，避免状态串扰（按会话隔离）
                String result = manager.runAndReturn(prompt); // Agent处理Prompt，返回最终结果
                EventStore.setResult(sessionId, result); // 缓存结果（前端通过 /result 获取）
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("任务已被用户中断")) {
                    EventStore.setResult(sessionId, "⏹ 任务已由用户中断。"); // 用户主动取消：友好提示
                } else {
                    EventStore.setResult(sessionId, "❌ 任务执行出错：" + e.getMessage()); // 出错也缓存错误信息
                }
                e.printStackTrace();
            } finally {
                TASK_SLOTS.release(); // 释放并发槽位（任务结束，允许新任务）
            }
        });

        return "✅ 任务已接收，正在后台执行中！请查看思考过程，完成后结果自动显示。";
    }

    /**
     * 获取任务最终结果（未完成时返回提示）（按会话隔离）
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    public String result(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {
        String r = EventStore.getResult(sessionId);
        return r == null ? "⏳ 任务还在执行中..." : r;
    }

    /**
     * 中断当前任务（设置取消标志，planHook 在下一个事件点抛异常终止 ReAct 循环）
     */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public String cancel(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {
        EventStore.requestCancel(sessionId);
        return "✅ 已发送中断请求，任务将在下一步停止。";
    }
}
