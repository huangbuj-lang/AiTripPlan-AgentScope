package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import utils.ExternalServices;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * 高德 MCP 客户端工厂：创建并初始化高德客户端。
 * 高德官方 Streamable HTTP 地址：https://mcp.amap.com/mcp?key=你的key
 * key 从 Nacos credentials 读取，环境变量 AMAP_MCP_KEY/AMAP_KEY 作为兜底。
 *
 * 并发控制：高德个人认证 QPS=3，Semaphore 限流，
 * 同时最多3个高德请求在飞，超出自动排队等待，保证不触发高德限流。
 */
public class AmapMcpClientFactory {

    /**
     * 并发信号量：QPS=3；两个子服务共用同一个 key，
     * 各自进程内的信号量上限可由环境变量 AMAP_SEM_PERMITS 控制，部署时分配份额使总和≤3（如 8082=2、8085=1），
     * 避免跨进程总并发超过高德 QPS 阈值。默认为 3（单进程兜底）。
     */
    private static final int SEM_PERMITS = Integer.parseInt(System.getenv().getOrDefault("AMAP_SEM_PERMITS", "3"));
    private static final Semaphore SEM = new Semaphore(SEM_PERMITS);

    /** 调用高德MCP前申请许可证（无票则阻塞排队，保证并发≤3） */
    public static void acquire() {
        try {
            SEM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待高德MCP并发许可被中断", e);
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
                .streamableHttpTransport(url)
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
        // 返回限流装饰器：工具调用走信号量并发控制(≤3)
        return new AmapRateLimitedMcpClientWrapper(mcp);
    }
}
