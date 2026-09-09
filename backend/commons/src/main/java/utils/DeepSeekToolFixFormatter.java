package utils;

import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** DeepSeek 消息格式化器：修复 tool_calls 与 tool 响应不配对问题 */
public class DeepSeekToolFixFormatter extends DeepSeekFormatter {

    @Override
    public List<OpenAIMessage> format(List<Msg> messages) {
        List<OpenAIMessage> formatted = super.format(messages); // 先执行官方格式化
        return fixToolMessagePairs(formatted);                  // 再修复 tool_calls 配对
    }

    /** 修复 tool_calls 与 tool 响应的配对 */
    private List<OpenAIMessage> fixToolMessagePairs(List<OpenAIMessage> msgs) {
        //第一遍：收集声明过与实际响应的 tool_call_id
        Set<String> declaredCallIds = new HashSet<>();   // assistant 声明的 tool_call_id
        Set<String> respondedCallIds = new HashSet<>();  // 有 tool 响应的 tool_call_id
        for (OpenAIMessage m : msgs) {
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null) {
                for (OpenAIToolCall tc : m.getToolCalls()) {
                    declaredCallIds.add(tc.getId());
                }
            }
            if ("tool".equals(m.getRole())) {
                respondedCallIds.add(m.getToolCallId());
            }
        }
        respondedCallIds.retainAll(declaredCallIds); // 只保留被声明过的 id

        //第二遍：重建消息列表
        List<OpenAIMessage> result = new ArrayList<>(msgs.size());
        for (OpenAIMessage m : msgs) {
            String role = m.getRole();
            if ("tool".equals(role)) {
                //删除孤立的 tool 消息
                if (!declaredCallIds.contains(m.getToolCallId())) {
                    continue;
                }
                result.add(m);
            } else if ("assistant".equals(role) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                //裁剪 tool_calls：只保留有响应的
                List<OpenAIToolCall> valid = new ArrayList<>();
                for (OpenAIToolCall tc : m.getToolCalls()) {
                    if (respondedCallIds.contains(tc.getId())) {
                        valid.add(tc);
                    }
                }
                if (valid.size() == m.getToolCalls().size()) {
                    result.add(m); // 全部有响应：原样保留
                } else if (valid.isEmpty()) {
                    //全部无响应：降级为纯文本 assistant
                    result.add(OpenAIMessage.builder()
                            .role("assistant")
                            .content(asText(m.getContent()))
                            .build());
                } else {
                    //部分有响应：只保留有效的 tool_calls
                    result.add(OpenAIMessage.builder()
                            .role("assistant")
                            .content(asText(m.getContent()))
                            .toolCalls(valid)
                            .build());
                }
            } else {
                result.add(m);
            }
        }
        return result;
    }

    /** 将 content 统一转为字符串 */
    private String asText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        return content.toString();
    }
}
