package managerAgent.orchestrator;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★2026-09-11 从 TripOrchestrator（2406 行上帝类）抽出的「路线Agent 返回解析层」。
 *
 * <p>抽出的都是纯函数、无实例状态——此前它们混在编排/HTTP/prompt 拼接/业务规则里，
 * 改一处要通读整个 god-class，且无法单独测。本类只做“文本 → 结构化数据”的解析，
 * 不认识会话、不写事件、不发请求。
 *
 * <p><b>行为与原实现逐行一致</b>（只移动，不改逻辑）：parseRouteJson / toInt /
 * extractTotalDistance / extractWaypoints + RouteData 数据类。
 */
public final class RoutePlanParser {

    private RoutePlanParser() {}

    /** 路线Agent 结构化输出（原 TripOrchestrator.RouteData，随解析层一同搬来） */
    public static class RouteData {
        public int totalKm = -1;      // 往返闭环总里程（含返程）
        public int oneWayKm = -1;     // 单程去程里程
        public String waypoints = ""; // 途经城市（逗号分隔）
        public int routeCount = 0;    // 候选路线条数
    }

    /** 从路线Agent回复提取【路线JSON】块解析出 totalKm/waypoints/routeCount（失败返回 null，退回旧正则提取）。
     *  ★用 Map 接收（非 DTO），避免 Jackson 默认 FAIL_ON_UNKNOWN_PROPERTIES 因路线Agent多写字段而整体失败。 */
    public static RouteData parseRouteJson(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return null;
        String json = null;
        Matcher m = Pattern
                .compile("【路线JSON】\\s*(\\{[\\s\\S]*?\\})\\s*【路线JSON结束】").matcher(routeResult);
        if (m.find()) json = m.group(1);
        if (json == null) json = utils.JsonUtils.extractJsonObject(routeResult);
        if (json == null) return null;
        Object o = utils.JsonUtils.parse(json, Object.class);
        if (!(o instanceof java.util.Map)) return null;
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) o;
        RouteData rd = new RouteData();
        rd.totalKm = toInt(map.get("totalKm"));
        rd.oneWayKm = toInt(map.get("oneWayKm"));
        rd.waypoints = map.get("waypoints") == null ? "" : map.get("waypoints").toString().trim();
        rd.routeCount = toInt(map.get("routeCount"));
        return rd.totalKm > 0 ? rd : null;
    }

    /** 宽松取整：Number 直接转；字符串尝试解析；失败/为空返回 -1（表示“没取到”） */
    public static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o == null) return -1;
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** 从路线Agent返回提取候选路线总里程（优先“往返总里程”闭环全程 → 退回“总里程”/“去程里程”），失败返回 -1。
     *  ★只取第一条（不累加所有方案——多条候选路线累加会把总里程虚高几倍）。 */
    public static int extractTotalDistance(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return -1;
        // ① 优先结构化【路线JSON】块的 totalKm
        RouteData rd = parseRouteJson(routeResult);
        if (rd != null && rd.totalKm > 0) return rd.totalKm;
        // ② 退回标注行（"往返总里程/总里程/去程里程：约 NNNkm"），不做散落 km 累加
        String[] keys = {"往返总里程", "总里程", "去程里程"};
        for (String key : keys) {
            Matcher m = Pattern
                    .compile(key + "[^\\d]*(\\d+(?:\\.\\d+)?)\\s*km").matcher(routeResult);
            if (m.find()) {
                try { return (int) Math.round(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) { }
            }
        }
        return -1;
    }

    /** 从路线Agent返回提取途经城市（优先结构化 waypoints；退回"途经城市："行；再退回全文地名正则），最多 5 个 */
    public static String extractWaypoints(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return "";
        // ① 优先结构化【路线JSON】块的 waypoints（精确字段，不用正则猜）
        RouteData rd = parseRouteJson(routeResult);
        if (rd != null && !rd.waypoints.isBlank()) {
            return rd.waypoints;
        }
        Set<String> seen = new LinkedHashSet<>();
        // ① 优先：固定格式行（可多行=多条候选路线，合并去重）
        Matcher line = Pattern
                .compile("途经城市[：:]\\s*([^\\n\\r]{0,120})")
                .matcher(routeResult);
        while (line.find() && seen.size() < 5) {
            for (String tok : line.group(1).split("[,，、;；\\s]+")) {
                String t = tok.trim();
                if (t.isBlank()) continue;
                if (t.length() <= 6 && t.matches(".*(?:市|区|县|镇|州)$")) {
                    seen.add(t);
                    if (seen.size() >= 5) break;
                }
            }
        }
        // ② 兜底：无"途经城市："行时退回旧全文正则（老格式/自由文本路线）
        if (seen.isEmpty()) {
            Matcher m = Pattern
                    .compile("[\\u4e00-\\u9fa5]{2,6}?(?:市|区|县|镇|州)")
                    .matcher(routeResult);
            while (m.find() && seen.size() < 5) {
                seen.add(m.group());
            }
        }
        // 剔除起终点城市名本身
        seen.removeIf(n -> n.contains("起点") || n.contains("终点"));
        return String.join(",", seen);
    }
}
