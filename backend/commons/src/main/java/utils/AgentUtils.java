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
@Slf4j  // @Slf4j：注入日志对象
public class AgentUtils {  // 类声明：ReActAgent 构建工具

    /**
     * 作用：构建 DeepSeek 主模型（OpenAI 兼容接口 + DeepSeek 专用 tool 消息配对修复器，关闭流式增量保证完整输出）
     * 参数：apiKey DeepSeek API Key
     * 返回值：Model 实例
     * 异常：无（构建失败在调用时以下游异常暴露）
     */
    private static io.agentscope.core.model.Model buildDeepSeekModel(String apiKey) {  // buildDeepSeekModel：构建 DeepSeek 主模型
        return OpenAIChatModel.builder()  // OpenAIChatModel.builder() 开始
                .apiKey(apiKey) // API Key  // API Key
                .baseUrl(Config.get("MODEL_BASE_URL", "https://api.deepseek.com")) // DeepSeek OpenAI 兼容接口  // OpenAI 兼容接口地址（可配置）
                .modelName(Config.get("MODEL_NAME", "deepseek-v4-flash")) // 语言模型（快速版，支持工具调用）  // 模型名（默认 deepseek-v4-flash）
                // DeepSeek 专用 formatter：修复 tool 消息与 tool_calls 的配对
                .formatter(new DeepSeekToolFixFormatter())  // DeepSeek 专用 formatter：修复 tool 消息与 tool_calls 配对
                // ★2026-09-11 输出上限：同 buildPrimaryModel（防长行程JSON被默认4096截断）
                .generateOptions(io.agentscope.core.model.GenerateOptions.builder()  // 生成参数
                        .maxTokens(Config.getInt("LLM_MAX_OUTPUT_TOKENS", 8192))  // 输出上限（默认 8192，防长行程 JSON 被 4096 截断）
                        .build())  // build 参数
                // 关闭流式增量，保证完整输出
                .stream(false)  // 关闭流式增量，保证完整输出
                .build();  // 构建模型
    }  // 方法结束

    /**
     * 作用：构建备用模型（默认阿里 DashScope 兼容接口，URL/名称可热更）
     * 参数：apiKey 备用模型 API Key
     * 返回值：Model 实例
     * 异常：无
     */
    private static io.agentscope.core.model.Model buildQwenModel(String apiKey) {  // buildQwenModel：构建备用模型（DashScope 兼容模式）
        var b = OpenAIChatModel.builder()  // builder 开始
                .apiKey(apiKey) // API Key  // API Key
                .baseUrl(utils.ModelConfigHolder.fbBaseUrl()) // OpenAI 兼容接口地址（可热更）  // 兼容接口地址（可热更）
                .modelName(utils.ModelConfigHolder.fbModelName()) // 语言模型（可热更）  // 模型名（可热更）
                // ★2026-09-11 输出上限：备用模型也要设，否则熔断降级后照样被默认上限截断
                .generateOptions(io.agentscope.core.model.GenerateOptions.builder()  // 生成参数
                        .maxTokens(Config.getInt("LLM_MAX_OUTPUT_TOKENS", 8192))  // 输出上限（备用也要设，否则降级后照样被截断）
                        .build())  // build 参数
                // 关闭流式增量，保证完整输出
                .stream(false)  // 关闭流式增量
                .build();  // 构建模型
        return b;  // 返回
    }  // 方法结束

    /**
     * 作用：构建主/备模型并用 SafeModel 包裹（限速+重试+熔断动态降级），是进程内构建模型的唯一入口
     * 参数：无
     * 返回值：SafeModel 实例（备用模型的 Key 缺失时不带备用）
     * 异常：IllegalStateException 主模型 API Key 未配置时抛出
     */
    public static io.agentscope.core.model.Model buildModel() {  // buildModel：构建主/备模型并用 SafeModel 包裹（进程内唯一入口）
        // ★热更配置中心：主模型 URL/KEY/NAME 从 ModelConfigHolder 读（管理端保存即更新）
        String key = ModelConfigHolder.apiKey();  // key：主模型 Key（从热更中心读）
        if (key == null || key.isBlank()) {  // Key 未配置
            throw new IllegalStateException(  // 抛异常：提示去管理端填写
                    "未配置模型 API Key！请在管理端「模型监控」页填写，或 .env 配置 MODEL_API_KEY");  // 续行
        }  // if 结束
        String qwenKey = utils.ModelConfigHolder.fbApiKey(); // 备用模型 Key（热更中心，初始读 FALLBACK_MODEL_API_KEY/DASHSCOPE_API_KEY）  // qwenKey：备用模型 Key（可缺失）

        io.agentscope.core.model.Model primary = buildPrimaryModel(  // primary：通用主模型（
                ModelConfigHolder.baseUrl(), key, ModelConfigHolder.modelName());  // 续：地址/Key/名称都来自热更中心）
        io.agentscope.core.model.Model fallback = (qwenKey != null && !qwenKey.isBlank())  // fallback：备用模型
                ? buildQwenModel(qwenKey)   // 备用模型：Qwen（可缺失）  // 有 Key 才构建
                : null;  // 否则为 null
        return new SafeModel(primary, fallback); // 安全装饰器：限速+重试+熔断动态降级  // 返回 SafeModel（限速 + 重试 + 熔断降级）
    }  // 方法结束

    /**
     * 作用：通用主模型构建（OpenAI 兼容接口，URL/Key/名称可热更；DeepSeek 域名自动挂配对修复器）
     * 参数：baseUrl 接口地址；apiKey API Key；modelName 模型名称
     * 返回值：Model 实例
     * 异常：无
     */
    private static io.agentscope.core.model.Model buildPrimaryModel(String baseUrl, String apiKey, String modelName) {  // buildPrimaryModel：通用主模型构建
        boolean isDeepSeek = baseUrl != null && baseUrl.contains("deepseek");  // 是否是 DeepSeek 域名
        var b = io.agentscope.core.model.OpenAIChatModel.builder()  // b：builder
                .baseUrl(baseUrl)  // 接口地址
                .apiKey(apiKey)  // API Key
                .modelName(modelName)  // 模型名
                // ★2026-09-11 显式设输出上限（评审点：输出被截断 → JSON 不完整 → 报"行程数据缺失"）。
                //   原来完全不设 → 走服务端默认（DeepSeek 默认输出 4096 tokens），
                //   而一份完整行程 JSON 常达 8000+ 字符（中文≈1.5 token/字）→ 必被截断。
                //   可用 LLM_MAX_OUTPUT_TOKENS 覆盖。（agentscope 的模型级默认值入口是 defaultOptions）
                .generateOptions(io.agentscope.core.model.GenerateOptions.builder()  // 显式设输出上限（不设会用服务端默认 4096）
                        .maxTokens(Config.getInt("LLM_MAX_OUTPUT_TOKENS", 8192))  // maxTokens 从配置读（默认 8192）
                        .build())  // build 参数
                .stream(false); // 关闭流式增量，保证完整输出  // 关闭流式增量（否则 JSON 会被切成增量片段）
        if (isDeepSeek) {  // 是 DeepSeek
            b.formatter(new DeepSeekToolFixFormatter()); // 仅 DeepSeek 需要 tool 配对修复  // 挂 tool 配对修复器
        }  // if 结束
        return b.build();  // 构建并返回
    }  // 方法结束

    /**
     * 作用：创建 ReAct Agent 构建器：绑定主/备模型、通用系统提示词与最大迭代轮数
     * 参数：name Agent 名称；description Agent 描述
     * 返回值：配置好的 ReActAgent.Builder
     * 异常：IllegalStateException 未配置模型 Key 时由 buildModel 抛出
     */
    public static ReActAgent.Builder getReActAgentBuilder(  // getReActAgentBuilder：创建 ReAct Agent 构建器
            String name,  // name：Agent 名
            String description  // description：Agent 描述
    ) {  // 签名结束

        io.agentscope.core.model.Model model = buildModel(); // 主/备模型（SafeModel 装饰）  // model：主/备模型（SafeModel 装饰）

        return ReActAgent.builder()  // ReActAgent.builder() 开始
                        .name(name)  // 设置名称
                        .description(description)  // 设置描述
                        .sysPrompt(AgentPrompts.genericReActSystem(name))  // 通用系统提示词
                        // 设置最大迭代轮数，避免复杂任务被截断
                        .maxIters(Config.getInt("MODEL_MAX_ITERS", 30))  // 最大迭代轮数（默认 30，防复杂任务被截断）
                        .model(model)  // 绑定模型
                        ;  // 构建器声明结束（分号）

    }  // 方法结束

    /**
     * 作用：以流式方式向 Agent 发送一次用户消息
     * 参数：agent 目标 Agent；prompt 用户提示词
     * 返回值：事件流 Flux<Event>
     * 异常：无（异常以错误流形式下发）
     */
    public static Flux<Event> streamResponse(  // streamResponse：向 Agent 发一次用户消息（流式）
            AgentBase agent,  // agent：目标 Agent
            String prompt) {  // prompt：用户提示词

       return agent.stream(  // 调用 agent.stream 并返回事件流
                        // Prompt
                        Msg.builder()  // 构造一条用户消息
                                .role(MsgRole.USER) // 消息角色  // 角色 USER
                                .content(List.of( // 消息内容  // 消息内容块
                                        TextBlock.builder()  // 文本块
                                                .text(prompt)  // 文本正文 = prompt
                                                .build()  // 构造文本块
                                ))  // 内容列表结束
                                .build()  // 消息构造结束
                );  // stream 调用结束

    }  // 方法结束

}  // 类结束
