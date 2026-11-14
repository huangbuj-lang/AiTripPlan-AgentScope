package managerAgent.tool;

/**
 * 轻量 HTTP GET 探针（探活子 Agent / 拉 /trace），失败返回 null。
 */
public final class HttpProbe {  // 类声明：轻量 HTTP GET 探针
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private HttpProbe() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：发起一次 HTTP GET 并返回响应体（静默失败）
     * 参数：url 目标地址；timeoutMs 超时毫秒
     * 返回值：响应体文本；失败或非 200 返回空串
     * 异常：无（异常内部捕获）
     */
    public static String getBody(String url, int timeoutMs) {        try {  // getBody 签名 + try：任何异常都静默吞掉
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();  // 打开连接并强转 HttpURLConnection
            conn.setConnectTimeout(timeoutMs); conn.setReadTimeout(timeoutMs);  // 设置连接超时与读超时（同一个值）
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }  // 非 200 → 断开并返回 null
            String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);  // 读全部字节按 UTF-8 解码成响应体
            conn.disconnect();  // 断开连接
            return body;  // 返回响应体
        } catch (Exception e) { return null; }  // 异常 → 返回 null（探活失败不抛）
    
    }  // 方法结束
}  // 类结束

