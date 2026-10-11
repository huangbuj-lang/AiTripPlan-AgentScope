package managerAgent.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ★2026-09-11 解析层抽出后的回归测试：锁定 RoutePlanParser 的既有行为。
 *
 * <p>抽解析层的前提是“行为完全不变”，所以这里逐分支断言——包括各种降级路径
 * （无【路线JSON】块时退回正则、字段缺失时的默认值）。这些分支此前无法单独测，
 * 全都埋在 2400 行的 TripOrchestrator 里。
 */
class RoutePlanParserTest {

    // ==================== parseRouteJson ====================

    /** 标准【路线JSON】块：四个字段全部解析出来 */
    @Test
    void parseRouteJsonParsesAllFields() {
        String reply = """
                已为你规划3条候选路线，往返总里程约 1566 km。
                【路线JSON】
                {"totalKm": 1566, "oneWayKm": 783, "waypoints": "雅安,康定,理塘", "routeCount": 3}
                【路线JSON结束】
                请查收。
                """;
        RoutePlanParser.RouteData rd = RoutePlanParser.parseRouteJson(reply);
        assertNotNull(rd, "应解析出结构化数据");
        assertEquals(1566, rd.totalKm);
        assertEquals(783, rd.oneWayKm);
        assertEquals("雅安,康定,理塘", rd.waypoints);
        assertEquals(3, rd.routeCount);
    }

    /** 无【路线JSON】块 → 退回 JsonUtils 提取裸 JSON；无 JSON → null */
    @Test
    void parseRouteJsonFallsBackToBareJsonThenNull() {
        // 裸 JSON（没有标记块）也能提取
        RoutePlanParser.RouteData rd = RoutePlanParser.parseRouteJson("{\"totalKm\": 800, \"oneWayKm\": 400}");
        assertNotNull(rd, "裸 JSON 应能提取");
        assertEquals(800, rd.totalKm);
        assertEquals(400, rd.oneWayKm);

        // 完全没有 JSON → null（外层据此降级走正则）
        assertNull(RoutePlanParser.parseRouteJson("就只有一段自然语言描述，没有结构化输出"));
        assertNull(RoutePlanParser.parseRouteJson(null));
        assertNull(RoutePlanParser.parseRouteJson("   "));
    }

    /** ★关键业务规则：totalKm <= 0 视为无效（返回 null），避免拿 0 里程当有效数据 */
    @Test
    void parseRouteJsonRejectsNonPositiveTotalKm() {
        assertNull(RoutePlanParser.parseRouteJson("【路线JSON】{\"totalKm\": 0, \"waypoints\": \"雅安\"}【路线JSON结束】"),
                "totalKm=0 应判为无效");
        assertNull(RoutePlanParser.parseRouteJson("【路线JSON】{\"oneWayKm\": 400}【路线JSON结束】"),
                "缺 totalKm 应判为无效");
    }

    /** 路线Agent 多写未知字段时不应整体失败（用 Map 接收的原因） */
    @Test
    void parseRouteJsonToleratesUnknownFields() {
        String reply = "【路线JSON】{\"totalKm\": 1200, \"unknownField\": \"x\", \"route\": [1,2,3]}【路线JSON结束】";
        RoutePlanParser.RouteData rd = RoutePlanParser.parseRouteJson(reply);
        assertNotNull(rd, "多写字段不应导致解析失败（历史 bug）");
        assertEquals(1200, rd.totalKm);
    }

    // ==================== extractTotalDistance ====================

    /** 优先取结构化 totalKm（往返闭环口径），而不是散落的 km 数字 */
    @Test
    void extractTotalDistancePrefersStructuredJson() {
        String reply = """
                单程 783 km，往返约 1566 km。
                【路线JSON】{"totalKm": 1566, "oneWayKm": 783}【路线JSON结束】
                """;
        assertEquals(1566, RoutePlanParser.extractTotalDistance(reply));
    }

    /** 无 JSON 时退回标注行；按 往返总里程 > 总里程 > 去程里程 的优先级 */
    @Test
    void extractTotalDistanceFallsBackToLabelledLines() {
        assertEquals(1900, RoutePlanParser.extractTotalDistance("本路线往返总里程：约 1900 km"));
        assertEquals(950, RoutePlanParser.extractTotalDistance("全程总里程约 950km，路况良好"));
        assertEquals(480, RoutePlanParser.extractTotalDistance("去程里程：约 480 km"));
    }

    /** ★不累加多条候选路线的里程（历史 bug：累加导致总里程虚高数倍） */
    @Test
    void extractTotalDistanceTakesFirstNotSum() {
        String reply = """
                方案一 总里程：约 600 km
                方案二 总里程：约 700 km
                """;
        assertEquals(600, RoutePlanParser.extractTotalDistance(reply), "只取第一条，不做累加");
    }

    /** 取不到 → -1（调用方据此跳过里程校验，而不是当成 0） */
    @Test
    void extractTotalDistanceReturnsMinusOneWhenAbsent() {
        assertEquals(-1, RoutePlanParser.extractTotalDistance("这段文字里没有任何里程标注"));
        assertEquals(-1, RoutePlanParser.extractTotalDistance(null));
    }

    // ==================== extractWaypoints ====================

    /** 优先结构化 waypoints */
    @Test
    void extractWaypointsPrefersStructuredField() {
        String reply = "途经城市：成都、雅安\n【路线JSON】{\"totalKm\": 900, \"waypoints\": \"雅安,康定,理塘\"}【路线JSON结束】";
        assertEquals("雅安,康定,理塘", RoutePlanParser.extractWaypoints(reply));
    }

    /** 无结构化字段 → 解析“途经城市：”行，只收地名后缀词、最多5个 */
    @Test
    void extractWaypointsParsesLabelledLine() {
        String reply = "途经城市：雅安市、康定市、理塘县、稻城县、泸定县、丹巴县、新都桥镇";
        String wp = RoutePlanParser.extractWaypoints(reply);
        String[] parts = wp.split(",");
        assertTrue(parts.length <= 5, "最多5个，实际: " + wp);
        assertTrue(wp.contains("雅安市"));
    }

    /** 无任何标注 → 退回全文地名正则兜底 */
    @Test
    void extractWaypointsFallsBackToGlobalRegex() {
        String wp = RoutePlanParser.extractWaypoints("先到雅安市，再走康定市，最后抵达稻城县。");
        assertTrue(wp.contains("雅安市"), "兜底正则应能提取地名，实际: " + wp);
    }

    /** 空/无地名 → 空串（不是 null，调用方直接拼 prompt） */
    @Test
    void extractWaypointsReturnsEmptyWhenNothingFound() {
        assertEquals("", RoutePlanParser.extractWaypoints("no place names here"));
        assertEquals("", RoutePlanParser.extractWaypoints(null));
    }

    // ==================== toInt ====================

    @Test
    void toIntHandlesNumberStringAndNull() {
        assertEquals(1566, RoutePlanParser.toInt(1566));
        assertEquals(1566, RoutePlanParser.toInt(1566L));
        assertEquals(1566, RoutePlanParser.toInt("  1566 "));
        assertEquals(-1, RoutePlanParser.toInt(null), "null → -1（表示未取到，不是0）");
        assertEquals(-1, RoutePlanParser.toInt("abc"), "无法解析 → -1");
    }
}
