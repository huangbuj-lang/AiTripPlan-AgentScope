package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
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
     * 构建备用模型：Qwen（DashScope）
     */
    private static io.agentscope.core.model.Model buildQwenModel(String apiKey) {
        return DashScopeChatModel.builder()
                .apiKey(apiKey) // API Key
                .modelName(Config.get("FALLBACK_MODEL_NAME", "qwen3.7-max")) // 语言模型
                // 关闭思考模式，加快响应
                .enableThinking(false)
                // 关闭流式增量，保证完整输出
                .stream(false)
                .build();
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
        String qwenKey = System.getenv("DASHSCOPE_API_KEY"); // 备用模型 Key（可缺失）

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
                        // 中文化系统提示词：强制中文思考与完整详细输出
                        .sysPrompt("你是" + name + "，一个专业的中文AI助手。"
                                + "请始终使用中文进行思考、推理和回答，除非用户明确要求使用其他语言。"
                                + "你的目标是高质量完成用户交给你的任务。"
                                + "★最终输出铁律：你的最终回复【必须】是完整、详细、可直接阅读的完整计划/完整答案，"
                                + "包含每一天的具体安排、具体景点名、具体价格、具体酒店餐厅名。"
                                + "【绝对禁止】只输出\"已完成\"\"总结\"\"总览\"\"核心成果\"等收尾性概述——"
                                + "那些是总结不是计划，用户看不到你的子任务结果，只看到你的最终回复。"
                                + "输出前自查：如果最终回复里没有具体的日程细节，必须展开为完整计划再输出。"
                                + "★开头禁止引导句：最终回复【直接】从计划正文开始（如\"路线：...\"\"第一天：...\"），"
                                + "【绝对禁止】开头写\"我已加载...\"\"我已核对...\"\"以下是为您整理...\""
                                + "\"计划已制定完毕/已完成\"\"最终完整方案\"等任何过程叙述或引导语——"
                                + "那些话占用篇幅且用户不需要，直接给内容。")
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
