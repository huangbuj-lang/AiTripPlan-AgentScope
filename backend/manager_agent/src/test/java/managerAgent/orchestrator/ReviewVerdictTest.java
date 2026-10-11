package managerAgent.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reviewer 裁定解析单元测试（纯代码，不调 LLM）。
 */
class ReviewVerdictTest {

    @Test
    void parsesPlainJsonPass() {
        ReviewVerdict v = ReviewVerdict.parse("{\"verdict\":\"PASS\",\"issues\":[]}");
        assertNotNull(v);
        assertFalse(v.needsRevise());
        assertTrue(v.getIssues().isEmpty());
    }

    @Test
    void parsesReviseWithIssues() {
        String json = "```json\n{\"verdict\":\"REVISE\",\"issues\":[" +
                "{\"type\":\"schedule\",\"severity\":\"high\",\"target\":\"day2\",\"reason\":\"安排过赶\",\"suggestion\":\"拆分景点\"}," +
                "{\"type\":\"budget\",\"severity\":\"low\",\"target\":\"overall\",\"reason\":\"略超预算\"}]}\n```";
        ReviewVerdict v = ReviewVerdict.parse(json);
        assertNotNull(v);
        assertTrue(v.needsRevise());
        assertEquals(2, v.getIssues().size());
        assertEquals("day2", v.getIssues().get(0).target);
        assertEquals("拆分景点", v.getIssues().get(0).suggestion);
    }

    @Test
    void feedbackTextJoinsIssues() {
        ReviewVerdict v = ReviewVerdict.parse("{\"verdict\":\"REVISE\",\"issues\":[" +
                "{\"type\":\"schedule\",\"severity\":\"high\",\"target\":\"day2\",\"reason\":\"安排过赶\",\"suggestion\":\"拆分景点\"}," +
                "{\"type\":\"budget\",\"severity\":\"low\",\"target\":\"overall\",\"reason\":\"略超预算\"}]}");
        String fb = v.feedbackText();
        assertTrue(fb.contains("[day2]"));
        assertTrue(fb.contains("建议：拆分景点"));
        assertTrue(fb.contains("[overall]"));
        assertTrue(fb.contains("；"));
    }

    @Test
    void tolerateMissingFieldsAndBlankIssues() {
        ReviewVerdict v = ReviewVerdict.parse("{\"verdict\":\"REVISE\",\"issues\":[{\"reason\":\"\"},{\"severity\":\"high\"}]}");
        assertNotNull(v);
        assertEquals(0, v.getIssues().size(), "空 reason 的 issue 应被丢弃，无 reason 的也不入库");
    }

    @Test
    void nullOrGarbageReturnsNull() {
        assertNull(ReviewVerdict.parse(null));
        assertNull(ReviewVerdict.parse("审查没输出 JSON"));
    }

    @Test
    void unknownVerdictDefaultsToPass() {
        ReviewVerdict v = ReviewVerdict.parse("{\"verdict\":\"OK\"}");
        assertNotNull(v);
        assertFalse(v.needsRevise());
    }
}
