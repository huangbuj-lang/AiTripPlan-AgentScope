package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.Map;

/** 高德复合工具：把「geocode->目标工具」固定依赖链封装成一次调用，省去ReAct多轮思考。 */
public class AmapCompositeTool {

    private final McpClientWrapper mcp;

    //==================== 观测埋点：综合工具(query_trip/query_route_unified等)成败/次数/耗时 ====================
    public static final java.util.concurrent.atomic.AtomicLong TOOL_CALLS = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong TOOL_FAILS = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong TOOL_COST_MS = new java.util.concurrent.atomic.AtomicLong(0);

    public AmapCompositeTool(McpClientWrapper mcp) {
        this.mcp = mcp;
    }

    /** 通用MCP调用：失败/错误文本返回明确降级标记，防模型编造。 */
        private String callMcp(String toolName, Map<String, Object> args) {
            try {
                var result = mcp.callTool(toolName, args).block();
                if (result == null) return "⚠️[" + toolName + "] 数据获取失败（无返回）";
                StringBuilder sb = new StringBuilder();
                result.content().forEach(c -> {
                    if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                        sb.append(tc.text());
                    }
                });
                String text = sb.length() == 0 ? String.valueOf(result) : sb.toString();
                //高德返回错误文本 -> 标注数据缺失
                if (text.contains("错误") || text.contains("error") || text.contains("Error")
                        || text.contains("超限") || text.contains("LIMIT") || text.contains("EXCEEDED")
                        || text.contains("未找到")) {
                    return "⚠️[" + toolName + "] 数据获取失败：" + text;
                }
                return text;
            } catch (Exception e) {
                return "⚠️[" + toolName + "] 数据获取失败：" + e.getMessage();
            }
        }

    /** 地名 -> "经度,纬度"（内部调 maps_geo，失败返回null） */
    private String geo(String name) {
        try {
            String r = callMcp("maps_geo", Map.of("address", name));
            int idx = r.indexOf("\"location\":\"");
            if (idx < 0) return null;
            int start = idx + 12;
            int end = r.indexOf("\"", start);
            return r.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    //============ 复合工具1：路线（含地理编码依赖链） ============

    /** 驾车/步行/骑行/公交 通用路线复合工具 */
    @Tool(name = "query_route", description = "规划两个地点之间的路线（驾车/步行/骑行/公交）。传两个地名即可，内部自动处理坐标，返回距离、耗时、途经。")
    public String queryRoute(
            @ToolParam(name = "origin", description = "起点地名，如：重庆解放碑") String origin,
            @ToolParam(name = "destination", description = "终点地名，如：重庆磁器口") String destination,
            @ToolParam(name = "mode", description = "出行方式：driving/walking/bicycling/transit，默认driving") String mode) {
        //固定依赖链：先地名转坐标，再调路线工具
        String o = geo(origin);
        if (o == null) return "⚠️[query_route] 无法定位起点坐标: " + origin;
        String d = geo(destination);
        if (d == null) return "⚠️[query_route] 无法定位终点坐标: " + destination;
        String tool;
        switch (mode == null ? "driving" : mode) {
            case "walking": tool = "maps_direction_walking"; break;
            case "bicycling": tool = "maps_direction_bicycling"; break;
            case "transit": tool = "maps_direction_transit_integrated"; break;
            default: tool = "maps_direction_driving";
        }
        return callMcp(tool, Map.of("origin", o, "destination", d));
    }

    //============ 复合工具2：周边搜索（含坐标依赖链） ============

    @Tool(name = "query_around", description = "以某地点为中心，找周围的餐厅/酒店/景点。传中心地名和关键词即可，内部自动处理坐标。")
    public String queryAround(
            @ToolParam(name = "keywords", description = "关键词，如：美食、酒店") String keywords,
            @ToolParam(name = "center", description = "中心地点名，如：解放碑") String center,
            @ToolParam(name = "radius", description = "搜索半径(米)，默认1000") String radius) {
        String loc = geo(center);
        if (loc == null) return "⚠️[query_around] 无法定位中心点坐标: " + center;
        Map<String, Object> args = new java.util.HashMap<>();
        if (keywords != null && !keywords.isBlank()) args.put("keywords", keywords);
        args.put("location", loc);
        if (radius != null && !radius.isBlank()) args.put("radius", radius);
        return callMcp("maps_around_search", args);
    }

    //============ 复合工具3：距离（含坐标依赖链） ============

    @Tool(name = "query_distance", description = "测量两个地点之间的距离（驾车/直线/步行）。传地名即可，内部自动处理坐标。")
    public String queryDistance(
            @ToolParam(name = "origin", description = "起点地名，如：解放碑") String origin,
            @ToolParam(name = "destination", description = "终点地名，如：磁器口") String destination,
            @ToolParam(name = "type", description = "类型：1驾车/0直线/3步行，默认1") String type) {
        String o = geo(origin), d = geo(destination);
        if (o == null) return "⚠️[query_distance] 无法定位起点: " + origin;
        if (d == null) return "⚠️[query_distance] 无法定位终点: " + destination;
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("origins", o);
        args.put("destination", d);
        if (type != null && !type.isBlank()) args.put("type", type);
        return callMcp("maps_distance", args);
    }

    //============ 简单透传工具（无需复合） ============

    /** 天气：传城市名即可 */
    @Tool(name = "query_weather", description = "查询指定城市的天气预报。传城市名即可，如：重庆")
    public String queryWeather(
            @ToolParam(name = "city", description = "城市名，如：重庆") String city) {
        return callMcp("maps_weather", Map.of("city", city));
    }

    /** 地点搜索：城市名+关键词 */
    @Tool(name = "query_places", description = "搜索城市内的地点（景点/酒店/美食）。传城市名和关键词即可。")
    public String queryPlaces(
            @ToolParam(name = "keywords", description = "关键词，如：景点、酒店") String keywords,
            @ToolParam(name = "city", description = "城市名，如：重庆") String city) {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("keywords", keywords);
        if (city != null && !city.isBlank()) args.put("city", city);
        return callMcp("maps_text_search", args);
    }

    /** 地点详情：传名称+城市，内部搜拿id再查 */
    @Tool(name = "query_place_detail", description = "查询地点详情（评分/门票/开放时间）。传地点名称和城市即可，内部自动查找。")
    public String queryPlaceDetail(
            @ToolParam(name = "keywords", description = "地点名称，如：洪崖洞") String keywords,
            @ToolParam(name = "city", description = "城市，如：重庆") String city) {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("keywords", keywords);
        if (city != null && !city.isBlank()) args.put("city", city);
        String r = callMcp("maps_text_search", args);
        String id = extractPoiId(r);
        if (id == null) return "⚠️[query_place_detail] 未找到地点详情: " + r;
        return callMcp("maps_search_detail", Map.of("id", id));
    }

    /** 从 text_search 结果提取第一个 POI id */
    private String extractPoiId(String r) {
        try {
            int idx = r.indexOf("\"id\":\"");
            if (idx < 0) return null;
            int start = idx + 6;
            int end = r.indexOf("\"", start);
            return r.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    //============ 综合工具A：行程数据一站式 ============

    /** 行程Agent专用综合工具。 */
    @Tool(name = "query_trip", description = "一站式获取规划行程所需的全部真实数据。单城游传 destination 查城市景点/天气/酒店/美食；跨城自驾传 waypoints(途经城市,逗号分隔)查沿途途经景点,不查两端城市内部景点。一次调用返回完整数据。")
    public String queryTrip(
            @ToolParam(name = "destination", description = "目的地城市名（单城游用），如：重庆") String destination,
            @ToolParam(name = "days", description = "行程天数（仅提示）") String days,
            @ToolParam(name = "preference", description = "偏好关键词，可选") String preference,
            @ToolParam(name = "waypoints", description = "跨城自驾途经城市，逗号分隔") String waypoints) {
        long t0 = System.currentTimeMillis();
        TOOL_CALLS.incrementAndGet();
        try {
            String out = doQueryTrip(destination, days, preference, waypoints);
            boolean ok = !out.contains("⚠️");
            if (ok) TOOL_COST_MS.addAndGet(System.currentTimeMillis() - t0); else TOOL_FAILS.incrementAndGet();
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip",
                    (ok ? "综合工具调用成功 · " : "综合工具调用失败 · ")
                            + (destination == null ? "" : destination)
                            + (waypoints != null && !waypoints.isBlank() ? "(途经:" + waypoints + ")" : "")
                            + (ok ? "" : " ⚠" + extractWarn(out)),
                    ok, System.currentTimeMillis() - t0);
            return out;
        } catch (RuntimeException e) {
            TOOL_FAILS.incrementAndGet();
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip",
                    "异常: " + e.getMessage(), false, System.currentTimeMillis() - t0);
            throw e;
        }
    }

    /** 原实现（观测包装拆出，无 @Tool 注解） */
    private String doQueryTrip(String destination, String days, String preference, String waypoints) {
        StringBuilder sb = new StringBuilder();
        //waypoints 非空=跨城途经分支，否则单城分支
        boolean crossCity = waypoints != null && !waypoints.isBlank();
        sb.append("【行程类型】").append(crossCity ? "跨城自驾(途经景点)" : (destination == null || destination.isBlank() ? "未提供" : "单城游"));
        if (days != null && !days.isBlank()) {
            sb.append(" ｜ 计划").append(days).append("日游");
        }
        if (crossCity) {
            //跨城自驾：查途经城市沿途景点/美食
            //★上限 6 个途经地：途经点从"州级"细化为"县级"后，若不封顶，调用次数与返回体量会成倍放大
            //（耗时、高德配额、喂给模型的 prompt 都会膨胀）。6 个足以覆盖主要落路段，且可控。
            int wpCount = 0;
            for (String wp : waypoints.split("[,，]")) {
                wp = wp.trim();
                if (wp.isBlank()) continue;
                if (wpCount >= 6) break;
                wpCount++;
                String pk = kw("景点", preference);
                sb.append("\n\n【途经地景点|").append(wp).append("】\n").append(queryPlaces(pk, wp));
                //★沿途风光（用户要求）：单独补一次「观景台」查询 —— 路景（观景台/垭口/大桥视角等）不在
                //  「景点」关键词的默认结果里，但正是自驾沿途最值得看的东西。结果供行程Agent 写进
                //  当天【车程条目的说明】里（不下车、不占游玩时长、不单列条目）。
                sb.append("\n【沿途风光|").append(wp).append("】\n").append(queryPlaces("观景台", wp));
            }
            sb.append("\n\n【说明】仅列途经地景点，起点/终点城市景点另行规划。");
            //也给目的地天气(跨城沿途有用)
            if (destination != null && !destination.isBlank()) {
                sb.append("\n\n【目的地天气|").append(destination).append("】\n").append(queryWeather(destination));
            }
            return sb.toString();
        }
        //原单城分支
        String placeKw = kw("景点", preference);
        String hotelKw = kw("酒店", preference);
        String foodKw  = kw("美食", preference);
        sb.append("\n\n").append("【天气】\n").append(queryWeather(destination));
        sb.append("\n\n").append("【热门景点】\n").append(queryPlaces(placeKw, destination));
        sb.append("\n\n").append("【住宿酒店】\n").append(queryPlaces(hotelKw, destination));
        sb.append("\n\n").append("【餐饮美食】\n").append(queryPlaces(foodKw, destination));
        return sb.toString();
    }

    /** 拼接偏好关键词：preference 非空时 -> "基础词 偏好词"(如"景点 亲子")，否则用基础词。 */
    private String kw(String base, String preference) {
        return (preference == null || preference.isBlank()) ? base : base + " " + preference.trim();
    }

    //============ 综合工具5：路线一站式查询 ============

    /** 路线Agent专用综合工具。 */
    @Tool(name = "query_route_all", description = "一站式获取两地之间的自驾路线与距离：传两个地名即可，内部自动处理坐标，返回驾车路线文本与两段间的驾车距离。")
    public String queryRouteAll(
            @ToolParam(name = "origin", description = "起点地名，如：重庆解放碑") String origin,
            @ToolParam(name = "destination", description = "终点地名，如：重庆磁器口") String destination,
            @ToolParam(name = "day", description = "第几天（仅标签提示，可选）") String day) {
        //固定依赖链：地名->坐标->驾车路线->距离
        String o = geo(origin);
        if (o == null) return "⚠️[query_route_all] 无法定位起点坐标: " + origin;
        String d = geo(destination);
        if (d == null) return "⚠️[query_route_all] 无法定位终点坐标: " + destination;
        Map<String, Object> routeArgs = new java.util.HashMap<>();
        routeArgs.put("origin", o);
        routeArgs.put("destination", d);
        String route = callMcp("maps_direction_driving", routeArgs);
        Map<String, Object> distArgs = new java.util.HashMap<>();
        distArgs.put("origins", o);
        distArgs.put("destination", d);
        String dist = callMcp("maps_distance", distArgs);
        StringBuilder sb = new StringBuilder();
        if (day != null && !day.isBlank()) sb.append("【第").append(day).append("天】\n");
        sb.append("【起终点】").append(origin).append(" → ").append(destination).append("\n\n");
        sb.append("【驾车路线】\n").append(route).append("\n\n");
        sb.append("【驾车距离】\n").append(dist);
        return sb.toString();
    }

    //============ 综合工具6：市内衔接（步行/公交） ============

    /** 市内游专属综合工具：城市内景点/地点间的衔接方式（步行或公共交通）。 */
    @Tool(name = "query_urban_route", description = "查询城市内两个地点之间的衔接方式：传两个地名，内部自动算距离，短距离推荐步行、较长距离返回公共交通(公交/地铁)路线。适合市内景点间的串联。")
    public String queryUrbanRoute(
            @ToolParam(name = "origin", description = "起点地名，如：拙政园") String origin,
            @ToolParam(name = "destination", description = "终点地名，如：平江路") String destination) {
        String o = geo(origin);
        if (o == null) return "⚠️[query_urban_route] 无法定位起点坐标: " + origin;
        String d = geo(destination);
        if (d == null) return "⚠️[query_urban_route] 无法定位终点坐标: " + destination;
        //先算距离（type=3 步行距离，给判断用），避免 walking 超范围导致 OVER_DIRECTION_RANGE
        Map<String, Object> distArgs = new java.util.HashMap<>();
        distArgs.put("origins", o);
        distArgs.put("destination", d);
        distArgs.put("type", "3");
        String distStr = callMcp("maps_distance", distArgs);
        double meters = parseMeters(distStr);
        StringBuilder sb = new StringBuilder();
        sb.append("【衔接】").append(origin).append(" → ").append(destination).append("\n\n");
        sb.append("【距离】\n").append(distStr).append("\n\n");
        if (meters > 0 && meters <= MAX_WALK_METERS) {
            //短距：步行
            Map<String, Object> w = new java.util.HashMap<>();
            w.put("origin", o);
            w.put("destination", d);
            sb.append("【推荐】距离约 ").append((long) meters).append(" 米，步行即可。\n");
            sb.append("【步行路线】\n").append(callMcp("maps_direction_walking", w));
        } else {
            //长距：公共交通（公交/地铁 integrated）
            Map<String, Object> t = new java.util.HashMap<>();
            t.put("origin", o);
            t.put("destination", d);
            sb.append("【推荐】距离较远（约 ").append(meters > 0 ? (long) meters : "未知")
                    .append(" 米），建议公交/地铁。\n");
            sb.append("【公交路线】\n").append(callMcp("maps_direction_transit_integrated", t));
        }
        return sb.toString();
    }

    /** 调用链明细：从工具输出提取首个 失败片段（内部归因：哪个环节挂的） */
    private static String extractWarn(String out) {
        if (out == null) return "";
        int i = out.indexOf("⚠️");
        if (i < 0) i = out.indexOf("⚠");
        if (i < 0) return "";
        String seg = out.substring(i).replace('\n', ' ').replace('\r', ' ').trim();
        return seg.length() > 100 ? seg.substring(0, 100) + "…" : seg;
    }

    /** 从 maps_distance 返回文本里解析米数（优先取 meters 字段），失败返回 -1 */
    private double parseMeters(String r) {
        try {
            int i = r.indexOf("\"meters\":");
            if (i < 0) return -1;
            int s = i + 9;
            int e = r.indexOf(",", s);
            if (e < 0) e = r.indexOf("}", s);
            if (e < 0) return -1;
            return Double.parseDouble(r.substring(s, e).trim());
        } catch (Exception ex) {
            return -1;
        }
    }

    /** 步行推荐里程上限（米）：超过则改用公交 */
    private static final double MAX_WALK_METERS = 3000;

    //============ 综合工具7：路线统一（自驾/步行/公交合一） ============

    /** 路线Agent唯一综合工具。 */
    @Tool(name = "query_route_unified", description = "路线统一查询工具：查两点间路线。mode=driving查自驾路线与距离；mode=urban查市内衔接(短距步行/长距公交)。可选routePref偏好(如公交/骑行/步行/不走高速)影响出行方式。跨城自驾用driving，市内景区串联用urban。")
    /** 观测包装：综合工具成败/耗时统计 */
    public String queryRouteUnified(
            @ToolParam(name = "origin", description = "起点地名，如：重庆解放碑") String origin,
            @ToolParam(name = "destination", description = "终点地名，如：重庆磁器口") String destination,
            @ToolParam(name = "mode", description = "模式：driving=自驾(默认)，urban=市内步行/公交") String mode,
            @ToolParam(name = "day", description = "第几天（仅标签提示，可选）") String day,
            @ToolParam(name = "routePref", description = "路线偏好（可选）") String routePref) {
        long t0 = System.currentTimeMillis();
        TOOL_CALLS.incrementAndGet();
        try {
            String out = doQueryRouteUnified(origin, destination, mode, day, routePref);
            boolean ok = !out.contains("⚠️");
            if (ok) TOOL_COST_MS.addAndGet(System.currentTimeMillis() - t0); else TOOL_FAILS.incrementAndGet();
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_route_unified",
                    (ok ? "综合工具调用成功 · " : "综合工具调用失败 · ")
                            + (origin == null ? "?" : origin) + " → " + (destination == null ? "?" : destination)
                            + " · " + (mode == null || mode.isBlank() ? "driving" : mode)
                            + (ok ? "" : " ⚠" + extractWarn(out)),
                    ok, System.currentTimeMillis() - t0);
            return out;
        } catch (RuntimeException e) {
            TOOL_FAILS.incrementAndGet();
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_route_unified",
                    "异常: " + e.getMessage(), false, System.currentTimeMillis() - t0);
            throw e;
        }
    }

    /** 原实现（观测包装拆出，无 @Tool 注解） */
    private String doQueryRouteUnified(String origin, String destination, String mode, String day, String routePref) {
        //路线偏好->出行方式映射
        if (routePref != null && !routePref.isBlank()) {
            if (routePref.contains("公交") || routePref.contains("地铁") || routePref.contains("公共交通")) {
                mode = "urban";       // 公交优先->市内衔接
            } else if (routePref.contains("骑") || routePref.contains("自行車") || routePref.contains("bike")) {
                mode = "cycling";    // 骑行
            } else if (routePref.contains("步行") || routePref.contains("走路")) {
                mode = "walking";    // 步行
            }
        }
        boolean urban = "urban".equalsIgnoreCase(mode) || "walking".equalsIgnoreCase(mode) || "cycling".equalsIgnoreCase(mode);
        if (urban) {
            //市内/步行/公交分支
            String o = geo(origin);
            if (o == null) return "⚠️[query_route_unified] 无法定位起点坐标: " + origin;
            String d = geo(destination);
            if (d == null) return "⚠️[query_route_unified] 无法定位终点坐标: " + destination;
            //先算距离（type=3 步行距离），避免 walking 超范围导致 OVER_DIRECTION_RANGE
            Map<String, Object> distArgs = new java.util.HashMap<>();
            distArgs.put("origins", o);
            distArgs.put("destination", d);
            distArgs.put("type", "3");
            String distStr = callMcp("maps_distance", distArgs);
            double meters = parseMeters(distStr);
            StringBuilder sb = new StringBuilder();
            if (day != null && !day.isBlank()) sb.append("【第").append(day).append("天】\n");
            sb.append("【衔接】").append(origin).append(" → ").append(destination).append("\n\n");
            sb.append("【距离】\n").append(distStr).append("\n\n");
            String mode2 = mode == null ? "" : mode.toLowerCase();
            if ("cycling".equals(mode2)) {
                //骑行偏好
                Map<String, Object> b = new java.util.HashMap<>();
                b.put("origin", o);
                b.put("destination", d);
                sb.append("【推荐】按骑行偏好查骑行路线\n");
                sb.append("【骑行路线】\n").append(callMcp("maps_direction_bicycling", b));
            } else if ("walking".equals(mode2) || (meters > 0 && meters <= MAX_WALK_METERS)) {
                //步行偏好 或 短距自动步行
                Map<String, Object> w = new java.util.HashMap<>();
                w.put("origin", o);
                w.put("destination", d);
                sb.append("【推荐】").append(mode2.equals("walking") ? "按步行偏好" : "距离约 " + (long) meters + " 米")
                        .append("，步行即可。\n");
                sb.append("【步行路线】\n").append(callMcp("maps_direction_walking", w));
            } else {
                //长距公交
                Map<String, Object> t = new java.util.HashMap<>();
                t.put("origin", o);
                t.put("destination", d);
                sb.append("【推荐】距离较远（约 ").append(meters > 0 ? (long) meters : "未知")
                        .append(" 米），建议公交/地铁。\n");
                sb.append("【公交路线】\n").append(callMcp("maps_direction_transit_integrated", t));
            }
            return sb.toString();
        }
        //默认自驾分支
        String o = geo(origin);
        if (o == null) return "⚠️[query_route_unified] 无法定位起点坐标: " + origin;
        String d = geo(destination);
        if (d == null) return "⚠️[query_route_unified] 无法定位终点坐标: " + destination;
        Map<String, Object> routeArgs = new java.util.HashMap<>();
        routeArgs.put("origin", o);
        routeArgs.put("destination", d);
        String route = callMcp("maps_direction_driving", routeArgs);
        Map<String, Object> distArgs = new java.util.HashMap<>();
        distArgs.put("origins", o);
        distArgs.put("destination", d);
        String dist = callMcp("maps_distance", distArgs);
        StringBuilder sb = new StringBuilder();
        if (day != null && !day.isBlank()) sb.append("【第").append(day).append("天】\n");
        sb.append("【起终点】").append(origin).append(" → ").append(destination).append("\n\n");
        sb.append("【驾车路线】\n").append(route).append("\n\n");
        sb.append("【驾车距离】\n").append(dist);
        return sb.toString();
    }
}
