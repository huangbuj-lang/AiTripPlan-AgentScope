package managerAgent.tool;

import utils.ExternalServices;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德地名解析工具：关键词带目的地限定 + 目的地围栏校验（防同名景点错配，如"五彩池"被解析到黄龙）。
 * 纯外部服务调用与坐标计算，不含业务规则；失败一律返回 null / 空。
 */
public final class AmapPlaceGeocoder {

    private AmapPlaceGeocoder() {
    }

    /** 目的地中心坐标缓存：city->{lon,lat}（进程内，防重复请求） */
    private static final ConcurrentHashMap<String, double[]> DEST_CENTER_CACHE = new ConcurrentHashMap<>();

    /**
     * 地名 -> 坐标 + POI ID。
     *
     * @return 长度为 3 的数组 {经度, 纬度, POI ID}，解析失败返回 null
     */
    public static String[] geocode(String key, String name, String city) {
        try {
            String cleanCity = cleanCityName(city);
            //关键词组合：目的地+景点名（如 "九寨沟 五彩池"），提升同城景点命中率
            String kw = (cleanCity != null && !cleanCity.isBlank() && !name.contains(cleanCity))
                    ? name + " " + cleanCity : name;
            String qs = "keywords=" + java.net.URLEncoder.encode(kw, "UTF-8")
                    + "&output=json&key=" + key + "&offset=5&extensions=base";
            if (cleanCity != null && !cleanCity.isBlank()) qs += "&city=" + java.net.URLEncoder.encode(cleanCity, "UTF-8");
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            com.fasterxml.jackson.databind.JsonNode pois = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b).path("pois");
            double[] center = destCenter(key, cleanCity);
            for (com.fasterxml.jackson.databind.JsonNode poi : pois) {
                String loc = poi.path("location").asText("");
                String[] parts = loc.split(",");
                if (parts.length < 2) continue;
                //围栏校验：坐标须落在目的地中心 150km 内，越界视为同名错配，跳过
                if (center != null) {
                    double d = distKm(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]),
                            center[1], center[0]);
                    if (d > 150) continue;
                }
                Thread.sleep(350);
                return new String[]{parts[0], parts[1], poi.path("id").asText("")};
            }
            Thread.sleep(350);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目的地中心坐标（place/text 首条，进程内缓存） */
    private static double[] destCenter(String key, String city) {
        if (city == null || city.isBlank()) return null;
        double[] hit = DEST_CENTER_CACHE.get(city);
        if (hit != null) return hit;
        try {
            Thread.sleep(350); // QPS 3 限速
            String qs = "keywords=" + java.net.URLEncoder.encode(city, "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            java.net.URL u = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            com.fasterxml.jackson.databind.JsonNode poi = new com.fasterxml.jackson.databind.ObjectMapper().readTree(b).path("pois").path(0);
            String loc = poi.path("location").asText("");
            String[] p = loc.split(",");
            if (p.length < 2) return null;
            double[] v = {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
            DEST_CENTER_CACHE.put(city, v);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目的地名清洗：去掉 "3 日"/"三日游" 等后缀 */
    private static String cleanCityName(String city) {
        if (city == null) return null;
        return city.trim().replaceAll("\\d+\\s*[日天]|[一二三四五六七八九十]+\\s*[日天]|游$", "").trim();
    }

    /** 两点球面距离（km） */
    private static double distKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /**
     * 单个地名 -> 经纬度（前端点击景点直接定位用）：调高德 Web服务 place/text，取第一条 POI。
     * 返回结构与原 /amap/geocode 接口一致：{ok:false,error} 或 {ok:true,lng,lat,poiId,poiName}。
     */
    public static java.util.Map<String, Object> geocodeFirstPoi(String name, String city) {
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("ok", false);
        if (name == null || name.isBlank()) {
            resp.put("error", "name 不能为空");
            return resp;
        }
        String key = ExternalServices.amapKey();
        if (key.isBlank()) {
            resp.put("error", "高德Key未配置，请在管理端填写或配置AMAP_KEY");
            return resp;
        }
        try {
            //高德 Web服务 place/text 关键词搜索
            String qs = "keywords=" + java.net.URLEncoder.encode(name.trim(), "UTF-8")
                    + "&output=json&key=" + key + "&offset=1&extensions=base";
            if (city != null && !city.isBlank()) {
                qs += "&city=" + java.net.URLEncoder.encode(city.trim(), "UTF-8");
            }
            java.net.URL url = new java.net.URL(ExternalServices.amapPlaceSearchUrl(qs));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            conn.disconnect();
            if (code != 200) {
                resp.put("error", "高德返回 HTTP " + code);
                return resp;
            }
            //解析 pois[0].location（"经度,纬度"）
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, java.util.Map.class);
            Object poisObj = json.get("pois");
            if (!(poisObj instanceof java.util.List) || ((java.util.List<?>) poisObj).isEmpty()) {
                resp.put("error", "未找到该地点");
                return resp;
            }
            Object first = ((java.util.List<?>) poisObj).get(0);
            if (!(first instanceof java.util.Map)) {
                resp.put("error", "高德返回格式异常");
                return resp;
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> poi = (java.util.Map<String, Object>) first;
            String location = poi.get("location") == null ? "" : poi.get("location").toString();
            String poiId = poi.get("id") == null ? "" : poi.get("id").toString();
            String poiName = poi.get("name") == null ? "" : poi.get("name").toString();
            if (location.isBlank() || !location.contains(",")) {
                resp.put("error", "高德未返回坐标");
                return resp;
            }
            String[] ll = location.split(",");
            resp.put("ok", true);
            resp.put("lng", ll[0]);
            resp.put("lat", ll[1]);
            resp.put("poiId", poiId);
            resp.put("poiName", poiName);
            return resp;
        } catch (Exception e) {
            resp.put("error", "调用失败: " + e.getMessage());
            return resp;
        }
    }
}
