package utils;

import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DeepSeek 消息格式化器：修复 tool_calls 与 tool 响应不配对问题
 */
public class DeepSeekToolFixFormatter extends DeepSeekFormatter {

    @Override
    public List<OpenAIMessage> format(List<Msg> messages) {
        //★先拆：官方 DeepSeekFormatter 只认 assistant 消息里的 tool_calls，
        //  混在 user 消息里的工具调用/返回会被整块丢掉（实测输出 toolCalls=null，工具名/入参/返回全没了）。
        //  没有这种消息时直接原样返回，行为与改动前完全一致。
        List<Msg> prepared = splitUserToolBlocks(messages);
        List<OpenAIMessage> formatted = super.format(prepared); // 先执行官方格式化
        return fixToolMessagePairs(formatted);                  // 再修复 tool_calls 配对
    }

    /**
     * 把"混在 user 消息里的工具块"拆成 OpenAI 协议要求的三段：
     * ① user 纯文本  ② assistant(带 tool_calls)  ③ tool(工具返回)
     * 其它消息原样保留，顺序不变。
     */
    private List<Msg> splitUserToolBlocks(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        boolean need = false;
        for (Msg m : messages) {
            if (m != null && MsgRole.USER.equals(m.getRole()) && hasToolBlocks(m)) {
                need = true;
                break;
            }
        }
        if (!need) {
            return messages; // 没有 user+工具块的情况：原样返回，不改任何行为
        }
        List<Msg> out = new ArrayList<>(messages.size() + 4);
        for (Msg m : messages) {
            if (m == null || !MsgRole.USER.equals(m.getRole()) || !hasToolBlocks(m)) {
                out.add(m);
                continue;
            }
            // ① user 的纯文本
            StringBuilder sb = new StringBuilder();
            List<ContentBlock> uses = new ArrayList<>();
            List<ContentBlock> results = new ArrayList<>();
            for (ContentBlock b : blocksOf(m)) {
                if (b instanceof TextBlock) {
                    String t = ((TextBlock) b).getText();
                    if (t != null && !t.isBlank()) {
                        sb.append(t);
                    }
                } else if (b instanceof ToolUseBlock) {
                    uses.add(b);
                } else if (b instanceof ToolResultBlock) {
                    results.add(b);
                }
            }
            if (sb.length() > 0) {
                out.add(Msg.builder().role(MsgRole.USER).name(m.getName())
                        .content(TextBlock.builder().text(sb.toString()).build()).build());
            }
            // ② assistant 的 tool_calls
            if (!uses.isEmpty()) {
                out.add(Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
                        .content(uses.toArray(new ContentBlock[0])).build());
            }
            // ③ tool 的返回
            for (ContentBlock r : results) {
                out.add(Msg.builder().role(MsgRole.TOOL).name("tool").content(r).build());
            }
        }
        return out;
    }

    private boolean hasToolBlocks(Msg m) {
        for (ContentBlock b : blocksOf(m)) {
            if (b instanceof ToolUseBlock || b instanceof ToolResultBlock) {
                return true;
            }
        }
        return false;
    }

    private List<ContentBlock> blocksOf(Msg m) {
        List<ContentBlock> result = new ArrayList<>();
        try {
            Object c = m.getContent();
            if (c instanceof List) {
                for (Object o : (List<?>) c) {
                    if (o instanceof ContentBlock) {
                        result.add((ContentBlock) o);
                    }
                }
            } else if (c instanceof ContentBlock) {
                result.add((ContentBlock) c);
            }
        } catch (Throwable ignore) {
            // 拿不到就当作没有工具块，退回改动前的行为
        }
        return result;
    }

    /** 修复 tool_calls 与 tool 响应的配对 */
    private List<OpenAIMessage> fixToolMessagePairs(List<OpenAIMessage> msgs) {
        // 第一遍：收集声明过与实际响应的 tool_call_id
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

        // 第二遍：重建消息列表
        List<OpenAIMessage> result = new ArrayList<>(msgs.size());
        for (OpenAIMessage m : msgs) {
            String role = m.getRole();
            if ("tool".equals(role)) {
                // 删除孤立的 tool 消息
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
                    result.add(m); // 全部有响应：原样保留
                } else if (valid.isEmpty()) {
                    // 全部无响应：降级为纯文本 assistant
                    result.add(OpenAIMessage.builder()
                            .role("assistant")
                            .content(asText(m.getContent()))
                            .build());
                } else {
                    // 部分有响应：只保留有效的 tool_calls
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
