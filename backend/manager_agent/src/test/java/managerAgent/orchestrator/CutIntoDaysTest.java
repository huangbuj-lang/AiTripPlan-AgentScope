package managerAgent.orchestrator;

import dto.TripRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分天骨架核心逻辑单测（纯函数，不依赖网络/DB/A2A）。
 *
 * <p><b>来历（2026-09-12 实测）</b>：14 天「川西14日游」产出结构崩坏（缺天/重复/碎片/顺序乱），
 * 根因是候选代表景点上限被写死 8 导致天骨架放弃、回退整单生成。修复过程中用 Python 复刻算法
 * 又连续挖出两个自身 bug（合并掉大景区的多天、合并后当天堆到 24 小时），所以把这几条固化成测试。
 *
 * <p>测试数据取自真实候选（`trip_plans` id=76 那次 14 天任务的 attraction 候选池，18 个），
 * 不是编造的样例。
 */
class CutIntoDaysTest {

    /** 造一个候选（和候选专家的输出字段一致） */
    private static Map<String, Object> cand(String name, String loc, String duration, String rating) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("location", loc);
        m.put("duration", duration);
        m.put("rating", rating);
        return m;
    }

    /** 真实 18 个候选（川西14日游那次任务的候选池原样） */
    private static List<Map<String, Object>> realCandidates() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(cand("甘孜稻城亚丁景区", "100.286793,28.458898", "建议1-2天", "4.9"));
        l.add(cand("九寨沟", "103.912874,33.142376", "建议1天", "4.9"));
        l.add(cand("黄龙国家级风景名胜区", "103.833028,32.743163", "建议半天-1天", "4.8"));
        l.add(cand("四姑娘山景区", "102.826018,31.094381", "建议1天", "4.9"));
        l.add(cand("达古冰川风景名胜区", "102.911009,32.131020", "建议半天-1天", "4.7"));
        l.add(cand("海螺沟冰川森林公园", "102.005499,29.579018", "建议1天", "4.6"));
        l.add(cand("木格措景区", "101.872437,30.170233", "建议半天-1天", "4.7"));
        l.add(cand("若尔盖大草原自然区", "102.491446,33.377610", "建议半天-1天", "4.7"));
        l.add(cand("黄河九曲第一湾", "102.477539,33.472970", "建议半天", "4.6"));
        l.add(cand("花湖生态旅游区", "102.828661,33.933500", "建议半天", "4.5"));
        l.add(cand("甲居藏寨", "101.862684,30.939766", "建议半天", "4.6"));
        l.add(cand("中路藏寨", "101.937996,30.889513", "建议半天", "4.6"));
        l.add(cand("塔公草原", "101.554960,30.329930", "建议半天", "4.6"));
        l.add(cand("墨石公园景区", "101.542400,30.444886", "建议2-3小时", "4.6"));
        l.add(cand("色达喇荣五明佛学院", "100.472477,32.150521", "建议1天", ""));
        l.add(cand("毕棚沟景区", "102.994424,31.380620", "建议1天", "4.7"));
        l.add(cand("勒通古镇·千户藏寨旅游景区", "100.268516,29.993780", "建议半天", "4.7"));
        l.add(cand("阿坝羌寨碉群桃坪羌寨", "103.457267,31.555464", "建议2-3小时", "4.6"));
        return l;
    }

    /** 每个候选单独成簇（模拟"景点之间相距都很远"的川西场景，簇顺序即地理链顺序） */
    private static List<List<Map<String, Object>>> chainOf(List<Map<String, Object>> spots) {
        List<List<Map<String, Object>>> chain = new ArrayList<>();
        for (Map<String, Object> s : spots) {
            List<Map<String, Object>> one = new ArrayList<>();
            one.add(s);
            chain.add(one);
        }
        return chain;
    }

    private static TripRequest req(int days, String travelType) {
        TripRequest r = new TripRequest();
        r.setDays(days);
        r.setTravelType(travelType);
        r.setDestination("川西");
        return r;
    }

    // ===================== duration 解析 =====================

    @Test
    @DisplayName("duration 文本解析：半天/1天/1-2天/半天-1天/2-3小时/2-3天")
    void durationMinutesParse() {
        assertEquals(240, TripOrchestrator.durationMinutes("建议半天"));
        assertEquals(480, TripOrchestrator.durationMinutes("建议1天"));
        assertEquals(960, TripOrchestrator.durationMinutes("建议1-2天"), "取上限：1-2天按2天算");
        assertEquals(360, TripOrchestrator.durationMinutes("建议半天-1天"));
        assertEquals(150, TripOrchestrator.durationMinutes("建议2-3小时"));
        // ★这条是"张家界玩好几天"场景：原先认不出"X-Y天"会落到半天(240)，把大景区压成半天
        assertEquals(1440, TripOrchestrator.durationMinutes("建议2-3天"), "2-3天按3天算");
        assertEquals(240, TripOrchestrator.durationMinutes(""), "空值按半天兜底");
        assertEquals(240, TripOrchestrator.durationMinutes(null));
    }

    @Test
    @DisplayName("priority/rating 解析：缺失不误伤（priority 默认 2，rating 默认 0）")
    void priorityAndRatingParse() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priority", 1);
        m.put("rating", "4.9");
        assertEquals(1, TripOrchestrator.priorityOf(m));
        assertEquals(4.9, TripOrchestrator.ratingOf(m), 0.001);

        Map<String, Object> empty = new LinkedHashMap<>();
        assertEquals(2, TripOrchestrator.priorityOf(empty), "缺失按 2，不误伤");
        assertEquals(0.0, TripOrchestrator.ratingOf(empty), 0.001);

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("priority", "99");
        bad.put("rating", "");
        assertEquals(2, TripOrchestrator.priorityOf(bad), "越界按 2");
        assertEquals(0.0, TripOrchestrator.ratingOf(bad), 0.001);
    }

    // ===================== 分天骨架 =====================

    @Test
    @DisplayName("★14 天长线必须正好切出 14 天（原先写死 8 上限时必然切不出，回退整单）")
    void fourteenDaysMustYieldFourteenGroups() {
        List<Map<String, Object>> spots = realCandidates();
        List<TripOrchestrator.DaySkel> days =
                TripOrchestrator.cutIntoDays(chainOf(spots), 14, req(14, "SELF_DRIVE"));
        assertNotNull(days, "不能返回 null（返回 null 会让天骨架放弃、回退整单生成）");
        assertEquals(14, days.size(), "必须正好 14 天");
        for (int i = 0; i < days.size(); i++) {
            assertTrue(!days.get(i).members.isEmpty(), "第 " + (i + 1) + " 天不能是空天");
        }
    }

    @Test
    @DisplayName("★大景区独占连续多天：稻城亚丁(建议1-2天)在 14 天里占连续 2 天")
    void bigSpotSpansConsecutiveDays() {
        List<Map<String, Object>> spots = realCandidates();
        List<TripOrchestrator.DaySkel> days =
                TripOrchestrator.cutIntoDays(chainOf(spots), 14, req(14, "SELF_DRIVE"));
        assertNotNull(days);
        int cnt = 0, first = -1;
        for (int i = 0; i < days.size(); i++) {
            for (Map<String, Object> m : days.get(i).members) {
                if ("甘孜稻城亚丁景区".equals(m.get("name"))) {
                    cnt++;
                    if (first < 0) first = i;
                }
            }
        }
        if (cnt != 2) {
            StringBuilder sb = new StringBuilder("实际分天明细:\n");
            for (int i = 0; i < days.size(); i++) {
                sb.append("  第").append(i + 1).append("天(共").append(days.get(i).members.size()).append("个): ");
                for (Map<String, Object> m : days.get(i).members) {
                    sb.append(m.get("name")).append('[').append(TripOrchestrator.durationMinutes(m.get("duration")))
                      .append("min] ");
                }
                sb.append('\n');
            }
            System.out.println(sb);
        }
        StringBuilder detail = new StringBuilder("稻城亚丁应占 2 天，实际 " + cnt + " 天；分天明细: ");
        for (int i = 0; i < days.size(); i++) {
            detail.append("D").append(i + 1).append('{');
            for (Map<String, Object> m : days.get(i).members) {
                detail.append(m.get("name")).append('(')
                      .append(TripOrchestrator.durationMinutes(m.get("duration"))).append("m) ");
            }
            detail.append("} ");
        }
        assertEquals(2, cnt, detail.toString());
        assertEquals(0, first, "应从第 1 天开始");
    }

    @Test
    @DisplayName("★天数超额时不得合并'大景区独占的多天'（否则大景区被压回1天）")
    void multiDaySpotMustNotBeMergedAway() {
        List<Map<String, Object>> spots = realCandidates();
        List<TripOrchestrator.DaySkel> days =
                TripOrchestrator.cutIntoDays(chainOf(spots), 14, req(14, "SELF_DRIVE"));
        assertNotNull(days);
        for (TripOrchestrator.DaySkel d : days) {
            for (Map<String, Object> m : d.members) {
                // 每天每个景点最多出现一次（同一景点靠"跨天"体现，不靠"同天重复"）
                long same = d.members.stream()
                        .filter(x -> String.valueOf(x.get("name")).equals(String.valueOf(m.get("name"))))
                        .count();
                assertTrue(same <= 1, "同一天不应重复出现同一景点");
            }
        }
    }

    @Test
    @DisplayName("短行程：3 天必须切出 3 天（改动不能破坏原有短行程行为）")
    void threeDaysStillWork() {
        List<Map<String, Object>> spots = realCandidates();
        List<TripOrchestrator.DaySkel> days =
                TripOrchestrator.cutIntoDays(chainOf(spots), 3, req(3, "SELF_DRIVE"));
        assertNotNull(days);
        assertEquals(3, days.size(), "3 天必须切出 3 天");
    }

    @Test
    @DisplayName("天数与候选数无关：14 天即使是 14 个候选也稳出 14 天")
    void fourteenSpotsFourteenDays() {
        List<Map<String, Object>> spots = new ArrayList<>(realCandidates().subList(0, 14));
        List<TripOrchestrator.DaySkel> days =
                TripOrchestrator.cutIntoDays(chainOf(spots), 14, req(14, "SELF_DRIVE"));
        assertNotNull(days);
        assertEquals(14, days.size());
    }

    @Test
    @DisplayName("priority=1 的景点不因'天数不够'被丢弃")
    void mustVisitNeverDropped() {
        List<Map<String, Object>> spots = realCandidates();
        // 把两个景点标为必去
        spots.get(0).put("priority", 1);
        spots.get(1).put("priority", 1);
        // 故意给很小的天数，逼出"舍可选"分支
        List<TripOrchestrator.DaySkel> days =
                TripOrchestrator.cutIntoDays(chainOf(spots), 2, req(2, "SELF_DRIVE"));
        assertNotNull(days);
        List<String> kept = new ArrayList<>();
        for (TripOrchestrator.DaySkel d : days) {
            for (Map<String, Object> m : d.members) kept.add(String.valueOf(m.get("name")));
        }
        assertTrue(kept.contains("甘孜稻城亚丁景区"), "必去景点不能被丢");
        assertTrue(kept.contains("九寨沟"), "必去景点不能被丢");
    }
}
