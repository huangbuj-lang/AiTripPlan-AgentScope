package utils;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * Agent Tool 工具类
 */
public class ToolUtils {

    private final Toolkit toolkit ;

    /**
     * @param parallel 工具调用是否并行：
     *   - true（主管）：同一轮多个tool_calls并行执行（同时调路线+行程Agent，省A2A时间）
     *   - false（路线Agent）：地图工具串行执行——BaiduMapTool的MCP sessionId是static共享的，
     *     并行会导致多个请求挤同一MCP会话→魔塔服务端排队→超时（实测踩坑）
     */
    public ToolUtils(boolean parallel) {
        toolkit = new Toolkit(io.agentscope.core.tool.ToolkitConfig.builder().parallel(parallel).build());
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
