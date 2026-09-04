package managerAgent.orchestrator;

import managerAgent.hook.EventStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reviewer 闭环结构化观测（任务2）：把每轮 REVISE 的触发原因（issue 域/严重度/定位）与结局
 * 存为结构化数据（EventStore extras + TaskMeta），供管理端展示"返工几轮/每轮为何被驳回/最终结局"。
 * 纯静态装配，不调 LLM、不抛异常（观测失败不影响编排）。
 */
public final class ReviseSummary {

    /** 一轮 REVISE 的结构化记录 */
    public static class Round {
        public int round;                 // 第几轮（1 起）
        public String verdict;            // PASS / REVISE / PARSE_ERROR
        public int qualityScore = -1;     // Reviewer 质量分（-1=未给）
        public List<Map<String, Object>> issues = new ArrayList<>(); // {type,severity,target,domain,reason}
        public String action = "";        // 本轮处置：rework/maxed_rework（末轮仍REVISE记已达上限）/accepted/none
    }

    /** 写入一轮记录（reviewerLoop 每轮调用）；issues 为 ReviewVerdict.Issue 的字段快照 */
    public static void recordRound(String sessionId, int round, String verdict, int qualityScore,
                                   List<Map<String, Object>> issues, String action) {
        try {
            List<Map<String, Object>> rounds = roundsOf(sessionId);
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("round", round);
            r.put("verdict", verdict);
            r.put("qualityScore", qualityScore);
            if (issues != null) r.put("issues", issues);
            r.put("action", action == null ? "" : action);
            rounds.add(r);
            EventStore.putExtra(sessionId, "reviseRounds", rounds);
            // 同步任务级摘要（历史表列用）
            EventStore.TaskMeta meta = EventStore.metaOf(sessionId);
            if (meta != null) {
                meta.reviseRounds = Math.max(meta.reviseRounds, round);
                if (action != null) meta.reviseOutcome = action;
            }
        } catch (Exception ignored) { }
    }

    /** 标记闭环最终结局（pass=审查通过收尾 / maxed=达上限交付 / rework_failed 等） */
    public static void finish(String sessionId, String outcome) {
        try {
            EventStore.putExtra(sessionId, "reviseOutcome", outcome == null ? "" : outcome);
            EventStore.TaskMeta meta = EventStore.metaOf(sessionId);
            if (meta != null && outcome != null && !outcome.isBlank()) meta.reviseOutcome = outcome;
        } catch (Exception ignored) { }
    }

    /** 读某会话已记录的轮次列表（无记录返回空可变列表） */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> roundsOf(String sessionId) {
        Object cur = EventStore.getExtra(sessionId, "reviseRounds");
        if (cur instanceof List) return (List<Map<String, Object>>) cur;
        return new ArrayList<>();
    }

    /** ReviewVerdict.Issue → 展示用字段快照（type/severity/target/domain/reason） */
    public static Map<String, Object> issueSnapshot(ReviewVerdict.Issue is) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (is == null) return m;
        m.put("type", is.type);
        m.put("severity", is.severity);
        m.put("target", is.target);
        m.put("domain", is.domain);
        m.put("reason", is.reason);
        return m;
    }

    private ReviseSummary() { }
}
