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

    // 异步线程池：单线程=同时只处理一个任务，防并发混流
    private static final java.util.concurrent.ExecutorService TASK_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static volatile boolean RUNNING = false; // 并发防护标志：有任务在跑时拒绝新请求

    /**
     * HTTP入口：提交任务给主管Agent处理（异步，立即返回）
     */
    @RequestMapping(value = "/app", method = RequestMethod.POST)
    public String app(@RequestParam(name = "prompt", required = false, defaultValue = "帮我制定2026年元旦,深圳到惠州3日游自驾游计划，请包含吃住行，天气，酒店，餐饮美食。") String prompt) {

        if (RUNNING) { // 并发防护：有任务在跑时拒绝
            return "⚠️ 有任务正在执行中，请等当前任务完成后再试！";
        }
        RUNNING = true;

        EventStore.clear(); // 新任务开始：清空旧事件（/events只显示本次任务）

        TASK_POOL.submit(() -> { // 异步执行：立即返回，任务在后台线程池运行
            try {
                ManagerAgent manager = new ManagerAgent(); // 每次请求新实例，避免状态串扰
                String result = manager.runAndReturn(prompt); // Agent处理Prompt，返回最终结果
                EventStore.setResult(result); // 缓存结果（前端通过 /result 获取）
            } catch (Exception e) {
                EventStore.setResult("❌ 任务执行出错：" + e.getMessage()); // 出错也缓存错误信息
                e.printStackTrace();
            } finally {
                RUNNING = false;
            }
        });

        return "✅ 任务已接收，正在后台执行中！请查看思考过程，完成后结果自动显示。";
    }

    /**
     * 获取任务最终结果（未完成时返回提示）
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    public String result() {
        String r = EventStore.getResult();
        return r == null ? "⏳ 任务还在执行中..." : r;
    }
}
