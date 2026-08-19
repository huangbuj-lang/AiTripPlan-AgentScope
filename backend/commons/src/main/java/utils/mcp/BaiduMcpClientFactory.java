package utils.mcp;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.time.Duration;

/**
 * 百度地图MCP客户端工厂：用框架标准 McpClientBuilder 创建并初始化MCP客户端。
 * ★标准MCP接线：不再手写HTTP，由框架管理会话/协议/工具注册。
 */
public class BaiduMcpClientFactory {

    /**
     * 创建并初始化百度地图MCP客户端（阻塞直到就绪）
     * @return 已初始化的McpClientWrapper
     */
    public static McpClientWrapper createInitialized() {
        String url = System.getenv("BAIDU_MCP_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("缺少环境变量 BAIDU_MCP_URL");
        }
        McpClientWrapper mcp = McpClientBuilder.create("BaiduMap-mcp")
                .streamableHttpTransport(url)
                .timeout(Duration.ofSeconds(120))
                .buildAsync()
                .block();
        if (mcp == null) {
            throw new IllegalStateException("百度地图MCP客户端创建失败");
        }
        mcp.initialize().block(); // 初始化握手（含会话建立）
        if (!mcp.isInitialized()) {
            throw new IllegalStateException("百度地图MCP客户端初始化失败");
        }
        System.out.println("[BaiduMCP] 初始化成功，工具数=" + mcp.listTools().block().size());
        return mcp;
    }
}
