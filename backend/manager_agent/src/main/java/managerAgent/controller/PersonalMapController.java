package managerAgent.controller;

import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import utils.ExternalServices;

import java.net.URLEncoder;
import java.util.*;

/**
 * 专属地图：把行程（每日地点序列）生成高德地图APP唤端链接。
 * 实现：前端传 {title, destination, days:[{title, places:[名称...]}]} →
 *       后端逐个 geocode 查坐标（复用高德 place/text，QPS 限速 350ms）→
 *       JSON-RPC 直调高德MCP maps_schema_personal_map → 返回 amapuri:// 链接。
 */
@RestController
@RequestMapping("/api/amap")
public class PersonalMapController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PersonalMapController.class);

    private final ObjectMapper om = new ObjectMapper();

    @PostMapping("/personal-map")
    public Map<String, Object> personalMap(@RequestBody String body) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", false);
        try {
            JsonNode root = om.readTree(body);
            String title = root.path("title").asText("行程规划");
            String dest = root.path("destination").asText("");
            JsonNode days = root.path("days");
            if (!days.isArray() || days.size() == 0) {
                resp.put("error", "缺少行程数据");
                return resp;
            }
            String key = ExternalServices.amapKey();
            if (key.isBlank()) { resp.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY"); return resp; }

            // 组 lineList：每天一个 line，地点逐个查坐标（查不到的跳过）
            List<Map<String, Object>> lineList = new ArrayList<>();
            for (JsonNode day : days) {
                String dayTitle = day.path("title").asText("行程");
                List<Map<String, Object>> points = new ArrayList<>();
                for (JsonNode pl : day.path("places")) {
                    String name = pl.asText("").trim();
                    if (name.isEmpty()) continue;
                    String[] ll = geocode(key, name, dest);
                    if (ll == null) continue; // 查不到坐标的地点不进地图
                    Map<String, Object> pt = new HashMap<>();
                    pt.put("name", name);
                    pt.put("lon", Double.parseDouble(ll[0]));
                    pt.put("lat", Double.parseDouble(ll[1]));
                    pt.put("poiId", ll.length >= 3 && !ll[2].isEmpty() ? ll[2] : "unknown"); // 高德POI ID(vid)，缺id途经点会被丢弃
                    points.add(pt);
                }
                if (!points.isEmpty()) {
                    Map<String, Object> line = new HashMap<>();
                    line.put("title", dayTitle);
                    line.put("pointInfoList", points);
                    lineList.add(line);
                }
            }
            if (lineList.isEmpty()) { resp.put("error", "没有可定位的地点"); return resp; }

            // 同时生成网页版路线链接（vname/vlat/vlon/vid 用|分隔，须带vid）
            try {
                List<String> allPts = new ArrayList<>();
                for (Map<String, Object> line : lineList) {
                    for (Object o : (List<?>) line.get("pointInfoList")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pt = (Map<String, Object>) o;
                        allPts.add(pt.get("lon") + "," + pt.get("lat") + "," + pt.get("name") + "," + pt.get("poiId"));
                    }
                }
                if (!allPts.isEmpty()) {
                    // 高德官方 URI API 路线链接：市内默认公交模式（mode=bus），多途经点 via 分号分隔
                    resp.put("webUrl", ExternalServices.buildNavigationUrl(allPts));
                    // 补充：静态地图图片兜底
                    resp.put("staticMapUrl", buildStaticMapUrl(allPts));
                }
            } catch (Exception ignore) { }

            // JSON-RPC 直调高德 MCP
            Map<String, Object> args = new HashMap<>();
            args.put("orgName", "AiTripPlan");
            args.put("lineList", lineList);
            Map<String, Object> params = new HashMap<>();
            params.put("name", "maps_schema_personal_map");
            params.put("arguments", args);
            Map<String, Object> rpc = new LinkedHashMap<>();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", 1);
            rpc.put("method", "tools/call");
            rpc.put("params", params);

            String mcpKey = ExternalServices.amapMcpKey();
            java.net.URL url = new java.net.URL(ExternalServices.amapMcpUrl(mcpKey));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            conn.getOutputStream().write(om.writeValueAsBytes(rpc));
            String out = new String(conn.getInputStream().readAllBytes(), "UTF-8");
            if (out.contains("\ndata:")) {
                for (String line : out.split("\n")) {
                    if (line.startsWith("data:")) { out = line.substring(5).trim(); break; }
                }
            }
            JsonNode res = om.readTree(out);
            String text = res.path("result").path("content").path(0).path("text").asText("");
            if (text.startsWith("amapuri://") || text.startsWith("https://")) {
                resp.put("ok", true);
                resp.put("url", text);
            } else {
                resp.put("error", text.isBlank() ? "生成失败" : text);
            }
        } catch (Exception e) {
            resp.put("error", e.getMessage());
        }
        return resp;
    }

    /**
     * 生成静态专属地图：高德静态地图 API。
     * 全部景点 markers（A/B/C... 字母标记）+ 按行程顺序 path 连线。
     * 注意：ditu.amap.com 网页版 URL 不支持多途经点直传，故用静态地图图片展示全景点衔接。
     * 返回后端代理 URL（/api/amap/static-map），key 不暴露给前端。
     */
    private String buildStaticMapUrl(List<String> allPts) {
        try {
            String key = ExternalServices.amapKey();
            if (key.isBlank()) return "";
            // 解析所有点
            List<double[]> pts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String s : allPts) {
                String[] parts = s.split(",");
                if (parts.length >= 2) {
                    try {
                        pts.add(new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])});
                        names.add(parts.length >= 3 ? parts[2] : "");
                    } catch (NumberFormatException ignore) { }
                }
            }
            if (pts.size() < 2) return "";
            // markers：共用一个 mid 前缀，第一个点带字母标记，其余只给坐标（高德静态图要求）
            StringBuilder markers = new StringBuilder("mid,,A:");
            for (int i = 0; i < pts.size(); i++) {
                if (i > 0) markers.append(";");
                markers.append(pts.get(i)[0]).append(",").append(pts.get(i)[1]);
            }
            // path：按行程顺序连线
            StringBuilder path = new StringBuilder("3,0x0000ff,1,,:");
            for (int i = 0; i < pts.size(); i++) {
                if (i > 0) path.append(";");
                path.append(pts.get(i)[0]).append(",").append(pts.get(i)[1]);
            }
            // zoom：按所有点跨度估算（城市内11~12，跨城8~9）
            double minLon = pts.stream().mapToDouble(p -> p[0]).min().orElse(0);
            double maxLon = pts.stream().mapToDouble(p -> p[0]).max().orElse(0);
            double minLat = pts.stream().mapToDouble(p -> p[1]).min().orElse(0);
            double maxLat = pts.stream().mapToDouble(p -> p[1]).max().orElse(0);
            double span = Math.max(maxLon - minLon, maxLat - minLat);
            int zoom = span < 0.15 ? 13 : span < 0.4 ? 12 : span < 0.8 ? 11 : span < 2 ? 9 : 7;
            String qs = "markers=" + URLEncoder.encode(markers.toString(), "UTF-8")
                    + "&path=" + URLEncoder.encode(path.toString(), "UTF-8")
                    + "&zoom=" + zoom + "&size=800*500&key=" + key;
            return "/api/amap/static-map?" + qs;
        } catch (Exception e) {
            return "";
        }
    }

    /** 代理：把高德静态地图图片字节流回给前端（key 只在后端） */
    @GetMapping("/static-map")
    public org.springframework.http.ResponseEntity<byte[]> staticMap(
            @RequestParam String markers, @RequestParam String path,
            @RequestParam(defaultValue = "11") int zoom) {
        try {
            String key = ExternalServices.amapKey();
            String url = ExternalServices.amapStaticMapUrl("markers="
                    + URLEncoder.encode(markers, "UTF-8")
                    + "&path=" + URLEncoder.encode(path, "UTF-8")
                    + "&zoom=" + zoom + "&size=800*500&key=" + key);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            byte[] img = c.getInputStream().readAllBytes();
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "image/png")
                    .header("Cache-Control", "max-age=3600")
                    .body(img);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(502).build();
        }
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
            String qs = "keywords=" + URLEncoder.encode(kw, "UTF-8")
                    + "&output=json&key=" + key + "&offset=5&extensions=base";
            if (cleanCity != null && !cleanCity.isBlank()) qs += "&city=" + URLEncoder.encode(cleanCity, "UTF-8");
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            JsonNode pois = om.readTree(b).path("pois");
            if (pois.isEmpty()) {
                log.warn("[geocode诊断] keywords={} 无命中, status={} infocode={} info={} keyLen={}",
                        kw, om.readTree(b).path("status").asText(""),
                        om.readTree(b).path("infocode").asText(""), om.readTree(b).path("info").asText(""),
                        ExternalServices.amapKey().length());
            }
            double[] center = destCenter(key, cleanCity);
            for (JsonNode poi : pois) {
                String loc = poi.path("location").asText("");
                String[] parts = loc.split(",");
                if (parts.length < 2) continue;
                // ★围栏校验：坐标须落在目的地中心 150km 内，越界视为同名错配，跳过
                if (center != null) {
                    double d = distKm(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]),
                            center[1], center[0]);
                    if (d > 150) continue;
                }
                Thread.sleep(350); // QPS 3 限速
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
            String qs = "keywords=" + URLEncoder.encode(city, "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            JsonNode poi = om.readTree(b).path("pois").path(0);
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
}
