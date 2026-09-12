package utils;

import dto.TripRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可行性预检单测：里程解析 + 所需天数换算 + "取不到里程不下结论"口径。
 */
class RouteFeasibilityTest {

    private static TripRequest req(String origin, String dest, int days) {
        TripRequest r = new TripRequest();
        r.setOrigin(origin);
        r.setDestination(dest);
        r.setDays(days);
        return r;
    }

    @Test
    void 显式总里程优先() {
        String text = "第1天 行驶约 120 公里\n总里程 2180 公里\n第2天 行驶约 380 公里";
        assertEquals(Integer.valueOf(2180), RouteFeasibility.totalKm(text));
    }

    @Test
    void 没有总里程时取最大值避免分段重复累加() {
        String text = "第1天 行驶约 120 公里，第2天 行驶约 380 公里，全程 1200 km";
        assertEquals(Integer.valueOf(1200), RouteFeasibility.totalKm(text));
    }

    @Test
    void 支持公里与km两种写法() {
        assertEquals(Integer.valueOf(860), RouteFeasibility.totalKm("往返约 860km"));
        assertEquals(Integer.valueOf(860), RouteFeasibility.totalKm("往返约 860 公里"));
    }

    @Test
    void 不可行时给出可执行替代方案() {
        RouteFeasibility.Result r = RouteFeasibility.evaluate("总里程 4178 公里", req("深圳", "川西", 7));
        assertTrue(r.infeasible, "4178km / 450km ≈ 10 天 > 7 天，应判不可行");
        assertTrue(r.advice.contains("加到"), "应给出加天数的建议；实际：" + r.advice);
        assertTrue(r.advice.contains("租车"), "应给出飞+租车的替代；实际：" + r.advice);
    }

    @Test
    void 取不到里程返回null且不下结论() {
        assertEquals(null, RouteFeasibility.totalKm("路途风景优美，注意休息"));
        // 无法判定 → 不打扰用户（不报"不可行"）
        assertEquals("", RouteFeasibility.check("路途风景优美，注意休息", req("深圳", "川西", 3)));
        RouteFeasibility.Result r = RouteFeasibility.evaluate("路途风景优美，注意休息", req("深圳", "川西", 3));
        assertFalse(r.infeasible, "取不到里程时不得判不可行（不猜数字）");
        assertEquals("", r.note, "取不到里程时说明应为空");
    }

    @Test
    void 行程车程复核超单日上限判不可行() {
        // 3 天 × 上限 6h = 18h < 实际 21h → 排不开
        RouteFeasibility.Result r = RouteFeasibility.fromPlanDrive(21 * 60, 3, 6 * 60);
        assertTrue(r.infeasible, "21h > 18h，应判不可行");
        assertEquals(4, r.need, "21h ÷ 6h = 3.5 → 需 4 天");
        assertTrue(r.advice.contains("加到"), "应给替代方案；实际：" + r.advice);
    }

    @Test
    void 行程车程复核未超上限则可行() {
        RouteFeasibility.Result r = RouteFeasibility.fromPlanDrive(15 * 60, 3, 6 * 60);
        assertFalse(r.infeasible, "15h ≤ 18h，不应判不可行");
        assertEquals("", r.note, "可行时不应有说明");
    }

    @Test
    void 跨城按日均450km换算超出天数则提示() {
        // 2600km / 450 = 5.78 → 需 6 天 > 用户 3 天
        String msg = RouteFeasibility.check("总里程 2600 公里", req("深圳", "川西", 3));
        assertTrue(msg.contains("需约 6 天"), "实际提示：" + msg);
        assertTrue(msg.contains("超过用户要求的 3 天"), "实际提示：" + msg);
    }

    @Test
    void 天数够时不提示() {
        assertEquals("", RouteFeasibility.check("总里程 1000 公里", req("深圳", "广州", 5)));
    }

    @Test
    void 市内按日均300km换算() {
        // 单城市（出发地=目的地）：900km / 300 = 3 天 > 用户 2 天
        String msg = RouteFeasibility.check("总里程 900 公里", req("成都", "成都", 2));
        assertTrue(msg.contains("需约 3 天"), "实际提示：" + msg);
        assertTrue(msg.contains("300km"), "市内日均应为 300km，实际：" + msg);
    }
}
