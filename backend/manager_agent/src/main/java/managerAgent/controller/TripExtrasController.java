package managerAgent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import utils.ExternalServices;

import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行程附加信息（区域天气 / 地点价格）——【不进入核心流程】。
 *
 * <p>用户口径（2026-09-12 定）：
 * <ul>
 *   <li>行程照常生成；<b>行程生成完整之后</b>由前端拿【已生成的行程】异步调用本接口取数，填进对应卡片；</li>
 *   <li><b>不重复获取</b>：同一份行程的城市/地点结果走<b>进程内缓存</b>（天气 3 小时、价格 24 小时）；</li>
 *   <li>取不到就返回空（前端显示"暂无"），<b>绝不编造</b>；本接口任何异常都只影响卡片，不影响行程交付。</li>
 * </ul>
 *
 * <p>取数方式：直接用高德 REST（天气 = 城市级预报，今天起 4 天；价格 = POI 详情的 biz_ext 人均/门票），
 * 受高德 QPS 3 限制，逐个查询之间 sleep 350ms，并封顶城市/地点数量防止配额被打爆。
 */
@RestController
@RequestMapping("/api/trip-extras")
public class TripExtrasController {

    private final ObjectMapper om = new ObjectMapper();

    /** 天气缓存：城市 -> [时间戳, 数据]（天气变化快，TTL 3 小时） */
    private static final Map<String, Object[]> WEATHER_CACHE = new ConcurrentHashMap<>();
    /** 价格缓存：地点名|城市 -> [时间戳, 数据]（TTL 24 小时） */
    private static final Map<String, Object[]> PRICE_CACHE = new ConcurrentHashMap<>();
    private static final long WEATHER_TTL_MS = 3L * 3600 * 1000;
    private static final long PRICE_TTL_MS = 24L * 3600 * 1000;

    /** 单次请求封顶（防高德配额被打爆 / 接口被拖长） */
    private static final int MAX_CITIES = 12;
    private static final int MAX_PLACES = 48;

    @PostMapping
    public Map<String, Object> extras(@RequestBody(required = false) byte[] rawBody) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", false);
        List<String> diag = new ArrayList<>(); // 取空时回显原始响应片段（仅诊断用）
        try {
            String key = ExternalServices.amapKey();
            if (key == null || key.isBlank()) {
                resp.put("error", "高德Key未配置");
                return resp;
            }
            //★必须显式按 UTF-8 解码：@RequestBody String 在缺 charset 时会被 Spring 按 ISO-8859-1 处理，
            //  中文城市名/地点名会变乱码 → 高德查不到任何结果（已实测踩坑）。故改用 byte[] + UTF-8。
            String body = (rawBody == null || rawBody.length == 0)
                    ? "{}" : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode root = om.readTree(body);

            //① 城市列表（去重 + 封顶）
            List<String> cities = new ArrayList<>();
            for (JsonNode c : root.path("cities")) {
                String s = c.asText("").trim();
                if (!s.isEmpty() && !cities.contains(s)) cities.add(s);
                if (cities.size() >= MAX_CITIES) break;
            }

            //② 关键地点（酒店/餐厅/景区；取不到价格的会返回空，不影响其他）
            List<Map<String, String>> places = new ArrayList<>();
            for (JsonNode p : root.path("places")) {
                String nm = p.path("name").asText("").trim();
                if (nm.isEmpty()) continue;
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", nm);
                m.put("city", p.path("city").asText("").trim());
                m.put("category", p.path("category").asText("").trim());
                places.add(m);
                if (places.size() >= MAX_PLACES) break;
            }

            //③ 区域天气（城市级 · 今天起 4 天，含用户要的"未来三天"）
            List<Map<String, Object>> weather = new ArrayList<>();
            for (String city : cities) {
                Map<String, Object> w = weatherOf(key, city, diag);
                if (w == null) {
                    //高德 QPS 3：间隔过密会被限流（CUQPS_HAS_EXCEEDED_THE_LIMIT）→ 放慢后重试一次
                    sleepQuiet(900);
                    w = weatherOf(key, city, diag);
                }
                if (w != null) weather.add(w);
                sleepQuiet(700);
            }

            //④ 地点价格（高德参考价：酒店/餐厅人均、景区门票；缺失即 null）
            List<Map<String, Object>> prices = new ArrayList<>();
            for (Map<String, String> p : places) {
                Map<String, Object> pr = priceOf(key, p.get("name"), p.get("city"), p.get("category"), diag);
                if (pr != null) prices.add(pr);
                sleepQuiet(700);
            }

            resp.put("success", true);
            resp.put("weather", weather);
            resp.put("prices", prices);
            resp.put("note", "天气为高德城市级预报（今天起 4 天）；价格为高德参考价/人均，可能缺失");
            //诊断用：两条链路都取空时，回显原始响应片段，便于定位（Key 无效 / 限流 / 参数错）
            if (weather.isEmpty() && prices.isEmpty()) {
                resp.put("debug", diag);
            }
            return resp;
        } catch (Throwable t) {
            //绝不抛出去影响前端：取数失败只是卡片显示"暂无"
            resp.put("error", "取数失败：" + t);
            return resp;
        }
    }

    /** 城市级天气预报（今天起 4 天）；失败返回 null */
    private Map<String, Object> weatherOf(String key, String city, List<String> diag) {
        Object[] cached = WEATHER_CACHE.get(city);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < WEATHER_TTL_MS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) cached[1];
            return hit;
        }
        try {
            String qs = "key=" + key + "&city=" + URLEncoder.encode(city, "UTF-8") + "&extensions=all";
            String b = httpGet("https://restapi.amap.com/v3/weather/weatherInfo?" + qs);
            JsonNode forecasts = om.readTree(b).path("forecasts");
            if (!forecasts.isArray() || forecasts.isEmpty()) {
                diag.add("weather[" + city + "] 空响应: " + clip(b));
                return null;
            }
            JsonNode f = forecasts.get(0);
            List<Map<String, String>> days = new ArrayList<>();
            for (JsonNode cast : f.path("casts")) {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("date", cast.path("date").asText(""));
                d.put("week", cast.path("week").asText(""));
                d.put("dayWeather", cast.path("dayweather").asText(""));
                d.put("nightWeather", cast.path("nightweather").asText(""));
                d.put("dayTemp", cast.path("daytemp").asText(""));
                d.put("nightTemp", cast.path("nighttemp").asText(""));
                days.add(d);
            }
            if (days.isEmpty()) {
                diag.add("weather[" + city + "] 无 casts: " + clip(b));
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("city", f.path("city").asText(city));
            out.put("province", f.path("province").asText(""));
            out.put("reportTime", f.path("reporttime").asText(""));
            out.put("days", days);
            WEATHER_CACHE.put(city, new Object[]{System.currentTimeMillis(), out});
            return out;
        } catch (Throwable t) {
            diag.add("weather[" + city + "] 异常: " + t);
            return null;
        }
    }

    /** 地点价格/评分（POI 详情 biz_ext）；完全取不到就返回 null（前端显示"暂无"） */
    private Map<String, Object> priceOf(String key, String name, String city, String category, List<String> diag) {
        String ck = name + "|" + city;
        Object[] cached = PRICE_CACHE.get(ck);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < PRICE_TTL_MS) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) cached[1];
            return hit;
        }
        try {
            //★不要把城市拼进关键词：city 已经用参数传了，前缀反而让高德搜不到
            //  （实测 "博乐市 湖锦牧场牛肉火锅" / "博乐市 美丽豪酒店" 都返回 0 条，去掉前缀就命中）
            String kw = name;
            String qs = "keywords=" + URLEncoder.encode(kw, "UTF-8")
                    + "&output=json&key=" + key + "&offset=5&extensions=all";
            if (city != null && !city.isBlank()) qs += "&city=" + URLEncoder.encode(city, "UTF-8");
            String b = httpGet(ExternalServices.amapPlaceSearchUrl(qs));
            JsonNode pois = om.readTree(b).path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                diag.add("price[" + name + "] 无 POI: " + clip(b));
                return null;
            }
            //★候选挑选顺序（实测踩坑后加的）：
            //  ① 名称完全一致的优先 —— 否则会抓到"XX酒店南侧270米"这类衍生条目（评分/地址都不对）
            //  ② 其次按类别挑（餐饮/住宿/景点），避免"阿凡提"这种通用名命中同名民宿
            //  ③ 都没有才退回第一条（保持原行为，不会拿不到数据）
            String wantName = name.replaceAll("\\s+", "");
            JsonNode poi = null, exactEmpty = null;
            for (JsonNode cand : pois) {
                if (!cand.path("name").asText("").replaceAll("\\s+", "").equals(wantName)) continue;
                //★名称全等但**什么数据都没有**（评分/人均/最低价/电话全空）时先记下，继续往下挑，
                //  免得卡片只剩"暂无"（实测"美丽豪酒店"空壳条 vs "美丽豪酒店(博乐店)"有 4.7 分）
                if (hasInfo(cand)) { poi = cand; break; }
                if (exactEmpty == null) exactEmpty = cand;
            }
            if (poi == null) {
                for (JsonNode cand : pois) {
                    if (matchType(category, cand.path("type").asText(""))) { poi = cand; break; }
                }
            }
            if (poi == null) poi = (exactEmpty != null) ? exactEmpty : pois.get(0);
            JsonNode biz = poi.path("biz_ext");
            //高德 biz_ext 的 cost 可能是空数组 []（实测酒店/景区常为空），此时退回 lowest_price（酒店最低价）
            String cost = biz.isArray() ? "" : biz.path("cost").asText("");
            if (cost.isBlank() || "[]".equals(cost.trim())) {
                String low = biz.isArray() ? "" : biz.path("lowest_price").asText("");
                cost = ("[]".equals(low.trim()) ? "" : low).trim();
            }
            String rating = biz.isArray() ? "" : biz.path("rating").asText("");
            if ("[]".equals(rating.trim())) rating = "";
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("city", city == null ? "" : city);
            out.put("category", category == null ? "" : category);
            out.put("matched", poi.path("name").asText(""));
            out.put("cost", cost);
            out.put("rating", rating);
            out.put("tel", poi.path("tel").asText(""));
            out.put("address", poi.path("address").asText(""));
            //★只要高德能命中这个地点就返回 —— 卡片还要展示【电话/地址】。
            //  酒店、景区通常既没有人均价也没有评分（cost/rating 都为空），
            //  原来按"cost 与 rating 都空"判空 → 整条被丢掉 → 前端只能显示"暂无"。
            //  改成四个字段全空才算"没有可用信息"。
            if (cost.isBlank() && rating.isBlank()
                    && poi.path("tel").asText("").isBlank()
                    && poi.path("address").asText("").isBlank()) {
                diag.add("price[" + name + "] 高德字段全空，命中=" + poi.path("name").asText(""));
                return null;
            }
            PRICE_CACHE.put(ck, new Object[]{System.currentTimeMillis(), out});
            return out;
        } catch (Throwable t) {
            diag.add("price[" + name + "] 异常: " + t);
            return null;
        }
    }

    /** 诊断用：截断原始响应片段 */
    /**
     * 这条 POI 有没有可用信息（评分 / 人均 / 最低价 / 电话 只要有一个就算有）。
     * 用途：名称全等的候选如果是个空壳，宁可继续挑带数据的同名条目，也别让卡片全是"暂无"。
     */
    private boolean hasInfo(JsonNode poi) {
        if (poi == null) return false;
        JsonNode biz = poi.path("biz_ext");
        if (!biz.isArray()) {
            if (!biz.path("cost").asText("").isBlank()) return true;
            if (!biz.path("lowest_price").asText("").isBlank()) return true;
            if (!biz.path("rating").asText("").isBlank()) return true;
        }
        return !poi.path("tel").asText("").isBlank();
    }

    /**
     * 粗略判断高德 POI 类型是否符合我们期望的类别（餐饮/住宿/景点）。
     * 用途：在候选里优先挑类型对得上的，避免"阿凡提"这种通用名被解析成同名民宿。
     * 判不出来就返回 true（不误杀）。
     */
    private boolean matchType(String want, String amapType) {
        if (want == null || want.isBlank()) return true;
        String t = amapType == null ? "" : amapType;
        if (t.isBlank()) return true;
        if (want.contains("餐") || want.contains("美食")) {
            return t.contains("餐") || t.contains("火锅") || t.contains("小吃") || t.contains("快餐")
                    || t.contains("咖啡") || t.contains("茶") || t.contains("酒吧") || t.contains("冷饮");
        }
        if (want.contains("住宿") || want.contains("酒店") || want.contains("宾馆")) {
            return t.contains("宾馆") || t.contains("酒店") || t.contains("住宿") || t.contains("民宿");
        }
        if (want.contains("景点") || want.contains("景区")) {
            return t.contains("风景") || t.contains("景点") || t.contains("公园") || t.contains("游乐")
                    || t.contains("博物馆") || t.contains("寺") || t.contains("湖") || t.contains("山")
                    || t.contains("纪念馆") || t.contains("展览");
        }
        return true;
    }

    private String clip(String s) {
        if (s == null) return "null";
        String t = s.replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        return new String(c.getInputStream().readAllBytes(), "UTF-8");
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
