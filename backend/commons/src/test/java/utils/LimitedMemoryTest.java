package utils;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限量记忆单元测试
 */
class LimitedMemoryTest {

    private Msg msg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    void keepsOnlyLastMaxSizeMessages() {
        LimitedMemory memory = new LimitedMemory(3);
        for (int i = 0; i < 5; i++) {
            memory.addMessage(msg("msg-" + i));
        }
        List<Msg> msgs = memory.getMessages();
        assertEquals(3, msgs.size());
        assertEquals("msg-2", msgs.get(0).getTextContent()); // 最旧的被淘汰
        assertEquals("msg-4", msgs.get(2).getTextContent());
    }

    @Test
    void clearRemovesAllMessages() {
        LimitedMemory memory = new LimitedMemory(5);
        memory.addMessage(msg("a"));
        memory.addMessage(msg("b"));
        memory.clear();
        assertTrue(memory.getMessages().isEmpty());
    }

    @Test
    void deleteMessageRemovesByIndex() {
        LimitedMemory memory = new LimitedMemory(5);
        memory.addMessage(msg("a"));
        memory.addMessage(msg("b"));
        memory.addMessage(msg("c"));
        memory.deleteMessage(1); // 删除中间
        List<Msg> msgs = memory.getMessages();
        assertEquals(2, msgs.size());
        assertEquals("a", msgs.get(0).getTextContent());
        assertEquals("c", msgs.get(1).getTextContent());
    }
}
