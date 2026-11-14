package managerAgent.orchestrator;

import utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reviewer 审查裁定（结构化）：verdict + 问题清单。
 * LLM 输出解析失败时按 PASS 兜底（审查器故障不阻断交付）。
 */
public class ReviewVerdict {  // 类声明：Reviewer 审查裁定（结论 + 问题清单）

    /** PASS=通过交付 / REVISE=需修改重跑 */
    private String verdict = "PASS";  // verdict：PASS 通过 / REVISE 需修改

    /** 审查质量分（0~100；-1=审查器未给出）；与确定性校验分（ValidationReport）互补 */
    private int score = -1;  // score：审查质量分（-1=未给）

    private List<Issue> issues = new ArrayList<>();  // issues：问题清单

    public static class Issue {  // 内部类 Issue：一条评审问题
        public String type;        // schedule/route/budget/duplicate/content/hotel/dining/preference/freshness  // type：schedule/route/budget/duplicate/content/hotel/dining/preference/freshness
        public String severity;    // high/medium/low  // severity：high/medium/low
        public String target;      // day2/hotel/overall  // target：day2/hotel/overall
        public String reason;  // reason：问题说明
        public String suggestion;  // suggestion：修改建议
        public String domain;      // attraction/hotel/dining/transport/none（按域定向重跑用）  // domain：所属域（按域定向重跑用）
        /**
         * 作用：构造空问题项（反序列化用）
         * 参数：无
         * 返回值：无
         * 异常：无
         */

        public Issue() {  // 无参构造（反序列化用）
        }  // 构造器结束
        /**
         * 作用：构造一条评审问题
         * 参数：type 问题类型；severity 严重级别；target 出问题的对象；reason 说明；suggestion 修改建议
         * 返回值：无
         * 异常：无
         */

        public Issue(String type, String severity, String target, String reason, String suggestion) {  // 构造器（五参）：不带域
            this(type, severity, target, reason, suggestion, "");  // 委托六参，域传空串
        }  // 构造器结束
        /**
         * 作用：构造一条带域归属的评审问题
         * 参数：type 问题类型；severity 严重级别；target 出问题的对象；reason 说明；suggestion 修改建议；domain 所属域（景点/酒店/餐饮）
         * 返回值：无
         * 异常：无
         */

        public Issue(String type, String severity, String target, String reason, String suggestion, String domain) {  // 构造器（六参）：带域归属
            this.type = type;  // 问题类型
            this.severity = severity;  // 严重度
            this.target = target;  // 定位
            this.reason = reason;  // 说明
            this.suggestion = suggestion;  // 修改建议
            this.domain = domain == null ? "" : domain;  // 所属域（null 归空串）
        }  // 构造器结束
    }  // 内部类结束
    /**
     * 作用：读取评审结论
     * 参数：无
     * 返回值：结论文本（PASS/REVISE 等）
     * 异常：无
     */

    public String getVerdict() {  // getVerdict：读结论
        return verdict;  // 返回 verdict
    }  // 方法结束
    /**
     * 作用：读取评审评分
     * 参数：无
     * 返回值：0~100 的分数
     * 异常：无
     */

    public int getScore() {  // getScore：读质量分
        return score;  // 返回 score
    }  // 方法结束
    /**
     * 作用：读取问题清单
     * 参数：无
     * 返回值：问题项列表
     * 异常：无
     */

    public List<Issue> getIssues() {  // getIssues：读问题清单
        return issues;  // 返回列表
    }  // 方法结束
    /**
     * 作用：判断是否必须重做
     * 参数：无
     * 返回值：true=需要重做
     * 异常：无
     */

    public boolean needsRevise() {  // needsRevise：是否必须重做
        return "REVISE".equalsIgnoreCase(verdict);  // 结论是 REVISE
    }  // 方法结束

    /**
     * 作用：把问题清单拼成给模型的反馈文本
     * 参数：无
     * 返回值：反馈文本；无问题时返回空串
     * 异常：无
     */
    public String feedbackText() {  // feedbackText：问题清单拼成给模型的反馈
        StringBuilder sb = new StringBuilder();  // sb：反馈缓冲
        for (int i = 0; i < issues.size() && i < 6; i++) {  // 最多取 6 条（防提示词过长）
            Issue is = issues.get(i);  // 取一条问题
            if (i > 0) sb.append("；");  // 非首条加分号
            sb.append("[").append(is.target == null ? "overall" : is.target).append("] ")  // 拼 [定位] 
                    .append(is.reason == null ? "" : is.reason)  // 续：问题说明
                    .append(is.suggestion == null || is.suggestion.isBlank() ? "" : "（建议：" + is.suggestion + "）");  // 续：有建议就带上（建议：xx）
        }  // for 结束
        return sb.toString();  // 返回反馈文本
    }  // 方法结束

    /**
     * 作用：把模型返回的评审 JSON 解析成评审结论
     * 参数：text 模型原始输出
     * 返回值：评审结论对象；解析失败返回默认"通过"结论
     * 异常：无（异常内部捕获）
     */
    public static ReviewVerdict parse(String text) {  // parse：把模型输出的评审 JSON 解析成对象
        Map<?, ?> node = JsonUtils.extractAndParse(text, Map.class);  // 先抠 JSON 再解析成 Map
        if (node == null) {  // 解析失败
            return null;  // 返回 null（调用方按“通过”兜底）
        }  // if 结束
        ReviewVerdict v = new ReviewVerdict();  // v：结果对象
        Object vd = node.get("verdict");  // 取 verdict 字段
        if (vd != null && "REVISE".equalsIgnoreCase(String.valueOf(vd))) {  // 明确是 REVISE
            v.verdict = "REVISE";  // 置为 REVISE
        }  // if 结束
        if (node.get("score") instanceof Number n) {  // score 是数字
            int s = n.intValue();  // 转 int
            v.score = (s >= 0 && s <= 100) ? s : -1;  // 0~100 才收，否则记 -1
        }  // if 结束
        Object arr = node.get("issues");  // 取 issues 数组
        if (arr instanceof List<?> list) {  // 是列表
            for (Object o : list) {  // 逐条处理
                if (!(o instanceof Map<?, ?> m)) continue;  // 不是 Map 就跳过
                Issue is = new Issue(  // 构造 Issue（六个字段全部安全转字符串）
                        str(m.get("type")),  // 续：type
                        str(m.get("severity")),  // 续：severity
                        str(m.get("target")),  // 续：target
                        str(m.get("reason")),  // 续：reason
                        str(m.get("suggestion")),  // 续：suggestion
                        str(m.get("domain")));  // 续：domain
                if (!is.reason.isBlank()) {  // 有实际说明才算有效问题
                    v.issues.add(is);  // 加入清单
                }  // if 结束
            }  // for 结束
        }  // if 结束
        return v;  // 返回裁定
    }  // 方法结束
    /**
     * 作用：安全转字符串
     * 参数：o 任意对象
     * 返回值：对象字符串；null 返回空串
     * 异常：无
     */

    private static String str(Object o) {  // str：安全转字符串
        return o == null ? "" : String.valueOf(o);  // null 归空串
    }  // 方法结束
}  // 类结束
