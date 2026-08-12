package managerAgent.controller;

import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 事件查询接口：GET /events?sessionId=xxx → 该会话主管Agent最近运行的事件列表（前端轮询实时显示进度）
 * ★会话隔离：按 sessionId 查询各自会话的事件，多窗口互不污染；不传回退默认会话
 */
@RestController
@RequestMapping("/events")
public class EventController {

    /**
     * 查询事件列表（该会话主管Agent的运行进度）
     */
    @GetMapping
    public List<Map<String, String>> events(
            @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {
        return EventStore.all(sessionId);
    }
}
