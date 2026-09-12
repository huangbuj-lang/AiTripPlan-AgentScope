package dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 交付前校验报告（约束判据的载体）。
 *
 * <p>设计原则：<b>约束若无法客观判定，就必须显式报告"无法判定"，不得当作通过</b>。
 * 因此每条条目带 {@code severity}：
 * <ul>
 *   <li>{@code BLOCK}  —— 违反硬约束，必须打回重做</li>
 *   <li>{@code WARN}   —— 可疑但不足以阻断（如疑似编造、时间线轻微不连续）</li>
 *   <li>{@code UNKNOWN}—— 数据不足，无法判定（缺 duration / 缺坐标等）</li>
 * </ul>
 *
 * <p>本类为纯数据载体，不依赖任何运行时组件。
 */
public class ValidationReport {

    /** 违反条目的严重度 */
    public enum Severity { BLOCK, WARN, UNKNOWN }

    /** 单条违反记录 */
    public static class Violation {
        /** 归属天数；全局性问题为 0 */
        public int day;
        /** 规则编号，如 "A1" */
        public String rule = "";
        /** 规则简述，如 "每天游玩+车程≤8h" */
        public String ruleName = "";
        public Severity severity = Severity.WARN;
        /** 具体证据，如 "第2天 游玩5.0h+车程4.5h=9.5h 超出 1.5h" */
        public String detail = "";

        public Violation() {}

        public Violation(int day, String rule, String ruleName, Severity severity, String detail) {
            this.day = day;
            this.rule = rule;
            this.ruleName = ruleName;
            this.severity = severity;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (day > 0 ? "第" + day + "天 " : "") + rule + "(" + ruleName + "):" + detail;
        }
    }

    private final List<Violation> violations = new ArrayList<>();
    /** 参与判定的天数（用于说明判定覆盖面） */
    private int checkedDays = 0;
    /** 无法判定的天数（数据不足） */
    private int unknownDays = 0;

    public void add(int day, String rule, String ruleName, Severity severity, String detail) {
        violations.add(new Violation(day, rule, ruleName, severity, detail));
    }

    public void add(Violation v) {
        violations.add(v);
    }

    public List<Violation> getViolations() {
        return violations;
    }

    /** 是否存在必须打回重做的违反（BLOCK） */
    public boolean hasBlocking() {
        for (Violation v : violations) {
            if (v.severity == Severity.BLOCK) return true;
        }
        return false;
    }

    public List<Violation> blocking() {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            if (v.severity == Severity.BLOCK) out.add(v);
        }
        return out;
    }

    public List<Violation> warnings() {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            if (v.severity == Severity.WARN) out.add(v);
        }
        return out;
    }

    /** 是否整体通过（无 BLOCK）；UNKNOWN 不影响通过，但会被如实报告 */
    public boolean passed() {
        return !hasBlocking();
    }

    /** 供"打回重做"时附加到任务里的修正清单（只列 BLOCK，带具体证据） */
    public String toFixInstruction() {
        List<Violation> bs = blocking();
        if (bs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Violation v : bs) {
            sb.append("- [").append(v.rule).append("] ").append(v.detail).append("\n");
        }
        return sb.toString();
    }

    /** 供事件/日志展示的一句话摘要 */
    public String summary() {
        int b = blocking().size(), w = warnings().size(), u = 0;
        for (Violation v : violations) {
            if (v.severity == Severity.UNKNOWN) u++;
        }
        return "校验：硬违反 " + b + " 条、可疑 " + w + " 条、无法判定 " + u + " 条"
                + "（已判定 " + checkedDays + " 天，数据不足 " + unknownDays + " 天）";
    }

    public int getCheckedDays() { return checkedDays; }
    public void setCheckedDays(int checkedDays) { this.checkedDays = checkedDays; }
    public int getUnknownDays() { return unknownDays; }
    public void setUnknownDays(int unknownDays) { this.unknownDays = unknownDays; }
}
