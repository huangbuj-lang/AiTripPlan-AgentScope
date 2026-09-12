package utils;

import dto.Section;
import dto.TripPlanData;
import dto.TripRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 结构化 JSON 提取/解析工具单元测试 */
class JsonUtilsTest {

    @Test
    void extractsBareJsonObject() {
        String text = "{\"days\":3,\"destination\":\"惠州\"}";
        assertEquals("{\"days\":3,\"destination\":\"惠州\"}", JsonUtils.extractJsonObject(text));
    }

    @Test
    void extractsJsonWrappedInMarkdownFence() {
        String text = "好的，结果如下：\n```json\n{\"days\":3,\"destination\":\"惠州\"}\n```\n希望有帮助";
        assertEquals("{\"days\":3,\"destination\":\"惠州\"}", JsonUtils.extractJsonObject(text));
    }

    @Test
    void handlesBracesInsideStringValue() {
        String text = "{\"note\":\"带花括号 { } 的备注\",\"days\":2}";
        String json = JsonUtils.extractJsonObject(text);
        assertNotNull(json);
        assertTrue(json.contains("带花括号"));
    }

    @Test
    void returnsNullWhenNoJsonObject() {
        assertNull(JsonUtils.extractJsonObject("这里没有 JSON"));
        assertNull(JsonUtils.extractJsonObject(null));
    }

    @Test
    void parsesTripRequest() {
        TripRequest req = JsonUtils.extractAndParse(
                "{\"origin\":\"深圳\",\"destination\":\"惠州\",\"days\":3,\"date\":\"2026-08-14\",\"preference\":\"自驾\"}",
                TripRequest.class);
        assertNotNull(req);
        assertEquals("深圳", req.getOrigin());
        assertEquals("惠州", req.getDestination());
        assertEquals(3, req.getDays());
        assertEquals("自驾", req.getPreference());
    }

    @Test
    void parsesTripPlanDataWithSections() {
        String json = "{\"title\":\"惠州3日游\",\"days\":3,\"sections\":[" +
                "{\"type\":\"timeline\",\"title\":\"第1天\",\"items\":[{\"time\":\"09:00\",\"place\":\"惠州西湖\"}]}," +
                "{\"type\":\"tips\",\"title\":\"实用信息\",\"items\":[{\"kind\":\"电话\",\"text\":\"12345\"}]}]}";
        TripPlanData plan = JsonUtils.parse(json, TripPlanData.class);
        assertNotNull(plan);
        assertEquals("惠州3日游", plan.getTitle());
        assertEquals(2, plan.getSections().size());
        Section first = plan.getSections().get(0);
        assertEquals("timeline", first.getType());
        assertEquals("惠州西湖", first.getItems().get(0).getPlace());
    }

    @Test
    void roundTripsToJsonAndBack() {
        TripPlanData plan = new TripPlanData();
        plan.setTitle("测试");
        plan.setDays(2);
        Section s = new Section();
        s.setType("stay");
        s.setTitle("住宿");
        plan.getSections().add(s);

        String json = JsonUtils.toJson(plan);
        assertNotNull(json);
        TripPlanData back = JsonUtils.parse(json, TripPlanData.class);
        assertNotNull(back);
        assertEquals("测试", back.getTitle());
        assertEquals("stay", back.getSections().get(0).getType());
    }

    @Test
    void parseReturnsNullOnInvalidJson() {
        assertNull(JsonUtils.parse("not-json", TripPlanData.class));
        assertNull(JsonUtils.parse("", TripPlanData.class));
    }
}
