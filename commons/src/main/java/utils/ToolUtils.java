package utils;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * Agent Tool 工具类
 */
public class ToolUtils {

    private final Toolkit toolkit ;

    public ToolUtils() {
        toolkit = new Toolkit(); // 创建工具包
    }

    /**
     * 获取工具包（注册普通工具）
     */
    public Toolkit getToolkit(Object tool) {

        toolkit.registerTool(tool); // 自动扫描@Tool注解的方法注册为Agent的工具

        return toolkit;
    }

    /**
     * 获取工具包（注册MCP客户端工具）
     */
    public Toolkit getToolkit(McpClientWrapper mcp) {

        toolkit.registerMcpClient(mcp).block(); // 把MCP服务端的所有工具添加到工具包

        return toolkit;
    }
}
