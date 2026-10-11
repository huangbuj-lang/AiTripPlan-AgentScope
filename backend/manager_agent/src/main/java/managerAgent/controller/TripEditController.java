package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.TripEditService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 行程二次编辑（阶段四 P2）：读取/保存结构化 TripPlanData，支持手动微调 + 增量重排某一天。 */
@RestController
@RequestMapping("/api/trips")
public class TripEditController {

    @Resource
    private TripEditService tripEditService;

    private long uid(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 读取某条行程的结构化 JSON（仅本人），供编辑页加载 */
    @GetMapping("/{id}/edit")
    public Map<String, Object> getForEdit(@PathVariable Long id, HttpSession session) {
        return tripEditService.getForEdit(id, uid(session));
    }

    /** 保存编辑后的结构化行程（覆盖 plan_json + 重渲染 markdown，仅本人） */
    @PutMapping("/{id}")
    public Map<String, Object> updateTrip(@PathVariable Long id,
                                          @RequestBody String planJson,
                                          HttpSession session) {
        return tripEditService.updateTrip(id, uid(session), planJson);
    }

    /** 增量重排某一天：用 LLM 重新生成该天 timeline 区块（可带调整要求），仅本人 */
    @PostMapping("/{id}/regenerate-day")
    public Map<String, Object> regenerateDay(@PathVariable Long id,
                                             @RequestParam int dayIndex,
                                             @RequestParam(required = false) String instruction,
                                             HttpSession session) {
        return tripEditService.regenerateDay(id, uid(session), dayIndex, instruction);
    }
}
