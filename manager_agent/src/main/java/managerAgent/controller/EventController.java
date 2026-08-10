package managerAgent.controller;

import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 事件查询接口：GET /events → 主管Agent最近运行的事件列表（前端轮询实时显示进度）
 */
@RestController
@RequestMapping("/events")
public class EventController {

    /**
     * 查询事件列表（主管Agent的运行进度）
     */
    @GetMapping
    public List<Map<String, String>> events() {
        return EventStore.all();
    }
}
