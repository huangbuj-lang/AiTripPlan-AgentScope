package utils;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
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

        String apiKey = System.getenv("DASHSCOPE_API_KEY"); // 优先从环境变量读取API Key
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "sk-REPLACED"; // 环境变量未设置 → 用代码内置的key
        }

        return ReActAgent.builder()
                        .name(name)
                        .description(description)
                        .model(DashScopeChatModel.builder()
                                .apiKey(apiKey) // API Key（环境变量优先，兜底内置）
                                .modelName("qwen3.7-max") // 语言模型（qwen3.7-max：比qwen3-max新，兼容原生API）
                                // 关闭思考模式：qwen3.7-max默认thinking每轮慢3倍(50秒vs18秒)，
                                // ReAct多轮下轮次耗尽任务失败；false=直接回答，快3倍
                                .enableThinking(false)
                                // 关闭流式增量：stream(true)时非流式调用只拿第一个增量块，
                                // 模型只回"请稍等"不调工具；false=完整输出，ReAct循环正常
                                //
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
