package managerAgent.orchestrator;

import dto.TripRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ★2026-09-11 单城市兜底测试。
 * 背景：`成都3天自驾游` 这类没写"A到B"的输入，需求解析 LLM 常把城市只填进出发地、目的地留空，
 * 实测解析成「成都 → 」；下游 assembleDayPlanText 照抄 destination 得到空串，
 * 于是报「行程JSON缺关键内容(目的地)」并整单降级为纯文本（一次真实端到端就是这样失败的）。
 */
class RequirementNormalizerPlaceTest {

    private TripRequest req(String origin, String dest) {
        TripRequest r = new TripRequest();
        r.setOrigin(origin);
        r.setDestination(dest);
        return r;
    }

    @Test
    void missingDestinationFallsBackToOrigin() {
        TripRequest r = req("成都", "");
        RequirementNormalizer.normalizePlace(r);
        assertEquals("成都", r.getDestination(), "单城市输入：目的地为空应取出发地");
    }

    @Test
    void missingOriginFallsBackToDestination() {
        TripRequest r = req("", "成都");
        RequirementNormalizer.normalizePlace(r);
        assertEquals("成都", r.getOrigin());
    }

    @Test
    void crossCityRequestIsUntouched() {
        // 跨城：两地都有值 → 绝不能改写（否则"北京→成都"会被改成"北京→北京"）
        TripRequest r = req("北京", "成都");
        RequirementNormalizer.normalizePlace(r);
        assertEquals("北京", r.getOrigin());
        assertEquals("成都", r.getDestination());
    }

    @Test
    void nullValuesAreSafe() {
        TripRequest r = req(null, null);
        assertDoesNotThrow(() -> RequirementNormalizer.normalizePlace(r));
    }

    @Test
    void whitespaceOnlyIsTreatedAsEmpty() {
        TripRequest r = req("  成都  ", " ");
        RequirementNormalizer.normalizePlace(r);
        assertEquals("成都", r.getDestination());
    }

    @Test
    void normalizeEntryPointsCallsPlaceFallback() {
        // 走主入口，防止以后有人在 normalize() 里漏掉这一步
        TripRequest r = req("成都", "");
        r.setRaw("成都3天自驾游");
        RequirementNormalizer.normalize(r);
        assertEquals("成都", r.getDestination());
    }
}
