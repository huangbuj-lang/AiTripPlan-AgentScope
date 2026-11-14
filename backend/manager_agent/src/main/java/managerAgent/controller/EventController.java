package managerAgent.controller;

import jakarta.servlet.http.HttpSession;
import managerAgent.hook.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 事件查询接口：按会话查询Agent运行事件（供前端轮询） */
@RestController  // @RestController：返回 JSON / SSE 流
@RequestMapping("/events")  // 类级映射 /events——本类所有路径都以此开头
public class EventController {  // 类声明：EventController

    /** 查询该会话的事件列表 */
    @GetMapping  // @GetMapping：不带子路径，所以完整路径就是 GET /events
    public List<Map<String, String>> events(  // 签名开始，返回 List<Map<String,String>>（每条事件是一个 Map：time/type/content/ts）
            @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,  // sessionId 参数，默认 default
            HttpSession session) {  // HttpSession 参数
        Object userIdObj = session.getAttribute("userId");  // 从会话里取 userId（可能是 null，游客没登录）
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {  // 归属校验：没登录，或者不是这个会话的归属人 → 进入 if
            return List.of(); // 非本人会话：返回空，不泄露他人事件  // 返回空列表：不返回 403，是为了不泄露“这个会话存在且产出过事件”
        }  // if 结束
        return EventStore.all(sessionId);  // 是本人：返回该会话全部事件（EventStore.all，重连/刷新时前端靠它回放）
    }  // 方法结束

    /** SSE 实时事件流（替代前端轮询，阶段四 F-3） */
    @GetMapping("/stream")  // @GetMapping("/stream")：完整路径 GET /events/stream
    public SseEmitter stream(  // 签名开始，返回 SseEmitter
            @RequestParam(name = "sessionId", required = false, defaultValue = "default") String sessionId,  // sessionId 参数，默认 default
            HttpSession session) {  // HttpSession 参数
        Object userIdObj = session.getAttribute("userId");  // 取 userId
        if (userIdObj == null || !EventStore.isOwner(sessionId, ((Number) userIdObj).longValue())) {  // 同样的归属校验
            SseEmitter denied = new SseEmitter(0L);  // 无权限时新建一个 SseEmitter
            denied.complete(); // 无权限：立即结束  // 立刻 complete()（结束）：不给内容，前端会认为流已关闭
            return denied;  // 返回这个空流
        }  // if 结束
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 分钟超时  // 有权限：新建 SSE 连接，超时 30 分钟（30*60*1000 毫秒）——超时后容器会断开，前端自动重连

        //先快照历史事件，再订阅，避免漏推/重复推
        List<Map<String, String>> history = EventStore.all(sessionId);  // 快照：先取当前全部历史事件
        EventStore.subscribe(sessionId, emitter);  // 订阅：把这连接登记到该会话的订阅者列表，之后 add() 会实时推过来
        for (Map<String, String> ev : history) {  // 遍历历史事件逐条补发
            try {  // try：发送可能因为客户端断开而失败
                emitter.send(SseEmitter.event().name("event").data(ev));  // 发一条 SSE：事件名 event，数据是这条事件的 Map
            } catch (Exception e) {  // 捕获异常
                break; // 客户端已断开  // 一旦发送失败说明客户端已断，break 停止补发
            }  // try 结束
        }  // 遍历结束
        emitter.onCompletion(() -> EventStore.unsubscribe(sessionId, emitter));  // 注册完成回调：连接正常结束时，从订阅者列表移除，防止内存泄漏
        emitter.onTimeout(() -> EventStore.unsubscribe(sessionId, emitter));  // 注册超时回调：超时同样移除订阅
        return emitter;  // 返回 emitter，Spring 会把这个连接保持住并持续推送
    }  // 方法结束
}  // 类结束

