package utils;

import dto.ScoreResult;
import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 六维加权评分模型单元测试（纯代码，不调 LLM）。
 */
class PlanScorerTest {

    private TripPlanData plan(int days) {
        TripPlanData p = new TripPlanData();
        p.setDays(days);
        for (int d = 1; d <= days; d++) {
            Section tl = new Section();
            tl.setType("timeline");
            tl.setTitle("第" + d + "天");
            for (int i = 0; i < 4; i++) {
                SectionItem it = new SectionItem();
                it.setTime("0" + (9 + i) + ":00");
                it.setPlace("景点" + d + "-" + i);
                it.setCost("80元");
                tl.getItems().add(it);
            }
            p.getSections().add(tl);
        }
        Section stay = new Section();
        stay.setType("stay");
        stay.setTitle("住宿餐饮");
        SectionItem hotel = new SectionItem();
        hotel.setName("示例酒店");
        hotel.setPrice("500元");
        stay.getItems().add(hotel);
        p.getSections().add(stay);
        return p;
    }

    private TripRequest req(int days, double budget) {
        TripRequest r = new TripRequest();
        r.setDays(days);
        r.setBudgetTotal(budget);
        return r;
    }

    @Test
    void goodPlanScoresHigh() {
        ScoreResult sr = PlanScorer.score(req(2, 100000), plan(2), null, true, true);
        assertTrue(sr.getTotal() >= 80, "数据齐全且无问题的方案应得高分, 实际 " + sr.getTotal());
        assertEquals(6, sr.getBreakdown().size());
        assertTrue(sr.getSummary().contains("总分"));
    }

    @Test
    void failingValidationLowersFeasibility() {
        ValidationReport bad = new ValidationReport();
        bad.add(new ValidationReport.Issue("schedule", "high", "overall", "天数不符"));
        bad.resolveStatus();
        ScoreResult fail = PlanScorer.score(req(1, 100000), plan(1), bad, true, true);
        ScoreResult pass = PlanScorer.score(req(1, 100000), plan(1), null, true, true);
        assertTrue(fail.getTotal() < pass.getTotal(), "FAIL 方案总分应低于 PASS 方案");
        assertTrue(fail.getBreakdown().get("feasible") < pass.getBreakdown().get("feasible"));
    }

    @Test
    void budgetOverrunLowersBudgetScore() {
        ScoreResult ok = PlanScorer.score(req(2, 100000), plan(2), null, true, true); // 16×80+500=1780 << 100000
        ScoreResult over = PlanScorer.score(req(2, 1000), plan(2), null, true, true); // 1780 > 1000 超支
        assertTrue(over.getBreakdown().get("budget") < ok.getBreakdown().get("budget"));
        assertTrue(over.getBreakdown().get("budget") >= 0);
    }

    @Test
    void neutralScoresWhenDataMissing() {
        ScoreResult sr = PlanScorer.score(req(1, 0), plan(1), null, false, false);
        assertEquals(70, sr.getBreakdown().get("budget")); // 无预算 → 中性
        assertEquals(70, sr.getBreakdown().get("efficiency")); // 无交通 → 中性
        assertEquals(30, sr.getBreakdown().get("reliability")); // 双侧专家缺失
        assertTrue(sr.getTotal() > 0);
    }

    @Test
    void nullPlanScoresZero() {
        ScoreResult sr = PlanScorer.score(req(1, 1000), null, null, false, false);
        assertEquals(0, sr.getTotal());
    }

    @Test
    void satisfiedConstraintsBoostMatch() {
        ValidationReport pass = new ValidationReport();
        pass.resolveStatus(); // PASS 且无 issues：约束全部满足的前提
        TripRequest r = req(1, 100000);
        r.getConstraints().add(new TripRequest.Constraint("必去景点1-0", "P0"));
        ScoreResult with = PlanScorer.score(r, plan(1), pass, true, true);
        ScoreResult without = PlanScorer.score(req(1, 100000), plan(1), pass, true, true);
        assertTrue(with.getBreakdown().get("match") > without.getBreakdown().get("match"),
                "明确约束全部满足应加分");
    }

    @Test
    void bestPicksHighestIndex() {
        assertEquals(1, PlanScorer.best(List.of(70, 88, 85)));
        assertEquals(0, PlanScorer.best(List.of(90, 85)));
        assertEquals(2, PlanScorer.best(java.util.Arrays.asList(null, 60, 77))); // null 跳过
        assertEquals(-1, PlanScorer.best(List.of()));
        assertEquals(-1, PlanScorer.best(java.util.Arrays.asList(null, null)));
    }
}
