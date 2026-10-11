package managerAgent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import managerAgent.tool.AmapPlaceGeocoder;
import org.springframework.stereotype.Service;
import utils.ExternalServices;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 专属地图业务：把行程（每日地点序列）生成高德地图APP唤端链接、网页路线链接、静态地图。 */
@Service
public class PersonalMapService {

    private final ObjectMapper om = new ObjectMapper();

    /** 行程 -> 专属地图唤端链接（含静态地图兜底） */
    public Map<String, Object> personalMap(String body) {
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

            //组 lineList：每天一个 line，地点逐个查坐标（查不到的跳过）
            List<Map<String, Object>> lineList = new ArrayList<>();
            for (JsonNode day : days) {
                String dayTitle = day.path("title").asText("行程");
                List<Map<String, Object>> points = new ArrayList<>();
                for (JsonNode pl : day.path("places")) {
                    String name = pl.asText("").trim();
                    if (name.isEmpty()) continue;
                    String[] ll = AmapPlaceGeocoder.geocode(key, name, dest);
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

            //同时生成网页版路线链接（vname/vlat/vlon/vid 用|分隔，须带vid）
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
                    String[] first = allPts.get(0).split(",", -1);
                    String[] last = allPts.get(allPts.size() - 1).split(",", -1);
                    StringBuilder nav = new StringBuilder(ExternalServices.amapNavigationUrl());
                    nav.append("&fname=").append(URLEncoder.encode(first.length >= 3 ? first[2] : "", "UTF-8"));
                    nav.append("&flat=").append(first[1]).append("&flon=").append(first[0]);
                    nav.append("&dname=").append(URLEncoder.encode(last.length >= 3 ? last[2] : "", "UTF-8"));
                    nav.append("&dlat=").append(last[1]).append("&dlon=").append(last[0]);
                    //中间全部为途经点（| 分隔）
                    if (allPts.size() > 2) {
                        StringBuilder vn = new StringBuilder(); // vname
                        StringBuilder va = new StringBuilder(); // vlat
                        StringBuilder vo = new StringBuilder(); // vlon
                        StringBuilder vi = new StringBuilder(); // vid
                        for (int i = 1; i < allPts.size() - 1; i++) {
                            String[] pp = allPts.get(i).split(",", -1);
                            if (vn.length() > 0) { vn.append("|"); va.append("|"); vo.append("|"); vi.append("|"); }
                            vn.append(URLEncoder.encode(pp.length >= 3 ? pp[2] : "", "UTF-8"));
                            va.append(pp[1]);
                            vo.append(pp[0]);
                            vi.append(URLEncoder.encode(pp.length >= 4 && !"unknown".equals(pp[3]) ? pp[3] : "", "UTF-8"));
                        }
                        nav.append("&vname=").append(vn).append("&vlat=").append(va)
                           .append("&vlon=").append(vo).append("&vid=").append(vi);
                    }
                    resp.put("webUrl", nav.toString());
                    //补充：静态地图图片兜底
                    resp.put("staticMapUrl", buildStaticMapUrl(allPts));
                }
            } catch (Exception ignore) { }

            //JSON-RPC 直调高德 MCP
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

    /** 生成静态专属地图URL：高德静态地图 API。 */
    String buildStaticMapUrl(List<String> allPts) {
        try {
            String key = ExternalServices.amapKey();
            if (key.isBlank()) return "";
            //解析所有点
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
            //markers：共用一个 mid 前缀，第一个点带字母标记，其余只给坐标（高德静态图要求）
            StringBuilder markers = new StringBuilder("mid,,A:");
            for (int i = 0; i < pts.size(); i++) {
                if (i > 0) markers.append(";");
                markers.append(pts.get(i)[0]).append(",").append(pts.get(i)[1]);
            }
            //path：按行程顺序连线
            StringBuilder path = new StringBuilder("3,0x0000ff,1,,:");
            for (int i = 0; i < pts.size(); i++) {
                if (i > 0) path.append(";");
                path.append(pts.get(i)[0]).append(",").append(pts.get(i)[1]);
            }
            //zoom：按所有点跨度估算（城市内11~12，跨城8~9）
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

    /** 代理取图：把高德静态地图图片字节流回给前端（key 只在后端），失败返回 null */
    public byte[] staticMapImage(String markers, String path, int zoom) {
        try {
            String key = ExternalServices.amapKey();
            String url = ExternalServices.amapStaticMapUrl("markers="
                    + URLEncoder.encode(markers, "UTF-8")
                    + "&path=" + URLEncoder.encode(path, "UTF-8")
                    + "&zoom=" + zoom + "&size=800*500&key=" + key);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            return c.getInputStream().readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
