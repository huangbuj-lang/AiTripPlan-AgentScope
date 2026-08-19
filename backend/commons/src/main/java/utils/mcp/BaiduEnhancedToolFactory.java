package utils.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * ★百度地图工具装配工厂：一次调用把「标准MCP客户端 + 语义化增强工具」都挂上。
 * 模型只用语义化工具(query_*，传城市名即可)，增强工具内部用MCP客户端做"地名→坐标"转换。
 */
public class BaiduEnhancedToolFactory {

    /** MCP客户端（单例复用，避免每次重建连接） */
    private static volatile McpClientWrapper MCP;

    /**
     * 注册到给定Toolkit：挂MCP客户端 + 增强工具
     */
    public static void register(Toolkit toolkit) {
        McpClientWrapper mcp = getMcp();
        toolkit.registerMcpClient(mcp).block();          // 标准MCP客户端(10工具)
        toolkit.registration().tool(new BaiduMapEnhancedTool(mcp)).apply(); // 语义化增强(6工具,人名友好)
    }

    private static McpClientWrapper getMcp() {
        if (MCP == null) {
            synchronized (BaiduEnhancedToolFactory.class) {
                if (MCP == null) {
                    MCP = BaiduMcpClientFactory.createInitialized();
                }
            }
        }
        return MCP;
    }
}
