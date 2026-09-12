package managerAgent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utils.ExternalServices;
import utils.LlmClient;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 价格预估（独立接口，加法式 —— 核心链路一行不碰）
 *
 * 用户口径：
 *  ① 知识库（pricing_output，城市级基准价）有数据 → 以知识库为准；
 *  ② 没有 → 外部 LLM 兜底：LLM 先决定要取哪些信息 → 后端执行高德调用 → 结果交回 LLM 整理；
 *  ③ 展示分三部分：单次人均 / 每天人均 / 全程人均；
 *  ④ 查不到就写"暂无"，绝不编造数字。
 *
 * 入参（行程生成完之后由前端调）：
 *   {
 *     "destination": "新疆伊犁河谷—巴音布鲁克",
 *     "days": [ { "date": "2026-09-12", "city": "精河县",
 *                 "items": [ {"name":"金美大酒店","city":"精河县","category":"住宿"}, ... ] } ]
 *   }
 */
@Slf4j
@RestController
@RequestMapping("/api/trip-price")
public class TripPriceController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    /** 用户口径：不要外部 LLM 预估，只用知识库（pricing_output）。置 true 可恢复兜底。 */
    private static final boolean USE_LLM_FALLBACK = true;

    /** 控制成本：最多分析的城市数 / 每天最多取的条数 */
    private static final int MAX_CITIES = 8;
    private static final int MAX_ITEMS_PER_DAY = 8;

    @PostMapping
    public Map<String, Object> price(@RequestBody byte[] rawBody) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> diag = new ArrayList<>();
        try {
            //★显式 UTF-8：@RequestBody String 在无 charset 时会按 ISO-8859-1 解码，中文城市名会乱码
            String body = new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8);
            JsonNode req = om.readTree(body);
            JsonNode daysNode = req.path("days");
            String destination = req.path("destination").asText("");

            //—— 1) 汇总城市（去重、封顶）——
            List<String> cities = new ArrayList<>();
            for (JsonNode d : daysNode) {
                String c = d.path("city").asText("");
                if (!c.isBlank() && !cities.contains(c)) cities.add(c);
                for (JsonNode it : d.path("items")) {
                    String c2 = it.path("city").asText("");
                    if (!c2.isBlank() && !cities.contains(c2)) cities.add(c2);
                }
                if (cities.size() >= MAX_CITIES) break;
            }
            if (cities.size() > MAX_CITIES) cities = cities.subList(0, MAX_CITIES);

            //—— 2) 知识库优先：pricing_output（城市级基准价）——
            Map<String, Map<String, Object>> kb = new LinkedHashMap<>();
            for (String c : cities) {
                Map<String, Object> row = readPricing(c);
                if (row != null) kb.put(c, row);
            }
            diag.add("知识库命中城市 " + kb.size() + "/" + cities.size());

            //—— 3) 组装每天的明细（知识库优先；缺的条目挂到 LLM 兜底清单）——
            List<Map<String, Object>> dayOut = new ArrayList<>();
            List<Map<String, String>> askLlm = new ArrayList<>();   // 需要外部 LLM 兜底的条目
            for (JsonNode d : daysNode) {
                String date = d.path("date").asText("");
                String city = d.path("city").asText("");
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("date", date);
                day.put("city", city);
                List<Map<String, Object>> items = new ArrayList<>();
                int cnt = 0;
                for (JsonNode it : d.path("items")) {
                    if (cnt++ >= MAX_ITEMS_PER_DAY) break;
                    String name = it.path("name").asText("");
                    String c2 = it.path("city").asText(city);
                    String cat = it.path("category").asText("");
                    if (name.isBlank()) continue;
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("name", name);
                    one.put("category", cat);
                    Double per = null;
                    String src = "";
                    Map<String, Object> kv = kb.get(c2);
                    if (kv == null) kv = kb.get(city);
                    if (kv != null) {
                        Double v = pickKb(kv, cat);
                        if (v != null) { per = v; src = "知识库"; }
                    }
                    if (per == null) {
                        src = "待预估";
                        Map<String, String> q = new LinkedHashMap<>();
                        q.put("name", name);
                        q.put("city", c2);
                        q.put("category", cat);
                        q.put("date", date);
                        if (!q.get("name").isBlank()) askLlm.add(q);
                    }
                    one.put("perPerson", per == null ? "" : String.format("%.0f", per));
                    one.put("source", src);
                    items.add(one);
                }
                day.put("items", items);
                dayOut.add(day);
            }

            //—— 4) 外部 LLM 兜底（LLM 决定要取哪些信息 → 后端执行高德 → 交回 LLM 整理）——
            String llmSource = "未启用";
            if (USE_LLM_FALLBACK && !askLlm.isEmpty()) {
                Map<String, Map<String, String>> amapFacts = new LinkedHashMap<>();
                for (Map<String, String> q : askLlm) {
                    String nm = q.get("name");
                    if (amapFacts.containsKey(nm)) continue;
                    amapFacts.put(nm, amapPoi(nameOf(nm), q.get("city")));
                    try { Thread.sleep(700); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                diag.add("高德取到 " + amapFacts.size() + " 条原始信息");
                Map<String, Double> llmPrices = askLlmPrices(askLlm, amapFacts, destination, diag);
                //回填
                for (Map<String, Object> day : dayOut) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> its = (List<Map<String, Object>>) day.get("items");
                    for (Map<String, Object> one : its) {
                        if (!"待预估".equals(one.get("source"))) continue;
                        Double v = llmPrices.get(String.valueOf(one.get("name")));
                        if (v != null) { one.put("perPerson", String.format("%.0f", v)); one.put("source", "估算"); }
                        else { one.put("perPerson", ""); one.put("source", "暂无"); }
                    }
                }
                llmSource = "估算";
            }

            //—— 5) 汇总三部分：单次人均 / 每天人均 / 全程人均 ——
            double totalAll = 0;
            int dayCount = 0;
            for (Map<String, Object> day : dayOut) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> its = (List<Map<String, Object>>) day.get("items");
                double sum = 0;
                int n = 0;
                for (Map<String, Object> one : its) {
                    String s = String.valueOf(one.get("perPerson"));
                    if (s != null && !s.isBlank() && !"null".equals(s)) {
                        try { sum += Double.parseDouble(s); n++; } catch (Exception ignore) { }
                    }
                }
                day.put("perDayPerPerson", n > 0 ? String.format("%.0f", sum) : "");
                day.put("counted", n);
                if (n > 0) { totalAll += sum; dayCount++; }
            }
            //—— 6) 三列（美食/酒店/景区）× 三行（单次/每天/全程 人均）——全部只用知识库 ——
            //  单次人均：各城市基准价的平均（知识库）
            //  每天人均：单次人均 × 该类别平均每天条数（从行程里数）
            //  全程人均：每天人均 × 天数
            //每天的条数 + 每类的"单次人均"（KB 命中的用 KB 价，KB 没覆盖的用兜底价）——都来自逐条明细
            int[] perDayCnt = new int[3];
            double[] sumPer = new double[3];
            int[] cntPer = new int[3];
            int dayN = 0;
            for (JsonNode d : daysNode) {
                dayN++;
                for (JsonNode it : d.path("items")) {
                    String cat = it.path("category").asText("");
                    int idx = cat.contains("餐") ? 0 : (cat.contains("住宿") || cat.contains("酒店") ? 1 : (cat.contains("景点") || cat.contains("景区") ? 2 : -1));
                    if (idx < 0) continue;
                    perDayCnt[idx]++;
                }
            }
            //从已经算好的 dayOut 明细里取每条的人均（KB 或兜底）
            for (Map<String, Object> day : dayOut) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> its = (List<Map<String, Object>>) day.get("items");
                for (Map<String, Object> one : its) {
                    String cat = String.valueOf(one.get("category"));
                    int idx = cat.contains("餐") ? 0 : (cat.contains("住宿") || cat.contains("酒店") ? 1 : (cat.contains("景点") || cat.contains("景区") ? 2 : -1));
                    if (idx < 0) continue;
                    String v = String.valueOf(one.get("perPerson"));
                    if (v == null || v.isBlank() || "null".equals(v)) continue;
                    try { sumPer[idx] += Double.parseDouble(v); cntPer[idx]++; } catch (Exception ignore) { }
                }
            }
            String[] cols = {"美食", "酒店", "景区"};
            Map<String, String> singleRow = new LinkedHashMap<>();
            Map<String, String> perDayRow = new LinkedHashMap<>();
            Map<String, String> totalRow = new LinkedHashMap<>();
            double sumPerDay = 0;
            for (int i = 0; i < 3; i++) {
                if (cntPer[i] == 0) { singleRow.put(cols[i], ""); perDayRow.put(cols[i], ""); totalRow.put(cols[i], ""); continue; }
                double single = sumPer[i] / cntPer[i];
                double perDayAvg = perDayCnt[i] / (double) Math.max(1, dayN);
                double perDay = single * perDayAvg;
                double all = perDay * dayN;
                singleRow.put(cols[i], String.format("%.0f", single));
                perDayRow.put(cols[i], String.format("%.0f", perDay));
                totalRow.put(cols[i], String.format("%.0f", all));
                sumPerDay += perDay;
            }
            Map<String, Object> matrix = new LinkedHashMap<>();
            matrix.put("columns", java.util.Arrays.asList(cols));
            matrix.put("single", singleRow);
            matrix.put("perDay", perDayRow);
            matrix.put("total", totalRow);
            matrix.put("perDaySum", dayN > 0 ? String.format("%.0f", sumPerDay) : "");
            matrix.put("totalSum", dayN > 0 ? String.format("%.0f", sumPerDay * dayN) : "");
            matrix.put("days", dayN);
            out.put("matrix", matrix);

            Map<String, Object> total = new LinkedHashMap<>();
            total.put("perPerson", dayCount > 0 ? String.format("%.0f", totalAll) : "");
            total.put("days", dayOut.size());
            total.put("countedDays", dayCount);

            out.put("success", true);
            out.put("destination", destination);
            out.put("source", kb.isEmpty() ? llmSource : ("知识库" + (askLlm.isEmpty() ? "" : "+估算")));
            out.put("kbCities", new ArrayList<>(kb.keySet()));
            out.put("days", dayOut);
            out.put("total", total);
            out.put("diag", diag);
        } catch (Throwable t) {
            log.warn("trip-price 失败", t);
            out.put("success", false);
            out.put("message", "价格预估暂时不可用：" + t.getClass().getSimpleName());
            out.put("diag", diag);
        }
        return out;
    }

    /** 知识库：pricing_output（城市级）——与 /api/insights 同一张表 */
    private Map<String, Object> readPricing(String city) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT hotel_base, hotel_price, food_base, food_price, ticket_base, ticket_price "
                            + "FROM pricing_output WHERE city = ? LIMIT 1", city);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按类别从知识库行里取"单次人均"：住宿=每晚；餐饮=每餐；景点=每张票 */
    private Double pickKb(Map<String, Object> row, String category) {
        try {
            String cat = category == null ? "" : category;
            String col = null, baseCol = null;
            if (cat.contains("住宿") || cat.contains("酒店")) { col = "hotel_price"; baseCol = "hotel_base"; }
            else if (cat.contains("餐")) { col = "food_price"; baseCol = "food_base"; }
            else if (cat.contains("景点") || cat.contains("景区")) { col = "ticket_price"; baseCol = "ticket_price"; }
            else return null;
            Object v = row.get(col);
            if (v == null && baseCol != null) v = row.get(baseCol);
            if (v == null) return null;
            return Double.parseDouble(String.valueOf(v));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 兜底估算（用户口径 ②）：
     *   第 1 步 LLM 先看一遍行程，指出还要补哪些信息（这里已经在 Java 侧准备好条目清单）；
     *   第 2 步后端用高德取原始事实（tel/address/rating/cost）；
     *   第 3 步把"行程 + 高德事实"交回 LLM，整理成每条的单次人均（JSON）。
     * 任何一步失败都只影响"暂无"，不会抛错。
     */
    private Map<String, Double> askLlmPrices(List<Map<String, String>> items,
                                             Map<String, Map<String, String>> facts,
                                             String destination, List<String> diag) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("目的地：").append(destination).append("\n");
            sb.append("以下是行程里的地点，以及从高德查到的原始信息（高德对酒店/景区通常没有价格）：\n");
            for (Map<String, String> it : items) {
                String nm = it.get("name");
                Map<String, String> f = facts.get(nm);
                sb.append("- 名称：").append(nm)
                        .append(" | 类别：").append(it.get("category"))
                        .append(" | 城市：").append(it.get("city"));
                if (f != null) {
                    sb.append(" | 高德：").append(f.getOrDefault("type", ""))
                            .append(" 评分=").append(blankTo(f.get("rating"), "无"))
                            .append(" 人均=").append(blankTo(f.get("cost"), "无"));
                }
                sb.append("\n");
            }
            String sys = "你是旅行花费估算助手。请根据地点名称、类别、所在城市和常识，"
                    + "估算每个地点的【单次人均花费】（元，整数）：住宿=每晚一间双人房人均、餐饮=每人一餐、景点=每人一张门票。"
                    + "只输出 JSON，格式：{\"items\":[{\"name\":\"原样名称\",\"perPerson\":数字或null}],\"note\":\"一句话说明\"}。"
                    + "拿不准就填 null，禁止编造精确价格。";
            String rsp = LlmClient.chatJson(sys, sb.toString());
            JsonNode arr = om.readTree(rsp).path("items");
            for (JsonNode n : arr) {
                String nm = n.path("name").asText("");
                JsonNode p = n.path("perPerson");
                if (!nm.isBlank() && p != null && !p.isNull() && p.isNumber()) {
                    double v = p.asDouble();
                    if (v > 0 && v < 200000) out.put(nm, v);
                }
            }
            diag.add("兜底估算给出 " + out.size() + " 条估值");
        } catch (Throwable t) {
            diag.add("兜底估算失败: " + t.getClass().getSimpleName());
        }
        return out;
    }

    /** 高德 POI 原始事实（城市级），复用与 /api/trip-extras 完全相同的口径 */
    private Map<String, String> amapPoi(String name, String city) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            String key = ExternalServices.amapKey();
            if (key == null || key.isBlank() || name == null || name.isBlank()) return m;
            String qs = "keywords=" + URLEncoder.encode(name, "UTF-8")
                    + "&output=json&key=" + key + "&offset=5&extensions=all";
            if (city != null && !city.isBlank()) qs += "&city=" + URLEncoder.encode(city, "UTF-8");
            String b = httpGet(ExternalServices.amapPlaceSearchUrl(qs));
            JsonNode pois = om.readTree(b).path("pois");
            if (!pois.isArray() || pois.isEmpty()) return m;
            JsonNode p = pois.get(0);
            m.put("matched", p.path("name").asText(""));
            m.put("type", p.path("type").asText(""));
            m.put("tel", p.path("tel").asText(""));
            m.put("address", p.path("address").asText(""));
            m.put("rating", p.path("biz_ext").path("rating").asText(""));
            m.put("cost", p.path("biz_ext").path("cost").asText(""));
        } catch (Throwable ignore) {
        }
        return m;
    }

    /** 从"店名 · 分店"里取主名去查（与前端一致：只取第一段） */
    private String nameOf(String full) {
        if (full == null) return "";
        String s = full.split("[·（(——]")[0].trim();
        return s.isBlank() ? full.trim() : s;
    }

    private String blankTo(String s, String dft) {
        return (s == null || s.isBlank()) ? dft : s;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestMethod("GET");
        try (Scanner sc = new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A")) {
            return sc.hasNext() ? sc.next() : "";
        } finally {
            c.disconnect();
        }
    }
}
