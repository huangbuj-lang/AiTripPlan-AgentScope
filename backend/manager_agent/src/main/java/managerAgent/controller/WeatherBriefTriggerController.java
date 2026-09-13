package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import managerAgent.job.WeatherBriefJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 行中提醒（天气早报）：手动触发 + 触发时间热更（仅管理员）。
 * 说明：本次改动只新增接口；每天的调度逻辑在 WeatherBriefJob.tick()，读 app_settings 表，改完即生效、无需重启。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class WeatherBriefTriggerController {

    private static final Pattern HM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final String KEY_TIME = "weather.brief.time";
    private static final String KEY_ENABLED = "weather.brief.enabled";

    @Resource
    private WeatherBriefJob weatherBriefJob;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 是否管理员（与 AdminInterceptor 同口径：session.role == admin） */
    private boolean isAdmin(HttpSession session) {
        return session != null && "admin".equals(session.getAttribute("role"));
    }

    private boolean loggedIn(HttpSession session) {
        return session != null && session.getAttribute("userId") != null;
    }

    /** 手动触发一次天气早报（force=true 跳过"天气没变化就不发"）。 */
    @PostMapping("/weather-brief/trigger")
    public Map<String, Object> trigger(
            @RequestParam(value = "force", required = false, defaultValue = "true") boolean force,
            HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        if (!loggedIn(session)) {
            result.put("message", "请先登录");
            return result;
        }
        try {
            int sent = weatherBriefJob.runManually(force);
            result.put("success", true);
            result.put("sent", sent);
            result.put("force", force);
            result.put("message", "已触发一次行中提醒：覆盖 " + sent + " 条行程（早报只在管理员弹窗里呈现，不发给普通用户）");
        } catch (Throwable t) {
            log.warn("手动触发天气早报失败: {}", t.toString());
            result.put("message", "触发失败：" + t.toString());
        }
        return result;
    }

    /**
     * 最近一次执行记录（★只给管理员，普通用户拿不到 → 前台不会弹窗）。
     * 数据来自 app_settings.weather.brief.last_run，不进通知模块。
     */
    @GetMapping("/admin/weather-brief/last-run")
    public Map<String, Object> lastRun(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        if (!isAdmin(session)) {
            result.put("message", "无管理员权限");
            return result;
        }
        String v = setting("weather.brief.last_run", "");
        if (v == null || v.isBlank()) {
            result.put("success", true);
            result.put("atMillis", 0L);
            return result;
        }
        String[] p = v.split("\\|", -1);
        long at = 0L;
        try { at = Long.parseLong(p[0].trim()); } catch (Exception ignore) { }
        result.put("success", true);
        result.put("atMillis", at);
        result.put("atText", p.length > 1 ? p[1] : "");
        result.put("sent", p.length > 2 ? p[2] : "0");
        result.put("by", p.length > 3 ? p[3] : "auto");
        result.put("brief", p.length > 4 ? p[4] : "");
        result.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(at > 0 ? at : System.currentTimeMillis())));
        return result;
    }

    /** 读取当前触发时间（管理端回显用） */
    @GetMapping("/admin/weather-brief/schedule")
    public Map<String, Object> getSchedule(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        if (!isAdmin(session)) {
            result.put("message", "无权限（仅管理员）");
            return result;
        }
        result.put("success", true);
        result.put("time", setting(KEY_TIME, "07:00"));
        result.put("enabled", "1".equals(setting(KEY_ENABLED, "1")));
        result.put("defaultTime", "07:00");
        return result;
    }

    /** 保存触发时间（热更：写入 app_settings，下一轮判断即生效，无需重启） */
    @PutMapping("/admin/weather-brief/schedule")
    public Map<String, Object> saveSchedule(
            @RequestParam(value = "time", required = false) String time,
            @RequestParam(value = "enabled", required = false, defaultValue = "1") String enabled,
            HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        if (!isAdmin(session)) {
            result.put("message", "无权限（仅管理员）");
            return result;
        }
        String t = time == null ? "" : time.trim();
        if (!HM.matcher(t).matches()) {
            result.put("message", "时间格式应为 HH:mm（24 小时制），例如 07:00");
            return result;
        }
        try {
            upsert(KEY_TIME, t, session);
            upsert(KEY_ENABLED, "1".equals(enabled) || "true".equalsIgnoreCase(enabled) ? "1" : "0", session);
            result.put("success", true);
            result.put("time", t);
            result.put("message", "已保存：每天 " + t + " 自动触发（下一轮判断即生效，无需重启）");
        } catch (Exception e) {
            log.warn("保存触发时间失败: {}", e.toString());
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    private void upsert(String key, String value, HttpSession session) {
        Object user = session == null ? null : session.getAttribute("username");
        jdbcTemplate.update(
                "INSERT INTO app_settings(k, v, updated_by) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE v = VALUES(v), updated_by = VALUES(updated_by)",
                key, value, user == null ? "admin" : String.valueOf(user));
    }

    private String setting(String key, String def) {
        try {
            java.util.List<String> vs = jdbcTemplate.queryForList("SELECT v FROM app_settings WHERE k = ?", String.class, key);
            if (vs != null && !vs.isEmpty() && vs.get(0) != null && !vs.get(0).isBlank()) return vs.get(0).trim();
        } catch (Exception ignore) { }
        return def;
    }
}
