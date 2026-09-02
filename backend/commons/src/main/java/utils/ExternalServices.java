package utils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Resolves outbound service endpoints from Nacos with safe code defaults. */
public final class ExternalServices {
    private static final String AMAP_PLACE_SEARCH_URL = "https://restapi.amap.com/v3/place/text";
    private static final String AMAP_STATIC_MAP_URL = "https://restapi.amap.com/v3/staticmap";
    private static final String AMAP_NAVIGATION_URL = "https://ditu.amap.com/ssr/dir?type=0&policy=10";
    private static final String AMAP_MCP_URL = "https://mcp.amap.com/mcp";

    private ExternalServices() {}

    public static String amapPlaceSearchUrl(String encodedQuery) {
        return withQuery(url("amapPlaceSearchUrl", AMAP_PLACE_SEARCH_URL), encodedQuery);
    }

    public static String amapStaticMapUrl(String encodedQuery) {
        return withQuery(url("amapStaticMapUrl", AMAP_STATIC_MAP_URL), encodedQuery);
    }

    public static String amapNavigationUrl() {
        return url("amapNavigationUrl", AMAP_NAVIGATION_URL);
    }

    /**
     * 高德官方 URI API 路线链接（市内默认公交 mode=bus，多途经点分号分隔）。
     * points 每项格式 lon,lat,name[,poiId]；不足 2 点返回空串。
     */
    public static String buildNavigationUrl(java.util.List<String> points) {
        return buildNavigationUrl(points, "bus");
    }

    /** 指定出行方式的路线链接：mode=car|bus|walk|ride（高德 URI API）。 */
    public static String buildNavigationUrl(java.util.List<String> points, String mode) {
        if (points == null || points.size() < 2) {
            return "";
        }
        String[] first = points.get(0).split(",", -1);
        String[] last = points.get(points.size() - 1).split(",", -1);
        StringBuilder nav = new StringBuilder("https://uri.amap.com/navigation?mode=")
                .append(mode == null || mode.isBlank() ? "bus" : mode)
                .append("&policy=0&src=aitripplan&coordinate=gaode&callnative=0");
        nav.append("&from=").append(pointParam(first));
        if (points.size() > 2) {
            StringBuilder via = new StringBuilder();
            for (int i = 1; i < points.size() - 1; i++) {
                String[] pp = points.get(i).split(",", -1);
                if (via.length() > 0) {
                    via.append(";");
                }
                via.append(pointParam(pp));
            }
            nav.append("&via=").append(via);
        }
        nav.append("&to=").append(pointParam(last));
        return nav.toString();
    }

    /** lon,lat,name → "lon,lat,urlencoded-name" */
    private static String pointParam(String[] lonLatName) {
        String lon = lonLatName.length > 0 ? lonLatName[0] : "";
        String lat = lonLatName.length > 1 ? lonLatName[1] : "";
        String name = lonLatName.length > 2 ? lonLatName[2] : "";
        return lon + "," + lat + "," + URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    public static String amapMcpUrl(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("缺少高德MCP key");
        }
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        return withQuery(url("amapMcpUrl", AMAP_MCP_URL), "key=" + encodedKey);
    }

    /** AMap Web-service key: Nacos credentials first, then AMAP_KEY. */
    public static String amapKey() {
        String configured = AgentRuntimeConfig.credential("amapKey", "");
        if (!configured.isBlank()) return configured;
        String value = System.getenv("AMAP_KEY");
        return value == null ? "" : value.trim();
    }

    /** AMap MCP key: explicit MCP key, then AMAP_MCP_KEY, then the Web key. */
    public static String amapMcpKey() {
        String configured = AgentRuntimeConfig.credential("amapMcpKey", "");
        if (!configured.isBlank()) return configured;
        String value = System.getenv("AMAP_MCP_KEY");
        if (value != null && !value.isBlank()) return value.trim();
        return amapKey();
    }


    private static String url(String name, String defaultValue) {
        String value = AgentRuntimeConfig.serviceUrl(name, defaultValue);
        if (value.isBlank()) {
            throw new IllegalStateException("外部服务地址不能为空: " + name);
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("unsupported scheme");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("missing host");
            }
        } catch (Exception e) {
            throw new IllegalStateException("外部服务地址无效 " + name + ": " + value, e);
        }
        return value;
    }

    private static String withQuery(String url, String encodedQuery) {
        if (encodedQuery == null || encodedQuery.isBlank()) return url;
        return url + (url.contains("?") ? "&" : "?") + encodedQuery;
    }
}
