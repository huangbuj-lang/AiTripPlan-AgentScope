package utils;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性约束校验引擎单元测试（纯代码，不调 LLM）。
 */
class ConstraintEngineTest {

    private TripPlanData plan(int days, String... places) {
        TripPlanData p = new TripPlanData();
        p.setDays(days);
        for (int d = 1; d <= days; d++) {
            Section s = new Section();
            s.setType("timeline");
            s.setTitle("第" + d + "天");
            // 每天轮转取不同地点，避免跨天重复干扰纯天数/预算断言
            String pl = places[(d - 1) % places.length];
            SectionItem it = new SectionItem();
            it.setTime("09:00");
            it.setPlace(pl);
            it.setCost("100元");
            s.getItems().add(it);
            p.getSections().add(s);
        }
        return p;
    }

    /** 单天多 item 构造器：places 依次作为同一天内的多条记录 */
    private TripPlanData oneDayPlan(String... places) {
        TripPlanData p = new TripPlanData();
        p.setDays(1);
        Section s = new Section();
        s.setType("timeline");
        s.setTitle("第1天");
        for (String pl : places) {
            SectionItem it = new SectionItem();
            it.setTime("09:00");
            it.setPlace(pl);
            it.setCost("100元");
            s.getItems().add(it);
        }
        p.getSections().add(s);
        return p;
    }

    @Test
    void passesCleanPlan() {
        TripRequest req = new TripRequest();
        req.setDays(2);
        req.setBudgetTotal(100000);
        TripPlanData p = plan(2, "西湖", "灵隐寺");
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertEquals("PASS", vr.getStatus());
        assertEquals(100, vr.getScore());
        assertTrue(vr.getIssues().isEmpty());
    }

    @Test
    void failsOnDayMismatch() {
        TripRequest req = new TripRequest();
        req.setDays(3);
        TripPlanData p = plan(2, "西湖");
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertEquals("FAIL", vr.getStatus());
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("天数不符")));
    }

    @Test
    void failsOnBudgetOverrunAndWarnsNearLimit() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setBudgetTotal(150); // 1天×100元=100 → PASS
        assertEquals("PASS", ConstraintEngine.validate(req, plan(1, "西湖")).getStatus());

        req.setBudgetTotal(90); // 100 > 90 → FAIL
        ValidationReport over = ConstraintEngine.validate(req, plan(1, "西湖"));
        assertEquals("FAIL", over.getStatus());
        assertTrue(over.getIssues().stream().anyMatch(i -> "budget".equals(i.getType()) && i.getReason().contains("超支")));

        req.setBudgetTotal(105); // 100 > 105*0.9=94.5 → WARN
        ValidationReport near = ConstraintEngine.validate(req, plan(1, "西湖"));
        assertEquals("WARN", near.getStatus());
    }

    @Test
    void skipsBudgetWhenNoNumberBudgetOrNoParsableMoney() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "西湖"));
        assertTrue(vr.getSkippedChecks().stream().anyMatch(s -> s.contains("预算")));

        req.setBudgetTotal(100);
        TripPlanData p = plan(1, "西湖");
        p.getSections().get(0).getItems().get(0).setCost("以现场为准"); // 无法解析
        ValidationReport vr2 = ConstraintEngine.validate(req, p);
        assertTrue(vr2.getSkippedChecks().stream().anyMatch(s -> s.contains("无可解析金额")));
    }

    @Test
    void detectsMissingMustGo() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.getConstraints().add(new TripRequest.Constraint("必去外滩", "P0"));
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "西湖"));
        assertEquals("FAIL", vr.getStatus());
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("外滩")));
    }

    @Test
    void detectsAvoidViolation() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.getConstraints().add(new TripRequest.Constraint("避免动物园", "P1"));
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "动物园"));
        assertTrue(vr.getIssues().stream().anyMatch(i -> "constraint".equals(i.getType()) && i.getReason().contains("动物园")));
        assertEquals("WARN", vr.getStatus()); // P1 回避 → medium
    }

    @Test
    void passesWhenMustGoPresent() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.getConstraints().add(new TripRequest.Constraint("必去西湖", "P0"));
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "西湖"));
        assertTrue(vr.getIssues().isEmpty());
    }

    @Test
    void detectsDuplicatePlaceInSameDay() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = oneDayPlan("西湖", "西湖"); // 同天两次
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> "duplicate".equals(i.getType()) && i.getReason().contains("重复安排")));
    }

    @Test
    void transportAndDiningEntriesDoNotCountAsDuplicate() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = oneDayPlan("束河古镇", "束河古镇");
        p.getSections().get(0).getItems().get(0).setCategory("景点"); // 游览
        p.getSections().get(0).getItems().get(1).setCategory("交通"); // 返回束河=到达地
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertFalse(vr.getIssues().stream().anyMatch(i -> "duplicate".equals(i.getType())),
                "交通/到达地条目不应计为景点重复");
    }

    @Test
    void detectsTimeBackwards() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = oneDayPlan("西湖", "灵隐寺");
        p.getSections().get(0).getItems().get(0).setTime("14:00");
        p.getSections().get(0).getItems().get(1).setTime("09:00");
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> "schedule".equals(i.getType()) && i.getReason().contains("倒序")));
    }

    @Test
    void detectsLongTransport() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = plan(1, "西湖");
        Section t = new Section();
        t.setType("transport");
        t.setTitle("交通");
        SectionItem leg = new SectionItem();
        leg.setFrom("A");
        leg.setTo("B");
        leg.setDuration("7小时"); // 420 > 360
        t.getItems().add(leg);
        p.getSections().add(t);
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> "transport".equals(i.getType())));
    }

    @Test
    void nullPlanFailsFast() {
        ValidationReport vr = ConstraintEngine.validate(new TripRequest(), null);
        assertEquals("FAIL", vr.getStatus());
    }

    @Test
    void parseMoneyVariants() {
        assertEquals(0.0, ConstraintEngine.parseMoney("免费"));
        assertEquals(80.0, ConstraintEngine.parseMoney("80元/人"));
        assertEquals(15000.0, ConstraintEngine.parseMoney("1.5万"));
        assertEquals(120.0, ConstraintEngine.parseMoney("¥120"));
        assertNull(ConstraintEngine.parseMoney("以现场为准"));
        assertNull(ConstraintEngine.parseMoney(""));
        assertNull(ConstraintEngine.parseMoney(null));
    }

    @Test
    void parseDurationAndTimeVariants() {
        assertEquals(150, ConstraintEngine.parseDurationMinutes("2小时30分"));
        assertEquals(150, ConstraintEngine.parseDurationMinutes("2.5小时"));
        assertEquals(90, ConstraintEngine.parseDurationMinutes("90分钟"));
        assertEquals(180, ConstraintEngine.parseDurationMinutes("3h"));
        assertEquals(-1, ConstraintEngine.parseDurationMinutes("很快"));
        assertEquals(540, ConstraintEngine.parseMinutesOfDay("09:00"));
        assertEquals(1305, ConstraintEngine.parseMinutesOfDay("21:45"));
        assertEquals(-1, ConstraintEngine.parseMinutesOfDay("上午"));
        assertEquals(-1, ConstraintEngine.parseMinutesOfDay(null));
    }

    @Test
    void stripPrefixWorks() {
        assertEquals("外滩", ConstraintEngine.stripPrefix("必去外滩", new String[]{"必去", "一定要去"}));
        assertNull(ConstraintEngine.stripPrefix("预算不超过8000元", new String[]{"必去", "不去"}));
        assertEquals("动物园", ConstraintEngine.stripPrefix("避开动物园", new String[]{"避开", "不去"}));
    }
}
