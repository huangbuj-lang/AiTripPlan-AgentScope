package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import utils.JsonUtils;
import utils.ExternalServices;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程分享接口（P0）：生成/撤销分享链接 + 免登录只读页。
 * share_code 短码指向 trip_plans，只读页复用 A4 渲染组件渲染 plan_json。
 */
@RestController
@RequestMapping("/api")
public class ShareController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"; // 去除易混淆字符

    private long userId(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 生成随机 share_code（10 位） */
    private String genCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 创建分享（幂等：同一行程重复分享则刷新 code；可设密码/有效期） */
    @PostMapping("/trips/{id}/share")
    public Map<String, Object> createShare(@PathVariable Long id,
                                           @RequestParam(required = false) String password,
                                           @RequestParam(required = false) Integer validDays,
                                           HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        // 校验行程归属
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_plans WHERE id = ? AND user_id = ?", Integer.class, id, uid);
        if (cnt == null || cnt == 0) {
            result.put("success", false);
            result.put("message", "行程不存在或无权分享");
            return result;
        }
        String code = genCode();
        String pwdHash = (password == null || password.isBlank()) ? null : passwordEncoder.encode(password);
        String expiresAt = null;
        if (validDays != null && validDays > 0) {
            expiresAt = jdbcTemplate.queryForObject(
                    "SELECT DATE_ADD(NOW(), INTERVAL ? DAY)", String.class, validDays);
        }
        // upsert（uk_trip 唯一，重复分享刷新）
        jdbcTemplate.update(
                "INSERT INTO trip_shares(trip_id, user_id, share_code, password_hash, is_public, expires_at) " +
                "VALUES (?, ?, ?, ?, 1, ?) " +
                "ON DUPLICATE KEY UPDATE share_code=VALUES(share_code), password_hash=VALUES(password_hash), " +
                "is_public=1, expires_at=VALUES(expires_at)",
                id, uid, code, pwdHash, expiresAt);
        result.put("success", true);
        result.put("shareCode", code);
        result.put("url", "/share.html?c=" + code);
        return result;
    }

    /** 撤销分享 */
    @DeleteMapping("/trips/{id}/share")
    public Map<String, Object> revokeShare(@PathVariable Long id, HttpSession session) {
        long uid = userId(session);
        int rows = jdbcTemplate.update("DELETE FROM trip_shares WHERE trip_id = ? AND user_id = ?", id, uid);
        Map<String, Object> result = new HashMap<>();
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已撤销分享" : "该行程未分享");
        return result;
    }

    /**
     * 免登录只读页：按 code 取行程内容（结构化 JSON 优先，markdown 兜底）。
     * 可选密码校验；浏览量 +1。
     */
    @GetMapping("/share/{code}")
    public Map<String, Object> getSharedTrip(@PathVariable String code,
                                             @RequestParam(required = false) String password) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> shares = jdbcTemplate.queryForList(
                "SELECT * FROM trip_shares WHERE share_code = ?", code);
        if (shares.isEmpty()) {
            result.put("success", false);
            result.put("message", "分享不存在或已撤销");
            return result;
        }
        Map<String, Object> share = shares.get(0);
        // 有效期校验
        if (share.get("expires_at") != null) {
            Integer expired = jdbcTemplate.queryForObject(
                    "SELECT NOW() > ?", Integer.class, share.get("expires_at"));
            if (expired != null && expired == 1) {
                result.put("success", false);
                result.put("message", "分享链接已过期");
                return result;
            }
        }
        // 密码校验
        if (share.get("password_hash") != null) {
            if (password == null || !passwordEncoder.matches(password, (String) share.get("password_hash"))) {
                result.put("success", false);
                result.put("needPassword", true);
                result.put("message", "该分享需要密码");
                return result;
            }
        }
        // 取行程内容
        Long tripId = ((Number) share.get("trip_id")).longValue();
        Map<String, Object> trip = jdbcTemplate.queryForMap(
                "SELECT city, days, plan_content, plan_json FROM trip_plans WHERE id = ?", tripId);
        jdbcTemplate.update("UPDATE trip_shares SET view_count = view_count + 1 WHERE share_code = ?", code);
        result.put("success", true);
        result.put("city", trip.get("city"));
        result.put("days", trip.get("days"));
        result.put("plan_content", trip.get("plan_content"));
        result.put("plan_json", trip.get("plan_json"));
        result.put("view_count", ((Number) share.get("view_count")).intValue() + 1);
        return result;
    }

    /**
     * ★分享页按天生成路线图：免登录，从分享行程的 plan_json 提取第 N 天景点，
     * 逐个 geocode 拿坐标+POI ID，拼 ditu.amap.com/ssr/dir 多途经点链接（当天全部景点衔接）。
     * day 从 1 开始。
     */
    @GetMapping("/share/{code}/day-map")
    public Map<String, Object> dayMap(@PathVariable String code, @RequestParam int day) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", false);
        try {
            List<Map<String, Object>> shares = jdbcTemplate.queryForList(
                    "SELECT trip_id FROM trip_shares WHERE share_code = ?", code);
            if (shares.isEmpty()) { result.put("error", "分享不存在或已撤销"); return result; }
            Long tripId = ((Number) shares.get(0).get("trip_id")).longValue();
            Map<String, Object> trip = jdbcTemplate.queryForMap(
                    "SELECT city, plan_json FROM trip_plans WHERE id = ?", tripId);
            String planJson = trip.get("plan_json") == null ? null : String.valueOf(trip.get("plan_json"));
            if (planJson == null || planJson.isBlank()) { result.put("error", "该行程无结构化数据"); return result; }
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(planJson);
            com.fasterxml.jackson.databind.JsonNode sections = root.path("sections");
            if (!sections.isArray()) { result.put("error", "行程数据异常"); return result; }
            // 提取第 day 天（1基）的 timeline 区块
            List<String> places = new java.util.ArrayList<>();
            int dayIdx = 0;
            for (com.fasterxml.jackson.databind.JsonNode sec : sections) {
                String type = sec.path("type").asText("");
                String title = sec.path("title").asText("");
                boolean isDay = "timeline".equals(type) || title.matches("第.+天");
                if (!isDay) continue;
                dayIdx++;
                if (dayIdx != day) continue;
                for (com.fasterxml.jackson.databind.JsonNode it : sec.path("items")) {
                    String name = it.path("place").asText("").trim();
                    if (name.isEmpty()) name = it.path("name").asText("").trim();
                    if (!name.isEmpty() && !places.contains(name)) places.add(name);
                }
                break;
            }
            if (places.isEmpty()) { result.put("error", "第 " + day + " 天没有可定位的景点"); return result; }
            // 逐个 geocode（复用高德 place/text，350ms 限速）
            String key = ExternalServices.amapKey();
            if (key.isBlank()) { result.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY"); return result; }
            String city = trip.get("city") == null ? "" : String.valueOf(trip.get("city"));
            List<String> allPts = new java.util.ArrayList<>();
            for (String name : places) {
                String[] ll = geocode(key, name, city);
                if (ll == null) continue;
                allPts.add(ll[0] + "," + ll[1] + "," + name + "," + ll[2]);
            }
            if (allPts.size() < 2) { result.put("error", "当天可定位的景点不足 2 个"); return result; }
            // ★ditu.amap.com 网页版驾车 SSR：fname/flat/flon + vname|vlat|vlon|vid 途经点（PC 直接打开不丢途经点）
            result.put("ok", true);
            result.put("webUrl", ExternalServices.buildSsrDirUrl(allPts));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 目的地中心坐标缓存：city→{lon,lat}（进程内，防重复请求） */
    private static final java.util.concurrent.ConcurrentHashMap<String, double[]> DEST_CENTER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 地名→坐标+POI ID：关键词带目的地限定 + 目的地围栏校验（防同名景点错配，如"五彩池"被解析到黄龙），失败返回 null；350ms 限速 */
    /** 地名 geocode：原文查不到时对「·」子地点格式（place具体化铁律产物，如"束河古镇·青龙桥"）按 子地点→大地点 依次重试 */
    private String[] geocode(String key, String name, String city) {
        String[] direct = geocodeOnce(key, name, city);
        if (direct != null) return direct;
        int sep = name.indexOf('·');
        if (sep >= 0) {
            String sub = name.substring(sep + 1).trim();
            String parent = name.substring(0, sep).trim();
            if (!sub.isBlank()) {
                String[] r = geocodeOnce(key, sub, city);
                if (r != null) return r;
            }
            if (!parent.isBlank()) {
                String[] r = geocodeOnce(key, parent, city);
                if (r != null) return r;
            }
        }
        return null;
    }

    private String[] geocodeOnce(String key, String name, String city) {
        try {
            String cleanCity = cleanCityName(city);
            // ★关键词组合：目的地+景点名（如 "九寨沟 五彩池"），提升同城景点命中率
            String kw = (cleanCity != null && !cleanCity.isBlank() && !name.contains(cleanCity))
                    ? name + " " + cleanCity : name;
            String qs = "keywords=" + java.net.URLEncoder.encode(kw, "UTF-8")
                    + "&output=json&key=" + key + "&offset=5&extensions=base";
            if (cleanCity != null && !cleanCity.isBlank()) qs += "&city=" + java.net.URLEncoder.encode(cleanCity, "UTF-8");
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            com.fasterxml.jackson.databind.JsonNode pois = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b).path("pois");
            double[] center = destCenter(key, cleanCity);
            for (com.fasterxml.jackson.databind.JsonNode poi : pois) {
                String loc = poi.path("location").asText("");
                String[] parts = loc.split(",");
                if (parts.length < 2) continue;
                // ★围栏校验改「精确同名优先」：与景点完全同名（如「泸定桥」）的可信直接采用；围栏仅拦无精确同名命中的
                //   错配（莫高窟→杭州类）。修复目的地为区域名（川西）时中心落省会、同省景点全判越界的误杀
                boolean exactSame = name.equals(poi.path("name").asText(""));
                if (center != null && !exactSame) {
                    double d = distKm(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]),
                            center[1], center[0]);
                    if (d > 150) continue;
                }
                Thread.sleep(350);
                return new String[]{parts[0], parts[1], poi.path("id").asText("")};
            }
            Thread.sleep(350);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目的地中心坐标（place/text 首条，进程内缓存） */
    private double[] destCenter(String key, String city) {
        if (city == null || city.isBlank()) return null;
        double[] hit = DEST_CENTER_CACHE.get(city);
        if (hit != null) return hit;
        try {
            Thread.sleep(350); // QPS 3 限速
            String qs = "keywords=" + java.net.URLEncoder.encode(city, "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            com.fasterxml.jackson.databind.JsonNode poi = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b).path("pois").path(0);
            String loc = poi.path("location").asText("");
            String[] p = loc.split(",");
            if (p.length < 2) return null;
            double[] v = {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
            DEST_CENTER_CACHE.put(city, v);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目的地名清洗：去掉 "3 日"/"三日游" 等后缀 */
    private String cleanCityName(String city) {
        if (city == null) return null;
        return city.trim().replaceAll("\\d+\\s*[日天]|[一二三四五六七八九十]+\\s*[日天]|游$", "").trim();
    }

    /** 两点球面距离（km） */
    private double distKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** 查询某行程的分享状态（前端"我的行程"展示分享链接） */
    @GetMapping("/trips/{id}/share")
    public Map<String, Object> getShareStatus(@PathVariable Long id, HttpSession session) {
        long uid = userId(session);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> shares = jdbcTemplate.queryForList(
                "SELECT share_code, view_count, created_at FROM trip_shares WHERE trip_id = ? AND user_id = ?", id, uid);
        if (shares.isEmpty()) {
            result.put("success", true);
            result.put("shared", false);
        } else {
            result.put("success", true);
            result.put("shared", true);
            result.put("shareCode", shares.get(0).get("share_code"));
            result.put("viewCount", shares.get(0).get("view_count"));
            result.put("url", "/share.html?c=" + shares.get(0).get("share_code"));
        }
        return result;
    }
}
