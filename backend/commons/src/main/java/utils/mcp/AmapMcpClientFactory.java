package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import utils.ExternalServices;
import utils.RateLimiter;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/** 高德 MCP 客户端工厂：创建并初始化高德客户端。 */
public class AmapMcpClientFactory {  // 类声明：高德 MCP 客户端工厂

    /** 并发信号量：限制同时进行中的请求数（默认3） */
    private static final int SEM_PERMITS = Integer.parseInt(System.getenv().getOrDefault("AMAP_SEM_PERMITS", "3"));  // SEM_PERMITS：并发许可数（AMAP_SEM_PERMITS，默认 3）
    private static final Semaphore SEM = new Semaphore(SEM_PERMITS);  // SEM：并发信号量（限制同时进行中的请求）

    /** 高德 QPS 主动限速令牌桶：速率=AMAP_QPS/s（默认3，对齐高德个人key配额）。
     *  并发数由 SEM 控制，时间速率由令牌桶控制——两者叠加，防瞬间并发打爆 QPS 配额。 */
    private static final double AMAP_QPS = Double.parseDouble(System.getenv().getOrDefault("AMAP_QPS", "3"));  // AMAP_QPS：主动限速速率（默认 3，对齐高德个人 Key 配额）
    private static final RateLimiter QPS_LIMITER = RateLimiter.create(AMAP_QPS);  // QPS_LIMITER：令牌桶（时间维度限速）

    /**
     * 作用：获取一个高德 MCP 调用许可（全局限并发，默认 3 个），拿不到则阻塞
     * 参数：无
     * 返回值：无
     * 异常：无（等待中被中断时恢复中断标志并返回，不抛异常）
     */
    public static void acquire() {  // acquire：获取一次调用许可（并发 + QPS 双重限制）
        try {  // try
            SEM.acquire();  // 无条件阻塞等待并发许可（拿不到就一直等）
        } catch (InterruptedException e) {  // 捕获中断
            Thread.currentThread().interrupt();  // 恢复中断标志
            throw new RuntimeException("等待高德MCP并发许可被中断", e);  // 包成运行时异常抛出
        }  // catch 结束
        // 主动限速：按 QPS 取令牌（阻塞等待，避免并发瞬时打爆高德配额触发 CUQPS）
        if (!QPS_LIMITER.tryAcquire(30_000)) {  // 再按 QPS 取令牌（最长等 30 秒）
            SEM.release(); // 拿不到令牌则释放并发许可，防止许可泄漏  // 拿不到令牌要释放已占的并发许可，防泄漏
            throw new RuntimeException("高德MCP限速等待超时（AMAP_QPS=" + AMAP_QPS + "）");  // 抛超时异常（提示当前 QPS 配置）
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：释放一个高德 MCP 调用许可
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public static void release() {  // release：释放并发许可（任务结束必须调用）
        SEM.release();  // 信号量 +1
    }  // 方法结束

    /**
     * 作用：创建并初始化高德 MCP 客户端（阻塞直到初始化完成）
     * 参数：无
     * 返回值：已初始化的 McpClientWrapper
     * 异常：RuntimeException 连接或初始化失败时由 block() 抛出
     */
    public static McpClientWrapper createInitialized() {  // createInitialized：创建并初始化 MCP 客户端
        String key = ExternalServices.amapMcpKey();  // 取高德 MCP Key
        if (key.isBlank()) {  // Key 为空
            throw new IllegalStateException("缺少高德MCP key（管理端/Nacos credentials 或 AMAP_MCP_KEY/AMAP_KEY）");  // 抛异常（说明三个配置来源）
        }  // if 结束
        String url = ExternalServices.amapMcpUrl(key);  // 拼 MCP 服务地址（带 Key）
        McpClientWrapper mcp = McpClientBuilder.create("Amap-mcp")  // 创建客户端
                .streamableHttpTransport(url)   // 高德 mcp.amap.com/mcp 是 streamable HTTP(名含 sse 但握手走 streamable); sseTransport 会 30s 超时  // streamable HTTP 传输（用 sseTransport 会 30 秒超时）
                .timeout(Duration.ofSeconds(120))  // 超时 120 秒
                .buildAsync()  // 异步构建
                .block();  // 阻塞等握手完成
        if (mcp == null) {  // 创建失败
            throw new IllegalStateException("高德MCP客户端创建失败");  // 抛异常
        }  // if 结束
        mcp.initialize().block();  // 初始化 MCP 会话
        if (!mcp.isInitialized()) {  // 初始化未成功
            throw new IllegalStateException("高德MCP客户端初始化失败");  // 抛异常
        }  // if 结束
        System.out.println("[AmapMCP] 初始化成功，工具数=" + mcp.listTools().block().size());  // 打印初始化成功与工具数量
        //返回限流装饰器：工具调用走信号量并发控制(<=3)
        return new AmapRateLimitedMcpClientWrapper(mcp);  // 返回限流装饰器（调用走信号量并发控制）
    }  // 方法结束
}  // 类结束
