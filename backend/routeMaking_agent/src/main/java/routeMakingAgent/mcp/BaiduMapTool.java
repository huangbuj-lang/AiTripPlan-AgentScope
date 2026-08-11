package routeMakingAgent.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 百度地图工具（普通@Tool版，替代MCP客户端注册）
 * 原因：AgentScope 1.0.8的registerMcpClient在魔塔MCP v1.9.4下有转换bug（首轮请求报400超长）
 * 方案：10个@Tool方法内部直接调魔塔MCP的 tools/call，绕开AgentScope的MCP转换层
 */
public class BaiduMapTool {

    // 魔塔MCP服务地址（百度地图MCP）
    private static final String MCP_URL = "https://mcp.api-inference.modelscope.net/884c6f052e6546/mcp";

    private static volatile String sessionId = null; // 缓存的MCP会话ID（避免每次调用都重新握手）

    private static final HttpClient HTTP = HttpClient.newBuilder() // HTTP客户端（复用连接）
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 确保MCP会话有效（没有则初始化握手）
     */
    private static synchronized String ensureSession() throws Exception {
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
        ensureSession();
        StringBuilder params = new StringBuilder("{"); // 构造 tools/call 请求体
        int i = 0;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (i++ > 0) params.append(",");
            params.append("\"").append(e.getKey()).append("\":\"")
                    .append(String.valueOf(e.getValue()).replace("\"", "\\\"")).append("\"");
        }
        params.append("}");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"call-" + System.currentTimeMillis() + "\","
                + "\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":" + params + "}}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(MCP_URL))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        String resp = r.body();
        // 用JSON正确解析MCP响应（原字符串截取会被转义符截断成残缺内容，模型看不懂）
        // 响应格式: {"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"真实内容"}]}}
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp);
            com.fasterxml.jackson.databind.JsonNode content = root.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText("");
                if (text != null && !text.isBlank()) return text;
            }
        } catch (Exception ignored) { }
        return resp.length() > 2000 ? resp.substring(0, 2000) : resp; // 兜底：返回截断的原始响应
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
