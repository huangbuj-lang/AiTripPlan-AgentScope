package utils;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 项目统一大模型客户端（唯一出口）： - 所有裸 LLM 调用（解析/分类/聚合/提取等）都走这里，改模型只动这一处 - 底层复用 AgentUtils.buildModel()（读 .env 的 MODEL_BASE_URL/MODEL_NAME/MODEL_API_KEY） */
public final class LlmClient {  // 类声明：项目统一大模型客户端（所有裸 LLM 调用唯一出口）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private LlmClient() {}  // 私有构造：禁止 new

    private static volatile Model sharedModel;  // sharedModel：全局共享模型单例（volatile 保证可见性）

    /**
     * 作用：获取全局共享的模型单例（双重检查锁懒加载），所有裸 LLM 调用共用同一个已包限速/重试/熔断的模型实例
     * 参数：无
     * 返回值：Model 实例（内部由 AgentUtils.buildModel() 用 SafeModel 包裹主/备模型）
     * 异常：模型构建失败时抛 IllegalStateException（如未配置 MODEL_API_KEY），此处不捕获
     */
    public static Model model() {  // model()：取共享模型（双重检查锁懒加载）
        if (sharedModel == null) {  // 还没构建
            synchronized (LlmClient.class) {  // 类级锁
                if (sharedModel == null) {  // 锁内再判一次
                    sharedModel = AgentUtils.buildModel();  // 构建（内部已用 SafeModel 包限速/重试/熔断）
                }  // if 结束
            }  // synchronized 结束
        }  // if 结束
        return sharedModel;  // 返回单例
    }  // 方法结束

    /**
     * 作用：同步调用一次大模型：把系统提示词与用户提示词发出去，攒齐所有文本块后一次性返回
     * 参数：sysPrompt 系统提示词（角色/规则）；userPrompt 用户输入；jsonMode 为 true 时附加 response_format=json_object 要求输出 JSON；timeoutMillis 阻塞等待的毫秒上限
     * 返回值：模型输出的纯文本（TextBlock 拼接）；超时、返回 null 或任何调用异常时返回空串
     * 异常：无（方法内部 catch 全部异常并返回空串，不向上抛出）
     */
    public static String chat(String sysPrompt, String userPrompt, boolean jsonMode, long timeoutMillis) {  // chat：同步调一次模型，攒齐文本块返回
        Msg system = Msg.builder().role(MsgRole.SYSTEM)  // 构造 system 消息
                .content(TextBlock.builder().text(sysPrompt).build()).build();  // 续：内容为一个文本块
        Msg user = Msg.builder().role(MsgRole.USER)  // 构造 user 消息
                .content(TextBlock.builder().text(userPrompt).build()).build();  // 续：内容为一个文本块
        GenerateOptions.Builder ob = GenerateOptions.builder();  // ob：生成参数构建器
        if (jsonMode) {  // 要 JSON 模式
            ob.additionalBodyParams(Map.of("response_format", Map.of("type", "json_object")));  // 附加 response_format=json_object（约束模型输出 JSON）
        }  // if 结束
        try {  // try
            ChatResponse resp = model().stream(List.of(system, user), null, ob.build())  // 流式调用（system + user 两条消息）
                    .blockLast(Duration.ofMillis(timeoutMillis));  // 阻塞到流结束，最长等 timeoutMillis
            if (resp == null) return "";  // 没有响应返回空串
            StringBuilder sb = new StringBuilder();  // sb：文本拼接缓冲
            for (io.agentscope.core.message.ContentBlock block : resp.getContent()) {  // 遍历返回的内容块
                if (block instanceof io.agentscope.core.message.TextBlock tb) {  // 是文本块
                    sb.append(tb.getText());  // 追加文本
                }  // if 结束
            }  // for 结束
            return sb.toString();  // 返回拼接结果
        } catch (Exception e) {  // 捕获异常
            return "";  // 任何异常都返回空串（不向上抛）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：热更后丢弃缓存的模型实例，使下一次 model() 按 ModelConfigHolder 的新配置重建
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    public static void reset() {  // reset：丢弃缓存的模型实例（配置热更后调用）
        sharedModel = null;  // 置空，下次 model() 会用新配置重建
    }  // 方法结束

    /**
     * 作用：JSON 模式便捷重载：走 chat() 并要求输出 JSON，超时取 ModelTimeouts.defaultMillis()
     * 参数：sysPrompt 系统提示词；userPrompt 用户输入
     * 返回值：模型输出的 JSON 文本；失败或超时返回空串
     * 异常：无（异常已在 chat() 内部捕获）
     */
    public static String chatJson(String sysPrompt, String userPrompt) {  // chatJson：JSON 模式便捷重载
        return chat(sysPrompt, userPrompt, true, ModelTimeouts.defaultMillis());  // 走 chat，jsonMode=true，超时取默认
    }  // 方法结束

    /**
     * 作用：普通文本模式便捷重载：走 chat() 不限制输出格式，超时取 ModelTimeouts.defaultMillis()
     * 参数：sysPrompt 系统提示词；userPrompt 用户输入
     * 返回值：模型输出的文本；失败或超时返回空串
     * 异常：无（异常已在 chat() 内部捕获）
     */
    public static String chatText(String sysPrompt, String userPrompt) {  // chatText：普通文本模式便捷重载
        return chat(sysPrompt, userPrompt, false, ModelTimeouts.defaultMillis());  // 走 chat，jsonMode=false，超时取默认
    }  // 方法结束
}  // 类结束
