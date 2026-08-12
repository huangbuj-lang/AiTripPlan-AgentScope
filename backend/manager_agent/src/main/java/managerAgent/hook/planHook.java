package managerAgent.hook;

import io.agentscope.core.hook.*;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.plan.PlanNotebook;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 计划拦截器：监听Agent事件，记录到EventStore（/events实时展示）+ Token监控
 * PreReasoningEvent：用户输入 | PostReasoningEvent：思考 | PostActingEvent：调用工具
 */
@Slf4j
public class planHook implements Hook {

    private final PlanNotebook plan; // 计划步骤（保留引用）

    private final String sessionId; // ★会话隔离：事件写入该会话的EventStore（前端localStorage生成）

    private String userInput = null; // ★缓存本次任务的真实用户输入（第1轮USER消息，后续轮次复用——memory滚动后USER可能被挤掉）

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

        // ★中断支持：/cancel 设置标志后，在每个事件触发点检查并抛异常终止 ReAct 循环
        if (EventStore.isCancelRequested(sessionId)) {
            throw new RuntimeException("任务已被用户中断（/cancel）");
        }

        switch (event) { // 匹配不同的事件类型

            case PreReasoningEvent e -> { // 用户输入事件

                // ★取真实用户输入：优先用第1轮缓存的USER消息（后续轮次复用）；
                //  第1轮时遍历找USER角色消息（AgentScope把sysPrompt放最前且memory滚动后USER可能被挤掉）
                String reason = null;
                if (userInput != null) {
                    reason = userInput; // 已有缓存：本轮直接复用（显示不变）
                } else {
                    for (io.agentscope.core.message.Msg m : e.getInputMessages()) {
                        if (m.getRole() == io.agentscope.core.message.MsgRole.USER) {
                            reason = m.getTextContent(); // 遍历到最后一条USER=真实用户输入
                        }
                    }
                    if (reason == null || reason.isBlank()) {
                        // 兜底：跳过SYSTEM(可能是sysPrompt)，找第一条非system消息
                        for (io.agentscope.core.message.Msg m : e.getInputMessages()) {
                            if (m.getRole() != io.agentscope.core.message.MsgRole.SYSTEM
                                    && m.getTextContent() != null && !m.getTextContent().isBlank()) {
                                reason = m.getTextContent();
                                break;
                            }
                        }
                    }
                    if (reason != null) {
                        userInput = reason; // 缓存本次任务的真实输入（防止后续轮次memory滚动后取到sysPrompt）
                    }
                }
                log.info("#### 用户的Prompt：#######" );
                log.info(reason);

                EventStore.add(sessionId, "用户输入", reason); // 记录事件（供 /events 接口查询）

                if (startTimeNanos == 0) { // 记录开始时间（第一次输入时）
                    startTimeNanos = System.nanoTime();
                }
                if (reason != null) { // 累计字符数（估算token）
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

            } // 全自动模式：不征求用户意见，Agent 继续执行

            case PostActingEvent e -> { // 调用工具事件

                String toolName = e.getToolUse().getName();
                log.info("##### 调用工具："+toolName);

                EventStore.add(sessionId, "调用工具", toolName); // 记录事件

                toolCallCount++; // 工具调用计数
            }

            case PostCallEvent e -> { // Agent 运行结束事件（打印监控统计）
                printMonitorSummary();
                EventStore.add(sessionId, "完成", "Agent 运行结束"); // 记录完成事件
                startTimeNanos = 0; // 重置统计（下次运行重新计）
                totalMessageChars = 0;
                toolCallCount = 0;
            }

            default -> {
            } // 其他事件忽略
        }

        return Mono.just(event); // 返回原事件
    }

    /**
     * 打印 Token 监控统计（1 token ≈ 4字符，1万token ≈ 0.5元）
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
