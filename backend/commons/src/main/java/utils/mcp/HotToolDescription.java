package utils.mcp;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import utils.AgentPromptStore;

import java.util.Map;

/** Delegates tool execution to the original tool while reading its description on every schema request. */
final class HotToolDescription {  // 类声明：工具描述热更包装器（包内可见）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private HotToolDescription() {}  // 私有构造：禁止 new
    /**
     * 作用：把已注册的工具替换成"描述可热更"的包装版：描述从 AgentPromptStore 实时读取，其余行为转发给原工具
     * 参数：toolkit Agent 工具包；toolName 要包装的工具名；promptKey 描述对应的提示词键；defaultDescription 提示词缺失时的默认描述
     * 返回值：无
     * 异常：无（工具不存在时直接返回，不做替换）
     */

    static void wrap(Toolkit toolkit, String toolName, String promptKey, String defaultDescription) {  // wrap：把工具替换成描述可热更的包装版
        AgentTool original = toolkit.getTool(toolName);  // 取原工具
        if (original == null) return;  // 不存在直接返回
        toolkit.removeTool(toolName);  // 先移除原工具
        toolkit.registerAgentTool(new AgentTool() {  // 注册包装版（匿名 AgentTool）
            /**
             * 作用：返回被包装工具的原始名称
             * 参数：无
             * 返回值：工具名
             * 异常：无
             */
            @Override  // @Override 取工具名
            public String getName() {  // getName：
                return original.getName();  // 返回原工具名
            }  // 方法结束
            /**
             * 作用：返回可热更的工具描述（每次取都读最新提示词）
             * 参数：无
             * 返回值：工具描述文本
             * 异常：无
             */
            @Override  // @Override 取工具描述
            public String getDescription() {  // getDescription：
                return AgentPromptStore.prompt(promptKey, defaultDescription);  // 每次实时读提示词（Nacos 改了立刻生效）
            }  // 方法结束
            /**
             * 作用：返回被包装工具的参数定义
             * 参数：无
             * 返回值：参数 Map；原工具无参数时返回空 Map
             * 异常：无
             */
            @Override  // @Override 取参数定义
            public Map<String, Object> getParameters() {  // getParameters：
                Map<String, Object> parameters = original.getParameters();  // 转发给原工具
                return parameters == null ? Map.of() : parameters;  // null 归一为空 Map
            }  // 方法结束
            /**
             * 作用：调用被包装工具的实际实现
             * 参数：param 工具调用参数
             * 返回值：工具结果流
             * 异常：无（异常由原工具以错误结果形式返回）
             */
            @Override  // @Override 调用工具
            public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {  // callAsync：
                return original.callAsync(param);  // 实际执行转发给原工具
            }  // 方法结束
        });  // 匿名类结束并注册
    }  // 方法结束
}  // 类结束
