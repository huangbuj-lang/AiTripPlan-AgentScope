package utils;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/** Agent Tool 工具类 */
public class ToolUtils {  // 类声明：Agent 工具包构建器

    private final Toolkit toolkit ;  // toolkit 字段：内部持有的 Toolkit 实例

    /**
     * 作用：构造工具包构建器
     * 参数：parallel 是否允许工具并行执行
     * 返回值：无
     * 异常：无
     */
    public ToolUtils(boolean parallel) {  // 构造器：按是否允许工具并行创建 Toolkit
        toolkit = new Toolkit(io.agentscope.core.tool.ToolkitConfig.builder().parallel(parallel).build());  // ToolkitConfig.builder().parallel(parallel) 构造配置
    }  // 构造器结束

    /**
     * 作用：把普通工具对象注册进新的 Toolkit（重载一）
     * 参数：tool 工具实例
     * 返回值：Toolkit
     * 异常：无
     */
    public Toolkit getToolkit(Object tool) {  // getToolkit（重载一）：注册普通工具对象

        toolkit.registerTool(tool); // 注册工具（自动扫描 @Tool 注解方法）  // registerTool：扫描该对象的 @Tool 注解方法并注册

        return toolkit;  // 返回同一个 toolkit
    }  // 方法结束（重载一）

    /**
     * 作用：把 MCP 客户端注册进新的 Toolkit（重载二）
     * 参数：mcp MCP 客户端
     * 返回值：Toolkit
     * 异常：无
     */
    public Toolkit getToolkit(McpClientWrapper mcp) {  // getToolkit（重载二）：注册 MCP 客户端

        toolkit.registerMcpClient(mcp).block(); // 注册 MCP 服务端工具  // registerMcpClient(mcp).block()：阻塞等待 MCP 工具列表拉取完成

        return toolkit;  // 返回同一个 toolkit
    }  // 方法结束（重载二）
}  // 类结束

