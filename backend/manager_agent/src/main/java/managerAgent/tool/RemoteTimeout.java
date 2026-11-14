package managerAgent.tool;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime budget shared by A2A calls and the orchestrator's future wait. */
public final class RemoteTimeout {  // 类声明：final，禁止继承
    public static final int MIN_SECONDS = 30;  // MIN_SECONDS=30：超时下限
    public static final int MAX_SECONDS = 900;  // MAX_SECONDS=900：超时上限

    private static final AtomicInteger SECONDS = new AtomicInteger(initialSeconds());  // SECONDS：当前生效秒数（AtomicInteger，Nacos 改动后其他线程立即可见）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private RemoteTimeout() {}  // 私有构造（空实现）
    /**
     * 作用：获取当前 A2A 调用与编排等待共用的运行时预算
     * 参数：无
     * 返回值：Duration 超时时长，秒数取自 Nacos 下发的 remoteTimeoutSeconds
     * 异常：无
     */

    public static Duration timeout() {  // timeout() 签名
        return Duration.ofSeconds(SECONDS.get());  // 按当前秒数构造 Duration（调用方拿它做 block 超时）
    }  // 方法结束
    /**
     * 作用：获取当前超时的秒数（用于日志与事件文案）
     * 参数：无
     * 返回值：当前生效的超时秒数
     * 异常：无
     */

    public static int currentSeconds() {  // currentSeconds 签名
        return SECONDS.get();  // 返回 SECONDS 当前值
    }  // 方法结束
    /**
     * 作用：按 Nacos 编排配置刷新超时秒数：缺失或类型不符时回落到环境变量默认值
     * 参数：orchestration Nacos 编排配置 Map，取键 remoteTimeoutSeconds（Number 类型才生效）
     * 返回值：无（直接写入静态变量）
     * 异常：无（入参为 null 直接返回；数值越界由 normalize 夹紧）
     */

    public static void configure(Map<String, Object> orchestration) {  // configure 签名：每次 A2A 调用前都会调一次
        if (orchestration == null) return;  // 配置为 null（没拉到）直接返回，保持当前值
        Object value = orchestration.get("remoteTimeoutSeconds");  // 取 remoteTimeoutSeconds
        SECONDS.set(value instanceof Number number  // 是数字就 normalize 后写入；
                ? normalize(number.intValue())  // 续：不是数字或缺失
                : initialSeconds());  // 就回落到环境变量值
    }  // 方法结束
    /**
     * 作用：读取环境变量 REMOTE_TIMEOUT_SECONDS 作为初始超时秒数
     * 参数：无
     * 返回值：合法化后的秒数，解析失败时返回 480
     * 异常：无（NumberFormatException 已在内部捕获）
     */

    private static int initialSeconds() {  // initialSeconds 签名
        try {  // try
            return normalize(Integer.parseInt(  // 读环境变量（默认 300），
                    System.getenv().getOrDefault("REMOTE_TIMEOUT_SECONDS", "300").trim()));  // 续：trim 后 parseInt，再夹紧
        } catch (NumberFormatException e) {  // 解析失败
            return 300;  // 返回 300（保证服务能启动）
        }  // catch 结束
    }  // 方法结束
    /**
     * 作用：把任意秒数夹紧到 [MIN_SECONDS, MAX_SECONDS] 区间
     * 参数：value 待校验的秒数
     * 返回值：夹紧后的合法秒数
     * 异常：无
     */

    private static int normalize(int value) {  // normalize 签名
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));  // min(900) 压上限、max(30) 抬下限
    }  // 方法结束
}  // 类结束

