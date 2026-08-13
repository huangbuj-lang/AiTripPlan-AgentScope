package utils;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * Agent Tool 工具类
 */
public class ToolUtils {

    private final Toolkit toolkit ;

    /**
     * 构造工具包（parallel 为 true 时工具调用并行执行）
     */
    public ToolUtils(boolean parallel) {
        toolkit = new Toolkit(io.agentscope.core.tool.ToolkitConfig.builder().parallel(parallel).build());
    }

    /**
     * 获取工具包（注册普通工具）
     */
    public Toolkit getToolkit(Object tool) {

        toolkit.registerTool(tool); // 注册工具（自动扫描 @Tool 注解方法）

        return toolkit;
    }

    /**
     * 获取工具包（注册 MCP 客户端工具）
     */
    public Toolkit getToolkit(McpClientWrapper mcp) {

        toolkit.registerMcpClient(mcp).block(); // 注册 MCP 服务端工具

        return toolkit;
    }
}
