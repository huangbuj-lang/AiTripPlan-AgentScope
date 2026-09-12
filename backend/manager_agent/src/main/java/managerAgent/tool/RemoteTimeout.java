package managerAgent.tool;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime budget shared by A2A calls and the orchestrator's future wait. */
public final class RemoteTimeout {
    public static final int MIN_SECONDS = 30;
    public static final int MAX_SECONDS = 900;

    private static final AtomicInteger SECONDS = new AtomicInteger(initialSeconds());

    private RemoteTimeout() {}

    public static Duration timeout() {
        return Duration.ofSeconds(SECONDS.get());
    }

    public static int currentSeconds() {
        return SECONDS.get();
    }

    public static void configure(Map<String, Object> orchestration) {
        if (orchestration == null) return;
        Object value = orchestration.get("remoteTimeoutSeconds");
        SECONDS.set(value instanceof Number number
                ? normalize(number.intValue())
                : initialSeconds());
    }

    private static int initialSeconds() {
        try {
            return normalize(Integer.parseInt(
                    System.getenv().getOrDefault("REMOTE_TIMEOUT_SECONDS", "480").trim()));
        } catch (NumberFormatException e) {
            return 480;
        }
    }

    private static int normalize(int value) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
    }
}
