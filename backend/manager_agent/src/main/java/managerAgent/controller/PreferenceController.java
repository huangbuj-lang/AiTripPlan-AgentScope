package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好接口：保存/读取「预检卡片」勾选的偏好（步行忍耐度/作息/博物馆占比）
 * 供前端预检卡片回填历史默认值，实现"隐形学习"——用户下次不用重新勾选。
 */
@RestController
@RequestMapping("/api")
public class PreferenceController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 保存偏好（UPSERT：user_id 唯一，有则更新） */
    @PostMapping("/save-preference")
    public Map<String, Object> savePreference(@RequestParam(required = false, defaultValue = "") String walkTolerance,
                                              @RequestParam(required = false, defaultValue = "") String chronotype,
                                              @RequestParam(required = false, defaultValue = "") String museumRatio,
                                              HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = ((Number) userIdObj).longValue();
        jdbcTemplate.update(
                "INSERT INTO user_preferences(user_id, walk_tolerance, chronotype, museum_ratio) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE walk_tolerance=VALUES(walk_tolerance), "
                        + "chronotype=VALUES(chronotype), museum_ratio=VALUES(museum_ratio)",
                userId, walkTolerance, chronotype, museumRatio);
        result.put("success", true);
        result.put("message", "偏好已保存");
        return result;
    }

    /** 读取偏好（无记录时返回空字段，前端回填为空=不限） */
    @GetMapping("/get-preference")
    public Map<String, Object> getPreference(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = ((Number) userIdObj).longValue();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT walk_tolerance, chronotype, museum_ratio FROM user_preferences WHERE user_id = ?", userId);
        result.put("success", true);
        if (rows.isEmpty()) {
            result.put("walkTolerance", "");
            result.put("chronotype", "");
            result.put("museumRatio", "");
        } else {
            Map<String, Object> row = rows.get(0);
            result.put("walkTolerance", row.get("walk_tolerance"));
            result.put("chronotype", row.get("chronotype"));
            result.put("museumRatio", row.get("museum_ratio"));
        }
        return result;
    }
}
