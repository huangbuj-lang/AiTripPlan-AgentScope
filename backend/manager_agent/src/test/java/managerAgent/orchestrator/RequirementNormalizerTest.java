package managerAgent.orchestrator;

import dto.TripRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求规整器单元测试：预算数值化 + P0~P3 约束兜底提取（纯代码，不调 LLM）。
 */
class RequirementNormalizerTest {

    @Test
    void parsesPlainYuanBudget() {
        assertEquals(8000, RequirementNormalizer.parseBudgetYuan("预算8000"), 0.001);
        assertEquals(8000, RequirementNormalizer.parseBudgetYuan("预算 8000 元左右"), 0.001);
        assertEquals(8000, RequirementNormalizer.parseBudgetYuan("预算控制在8000以内"), 0.001);
    }

    @Test
    void parsesWanQianKUnits() {
        assertEquals(10000, RequirementNormalizer.parseBudgetYuan("预算1万"), 0.001);
        assertEquals(15000, RequirementNormalizer.parseBudgetYuan("预算1.5万"), 0.001);
        assertEquals(8000, RequirementNormalizer.parseBudgetYuan("花费8千"), 0.001);
        assertEquals(10000, RequirementNormalizer.parseBudgetYuan("预算10k"), 0.001);
        assertEquals(10000, RequirementNormalizer.parseBudgetYuan("预算10K"), 0.001);
    }

    @Test
    void returnsZeroOnMissingOrAbsurdBudget() {
        assertEquals(0, RequirementNormalizer.parseBudgetYuan("我想去东京玩5天"));
        assertEquals(0, RequirementNormalizer.parseBudgetYuan(null));
        assertEquals(0, RequirementNormalizer.parseBudgetYuan("预算50")); // 过小视为无效
        assertEquals(0, RequirementNormalizer.parseBudgetYuan("预算2000万")); // 过大视为无效
    }

    @Test
    void keepsLlmParsedBudgetAndSkipsReparse() {
        TripRequest req = new TripRequest();
        req.setRaw("东京5天");
        req.setBudgetTotal(12000);
        RequirementNormalizer.normalize(req);
        assertEquals(12000, req.getBudgetTotal(), 0.001);
    }

    @Test
    void extractsBudgetConstraintAsP0WhenLlmMissed() {
        TripRequest req = new TripRequest();
        req.setRaw("去成都玩4天，预算8000，不要太赶");
        RequirementNormalizer.normalize(req);
        assertEquals(8000, req.getBudgetTotal(), 0.001);
        assertTrue(req.hasHardConstraints(), "预算应登记为 P0 硬约束");
        assertEquals(1, req.constraintTexts("P0").size());
        assertTrue(req.constraintTexts("P0").get(0).contains("8000"));
    }

    @Test
    void extractsMustGoAndAvoidFromRaw() {
        TripRequest req = new TripRequest();
        req.setRaw("重庆3日游，必去洪崖洞，不去动物园");
        RequirementNormalizer.normalize(req);
        List<String> p0 = req.constraintTexts("P0");
        assertTrue(p0.stream().anyMatch(t -> t.contains("洪崖洞")), "必去应为 P0");
        List<String> p1 = req.constraintTexts("P1");
        assertTrue(p1.stream().anyMatch(t -> t.contains("动物园")), "回避应为 P1");
    }

    @Test
    void dedupesAndKeepsValidLevels() {
        TripRequest req = new TripRequest();
        req.setRaw("");
        TripRequest.Constraint c1 = new TripRequest.Constraint("少走路", "P1");
        TripRequest.Constraint c2 = new TripRequest.Constraint("少走路", "P2"); // 重复，保留首个
        TripRequest.Constraint c3 = new TripRequest.Constraint("吃好点", "XX"); // 非法层级→P2
        req.getConstraints().addAll(List.of(c1, c2, c3));
        RequirementNormalizer.normalize(req);
        assertEquals(2, req.getConstraints().size());
        assertEquals("P1", req.getConstraints().get(0).getLevel());
        assertEquals("P2", req.getConstraints().get(1).getLevel());
    }

    @Test
    void capsConstraintCount() {
        TripRequest req = new TripRequest();
        req.setRaw("");
        for (int i = 0; i < 20; i++) {
            req.getConstraints().add(new TripRequest.Constraint("约束" + i, "P2"));
        }
        RequirementNormalizer.normalize(req);
        assertEquals(8, req.getConstraints().size(), "约束应截断到上限 8 条");
    }

    @Test
    void toleratesNullRequestAndNullConstraintList() {
        RequirementNormalizer.normalize(null); // 不抛异常
        TripRequest req = new TripRequest();
        req.setRaw("");
        req.setConstraints(null);
        assertDoesNotThrow(() -> RequirementNormalizer.normalize(req));
        assertNotNull(req.getConstraints());
    }
}
