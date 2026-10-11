package utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三域候选 JSON 工具单元测试（纯代码，不调 LLM）。
 */
class CandidateUtilTest {

    private static final String JSON = """
            {"attraction":{"city":"成都","degraded":false,"candidates":[
              {"name":"大熊猫基地","reason":"亲子友好","price":"55元","confidence":0.9,"openTime":"07:30-18:00"},
              {"name":"都江堰","confidence":0.85},
              {"name":"宽窄巷子","confidence":0.6}]},
             "hotel":{"candidates":[{"name":"锦江宾馆","price":"700/晚","confidence":0.88}]},
             "dining":{"degraded":true,"reason":"域专家查询失败"}}
            """;

    @Test
    void extractsBlockFromReply() {
        String reply = "这里是行程正文……\n\n【域候选JSON】\n" + JSON + "\n【域候选JSON结束】\n（附注）";
        String block = CandidateUtil.extractBlock(reply);
        assertTrue(block.contains("大熊猫基地"));
        assertTrue(block.endsWith("}"));
        assertEquals("", CandidateUtil.extractBlock("没有块的普通回复"));
        assertEquals("", CandidateUtil.extractBlock(null));
    }

    @Test
    void parsesAndReadsDomains() {
        Map<String, Object> cands = CandidateUtil.parse(JSON);
        assertNotNull(cands);
        assertEquals(3, CandidateUtil.items(cands, "attraction").size());
        assertEquals(1, CandidateUtil.items(cands, "hotel").size());
        assertTrue(CandidateUtil.items(cands, "dining").isEmpty());
        assertTrue(CandidateUtil.degraded(cands, "dining"), "degraded=true 应判定域降级");
        assertFalse(CandidateUtil.degraded(cands, "attraction"));
        assertNull(CandidateUtil.parse("不是JSON"));
        assertNull(CandidateUtil.parse(null));
    }

    @Test
    void avgConfidenceAndNeedsConfirm() {
        Map<String, Object> cands = CandidateUtil.parse(JSON);
        List<Map<String, Object>> items = CandidateUtil.items(cands, "attraction");
        double avg = CandidateUtil.avgConfidence(items);
        assertTrue(avg > 0.7 && avg < 0.8, "实际均值 " + avg);

        // 全局均值 <0.70 时低分候选应被标注 needsConfirm
        assertTrue(CandidateUtil.markNeedsConfirm(cands, "attraction"));
        Map<String, Object> low = CandidateUtil.items(cands, "attraction").get(2);
        assertEquals(Boolean.TRUE, low.get("needsConfirm"), "0.6 的候选应标注待确认");
        Map<String, Object> high = CandidateUtil.items(cands, "attraction").get(0);
        assertNull(high.get("needsConfirm"), "0.9 的候选不应标注");

        // 均值≥0.70 的域不标注
        assertFalse(CandidateUtil.markNeedsConfirm(cands, "hotel"));
    }

    @Test
    void evidenceListAndSummary() {
        Map<String, Object> cands = CandidateUtil.parse(JSON);
        CandidateUtil.attachEvidence(cands, "amap+local_db", "A", "2026-09-02 10:00");
        List<Map<String, Object>> ev = CandidateUtil.evidenceList(cands);
        assertEquals(2, ev.size(), "降级域(无候选)不产出证据记录");
        Map<String, Object> attraction = ev.get(0);
        assertEquals("attraction", attraction.get("domain"));
        assertEquals("A", attraction.get("grade"));
        assertEquals("2026-09-02 10:00", attraction.get("retrievedAt"), "抓取时间应随候选透传");
        assertEquals(Boolean.TRUE, attraction.get("needsConfirm"));
        String summary = CandidateUtil.evidenceSummary(cands);
        assertTrue(summary.contains("景点"));
        assertTrue(summary.contains("低置信已标注"));
    }

    @Test
    void promptTextIncludesNamesAndPrices() {
        Map<String, Object> cands = CandidateUtil.parse(JSON);
        String text = CandidateUtil.toPromptText(cands, 3);
        assertTrue(text.contains("大熊猫基地"));
        assertTrue(text.contains("55元"));
        assertTrue(text.contains("锦江宾馆"));
        assertFalse(text.contains("餐饮候选"), "降级域不输出候选文本");
    }

    @Test
    void namesListCapped() {
        Map<String, Object> cands = CandidateUtil.parse(JSON);
        assertEquals(2, CandidateUtil.names(cands, "attraction", 2).size());
        assertEquals("大熊猫基地", CandidateUtil.names(cands, "attraction", 5).get(0));
    }

    @Test
    void avgConfidenceFromTextFallback() {
        double avg = CandidateUtil.avgConfidenceFromText("{\"a\":{\"confidence\":0.8}}".replace("a", "x"));
        assertEquals(-1, avg, "无 confidence 字段返回 -1");
        double v = CandidateUtil.avgConfidenceFromText("{\"attraction\":{\"candidates\":[{\"confidence\":0.8},{\"confidence\":0.6}]}}");
        assertTrue(Math.abs(v - 0.7) < 0.01);
    }

    // ==================== parseDomainNode：扁平/包裹/抢救三形态 ====================

    @Test
    void parsesFlatDomainNode() {
        String out = "{\"domain\":\"attraction\",\"city\":\"成都\",\"degraded\":false,"
                + "\"candidates\":[{\"name\":\"熊猫基地\",\"confidence\":0.9}]}";
        Map<String, Object> node = CandidateUtil.parseDomainNode(out, "attraction");
        assertNotNull(node, "扁平形应解析出域节点");
        assertEquals(1, CandidateUtil.items(node, "attraction").size());
        assertFalse(CandidateUtil.degraded(node, "attraction"));
    }

    @Test
    void parsesWrappedDomainNode() {
        String out = "{\"attraction\":{\"degraded\":false,\"candidates\":[{\"name\":\"西湖\",\"confidence\":0.9}]}}";
        Map<String, Object> node = CandidateUtil.parseDomainNode(out, "attraction");
        assertNotNull(node, "包裹形应解析出域节点");
        assertEquals("西湖", CandidateUtil.names(node, "attraction", 1).get(0));
    }

    @Test
    void salvagesJsonFromProse() {
        String out = "本地知识库两次查询均失败，但高德数据已成功获取。让我基于高德数据整理候选。\n"
                + "{\"domain\":\"dining\",\"city\":\"成都\",\"degraded\":false,"
                + "\"candidates\":[{\"name\":\"小龙坎\",\"confidence\":0.8}]}";
        Map<String, Object> node = CandidateUtil.parseDomainNode(out, "dining");
        assertNotNull(node, "散文夹带JSON应能抢救");
        assertEquals("小龙坎", CandidateUtil.names(node, "dining", 1).get(0));
    }

    @Test
    void returnsNullOnProseWithoutJson() {
        assertNull(CandidateUtil.parseDomainNode("我需要再想想，候选还没准备好。", "hotel"));
        assertNull(CandidateUtil.parseDomainNode("{\"domain\":\"hotel\",\"reason\":\"还在分析\"}", "hotel"),
                "既无 candidates 也无 degraded 的对象不算域节点");
    }

    @Test
    void areaGroupHintGroupsByAreaText() {
        String multi = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区·西街","confidence":0.9},
                  {"name":"天后宫","area":"鲤城区·天后路","confidence":0.9},
                  {"name":"洛阳桥","area":"洛江区·桥南村","confidence":0.85}]}}
                """;
        String hint = CandidateUtil.areaGroupHint(CandidateUtil.parse(multi));
        assertTrue(hint.contains("鲤城区"), "应含归一化后的行政区名: " + hint);
        assertTrue(hint.contains("开元寺、天后宫"), "同行政区（·前缀相同）应归同组（保序）: " + hint);
        assertTrue(hint.contains("洛阳桥"), "另一区域应单独成组: " + hint);
        assertTrue(hint.startsWith("【景点区域分组"), "应有分组标题前缀");
        // 真实坑回归：域专家填"丰泽区·泉山路/丰泽区·北清东路268号"应归同组而非按全串拆散
        String quanzhou = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"清源山","area":"丰泽区·泉山路","confidence":0.85},
                  {"name":"泉州博物馆","area":"丰泽区·北清东路268号","confidence":0.85},
                  {"name":"泉州少林寺","area":"丰泽区·古道街66号","confidence":0.85}]}}
                """;
        assertEquals("", CandidateUtil.areaGroupHint(CandidateUtil.parse(quanzhou)),
                "归一化后全同区（丰泽区）→ 返回空串不产生噪音分组");
    }

    @Test
    void coordinateGroupHintClustersByRealCoords() {
        // 泉州真实坐标：开元寺(鲤城) 与 洛阳桥(洛江) 相距 ~11km > 5km 阈值 → 应分两组；
        // 同片区两个景点（西街近开元寺 ~1km）应同组。鲤城区坐标对：
        String json = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区·西街","location":"118.586,24.908","confidence":0.9},
                  {"name":"西街","area":"鲤城区·钟楼","location":"118.581,24.909","confidence":0.85},
                  {"name":"洛阳桥","area":"洛江区·桥南村","location":"118.684,24.963","confidence":0.85}]}}
                """;
        String hint = CandidateUtil.coordinateGroupHint(CandidateUtil.parse(json));
        assertTrue(hint.contains("开元寺、西街"), "距离近(坐标<1km)应同组: " + hint);
        assertTrue(hint.contains("洛阳桥"), "洛阳桥(相距>10km)应独立成组: " + hint);
        assertTrue(hint.contains("鲤城区"), "组名应取众数行政区(鲤城区): " + hint);
        assertTrue(hint.startsWith("【景点区域分组"), "应有坐标分组标题: " + hint);
    }

    @Test
    void coordinateGroupHintFallsBackEmptyWhenNoCoords() {
        // 全部无 location → 返回空（调用方回退 areaGroupHint）
        String noLoc = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区","confidence":0.9},
                  {"name":"洛阳桥","area":"洛江区","confidence":0.85}]}}
                """;
        assertEquals("", CandidateUtil.coordinateGroupHint(CandidateUtil.parse(noLoc)), "无坐标应返回空串");
        // 非法坐标 → 空
        String badLoc = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"A","location":"abc","confidence":0.9},
                  {"name":"B","location":"118,24","confidence":0.9}]}}
                """;
        assertEquals("", CandidateUtil.coordinateGroupHint(CandidateUtil.parse(badLoc)), "非法坐标应返回空串");
        // null / 空 → 空
        assertEquals("", CandidateUtil.coordinateGroupHint(null));
    }

    @Test
    void coordinateGroupHintAllNearbyIsEmpty() {
        // 全在 5km 内一簇 → 无分组价值返回空
        String near = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","location":"118.586,24.908","confidence":0.9},
                  {"name":"承天寺","location":"118.591,24.905","confidence":0.85},
                  {"name":"清净寺","location":"118.588,24.902","confidence":0.85}]}}
                """;
        assertEquals("", CandidateUtil.coordinateGroupHint(CandidateUtil.parse(near)), "全近邻一簇应返回空串");
    }

    @Test
    void coordinateGroupHintLabelsWithoutAreaBySpan() {
        // 无 area 但有真实远近距离 → 组名用跨度描述；分两组
        String json = """
                {"attraction":{"city":"X","degraded":false,"candidates":[
                  {"name":"近A","location":"118.586,24.908","confidence":0.9},
                  {"name":"近B","location":"118.590,24.905","confidence":0.85},
                  {"name":"远C","location":"118.780,25.100","confidence":0.85}]}}
                """;
        String hint = CandidateUtil.coordinateGroupHint(CandidateUtil.parse(json));
        assertTrue(hint.contains("近A、近B"), "近邻两点应同组: " + hint);
        assertTrue(hint.contains("近邻·跨度约"), "无area组名应含跨度描述: " + hint);
    }

    @Test
    void areaGroupHintEmptyWhenNotGroupable() {
        // 全在同区域 → 分组无意义
        String same = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区","confidence":0.9},
                  {"name":"西街","area":"鲤城区","confidence":0.9}]}}
                """;
        assertEquals("", CandidateUtil.areaGroupHint(CandidateUtil.parse(same)), "同区域应返回空串");
        // 无 area 字段 → 全归"未标注区域"一组 → 空
        String noArea = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","confidence":0.9},
                  {"name":"西街","confidence":0.9}]}}
                """;
        assertEquals("", CandidateUtil.areaGroupHint(CandidateUtil.parse(noArea)), "无 area 应返回空串");
        // 空候选 / null / 单景点 → 空
        assertEquals("", CandidateUtil.areaGroupHint(CandidateUtil.parse("{\"attraction\":{\"candidates\":[]}}")));
        assertEquals("", CandidateUtil.areaGroupHint(null));
    }

    // ==================== clusterHintSummary：聚类生效可观测摘要（管理端事件流） ====================

    @Test
    void clusterHintSummaryReportsCoordClustering() {
        // 3/4 带坐标、成 2 簇（同 fixture 语义：开元寺与西街近邻一组，洛阳桥独立）
        String json = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区·西街","location":"118.586,24.908","confidence":0.9},
                  {"name":"西街","area":"鲤城区·钟楼","location":"118.581,24.909","confidence":0.85},
                  {"name":"洛阳桥","area":"洛江区·桥南村","location":"118.684,24.963","confidence":0.85},
                  {"name":"无坐标景点","area":"惠安县","confidence":0.8}]}}
                """;
        String s = CandidateUtil.clusterHintSummary(CandidateUtil.parse(json));
        assertTrue(s.contains("坐标聚类分组已注入"), "坐标成簇应报坐标聚类生效: " + s);
        assertTrue(s.contains("2 组"), "应报簇数: " + s);
        assertTrue(s.contains("3/4"), "应报带坐标覆盖 3/4: " + s);
    }

    @Test
    void clusterHintSummaryReportsAreaFallback() {
        // 全无坐标但有区域 → 报回退文本分组（2 组：鲤城区 vs 洛江区）
        String noLoc = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区·西街","confidence":0.9},
                  {"name":"天后宫","area":"鲤城区·天后路","confidence":0.85},
                  {"name":"洛阳桥","area":"洛江区·桥南村","confidence":0.85}]}}
                """;
        String s = CandidateUtil.clusterHintSummary(CandidateUtil.parse(noLoc));
        assertTrue(s.contains("回退区域文本分组已注入"), "坐标成簇不足应报回退: " + s);
        assertTrue(s.contains("2 组"), "应报文本分组数: " + s);
        assertTrue(s.contains("0/3"), "应报坐标覆盖 0/3: " + s);
    }

    @Test
    void clusterHintSummaryReportsNoGrouping() {
        // 无坐标且同区（分组无意义）→ 报无分组提示；全近邻一簇同理
        String same = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区","confidence":0.9},
                  {"name":"西街","area":"鲤城区","confidence":0.9}]}}
                """;
        assertTrue(CandidateUtil.clusterHintSummary(CandidateUtil.parse(same)).contains("无分组提示"));
        String near = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","location":"118.586,24.908","confidence":0.9},
                  {"name":"承天寺","location":"118.591,24.905","confidence":0.85},
                  {"name":"清净寺","location":"118.588,24.902","confidence":0.85}]}}
                """;
        assertTrue(CandidateUtil.clusterHintSummary(CandidateUtil.parse(near)).contains("无分组提示"),
                "全近邻一簇无分组价值也应如实报无分组: " + CandidateUtil.clusterHintSummary(CandidateUtil.parse(near)));
        // 边界：null / 空 / 单景点 → 空串（调用方不记事件）
        assertEquals("", CandidateUtil.clusterHintSummary(null));
        assertEquals("", CandidateUtil.clusterHintSummary(CandidateUtil.parse("{}")));
    }

    // ==================== clusterStats：聚类效果结构化统计（任务3） ====================

    @Test
    void clusterStatsCoordMode() {
        String json = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区·西街","location":"118.586,24.908","confidence":0.9},
                  {"name":"西街","area":"鲤城区·钟楼","location":"118.581,24.909","confidence":0.85},
                  {"name":"洛阳桥","area":"洛江区·桥南村","location":"118.684,24.963","confidence":0.85},
                  {"name":"无坐标景点","area":"惠安县","confidence":0.8}]}}
                """;
        Map<String, Object> s = CandidateUtil.clusterStats(CandidateUtil.parse(json));
        assertEquals(4, s.get("attractionCount"));
        assertEquals(3, s.get("withLocation"));
        assertEquals(75.0, s.get("locationCoverage"));
        assertEquals(2, s.get("coordGroups"));
        assertEquals("coordinate", s.get("mode"));
        assertEquals(List.of(2, 1), s.get("groupSizes"), "组1=开元寺+西街，组2=洛阳桥");
    }

    @Test
    void clusterStatsAreaFallbackAndNone() {
        // 全无坐标 → area 回退模式
        String noLoc = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区·西街","confidence":0.9},
                  {"name":"天后宫","area":"鲤城区·天后路","confidence":0.85},
                  {"name":"洛阳桥","area":"洛江区·桥南村","confidence":0.85}]}}
                """;
        Map<String, Object> s = CandidateUtil.clusterStats(CandidateUtil.parse(noLoc));
        assertEquals(0, s.get("withLocation"));
        assertEquals(0.0, s.get("locationCoverage"));
        assertEquals(0, s.get("coordGroups"));
        assertEquals(2, s.get("areaGroups"));
        assertEquals("area", s.get("mode"));
        assertEquals(List.of(2, 1), s.get("groupSizes"));

        // 同区无坐标 → none 模式（未成组）
        String same = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","area":"鲤城区","confidence":0.9},
                  {"name":"西街","area":"鲤城区","confidence":0.9}]}}
                """;
        Map<String, Object> s2 = CandidateUtil.clusterStats(CandidateUtil.parse(same));
        assertEquals("none", s2.get("mode"));
        assertTrue(((List<?>) s2.get("groupSizes")).isEmpty());

        // null / 空候选 → 全 0
        Map<String, Object> s3 = CandidateUtil.clusterStats(null);
        assertEquals(0, s3.get("attractionCount"));
        assertEquals("none", s3.get("mode"));
    }

    // ==================== ★删聚合：行程JSON块 提取/剥离 ====================

    @Test
    void extractsPlanBlockFromReply() {
        String planJson = "{\"title\":\"成都3日游\",\"days\":3,\"sections\":[]}";
        String reply = "可读行程文本……\n\n【行程JSON】\n" + planJson + "\n【行程JSON结束】\n（结尾附注）";
        assertEquals(planJson, CandidateUtil.extractPlanBlock(reply));
        assertEquals("", CandidateUtil.extractPlanBlock("没有JSON块的纯文本"));
        assertEquals("", CandidateUtil.extractPlanBlock(null));
        assertEquals("", CandidateUtil.extractPlanBlock(""));
        // 无结束标记 → 取到末尾
        String openOnly = "文本【行程JSON】{\"title\":\"x\"}";
        assertTrue(CandidateUtil.extractPlanBlock(openOnly).contains("\"title\""));
        // 域候选块与行程块互不干扰
        String both = "文本【域候选JSON】{c:1}【域候选JSON结束】\n【行程JSON】{\"days\":2}【行程JSON结束】";
        assertEquals("{\"days\":2}", CandidateUtil.extractPlanBlock(both));
    }

    @Test
    void stripsPlanBlockFromReply() {
        String reply = "可读行程\n【行程JSON】\n{\"x\":1}\n【行程JSON结束】\n尾注";
        String stripped = CandidateUtil.stripPlanBlock(reply);
        assertFalse(stripped.contains("行程JSON"));
        assertTrue(stripped.startsWith("可读行程"));
        assertTrue(stripped.endsWith("尾注"));
        assertEquals("原样文本", CandidateUtil.stripPlanBlock("原样文本"));
        assertEquals("", CandidateUtil.stripPlanBlock(null));
    }

    // ==================== ★删聚合：簇代表景点清单（市内路线按簇查衔接） ====================

    @Test
    void clusterRepSightsPerCluster() {
        // 泉州：开元寺+西街 近(≤5km)一簇；洛阳桥 远一簇；清净寺 近开元寺但按贪心序并入第一簇
        String json = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","location":"118.586,24.908","confidence":0.9},
                  {"name":"西街","location":"118.581,24.909","confidence":0.85},
                  {"name":"洛阳桥","location":"118.684,24.963","confidence":0.85},
                  {"name":"清净寺","location":"118.588,24.902","confidence":0.85}]}}
                """;
        // 开元寺(首)为第一簇代表；洛阳桥单独成簇（相距>5km）→ 代表=开元寺,洛阳桥
        String reps = CandidateUtil.clusterRepSights(CandidateUtil.parse(json), 8);
        assertTrue(reps.startsWith("开元寺"), "首簇代表应为开元寺: " + reps);
        assertTrue(reps.contains("洛阳桥"), "远簇代表应为洛阳桥: " + reps);
        assertFalse(reps.contains("西街"), "同簇成员不作为代表: " + reps);
        // 上限语义：cap 保底3 仅当簇数足够；本例仅2簇 → 如实返回2个代表（保底不无中生有）
        String capped = CandidateUtil.clusterRepSights(CandidateUtil.parse(json), 1);
        assertEquals("开元寺,洛阳桥", capped, "max<簇数时按实际簇数返回: " + capped);
    }

    @Test
    void clusterRepSightsFallbacks() {
        // 无坐标 → 按候选顺序取前N
        String noLoc = """
                {"attraction":{"city":"泉州","degraded":false,"candidates":[
                  {"name":"开元寺","confidence":0.9},
                  {"name":"西街","confidence":0.85},
                  {"name":"洛阳桥","confidence":0.85},
                  {"name":"清净寺","confidence":0.85},
                  {"name":"天后宫","confidence":0.8}]}}
                """;
        assertEquals("开元寺,西街,洛阳桥,清净寺,天后宫",
                CandidateUtil.clusterRepSights(CandidateUtil.parse(noLoc), 8));
        assertEquals("开元寺,西街,洛阳桥",
                CandidateUtil.clusterRepSights(CandidateUtil.parse(noLoc), 3));
        // 空/单景点/null → 空串或单点
        assertEquals("", CandidateUtil.clusterRepSights(null, 8));
        assertEquals("", CandidateUtil.clusterRepSights(CandidateUtil.parse("{}"), 8));
        String single = """
                {"attraction":{"candidates":[{"name":"开元寺","location":"118.586,24.908"}]}}
                """;
        assertEquals("开元寺", CandidateUtil.clusterRepSights(CandidateUtil.parse(single), 8));
    }

    @Test
    void clusterRepSightsSingleClusterBackfill() {
        // 全部候选挤在一簇（市中心 5km 内）→ 贪心只有 1 个代表 → 补足到 3 个（路线Agent 才有衔接可查）
        String json = """
                {"attraction":{"city":"重庆","degraded":false,"candidates":[
                  {"name":"洪崖洞民俗风貌区","location":"106.584,29.562","confidence":0.9},
                  {"name":"解放碑步行街","location":"106.577,29.556","confidence":0.85},
                  {"name":"长江索道","location":"106.586,29.558","confidence":0.85},
                  {"name":"山城步道","location":"106.565,29.550","confidence":0.85}]}}
                """;
        String reps = CandidateUtil.clusterRepSights(CandidateUtil.parse(json), 8);
        String[] parts = reps.split(",");
        assertTrue(parts.length >= 2, "单簇也要补足≥2个代表供查衔接: " + reps);
        assertTrue(parts[0].contains("洪崖洞"), "首个代表应为首个候选: " + reps);
        // 无坐标兜底同样保证 ≥2（单景点除外）
        String noLocSingle = """
                {"attraction":{"candidates":[{"name":"只有一个景点","confidence":0.9}]}}
                """;
        assertEquals("只有一个景点", CandidateUtil.clusterRepSights(CandidateUtil.parse(noLocSingle), 8));
    }
}
