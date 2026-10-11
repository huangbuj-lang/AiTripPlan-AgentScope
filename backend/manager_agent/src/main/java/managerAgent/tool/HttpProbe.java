package managerAgent.tool;

/**
 * 轻量 HTTP GET 探针（探活子 Agent / 拉 /trace），失败返回 null。
 */
public final class HttpProbe {

    private HttpProbe() {
    }

    /** HTTP GET 读 body，失败返回 null */
    public static String getBody(String url, int timeoutMs) {        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs); conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            conn.disconnect();
            return body;
        } catch (Exception e) { return null; }
    
    }
}
