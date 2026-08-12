package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import utils.DeepSeekToolFixFormatter;
import jakarta.annotation.Resource;
import org.springframework.core.env.PropertyResolver;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * ReActAgent 工具类
 */
public class AgentUtils {

    /**
     * 创建ReAct Agent Builder
     */
    public static ReActAgent.Builder getReActAgentBuilder(
            String name,
            String description
    ) {

        // API Key 唯一来源：环境变量 DEEPSEEK_API_KEY（.env / export 注入）
        // ★安全要求：绝不硬编码 key 到代码（曾有内置key被终端脱敏截断导致全崩的教训）
        // 缺失时直接抛异常，让启动立即失败并提示——而不是带着坏 key 静默跑、运行时才炸
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置 DEEPSEEK_API_KEY 环境变量！请先执行: export DEEPSEEK_API_KEY=你的key，再启动 Agent");
        }

        return ReActAgent.builder()
                        .name(name)
                        .description(description)
                        // ★中文化系统提示词：DeepSeek 默认用英文思考（其训练偏好），
                        //  覆盖 AgentScope 英文内置 sysPrompt，让思考与回答全程中文
                        .sysPrompt("你是" + name + "，一个专业的中文AI助手。"
                                + "请始终使用中文进行思考、推理和回答，除非用户明确要求使用其他语言。"
                                + "你的目标是高质量完成用户交给你的任务。")
                        .model(OpenAIChatModel.builder()
                                .apiKey(apiKey) // API Key（仅环境变量，无内置兜底）
                                .baseUrl("https://api.deepseek.com") // DeepSeek OpenAI兼容接口
                                .modelName("deepseek-v4-flash") // 语言模型（v4-flash：快速版，支持工具调用）
                                // DeepSeek 专用 formatter：修复 tool 消息必须紧跟 tool_calls 的协议要求
                                // （不设置会报 400: Messages with role 'tool' must be a response to preceding tool_calls）
                                .formatter(new DeepSeekToolFixFormatter())
                                // 关闭流式增量：stream(true)时非流式调用只拿第一个增量块，
                                // 模型只回"请稍等"不调工具；false=完整输出，ReAct循环正常
                                .stream(false)
                                .build())
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
