package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程保存接口（规划结果存入MySQL）
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 保存行程到数据库
     */
    @PostMapping("/save-plan")
    public Map<String, Object> savePlan(@RequestParam String city,
                                        @RequestParam int days,
                                        @RequestParam String planContent,
                                        HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = (Long) userIdObj;
        jdbcTemplate.update( // 插入行程记录（关联当前用户）
                "INSERT INTO trip_plans(user_id, city, days, plan_content) VALUES (?, ?, ?, ?)",
                userId, city, days, planContent);
        result.put("success", true);
        result.put("message", "保存成功！可在「我的行程」中查看");
        return result;
    }

    /**
     * 查询当前用户保存的所有行程（按时间倒序）
     */
    @GetMapping("/my-plans")
    public Map<String, Object> myPlans(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = (Long) userIdObj;
        List<Map<String, Object>> plans = jdbcTemplate.queryForList(
                "SELECT * FROM trip_plans WHERE user_id = ? ORDER BY id DESC", userId);
        result.put("success", true);
        result.put("plans", plans);
        return result;
    }

    /**
     * 删除自己的行程（id + user_id 双重条件）
     */
    @PostMapping("/delete-plan")
    public Map<String, Object> deletePlan(@RequestParam Long id, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = (Long) userIdObj;
        int rows = jdbcTemplate.update(
                "DELETE FROM trip_plans WHERE id = ? AND user_id = ?", id, userId);
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已删除" : "删除失败（行程不存在或无权删除）");
        return result;
    }
}
