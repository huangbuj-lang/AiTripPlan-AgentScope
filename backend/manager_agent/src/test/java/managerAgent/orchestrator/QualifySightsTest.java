package managerAgent.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 地名歧义防护单测（2026-09-11）。
 *
 * <p>背景（实测证据）：探针逐阶段计时显示，裸地名"环球中心"被高德解析到**新疆阿克苏**，
 * 路线Agent 该轮查询白跑并触发重查，单次多花 48.6s（占整单 258s 的 19%）。
 * 修法 = 下发给路线Agent 的地名一律带城市限定前缀。
 */
class QualifySightsTest {

    @Test
    @DisplayName("裸地名逐个加城市前缀")
    void addsCityPrefix() {
        assertEquals("成都·环球中心、成都·宽窄巷子、成都·熊猫基地",
                TripOrchestrator.qualifySights("成都", "环球中心、宽窄巷子、熊猫基地"));
    }

    @Test
    @DisplayName("已带城市限定的原样保留（不出现重复前缀）")
    void keepsAlreadyQualified() {
        assertEquals("成都·环球中心", TripOrchestrator.qualifySights("成都", "成都·环球中心"));
        assertEquals("成都环球中心、成都·宽窄巷子",
                TripOrchestrator.qualifySights("成都", "成都环球中心、成都·宽窄巷子"));
        assertEquals("成都市人民公园", TripOrchestrator.qualifySights("成都", "成都市人民公园"));
    }

    @Test
    @DisplayName("兼容多种分隔符")
    void handlesSeparators() {
        assertEquals("成都·A、成都·B、成都·C、成都·D",
                TripOrchestrator.qualifySights("成都", "A,B，C;D"));
    }

    @Test
    @DisplayName("空值与空城市安全降级")
    void handlesBlankInputs() {
        assertEquals("", TripOrchestrator.qualifySights("成都", ""));
        assertEquals("", TripOrchestrator.qualifySights("成都", null));
        assertEquals("环球中心", TripOrchestrator.qualifySights("", "环球中心"));
        assertEquals("环球中心", TripOrchestrator.qualifySights(null, "环球中心"));
    }

    @Test
    @DisplayName("多余分隔符不产生空条目")
    void skipsEmptyTokens() {
        assertEquals("成都·环球中心、成都·宽窄巷子",
                TripOrchestrator.qualifySights("成都", "环球中心、、宽窄巷子、"));
    }
}
