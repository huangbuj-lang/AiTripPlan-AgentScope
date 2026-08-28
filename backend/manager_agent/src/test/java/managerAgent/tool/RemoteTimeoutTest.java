package managerAgent.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteTimeoutTest {

    @Test
    void usesConfiguredRemoteTimeout() {
        RemoteTimeout.configure(Map.of("remoteTimeoutSeconds", 45));

        assertEquals(45, RemoteTimeout.currentSeconds());
        assertEquals(Duration.ofSeconds(45), RemoteTimeout.timeout());
    }

    @Test
    void clampsConfiguredRemoteTimeoutToSafeRange() {
        RemoteTimeout.configure(Map.of("remoteTimeoutSeconds", 1));
        assertEquals(RemoteTimeout.MIN_SECONDS, RemoteTimeout.currentSeconds());

        RemoteTimeout.configure(Map.of("remoteTimeoutSeconds", 999));
        assertEquals(RemoteTimeout.MAX_SECONDS, RemoteTimeout.currentSeconds());
    }

    @Test
    void ignoresMissingSnapshot() {
        RemoteTimeout.configure(Map.of("remoteTimeoutSeconds", 60));
        RemoteTimeout.configure(null);

        assertEquals(60, RemoteTimeout.currentSeconds());
    }
}
