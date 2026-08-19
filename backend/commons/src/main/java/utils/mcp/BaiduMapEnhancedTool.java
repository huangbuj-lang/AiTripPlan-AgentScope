package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.Map;

/**
 * ★百度地图增强工具（手写转换层）：把"模型会说人话的地名"转换成 MCP 真正要的坐标格式。
 * 背景：魔塔MCP工具的坐标参数格式各不相同（天气要"经度,纬度"、矩阵要"纬度,经度"），
 * 模型只会给城市名/地名 → 直接调 MCP 必失败。此工具内部先用 map_geocode 转坐标，再调对应MCP。
 * 其余本就地名友好的工具（search/geocode/directions单条）直接透传。
 */
public class BaiduMapEnhancedTool {

    private final McpClientWrapper mcp; // 框架标准MCP客户端

    public BaiduMapEnhancedTool(McpClientWrapper mcp) {
        this.mcp = mcp;
    }

    /** 通用MCP调用：tools/call → 返回文本 */
    private String callMcp(String toolName, Map<String, Object> args) {
        try {
            var result = mcp.callTool(toolName, args).block();
            if (result == null) return "无返回";
            // CallToolResult: content 里有 TextContent, 取 text
            StringBuilder sb = new StringBuilder();
            result.content().forEach(c -> {
                if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                    sb.append(tc.text());
                }
            });
            return sb.length() == 0 ? result.toString() : sb.toString();
        } catch (Exception e) {
            return "工具调用失败: " + e.getMessage();
        }
    }

    /** geocode：地名 → {lng,lat}，解析MCP返回JSON */
    private double[] geocode(String name) {
        try {
            String r = callMcp("map_geocode", Map.of("address", name));
            // 从返回的 location 里提取 lng/lat
            int li = r.indexOf("\"lng\":");
            int la = r.indexOf("\"lat\":");
            double lng = Double.parseDouble(r.substring(li + 6, r.indexOf(",", li)).trim());
            double lat = Double.parseDouble(r.substring(la + 6, r.indexOf("}", la)).trim());
            return new double[]{lng, lat}; // [经度, 纬度]
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 天气查询（语义化）：传城市名即可，内部转经纬度（天气要"经度,纬度"）
     */
    @Tool(name = "query_weather", description = "查询指定城市的实时天气预报。参数传城市名即可，例如：重庆")
    public String queryWeather(
            @ToolParam(name = "city", description = "城市名，例如：重庆、北京") String city) {
        double[] c = geocode(city);
        if (c == null) return "无法定位城市坐标: " + city;
        return callMcp("map_weather", Map.of("location", c[0] + "," + c[1])); // 经度,纬度
    }

    /**
     * 路线矩阵（语义化）：传城市/景点名，内部转"纬度,经度"坐标
     */
    @Tool(name = "query_route_matrix", description = "批量计算多个地点之间的驾车/骑行/步行距离和时间。参数传地点名称（城市或景点），内部自动转坐标。")
    public String queryRouteMatrix(
            @ToolParam(name = "origins", description = "起点名称列表，多个用|分隔，如：解放碑|洪崖洞") String origins,
            @ToolParam(name = "destinations", description = "终点名称列表，多个用|分隔") String destinations,
            @ToolParam(name = "model", description = "出行方式：driving/riding/walking，默认driving") String model) {
        String[] os = origins.split("\\|");
        String[] ds = destinations.split("\\|");
        StringBuilder ob = new StringBuilder(), db = new StringBuilder();
        for (String o : os) { double[] c = geocode(o.trim()); if (c == null) return "无法定位: " + o; if (ob.length()>0) ob.append("|"); ob.append(c[1]).append(",").append(c[0]); } // 纬度,经度
        for (String d : ds) { double[] c = geocode(d.trim()); if (c == null) return "无法定位: " + d; if (db.length()>0) db.append("|"); db.append(c[1]).append(",").append(c[0]); }
        return callMcp("map_directions_matrix", Map.of("origins", ob.toString(), "destinations", db.toString(), "model", model));
    }

    /**
     * 地点详情（语义化）：传地点名称即可，内部自动：搜地点拿uid → 用uid查详情。
     * 解决 map_place_details 只要uid、但uid从search结果来、模型拿不到的问题。
     */
    @Tool(name = "query_place_detail", description = "查询一个景点/地点的详细信息(门票、开放时间、评分、简介)。参数传地点名称和城市即可，内部自动查找。")
    public String queryPlaceDetail(
            @ToolParam(name = "place_name", description = "地点名称，如：洪崖洞、西湖") String placeName,
            @ToolParam(name = "city", description = "城市，如：重庆") String city) {
        // ① 先搜地点，拿搜索结果（里面含 uid）
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("query", placeName);
        if (city != null && !city.isBlank()) args.put("region", city);
        String search = callMcp("map_search_places", args);
        // ② 从搜索结果里提取第一个 uid
        String uid = extractUid(search);
        if (uid == null) {
            return "未找到地点 " + placeName + " 的详情（搜索无结果或无uid）。原始搜索返回: " + search;
        }
        // ③ 用 uid 查详情
        return callMcp("map_place_details", Map.of("uid", uid));
    }

    /** 从search结果文本中提取第一个 uid（每个POI顶层 "uid":"xxx" 字段） */
    private String extractUid(String searchText) {
        try {
            // 宽松正则：匹配 "uid":"值"（容忍空格/换行）
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"uid\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(searchText);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
