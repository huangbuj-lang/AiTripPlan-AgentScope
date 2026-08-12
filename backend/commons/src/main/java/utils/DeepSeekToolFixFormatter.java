package utils;

import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DeepSeek 专用 formatter（修复版）
 * <p>
 * 官方 DeepSeekFormatter 只修复"结尾补空user"等场景，但 ReAct 多轮工具调用后
 * AgentScope(1.0.8) 组装的消息历史会出现 tool_calls 与 tool 响应不配对：
 * <pre>
 * 错误1: Messages with role 'tool' must be a response to a preceding message with 'tool_calls'
 * 错误2: An assistant message with 'tool_calls' must be followed by tool messages
 *        responding to each 'tool_call_id'. (insufficient tool messages)
 * </pre>
 * 本类在官方修复基础上做"配对修复"：
 * <ol>
 *   <li>删除孤立的 tool 消息（其 toolCallId 没有对应的 assistant tool_calls）</li>
 *   <li>裁剪 assistant 消息中"没有 tool 响应"的多余 tool_calls（保留有响应的）</li>
 *   <li>assistant 的 tool_calls 全部无响应时，降级为纯文本消息（去掉 tool_calls）</li>
 * </ol>
 */
public class DeepSeekToolFixFormatter extends DeepSeekFormatter {

    @Override
    public List<OpenAIMessage> format(List<Msg> messages) {
        List<OpenAIMessage> formatted = super.format(messages); // 先走官方 DeepSeek 修复
        return fixToolMessagePairs(formatted);                  // 再修 tool_calls 配对
    }

    /**
     * 扫描消息历史，修复 tool_calls / tool 响应不配对的问题
     */
    private List<OpenAIMessage> fixToolMessagePairs(List<OpenAIMessage> msgs) {
        // ---- 第一遍：收集所有被 assistant 声明过的 tool_call_id，以及实际有响应的 id ----
        Set<String> declaredCallIds = new HashSet<>();   // assistant 声明过的所有 tool_call_id
        Set<String> respondedCallIds = new HashSet<>();  // 有 tool 消息响应的 tool_call_id
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
        respondedCallIds.retainAll(declaredCallIds); // 只认被声明过的

        // ---- 第二遍：重建消息列表 ----
        List<OpenAIMessage> result = new ArrayList<>(msgs.size());
        for (OpenAIMessage m : msgs) {
            String role = m.getRole();
            if ("tool".equals(role)) {
                // 孤立 tool 消息（id 没被任何 assistant 声明过）→ 直接删除
                if (!declaredCallIds.contains(m.getToolCallId())) {
                    continue;
                }
                result.add(m);
            } else if ("assistant".equals(role) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                // 裁剪 tool_calls：只保留有响应的
                List<OpenAIToolCall> valid = new ArrayList<>();
                for (OpenAIToolCall tc : m.getToolCalls()) {
                    if (respondedCallIds.contains(tc.getId())) {
                        valid.add(tc);
                    }
                }
                if (valid.size() == m.getToolCalls().size()) {
                    result.add(m); // 全部有响应，原样保留
                } else if (valid.isEmpty()) {
                    // 全部无响应 → 降级为纯文本 assistant（去掉 tool_calls，避免 DeepSeek 报"缺少tool响应"）
                    result.add(OpenAIMessage.builder()
                            .role("assistant")
                            .content(asText(m.getContent()))
                            .build());
                } else {
                    // 部分有响应 → 重建 assistant，只保留有响应的 tool_calls
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

    /** 把 content 转成字符串（content 可能是 String 或 List，统一转 String 传给 builder） */
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
