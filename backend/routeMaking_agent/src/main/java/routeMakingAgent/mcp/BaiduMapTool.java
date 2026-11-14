package routeMakingAgent.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 百度地图工具：通过普通@Tool方法调用魔塔MCP的百度地图服务
 */
public class BaiduMapTool {

    // 魔塔MCP服务地址（百度地图MCP）：从环境变量注入，避免密钥写死进源码
    private static final String MCP_URL = System.getenv("BAIDU_MCP_URL");

    private static volatile String sessionId = null; // 缓存的MCP会话ID

    // 结果缓存：工具名+参数 → 结果，TTL 24小时（静态缓存，跨实例共享）
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24小时
    private static final int MAX_CACHE_ENTRIES = 500;              // 缓存容量上限（防内存无限增长）
    private static final Map<String, CacheEntry> MCP_CACHE = new ConcurrentHashMap<>();

    /** 缓存条目：结果值 + 写入时间戳 */
    private static class CacheEntry {
        final String value;
        final long time;
        CacheEntry(String value, long time) { this.value = value; this.time = time; }
    }

    // ★MCP调用计数器（观测用）：实际发往魔塔MCP的网络请求次数（缓存命中不算）
    public static final java.util.concurrent.atomic.AtomicLong MCP_CALLS = new java.util.concurrent.atomic.AtomicLong(0);

    private static final HttpClient HTTP = HttpClient.newBuilder() // HTTP客户端（复用连接）
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper(); // JSON解析器（复用，避免反复new）

    /** 写入缓存（带容量上限：超出时清理过期条目，仍超则放弃缓存） */
    private static void cachePut(String key, CacheEntry entry) {
        synchronized (MCP_CACHE) {
            if (MCP_CACHE.size() >= MAX_CACHE_ENTRIES) {
                long now = System.currentTimeMillis();
                MCP_CACHE.entrySet().removeIf(e -> now - e.getValue().time >= CACHE_TTL_MS); // 清过期
                if (MCP_CACHE.size() >= MAX_CACHE_ENTRIES) {
                    return; // 仍超限：本次不缓存，避免无限增长
                }
            }
            MCP_CACHE.put(key, entry);
        }
    }

    /**
     * 确保MCP会话有效（没有则初始化握手）
     */
    private static synchronized String ensureSession() throws Exception {
        if (MCP_URL == null || MCP_URL.isBlank()) {
            throw new IllegalStateException("百度地图MCP未配置（缺少环境变量 BAIDU_MCP_URL）");
        }
        if (sessionId != null) return sessionId;
        String init = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{" // initialize 握手
                + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"aitripplan\",\"version\":\"1.0\"}}}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(MCP_URL))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(init)).build(),
                HttpResponse.BodyHandlers.ofString());
        sessionId = r.headers().firstValue("Mcp-Session-Id").orElse(null); // 从响应头拿会话ID
        String notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"; // 发 initialized 通知（MCP协议要求）
        HTTP.send(HttpRequest.newBuilder(URI.create(MCP_URL))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(notif)).build(),
                HttpResponse.BodyHandlers.ofString());
        return sessionId;
    }

    /**
     * 调用魔塔MCP的 tools/call
     * @param toolName 工具名（如 map_directions）
     * @param args     参数Map
     * @return 工具返回的文本内容
     */
    private static String callMcp(String toolName, Map<String, Object> args) throws Exception {
        return callMcp(toolName, args, 0); // 首次调用
    }

    /**
     * @param retryCount 会话过期后的重试次数（最多1次）
     */
    private static String callMcp(String toolName, Map<String, Object> args, int retryCount) throws Exception {
        // 结果缓存：同一工具+同参数24小时内直接返回缓存结果
        String cacheKey = toolName + "|" + args; // 生成缓存键
        long now = System.currentTimeMillis();
        synchronized (MCP_CACHE) { // 加锁保证缓存读写并发安全
            CacheEntry hit = MCP_CACHE.get(cacheKey);
            if (hit != null && now - hit.time < CACHE_TTL_MS) {
                return hit.value; // 命中缓存直接返回
            }
        }
        ensureSession();
        MCP_CALLS.incrementAndGet(); // ★计数：实际发往MCP的网络请求（缓存未命中才到这）
        // 用 ObjectMapper 构造 tools/call 请求体（避免手写 JSON 拼接的转义漏洞）
        Map<String, Object> callParams = new HashMap<>();
        callParams.put("name", toolName);
        callParams.put("arguments", args);
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("jsonrpc", "2.0");
        bodyMap.put("id", "call-" + System.currentTimeMillis());
        bodyMap.put("method", "tools/call");
        bodyMap.put("params", callParams);
        String body = MAPPER.writeValueAsString(bodyMap);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(MCP_URL))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        String resp = r.body();
        // 会话过期自愈：检测到会话过期则重置会话并重试一次
        if (resp != null && containsSessionExpired(resp)) {
            synchronized (BaiduMapTool.class) {
                if (sessionId != null) {
                    sessionId = null; // 强制重新握手
                }
            }
            if (retryCount < 1) {
                return callMcp(toolName, args, retryCount + 1); // 重试一次（新会话）
            }
            // 重试后仍过期：放弃自愈，返回原文（上层兜底）
            return resp;
        }
        // 解析MCP响应JSON，提取其中的文本内容
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(resp);
            com.fasterxml.jackson.databind.JsonNode content = root.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText("");
                if (text != null && !text.isBlank()) {
                    // 截断大响应：仅保留前3000字符并标记已截断
                    String result;
                    if (text.length() > 3000) {
                        result = text.substring(0, 3000) + "\n...[响应过长已截断，后续省略]";
                    } else {
                        result = text;
                    }
                    // 写回缓存
                    cachePut(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
                    return result;
                }
            }
        } catch (Exception ignored) { }
        String fallback = resp.length() > 2000 ? resp.substring(0, 2000) : resp; // 兜底：返回截断的原始响应
        // 兜底响应也写入缓存
        cachePut(cacheKey, new CacheEntry(fallback, System.currentTimeMillis()));
        return fallback;
    }

    /**
     * 检测MCP响应是否包含会话过期标志
     */
    private static boolean containsSessionExpired(String resp) {
        return resp.contains("会话超时") || resp.contains("会话已过期")
                || resp.contains("session expired") || resp.contains("session timeout")
                || resp.contains("session_id") && resp.contains("expired")
                || resp.contains("当前会话已过期") || resp.contains("会话无效")
                || resp.contains("Session expired") || resp.contains("invalid session");
    }

    // ============ 以下为10个百度地图工具（@Tool普通方法） ============

    /** 地理编码：地名 → 经纬度 */
    @Tool(name = "map_geocode", description = "地理编码：将地名（如西安）转换为经纬度坐标")
    public String mapGeocode(
            @ToolParam(name = "address", description = "需要查询的地名，例如：西安") String address) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("address", address);
            return callMcp("map_geocode", args);
        } catch (Exception e) { return "地理编码失败：" + e.getMessage(); }
    }

    /** 逆地理编码：经纬度 → 地名 */
    @Tool(name = "map_reverse_geocode", description = "逆地理编码：将经纬度坐标转换为地名")
    public String mapReverseGeocode(
            @ToolParam(name = "latitude", description = "纬度，例如：34.3416") String latitude,
            @ToolParam(name = "longitude", description = "经度，例如：108.9398") String longitude) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("latitude", latitude);
            args.put("longitude", longitude);
            return callMcp("map_reverse_geocode", args);
        } catch (Exception e) { return "逆地理编码失败：" + e.getMessage(); }
    }

    /** 地点检索：搜索POI */
    @Tool(name = "map_search_places", description = "地点检索：搜索指定区域的地点（POI），如餐厅、景点、加油站")
    public String mapSearchPlaces(
            @ToolParam(name = "query", description = "搜索关键词，例如：西安的景点") String query,
            @ToolParam(name = "region", description = "限定区域（可选），例如：西安") String region) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("query", query);
            if (region != null && !region.isBlank()) args.put("region", region);
            return callMcp("map_search_places", args);
        } catch (Exception e) { return "地点检索失败：" + e.getMessage(); }
    }

    /** 地点详情 */
    @Tool(name = "map_place_details", description = "地点详情：根据POI唯一标识获取地点详细信息")
    public String mapPlaceDetails(
            @ToolParam(name = "uid", description = "地点的唯一标识（POI的uid）") String uid) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("uid", uid);
            return callMcp("map_place_details", args);
        } catch (Exception e) { return "地点详情查询失败：" + e.getMessage(); }
    }

    /** 批量路线矩阵 */
    @Tool(name = "map_directions_matrix", description = "路线矩阵：批量计算多个起点到多个终点的距离和时间")
    public String mapDirectionsMatrix(
            @ToolParam(name = "origins", description = "起点列表，多个起点用竖线|分隔，例如：广州|深圳") String origins,
            @ToolParam(name = "destinations", description = "终点列表，多个终点用竖线|分隔，例如：珠海|惠州") String destinations) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("origins", origins);
            args.put("destinations", destinations);
            return callMcp("map_directions_matrix", args);
        } catch (Exception e) { return "路线矩阵计算失败：" + e.getMessage(); }
    }

    /** 自驾路线规划（核心工具） */
    @Tool(name = "map_directions", description = "自驾路线规划：计算两个地点之间的驾车路线，返回距离、时间、过路费和途经道路")
    public String mapDirections(
            @ToolParam(name = "origin", description = "起点地名，例如：广州市中心") String origin,
            @ToolParam(name = "destination", description = "终点地名，例如：珠海") String destination) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("origin", origin);
            args.put("destination", destination);
            return callMcp("map_directions", args);
        } catch (Exception e) { return "自驾路线规划失败：" + e.getMessage(); }
    }

    /** 天气查询 */
    @Tool(name = "map_weather", description = "天气查询：查询指定城市的天气预报")
    public String mapWeather(
            @ToolParam(name = "location", description = "城市名，例如：北京") String location) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("location", location);
            return callMcp("map_weather", args);
        } catch (Exception e) { return "天气查询失败：" + e.getMessage(); }
    }

    /** IP定位 */
    @Tool(name = "map_ip_location", description = "IP定位：根据IP地址获取地理位置")
    public String mapIpLocation(
            @ToolParam(name = "ip", description = "IP地址（可选）") String ip) {
        try {
            Map<String, Object> args = new HashMap<>();
            if (ip != null && !ip.isBlank()) args.put("ip", ip);
            return callMcp("map_ip_location", args);
        } catch (Exception e) { return "IP定位失败：" + e.getMessage(); }
    }

    /** 实时路况 */
    @Tool(name = "map_road_traffic", description = "实时路况：查询指定道路的实时交通状况")
    public String mapRoadTraffic(
            @ToolParam(name = "road_name", description = "道路名称，例如：京藏高速") String roadName,
            @ToolParam(name = "city", description = "城市名（可选）") String city) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("model", "driving");
            if (roadName != null && !roadName.isBlank()) args.put("road_name", roadName);
            if (city != null && !city.isBlank()) args.put("city", city);
            return callMcp("map_road_traffic", args);
        } catch (Exception e) { return "实时路况查询失败：" + e.getMessage(); }
    }

    /** POI提取 */
    @Tool(name = "map_poi_extract", description = "POI提取：从一段文本中提取地点信息")
    public String mapPoiExtract(
            @ToolParam(name = "text_content", description = "需要提取地点的文本内容") String textContent) {
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("text_content", textContent);
            return callMcp("map_poi_extract", args);
        } catch (Exception e) { return "POI提取失败：" + e.getMessage(); }
    }
}
