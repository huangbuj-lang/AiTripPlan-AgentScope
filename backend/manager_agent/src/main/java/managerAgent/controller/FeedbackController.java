package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * ④ 方案反馈闭环：记录用户对生成行程的 采纳(adopt)/收藏(like)，供离线画像/模型迭代使用。
 * 独立接口，不改动核心链路；未登录时 user_id=0（匿名反馈）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 记录一次反馈（action: adopt 采纳 / like 收藏；planId 可选） */
    @PostMapping
    public Map<String, Object> feedback(@RequestParam String action,
                                        @RequestParam(required = false) Long planId,
                                        HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        String a = action == null ? "" : action.trim();
        if (!"adopt".equals(a) && !"like".equals(a)) {
            result.put("success", false);
            result.put("message", "action 仅支持 adopt/like");
            return result;
        }
        Object userIdObj = session.getAttribute("userId");
        long userId = userIdObj == null ? 0L : ((Number) userIdObj).longValue();
        jdbcTemplate.update(
                "INSERT INTO plan_feedback(user_id, plan_id, action) VALUES (?, ?, ?)",
                userId, planId, a);
        result.put("success", true);
        result.put("message", "反馈已记录");
        return result;
    }
}
