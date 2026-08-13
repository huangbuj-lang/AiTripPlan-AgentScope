package managerAgent.hook;

import io.agentscope.core.hook.*;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.plan.PlanNotebook;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
/**
 * 计划拦截器：监听Agent事件并记录（事件展示 + Token监控）
 */
@Slf4j
public class planHook implements Hook {

    private final PlanNotebook plan; // 计划步骤引用

    private final String sessionId; // 会话ID（事件按会话隔离）

    private String userInput = null; // 缓存的真实用户输入

    // 监控统计字段
    private long startTimeNanos = 0;        // 本次Agent运行开始时间
    private int totalMessageChars = 0;      // 累计消息字符数（估算token用）
    private int toolCallCount = 0;          // 工具调用次数

    public planHook(PlanNotebook planNotebook, String sessionId) {
        this.plan = planNotebook;
        this.sessionId = sessionId;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        // 检查中断标志，有则抛异常终止运行
        if (EventStore.isCancelRequested(sessionId)) {
            throw new RuntimeException("任务已被用户中断（/cancel）");
        }

        switch (event) { // 匹配不同的事件类型

            case PreReasoningEvent e -> { // 用户输入事件

                // 取真实用户输入（优先复用缓存，否则遍历找USER消息）
                String reason = null;
                if (userInput != null) {
                    reason = userInput; // 复用缓存的输入
                } else {
                    for (io.agentscope.core.message.Msg m : e.getInputMessages()) {
                        if (m.getRole() == io.agentscope.core.message.MsgRole.USER) {
                            reason = m.getTextContent(); // 取最后一条USER消息作为输入
                        }
                    }
                    if (reason == null || reason.isBlank()) {
                        // 无USER消息时取第一条非system消息兜底
                        for (io.agentscope.core.message.Msg m : e.getInputMessages()) {
                            if (m.getRole() != io.agentscope.core.message.MsgRole.SYSTEM
                                    && m.getTextContent() != null && !m.getTextContent().isBlank()) {
                                reason = m.getTextContent();
                                break;
                            }
                        }
                    }
                    if (reason != null) {
                        userInput = reason; // 缓存输入供后续轮次复用
                    }
                }
                log.info("#### 用户的Prompt：#######" );
                log.info(reason);

                EventStore.add(sessionId, "用户输入", reason); // 记录用户输入事件

                if (startTimeNanos == 0) { // 记录开始时间
                    startTimeNanos = System.nanoTime();
                }
                if (reason != null) { // 累计字符数
                    totalMessageChars += reason.length();
                }
            }

            case PostReasoningEvent e -> { // 推理思考事件

                String reason = e.getReasoningMessage().getTextContent();
                log.info("#### 思考过程：#######" );
                log.info(reason);

                EventStore.add(sessionId, "思考", reason); // 记录事件

                if (reason != null) { // 累计思考字符数
                    totalMessageChars += reason.length();
                }

            } // 全自动模式：不征求用户意见，继续执行

            case PostActingEvent e -> { // 调用工具事件

                String toolName = e.getToolUse().getName();
                log.info("##### 调用工具："+toolName);

                EventStore.add(sessionId, "调用工具", toolName); // 记录事件

                toolCallCount++; // 工具调用计数
            }

            case PostCallEvent e -> { // Agent 运行结束事件（打印监控统计）
                printMonitorSummary();
                EventStore.add(sessionId, "完成", "Agent 运行结束"); // 记录完成事件
                startTimeNanos = 0; // 重置统计
                totalMessageChars = 0;
                toolCallCount = 0;
            }

            default -> {
            } // 其他事件忽略
        }

        return Mono.just(event); // 返回原事件
    }

    /**
    /**
     * 打印Token监控统计
     */
    private void printMonitorSummary() {
        try {
            long costMs = (System.nanoTime() - startTimeNanos) / 1_000_000; // 耗时（毫秒）
            int estimatedTokens = totalMessageChars / 4; // 估算 token
            double cost = estimatedTokens / 10000.0 * 0.5; // 估算费用

            System.out.println("════════════════════════════════════════");
            System.out.println("【Token监控】Agent 运行完成");
            System.out.println("   消息字符数: " + totalMessageChars + " 字符");
            System.out.println("   估算 Token: " + estimatedTokens);
            System.out.println("   调用工具次数: " + toolCallCount + " 次");
            System.out.println("   执行耗时: " + costMs + " ms");
            System.out.println("   估算费用: ¥" + String.format("%.4f", cost));
            System.out.println("════════════════════════════════════════");
        } catch (Exception ex) {
            System.err.println("Token监控统计失败: " + ex.getMessage());
        }
    }
}
