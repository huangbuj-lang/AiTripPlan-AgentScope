package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.TripEditService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 行程二次编辑（阶段四 P2）：读取/保存结构化 TripPlanData，支持手动微调 + 增量重排某一天。 */
@RestController  // @RestController
@RequestMapping("/api/trips")  // 类级映射 /api/trips
public class TripEditController {  // 类声明：TripEditController

    @Resource  // @Resource：注入下面的 service
    private TripEditService tripEditService;  // 字段：TripEditService

    private long uid(HttpSession session) {  // 私有工具方法 uid：从会话里取登录用户 ID
        return ((Number) session.getAttribute("userId")).longValue();  // 把 session 里的 userId 强转 Number 再 longValue——已登录用户一定有值；未登录会在 WebConfig 拦截层被挡掉
    }  // 方法结束

    /** 读取某条行程的结构化 JSON（仅本人），供编辑页加载 */
    @GetMapping("/{id}/edit")  // @GetMapping("/{id}/edit")：GET /api/trips/{id}/edit
    public Map<String, Object> getForEdit(@PathVariable Long id, HttpSession session) {  // 签名：路径变量 id + HttpSession
        return tripEditService.getForEdit(id, uid(session));  // 转交 tripEditService.getForEdit(id, uid)：归属校验在 service 里做，只返回本人的行程
    }  // 方法结束

    /** 保存编辑后的结构化行程（覆盖 plan_json + 重渲染 markdown，仅本人） */
    @PutMapping("/{id}")  // @PutMapping("/{id}")：PUT /api/trips/{id}
    public Map<String, Object> updateTrip(@PathVariable Long id,  // 签名：路径 id + 请求体 + session
                                          @RequestBody String planJson,  // @RequestBody：前端提交的整份 plan JSON 字符串（原样接收，不在这里做结构校验）
                                          HttpSession session) {  // HttpSession 参数
        return tripEditService.updateTrip(id, uid(session), planJson);  // 转交 service.updateTrip：落库 + 重渲染 markdown
    }  // 方法结束

    /** 增量重排某一天：用 LLM 重新生成该天 timeline 区块（可带调整要求），仅本人 */
    @PostMapping("/{id}/regenerate-day")  // @PostMapping("/{id}/regenerate-day")
    public Map<String, Object> regenerateDay(@PathVariable Long id,  // 签名：路径 id + 天序号 + 可选调整说明 + session
                                             @RequestParam int dayIndex,  // dayIndex：要重排第几天（0 基还是 1 基由 service 约定）
                                             @RequestParam(required = false) String instruction,  // instruction：用户给的调整要求，可为空（空则按原样重排）
                                             HttpSession session) {  // HttpSession 参数
        return tripEditService.regenerateDay(id, uid(session), dayIndex, instruction);  // 转交 service.regenerateDay：内部走 PlanReviser/LLM，只改这一天的区块
    }  // 方法结束
}  // 类结束

