package utils;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.List;

/** 有限条数记忆：最多保留最近 maxSize 条消息 */
public class LimitedMemory implements Memory {

    private final List<Msg> messages = new ArrayList<>(); // 消息列表

    private final int maxSize; // 消息条数上限

    public LimitedMemory(int maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    public void addMessage(Msg msg) {
        messages.add(msg);
        while (messages.size() > maxSize) { // 超出上限：删除最旧的消息
            messages.remove(0);
        }
    }

    @Override
    public List<Msg> getMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    @Override
    public void clear() {
        messages.clear();
    }
}
