package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * ★高德地图MCP客户端工厂：用框架标准 McpClientBuilder 创建并初始化高德MCP客户端。
 * 高德官方 Streamable HTTP 地址：https://mcp.amap.com/mcp?key=你的key
 * key 从环境变量 AMAP_MCP_KEY 读取（不硬编码进源码）。
 *
 * ★并发控制：高德 API 个人认证 QPS=3（每秒最多3次）。用 Semaphore 限流，
 * 同时最多3个高德请求在飞，超出自动排队等待，保证不触发高德限流。
 */
public class AmapMcpClientFactory {

    /** 高德MCP端点（key通过查询参数） */
    private static final String AMAP_MCP_BASE = "https://mcp.amap.com/mcp";

    /**
     * 并发信号量：高德个人认证 QPS=3。★跨进程共享治理：两个子服务(8082/8085)共用同一个 key，
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
        String key = System.getenv("AMAP_MCP_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("AMAP_KEY"); // 兜底用已有高德Web key
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("缺少高德MCP key（环境变量 AMAP_MCP_KEY 或 AMAP_KEY）");
        }
        String url = AMAP_MCP_BASE + "?key=" + key;
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
        // ★返回限流装饰器：所有工具调用自动走信号量并发控制(≤3)
        return new AmapRateLimitedMcpClientWrapper(mcp);
    }
}
