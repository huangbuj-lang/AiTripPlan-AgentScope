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
public final class HealthInfo {  // 类声明：服务级健康信息构造器（三服务统一返回形状）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private HealthInfo() {}  // 私有构造：禁止 new

    /** 依赖探针超时（毫秒）：宁可报 DOWN 也不阻塞 /health */
    private static final int DEP_PROBE_TIMEOUT_MS = 1500;  // DEP_PROBE_TIMEOUT_MS：依赖探针超时（宁可报 DOWN 也不阻塞 /health）

    /**
     * 作用：构造统一格式的健康信息（服务名/端口/Nacos 状态/运行时长 + 扩展字段），供各 Agent 的 /health 返回
     * 参数：service 服务名；port 端口；extras 额外指标，可为 null
     * 返回值：健康信息 Map
     * 异常：无
     */
    public static Map<String, Object> of(String service, int port, Map<String, Object> extras) {  // of：构造健康信息 Map
        Map<String, Object> m = new LinkedHashMap<>();  // m：结果（LinkedHashMap 保字段顺序）
        m.put("status", "UP");  // status 固定 UP（进程存活语义）
        m.put("service", service);  // 服务名
        m.put("port", port);  // 端口
        try {  // try：取进程信息失败也不影响
            m.put("pid", ProcessHandle.current().pid());  // pid 当前进程号
            m.put("startMs", System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getUptime());  // startMs 启动时间戳 = 现在 - 已运行时长
            m.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());  // uptimeMs 已运行毫秒
        } catch (Exception e) {  // 捕获异常
            m.put("pid", -1);  // pid 置 -1
        }  // catch 结束
        m.put("modelCalls", SafeModel.MODEL_CALLS.get());  // modelCalls：本进程累计模型调用次数
        // ★依赖健康：Nacos 不可达 = 三服务互相发现不了 = 所有 A2A 调用 5xx（实测踩过）
        String nacos = nacosStatus();  // 探一次 Nacos 依赖状态
        m.put("nacos", nacos);  // nacos 字段
        m.put("depsOk", "UP".equals(nacos));  // depsOk：依赖是否可用
        if (extras != null) m.putAll(extras);  // 附加扩展字段
        return m;  // 返回健康信息
    }  // 方法结束

    /**
     * 作用：读取当前 Nacos 注册状态
     * 参数：无
     * 返回值：状态文本（如 connected/disconnected）
     * 异常：无
     */
    public static String nacosStatus() {  // nacosStatus：探 Nacos 是否可达
        try {  // try
            String addr = AgentEndpoints.nacosAddr(); // 形态：host:port  // addr：Nacos 地址（host:port）
            if (addr == null || addr.isBlank()) return "DOWN(未配置)";  // 没配置直接报 DOWN
            String url = addr.startsWith("http") ? addr : "http://" + addr;  // 没有协议头就补 http://
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)  // 打开连接并强转 HttpURLConnection
                    new java.net.URL(url + "/nacos/").openConnection();  // 续行
            c.setConnectTimeout(DEP_PROBE_TIMEOUT_MS);  // 连接超时（1.5s）
            c.setReadTimeout(DEP_PROBE_TIMEOUT_MS);  // 读超时（1.5s）
            c.setRequestMethod("GET");  // GET 请求
            try {  // try
                int code = c.getResponseCode();  // 响应码
                // Nacos 控制台通常 200/302；能拿到响应码即说明服务在监听
                return (code >= 200 && code < 500) ? "UP" : "DOWN(http" + code + ")";  // 200~499 都算在监听 → UP，否则 DOWN
            } finally {  // finally
                c.disconnect();  // 断开连接
            }  // finally 结束
        } catch (Exception e) {  // 捕获异常
            return "DOWN(" + e.getClass().getSimpleName() + ")";  // DOWN(异常类名)
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取进程启动时间戳
     * 参数：无
     * 返回值：启动毫秒时间戳
     * 异常：无
     */
    public static long startMs() {  // startMs：进程启动时间戳
        try {  // try
            return System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getUptime();  // 现在 - 已运行时长
        } catch (Exception e) {  // 捕获异常
            return 0L;  // 返回 0
        }  // catch 结束
    }  // 方法结束
}  // 类结束
