package utils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Resolves outbound service endpoints from Nacos with safe code defaults. */
public final class ExternalServices {  // 类声明：外部服务端点（高德）解析
    private static final String AMAP_PLACE_SEARCH_URL = "https://restapi.amap.com/v3/place/text";  // 高德地点搜索默认地址
    private static final String AMAP_STATIC_MAP_URL = "https://restapi.amap.com/v3/staticmap";  // 高德静态地图默认地址
    private static final String AMAP_NAVIGATION_URL = "https://ditu.amap.com/ssr/dir?type=0&policy=10";  // 高德导航页默认地址
    private static final String AMAP_MCP_URL = "https://mcp.amap.com/mcp";  // 高德 MCP 默认地址
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ExternalServices() {}  // 私有构造：禁止 new
    /**
     * 作用：高德地点搜索页地址（前端跳转用）
     * 参数：encodedQuery 已 URL 编码的查询串
     * 返回值：完整地址
     * 异常：无
     */

    public static String amapPlaceSearchUrl(String encodedQuery) {  // amapPlaceSearchUrl：地点搜索地址
        return withQuery(url("amapPlaceSearchUrl", AMAP_PLACE_SEARCH_URL), encodedQuery);  // 取配置地址并拼查询串
    }  // 方法结束
    /**
     * 作用：高德静态地图地址（前端展示用）
     * 参数：encodedQuery 已编码的查询串
     * 返回值：完整地址
     * 异常：无
     */

    public static String amapStaticMapUrl(String encodedQuery) {  // amapStaticMapUrl：静态地图地址
        return withQuery(url("amapStaticMapUrl", AMAP_STATIC_MAP_URL), encodedQuery);  // 取配置地址并拼查询串
    }  // 方法结束
    /**
     * 作用：高德导航页地址
     * 参数：无
     * 返回值：完整地址
     * 异常：无
     */

    public static String amapNavigationUrl() {  // amapNavigationUrl：导航页地址
        return url("amapNavigationUrl", AMAP_NAVIGATION_URL);  // 直接取配置地址
    }  // 方法结束
    /**
     * 作用：高德 MCP 服务地址（带 Key）
     * 参数：key 高德 Key
     * 返回值：完整地址
     * 异常：无
     */

    public static String amapMcpUrl(String key) {  // amapMcpUrl：MCP 地址（带 Key）
        if (key == null || key.isBlank()) {  // Key 为空
            throw new IllegalStateException("缺少高德MCP key");  // 抛状态异常（缺 Key 不能静默）
        }  // if 结束
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);  // Key 做 URL 编码
        return withQuery(url("amapMcpUrl", AMAP_MCP_URL), "key=" + encodedKey);  // 拼 key= 查询参数返回
    }  // 方法结束

    /**
     * 作用：读取高德 Web 服务 Key
     * 参数：无
     * 返回值：Key 字符串
     * 异常：无
     */
    public static String amapKey() {  // amapKey：读高德 Web 服务 Key
        String configured = AgentRuntimeConfig.credential("amapKey", "");  // 先读运行时配置
        if (!configured.isBlank()) return configured;  // 配置了就直接用
        String value = System.getenv("AMAP_KEY");  // 否则读环境变量
        return value == null ? "" : value.trim();  // 返回（null 归空串）
    }  // 方法结束

    /**
     * 作用：读取高德 MCP 专用 Key
     * 参数：无
     * 返回值：Key 字符串
     * 异常：无
     */
    public static String amapMcpKey() {  // amapMcpKey：读高德 MCP 专用 Key
        String configured = AgentRuntimeConfig.credential("amapMcpKey", "");  // 先读运行时配置
        if (!configured.isBlank()) return configured;  // 配置了直接用
        String value = System.getenv("AMAP_MCP_KEY");  // 否则读环境变量 AMAP_MCP_KEY
        if (value != null && !value.isBlank()) return value.trim();  // 有值就返回
        return amapKey();  // 兜底用 Web 服务 Key
    }  // 方法结束
    /**
     * 作用：读取服务地址配置，缺失时用默认值
     * 参数：name 配置键；defaultValue 默认地址
     * 返回值：服务地址
     * 异常：无
     */


    private static String url(String name, String defaultValue) {  // url：读服务地址并校验
        String value = AgentRuntimeConfig.serviceUrl(name, defaultValue);  // 从运行时配置取（带默认值）
        if (value.isBlank()) {  // 为空
            throw new IllegalStateException("外部服务地址不能为空: " + name);  // 抛异常（地址不能为空）
        }  // if 结束
        try {  // try
            URI uri = URI.create(value);  // 解析成 URI
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();  // 取协议
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {  // 不是 http/https
                throw new IllegalArgumentException("unsupported scheme");  // 抛不支持
            }  // if 结束
            if (uri.getHost() == null || uri.getHost().isBlank()) {  // 没有 host
                throw new IllegalArgumentException("missing host");  // 抛缺 host
            }  // if 结束
        } catch (Exception e) {  // 捕获异常
            throw new IllegalStateException("外部服务地址无效 " + name + ": " + value, e);  // 包成“地址无效”抛出
        }  // catch 结束
        return value;  // 返回合法地址
    }  // 方法结束
    /**
     * 作用：给地址追加查询参数
     * 参数：url 基础地址；encodedQuery 已编码的查询串
     * 返回值：拼接后的地址
     * 异常：无
     */

    private static String withQuery(String url, String encodedQuery) {  // withQuery：给地址追加查询串
        if (encodedQuery == null || encodedQuery.isBlank()) return url;  // 查询串为空返回原地址
        return url + (url.contains("?") ? "&" : "?") + encodedQuery;  // 已有 ? 就用 &，否则用 ?
    }  // 方法结束
}  // 类结束
