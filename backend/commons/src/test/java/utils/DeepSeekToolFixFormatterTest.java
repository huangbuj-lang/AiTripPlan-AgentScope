package utils;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeekToolFixFormatter：非 assistant 消息（如 user）里的工具块必须保住。
 * <p>
 * 实测根因：官方 OpenAIMessageConverter 只认 assistant 里的 tool_calls 与 tool 消息里的工具返回，
 * 落在 user 消息里的 ToolUseBlock / ToolResultBlock 被整块丢弃（日志刷
 * "ToolUseBlock is not supported in user messages" ×54）→ 模型看不到自己调用过哪些工具、
 * 拿到过什么结果，只跑 1 次 LLM 就草率收尾，产出 95 字符空话，却仍被判为「调用成功」。
 * 本测试锁死：这类块在格式化后必须仍能看到内容。
 */
class DeepSeekToolFixFormatterTest {

    @Test
    void user消息里的工具调用与工具返回必须保留() {
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(
                        TextBlock.builder().text("请继续完成行程规划").build(),
                        ToolUseBlock.builder()
                                .id("call_1").name("query_trip")
                                .input(Map.of("city", "蓉城")).build(),
                        ToolResultBlock.builder()
                                .id("call_1").name("query_trip")
                                .output(TextBlock.builder().text("【景点清单】宽窄巷子/人民公园/武侯祠").build())
                                .build())
                .build();

        List<OpenAIMessage> out = new DeepSeekToolFixFormatter().format(List.of(userMsg));
        StringBuilder dump = new StringBuilder();
        for (OpenAIMessage m : out) {
            String c = m.getContentAsString();
            if (c == null) {
                c = String.valueOf(m.getContent());
            }
            dump.append(m.getRole()).append(" | content=").append(c)
                .append(" | toolCalls=").append(String.valueOf(m.getToolCalls())).append('\n');
        }
        String all = dump.toString();

        assertFalse(out.isEmpty(), "格式化结果不能为空");

        //★注意：框架的 OpenAIToolCall / OpenAIFunction 没有重写 toString（打印出来是 类名@哈希），
        //  所以不能只看 dump 字符串 —— 要按真实字段断言。工具名/入参在 toolCalls[].function 里。
        OpenAIMessage asst = null;
        OpenAIMessage toolMsg = null;
        for (OpenAIMessage m : out) {
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                asst = m;
            }
            if ("tool".equals(m.getRole())) {
                toolMsg = m;
            }
        }
        String asstTxt = (asst == null) ? "无 assistant 消息"
                : (String.valueOf(asst.getToolCalls().get(0).getFunction().getName()) + " | 入参="
                   + String.valueOf(asst.getToolCalls().get(0).getFunction().getArguments()));
        String toolTxt = (toolMsg == null) ? "无 tool 消息" : String.valueOf(toolMsg.getContentAsString());

        assertTrue(asstTxt.contains("query_trip"), "工具名必须保留。实际 => " + asstTxt + "\n" + dump);
        assertTrue(asstTxt.contains("蓉城"), "工具入参必须保留。实际 => " + asstTxt + "\n" + dump);
        assertTrue(toolTxt.contains("宽窄巷子"), "工具返回必须保留。实际 => " + toolTxt + "\n" + dump);
    }
}
