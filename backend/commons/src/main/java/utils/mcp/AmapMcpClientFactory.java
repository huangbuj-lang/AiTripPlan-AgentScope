package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import utils.ExternalServices;
import utils.RateLimiter;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/** 高德 MCP 客户端工厂：创建并初始化高德客户端。 */
public class AmapMcpClientFactory {

    /** 并发信号量：限制同时进行中的请求数（默认3） */
    private static final int SEM_PERMITS = Integer.parseInt(System.getenv().getOrDefault("AMAP_SEM_PERMITS", "3"));
    private static final Semaphore SEM = new Semaphore(SEM_PERMITS);

    /** 高德 QPS 主动限速令牌桶：速率=AMAP_QPS/s（默认3，对齐高德个人key配额）。
     *  并发数由 SEM 控制，时间速率由令牌桶控制——两者叠加，防瞬间并发打爆 QPS 配额。 */
    private static final double AMAP_QPS = Double.parseDouble(System.getenv().getOrDefault("AMAP_QPS", "3"));
    private static final RateLimiter QPS_LIMITER = RateLimiter.create(AMAP_QPS);

    /** 调用高德MCP前申请许可证（无票则阻塞排队，保证并发<=3 且 速率<=AMAP_QPS/s） */
    public static void acquire() {
        try {
            SEM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待高德MCP并发许可被中断", e);
        }
        // 主动限速：按 QPS 取令牌（阻塞等待，避免并发瞬时打爆高德配额触发 CUQPS）
        if (!QPS_LIMITER.tryAcquire(30_000)) {
            SEM.release(); // 拿不到令牌则释放并发许可，防止许可泄漏
            throw new RuntimeException("高德MCP限速等待超时（AMAP_QPS=" + AMAP_QPS + "）");
        }
    }

    /** 调用完释放许可证（必须finally调用，保证不卡死） */
    public static void release() {
        SEM.release();
    }

    /** 创建并初始化高德MCP客户端（阻塞直到就绪），返回带限流装饰的客户端 */
    public static McpClientWrapper createInitialized() {
        String key = ExternalServices.amapMcpKey();
        if (key.isBlank()) {
            throw new IllegalStateException("缺少高德MCP key（管理端/Nacos credentials 或 AMAP_MCP_KEY/AMAP_KEY）");
        }
        String url = ExternalServices.amapMcpUrl(key);
        McpClientWrapper mcp = McpClientBuilder.create("Amap-mcp")
                .streamableHttpTransport(url)   // 高德 mcp.amap.com/mcp 是 streamable HTTP(名含 sse 但握手走 streamable); sseTransport 会 30s 超时
                .timeout(Duration.ofSeconds(120))
                .buildAsync()
                .block();
        if (mcp == null) {
            throw new IllegalStateException("高德MCP客户端创建失败");
        }
        mcp.initialize().block();
        if (!mcp.isInitialized()) {
            throw new IllegalStateException("高德MCP客户端初始化失败");
        }
        System.out.println("[AmapMCP] 初始化成功，工具数=" + mcp.listTools().block().size());
        //返回限流装饰器：工具调用走信号量并发控制(<=3)
        return new AmapRateLimitedMcpClientWrapper(mcp);
    }
}
