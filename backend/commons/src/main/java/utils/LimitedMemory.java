package utils;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * 有限条数记忆：防止单例Agent上下文无限增长
 * 单例Agent的Memory会无限累积→上下文超长→DashScope报400"input length超限"→任务失败
 * 解决：最多保留最近maxSize条消息，超出自动删最旧，上下文始终有界
 */
public class LimitedMemory implements Memory {

    private final List<Msg> messages = new ArrayList<>(); // 消息列表（按加入顺序）

    private final int maxSize; // 最多保留多少条消息（超出删最旧）

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
