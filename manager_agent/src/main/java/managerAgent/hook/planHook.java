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

    // 监控统计字段
    private long startTimeNanos = 0;        // 本次Agent运行开始时间
    private int totalMessageChars = 0;      // 累计消息字符数（估算token用）
    private int toolCallCount = 0;          // 工具调用次数

    public planHook(PlanNotebook planNotebook) {
        this.plan = planNotebook;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        switch (event) { // 匹配不同的事件类型

            case PreReasoningEvent e -> { // 用户输入事件

                String reason = e.getInputMessages().get(0).getTextContent();
                log.info("#### 用户的Prompt：#######" );
                log.info(reason);

                EventStore.add("用户输入", reason); // 记录事件（供 /events 接口查询）

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

                EventStore.add("思考", reason); // 记录事件

                if (reason != null) { // 累计思考字符数
                    totalMessageChars += reason.length();
                }

            } // 全自动模式：不征求用户意见，Agent 继续执行

            case PostActingEvent e -> { // 调用工具事件

                String toolName = e.getToolUse().getName();
                log.info("##### 调用工具："+toolName);

                EventStore.add("调用工具", toolName); // 记录事件

                toolCallCount++; // 工具调用计数
            }

            case PostCallEvent e -> { // Agent 运行结束事件（打印监控统计）
                printMonitorSummary();
                EventStore.add("完成", "Agent 运行结束"); // 记录完成事件
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
