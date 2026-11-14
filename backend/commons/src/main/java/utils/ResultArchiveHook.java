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
public class ResultArchiveHook implements Hook {  // 类声明：实现 AgentScope 的 Hook，挂在子 Agent 上做结果存档
    /**
     * 作用：结果归档钩子：Agent 产出最终结果时把结果与任务描述存档，供断线补取与复盘
     * 参数：event 钩子事件
     * 返回值：原事件流（透传，不改变行为）
     * 异常：无（归档失败只记日志）
     */
    @Override  // @Override 覆写钩子入口
    public <T extends HookEvent> Mono<T> onEvent(T event) {  // onEvent：所有钩子事件都走这里
        try {  // try：存档失败绝不能影响主链路
            HookEventType type = event.getType();  // 取事件类型
            if (type == HookEventType.POST_SUMMARY || type == HookEventType.POST_CALL) {  // 只在“总结完成”或“调用完成”时存档
                String text = extractResult(event);  // 提取最终结果文本
                if (text != null && !text.isBlank()) {  // 有文本才存
                    String key = LastResultStore.keyOf(extractTask(event));  // 按任务文本算指纹作为键
                    LastResultStore.put(key, text);  // 写入 LastResultStore
                    // ★诊断日志：确认"子Agent 到底存没存、指纹是什么"——主管回捞未命中时靠它定位
                    org.slf4j.LoggerFactory.getLogger(ResultArchiveHook.class)  // 取日志对象
                            .info("[lastResult存档] type={} key={} {} 字符（存档数={}）",  // info 日志：类型 + 键 + 长度 + 当前存档数
                                    type, key, text.length(), LastResultStore.stats().get("size"));  // 续：用于排查“主管回捞为什么没命中”
                }  // if 结束
            }  // if 类型判断结束
        } catch (Throwable ignored) {  // 捕获一切异常（含 Error）
            // 存档失败绝不影响 Agent 主流程
        }  // catch 结束
        return Mono.just(event);  // 原样透传事件（不改变 Agent 行为）
    }  // 方法结束

    /**
     * 作用：从事件中提取最终结果文本
     * 参数：event 钩子事件
     * 返回值：结果文本；取不到返回空串
     * 异常：无
     */
    private String extractResult(HookEvent event) {  // extractResult：从事件里取最终结果文本
        if (event instanceof PostSummaryEvent pse) {  // 是“总结完成”事件
            Msg m = pse.getSummaryMessage();  // 取摘要消息
            if (m != null && m.getTextContent() != null && !m.getTextContent().isBlank()) {  // 摘要消息非空
                return m.getTextContent();  // 直接作为结果返回
            }  // if 结束
        }  // if 结束
        Memory mem = event.getMemory();  // 否则从会话记忆里找
        if (mem == null) return null;  // 没有记忆返回 null
        List<Msg> msgs = mem.getMessages();  // 取消息列表
        if (msgs == null) return null;  // 列表为空返回 null
        for (int i = msgs.size() - 1; i >= 0; i--) {  // 从最后往前找（最后一条 assistant 就是最终答复）
            Msg m = msgs.get(i);  // 取消息
            if (m != null && m.getRole() == MsgRole.ASSISTANT  // 角色是 ASSISTANT 且文本非空
                    && m.getTextContent() != null && !m.getTextContent().isBlank()) {  // 续行
                return m.getTextContent();  // 作为结果返回
            }  // if 结束
        }  // for 结束
        return null;  // 没找到返回 null
    }  // 方法结束

    /**
     * 作用：从事件中提取原始任务描述
     * 参数：event 钩子事件
     * 返回值：任务文本；取不到返回空串
     * 异常：无
     */
    private String extractTask(HookEvent event) {  // extractTask：从事件里取原始任务描述（算指纹用）
        Memory mem = event.getMemory();  // 取会话记忆
        if (mem == null) return "";  // 没有记忆返回空串
        List<Msg> msgs = mem.getMessages();  // 取消息列表
        if (msgs == null) return "";  // 为空返回空串
        for (Msg m : msgs) {  // 从前往后找
            if (m != null && m.getRole() == MsgRole.USER && m.getTextContent() != null) {  // 角色是 USER 且有文本
                String t = m.getTextContent();  // t：任务原文
                // ★诊断（关键）：把"子Agent 实际算指纹用的文本"打出来（长度 + 首尾 60 字符），
                // 好在主管侧日志里直接比对——两边是不是同一个字符串，一眼可见。
                // 这一步是为了回答"回捞为什么对不上"：不能再靠猜文本被没被包装。
                String head = t.length() > 60 ? t.substring(0, 60).replace('\n', '⏎') : t.replace('\n', '⏎');  // head：前 60 字符（换行显示成 ⏎，便于一行日志里看）
                String tail = t.length() > 60  // tail：超过 60 字符时再取末尾 40 字符
                        ? t.substring(t.length() - 40).replace('\n', '⏎') : "";  // 续：用来比对两边是不是同一个字符串
                org.slf4j.LoggerFactory.getLogger(ResultArchiveHook.class)  // 取日志对象
                        .info("[lastResult指纹] 用于算指纹的任务原文: len={} 首=<<{}>> 尾=<<{}>>",  // info 日志：长度 + 首部 + 尾部
                                t.length(), head, tail);  // 续行
                return t;  // 返回任务原文
            }  // if 结束
        }  // for 结束
        return "";  // 没找到返回空串
    }  // 方法结束
}  // 类结束

