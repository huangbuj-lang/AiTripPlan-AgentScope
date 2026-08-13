package managerAgent.controller;

import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
/**
 * 事件查询接口：按会话查询Agent运行事件（供前端轮询）
 */
@RestController
@RequestMapping("/events")
public class EventController {

    /**
    /**
     * 查询该会话的事件列表
     */
    @GetMapping
    public List<Map<String, String>> events(
            @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId) {
        return EventStore.all(sessionId);
    }
}
