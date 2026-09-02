package utils;

import java.time.Duration;
import java.util.Map;

/** Direct LLM call timeouts controlled by the Nacos model config. */
public final class ModelTimeouts {
    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 600;

    private static final int DEFAULT_SECONDS = 120;
    private static final int MODERATION_SECONDS = 60;
    private static final int AI_ANSWER_SECONDS = 120;
    private static final int TRIP_EDIT_SECONDS = 120;

    private ModelTimeouts() {}

    public static Duration defaultDuration() {
        return Duration.ofMillis(defaultMillis());
    }

    public static long defaultMillis() {
        return seconds("defaultSeconds", DEFAULT_SECONDS) * 1000L;
    }

    public static Duration moderationDuration() {
        return Duration.ofMillis(moderationMillis());
    }

    public static long moderationMillis() {
        return seconds("moderationSeconds", MODERATION_SECONDS) * 1000L;
    }

    public static Duration aiAnswerDuration() {
        return Duration.ofMillis(aiAnswerMillis());
    }

    public static long aiAnswerMillis() {
        return seconds("aiAnswerSeconds", AI_ANSWER_SECONDS) * 1000L;
    }

    public static Duration tripEditDuration() {
        return Duration.ofMillis(tripEditMillis());
    }

    public static long tripEditMillis() {
        return seconds("tripEditSeconds", TRIP_EDIT_SECONDS) * 1000L;
    }

    private static int seconds(String key, int defaultValue) {
        Map<String, Object> timeouts = NacosModelConfig.timeoutsConfig();
        return normalize(timeouts.get(key) instanceof Number number
                ? number.intValue()
                : defaultValue);
    }

    private static int normalize(int value) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
    }
}
