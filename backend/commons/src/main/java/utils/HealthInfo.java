package utils;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★2026-09-11 服务级健康信息（评审点：原来只有 /agents/health 这种子Agent健康统计，
 * 没有真正的进程存活探针，服务挂了没人知道）。三服务统一返回同一形状，便于守护脚本/监控判断。
 *
 * <p>★二次修正（2026-09-11 晚）：原来只报<b>进程自身</b>状态，不报依赖——结果"Nacos 挂了"时
 * 三个服务 `/health` 全是 UP，排查绕了一大圈（要翻子Agent日志看到 ConnectException 才知道）。
 * 现在附加一个<b>快速、非阻塞</b>的依赖探针（`nacos` / `depsOk`）：进程存活语义仍是 `status`，
 * 依赖状态单独成字段，watchdog 按 HTTP 200 判活不受影响。
 */
public final class HealthInfo {

    private HealthInfo() {}

    /** 依赖探针超时（毫秒）：宁可报 DOWN 也不阻塞 /health */
    private static final int DEP_PROBE_TIMEOUT_MS = 1500;

    /** 上游/依赖的可选深检开关：/health?deep=1 时附加（默认关闭） */
    public static Map<String, Object> of(String service, int port, Map<String, Object> extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", service);
        m.put("port", port);
        try {
            m.put("pid", ProcessHandle.current().pid());
            m.put("startMs", System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getUptime());
            m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        } catch (Exception e) {
            m.put("pid", -1);
        }
        m.put("modelCalls", SafeModel.MODEL_CALLS.get());
        // ★依赖健康：Nacos 不可达 = 三服务互相发现不了 = 所有 A2A 调用 5xx（实测踩过）
        String nacos = nacosStatus();
        m.put("nacos", nacos);
        m.put("depsOk", "UP".equals(nacos));
        if (extras != null) m.putAll(extras);
        return m;
    }

    /** Nacos 可达性探针：1.5s 超时，任何异常都收敛成 DOWN(原因)，绝不抛出/阻塞 */
    public static String nacosStatus() {
        try {
            String addr = AgentEndpoints.nacosAddr(); // 形态：host:port
            if (addr == null || addr.isBlank()) return "DOWN(未配置)";
            String url = addr.startsWith("http") ? addr : "http://" + addr;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url + "/nacos/").openConnection();
            c.setConnectTimeout(DEP_PROBE_TIMEOUT_MS);
            c.setReadTimeout(DEP_PROBE_TIMEOUT_MS);
            c.setRequestMethod("GET");
            try {
                int code = c.getResponseCode();
                // Nacos 控制台通常 200/302；能拿到响应码即说明服务在监听
                return (code >= 200 && code < 500) ? "UP" : "DOWN(http" + code + ")";
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return "DOWN(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** 进程启动时刻（JVM 启动的 epoch ms），供各服务复用 */
    public static long startMs() {
        try {
            return System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getUptime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
