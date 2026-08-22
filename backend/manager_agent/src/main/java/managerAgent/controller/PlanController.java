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
        jdbcTemplate.update( // 插入行程记录（关联当前用户 + 结构化 JSON）
                "INSERT INTO trip_plans(user_id, city, days, plan_content, plan_json) VALUES (?, ?, ?, ?, ?)",
                userId, city, days, planContent, json);
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
        if (rows > 0) {
            triggerProfileRebuild(userId); // ★删除后重算该用户画像（剩余数据全量聚合，共同偏好不误删）
        }
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已删除" : "删除失败（行程不存在或无权删除）");
        return result;
    }

    /**
     * ★画像增量维护：异步调用 data-pipeline/profile.py --user <id> 单用户全量重算。
     * 失败静默（不影响主流程）。
     */
    private void triggerProfileRebuild(long userId) {
        try {
            java.io.File script = new java.io.File(System.getProperty("user.dir"),
                    "../../data-pipeline/profile.py");
            if (!script.exists()) {
                return;
            }
            final ProcessBuilder pb = new ProcessBuilder("python", script.getCanonicalPath(),
                    "--user", String.valueOf(userId));
            pb.redirectErrorStream(true);
            pb.redirectOutput(java.io.File.createTempFile("atp_profile_", ".log"));
            new Thread(() -> {
                try {
                    Process pr = pb.start();
                    pr.waitFor();
                } catch (Exception ignored) { }
            }, "profile-rebuild-" + userId).start();
        } catch (Exception ignored) { }
    }
}
