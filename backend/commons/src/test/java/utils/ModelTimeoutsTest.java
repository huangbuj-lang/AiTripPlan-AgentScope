package utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelTimeoutsTest {

    @Test
    void usesDirectLlmTimeoutsFromNacos() throws Exception {
        NacosModelConfig.applyConfig("""
                {"timeouts":{
                  "defaultSeconds":30,
                  "moderationSeconds":15,
                  "aiAnswerSeconds":45,
                  "tripEditSeconds":90
                }}
                """);

        assertEquals(Duration.ofSeconds(30), ModelTimeouts.defaultDuration());
        assertEquals(Duration.ofSeconds(15), ModelTimeouts.moderationDuration());
        assertEquals(Duration.ofSeconds(45), ModelTimeouts.aiAnswerDuration());
        assertEquals(Duration.ofSeconds(90), ModelTimeouts.tripEditDuration());
    }

    @Test
    void clampsTimeoutsToSafeRange() throws Exception {
        NacosModelConfig.applyConfig("""
                {"timeouts":{
                  "defaultSeconds":0,
                  "moderationSeconds":1,
                  "aiAnswerSeconds":9999,
                  "tripEditSeconds":601
                }}
                """);

        assertEquals(Duration.ofSeconds(5), ModelTimeouts.defaultDuration());
        assertEquals(Duration.ofSeconds(5), ModelTimeouts.moderationDuration());
        assertEquals(Duration.ofSeconds(600), ModelTimeouts.aiAnswerDuration());
        assertEquals(Duration.ofSeconds(600), ModelTimeouts.tripEditDuration());
    }

    @Test
    void fallsBackToCodeDefaultsWhenTimeoutsAreAbsent() throws Exception {
        NacosModelConfig.applyConfig("{\"updatedAt\":\"2026-08-25T00:00:00Z\"}");

        assertEquals(Duration.ofSeconds(120), ModelTimeouts.defaultDuration());
        assertEquals(Duration.ofSeconds(60), ModelTimeouts.moderationDuration());
        assertEquals(Duration.ofSeconds(120), ModelTimeouts.aiAnswerDuration());
        assertEquals(Duration.ofSeconds(120), ModelTimeouts.tripEditDuration());
    }
}
