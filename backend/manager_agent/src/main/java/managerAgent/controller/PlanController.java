package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import utils.JsonUtils;

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
                                        @RequestParam(required = false) String planJson,
                                        HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = (Long) userIdObj;
        // 空串/非法 JSON 存 NULL（JSON 列不接受空串，非法 JSON 会触发 MySQL 500）
        String json = (planJson == null || planJson.isBlank()) ? null : planJson;
        if (json != null && JsonUtils.parse(json, Map.class) == null) {
            json = null; // 非法 JSON：放弃结构化字段，仍保存 markdown
        }
        final String jsonFinal = json;
        // 插入行程记录（关联当前用户 + 结构化 JSON），取回自增 id 供反馈闭环
        org.springframework.jdbc.support.KeyHolder kh =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO trip_plans(user_id, city, days, plan_content, plan_json) VALUES (?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, userId); ps.setObject(2, city); ps.setObject(3, days);
            ps.setObject(4, planContent); ps.setObject(5, jsonFinal);
            return ps;
        }, kh);
        long newPlanId = kh.getKey() != null ? kh.getKey().longValue() : -1L;

        result.put("success", true);
        result.put("message", "保存成功！可在「我的行程」中查看");
        result.put("planId", newPlanId);
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
