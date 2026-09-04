package managerAgent.orchestrator;

import managerAgent.hook.EventStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReviseSummary（任务2）：Reviewer 闭环结构化观测的装配逻辑。
 */
class ReviseSummaryTest {

    @Test
    void recordsRoundsAndOutcomeWithMeta() {
        String sid = "rev-sum-" + System.nanoTime();
        EventStore.startTask(sid, 42); // 注册 meta（TaskMeta.reviseRounds/reviseOutcome 跟随更新）

        ReviewVerdict.Issue is = new ReviewVerdict.Issue("schedule", "medium", "day2", "第2天3个景点跨度过大", "拆分到两天", "attraction");
        ReviseSummary.recordRound(sid, 1, "REVISE", 85, List.of(ReviseSummary.issueSnapshot(is)), "rework");
        ReviseSummary.recordRound(sid, 2, "PASS", 92, null, "accepted");
        ReviseSummary.finish(sid, "pass_after_rework");

        List<Map<String, Object>> rounds = ReviseSummary.roundsOf(sid);
        assertEquals(2, rounds.size());
        assertEquals(1, rounds.get(0).get("round"));
        assertEquals("REVISE", rounds.get(0).get("verdict"));
        assertEquals(85, rounds.get(0).get("qualityScore"));
        assertEquals("rework", rounds.get(0).get("action"));
        assertEquals(2, rounds.get(1).get("round"));
        // issue 快照字段
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) rounds.get(0).get("issues");
        assertEquals(1, issues.size());
        assertEquals("attraction", issues.get(0).get("domain"));
        assertEquals("medium", issues.get(0).get("severity"));
        assertEquals("day2", issues.get(0).get("target"));
        assertEquals("第2天3个景点跨度过大", issues.get(0).get("reason"));
        // meta 跟随
        EventStore.TaskMeta meta = EventStore.metaOf(sid);
        assertNotNull(meta);
        assertEquals(2, meta.reviseRounds);
        assertEquals("pass_after_rework", meta.reviseOutcome);
        // finish 落 extras
        assertEquals("pass_after_rework", EventStore.getExtra(sid, "reviseOutcome"));
        EventStore.finishTask(sid, "DONE"); // 清理
    }

    @Test
    void roundsOfIsolatesSessionsAndHandlesMissing() {
        String a = "rev-a-" + System.nanoTime();
        String b = "rev-b-" + System.nanoTime();
        assertTrue(ReviseSummary.roundsOf(b).isEmpty(), "未记录会话返回空列表");
        ReviseSummary.recordRound(a, 1, "REVISE", 80, null, "rework");
        assertEquals(1, ReviseSummary.roundsOf(a).size());
        assertTrue(ReviseSummary.roundsOf(b).isEmpty(), "会话间不串数据");
        assertNull(EventStore.getExtra(b, "reviseOutcome"), "未知会话 extras 读取返回 null");
    }

    @Test
    void maxedOutcomeMarkedOnLimit() {
        String sid = "rev-max-" + System.nanoTime();
        EventStore.startTask(sid, -1);
        ReviewVerdict.Issue is = new ReviewVerdict.Issue("duplicate", "high", "overall", "景点跨天重复", "", "none");
        ReviseSummary.recordRound(sid, 2, "REVISE", 78, List.of(ReviseSummary.issueSnapshot(is)), "maxed_rework");
        ReviseSummary.finish(sid, "maxed");
        assertEquals("maxed", EventStore.getExtra(sid, "reviseOutcome"));
        List<Map<String, Object>> rounds = ReviseSummary.roundsOf(sid);
        assertEquals("maxed_rework", rounds.get(0).get("action"));
        assertEquals("none", ((List<?>) rounds.get(0).get("issues") instanceof List<?> l ? ((Map<?, ?>) l.get(0)).get("domain") : ""));
        EventStore.finishTask(sid, "DONE");
    }
}
