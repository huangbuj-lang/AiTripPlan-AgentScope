package managerAgent.controller;

import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import utils.ExternalServices;
    import io.agentscope.core.message.Msg;
    import io.agentscope.core.message.MsgRole;
    import io.agentscope.core.message.TextBlock;
    import io.agentscope.core.message.ContentBlock;
    import io.agentscope.core.model.ChatResponse;
    import io.agentscope.core.model.GenerateOptions;
    import utils.LlmClient;
    import utils.ModelTimeouts;

import java.net.URLEncoder;
import java.util.*;

/** 专属地图：把行程（每日地点序列）生成高德地图APP唤端链接。 */
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
            String key = ExternalServices.amapKey();
            if (key.isBlank()) { resp.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY"); return resp; }

            //组 lineList：每天一个 line，地点逐个查坐标（查不到的跳过）
            List<Map<String, Object>> lineList = new ArrayList<>();
            for (JsonNode day : days) {
                String dayTitle = day.path("title").asText("行程");
                List<Map<String, Object>> points = new ArrayList<>();
                //★用户口径（2026-09-13）：先把这一天的地点交给 LLM 判断「该用什么检索词、在哪个城市」，
                //  再去高德查坐标（坐标只取高德返回值）。不再把行程目的地拼进关键词、不再靠距离就近校正。
                //  LLM/高德任一步失败 → 退回原来的 geocode，保证地图照旧能出。
                Map<String, String[]> resolved = null;
                try {
                    List<String> dayNames = new ArrayList<>();
                    for (JsonNode pl : day.path("places")) {
                        String nm = pl.asText("").trim();
                        if (!nm.isEmpty()) dayNames.add(nm);
                    }
                    resolved = llmResolveQueries(dayNames, dest, dayTitle);
                } catch (Exception ignore) { }
                for (JsonNode pl : day.path("places")) {
                    String name = pl.asText("").trim();
                    if (name.isEmpty()) continue;
                    String[] ll = null;
                    if (resolved != null && resolved.containsKey(name)) {
                        String[] rv = resolved.get(name);
                        ll = geocodeByQuery(key, rv[0], (rv[1] == null || rv[1].isBlank()) ? dest : rv[1]);
                    }
                    if (ll == null) ll = geocode(key, name, dest);   //兜底：LLM 判不出/高德查不到 → 老路径
                    if (ll == null) continue; // 查不到坐标的地点不进地图
                    Map<String, Object> pt = new HashMap<>();
                    pt.put("name", name);
                    pt.put("lon", Double.parseDouble(ll[0]));
                    pt.put("lat", Double.parseDouble(ll[1]));
                    pt.put("poiId", ll.length >= 3 && !ll[2].isEmpty() ? ll[2] : "unknown"); // 高德POI ID(vid)，缺id途经点会被丢弃
                    pt.put("city", ll.length >= 4 ? ll[3] : "");
                    points.add(pt);
                }
                //★就近校正（用户口径：别让店名被解析到外省）：
                //  通用店名（如"金美大酒店"）在 destination 无法作为有效城市时会被解析到外省
                //  （实测"金美大酒店"落到天津静海区，页面表现为路线跨省、高德报"检测到重复地点"、终点丢失）。
                //  做法：用【当天自己已解析出的点】算中心，把偏离过大的点用"多数点所在城市"重查一次；
                //  仍偏离就丢弃（宁可地图上少一个点，也不要一条跑偏的路线）。
                //  ★阈值 200km 太严：自驾日跨度 300~500km 很正常（实测 乌鲁木齐→天山天池→奎屯市 约 250km，
                //    奎屯市被当异常点丢掉 → 地图只画出 天池→阜康 一段）。放到 600km：
                //    仍然能拦住"金美大酒店→天津静海"这类跨省张冠李戴（3000km 级），但不误伤正常长途。
                if (points.size() >= 2) {
                    Double mLon = medianOf(points, "lon");
                    Double mLat = medianOf(points, "lat");
                    String majorityCity = majorityCityOf(points);
                    List<Map<String, Object>> keep = new ArrayList<>();
                    for (Map<String, Object> pt : points) {
                        double d = distKm((Double) pt.get("lat"), (Double) pt.get("lon"), mLat, mLon);
                        if (d <= MAX_DRIFT_KM) { keep.add(pt); continue; }
                        String nm = String.valueOf(pt.get("name"));
                        String[] again = (majorityCity == null || majorityCity.isBlank())
                                ? null : geocode(key, nm, majorityCity);
                        if (again != null) {
                            double d2 = distKm(Double.parseDouble(again[1]), Double.parseDouble(again[0]), mLat, mLon);
                            if (d2 <= MAX_DRIFT_KM) {
                                pt.put("lon", Double.parseDouble(again[0]));
                                pt.put("lat", Double.parseDouble(again[1]));
                                pt.put("poiId", again.length >= 3 && !again[2].isEmpty() ? again[2] : "unknown");
                                keep.add(pt);
                                continue;
                            }
                        }
                        // 仍偏离 → 丢弃该点（不把错误坐标带进路线）
                    }
                    points = keep;
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

    /** 生成静态专属地图：高德静态地图 API。 */
    private String buildStaticMapUrl(List<String> allPts) {
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

    /** 就近校正的允许偏离（km）：超过它才认为坐标被解析到了错误的城市，去重查/丢弃 */
    private static final double MAX_DRIFT_KM = 600.0;

    /** 目的地中心坐标缓存：city->{lon,lat}（进程内，防重复请求） */
    private static final java.util.concurrent.ConcurrentHashMap<String, double[]> DEST_CENTER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 地名->坐标+POI ID：关键词带目的地限定 + 目的地围栏校验（防同名景点错配，如"五彩池"被解析到黄龙），失败返回 null； */
    /** 取某数值字段的中位数（就近校正用：比平均值更容忍个别异常点） */
    private Double medianOf(List<Map<String, Object>> pts, String key) {
        List<Double> xs = new ArrayList<>();
        for (Map<String, Object> p : pts) {
            Object v = p.get(key);
            if (v instanceof Double d) xs.add(d);
        }
        if (xs.isEmpty()) return null;
        Collections.sort(xs);
        return xs.get(xs.size() / 2);
    }

    /** 取多数点所在城市（就近校正重查时的城市提示） */
    private String majorityCityOf(List<Map<String, Object>> pts) {
        Map<String, Integer> cnt = new HashMap<>();
        for (Map<String, Object> p : pts) {
            Object c = p.get("city");
            String s = c == null ? "" : String.valueOf(c).trim();
            if (!s.isEmpty()) cnt.merge(s, 1, Integer::sum);
        }
        return cnt.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
    }

    private String[] geocode(String key, String name, String city) {
        try {
            String cleanCity = cleanCityName(city);
            //关键词组合：目的地+景点名（如 "九寨沟 五彩池"），提升同城景点命中率
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
            double[] center = destCenter(key, cleanCity);
            for (JsonNode poi : pois) {
                String loc = poi.path("location").asText("");
                String[] parts = loc.split(",");
                if (parts.length < 2) continue;
                //围栏校验：坐标须落在目的地中心 150km 内，越界视为同名错配，跳过
                if (center != null) {
                    double d = distKm(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]),
                            center[1], center[0]);
                    if (d > 150) continue;
                }
                Thread.sleep(350); // QPS 3 限速
                return new String[]{parts[0], parts[1], poi.path("id").asText(""),
                        poi.path("cityname").asText("")}; // [3]=城市名（就近校正用，不影响既有取值）
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

    /**
     * ★新增（只读）：地点名 → 高德坐标，供前端「一键导航」按钮使用。
     * 完全独立的新路由，不改动 /personal-map、/static-map 的任何既有行为。
     * 入参：{"places":[{"name":"天山天池","city":"阜康市"}],"cities":["阜康市"]}
     * 出参：{"ok":true,"items":[{"name":"…","city":"…","location":"经度,纬度"}]}（查不到 location 为 ""）
     */
    @PostMapping("/poi-locations")
    public Map<String, Object> poiLocations(@RequestBody(required = false) byte[] body) {
        Map<String, Object> res = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String reqText = body == null ? "{}" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode root = om.readTree(reqText);
            String key = ExternalServices.amapKey();
            String fallbackCity = (root.path("cities").isArray() && root.path("cities").size() > 0)
                    ? root.path("cities").get(0).asText("") : "";
            for (JsonNode nd : root.path("places")) {
                String name = nd.path("name").asText("").trim();
                String city = nd.path("city").asText("").trim();
                if (city.isEmpty()) city = fallbackCity;
                Map<String, Object> one = new HashMap<>();
                one.put("name", name);
                one.put("city", city);
                String loc = "";
                String poiId = "";
                if (!name.isEmpty()) {
                    //★只认【名称对得上】的高德结果：对不上宁可返回空（前端会退回高德搜索页），
                    //  否则"天山天池"这种查得到还好，乱写的名字会被高德兜底到第一条 → 一键导航导到错的地方。
                    String qs2 = "keywords=" + URLEncoder.encode(name, "UTF-8")
                            + "&output=json&key=" + key + "&offset=5&extensions=base";
                    if (city != null && !city.isBlank()) qs2 += "&city=" + URLEncoder.encode(city, "UTF-8");
                    java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL(
                            ExternalServices.amapPlaceSearchUrl(qs2)).openConnection();
                    c2.setConnectTimeout(5000);
                    c2.setReadTimeout(8000);
                    JsonNode pois2 = om.readTree(new String(c2.getInputStream().readAllBytes(), "UTF-8")).path("pois");
                    for (JsonNode poi : pois2) {
                        String pn = poi.path("name").asText("").trim();
                        String nm = pn.replaceAll("[(（].*$", "").trim();   //去掉"天山天池(售票处)"这类后缀
                        if (nm.equals(name) || nm.contains(name) || name.contains(nm)) {
                            loc = poi.path("location").asText("");
                            poiId = poi.path("id").asText("");   //★高德 POI 编号：前端用它拼「地点详情页」URL
                            break;
                        }
                    }
                }
                one.put("location", loc);
                one.put("poiId", poiId);
                out.add(one);
                try { Thread.sleep(350); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            res.put("ok", true);
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", "坐标查询失败：" + e.getMessage());
        }
        res.put("items", out);
        return res;
    }


    /**
     * ★用户口径（2026-09-13）：地点 → 高德检索词，直接交给 LLM 判断，
     *   不再靠"把行程目的地拼进关键词"和"距离就近校正"这类代码逻辑
     *   （实测：目的地是"伊犁"时，"天山天池"被拼成"天山天池 伊犁" → 高德给到赛里木湖一带，差 700 公里）。
     *   约定：LLM 只负责【判断该用什么检索词、在哪个城市】；坐标一律取高德返回的真实值，绝不编造。
     *   查不到/判断不出 → 返回原词，由高德兜底；仍查不到就不进地图。
     */
    private Map<String, String[]> llmResolveQueries(List<String> names, String dest, String dayHint) {
        Map<String, String[]> out = new HashMap<>();
        if (names == null || names.isEmpty()) return out;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("行程目的地：").append(dest == null ? "" : dest).append("\n");
            //★用户口径（2026-09-13）：行程上下文必须一起给 —— 光凭地点名猜行政区不准 ✗
            String ctx = dayHint == null ? "" : dayHint.trim();
            if (!ctx.isEmpty()) {
                sb.append("【行程上下文】下面这条行程的走向是已经生成好的事实，请结合它判断每个地点真实所在的行政区：\n");
                sb.append(ctx).append("\n");
            }
            sb.append("需要在地图上定位的地点（原词，可能不规范或含错别字）：\n");
            for (String n : names) sb.append("- ").append(n).append("\n");
            sb.append("\n请为每个地点给出高德地图能检索到的标准名称与所在城市。");
            sb.append("规则：①只判断名称，不要输出经纬度（经纬度由高德给）②不确定就用原词 ③城市必须是该点真实所在的城市/区县，不要因为行程目的地就套上去");
            sb.append("④只输出 JSON 数组，形如 [{\"in\":\"天山天池\",\"query\":\"天山天池\",\"city\":\"阜康市\"}]，不要输出任何其他文字。");
            Msg sys = Msg.builder().role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text("你是中国地理地名纠错助手。只输出 JSON 数组。").build()).build();
            Msg usr = Msg.builder().role(MsgRole.USER)
                    .content(TextBlock.builder().text(sb.toString()).build()).build();
            GenerateOptions opt = GenerateOptions.builder()
                    .additionalBodyParams(Map.of("response_format", Map.of("type", "json_object"))).build();
            ChatResponse resp = LlmClient.model().stream(List.of(sys, usr), null, opt)
                    .blockLast(ModelTimeouts.tripEditDuration());
            StringBuilder raw = new StringBuilder();
            if (resp != null) {
                for (ContentBlock b : resp.getContent()) if (b instanceof TextBlock tb) raw.append(tb.getText());
            }
            JsonNode arr = om.readTree(raw.toString());
            if (arr.isObject() && arr.path("items").isArray()) arr = arr.path("items");   // 兼容 {"items":[...]}
            if (arr.isArray()) {
                for (JsonNode it : arr) {
                    String in = it.path("in").asText("").trim();
                    String q = it.path("query").asText("").trim();
                    String c = it.path("city").asText("").trim();
                    if (!in.isEmpty()) out.put(in, new String[]{ q.isEmpty() ? in : q, c });
                }
            }
        } catch (Exception e) {
            // LLM 挂了就用原词 → 高德自己兜底，绝不影响地图生成
        }
        return out;
    }

    /** 干净的关键词查询：关键词只放名称（不拼城市），城市用 city 参数传 */
    private String[] geocodeByQuery(String key, String query, String city) {
        try {
            String qs = "keywords=" + URLEncoder.encode(query, "UTF-8")
                    + "&output=json&key=" + key + "&offset=5&extensions=base";
            if (city != null && !city.isBlank()) qs += "&city=" + URLEncoder.encode(city, "UTF-8");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    ExternalServices.amapPlaceSearchUrl(qs)).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            JsonNode pois = om.readTree(new String(c.getInputStream().readAllBytes(), "UTF-8")).path("pois");
            for (JsonNode poi : pois) {
                String loc = poi.path("location").asText("");
                if (!loc.contains(",")) continue;
                String[] parts = loc.split(",");
                return new String[]{ parts[0], parts[1], poi.path("id").asText(""), poi.path("adname").asText("") };
            }
        } catch (Exception e) { /* 查不到就算了 */ }
        return null;
    }

    /**
     * ★用户口径（2026-09-13）：点地名 → 高德搜索框里该填什么词。
     *   原来是把【整串行程目的地】当前缀（如「川西（成都—都江堰—…—海螺沟）」）✗ → 高德搜不到、城市还被认成大连 ✗。
     *   这里接上与个人地图同一套 llmResolveQueries：LLM 判断【标准检索词 + 真实所在城市】，
     *   查不到就让 LLM 用原词（前端照旧跳，高德自己兜底）；结果进程内缓存，避免每次点击都调 LLM。
     *   入参：{"places":[{"name":"中路藏寨"}], "dest":"川西"}
     *   出参：{"ok":true,"items":[{"in":"中路藏寨","query":"中路藏寨","city":"丹巴县"}]}
     */
    @PostMapping("/search-hints")
    public Map<String, Object> amapSearchHints(@RequestBody(required = false) byte[] body) {
        Map<String, Object> res = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String reqText = body == null ? "{}" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode root = om.readTree(reqText);
            String dest = root.path("dest").asText("").trim();
            //★用户口径：给 LLM 的上下文只能是【短城市】——原来把「川西（成都—都江堰—…—海螺沟）」整串给它 ✗，
            //  模型就把这串原样拼进检索词 → 高德搜不到、城市还被认成别的 ✗
            dest = shortDest(dest);
            //★用户口径（2026-09-13）：光凭景点名判断行政区不准 ✗ → 把【生成出来的行程大纲】当上下文一起给模型 ✓
            //  （例如行程里写着"乌鲁木齐→天山天池→精河→赛里木湖→霍城→那拉提→巴音布鲁克→库尔勒"，
            //    模型才能把「赛里木湖」判到博乐市、「那拉提草原」判到新源县 ✓）
            String outline = root.path("context").asText("").trim();
            if (outline.length() > 1200) outline = outline.substring(0, 1200);
            List<String> names = new ArrayList<>();
            for (JsonNode nd : root.path("places")) {
                String n = nd.path("name").asText("").trim();
                if (!n.isEmpty() && n.length() <= 30 && !names.contains(n)) names.add(n);
            }
            List<String> miss = new ArrayList<>();
            for (String n : names) if (!HINT_CACHE.containsKey(n)) miss.add(n);
            if (!miss.isEmpty()) {
                Map<String, String[]> got = llmResolveQueries(miss, dest, outline);
                for (String n : miss) {
                    String[] v = got.get(n);
                    HINT_CACHE.put(n, (v == null || v.length < 1 || v[0] == null || v[0].isEmpty())
                            ? new String[]{ n, "" } : v);
                }
                if (HINT_CACHE.size() > 1000) HINT_CACHE.clear();   // 简单封顶，防内存膨胀
            }
            for (String n : names) {
                String[] v = HINT_CACHE.get(n);
                Map<String, Object> one = new HashMap<>();
                one.put("in", n);
                //★清洗模型返回的检索词：去掉括号里的路线、去掉过长内容（防它把整串目的地拼进来 ✗）
                String q = (v == null || v[0] == null || v[0].isEmpty()) ? n : cleanQuery(v[0]);
                one.put("query", q.isEmpty() ? n : q);
                one.put("city", shortDest((v == null || v.length < 2 || v[1] == null) ? "" : v[1]));
                out.add(one);
            }
            res.put("ok", true);
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", "检索词整理失败：" + e.getMessage());
        }
        res.put("items", out);
        return res;
    }

    /** 地点 → [高德检索词, 真实城市] 的进程内缓存（重启即清） */
    private static final Map<String, String[]> HINT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** ★短城市：把「川西（成都—都江堰—…—海螺沟）」「大连五日」这类收敛成「川西」「大连」 */
    private static String shortDest(String c) {
        if (c == null) return "";
        String s = c.trim().replaceAll("[（(].*$", "").trim();
        s = s.replaceAll("\\d+\\s*日.*$", "").trim();
        s = s.split("[—\\-/、,，]")[0].trim();
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    /** ★清洗检索词：去掉括号里的路线、压掉多余空白、超长直接截断 */
    private static String cleanQuery(String q) {
        if (q == null) return "";
        String s = q.replaceAll("[（(][^）)]*[）)]", " ").replaceAll("\\s{2,}", " ").trim();
        s = s.replaceAll("^(行程目的地|目的地)[：: ]*", "").trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

}
