package managerAgent.controller;

import managerAgent.service.TripTaskService;
import managerAgent.tool.HttpProbe;
import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import managerAgent.service.AdminTaskService;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/api/admin")
public class AdminTaskController {

    @Resource
    private AdminTaskService adminTaskService;

    /** 并发槽位占用 + 全部会话概览 */
    @GetMapping("/tasks")
    public Map<String, Object> tasks() {
        return adminTaskService.tasks();
    }

    /** 强制中断指定会话 */
    @PostMapping("/tasks/cancel")
    public Map<String, Object> cancelTask(@RequestParam String sessionId) {
        return adminTaskService.cancelTask(sessionId);
    }

    /** 单个任务的调用链明细：主管(LLM/A2A) + 子Agent进程(TOOL/MCP，经 /trace 合并)，按时间排序 */
    @GetMapping("/tasks/trace")
    public Map<String, Object> taskTrace(@RequestParam("sessionId") String sessionId) {
        return adminTaskService.taskTrace(sessionId);
    }
}
