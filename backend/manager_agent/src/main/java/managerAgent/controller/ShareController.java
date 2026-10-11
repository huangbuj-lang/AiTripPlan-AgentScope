package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.ShareService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 行程分享接口（P0）：生成/撤销分享链接 + 免登录只读页。 */
@RestController
@RequestMapping("/api")
public class ShareController {

    @Resource
    private ShareService shareService;

    private long userId(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 创建分享（幂等：同一行程重复分享则刷新 code） */
    @PostMapping("/trips/{id}/share")
    public Map<String, Object> createShare(@PathVariable Long id,
                                           @RequestParam(required = false) String password,
                                           @RequestParam(required = false) Integer validDays,
                                           HttpSession session) {
        return shareService.createShare(userId(session), id, password, validDays);
    }

    /** 撤销分享 */
    @DeleteMapping("/trips/{id}/share")
    public Map<String, Object> revokeShare(@PathVariable Long id, HttpSession session) {
        return shareService.revokeShare(userId(session), id);
    }

    /** 免登录只读页：按 code 取行程内容（结构化 JSON 优先，markdown 兜底）。 */
    @GetMapping("/share/{code}")
    public Map<String, Object> getSharedTrip(@PathVariable String code,
                                             @RequestParam(required = false) String password) {
        return shareService.getSharedTrip(code, password);
    }

    /** 分享页按天生成路线图（免登录） */
    @GetMapping("/share/{code}/day-map")
    public Map<String, Object> dayMap(@PathVariable String code, @RequestParam int day) {
        return shareService.dayMap(code, day);
    }

    /** 查询某行程的分享状态（前端"我的行程"展示分享链接） */
    @GetMapping("/trips/{id}/share")
    public Map<String, Object> getShareStatus(@PathVariable Long id, HttpSession session) {
        return shareService.getShareStatus(userId(session), id);
    }
}
