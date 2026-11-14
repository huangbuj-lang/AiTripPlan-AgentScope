package utils;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;

import java.util.ArrayList;
import java.util.List;

/** 有限条数记忆：最多保留最近 maxSize 条消息 */
public class LimitedMemory implements Memory {  // 类声明：实现 AgentScope Memory，限量保留最近 N 条

    private final List<Msg> messages = new ArrayList<>(); // 消息列表  // messages：消息列表

    private final int maxSize; // 消息条数上限  // maxSize：条数上限
    /**
     * 作用：构造限量记忆，超过上限时自动淘汰最早的消息（防止单例 Agent 上下文无限增长导致 400）
     * 参数：maxSize 最多保留的消息条数
     * 返回值：无
     * 异常：无
     */

    public LimitedMemory(int maxSize) {  // 构造器：指定上限
        this.maxSize = maxSize;  // 保存上限
    }  // 构造器结束
    /**
     * 作用：追加一条消息，超上限时淘汰最早一条
     * 参数：msg 待追加的消息
     * 返回值：无
     * 异常：无
     */
    @Override  // @Override：实现 Memory 接口
    public void addMessage(Msg msg) {  // addMessage：追加消息
        messages.add(msg);  // 先加入
        while (messages.size() > maxSize) { // 超出上限：删除最旧的消息  // 超过上限就淘汰最旧的
            messages.remove(0);  // 删除第 0 条
        }  // while 结束
    }  // 方法结束
    /**
     * 作用：读取当前全部消息（只读视图）
     * 参数：无
     * 返回值：消息列表
     * 异常：无
     */
    @Override  // @Override：实现 Memory 接口
    public List<Msg> getMessages() {  // getMessages：读全部消息
        return new ArrayList<>(messages);  // 返回副本（防止外部改内部列表）
    }  // 方法结束
    /**
     * 作用：删除指定下标的消息
     * 参数：index 消息下标
     * 返回值：无
     * 异常：无（下标越界时内部捕获忽略）
     */
    @Override  // @Override：实现 Memory 接口
    public void deleteMessage(int index) {  // deleteMessage：按下标删除
        if (index >= 0 && index < messages.size()) {  // 下标合法才删
            messages.remove(index);  // 删除该条
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：清空记忆
     * 参数：无
     * 返回值：无
     * 异常：无
     */
    @Override  // @Override：实现 Memory 接口
    public void clear() {  // clear：清空记忆
        messages.clear();  // 清空列表
    }  // 方法结束
}  // 类结束
