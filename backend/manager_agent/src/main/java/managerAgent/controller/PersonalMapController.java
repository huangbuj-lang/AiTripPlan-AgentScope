package managerAgent.controller;

import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.util.*;

/**
 * ★专属地图：把生成的行程（每日地点序列）导入高德地图APP，生成"专属地图"唤端链接。
 * 实现：前端传 {title, destination, days:[{title, places:[名称...]}]} →
 *       后端逐个 geocode 查坐标（复用高德 place/text，QPS 限速 350ms）→
 *       JSON-RPC 直调高德MCP maps_schema_personal_map → 返回 amapuri:// 链接。
 */
@RestController
@RequestMapping("/api/amap")
public class PersonalMapController {

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
            String key = System.getenv().getOrDefault("AMAP_KEY", "");
            if (key.isBlank()) { resp.put("error", "AMAP_KEY 未配置"); return resp; }

            // 组 lineList：每天一个 line，地点逐个查坐标（查不到的跳过）
            List<Map<String, Object>> lineList = new ArrayList<>();
            for (JsonNode day : days) {
                String dayTitle = day.path("title").asText("行程");
                List<Map<String, Object>> points = new ArrayList<>();
                for (JsonNode pl : day.path("places")) {
                    String name = pl.asText("").trim();
                    if (name.isEmpty()) continue;
                    double[] ll = geocode(key, name, dest);
                    if (ll == null) continue; // 查不到坐标的地点不进地图
                    Map<String, Object> pt = new HashMap<>();
                    pt.put("name", name);
                    pt.put("lon", ll[0]);
                    pt.put("lat", ll[1]);
                    pt.put("poiId", "unknown"); // 高德仅校验非空
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

            String mcpKey = System.getenv().getOrDefault("AMAP_MCP_KEY",
                    System.getenv().getOrDefault("AMAP_KEY", ""));
            java.net.URL url = new java.net.URL("https://mcp.amap.com/mcp?key=" + mcpKey);
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

    /** 地名→坐标（复用高德 place/text），失败返回 null；350ms 间隔防超QPS */
    private double[] geocode(String key, String name, String city) {
        try {
            String qs = "keywords=" + URLEncoder.encode(name, "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            if (city != null && !city.isBlank()) qs += "&city=" + URLEncoder.encode(city, "UTF-8");
            java.net.URL u = new java.net.URL("https://restapi.amap.com/v3/place/text?" + qs);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            JsonNode poi = om.readTree(b).path("pois").path(0);
            if (poi.isMissingNode()) return null;
            String loc = poi.path("location").asText("");
            String[] parts = loc.split(",");
            Thread.sleep(350); // QPS 3 限速
            if (parts.length < 2) return null;
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return null;
        }
    }
}
