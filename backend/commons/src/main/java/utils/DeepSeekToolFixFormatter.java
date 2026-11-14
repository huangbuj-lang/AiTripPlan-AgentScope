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
public class DeepSeekToolFixFormatter extends DeepSeekFormatter {  // 类声明：继承官方 DeepSeekFormatter，补 tool 配对修复
    /**
     * 作用：格式化入口：先拆分混在 user 消息里的工具块，再走官方 DeepSeek 格式化，最后修复 tool 消息与 tool_calls 的配对
     * 参数：messages AgentScope 原始消息列表
     * 返回值：DeepSeek/OpenAI 协议可接受的消息列表
     * 异常：无（内部对异常消息做保守处理，不抛异常）
     */
    @Override  // @Override 覆写格式化入口
    public List<OpenAIMessage> format(List<Msg> messages) {  // format：把 AgentScope 消息转成 OpenAI 协议消息
        List<OpenAIMessage> formatted = super.format(messages); // 先执行官方格式化  // 先走官方格式化
        return fixToolMessagePairs(formatted);                  // 再修复 tool_calls 配对  // 再修复 tool_calls 与 tool 响应的配对
    }  // 方法结束

    /**
     * 作用：修复 tool 消息与 tool_calls 的配对：补齐缺失的 assistant tool_calls 或占位 tool 返回，避免 DeepSeek 400
     * 参数：msgs 已格式化的 OpenAI 消息列表
     * 返回值：修复后的新列表
     * 异常：无
     */
    private List<OpenAIMessage> fixToolMessagePairs(List<OpenAIMessage> msgs) {  // fixToolMessagePairs：修复配对，避免 DeepSeek 400
        //第一遍：收集声明过与实际响应的 tool_call_id
        Set<String> declaredCallIds = new HashSet<>();   // assistant 声明的 tool_call_id  // declaredCallIds：assistant 声明过的 tool_call_id
        Set<String> respondedCallIds = new HashSet<>();  // 有 tool 响应的 tool_call_id  // respondedCallIds：真正有 tool 响应的 id
        for (OpenAIMessage m : msgs) {  // 第一遍：遍历消息
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null) {  // 是 assistant 且有 tool_calls
                for (OpenAIToolCall tc : m.getToolCalls()) {  // 遍历它的 tool_calls
                    declaredCallIds.add(tc.getId());  // 收集声明的 id
                }  // for 结束
            }  // if 结束
            if ("tool".equals(m.getRole())) {  // 是 tool 消息
                respondedCallIds.add(m.getToolCallId());  // 收集响应的 id
            }  // if 结束
        }  // for 结束
        respondedCallIds.retainAll(declaredCallIds); // 只保留被声明过的 id  // 只保留被声明过的响应 id

        //第二遍：重建消息列表
        List<OpenAIMessage> result = new ArrayList<>(msgs.size());  // result：重建后的列表
        for (OpenAIMessage m : msgs) {  // 第二遍：逐条重建
            String role = m.getRole();  // 角色
            if ("tool".equals(role)) {  // 是 tool 消息
                //删除孤立的 tool 消息
                if (!declaredCallIds.contains(m.getToolCallId())) {  // 孤立响应（没有对应声明）
                    continue;  // 丢弃
                }  // if 结束
                result.add(m);  // 保留
            } else if ("assistant".equals(role) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {  // 是带 tool_calls 的 assistant
                //裁剪 tool_calls：只保留有响应的
                List<OpenAIToolCall> valid = new ArrayList<>();  // valid：有响应的 tool_calls
                for (OpenAIToolCall tc : m.getToolCalls()) {  // 遍历 tool_calls
                    if (respondedCallIds.contains(tc.getId())) {  // 有对应响应
                        valid.add(tc);  // 保留
                    }  // if 结束
                }  // for 结束
                if (valid.size() == m.getToolCalls().size()) {  // 全部都有响应
                    result.add(m); // 全部有响应：原样保留  // 原样保留
                } else if (valid.isEmpty()) {  // 一个响应都没有
                    //全部无响应：降级为纯文本 assistant
                    result.add(OpenAIMessage.builder()  // 降级为纯文本 assistant（丢掉 tool_calls）
                            .role("assistant")  // 角色 assistant
                            .content(asText(m.getContent()))  // 内容转纯文本
                            .build());  // 构造消息
                } else {  // 部分有响应
                    //部分有响应：只保留有效的 tool_calls
                    result.add(OpenAIMessage.builder()  // 只保留有效的 tool_calls
                            .role("assistant")  // 角色 assistant
                            .content(asText(m.getContent()))  // 内容转纯文本
                            .toolCalls(valid)  // 挂上有效的 tool_calls
                            .build());  // 构造消息
                }  // if 结束
            } else {  // 其他消息
                result.add(m);  // 原样保留
            }  // if 结束
        }  // for 结束
        return result;  // 返回修复后的列表
    }  // 方法结束

    /**
     * 作用：把消息内容对象转成纯文本
     * 参数：content 内容对象（字符串/块列表/null）
     * 返回值：文本，无法识别时为空串
     * 异常：无
     */
    private String asText(Object content) {  // asText：内容对象转纯文本
        if (content == null) {  // 内容为空
            return "";  // 返回空串
        }  // if 结束
        if (content instanceof String) {  // 本身是字符串
            return (String) content;  // 直接返回
        }  // if 结束
        return content.toString();  // 其他类型 toString
    }  // 方法结束
}  // 类结束
