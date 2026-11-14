package managerAgent.tool;

import utils.ExternalServices;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德地名解析工具：关键词带目的地限定 + 目的地围栏校验（防同名景点错配，如"五彩池"被解析到黄龙）。
 * 纯外部服务调用与坐标计算，不含业务规则；失败一律返回 null / 空。
 */
public final class AmapPlaceGeocoder {  // 类声明：final 工具类
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private AmapPlaceGeocoder() {  // 私有构造开始
    }  // 构造结束

    /** 目的地中心坐标缓存：city->{lon,lat}（进程内，防重复请求） */
    private static final ConcurrentHashMap<String, double[]> DEST_CENTER_CACHE = new ConcurrentHashMap<>();  // DEST_CENTER_CACHE：并发 Map

    /**
     * 作用：地名转坐标：优先按城市内搜索，取不到时用目的地中心点兜底
     * 参数：key 高德 Web 服务 Key；name 地名；city 城市名
     * 返回值：[经度, 纬度]；解析不到返回 null
     * 异常：无（异常内部捕获）
     */
    public static String[] geocode(String key, String name, String city) {  // geocode 签名：返回 String[]（经度、纬度、POI ID）
        try {  // try
            String cleanCity = cleanCityName(city);  // 清洗城市名（去掉“3日游”之类后缀）
            //关键词组合：目的地+景点名（如 "九寨沟 五彩池"），提升同城景点命中率
            String kw = (cleanCity != null && !cleanCity.isBlank() && !name.contains(cleanCity))  // 条件：城市非空且地名里还没有城市名
                    ? name + " " + cleanCity : name;  // 是则拼成“name city”，否则原样
            String qs = "keywords=" + java.net.URLEncoder.encode(kw, "UTF-8")  // 拼查询串：keywords + output=json + key + offset=5 + extensions=base
                    + "&output=json&key=" + key + "&offset=5&extensions=base";  // 续行
            if (cleanCity != null && !cleanCity.isBlank()) qs += "&city=" + java.net.URLEncoder.encode(cleanCity, "UTF-8");  // 有城市就再加 city 参数（限定搜索范围）
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));  // 拼完整 URL（ExternalServices 统一拼 place/text 地址）
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();  // 打开连接
            c.setConnectTimeout(5000);  // 连接超时 5 秒
            c.setReadTimeout(8000);  // 读超时 8 秒
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应体（UTF-8）
            com.fasterxml.jackson.databind.JsonNode pois = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b).path("pois");  // 解析 JSON，取 pois 数组
            double[] center = destCenter(key, cleanCity);  // 先算目的地中心点（用于后面的围栏校验）
            for (com.fasterxml.jackson.databind.JsonNode poi : pois) {  // 遍历 POI
                String loc = poi.path("location").asText("");  // 取 location 字段（格式“经度,纬度”）
                String[] parts = loc.split(",");  // 按逗号切成两部分
                if (parts.length < 2) continue;  // 格式不对就跳过这条
                //围栏校验：坐标须落在目的地中心 150km 内，越界视为同名错配，跳过
                if (center != null) {  // 有中心点才校验
                    double d = distKm(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]),  // 算 POI 到中心的距离（参数顺序：纬度、经度）
                            center[1], center[0]);  // 续行：中心点也是（纬度, 经度）
                    if (d > 150) continue;  // 超过 150km 就跳过（防止“五彩池”被解析到黄龙）
                }  // 围栏校验结束
                Thread.sleep(350);  // 限速：每次返回前 sleep 350ms（高德 QPS≈3）
                return new String[]{parts[0], parts[1], poi.path("id").asText("")};  // 返回 [经度, 纬度, poiId]
            }  // 遍历结束
            Thread.sleep(350);  // 一条都没命中也要限速
            return null;  // 返回 null（解析不到）
        } catch (Exception e) {  // 任何异常
            return null;  // 返回 null（调用方按“坐标缺失”处理）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：取目的地城市中心点坐标（用于活动范围判断与兜底）
     * 参数：key 高德 Key；city 城市名
     * 返回值：[经度, 纬度]；取不到返回 null
     * 异常：无（异常内部捕获）
     */
    private static double[] destCenter(String key, String city) {  // destCenter 签名
        if (city == null || city.isBlank()) return null;  // 城市为空直接 null（没有围栏可做）
        double[] hit = DEST_CENTER_CACHE.get(city);  // 先查缓存
        if (hit != null) return hit;  // 命中直接返回（省一次高德请求）
        try {  // try
            Thread.sleep(350); // QPS 3 限速  // 限速：高德 QPS 3，先 sleep 350ms
            String qs = "keywords=" + java.net.URLEncoder.encode(city, "UTF-8")  // 拼查询串：keywords=城市 + offset=1
                    + "&output=json&key=" + key + "&offset=1&extensions=base";  // 续行
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));  // 拼 URL
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();  // 打开连接
            c.setConnectTimeout(5000);  // 连接超时 5 秒
            c.setReadTimeout(8000);  // 读超时 8 秒
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应体
            com.fasterxml.jackson.databind.JsonNode poi = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b).path("pois").path(0);  // 解析并取 pois[0]
            String loc = poi.path("location").asText("");  // 取 location
            String[] p = loc.split(",");  // 按逗号切
            if (p.length < 2) return null;  // 格式不对返回 null
            double[] v = {Double.parseDouble(p[0]), Double.parseDouble(p[1])};  // 转成 [经度, 纬度]
            DEST_CENTER_CACHE.put(city, v);  // 写缓存
            return v;  // 返回
        } catch (Exception e) {  // 任何异常
            return null;  // 返回 null（无围栏，后续跳过校验）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：清洗城市名（去掉市/区等后缀与括号说明）
     * 参数：city 原始城市名
     * 返回值：清洗后的城市名
     * 异常：无
     */
    private static String cleanCityName(String city) {  // cleanCityName 签名
        if (city == null) return null;  // null 直接返回 null
        return city.trim().replaceAll("\\d+\\s*[日天]|[一二三四五六七八九十]+\\s*[日天]|游$", "").trim();  // 去掉“3日/三天/…游”这类后缀再 trim（否则高德搜不到城市）
    }  // 方法结束

    /**
     * 作用：计算两点球面距离（Haversine）
     * 参数：lat1 起点纬度；lon1 起点经度；lat2 终点纬度；lon2 终点经度
     * 返回值：距离公里数
     * 异常：无
     */
    private static double distKm(double lat1, double lon1, double lat2, double lon2) {  // distKm 签名
        double r = 6371.0;  // 地球半径 6371km
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);  // 经纬度差转弧度
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)  // Haversine 公式前半部分
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);  // 续：cos(lat1)*cos(lat2)*sin²(dLon/2)
        return 2 * r * Math.asin(Math.sqrt(a));  // 返回 2R·asin(√a)——标准球面距离
    }  // 方法结束

    /**
     * 作用：按地名查第一个 POI 的坐标与名称（供前端地图跳转展示）
     * 参数：name 地名；city 城市名
     * 返回值：含 ok/名称/坐标的 Map；失败时 ok=false
     * 异常：无（异常转成 ok=false）
     */
    public static java.util.Map<String, Object> geocodeFirstPoi(String name, String city) {  // geocodeFirstPoi 签名：/amap/geocode 接口直接调它
        java.util.Map<String, Object> resp = new java.util.HashMap<>();  // 新建响应 Map
        resp.put("ok", false);  // 默认 ok=false（失败是默认值，成功才改）
        if (name == null || name.isBlank()) {  // name 为空
            resp.put("error", "name 不能为空");  // 写错误信息
            return resp;  // 直接返回
        }  // if 结束
        String key = ExternalServices.amapKey();  // 取高德 Key
        if (key.isBlank()) {  // Key 没配
            resp.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY");  // 写“高德Key未配置，请在管理端填写或配置AMAP_KEY”
            return resp;  // 返回
        }  // if 结束
        try {  // try
            //高德 Web服务 place/text 关键词搜索
            String qs = "keywords=" + java.net.URLEncoder.encode(name.trim(), "UTF-8")  // 拼查询串：keywords + offset=1
                    + "&output=json&key=" + key + "&offset=1&extensions=base";  // 续行
            if (city != null && !city.isBlank()) {  // 有城市才加 city 限定
                qs += "&city=" + java.net.URLEncoder.encode(city.trim(), "UTF-8");  // 续行：city 编码
            }  // if 结束
            java.net.URL url = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));  // 拼 URL
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();  // 打开连接
            conn.setRequestMethod("GET");  // GET 方法
            conn.setConnectTimeout(5000);  // 连接超时 5 秒
            conn.setReadTimeout(8000);  // 读超时 8 秒
            int code = conn.getResponseCode();  // 取 HTTP 状态码
            String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);  // 读响应体（UTF-8）
            conn.disconnect();  // 断开连接
            if (code != 200) {  // 非 200
                resp.put("error", "高德返回 HTTP " + code);  // 写“高德返回 HTTP xxx”
                return resp;  // 返回
            }  // if 结束
            //解析 pois[0].location（"经度,纬度"）
            @SuppressWarnings("unchecked")  // 抑制泛型警告
            java.util.Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper()  // 用 Jackson 读成 Map
                    .readValue(body, java.util.Map.class);  // 续行
            Object poisObj = json.get("pois");  // 取 pois
            if (!(poisObj instanceof java.util.List) || ((java.util.List<?>) poisObj).isEmpty()) {  // 不是非空 List
                resp.put("error", "未找到该地点");  // 写“未找到该地点”
                return resp;  // 返回
            }  // if 结束
            Object first = ((java.util.List<?>) poisObj).get(0);  // 取第一个 POI
            if (!(first instanceof java.util.Map)) {  // 不是 Map
                resp.put("error", "高德返回格式异常");  // 写“高德返回格式异常”
                return resp;  // 返回
            }  // if 结束
            @SuppressWarnings("unchecked")  // 抑制泛型警告
            java.util.Map<String, Object> poi = (java.util.Map<String, Object>) first;  // 强转成 Map
            String location = poi.get("location") == null ? "" : poi.get("location").toString();  // 取 location（null 归一空串）
            String poiId = poi.get("id") == null ? "" : poi.get("id").toString();  // 取 POI ID
            String poiName = poi.get("name") == null ? "" : poi.get("name").toString();  // 取 POI 名称
            if (location.isBlank() || !location.contains(",")) {  // location 为空或不含逗号
                resp.put("error", "高德未返回坐标");  // 写“高德未返回坐标”
                return resp;  // 返回
            }  // if 结束
            String[] ll = location.split(",");  // 按逗号拆经纬度
            resp.put("ok", true);  // ok=true
            resp.put("lng", ll[0]);  // 经度
            resp.put("lat", ll[1]);  // 纬度
            resp.put("poiId", poiId);  // POI ID
            resp.put("poiName", poiName);  // POI 名称
            return resp;  // 返回成功响应
        } catch (Exception e) {  // 任何异常
            resp.put("error", "调用失败: " + e.getMessage());  // 写“调用失败: 消息”
            return resp;  // 返回失败响应
        }  // catch 结束
    }  // 方法结束
}  // 类结束

