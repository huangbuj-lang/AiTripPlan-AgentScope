package utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosModelConfigTest {

    @Test
    void exposesPythonSectionFromLatestNacosSnapshot() throws Exception {
        NacosModelConfig.applyConfig("""
                {"python":{"url":"https://example.com/v1","key":"test-key","name":"test-model"}}
                """);

        Map<String, Object> python = NacosModelConfig.pythonConfig();
        assertNotNull(python);
        assertEquals("https://example.com/v1", python.get("url"));
        assertEquals("test-key", python.get("key"));
        assertEquals("test-model", python.get("name"));
    }

    @Test
    void returnsEmptyPythonConfigWhenSnapshotHasNoPythonSection() throws Exception {
        NacosModelConfig.applyConfig("{\"updatedAt\":\"2026-08-25T00:00:00Z\"}");

        Map<String, Object> python = NacosModelConfig.pythonConfig();
        assertNotNull(python);
        assertTrue(python.isEmpty());
    }

    @Test
    void exposesOrchestrationSectionFromLatestNacosSnapshot() throws Exception {
        NacosModelConfig.applyConfig("""
                {"orchestration":{"maxConcurrentTasks":3}}
                """);

        Map<String, Object> orchestration = NacosModelConfig.orchestrationConfig();
        assertNotNull(orchestration);
        assertEquals(3, orchestration.get("maxConcurrentTasks"));
    }
}
