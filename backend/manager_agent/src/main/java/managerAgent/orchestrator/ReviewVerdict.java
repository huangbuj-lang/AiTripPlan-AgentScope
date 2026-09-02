package managerAgent.orchestrator;

import utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reviewer 审查裁定（结构化）：verdict + 问题清单。
 * LLM 输出解析失败时按 PASS 兜底（审查器故障不阻断交付）。
 */
public class ReviewVerdict {

    /** PASS=通过交付 / REVISE=需修改重跑 */
    private String verdict = "PASS";

    private List<Issue> issues = new ArrayList<>();

    public static class Issue {
        public String type;        // schedule/route/budget/content
        public String severity;    // high/medium/low
        public String target;      // day2/hotel/overall
        public String reason;
        public String suggestion;

        public Issue() {
        }

        public Issue(String type, String severity, String target, String reason, String suggestion) {
            this.type = type;
            this.severity = severity;
            this.target = target;
            this.reason = reason;
            this.suggestion = suggestion;
        }
    }

    public String getVerdict() {
        return verdict;
    }

    public List<Issue> getIssues() {
        return issues;
    }

    public boolean needsRevise() {
        return "REVISE".equalsIgnoreCase(verdict);
    }

    /** 把问题清单压成一行反馈文本（聚合重跑 prompt 用） */
    public String feedbackText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size() && i < 6; i++) {
            Issue is = issues.get(i);
            if (i > 0) sb.append("；");
            sb.append("[").append(is.target == null ? "overall" : is.target).append("] ")
                    .append(is.reason == null ? "" : is.reason)
                    .append(is.suggestion == null || is.suggestion.isBlank() ? "" : "（建议：" + is.suggestion + "）");
        }
        return sb.toString();
    }

    /** 解析 LLM 输出（容忍围栏/前后缀）；解析失败返回 null。 */
    public static ReviewVerdict parse(String text) {
        Map<?, ?> node = JsonUtils.extractAndParse(text, Map.class);
        if (node == null) {
            return null;
        }
        ReviewVerdict v = new ReviewVerdict();
        Object vd = node.get("verdict");
        if (vd != null && "REVISE".equalsIgnoreCase(String.valueOf(vd))) {
            v.verdict = "REVISE";
        }
        Object arr = node.get("issues");
        if (arr instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Issue is = new Issue(
                        str(m.get("type")),
                        str(m.get("severity")),
                        str(m.get("target")),
                        str(m.get("reason")),
                        str(m.get("suggestion")));
                if (!is.reason.isBlank()) {
                    v.issues.add(is);
                }
            }
        }
        return v;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
