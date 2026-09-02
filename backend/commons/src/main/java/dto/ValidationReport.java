package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性校验报告（Constraint Engine 产物，供 Reviewer/评分/前端事件流消费）。
 * 数据缺失不判 FAIL（分级设计）：无法校验的项目直接跳过，防止数据覆盖率低导致大面积误报。
 */
@Data
@NoArgsConstructor
public class ValidationReport {

    /** 总体结论：PASS=通过 / WARN=有警告可交付 / FAIL=有高严重度问题 */
    private String status = "PASS";

    /** 扣分制得分（100 起步，high -15 / medium -8 / low -3，下限 0） */
    private int score = 100;

    /** 问题清单 */
    private List<Issue> issues = new ArrayList<>();

    /** 已执行/跳过的检查项名称（追溯哪些检查因数据缺失未执行） */
    private List<String> skippedChecks = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class Issue {
        /** 问题类型：budget/schedule/duplicate/transport/constraint */
        private String type;
        /** 严重度：high/medium/low */
        private String severity;
        /** 定位（如 day2 / hotel / overall） */
        private String target;
        /** 说明 */
        private String reason;

        public Issue(String type, String severity, String target, String reason) {
            this.type = type;
            this.severity = severity;
            this.target = target;
            this.reason = reason;
        }
    }

    public void add(Issue issue) {
        issues.add(issue);
        switch (issue.severity) {
            case "high" -> score -= 15;
            case "medium" -> score -= 8;
            default -> score -= 3;
        }
    }

    /** 汇总结论：有 high → FAIL；有 medium/low → WARN；否则 PASS */
    public String resolveStatus() {
        boolean anyHigh = issues.stream().anyMatch(i -> "high".equals(i.severity));
        this.status = anyHigh ? "FAIL" : (issues.isEmpty() ? "PASS" : "WARN");
        this.score = Math.max(0, this.score);
        return this.status;
    }

    /** 一行摘要（事件流展示用，如 "FAIL(70): 预算超支; day2 时间倒序"） */
    public String summary() {
        StringBuilder sb = new StringBuilder(status).append("(").append(score).append(")");
        for (int i = 0; i < issues.size() && i < 5; i++) {
            sb.append(i == 0 ? ": " : "; ").append(issues.get(i).reason);
        }
        if (issues.size() > 5) {
            sb.append(" 等共").append(issues.size()).append("项");
        }
        return sb.toString();
    }
}
