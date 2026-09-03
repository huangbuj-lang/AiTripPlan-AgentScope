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
}
