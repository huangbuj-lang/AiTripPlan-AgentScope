package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.Map;

/** 高德复合工具：把「geocode->目标工具」固定依赖链封装成一次调用，省去ReAct多轮思考。 */
public class AmapCompositeTool {  // 类声明：高德复合工具（把多步依赖链封装成一次调用）

    private final McpClientWrapper mcp;  // mcp：高德 MCP 客户端

    //==================== 观测埋点：综合工具(query_trip/query_route_unified等)成败/次数/耗时 ====================
    public static final java.util.concurrent.atomic.AtomicLong TOOL_CALLS = new java.util.concurrent.atomic.AtomicLong(0);  // TOOL_CALLS：综合工具调用总数
    public static final java.util.concurrent.atomic.AtomicLong TOOL_FAILS = new java.util.concurrent.atomic.AtomicLong(0);  // TOOL_FAILS：失败数
    public static final java.util.concurrent.atomic.AtomicLong TOOL_COST_MS = new java.util.concurrent.atomic.AtomicLong(0);  // TOOL_COST_MS：累计耗时
    /**
     * 作用：构造高德聚合工具，绑定底层 MCP 客户端
     * 参数：mcp 已初始化的高德 MCP 客户端
     * 返回值：无
     * 异常：无
     */

    public AmapCompositeTool(McpClientWrapper mcp) {  // 构造器：绑定 MCP 客户端
        this.mcp = mcp;  // 保存客户端
    }  // 构造器结束

        /**
         * 作用：调用一次 MCP 工具并统一处理空结果与异常，永远返回可读文本（不抛异常）
         * 参数：toolName MCP 工具名；args 工具参数 Map
         * 返回值：工具返回的文本；调用失败时返回"查询失败：..."文本
         * 异常：无（异常内部捕获转成文本，避免打断 Agent 循环）
         */
        private String callMcp(String toolName, Map<String, Object> args) {  // callMcp：调一次 MCP 工具并取文本结果
            try {  // try
                var result = mcp.callTool(toolName, args).block();  // 调用工具并阻塞等结果
                if (result == null) return "⚠️[" + toolName + "] 数据获取失败（无返回）";  // 没有结果返回失败提示
                StringBuilder sb = new StringBuilder();  // sb：文本拼接
                result.content().forEach(c -> {  // 遍历返回内容块
                    if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {  // 是文本内容块
                        sb.append(tc.text());  // 追加文本
                    }  // if 结束
                });  // forEach 结束
                String text = sb.length() == 0 ? String.valueOf(result) : sb.toString();  // 有文本用文本，否则用对象字符串
                //高德返回错误文本 -> 标注数据缺失
                if (text.contains("错误") || text.contains("error") || text.contains("Error")  // 返回文本里含错误关键字
                        || text.contains("超限") || text.contains("LIMIT") || text.contains("EXCEEDED")  // 续：超限/限流
                        || text.contains("未找到")) {  // 续：未找到
                    return "⚠️[" + toolName + "] 数据获取失败：" + text;  // 包成 ⚠️ 失败提示返回
                }  // if 结束
                return text;  // 正常返回数据
            } catch (Exception e) {  // 捕获异常
                return "⚠️[" + toolName + "] 数据获取失败：" + e.getMessage();  // 异常也包成 ⚠️ 提示（不抛出，让模型自己处理）
            }  // catch 结束
        }  // 方法结束

    /**
     * 作用：地名转经纬度（"经度,纬度"），供路线/周边接口使用
     * 参数：name 地名，如"重庆解放碑"
     * 返回值："经度,纬度" 字符串；解析失败时返回空串
     * 异常：无
     */
    private String geo(String name) {  // geo：地名转坐标（内嵌依赖链第一步）
        try {  // try
            String r = callMcp("maps_geo", Map.of("address", name));  // 调 maps_geo
            int idx = r.indexOf("\"location\":\"");  // 找 location 字段起点
            if (idx < 0) return null;  // 没找到返回 null
            int start = idx + 12;  // 值开始位置
            int end = r.indexOf("\"", start);  // 值结束位置（下一个引号）
            return r.substring(start, end);  // 截出“经度,纬度”
        } catch (Exception e) {  // 捕获异常
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    //============ 复合工具1：路线（含地理编码依赖链） ============

    /**
     * 作用：两点自驾/步行/骑行路线查询工具
     * 参数：origin 起点地名；destination 终点地名；mode 出行方式（driving/walking/bicycling）
     * 返回值：路线与耗时文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_route", description = "规划两个地点之间的路线（驾车/步行/骑行/公交）。传两个地名即可，内部自动处理坐标，返回距离、耗时、途经。")  // @Tool：路线查询（地名 → 坐标 → 路线）
    public String queryRoute(  // queryRoute：两地点间路线
            @ToolParam(name = "origin", description = "起点地名，如：重庆解放碑") String origin,  // 参数 origin：起点地名
            @ToolParam(name = "destination", description = "终点地名，如：重庆磁器口") String destination,  // 参数 destination：终点地名
            @ToolParam(name = "mode", description = "出行方式：driving/walking/bicycling/transit，默认driving") String mode) {  // 参数 mode：出行方式（默认 driving）
        //固定依赖链：先地名转坐标，再调路线工具
        String o = geo(origin);  // 起点转坐标
        if (o == null) return "⚠️[query_route] 无法定位起点坐标: " + origin;  // 定位失败直接提示
        String d = geo(destination);  // 终点转坐标
        if (d == null) return "⚠️[query_route] 无法定位终点坐标: " + destination;  // 定位失败直接提示
        String tool;  // tool：实际要调的底层工具名
        switch (mode == null ? "driving" : mode) {  // 按出行方式选择（null 按 driving）
            case "walking": tool = "maps_direction_walking"; break;  // 步行
            case "bicycling": tool = "maps_direction_bicycling"; break;  // 骑行
            case "transit": tool = "maps_direction_transit_integrated"; break;  // 公交
            default: tool = "maps_direction_driving";  // 默认自驾
        }  // switch 结束
        return callMcp(tool, Map.of("origin", o, "destination", d));  // 调底层路线工具（坐标已就绪）
    }  // 方法结束

    //============ 复合工具2：周边搜索（含坐标依赖链） ============
    /**
     * 作用：周边搜索工具（美食/酒店等）
     * 参数：keywords 搜索关键词；center 中心地点名；radius 搜索半径（米，默认 1000）
     * 返回值：周边 POI 列表文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_around", description = "以某地点为中心，找周围的餐厅/酒店/景点。传中心地名和关键词即可，内部自动处理坐标。")  // @Tool：周边搜索（含坐标依赖）
    public String queryAround(  // queryAround：以某地为中心找周边
            @ToolParam(name = "keywords", description = "关键词，如：美食、酒店") String keywords,  // 参数 keywords：关键词
            @ToolParam(name = "center", description = "中心地点名，如：解放碑") String center,  // 参数 center：中心地名
            @ToolParam(name = "radius", description = "搜索半径(米)，默认1000") String radius) {  // 参数 radius：半径（默认 1000 米）
        String loc = geo(center);  // 中心点转坐标
        if (loc == null) return "⚠️[query_around] 无法定位中心点坐标: " + center;  // 定位失败提示
        Map<String, Object> args = new java.util.HashMap<>();  // args：调用参数
        if (keywords != null && !keywords.isBlank()) args.put("keywords", keywords);  // 有关键词就带上
        args.put("location", loc);  // 必需的中心坐标
        if (radius != null && !radius.isBlank()) args.put("radius", radius);  // 有半径就带上
        return callMcp("maps_around_search", args);  // 调周边搜索
    }  // 方法结束

    //============ 复合工具3：距离（含坐标依赖链） ============
    /**
     * 作用：两地距离测量工具
     * 参数：origin 起点地名；destination 终点地名；type 测量类型（1 驾车/0 直线/3 步行）
     * 返回值：距离文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_distance", description = "测量两个地点之间的距离（驾车/直线/步行）。传地名即可，内部自动处理坐标。")  // @Tool：距离测量（含坐标依赖）
    public String queryDistance(  // queryDistance：两地距离
            @ToolParam(name = "origin", description = "起点地名，如：解放碑") String origin,  // 参数 origin
            @ToolParam(name = "destination", description = "终点地名，如：磁器口") String destination,  // 参数 destination
            @ToolParam(name = "type", description = "类型：1驾车/0直线/3步行，默认1") String type) {  // 参数 type：1 驾车 / 0 直线 / 3 步行
        String o = geo(origin), d = geo(destination);  // 两个地名一起转坐标
        if (o == null) return "⚠️[query_distance] 无法定位起点: " + origin;  // 起点失败提示
        if (d == null) return "⚠️[query_distance] 无法定位终点: " + destination;  // 终点失败提示
        Map<String, Object> args = new java.util.HashMap<>();  // args：调用参数
        args.put("origins", o);  // 起点坐标
        args.put("destination", d);  // 终点坐标
        if (type != null && !type.isBlank()) args.put("type", type);  // 有类型就带上
        return callMcp("maps_distance", args);  // 调距离工具
    }  // 方法结束

    //============ 简单透传工具（无需复合） ============

    /**
     * 作用：城市天气查询工具
     * 参数：city 城市名
     * 返回值：天气预报文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_weather", description = "查询指定城市的天气预报。传城市名即可，如：重庆")  // @Tool：天气（直接透传）
    public String queryWeather(  // queryWeather：查城市天气
            @ToolParam(name = "city", description = "城市名，如：重庆") String city) {  // 参数 city：城市名
        return callMcp("maps_weather", Map.of("city", city));  // 直接透传 maps_weather
    }  // 方法结束

    /**
     * 作用：按关键词搜城市内地点（景点/酒店等）工具
     * 参数：keywords 关键词；city 城市名
     * 返回值：POI 列表文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_places", description = "搜索城市内的地点（景点/酒店/美食）。传城市名和关键词即可。")  // @Tool：地点搜索
    public String queryPlaces(  // queryPlaces：搜城市内地点
            @ToolParam(name = "keywords", description = "关键词，如：景点、酒店") String keywords,  // 参数 keywords：关键词（景点/酒店/美食）
            @ToolParam(name = "city", description = "城市名，如：重庆") String city) {  // 参数 city：城市名
        Map<String, Object> args = new java.util.HashMap<>();  // args：调用参数
        args.put("keywords", keywords);  // 关键词（必填）
        if (city != null && !city.isBlank()) args.put("city", city);  // 有城市就带上
        return callMcp("maps_text_search", args);  // 调文本搜索
    }  // 方法结束

    /**
     * 作用：地点详情查询工具（营业时间、评分、地址等）
     * 参数：keywords 地点名称；city 城市名
     * 返回值：地点详情文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_place_detail", description = "查询地点详情（评分/门票/开放时间）。传地点名称和城市即可，内部自动查找。")  // @Tool：地点详情（搜索 + 详情两步）
    public String queryPlaceDetail(  // queryPlaceDetail：查地点评分/门票/开放时间
            @ToolParam(name = "keywords", description = "地点名称，如：洪崖洞") String keywords,  // 参数 keywords：地点名
            @ToolParam(name = "city", description = "城市，如：重庆") String city) {  // 参数 city：城市
        Map<String, Object> args = new java.util.HashMap<>();  // args：调用参数
        args.put("keywords", keywords);  // 地点名
        if (city != null && !city.isBlank()) args.put("city", city);  // 有城市就带上
        String r = callMcp("maps_text_search", args);  // 先搜索拿到 id
        String id = extractPoiId(r);  // 从结果里提取 POI id
        if (id == null) return "⚠️[query_place_detail] 未找到地点详情: " + r;  // 没提取到就提示
        return callMcp("maps_search_detail", Map.of("id", id));  // 用 id 调详情接口
    }  // 方法结束

    /**
     * 作用：从 POI 查询响应中提取 poiid（详情接口必需）
     * 参数：r POI 接口原始响应文本
     * 返回值：poiid 字符串；提取不到时返回空串
     * 异常：无
     */
    private String extractPoiId(String r) {  // extractPoiId：从搜索结果提取 POI id
        try {  // try
            int idx = r.indexOf("\"id\":\"");  // 找 id 字段起点
            if (idx < 0) return null;  // 没找到返回 null
            int start = idx + 6;  // 值开始位置（跳过 "id":"）
            int end = r.indexOf("\"", start);  // 值结束位置
            return r.substring(start, end);  // 截出 id
        } catch (Exception e) {  // 捕获异常
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    //============ 综合工具A：行程数据一站式 ============

    /**
     * 作用：行程数据聚合工具（子 Agent 唯一入口）：一次调用拿天气+景点+酒店+美食，避免多轮 ReAct 撮合
     * 参数：destination 目的地城市（单城游）；days 行程天数（仅提示）；preference 偏好关键词；waypoints 跨城途经城市（逗号分隔）
     * 返回值：整合后的行程数据文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_trip", description = "一站式获取规划行程所需的全部真实数据。单城游传 destination 查城市景点/天气/酒店/美食；跨城自驾传 waypoints(途经城市,逗号分隔)查沿途途经景点,不查两端城市内部景点。一次调用返回完整数据。")  // @Tool：行程数据一站式（综合工具）
    public String queryTrip(  // queryTrip：一次拿天气 + 景点 + 酒店 + 餐饮
            @ToolParam(name = "destination", description = "目的地城市名（单城游用），如：重庆") String destination,  // 参数 destination：目的地城市
            @ToolParam(name = "days", description = "行程天数（仅提示）") String days,  // 参数 days：天数（仅提示）
            @ToolParam(name = "preference", description = "偏好关键词，可选") String preference,  // 参数 preference：偏好关键词
            @ToolParam(name = "waypoints", description = "跨城自驾途经城市，逗号分隔") String waypoints) {  // 参数 waypoints：跨城途经城市
        long t0 = System.currentTimeMillis();  // t0：开始时间
        TOOL_CALLS.incrementAndGet();  // 调用数 +1
        try {  // try
            String out = doQueryTrip(destination, days, preference, waypoints);  // 真正执行查询
            boolean ok = !out.contains("⚠️");  // ok：输出里没有 ⚠️ 才算成功
            if (ok) TOOL_COST_MS.addAndGet(System.currentTimeMillis() - t0); else TOOL_FAILS.incrementAndGet();  // 成功累加耗时，失败累加失败数
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip",  // 记调用链：TOOL 层
                    (ok ? "综合工具调用成功 · " : "综合工具调用失败 · ")  // 续：成功/失败 + 目的地
                            + (destination == null ? "" : destination)  // 续：途经城市
                            + (waypoints != null && !waypoints.isBlank() ? "(途经:" + waypoints + ")" : "")  // 续：途经说明
                            + (ok ? "" : " ⚠" + extractWarn(out)),  // 续：失败时附警告摘要
                    ok, System.currentTimeMillis() - t0);  // 续：成败 + 耗时
            return out;  // 返回结果
        } catch (RuntimeException e) {  // 捕获运行时异常
            TOOL_FAILS.incrementAndGet();  // 失败数 +1
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_trip",  // 记调用链：异常 + 耗时
                    "异常: " + e.getMessage(), false, System.currentTimeMillis() - t0);  // 续行
            throw e;  // 原样抛出
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：queryTrip 的实际实现：并行查天气/景点/酒店/美食并拼装成一段文本
     * 参数：destination 目的地；days 天数；preference 偏好关键词；waypoints 跨城途经城市
     * 返回值：拼装后的行程数据文本
     * 异常：无（单路失败只缺该段，不影响整体）
     */
    private String doQueryTrip(String destination, String days, String preference, String waypoints) {  // doQueryTrip：行程数据的实际查询（单城/跨城两分支）
        StringBuilder sb = new StringBuilder();  // sb：输出缓冲
        //waypoints 非空=跨城途经分支，否则单城分支
        boolean crossCity = waypoints != null && !waypoints.isBlank();  // 有途经城市 = 跨城分支
        sb.append("【行程类型】").append(crossCity ? "跨城自驾(途经景点)" : (destination == null || destination.isBlank() ? "未提供" : "单城游"));  // 行程类型说明
        if (days != null && !days.isBlank()) {  // 有天数额外说明
            sb.append(" ｜ 计划").append(days).append("日游");  // 计划 N 日游
        }  // if 结束
        if (crossCity) {  // 跨城分支
            //跨城自驾：查途经城市沿途景点/美食
            for (String wp : waypoints.split("[,，]")) {  // 逐个途经城市查询
                wp = wp.trim();  // 去空格
                if (wp.isBlank()) continue;  // 空跳过
                String pk = kw("景点", preference);  // pk：带偏好的关键词
                sb.append("\n\n【途经地景点|").append(wp).append("】\n").append(queryPlaces(pk, wp));  // 输出该途经地的景点
            }  // for 结束
            sb.append("\n\n【说明】仅列途经地景点，起点/终点城市景点另行规划。");  // 说明：只列途经地景点
            //也给目的地天气(跨城沿途有用)
            if (destination != null && !destination.isBlank()) {  // 有目的地
                sb.append("\n\n【目的地天气|").append(destination).append("】\n").append(queryWeather(destination));  // 再查目的地天气（沿途有用）
            }  // if 结束
            return sb.toString();  // 返回跨城结果
        }  // if 结束
        //原单城分支
        String placeKw = kw("景点", preference);  // placeKw：景点关键词
        String hotelKw = kw("酒店", preference);  // hotelKw：酒店关键词
        String foodKw  = kw("美食", preference);  // foodKw：美食关键词
        sb.append("\n\n").append("【天气】\n").append(queryWeather(destination));  // 天气
        sb.append("\n\n").append("【热门景点】\n").append(queryPlaces(placeKw, destination));  // 热门景点
        sb.append("\n\n").append("【住宿酒店】\n").append(queryPlaces(hotelKw, destination));  // 住宿酒店
        sb.append("\n\n").append("【餐饮美食】\n").append(queryPlaces(foodKw, destination));  // 餐饮美食
        return sb.toString();  // 返回单城结果
    }  // 方法结束

    /**
     * 作用：把基础关键词与偏好合并成检索关键词
     * 参数：base 基础关键词（如"景点"）；preference 偏好关键词，可为空
     * 返回值：合并后的关键词
     * 异常：无
     */
    private String kw(String base, String preference) {  // kw：基础关键词 + 偏好
        return (preference == null || preference.isBlank()) ? base : base + " " + preference.trim();  // 没有偏好就用基础词，否则拼上偏好
    }  // 方法结束

    //============ 综合工具5：路线一站式查询 ============

    /**
     * 作用：全程自驾路线查询（含分段与总里程）工具
     * 参数：origin 起点地名；destination 终点地名；day 第几天（仅标签提示）
     * 返回值：含分段里程与耗时的路线文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_route_all", description = "一站式获取两地之间的自驾路线与距离：传两个地名即可，内部自动处理坐标，返回驾车路线文本与两段间的驾车距离。")  // @Tool：自驾路线 + 距离一站式
    public String queryRouteAll(  // queryRouteAll：两地自驾路线与距离
            @ToolParam(name = "origin", description = "起点地名，如：重庆解放碑") String origin,  // 参数 origin
            @ToolParam(name = "destination", description = "终点地名，如：重庆磁器口") String destination,  // 参数 destination
            @ToolParam(name = "day", description = "第几天（仅标签提示，可选）") String day) {  // 参数 day：第几天（仅标签）
        //固定依赖链：地名->坐标->驾车路线->距离
        String o = geo(origin);  // 起点转坐标
        if (o == null) return "⚠️[query_route_all] 无法定位起点坐标: " + origin;  // 失败提示
        String d = geo(destination);  // 终点转坐标
        if (d == null) return "⚠️[query_route_all] 无法定位终点坐标: " + destination;  // 失败提示
        Map<String, Object> routeArgs = new java.util.HashMap<>();  // routeArgs：路线参数
        routeArgs.put("origin", o);  // 起点坐标
        routeArgs.put("destination", d);  // 终点坐标
        String route = callMcp("maps_direction_driving", routeArgs);  // 查驾车路线
        Map<String, Object> distArgs = new java.util.HashMap<>();  // distArgs：距离参数
        distArgs.put("origins", o);  // 起点坐标
        distArgs.put("destination", d);  // 终点坐标
        String dist = callMcp("maps_distance", distArgs);  // 查驾车距离
        StringBuilder sb = new StringBuilder();  // sb：输出缓冲
        if (day != null && !day.isBlank()) sb.append("【第").append(day).append("天】\n");  // 有第几天就加标签
        sb.append("【起终点】").append(origin).append(" → ").append(destination).append("\n\n");  // 起终点行
        sb.append("【驾车路线】\n").append(route).append("\n\n");  // 驾车路线
        sb.append("【驾车距离】\n").append(dist);  // 驾车距离
        return sb.toString();  // 返回
    }  // 方法结束

    //============ 综合工具6：市内衔接（步行/公交） ============

    /**
     * 作用：市内两点衔接查询（步行/公交）工具
     * 参数：origin 起点地名；destination 终点地名
     * 返回值：衔接方式与耗时文本
     * 异常：无（失败以文本形式返回）
     */
    @Tool(name = "query_urban_route", description = "查询城市内两个地点之间的衔接方式：传两个地名，内部自动算距离，短距离推荐步行、较长距离返回公共交通(公交/地铁)路线。适合市内景点间的串联。")  // @Tool：市内衔接（自动选步行/公交）
    public String queryUrbanRoute(  // queryUrbanRoute：市内两地点衔接
            @ToolParam(name = "origin", description = "起点地名，如：拙政园") String origin,  // 参数 origin
            @ToolParam(name = "destination", description = "终点地名，如：平江路") String destination) {  // 参数 destination
        String o = geo(origin);  // 起点转坐标
        if (o == null) return "⚠️[query_urban_route] 无法定位起点坐标: " + origin;  // 失败提示
        String d = geo(destination);  // 终点转坐标
        if (d == null) return "⚠️[query_urban_route] 无法定位终点坐标: " + destination;  // 失败提示
        //先算距离（type=3 步行距离，给判断用），避免 walking 超范围导致 OVER_DIRECTION_RANGE
        Map<String, Object> distArgs = new java.util.HashMap<>();  // distArgs：距离参数
        distArgs.put("origins", o);  // 起点坐标
        distArgs.put("destination", d);  // 终点坐标
        distArgs.put("type", "3");  // type=3 步行距离（先量距离，避免步行超范围报错）
        String distStr = callMcp("maps_distance", distArgs);  // 查距离
        double meters = parseMeters(distStr);  // 解析米数
        StringBuilder sb = new StringBuilder();  // sb：输出缓冲
        sb.append("【衔接】").append(origin).append(" → ").append(destination).append("\n\n");  // 衔接标题
        sb.append("【距离】\n").append(distStr).append("\n\n");  // 距离
        if (meters > 0 && meters <= MAX_WALK_METERS) {  // 3 公里内
            //短距：步行
            Map<String, Object> w = new java.util.HashMap<>();  // w：步行参数
            w.put("origin", o);  // 起点坐标
            w.put("destination", d);  // 终点坐标
            sb.append("【推荐】距离约 ").append((long) meters).append(" 米，步行即可。\n");  // 推荐步行
            sb.append("【步行路线】\n").append(callMcp("maps_direction_walking", w));  // 查步行路线
        } else {  // 否则
            //长距：公共交通（公交/地铁 integrated）
            Map<String, Object> t = new java.util.HashMap<>();  // t：公交参数
            t.put("origin", o);  // 起点坐标
            t.put("destination", d);  // 终点坐标
            sb.append("【推荐】距离较远（约 ").append(meters > 0 ? (long) meters : "未知")  // 推荐公交（距离可能未知）
                    .append(" 米），建议公交/地铁。\n");  // 续行
            sb.append("【公交路线】\n").append(callMcp("maps_direction_transit_integrated", t));  // 查公交/地铁路线
        }  // if 结束
        return sb.toString();  // 返回
    }  // 方法结束

    /**
     * 作用：从高德返回中提取限行/封路等警示信息
     * 参数：out 高德原始返回文本
     * 返回值：警示文本；没有警示时返回空串
     * 异常：无
     */
    private static String extractWarn(String out) {  // extractWarn：从输出里截取 ⚠️ 警告摘要
        if (out == null) return "";  // null 返回空串
        int i = out.indexOf("⚠️");  // 找 ⚠️ 位置
        if (i < 0) i = out.indexOf("⚠");  // 退而找 ⚠
        if (i < 0) return "";  // 没有返回空串
        String seg = out.substring(i).replace('\n', ' ').replace('\r', ' ').trim();  // 截取并压平换行
        return seg.length() > 100 ? seg.substring(0, 100) + "…" : seg;  // 超 100 字截断
    }  // 方法结束

    /**
     * 作用：解析高德返回中的距离（米）
     * 参数：r 高德原始返回文本
     * 返回值：距离米数；解析不到时返回 -1
     * 异常：无
     */
    private double parseMeters(String r) {  // parseMeters：从距离结果解析米数
        try {  // try
            int i = r.indexOf("\"meters\":");  // 找 meters 字段
            if (i < 0) return -1;  // 没找到返回 -1
            int s = i + 9;  // 值开始位置
            int e = r.indexOf(",", s);  // 找逗号
            if (e < 0) e = r.indexOf("}", s);  // 没逗号就找右括号
            if (e < 0) return -1;  // 都找不到返回 -1
            return Double.parseDouble(r.substring(s, e).trim());  // 解析成 double
        } catch (Exception ex) {  // 捕获异常
            return -1;  // 返回 -1
        }  // catch 结束
    }  // 方法结束

    /** 步行推荐里程上限（米）：超过则改用公交 */
    private static final double MAX_WALK_METERS = 3000;  // MAX_WALK_METERS：步行距离上限 3 公里

    //============ 综合工具7：路线统一（自驾/步行/公交合一） ============

    /** 路线Agent唯一综合工具。 */
    @Tool(name = "query_route_unified", description = "路线统一查询工具：查两点间路线。mode=driving查自驾路线与距离；mode=urban查市内衔接(短距步行/长距公交)。可选routePref偏好(如公交/骑行/步行/不走高速)影响出行方式。跨城自驾用driving，市内景区串联用urban。")  // @Tool：路线统一查询（自驾/市内合一）
    /**
     * 作用：统一路线工具（子 Agent 唯一入口）：driving=跨城自驾分段里程，urban=市内景点间衔接
     * 参数：origin 起点地名；destination 终点地名；mode 模式（driving/urban）；day 第几天（仅标签）；routePref 路线偏好（可选）
     * 返回值：路线文本（自驾含总里程，市内含衔接方式）
     * 异常：无（失败以文本形式返回）
     */
    public String queryRouteUnified(  // queryRouteUnified：按 mode 分流
            @ToolParam(name = "origin", description = "起点地名，如：重庆解放碑") String origin,  // 参数 origin
            @ToolParam(name = "destination", description = "终点地名，如：重庆磁器口") String destination,  // 参数 destination
            @ToolParam(name = "mode", description = "模式：driving=自驾(默认)，urban=市内步行/公交") String mode,  // 参数 mode：driving 自驾 / urban 市内
            @ToolParam(name = "day", description = "第几天（仅标签提示，可选）") String day,  // 参数 day：第几天
            @ToolParam(name = "routePref", description = "路线偏好（可选）") String routePref) {  // 参数 routePref：路线偏好
        long t0 = System.currentTimeMillis();  // t0：开始时间
        TOOL_CALLS.incrementAndGet();  // 调用数 +1
        try {  // try
            String out = doQueryRouteUnified(origin, destination, mode, day, routePref);  // 真正执行
            boolean ok = !out.contains("⚠️");  // ok：没有 ⚠️ 算成功
            if (ok) TOOL_COST_MS.addAndGet(System.currentTimeMillis() - t0); else TOOL_FAILS.incrementAndGet();  // 成功累加耗时，失败累加失败数
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_route_unified",  // 记调用链：TOOL 层
                    (ok ? "综合工具调用成功 · " : "综合工具调用失败 · ")  // 续：成功/失败 + 起终点
                            + (origin == null ? "?" : origin) + " → " + (destination == null ? "?" : destination)  // 续：起点 → 终点
                            + " · " + (mode == null || mode.isBlank() ? "driving" : mode)  // 续：模式
                            + (ok ? "" : " ⚠" + extractWarn(out)),  // 续：失败附警告摘要
                    ok, System.currentTimeMillis() - t0);  // 续：成败 + 耗时
            return out;  // 返回结果
        } catch (RuntimeException e) {  // 捕获运行时异常
            TOOL_FAILS.incrementAndGet();  // 失败数 +1
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_route_unified",  // 记调用链：异常
                    "异常: " + e.getMessage(), false, System.currentTimeMillis() - t0);  // 续：成败 + 耗时
            throw e;  // 原样抛出
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：queryRouteUnified 的实际实现：按模式分流到自驾全程查或市内逐段查
     * 参数：origin 起点地名；destination 终点地名；mode 模式；day 第几天；routePref 路线偏好
     * 返回值：路线文本
     * 异常：无（内部捕获异常）
     */
    private String doQueryRouteUnified(String origin, String destination, String mode, String day, String routePref) {  // doQueryRouteUnified：按偏好与 mode 分流
        //路线偏好->出行方式映射
        if (routePref != null && !routePref.isBlank()) {  // 有路线偏好
            if (routePref.contains("公交") || routePref.contains("地铁") || routePref.contains("公共交通")) {  // 偏好是公交/地铁
                mode = "urban";       // 公交优先->市内衔接  // 改走市内衔接
            } else if (routePref.contains("骑") || routePref.contains("自行車") || routePref.contains("bike")) {  // 偏好是骑行
                mode = "cycling";    // 骑行  // 改走骑行
            } else if (routePref.contains("步行") || routePref.contains("走路")) {  // 偏好是步行
                mode = "walking";    // 步行  // 改走步行
            }  // if 结束
        }  // if 结束
        boolean urban = "urban".equalsIgnoreCase(mode) || "walking".equalsIgnoreCase(mode) || "cycling".equalsIgnoreCase(mode);  // urban：市内 / 步行 / 骑行都归市内分支
        if (urban) {  // 市内分支
            //市内/步行/公交分支
            String o = geo(origin);  // 起点转坐标
            if (o == null) return "⚠️[query_route_unified] 无法定位起点坐标: " + origin;  // 失败提示
            String d = geo(destination);  // 终点转坐标
            if (d == null) return "⚠️[query_route_unified] 无法定位终点坐标: " + destination;  // 失败提示
            //先算距离（type=3 步行距离），避免 walking 超范围导致 OVER_DIRECTION_RANGE
            Map<String, Object> distArgs = new java.util.HashMap<>();  // distArgs：距离参数
            distArgs.put("origins", o);  // 起点坐标
            distArgs.put("destination", d);  // 终点坐标
            distArgs.put("type", "3");  // type=3 步行距离
            String distStr = callMcp("maps_distance", distArgs);  // 查距离
            double meters = parseMeters(distStr);  // 解析米数
            StringBuilder sb = new StringBuilder();  // sb：输出缓冲
            if (day != null && !day.isBlank()) sb.append("【第").append(day).append("天】\n");  // 有第几天加标签
            sb.append("【衔接】").append(origin).append(" → ").append(destination).append("\n\n");  // 衔接标题
            sb.append("【距离】\n").append(distStr).append("\n\n");  // 距离
            String mode2 = mode == null ? "" : mode.toLowerCase();  // mode2：小写模式
            if ("cycling".equals(mode2)) {  // 骑行偏好
                //骑行偏好
                Map<String, Object> b = new java.util.HashMap<>();  // b：骑行参数
                b.put("origin", o);  // 起点坐标
                b.put("destination", d);  // 终点坐标
                sb.append("【推荐】按骑行偏好查骑行路线\n");  // 说明按骑行偏好
                sb.append("【骑行路线】\n").append(callMcp("maps_direction_bicycling", b));  // 查骑行路线
            } else if ("walking".equals(mode2) || (meters > 0 && meters <= MAX_WALK_METERS)) {  // 步行偏好，或距离在步行范围内
                //步行偏好 或 短距自动步行
                Map<String, Object> w = new java.util.HashMap<>();  // w：步行参数
                w.put("origin", o);  // 起点坐标
                w.put("destination", d);  // 终点坐标
                sb.append("【推荐】").append(mode2.equals("walking") ? "按步行偏好" : "距离约 " + (long) meters + " 米")  // 推荐理由（偏好 or 距离）
                        .append("，步行即可。\n");  // 续：步行即可
                sb.append("【步行路线】\n").append(callMcp("maps_direction_walking", w));  // 查步行路线
            } else {  // 否则（长距）
                //长距公交
                Map<String, Object> t = new java.util.HashMap<>();  // t：公交参数
                t.put("origin", o);  // 起点坐标
                t.put("destination", d);  // 终点坐标
                sb.append("【推荐】距离较远（约 ").append(meters > 0 ? (long) meters : "未知")  // 推荐公交（距离可能未知）
                        .append(" 米），建议公交/地铁。\n");  // 续行
                sb.append("【公交路线】\n").append(callMcp("maps_direction_transit_integrated", t));  // 查公交/地铁
            }  // if 结束
            return sb.toString();  // 返回市内结果
        }  // if 结束
        //默认自驾分支
        String o = geo(origin);  // 默认自驾分支：起点转坐标
        if (o == null) return "⚠️[query_route_unified] 无法定位起点坐标: " + origin;  // 失败提示
        String d = geo(destination);  // 终点转坐标
        if (d == null) return "⚠️[query_route_unified] 无法定位终点坐标: " + destination;  // 失败提示
        Map<String, Object> routeArgs = new java.util.HashMap<>();  // routeArgs：路线参数
        routeArgs.put("origin", o);  // 起点坐标
        routeArgs.put("destination", d);  // 终点坐标
        String route = callMcp("maps_direction_driving", routeArgs);  // 查驾车路线
        Map<String, Object> distArgs = new java.util.HashMap<>();  // distArgs：距离参数
        distArgs.put("origins", o);  // 起点坐标
        distArgs.put("destination", d);  // 终点坐标
        String dist = callMcp("maps_distance", distArgs);  // 查驾车距离
        StringBuilder sb = new StringBuilder();  // sb：输出缓冲
        if (day != null && !day.isBlank()) sb.append("【第").append(day).append("天】\n");  // 有第几天加标签
        sb.append("【起终点】").append(origin).append(" → ").append(destination).append("\n\n");  // 起终点
        sb.append("【驾车路线】\n").append(route).append("\n\n");  // 驾车路线
        sb.append("【驾车距离】\n").append(dist);  // 驾车距离
        return sb.toString();  // 返回
    }  // 方法结束
}  // 类结束
