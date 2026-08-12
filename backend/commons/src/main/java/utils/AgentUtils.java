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
     * 构建主模型：DeepSeek（OpenAI兼容接口）
     */
    private static io.agentscope.core.model.Model buildDeepSeekModel(String apiKey) {
        return OpenAIChatModel.builder()
                .apiKey(apiKey) // API Key（仅环境变量，无内置兜底）
                .baseUrl("https://api.deepseek.com") // DeepSeek OpenAI兼容接口
                .modelName("deepseek-v4-flash") // 语言模型（v4-flash：快速版，支持工具调用）
                // DeepSeek 专用 formatter：修复 tool 消息必须紧跟 tool_calls 的协议要求
                // （不设置会报 400: Messages with role 'tool' must be a response to preceding tool_calls）
                .formatter(new DeepSeekToolFixFormatter())
                // 关闭流式增量：stream(true)时非流式调用只拿第一个增量块，
                // 模型只回"请稍等"不调工具；false=完整输出，ReAct循环正常
                .stream(false)
                .build();
    }

    /**
     * 构建备用模型：Qwen（DashScope）——主模型熔断时降级用
     */
    private static io.agentscope.core.model.Model buildQwenModel(String apiKey) {
        return DashScopeChatModel.builder()
                .apiKey(apiKey) // API Key（仅环境变量，无内置兜底）
                .modelName("qwen3.7-max") // 语言模型（qwen3.7-max：比qwen3-max新，兼容原生API）
                // 关闭思考模式：qwen3.7-max默认thinking每轮慢3倍(50秒vs18秒)，
                // ReAct多轮下轮次耗尽任务失败；false=直接回答，快3倍
                .enableThinking(false)
                // 关闭流式增量（同DeepSeek：false=完整输出，ReAct循环正常）
                .stream(false)
                .build();
    }

    /**
     * 创建ReAct Agent Builder
     */
    public static ReActAgent.Builder getReActAgentBuilder(
            String name,
            String description
    ) {

        // API Key：主模型 DeepSeek（DEEPSEEK_API_KEY）+ 备用模型 Qwen（DASHSCOPE_API_KEY）
        // ★安全要求：绝不硬编码 key 到代码（曾有内置key被终端脱敏截断导致全崩的教训）
        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        if (deepseekKey == null || deepseekKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置 DEEPSEEK_API_KEY 环境变量！请先执行: export DEEPSEEK_API_KEY=你的key，再启动 Agent");
        }
        String qwenKey = System.getenv("DASHSCOPE_API_KEY"); // 备用模型key（熔断降级用，可缺失：缺失则熔断后报错而不是偷偷用坏key）

        // ★熔断降级：主模型 DeepSeek 连续失败触发熔断后，自动改用备用模型 Qwen
        //  (CircuitBreaker.isOpen()=true 表示熔断中/半开试探中)
        boolean useFallback = CircuitBreaker.getInstance().isOpen() && qwenKey != null && !qwenKey.isBlank();
        if (useFallback) {
            log.warn("★★★ 熔断器已触发：主模型DeepSeek连续失败，降级使用备用模型 Qwen(qwen3.7-max)");
        }

        // 构建模型：主=DeepSeek(OpenAI兼容)，备=Qwen(DashScope)；统一包上 SafeModel（限速+重试+熔断上报）
        io.agentscope.core.model.Model model = useFallback
                ? buildQwenModel(qwenKey)   // 熔断降级：Qwen
                : buildDeepSeekModel(deepseekKey); // 正常：DeepSeek
        model = new SafeModel(model); // 三层防护：限速(3次/秒) + 429重试(指数退避) + 失败上报熔断器

        return ReActAgent.builder()
                        .name(name)
                        .description(description)
                        // ★中文化系统提示词：DeepSeek 默认用英文思考（其训练偏好），
                        //  覆盖 AgentScope 英文内置 sysPrompt，让思考与回答全程中文
                        // ★★★最终输出铁律：DeepSeek 实测会把"完成总结"当最终回复（云南14日任务踩坑：
                        //  只输出完成情况总览表格，没有详细计划）。必须在 sysPrompt 里强制要求。
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
                        // ★maxIters=30：AgentScope 默认只有10轮，复杂任务(加载技能→建计划→A2A→多个子任务→整合)
                        //  轻松超10轮 → 模型被强制截断，输出"已达最大迭代限制"+残缺结果（郑州/广州任务实测踩坑）
                        .maxIters(30)
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
