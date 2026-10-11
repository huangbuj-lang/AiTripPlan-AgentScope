package managerAgent.service;

import jakarta.annotation.Resource;
import managerAgent.repository.ShareRepository;
import managerAgent.tool.AmapPlaceGeocoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import utils.ExternalServices;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程分享业务（P0）：生成/撤销分享链接、免登录只读页、按天路线图。
 * 只做业务，SQL 在 ShareRepository，高德地名解析在 AmapPlaceGeocoder。
 */
@Service
public class ShareService {

    @Resource
    private ShareRepository shareRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"; // 去除易混淆字符

    /** 生成随机 share_code（10 位） */
    private String genCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 创建分享（幂等：同一行程重复分享则刷新 code） */
    public Map<String, Object> createShare(long uid, Long tripId, String password, Integer validDays) {
        Map<String, Object> result = new HashMap<>();
        if (shareRepository.countTripOwned(tripId, uid) == 0) { //校验行程归属
            result.put("success", false);
            result.put("message", "行程不存在或无权分享");
            return result;
        }
        String code = genCode();
        String pwdHash = (password == null || password.isBlank()) ? null : passwordEncoder.encode(password);
        String expiresAt = null;
        if (validDays != null && validDays > 0) {
            expiresAt = shareRepository.expiresAtInDays(validDays);
        }
        shareRepository.upsertShare(tripId, uid, code, pwdHash, expiresAt); //upsert（uk_trip 唯一，重复分享刷新）
        result.put("success", true);
        result.put("shareCode", code);
        result.put("url", "/share.html?c=" + code);
        return result;
    }

    /** 撤销分享 */
    public Map<String, Object> revokeShare(long uid, Long tripId) {
        int rows = shareRepository.deleteShare(tripId, uid);
        Map<String, Object> result = new HashMap<>();
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已撤销分享" : "该行程未分享");
        return result;
    }

    /** 免登录只读页：按 code 取行程内容（结构化 JSON 优先，markdown 兜底）。 */
    public Map<String, Object> getSharedTrip(String code, String password) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> shares = shareRepository.findByCode(code);
        if (shares.isEmpty()) {
            result.put("success", false);
            result.put("message", "分享不存在或已撤销");
            return result;
        }
        Map<String, Object> share = shares.get(0);
        if (share.get("expires_at") != null) { //有效期校验
            Integer expired = shareRepository.isExpired(share.get("expires_at"));
            if (expired != null && expired == 1) {
                result.put("success", false);
                result.put("message", "分享链接已过期");
                return result;
            }
        }
        if (share.get("password_hash") != null) { //密码校验
            if (password == null || !passwordEncoder.matches(password, (String) share.get("password_hash"))) {
                result.put("success", false);
                result.put("needPassword", true);
                result.put("message", "该分享需要密码");
                return result;
            }
        }
        Long tripId = ((Number) share.get("trip_id")).longValue();
        Map<String, Object> trip = shareRepository.findTripForShare(tripId); //取行程内容
        shareRepository.incrementView(code);
        result.put("success", true);
        result.put("city", trip.get("city"));
        result.put("days", trip.get("days"));
        result.put("plan_content", trip.get("plan_content"));
        result.put("plan_json", trip.get("plan_json"));
        result.put("view_count", ((Number) share.get("view_count")).intValue() + 1);
        return result;
    }

    /** 分享页按天生成路线图：免登录，从分享行程的 plan_json 提取第 N 天景点，逐个 geocode 拿坐标+POI ID，拼高德多途经点链接。 */
    public Map<String, Object> dayMap(String code, int day) {
        Map<String, Object> result = new HashMap<>();
        result.put("ok", false);
        try {
            List<Map<String, Object>> shares = shareRepository.findTripIdByCode(code);
            if (shares.isEmpty()) { result.put("error", "分享不存在或已撤销"); return result; }
            Long tripId = ((Number) shares.get(0).get("trip_id")).longValue();
            Map<String, Object> trip = shareRepository.findTripCityAndJson(tripId);
            String planJson = trip.get("plan_json") == null ? null : String.valueOf(trip.get("plan_json"));
            if (planJson == null || planJson.isBlank()) { result.put("error", "该行程无结构化数据"); return result; }
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(planJson);
            com.fasterxml.jackson.databind.JsonNode sections = root.path("sections");
            if (!sections.isArray()) { result.put("error", "行程数据异常"); return result; }
            //提取第 day 天（1基）的 timeline 区块
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
            //逐个 geocode（复用高德 place/text，350ms 限速）
            String key = ExternalServices.amapKey();
            if (key.isBlank()) { result.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY"); return result; }
            String city = trip.get("city") == null ? "" : String.valueOf(trip.get("city"));
            List<String> allPts = new java.util.ArrayList<>();
            for (String name : places) {
                String[] ll = AmapPlaceGeocoder.geocode(key, name, city);
                if (ll == null) continue;
                allPts.add(ll[0] + "," + ll[1] + "," + name + "," + ll[2]);
            }
            if (allPts.size() < 2) { result.put("error", "当天可定位的景点不足 2 个"); return result; }
            //拼高德路线链接
            String[] first = allPts.get(0).split(",", -1);
            String[] last = allPts.get(allPts.size() - 1).split(",", -1);
            StringBuilder nav = new StringBuilder(ExternalServices.amapNavigationUrl());
            nav.append("&fname=").append(java.net.URLEncoder.encode(first.length >= 3 ? first[2] : "", "UTF-8"));
            nav.append("&flat=").append(first[1]).append("&flon=").append(first[0]);
            nav.append("&dname=").append(java.net.URLEncoder.encode(last.length >= 3 ? last[2] : "", "UTF-8"));
            nav.append("&dlat=").append(last[1]).append("&dlon=").append(last[0]);
            if (allPts.size() > 2) {
                StringBuilder vn = new StringBuilder(), va = new StringBuilder(), vo = new StringBuilder(), vi = new StringBuilder();
                for (int i = 1; i < allPts.size() - 1; i++) {
                    String[] pp = allPts.get(i).split(",", -1);
                    if (vn.length() > 0) { vn.append("|"); va.append("|"); vo.append("|"); vi.append("|"); }
                    vn.append(java.net.URLEncoder.encode(pp.length >= 3 ? pp[2] : "", "UTF-8"));
                    va.append(pp[1]); vo.append(pp[0]);
                    vi.append(java.net.URLEncoder.encode(pp.length >= 4 && !"unknown".equals(pp[3]) ? pp[3] : "", "UTF-8"));
                }
                nav.append("&vname=").append(vn).append("&vlat=").append(va)
                   .append("&vlon=").append(vo).append("&vid=").append(vi);
            }
            result.put("ok", true);
            result.put("webUrl", nav.toString());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 查询某行程的分享状态（前端"我的行程"展示分享链接） */
    public Map<String, Object> getShareStatus(long uid, Long tripId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> shares = shareRepository.findShareStatus(tripId, uid);
        result.put("success", true);
        if (shares.isEmpty()) {
            result.put("shared", false);
        } else {
            result.put("shared", true);
            result.put("shareCode", shares.get(0).get("share_code"));
            result.put("viewCount", shares.get(0).get("view_count"));
            result.put("url", "/share.html?c=" + shares.get(0).get("share_code"));
        }
        return result;
    }
}
