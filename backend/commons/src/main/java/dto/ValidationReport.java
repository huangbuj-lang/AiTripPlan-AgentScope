package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性校验报告（Constraint Engine 产物，供 Reviewer/评分/前端事件流消费）。
 * 数据缺失不判 FAIL（分级设计）：无法校验的项目直接跳过，防止数据覆盖率低导致大面积误报。
 */
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造（Jackson 反序列化需要）
public class ValidationReport {  // 类声明：确定性校验报告（校验引擎产物）

    /** 总体结论：PASS=通过 / WARN=有警告可交付 / FAIL=有高严重度问题 */
    private String status = "PASS";  // status：总体结论 PASS / WARN / FAIL

    /** 扣分制得分（100 起步，high -15 / medium -8 / low -3，下限 0） */
    private int score = 100;  // score：扣分制得分（100 起，high -15 / medium -8 / low -3）

    /** 问题清单 */
    private List<Issue> issues = new ArrayList<>();  // issues：问题清单

    /** 已执行/跳过的检查项名称（追溯哪些检查因数据缺失未执行） */
    private List<String> skippedChecks = new ArrayList<>();  // skippedChecks：因数据缺失而跳过的检查项（追溯用）

    /** 引擎已实际执行的检查项名称（数据充分、真跑了规则；含查出的问题和通过的项） */
    private List<String> executedChecks = new ArrayList<>();  // executedChecks：引擎真跑过的检查项

    /**
     * 作用：生成逐条明细的校验摘要（管理端/日志用）
     * 参数：无
     * 返回值：多行摘要文本
     * 异常：无
     */
    public String detailedSummary() {  // detailedSummary：生成逐条明细摘要
        StringBuilder sb = new StringBuilder(summary());  // 以一句话摘要打底
        if (!executedChecks.isEmpty()) {  // 有已执行项
            sb.append("\n引擎已实际执行的确定性检查（这些项以代码规则为准，无需再凭感觉复检）：")  // 换行 + 说明“这些项以代码规则为准”
              .append(String.join("、", executedChecks));  // 续：拼接检查项名
        }  // if 结束
        if (!skippedChecks.isEmpty()) {  // 有跳过项
            sb.append("\n引擎因数据缺失跳过的检查（这些项可由你补充人工判断）：")  // 换行 + 说明“可由你补充人工判断”
              .append(String.join("、", skippedChecks));  // 续：拼接跳过项名
        }  // if 结束
        return sb.toString();  // 返回多行摘要
    }  // 方法结束

    @Data  // @Data：Issue 也用 Lombok
    @NoArgsConstructor  // @NoArgsConstructor：无参构造
    public static class Issue {  // 内部类 Issue：一条校验问题
        /** 问题类型：budget/schedule/duplicate/transport/constraint */
        private String type;  // type：bucket/schedule/duplicate/transport/constraint
        /** 严重度：high/medium/low */
        private String severity;  // severity：high / medium / low
        /** 定位（如 day2 / hotel / overall） */
        private String target;  // target：定位（day2 / hotel / overall）
        /** 说明 */
        private String reason;  // reason：问题说明
        /**
         * 作用：构造一条校验问题
         * 参数：type 问题类型（天数/节奏/预算/地点等）；severity 严重级别；target 出问题的对象（天/地点）；reason 说明
         * 返回值：无
         * 异常：无
         */

        public Issue(String type, String severity, String target, String reason) {  // Issue 构造器
            this.type = type;  // 问题类型
            this.severity = severity;  // 严重度
            this.target = target;  // 定位
            this.reason = reason;  // 说明
        }  // 构造器结束
    }  // Issue 类结束
    /**
     * 作用：追加一条校验问题
     * 参数：issue 问题对象
     * 返回值：无
     * 异常：无
     */

    public void add(Issue issue) {  // add：追加一条问题并扣分
        issues.add(issue);  // 加入问题清单
        switch (issue.severity) {  // 按严重度扣分
            case "high" -> score -= 15;  // high 扣 15
            case "medium" -> score -= 8;  // medium 扣 8
            default -> score -= 3;  // low 扣 3
        }  // switch 结束
    }  // 方法结束

    /**
     * 作用：给出校验结论状态
     * 参数：无
     * 返回值：状态文本（通过/可交付/需重做）
     * 异常：无
     */
    public String resolveStatus() {  // resolveStatus：算最终结论
        boolean anyHigh = issues.stream().anyMatch(i -> "high".equals(i.severity));  // 是否有 high 级问题
        this.status = anyHigh ? "FAIL" : (issues.isEmpty() ? "PASS" : "WARN");  // 有 high → FAIL；无问题 → PASS；否则 WARN
        this.score = Math.max(0, this.score);  // 得分不为负
        return this.status;  // 返回结论
    }  // 方法结束

    /**
     * 作用：生成一句话校验摘要（过程事件展示用）
     * 参数：无
     * 返回值：摘要文本
     * 异常：无
     */
    public String summary() {  // summary：一句话摘要
        StringBuilder sb = new StringBuilder(status).append("(").append(score).append(")");  // 以“状态(分数)”开头
        for (int i = 0; i < issues.size() && i < 5; i++) {  // 最多带 5 条问题原因
            sb.append(i == 0 ? ": " : "; ").append(issues.get(i).reason);  // 第 1 条用“: ”分隔，其后用“; ”
        }  // for 结束
        if (issues.size() > 5) {  // 超过 5 条
            sb.append(" 等共").append(issues.size()).append("项");  // 补“等共N项”
        }  // if 结束
        return sb.toString();  // 返回摘要
    }  // 方法结束

    /**
     * 作用：生成检查项概览（各项是否通过）
     * 参数：无
     * 返回值：概览文本
     * 异常：无
     */
    public String checksBrief() {  // checksBrief：检查项概览
        StringBuilder sb = new StringBuilder("（已执行 ").append(executedChecks.size()).append(" 项确定性检查");  // 以“（已执行 N 项确定性检查”开头
        if (!skippedChecks.isEmpty()) {  // 有跳过项
            sb.append("，跳过 ").append(skippedChecks.size()).append(" 项");  // 补“，跳过 N 项”
        }  // if 结束
        return sb.append("）").toString();  // 补右括号并返回
    }  // 方法结束
}  // 类结束
