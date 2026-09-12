package utils;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;
import dto.ValidationReport.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付前约束校验器单测（纯函数，秒级）。
 *
 * <p>覆盖：A1 时间预算 / A2 游玩下限 / A3 赶路上限 / A4 赶路须有景点 /
 * A5 天数与覆盖 / A6 地点来源 / A7 时间线 / A8 夜间赶路 / A10 区块重复，
 * 以及两条口径：单城市不做节奏约束、判不出来必须报 UNKNOWN 而不是通过。
 */
class PlanValidatorTest {

    // ==================== 构造工具 ====================

    private static SectionItem item(String time, String category, String place, String duration, String activity) {
        SectionItem it = new SectionItem();
        it.setTime(time);
        it.setCategory(category);
        it.setPlace(place);
        it.setDuration(duration);
        it.setActivity(activity);
        return it;
    }

    private static Section day(int d, SectionItem... items) {
        Section s = new Section();
        s.setType("timeline");
        s.setTitle("第" + d + "天");
        s.setItems(new ArrayList<>(Arrays.asList(items)));
        return s;
    }

    private static TripPlanData plan(int days, Section... sections) {
        TripPlanData p = new TripPlanData();
        p.setDays(days);
        p.setDestination("目的地");
        p.setSections(new ArrayList<>(Arrays.asList(sections)));
        return p;
    }

    private static TripRequest req(String origin, String dest, int days) {
        TripRequest r = new TripRequest();
        r.setOrigin(origin);
        r.setDestination(dest);
        r.setDays(days);
        return r;
    }

    private static boolean has(ValidationReport r, String rule, Severity sev) {
        for (ValidationReport.Violation v : r.getViolations()) {
            if (v.rule.equals(rule) && v.severity == sev) return true;
        }
        return false;
    }

    // ==================== A1 每天游玩+车程 ≤ 8h ====================

    @Test
    void a1_游玩加车程超8小时应拦截() {
        // 深圳→川西 3 天（跨城）：第1天 游玩5h + 车程4.5h = 9.5h
        TripPlanData p = plan(1,
                day(1,
                        item("09:00", "景点", "某个景区", "5小时", "游览景区"),
                        item("15:00", "交通", "", "4.5小时", "自驾前往下一站")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);

        assertTrue(has(r, "A1", Severity.BLOCK), "应命中 A1");
        assertFalse(r.passed(), "有硬违反时不应通过");
        assertTrue(r.toFixInstruction().contains("第1天"), "打回清单应带具体天数与证据");
    }

    // ==================== A2 每天纯游玩 ≥ 4h ====================

    @Test
    void a2_纯游玩不足4小时应拦截() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "某景区", "3小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A2", Severity.BLOCK));
    }

    // ==================== A3 单日赶路 ≤ 6h ====================

    @Test
    void a3_单日赶路超6小时应拦截() {
        TripPlanData p = plan(1,
                day(1,
                        item("07:00", "交通", "", "7小时", "全天赶路"),
                        item("15:00", "景点", "某景区", "4小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A3", Severity.BLOCK));
        assertTrue(has(r, "A1", Severity.BLOCK), "7h车程+4h游玩同时也违反 A1");
    }

    // ==================== A4 赶路 > 4h 必须有景点 ====================

    @Test
    void a4_长途赶路却无景点应拦截() {
        TripPlanData p = plan(1, day(1, item("07:00", "交通", "", "5小时", "自驾赶路")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A4", Severity.BLOCK), "赶路5h无沿途景点应命中 A4");
    }

    // ==================== A16 营业时间 ====================

    @Test
    void a16_行程时间超出开放时段应报出() {
        SectionItem a = item("18:30", "景点", "成都市·某博物馆", "1小时", "参观");
        a.setNote("开放 09:00-17:00");
        TripPlanData p = plan(1, day(1, a));
        ValidationReport r = PlanValidator.validate(p, req("成都", "成都", 1), null);
        assertTrue(has(r, "A16", Severity.WARN), "18:30 参观 17:00 闭馆的馆应命中 A16");
    }

    @Test
    void a16_行程时间在开放时段内不应报出() {
        SectionItem a = item("10:00", "景点", "成都市·某博物馆", "1小时", "参观");
        a.setNote("开放 09:00-17:00");
        TripPlanData p = plan(1, day(1, a));
        ValidationReport r = PlanValidator.validate(p, req("成都", "成都", 1), null);
        assertFalse(has(r, "A16", Severity.WARN), "10:00 在开放时段内不应命中 A16");
    }

    @Test
    void a16_查不到开放时间视为全天开放不报() {
        TripPlanData p = plan(1, day(1, item("10:00", "景点", "成都市·宽窄巷子", "2小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("成都", "成都", 1), null);
        assertFalse(has(r, "A16", Severity.UNKNOWN),
                "用户口径：开放时间只用于排程，查不到就按全天开放处理，不报无法判定");
        assertFalse(has(r, "A16", Severity.WARN), "没有开放时间信息就不判时段");
    }

    // ==================== A14 必去 / A15 预算（用户硬性要求） ====================

    @Test
    void a14_必去对象没安排应拦截() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "成都市·人民公园", "2小时", "游览")));
        TripRequest q = req("成都", "成都", 1);
        q.setMustVisit(java.util.Arrays.asList("宽窄巷子"));
        ValidationReport r = PlanValidator.validate(p, q, null);
        assertTrue(has(r, "A14", Severity.BLOCK), "必去未安排应命中 A14");
    }

    @Test
    void a14_必去对象已安排则通过() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "成都市·宽窄巷子", "2小时", "游宽巷子")));
        TripRequest q = req("成都", "成都", 1);
        q.setMustVisit(java.util.Arrays.asList("宽窄巷子"));
        ValidationReport r = PlanValidator.validate(p, q, null);
        assertFalse(has(r, "A14", Severity.BLOCK), "必去已安排不应命中 A14");
    }

    @Test
    void a15_超预算应报出() {
        SectionItem a = item("09:00", "景点", "成都市·宽窄巷子", "2小时", "游览");
        a.setCost("3000元");
        SectionItem b = item("14:00", "景点", "成都市·人民公园", "2小时", "游览");
        b.setCost("3000元");
        TripPlanData p = plan(1, day(1, a, b));
        TripRequest q = req("成都", "成都", 1);
        q.setBudgetTotal(5000);
        ValidationReport r = PlanValidator.validate(p, q, null);
        assertTrue(has(r, "A15", Severity.WARN), "合计 6000 超预算 5000 应命中 A15");
    }

    @Test
    void a15_未超预算不应报出() {
        SectionItem a = item("09:00", "景点", "成都市·宽窄巷子", "2小时", "游览");
        a.setCost("3000元");
        TripPlanData p = plan(1, day(1, a));
        TripRequest q = req("成都", "成都", 1);
        q.setBudgetTotal(5000);
        ValidationReport r = PlanValidator.validate(p, q, null);
        assertFalse(has(r, "A15", Severity.WARN), "3000 在 5000 预算内不应命中 A15");
    }

    // ==================== A12 同景区拆行 / A13 回避对象 ====================

    @Test
    void a12_同一天拆分同一景区应被报出() {
        // 口径取自 v6：`·` 前段 >4 字视为景区主体 → 同一主体的多行折叠判重
        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "四姑娘山景区双桥沟·红杉林", "1.5小时", "游红杉林"),
                item("11:00", "景点", "四姑娘山景区双桥沟·盆景滩", "1小时", "游盆景滩")));
        ValidationReport r = PlanValidator.validate(p, req("成都", "四姑娘山", 1), null);
        assertTrue(has(r, "A12", Severity.WARN), "同一景区拆成两行应命中 A12");
    }

    @Test
    void a12_前段不足5字按城市前缀处理不误判() {
        // v6 踩坑经验：前段 ≤4 字多为城市/古镇前缀，整串比，否则同城全判重复
        TripPlanData p = plan(1, day(1,
                item("14:00", "景点", "成都市·宽窄巷子", "1.5小时", "游宽巷子"),
                item("15:30", "景点", "宽窄巷子·井巷子", "1小时", "游窄巷子")));
        ValidationReport r = PlanValidator.validate(p, req("成都", "成都", 1), null);
        assertFalse(has(r, "A12", Severity.WARN), "前段≤4字按 v6 口径不折叠（避免误判城市前缀）");
    }

    @Test
    void a12_城市前缀不同地点不得误判重复() {
        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "丹巴·中路乡", "2小时", "游览"),
                item("14:00", "景点", "丹巴·甲居藏寨", "2小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("成都", "丹巴", 1), null);
        assertFalse(has(r, "A12", Severity.WARN), "同为丹巴前缀的不同地点不应判重复");
    }

    @Test
    void a13_出现回避对象应拦截() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "某山·登山步道", "2小时", "爬山")));
        TripRequest q = req("成都", "成都", 1);
        TripRequest.Preferences pref = new TripRequest.Preferences();
        pref.setAvoid(java.util.Arrays.asList("爬山"));
        q.setPreferences(pref);
        ValidationReport r = PlanValidator.validate(p, q, null);
        assertTrue(has(r, "A13", Severity.BLOCK), "出现回避项应命中 A13");
    }

    // ==================== 沿途路景不得顶替真景点（用户口径 2026-09-12） ====================

    @Test
    void 路景写成景点条目也不能顶替真景点() {
        // 第1天：赶路 5h + 一条 category=景点 但实为路景的条目（粤北丘陵）
        TripPlanData p = plan(1, day(1,
                item("07:00", "交通", "", "5小时", "自驾赶路"),
                item("12:00", "景点", "粤北丘陵", "1小时", "沿途路景（车览、不停车）")));
        // A6 只在白名单非空时才执行，故这里给一个非空白名单
        java.util.Set<String> wl = new java.util.HashSet<>(java.util.Arrays.asList("成都市·宽窄巷子"));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), wl);
        assertTrue(has(r, "A4", Severity.BLOCK), "路景不得顶替真景点，赶路5h仍应命中 A4");
        assertTrue(has(r, "A6", Severity.WARN), "路景被写成景点条目应被 A6 报出");
    }

    @Test
    void 真景点仍正常计入() {
        TripPlanData p = plan(1, day(1,
                item("07:00", "交通", "", "5小时", "自驾赶路"),
                item("12:00", "景点", "成都市·宽窄巷子", "2小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertFalse(has(r, "A4", Severity.BLOCK), "有真景点不应命中 A4");
    }

    // ==================== A11 走向合理性（为沿途景点绕路须在合理范围内） ====================

    @Test
    void a11_为沿途景点绕路过多应命中() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "成都市·宽窄巷子", "3小时", "游览")));
        // 实际 2400km vs 直达 1500km = 绕路 60%
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null,
                "★总里程（全程合计）：约 2400 公里；不绕路直达：约 1500 公里");
        assertTrue(has(r, "A11", Severity.WARN), "绕路 60% 应命中 A11");
    }

    @Test
    void a11_绕路在合理范围内不应命中() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "成都市·宽窄巷子", "3小时", "游览")));
        // 实际 1650km vs 直达 1500km = 绕路 10%，在 20% 上限内
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null,
                "★总里程（全程合计）：约 1650 公里；不绕路直达：约 1500 公里");
        assertFalse(has(r, "A11", Severity.WARN), "绕路 10% 不应命中 A11");
    }

    @Test
    void a11_缺基准里程应报无法判定而非放行() {
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "成都市·宽窄巷子", "3小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null,
                "★总里程（全程合计）：约 2400 公里");   // 没有"不绕路直达"基准
        assertTrue(has(r, "A11", Severity.UNKNOWN), "缺基准必须报 UNKNOWN，绝不当通过");
    }

    // ==================== A5 天数与覆盖 ====================

    @Test
    void a5_天数不符或缺天应拦截() {
        // 用户要 3 天，实际只给了第 1 天
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "某景区", "5小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 3), null);
        assertTrue(has(r, "A5", Severity.BLOCK), "天数不符应命中 A5");
        assertTrue(r.toFixInstruction().contains("2"), "应指出缺第2、3天");
    }

    // ==================== A6 地点来源（防编造）====================

    @Test
    void a6_不在真实数据里的地点应告警() {
        Set<String> real = new HashSet<>(Arrays.asList("稻城亚丁", "四姑娘山"));
        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "稻城亚丁", "4小时", "游览"),
                item("14:00", "景点", "传说中的秘境", "2小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), real);
        assertTrue(has(r, "A6", Severity.WARN), "编造地点应告警");
        assertFalse(has(r, "A6", Severity.BLOCK), "A6 是告警，不阻断");
    }

    // ==================== A7 时间线递增 ====================

    @Test
    void a7_时间倒序应告警() {
        TripPlanData p = plan(1, day(1,
                item("14:00", "景点", "某景区", "2小时", "游览"),
                item("09:00", "景点", "另一景区", "2小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A7", Severity.WARN));
    }

    // ==================== A8 夜间不赶路 ====================

    @Test
    void a8_凌晨赶路应拦截() {
        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "某景区", "5小时", "游览"),
                item("23:30", "交通", "", "1小时", "连夜自驾")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A8", Severity.BLOCK));
    }

    // ==================== A10 区块重复 ====================

    @Test
    void a10_重复区块应告警() {
        TripPlanData p = plan(1,
                day(1, item("09:00", "景点", "某景区", "5小时", "游览")),
                day(1, item("09:00", "景点", "某景区", "5小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A10", Severity.WARN));
    }

    // ==================== 口径：单城市不做节奏约束 ====================

    @Test
    void 单城市不做节奏类约束() {
        // 单城市（出发地=目的地）：游玩5h+车程4.5h=9.5h，也**不应**被 A1/A2/A3 拦
        TripPlanData p = plan(1,
                day(1,
                        item("09:00", "景点", "宽窄巷子", "5小时", "游览"),
                        item("15:00", "交通", "", "4.5小时", "市区内移动")));
        ValidationReport r = PlanValidator.validate(p, req("成都", "成都", 1), null);

        assertFalse(has(r, "A1", Severity.BLOCK), "单城市不应做 A1");
        assertFalse(has(r, "A2", Severity.BLOCK), "单城市不应做 A2");
        assertFalse(has(r, "A3", Severity.BLOCK), "单城市不应做 A3");
        assertTrue(r.passed(), "单城市此例应通过");
    }

    // ==================== 口径：判不出来必须报 UNKNOWN ====================

    @Test
    void 时长缺失应报无法判定而不是通过() {
        TripPlanData p = plan(1, day(1,
                item("上午", "景点", "某景区", "", "游览"),
                item("下午", "景点", "另一景区", "", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);

        assertTrue(has(r, "A1", Severity.UNKNOWN), "数据不足必须报 UNKNOWN");
        assertEquals(1, r.getUnknownDays());
        assertEquals(0, r.getCheckedDays(), "无可用时长 → 不计入已判定天数");
    }

    // ==================== 正常行程应通过 ====================

    @Test
    void 合规行程应通过() {
        TripPlanData p = plan(1,
                day(1,
                        item("09:00", "景点", "稻城亚丁", "4小时", "游览"),
                        item("13:00", "交通", "", "1小时", "自驾前往"),
                        item("14:00", "景点", "四姑娘山", "3小时", "游览")));
        Set<String> real = new HashSet<>(Arrays.asList("稻城亚丁", "四姑娘山"));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), real);

        assertTrue(r.passed(), "合规行程应通过；实际违反：" + r.getViolations());
        assertEquals(1, r.getCheckedDays());
    }

    // ==================== 时长解析 ====================

    @Test
    void 时长文本解析() {
        assertEquals(Integer.valueOf(120), PlanValidator.parseDuration("2小时"));
        assertEquals(Integer.valueOf(90), PlanValidator.parseDuration("约1.5小时"));
        assertEquals(Integer.valueOf(90), PlanValidator.parseDuration("90分钟"));
        assertEquals(Integer.valueOf(240), PlanValidator.parseDuration("半天"));
        assertEquals(Integer.valueOf(150), PlanValidator.timeRangeMinutes("09:00-11:30"));
        assertEquals(Integer.valueOf(120), PlanValidator.timeRangeMinutes("23:30-01:30"));
        assertEquals(null, PlanValidator.parseDuration("视情况而定"));
    }

    @Test
    void 天号识别兼容多种写法() {
        assertEquals(3, PlanValidator.dayOf("第3天"));
        assertEquals(3, PlanValidator.dayOf("第 3 天 成都"));
        assertEquals(2, PlanValidator.dayOf("Day 2"));
        assertEquals(0, PlanValidator.dayOf("住宿与餐饮"));
    }

    // ==================== 口径：用餐/休整不计入游玩+车程 ====================

    @Test
    void 餐饮住宿不计入游玩车程() {
        // 景点4h + 车程4h + 餐饮1h + 住宿0.5h → 游玩+车程正好 8.0h，应当通过（用餐另计）
        TripPlanData p = plan(1, day(1,
                item("07:00-11:00", "景点", "成都市·宽窄巷子", "4小时", "游览"),
                item("12:00-13:00", "餐饮", "某餐厅", "1小时", "午餐"),
                item("14:00-18:00", "自驾", "", "4小时", "自驾前往下一站"),
                item("19:00-19:30", "住宿", "某酒店", "0.5小时", "办理入住")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertFalse(has(r, "A1", Severity.BLOCK), "餐饮/住宿不计入，8.0h 不应被判超时；实际：" + r.getViolations());
        assertTrue(r.passed(), "应通过；实际：" + r.getViolations());
    }

    @Test
    void 自驾类别也要识别为车程() {
        // 实测：LLM 会把交通条目 category 填成「自驾」
        TripPlanData p = plan(1, day(1,
                item("07:00-14:00", "自驾", "", "7小时", "高速自驾赶路"),
                item("15:00-19:00", "景点", "某景区", "4小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A3", Severity.BLOCK), "「自驾」应被识别为车程并命中 A3；实际：" + r.getViolations());
    }

    // ==================== 可行性复核：行程自身车程合计 ====================

    @Test
    void 括号补充说明不影响真实地点判定() {
        // 实测误报：产出写「漓江（兴坪段）」，白名单里是「阳朔漓江」
        java.util.Set<String> wl = new java.util.HashSet<>(java.util.Arrays.asList("广西·桂林市·阳朔漓江"));
        assertTrue(PlanValidator.placeIsReal("漓江（兴坪段）", wl), "括号补充说明不应影响地点身份判定");
        assertTrue(PlanValidator.placeIsReal("阳朔漓江", wl));
    }

    @Test
    void 行程车程合计可复核可行性() {
        // 路线Agent 不给总里程时的兜底依据：用行程各天车程合计判断"排不排得开"
        TripPlanData p = plan(3,
                day(1, item("08:00-15:00", "自驾", "", "7小时", "赶路")),
                day(2, item("08:00-15:00", "自驾", "", "7小时", "赶路")),
                day(3, item("08:00-15:00", "自驾", "", "7小时", "赶路")));
        assertEquals(21 * 60, PlanValidator.totalDriveMinutes(p), "3 天每天 7h → 合计 21h");
    }

    @Test
    void 行程车程合计取不到时为零不当判据() {
        TripPlanData p = plan(1, day(1, item("", "景点", "成都市·宽窄巷子", "", "游览")));
        assertEquals(0, PlanValidator.totalDriveMinutes(p), "无车程数据应为 0（上层据此不下结论）");
    }

    // ==================== 口径：游玩下限随车程收敛 / 车程数据源三级优先 ====================

    @Test
    void 车程5h时玩3h应通过_下限收敛() {
        // 下限 = min(4h, 8h − 车程5h) = 3h → 玩3h 合规（旧实现一刀切4h会误报）
        TripPlanData p = plan(1, day(1,
                item("09:00-12:00", "景点", "成都市·宽窄巷子", "3小时", "游览"),
                item("13:00-18:00", "自驾", "", "5小时", "自驾前往")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(r.passed(), "车程5h→下限3h，玩3h应通过；实际：" + r.getViolations());
    }

    @Test
    void 车程5h时玩2h应违规() {
        TripPlanData p = plan(1, day(1,
                item("09:00-11:00", "景点", "成都市·宽窄巷子", "2小时", "游览"),
                item("12:00-17:00", "自驾", "", "5小时", "自驾前往")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A2", Severity.BLOCK), "车程5h→下限3h，玩2h应违规；实际：" + r.getViolations());
    }

    @Test
    void Agent给的车程小时优先于条目累加() {
        // 条目时长只写 1h，但显式声明「当日车程合计：约5小时」→ 必须按 5h 判定
        TripPlanData p = plan(1, day(1,
                item("09:00-13:00", "景点", "成都市·宽窄巷子", "4小时", "游览"),
                item("14:00-15:00", "自驾", "", "1小时", "自驾前往（当日车程合计：约5小时）")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A1", Severity.BLOCK), "应按 Agent 声明的 5h 车程判定（4+5=9h>8h）；实际：" + r.getViolations());
    }

    @Test
    void 无车程小时时按里程估算并标注() {
        // 无时长无车程声明，但有里程 560km → 560/70 = 8h → A3 违规，且文案标注"按里程估算"
        TripPlanData p = plan(1, day(1, item("", "自驾", "", "", "自驾前往，全程560公里")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A3", Severity.BLOCK), "应按里程估算出 8h 车程并命中 A3；实际：" + r.getViolations());
    }

    @Test
    void 既无车程也无里程必须报无法判定() {
        TripPlanData p = plan(1, day(1, item("", "自驾", "", "", "自驾前往")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A1", Severity.UNKNOWN), "无任何车程/里程数据 → 必须报无法判定，不得假通过；实际：" + r.getViolations());
    }

    // ==================== 时长推算（不依赖 LLM 填 duration 字段）====================

    @Test
    void 无duration时按相邻时间差推算时长() {
        // 实测依据：gitee 聚合产出 duration 恒为空串；此例仅有 time 字段
        TripPlanData p = plan(1, day(1,
                item("06:00", "交通", "", "", "自驾赶路"),
                item("12:30", "餐饮", "服务区", "", "午餐"),
                item("13:30", "景点", "某景区", "", "游览"),
                item("17:30", "景点", "另一景区", "", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);

        assertFalse(has(r, "A1", Severity.UNKNOWN), "有时段可推算时不应报无法判定");
        assertEquals(1, r.getCheckedDays(), "应能真正判定这一天");
        // 推算：交通 6.5h → 单日赶路超 6h
        assertTrue(has(r, "A3", Severity.BLOCK), "应推算判定 A3；实际：" + r.getViolations());
    }

    @Test
    void 既无duration又无时间才报无法判定() {
        TripPlanData p = plan(1, day(1,
                item("上午", "景点", "某景区", "", "游览"),
                item("下午", "景点", "另一景区", "", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), null);
        assertTrue(has(r, "A1", Severity.UNKNOWN));
    }

    // ==================== A6 方案A：子Agent 回传的真实地点清单 ====================

    private static final String AGENT_REPLY =
            "第1天 成都市·宽窄巷子 游览2小时；成都市·武侯祠 游览2小时。\n"
            + "第2天 甘孜州·稻城亚丁 游览一整天。\n"
            + "【真实地点清单】\n"
            + "- 成都市·宽窄巷子\n"
            + "- 成都市·武侯祠\n"
            + "- 甘孜州·稻城亚丁\n"
            + "- 甘孜州·理塘县毛垭草原\n";

    @Test
    void 解析真实地点清单() {
        Set<String> places = PlanValidator.parsePlacesBlock(AGENT_REPLY);
        assertEquals(4, places.size(), "实际：" + places);
        assertTrue(places.contains("成都市·宽窄巷子"));
        assertTrue(places.contains("甘孜州·理塘县毛垭草原"));
    }

    @Test
    void 剥离清单块避免污染正文() {
        String stripped = PlanValidator.stripPlacesBlock(AGENT_REPLY);
        assertFalse(stripped.contains(PlanValidator.PLACES_BLOCK), "清单块应被剥离");
        assertTrue(stripped.contains("稻城亚丁"), "正文应保留");
    }

    @Test
    void 没有清单块时返回空集且不下结论() {
        assertTrue(PlanValidator.parsePlacesBlock("第1天 某景区 游览5小时").isEmpty());
        // 空白名单 → A6 不判定（不误报）
        TripPlanData p = plan(1, day(1, item("09:00", "景点", "随便编的地方", "5小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), java.util.Collections.emptySet());
        assertFalse(has(r, "A6", Severity.WARN), "无白名单时不应报编造");
    }

    @Test
    void 行政地名不算编造景点() {
        // 实测误报：真实城市被当成编造 → 行政区名（市/州/县/区/镇…）A6 不判定
        TripPlanData p = plan(1, day(1,
                item("09:00", "交通", "广西梧州", "", "途经梧州"),
                item("11:00", "交通", "贵州三都", "", "途经三都")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), java.util.Collections.emptySet());
        assertFalse(has(r, "A6", Severity.WARN), "行政地名不应被标为编造；实际：" + r.getViolations());
        assertTrue(PlanValidator.isAdminName("广西梧州"));
        assertTrue(PlanValidator.isAdminName("四川泸州"));
        assertFalse(PlanValidator.isAdminName("康定"));
    }

    @Test
    void 路线里的真实地名并入白名单后不再误报() {
        // 实测误报「康定」：路线文本写的是「康定市」→ 抽取入白名单后，正文写「康定」应命中（双向包含）
        java.util.Set<String> whitelist = PlanValidator.placesFromText("第3天 成都出发前往康定市，经泸定县到稻城亚丁景区");
        assertTrue(whitelist.contains("康定市"), "应抽到康定市；实际：" + whitelist);

        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "康定", "5小时", "游览康定")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1), whitelist);
        assertFalse(has(r, "A6", Severity.WARN), "康定应命中白名单，不该报编造；实际：" + r.getViolations());
    }

    @Test
    void 路段描述仍会被标出() {
        // 这类"不是景点却当景点写"的表述必须继续被抓到
        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "桂粤交界丘陵、西江上游河谷段", "5小时", "沿途观光")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "川西", 1),
                new HashSet<>(Arrays.asList("成都市·宽窄巷子")));
        assertTrue(has(r, "A6", Severity.WARN), "路段描述应被标为疑似编造");
    }

    @Test
    void 交通识别扩充关键词() {
        SectionItem it = item("10:00-12:00", "", "", "", "自驾车程约2小时（第1段，约200km；广昆高速方向）");
        assertTrue(PlanValidator.isTravel(it), "含'自驾车程/高速'应识别为交通");
        SectionItem it2 = item("07:00", "", "某地", "", "驶入G4京港澳高速");
        assertTrue(PlanValidator.isTravel(it2), "含'驶入/高速'应识别为交通");
    }

    @Test
    void 清单用于判定编造地点() {
        Set<String> real = PlanValidator.parsePlacesBlock(AGENT_REPLY);
        TripPlanData p = plan(1, day(1,
                item("09:00", "景点", "成都市·宽窄巷子", "4小时", "游览"),
                item("13:00", "景点", "神秘未知古镇", "1小时", "游览")));
        ValidationReport r = PlanValidator.validate(p, req("深圳", "成都", 1), real);
        assertTrue(has(r, "A6", Severity.WARN), "清单外的地点应被标为疑似编造");
    }
}
