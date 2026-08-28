package utils.mcp;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import utils.AgentPromptStore;

import java.util.Map;

/** Delegates tool execution to the original tool while reading its description on every schema request. */
final class HotToolDescription {

    private HotToolDescription() {}

    static void wrap(Toolkit toolkit, String toolName, String promptKey, String defaultDescription) {
        AgentTool original = toolkit.getTool(toolName);
        if (original == null) return;
        toolkit.removeTool(toolName);
        toolkit.registerAgentTool(new AgentTool() {
            @Override
            public String getName() {
                return original.getName();
            }

            @Override
            public String getDescription() {
                return AgentPromptStore.prompt(promptKey, defaultDescription);
            }

            @Override
            public Map<String, Object> getParameters() {
                Map<String, Object> parameters = original.getParameters();
                return parameters == null ? Map.of() : parameters;
            }
            @Override
            public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {
                return original.callAsync(param);
            }
        });
    }
}
