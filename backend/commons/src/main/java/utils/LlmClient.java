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
public final class LlmClient {

    private LlmClient() {}

    private static volatile Model sharedModel;

    /** 共享单例模型（线程安全，懒加载） */
    public static Model model() {
        if (sharedModel == null) {
            synchronized (LlmClient.class) {
                if (sharedModel == null) {
                    sharedModel = AgentUtils.buildModel();
                }
            }
        }
        return sharedModel;
    }

    /** 同步调用：sysPrompt + userPrompt -> 文本。 */
    public static String chat(String sysPrompt, String userPrompt, boolean jsonMode, long timeoutMillis) {
        Msg system = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(sysPrompt).build()).build();
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(userPrompt).build()).build();
        GenerateOptions.Builder ob = GenerateOptions.builder();
        if (jsonMode) {
            ob.additionalBodyParams(Map.of("response_format", Map.of("type", "json_object")));
        }
        try {
            ChatResponse resp = model().stream(List.of(system, user), null, ob.build())
                    .blockLast(Duration.ofMillis(timeoutMillis));
            if (resp == null) return "";
            StringBuilder sb = new StringBuilder();
            for (io.agentscope.core.message.ContentBlock block : resp.getContent()) {
                if (block instanceof io.agentscope.core.message.TextBlock tb) {
                    sb.append(tb.getText());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 热更后调用：丢弃缓存模型实例，下次 model() 用 ModelConfigHolder 新配置重建 */
    public static void reset() {
        sharedModel = null;
    }

    /** JSON 模式便捷重载 */
    public static String chatJson(String sysPrompt, String userPrompt) {
        return chat(sysPrompt, userPrompt, true, ModelTimeouts.defaultMillis());
    }

    /** 普通文本模式便捷重载 */
    public static String chatText(String sysPrompt, String userPrompt) {
        return chat(sysPrompt, userPrompt, false, ModelTimeouts.defaultMillis());
    }
}
