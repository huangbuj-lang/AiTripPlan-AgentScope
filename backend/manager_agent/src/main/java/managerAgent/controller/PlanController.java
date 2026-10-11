package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import utils.JsonUtils;

/** 行程保存接口（规划结果存入MySQL） */
@RestController
@RequestMapping("/api")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 保存行程到数据库 */
    @PostMapping("/save-plan")
    public Map<String, Object> savePlan(@RequestParam String city,
                                        @RequestParam int days,
                                        @RequestParam String planContent,
                                        @RequestParam(required = false) String planJson,
                                        HttpSession session,
                                        HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Object userIdObj = session.getAttribute("userId");
        if (userIdObj == null) {
            result.put("success", false);
            result.put("message", "请先登录");
            return result;
        }
        long userId = (Long) userIdObj;
        //空串/非法 JSON 存 NULL（JSON 列不接受空串，非法 JSON 会触发 MySQL 500）
        String json = (planJson == null || planJson.isBlank()) ? null : planJson;
        if (json != null && JsonUtils.parse(json, Map.class) == null) {
            json = null; // 非法 JSON：放弃结构化字段，仍保存 markdown
        }
        //插入行程记录，取回自增 id 供画像提取/反馈使用
        final String jsonF = json;
        org.springframework.jdbc.support.KeyHolder kh =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO trip_plans(user_id, city, days, plan_content, plan_json) VALUES (?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, userId); ps.setObject(2, city); ps.setObject(3, days);
            ps.setObject(4, planContent); ps.setObject(5, jsonF);
            return ps;
        }, kh);
        long newPlanId = kh.getKey() != null ? kh.getKey().longValue() : -1L;
        //★用户口径：保存接口加"来源留痕" —— 以后再出现重复行程，一看日志就知道是【浏览器点击】还是【脚本调用】
        try {
            String md5 = planContent == null ? ""
                    : DigestUtils.md5DigestAsHex(planContent.getBytes(StandardCharsets.UTF_8));
            String ua = request == null ? "" : String.valueOf(request.getHeader("User-Agent"));
            String ref = request == null ? "" : String.valueOf(request.getHeader("Referer"));
            String ip = request == null ? "" : String.valueOf(request.getRemoteAddr());
            //同一用户已存在完全相同内容 → 明确打上 DUP 标记，便于事后归因（只读，不改任何数据）
            int dup = 0;
            try {
                Integer c = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM trip_plans WHERE user_id = ? AND MD5(plan_content) = ?",
                        Integer.class, userId, md5);
                dup = c == null ? 0 : c;
            } catch (Exception ignore) {
            }
            log.info("[SAVE-PLAN]{} user={} planId={} city={} days={} bytes={} md5={} ip={} referer={} ua={}",
                    dup > 0 ? "[DUP" + dup + "]" : "", userId, newPlanId, city, days,
                    planContent == null ? 0 : planContent.length(), md5, ip, ref, ua);
        } catch (Exception ignore) {
        }
        result.put("success", true);
        result.put("message", "保存成功！可在「我的行程」中查看");
        result.put("planId", newPlanId);
        //异步用 LLM 从行程内容提取偏好标签，追加到该用户画像
        triggerPreferenceExtract(userId, newPlanId, planContent);
        //保存行程 -> 重算"常去-城市"标签
        managerAgent.tool.ProfileHook.trigger(userId);
        return result;
    }

    /** 查询当前用户保存的所有行程（按时间倒序） */
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

    /** 删除自己的行程（id + user_id 双重条件） */
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
            managerAgent.tool.ProfileHook.trigger(userId); // 删除后重算画像
        }
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已删除" : "删除失败（行程不存在或无权删除）");
        return result;
    }

    /** 异步调用 data-pipeline/extract_pref.py， 用 LLM 从保存的行程内容提取偏好标签并写入该用户画像（用户无感，失败静默）。 */
    private void triggerPreferenceExtract(long userId, long planId, String planContent) {
        try {
            if (planContent == null || planContent.isBlank()) return;
            java.io.File script = new java.io.File(System.getProperty("user.dir"),
                    "../../data-pipeline/extract_pref.py");
            if (!script.exists()) return;
            String contentArg = planContent.length() > 3000 ? planContent.substring(0, 3000) : planContent;
            final ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath(),
                    "--user", String.valueOf(userId), "--content", contentArg);
            pb.redirectErrorStream(true);
            pb.redirectOutput(java.io.File.createTempFile("atp_extract_", ".log"));
            new Thread(() -> {
                try {
                    Process pr = pb.start();
                    pr.waitFor();
                } catch (Exception ignored) { }
            }, "pref-extract-" + planId).start();
        } catch (Exception ignored) { }
    }
}
