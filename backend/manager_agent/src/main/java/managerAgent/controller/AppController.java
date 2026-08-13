package managerAgent.controller;

import managerAgent.agents.ManagerAgent;
import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
/**
 * 用户与Agent互动的HTTP接口
 */
@RestController
public class AppController {

    // 全局并发任务数上限（环境变量配置，默认1）
    private static final int MAX_CONCURRENT_TASKS = Integer.parseInt(
            System.getenv().getOrDefault("MAX_CONCURRENT_TASKS", "1"));

    // 异步任务线程池（大小=并发上限）
    private static final java.util.concurrent.ExecutorService TASK_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(MAX_CONCURRENT_TASKS);

    // 并发信号量：达到上限时拒绝新任务
    private static final java.util.concurrent.Semaphore TASK_SLOTS =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_TASKS);

    /**
    /**
     * HTTP入口：提交任务给主管Agent异步处理
     */
    @RequestMapping(value = "/app", method = RequestMethod.POST)
    public String app(@RequestParam(name = "prompt", required = false, defaultValue = "帮我制定2026年元旦,深圳到惠州3日游自驾游计划，请包含吃住行，天气，酒店，餐饮美食。") String prompt,
                      @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {

        // 获取并发槽位，失败则拒绝
        if (!TASK_SLOTS.tryAcquire()) {
            return "⚠️ 有任务正在执行中（最多同时 " + MAX_CONCURRENT_TASKS + " 个），请等当前任务完成后再试！";
        }

        EventStore.clear(sessionId); // 清空该会话的旧事件

        TASK_POOL.submit(() -> { // 异步执行任务
            try {
                ManagerAgent manager = new ManagerAgent(sessionId); // 每次请求创建新实例（会话隔离）
                String result = manager.runAndReturn(prompt); // Agent处理Prompt，返回最终结果
                EventStore.setResult(sessionId, result); // 缓存结果供/result查询
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("任务已被用户中断")) {
                    EventStore.setResult(sessionId, "⏹ 任务已由用户中断。"); // 用户取消的友好提示
                } else {
                    EventStore.setResult(sessionId, "❌ 任务执行出错：" + e.getMessage()); // 缓存错误信息
                }
                e.printStackTrace();
            } finally {
                TASK_SLOTS.release(); // 释放并发槽位
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
    /**
     * 中断当前任务
     */
    @RequestMapping(value = "/cancel", method = RequestMethod.POST)
    public String cancel(@RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {
        EventStore.requestCancel(sessionId);
        return "✅ 已发送中断请求，任务将在下一步停止。";
    }
}
