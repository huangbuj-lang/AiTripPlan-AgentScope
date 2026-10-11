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
            it.setTime("09:00-11:00");
            it.setPlace(pl);
            it.setCost("100元");
            s.getItems().add(it);
            p.getSections().add(s);
        }
        return p;
    }

    /** 向第 day 天 timeline 追加一个景点条目（pace 用例辅助） */
    private void appendSpot(TripPlanData p, int day, String place) {
        int d = 0;
        for (Section s : p.getSections()) {
            if (!"timeline".equals(s.getType())) continue;
            d++;
            if (d == day) {
                SectionItem it = new SectionItem();
                it.setTime("13:00-15:00");
                it.setPlace(place);
                s.getItems().add(it);
                return;
            }
        }
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
            it.setTime("09:00-11:00");
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
        appendSpot(p, 1, "断桥残雪"); // pace 校验要求每天≥2景点
        appendSpot(p, 2, "河坊街");
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
        TripPlanData ok = plan(1, "西湖");
        appendSpot(ok, 1, "断桥残雪"); // pace 校验要求每天≥2景点
        assertEquals("PASS", ConstraintEngine.validate(req, ok).getStatus());

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
        TripPlanData p = plan(1, "西湖");
        appendSpot(p, 1, "断桥残雪"); // pace 校验要求每天≥2景点
        ValidationReport vr = ConstraintEngine.validate(req, p);
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

    // ==================== ⑤ 营业时间 / ⑥ 酒店位置（候选数据驱动） ====================

    /** 构造三域候选 JSON（attraction 含营业时间；hotel 含城市与区域） */
    private String candidatesJson(String openTime, String hotelCity, String hotelArea) {
        return "{\"attraction\":{\"candidates\":[{\"name\":\"西湖\",\"openTime\":\"" + openTime + "\",\"confidence\":0.9}]},"
                + "\"hotel\":{\"candidates\":[{\"name\":\"湖畔酒店\",\"city\":\"" + hotelCity + "\",\"area\":\"" + hotelArea + "\",\"confidence\":0.9}]}}";
    }

    @Test
    void flagsAttractionOutsideOpeningHours() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = plan(1, "西湖");
        p.getSections().get(0).getItems().get(0).setTime("19:30"); // 08:00-17:30 之外
        ValidationReport vr = ConstraintEngine.validate(req, p, candidatesJson("08:00-17:30", "杭州", "西湖区"));
        assertTrue(vr.getIssues().stream().anyMatch(i -> "opening".equals(i.getType())),
                "超营业时间应报 opening 问题");
    }

    @Test
    void passesAttractionWithinOpeningHours() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = plan(1, "西湖");
        p.getSections().get(0).getItems().get(0).setTime("09:00"); // 时间段内
        ValidationReport vr = ConstraintEngine.validate(req, p, candidatesJson("08:00-17:30", "杭州", "西湖区"));
        assertFalse(vr.getIssues().stream().anyMatch(i -> "opening".equals(i.getType())));
    }

    @Test
    void skipsOpeningHoursWithoutCandidates() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = plan(1, "西湖");
        p.getSections().get(0).getItems().get(0).setTime("19:30");
        ValidationReport vr = ConstraintEngine.validate(req, p, null);
        assertTrue(vr.getIssues().stream().noneMatch(i -> "opening".equals(i.getType())),
                "无候选数据时应跳过而非误报");
        assertTrue(vr.getSkippedChecks().stream().anyMatch(s -> s.contains("营业时间")));
    }

    @Test
    void flagsTooFewSpotsPerDay() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = plan(1, "西湖"); // 每天1个景点 → medium
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("内容过少")), vr.detailedSummary());
        // 补足后无 pace issue
        appendSpot(p, 1, "断桥残雪");
        ValidationReport vr2 = ConstraintEngine.validate(req, p);
        assertTrue(vr2.getIssues().stream().noneMatch(i -> i.getReason().contains("景点，内容过少") || i.getReason().contains("内容过少")));
    }

    @Test
    void flagsLongPureDriving() {
        // 自驾：单日 10.5h 纯赶路 0 景点 → 报"超6h上限" + "纯赶路必须景点"
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setTravelType("SELF_DRIVE");
        TripPlanData p = new TripPlanData();
        p.setDays(1);
        Section s = new Section();
        s.setType("timeline");
        s.setTitle("第1天");
        SectionItem transit = new SectionItem();
        transit.setTime("08:00-18:30");
        transit.setPlace("稻城→塔公");
        transit.setCategory("交通");
        transit.setNote("今日纯赶路，不安排游览");
        s.getItems().add(transit);
        p.getSections().add(s);
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("超过 6 小时上限")), vr.detailedSummary());
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("纯赶路")), vr.detailedSummary());
    }

    @Test
    void countsTransportAsTravelDay() {
        // 自驾第1天：timeline 仅 2h 景点，但 transport 段标"第1天"duration 6h → 判赶路日，不报"内容过少"
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setTravelType("SELF_DRIVE");
        TripPlanData p = new TripPlanData();
        p.setDays(1);
        Section tl = new Section();
        tl.setType("timeline");
        tl.setTitle("第1天");
        SectionItem spot = new SectionItem();
        spot.setTime("14:00-16:00");
        spot.setPlace("泸定桥");
        spot.setCategory("景点");
        tl.getItems().add(spot);
        p.getSections().add(tl);
        Section tp = new Section();
        tp.setType("transport");
        tp.setTitle("跨城自驾衔接");
        SectionItem leg = new SectionItem();
        leg.setTime("第1天");
        leg.setFrom("成都");
        leg.setTo("新都桥");
        leg.setDuration("约6小时");
        tp.getItems().add(leg);
        p.getSections().add(tp);
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().noneMatch(i -> i.getReason().contains("内容过少")), vr.detailedSummary());
    }

    @Test
    void detectsNightDriving() {
        assertTrue(ConstraintEngine.isNightTime("22:00-01:00"));  // 跨天恒为夜间
        assertTrue(ConstraintEngine.isNightTime("21:00-23:00"));  // 夜间结束
        assertTrue(ConstraintEngine.isNightTime("05:00-07:00"));  // 凌晨开始
        assertFalse(ConstraintEngine.isNightTime("08:00-13:00")); // 白天
        assertFalse(ConstraintEngine.isNightTime("18:00-19:30")); // 傍晚，20点前
    }

    @Test
    void localTransitNotCountedAsTravel() {
        // category="衔接"（市内通勤）不计入赶路：单城 1 景点 2h + 1 段衔接 30min → 不报赶路，但游玩 2h<4h 报内容过少
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = new TripPlanData();
        p.setDays(1);
        Section s = new Section();
        s.setType("timeline");
        s.setTitle("第1天");
        SectionItem spot = new SectionItem();
        spot.setTime("09:00-11:00");
        spot.setPlace("某景点");
        spot.setCategory("景点");
        s.getItems().add(spot);
        SectionItem link = new SectionItem();
        link.setTime("08:00-08:30");
        link.setPlace("酒店→景点");
        link.setCategory("衔接");
        s.getItems().add(link);
        p.getSections().add(s);
        ValidationReport vr2 = ConstraintEngine.validate(req, p);
        assertTrue(vr2.getIssues().stream().noneMatch(i -> i.getReason().contains("在途") || i.getReason().contains("赶路")), vr2.detailedSummary());
        assertTrue(vr2.getIssues().stream().anyMatch(i -> i.getReason().contains("内容过少")), vr2.detailedSummary());
    }

    @Test
    void allowsBigAttractionFullDay() {
        // 大景区 1 个玩一整天（8小时）→ 充实，不应报"内容过少"
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = new TripPlanData();
        p.setDays(1);
        Section s = new Section();
        s.setType("timeline");
        s.setTitle("第1天");
        SectionItem big = new SectionItem();
        big.setTime("08:00-16:00");
        big.setPlace("九寨沟风景区");
        big.setCategory("景点");
        s.getItems().add(big);
        p.getSections().add(s);
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().noneMatch(i -> i.getReason().contains("内容过少")), vr.detailedSummary());
    }

    @Test
    void stillFlagsOneSmallSpot() {
        // 城市小景点 1 个仅 1 小时 → 仍报"内容过少"（防糊弄）
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = new TripPlanData();
        p.setDays(1);
        Section s = new Section();
        s.setType("timeline");
        s.setTitle("第1天");
        SectionItem small = new SectionItem();
        small.setTime("09:00-10:00");
        small.setPlace("某小景点");
        small.setCategory("景点");
        s.getItems().add(small);
        p.getSections().add(s);
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("内容过少")), vr.detailedSummary());
    }

    @Test
    void flagsTooManySpotsPerDay() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        TripPlanData p = oneDayPlan("西湖", "灵隐寺", "断桥残雪", "河坊街", "雷峰塔", "九溪");
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getIssues().stream().anyMatch(i -> i.getReason().contains("赶场")), vr.detailedSummary());
    }

    @Test
    void skipsPaceWhenUserExplicit() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setRaw("杭州1日游，特种兵式打卡");
        TripPlanData p = plan(1, "西湖"); // 1个景点，但用户显式特种兵 → 跳过
        ValidationReport vr = ConstraintEngine.validate(req, p);
        assertTrue(vr.getSkippedChecks().stream().anyMatch(s -> s.contains("每天节奏")), vr.detailedSummary());
        assertTrue(vr.getIssues().stream().noneMatch(i -> i.getReason().contains("内容过少")));
    }

    @Test
    void flagsHotelCityMismatch() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setDestination("杭州");
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "西湖"),
                candidatesJson("08:00-17:30", "苏州", "金鸡湖"));
        assertTrue(vr.getIssues().stream().anyMatch(i -> "hotel".equals(i.getType())
                        && i.getReason().contains("不一致")),
                "酒店候选城市与目的地不符应报 hotel 问题");
    }

    @Test
    void warnsLowWhenHotelAreaNeverOverlapsActivities() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setDestination("杭州");
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "西湖"),
                candidatesJson("08:00-17:30", "杭州", "滨江高新区"));
        assertTrue(vr.getIssues().stream().anyMatch(i -> "hotel".equals(i.getType())
                        && i.getSeverity().equals("low")),
                "酒店区域与活动地点零重合应给 low 提示");
    }

    @Test
    void hotelLocationPassesWhenAreaOverlaps() {
        TripRequest req = new TripRequest();
        req.setDays(1);
        req.setDestination("杭州");
        ValidationReport vr = ConstraintEngine.validate(req, plan(1, "西湖"),
                candidatesJson("08:00-17:30", "杭州", "西湖区"));
        assertFalse(vr.getIssues().stream().anyMatch(i -> "hotel".equals(i.getType())));
    }
}
