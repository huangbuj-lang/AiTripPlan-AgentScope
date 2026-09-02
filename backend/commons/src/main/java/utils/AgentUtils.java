package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
/**
 * ReActAgent 工具类
 */
@Slf4j
public class AgentUtils {

    /**
     * 构建主模型：DeepSeek（OpenAI 兼容接口）
     */
    private static io.agentscope.core.model.Model buildDeepSeekModel(String apiKey) {
        return OpenAIChatModel.builder()
                .apiKey(apiKey) // API Key
                .baseUrl(Config.get("MODEL_BASE_URL", "https://api.deepseek.com")) // DeepSeek OpenAI 兼容接口
                .modelName(Config.get("MODEL_NAME", "deepseek-v4-flash")) // 语言模型（快速版，支持工具调用）
                // DeepSeek 专用 formatter：修复 tool 消息与 tool_calls 的配对
                .formatter(new DeepSeekToolFixFormatter())
                // 关闭流式增量，保证完整输出
                .stream(false)
                .build();
    }

    /**
     * 构建备用模型：OpenAI 兼容接口（默认阿里 DashScope 兼容模式 qwen）。
     * URL/KEY/NAME 从 ModelConfigHolder 读（管理端可热更），支持任意 OpenAI 兼容服务。
     */
    private static io.agentscope.core.model.Model buildQwenModel(String apiKey) {
        var b = OpenAIChatModel.builder()
                .apiKey(apiKey) // API Key
                .baseUrl(utils.ModelConfigHolder.fbBaseUrl()) // OpenAI 兼容接口地址（可热更）
                .modelName(utils.ModelConfigHolder.fbModelName()) // 语言模型（可热更）
                // 关闭流式增量，保证完整输出
                .stream(false)
                .build();
        return b;
    }

    /**
     * 构建主/备模型，包上 SafeModel（限速+重试+熔断动态降级，降级判断下沉到每次调用）。
     * 供 ReAct Agent 与确定性编排器的裸 LLM 调用复用。
     */
    public static io.agentscope.core.model.Model buildModel() {
        // ★热更配置中心：主模型 URL/KEY/NAME 从 ModelConfigHolder 读（管理端保存即更新）
        String key = ModelConfigHolder.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "未配置模型 API Key！请在管理端「模型监控」页填写，或 .env 配置 MODEL_API_KEY");
        }
        String qwenKey = utils.ModelConfigHolder.fbApiKey(); // 备用模型 Key（热更中心，初始读 FALLBACK_MODEL_API_KEY/DASHSCOPE_API_KEY）

        io.agentscope.core.model.Model primary = buildPrimaryModel(
                ModelConfigHolder.baseUrl(), key, ModelConfigHolder.modelName());
        io.agentscope.core.model.Model fallback = (qwenKey != null && !qwenKey.isBlank())
                ? buildQwenModel(qwenKey)   // 备用模型：Qwen（可缺失）
                : null;
        return new SafeModel(primary, fallback); // 安全装饰器：限速+重试+熔断动态降级
    }

    /** 通用主模型构建（OpenAI 兼容接口，URL/KEY/NAME 可热更） */
    private static io.agentscope.core.model.Model buildPrimaryModel(String baseUrl, String apiKey, String modelName) {
        boolean isDeepSeek = baseUrl != null && baseUrl.contains("deepseek");
        var b = io.agentscope.core.model.OpenAIChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(false); // 关闭流式增量，保证完整输出
        if (isDeepSeek) {
            b.formatter(new DeepSeekToolFixFormatter()); // 仅 DeepSeek 需要 tool 配对修复
        }
        return b.build();
    }

    /**
     * 创建 ReAct Agent Builder
     */
    public static ReActAgent.Builder getReActAgentBuilder(
            String name,
            String description
    ) {

        io.agentscope.core.model.Model model = buildModel(); // 主/备模型（SafeModel 装饰）

        return ReActAgent.builder()
                        .name(name)
                        .description(description)
                        .sysPrompt(AgentPrompts.genericReActSystem(name))
                        // 设置最大迭代轮数，避免复杂任务被截断
                        .maxIters(Config.getInt("MODEL_MAX_ITERS", 30))
                        .model(model)
                        ;

    }

    /**
     * ReAct Agent 流式响应
     */
    public static Flux<Event> streamResponse(
            AgentBase agent,
            String prompt) {

       return agent.stream(
                        // Prompt
                        Msg.builder()
                                .role(MsgRole.USER) // 消息角色
                                .content(List.of( // 消息内容
                                        TextBlock.builder()
                                                .text(prompt)
                                                .build()
                                ))
                                .build()
                );

    }

}
