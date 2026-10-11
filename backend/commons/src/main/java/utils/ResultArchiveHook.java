package utils;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ★2026-09-11 结果回捞（方案①）：挂在子Agent（行程/路线）上，把 Agent 的最终输出按"任务指纹"
 * 存进 {@link LastResultStore}，供主管 A2A 失败时直连 /lastResult 回取。
 *
 * <p>背景：A2A 是阻塞式长调用，子Agent 常常已经把活干完了，但回程（流式响应）中断/超时，主管只拿到
 * 开头半句话（实测 14~128 字符）就判失败并重发。重发既费时又可能再失败，于是天骨架失败→回退整单→降级。
 * 有了存档，主管超时后先回捞一次，捞到就直接用，不重发。
 *
 * <p>只读不写 Agent 行为：钩子里仅做存档，任何异常都吞掉，绝不影响主链路。
 */
public class ResultArchiveHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            HookEventType type = event.getType();
            if (type == HookEventType.POST_SUMMARY || type == HookEventType.POST_CALL) {
                String text = extractResult(event);
                if (text != null && !text.isBlank()) {
                    String key = LastResultStore.keyOf(extractTask(event));
                    LastResultStore.put(key, text);
                    // ★诊断日志：确认"子Agent 到底存没存、指纹是什么"——主管回捞未命中时靠它定位
                    org.slf4j.LoggerFactory.getLogger(ResultArchiveHook.class)
                            .info("[lastResult存档] type={} key={} {} 字符（存档数={}）",
                                    type, key, text.length(), LastResultStore.stats().get("size"));
                }
            }
        } catch (Throwable ignored) {
            // 存档失败绝不影响 Agent 主流程
        }
        return Mono.just(event);
    }

    /** 优先取"总结消息"（Agent 的最终答复），拿不到就退回 memory 里最后一条 assistant 消息 */
    private String extractResult(HookEvent event) {
        if (event instanceof PostSummaryEvent pse) {
            Msg m = pse.getSummaryMessage();
            if (m != null && m.getTextContent() != null && !m.getTextContent().isBlank()) {
                return m.getTextContent();
            }
        }
        Memory mem = event.getMemory();
        if (mem == null) return null;
        List<Msg> msgs = mem.getMessages();
        if (msgs == null) return null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg m = msgs.get(i);
            if (m != null && m.getRole() == MsgRole.ASSISTANT
                    && m.getTextContent() != null && !m.getTextContent().isBlank()) {
                return m.getTextContent();
            }
        }
        return null;
    }

    /** 任务文本 = memory 里第一条 user 消息（主管发来的 task），双方用同一指纹算法 */
    private String extractTask(HookEvent event) {
        Memory mem = event.getMemory();
        if (mem == null) return "";
        List<Msg> msgs = mem.getMessages();
        if (msgs == null) return "";
        for (Msg m : msgs) {
            if (m != null && m.getRole() == MsgRole.USER && m.getTextContent() != null) {
                String t = m.getTextContent();
                // ★诊断（关键）：把"子Agent 实际算指纹用的文本"打出来（长度 + 首尾 60 字符），
                // 好在主管侧日志里直接比对——两边是不是同一个字符串，一眼可见。
                // 这一步是为了回答"回捞为什么对不上"：不能再靠猜文本被没被包装。
                String head = t.length() > 60 ? t.substring(0, 60).replace('\n', '⏎') : t.replace('\n', '⏎');
                String tail = t.length() > 60
                        ? t.substring(t.length() - 40).replace('\n', '⏎') : "";
                org.slf4j.LoggerFactory.getLogger(ResultArchiveHook.class)
                        .info("[lastResult指纹] 用于算指纹的任务原文: len={} 首=<<{}>> 尾=<<{}>>",
                                t.length(), head, tail);
                return t;
            }
        }
        return "";
    }
}
