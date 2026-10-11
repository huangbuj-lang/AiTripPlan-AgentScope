package utils;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeConfigTest {

    @Test
    void parsesToolSwitchesAndUsesDefaultsForMissingKeys() throws Exception {
        AgentRuntimeConfig.applyConfig("""
                {"tools":{"amap":false,"localKnowledge":true}}
                """);

        assertFalse(AgentRuntimeConfig.toolEnabled("amap", true));
        assertTrue(AgentRuntimeConfig.toolEnabled("localKnowledge", false));
        assertTrue(AgentRuntimeConfig.toolEnabled("remoteRouteAgent", true));
    }

    @Test
    void resolvesNacosPromptOverridesAndFallsBackWhenBlank() {
        AgentPromptStore.applyPrompt("requestParser", "custom parser");
        AgentPromptStore.applyPrompt("toolQueryTrip", "custom trip tool");

        assertEquals("custom parser", AgentPrompts.requestParser());
        assertEquals("custom trip tool", AgentPrompts.toolQueryTrip());
        assertEquals(AgentPrompts.defaults().get("moderation"), AgentPrompts.moderation());

        AgentPromptStore.applyPrompt("requestParser", "");
        AgentPromptStore.applyPrompt("toolQueryTrip", " ");

        assertEquals(AgentPrompts.defaults().get("requestParser"), AgentPrompts.requestParser());
        assertEquals(AgentPrompts.defaults().get("toolQueryTrip"), AgentPrompts.toolQueryTrip());
    }

    @Test
    void wrapsRegisteredToolWithHotDescriptionButKeepsExecutionTool() throws Exception {
        AgentRuntimeConfig.applyConfig("""
                {"tools":{"amap":false,"localKnowledge":true}}
                """);
        AgentPromptStore.applyPrompt("toolLocalKnowledge", "热更后的本地知识工具");

        Toolkit tripTools = new Toolkit();
        utils.mcp.AmapEnhancedToolFactory.register(tripTools,
                utils.mcp.AmapEnhancedToolFactory.AgentType.TRIP);

        assertEquals("query_local_knowledge", tripTools.getTool("query_local_knowledge").getName());
        assertEquals("热更后的本地知识工具", tripTools.getTool("query_local_knowledge").getDescription());
        assertTrue(tripTools.getToolSchemas().stream()
                .anyMatch(schema -> "query_local_knowledge".equals(schema.getName())
                        && "热更后的本地知识工具".equals(schema.getDescription())));
    }

    @AfterEach
    void resetRuntimeConfig() throws Exception {
        AgentRuntimeConfig.applyConfig("{}");
        AgentPromptStore.resetForTest();
    }

    @Test
    void resolvesExternalServiceUrlsFromNacos() throws Exception {
        AgentRuntimeConfig.applyConfig("""
                {"services":{
                  "amapPlaceSearchUrl":"https://amap.example/v3/place/text",
                  "amapStaticMapUrl":"https://amap.example/v3/staticmap",
                  "amapNavigationUrl":"https://nav.example/dir?type=1",
                  "amapMcpUrl":"https://mcp.example/mcp"
                }}
                """);

        assertEquals("https://amap.example/v3/place/text?query=1",
                ExternalServices.amapPlaceSearchUrl("query=1"));
        assertEquals("https://amap.example/v3/staticmap?markers=1",
                ExternalServices.amapStaticMapUrl("markers=1"));
        assertEquals(URI.create("https://nav.example/dir?type=1"),
                URI.create(ExternalServices.amapNavigationUrl()));
        assertEquals("https://mcp.example/mcp?key=abc", ExternalServices.amapMcpUrl("abc"));
    }

    @Test
    void rejectsInvalidExternalServiceUrlInsteadOfSilentlyUsingIt() throws Exception {
        AgentRuntimeConfig.applyConfig("""
                {"services":{"amapNavigationUrl":"ftp://nav.example/dir"}}
                """);

        assertThrows(IllegalStateException.class, ExternalServices::amapNavigationUrl);
    }

    @Test
    void resolvesAmapCredentialsFromNacos() throws Exception {
        AgentRuntimeConfig.applyConfig("""
                {"services":{"amapMcpUrl":"https://mcp.example/mcp"},"credentials":{"amapKey":"web-key","amapMcpKey":"mcp-key"}}
                """);

        assertEquals("web-key", ExternalServices.amapKey());
        assertEquals("mcp-key", ExternalServices.amapMcpKey());
        assertEquals("https://mcp.example/mcp?key=mcp-key",
                ExternalServices.amapMcpUrl(ExternalServices.amapMcpKey()));
    }

    @Test
    void usesConfiguredWebKeyAsMcpFallback() throws Exception {
        // ★2026-09-11 本测试断言"未配 mcpKey 时回退到 webKey"，但 amapMcpKey() 的优先级是
        //   显式配置 → 环境变量 AMAP_MCP_KEY → webKey。若运行环境恰好设置了 AMAP_MCP_KEY
        //   （如加载了 .env），回退分支根本不会走到 → 测试假失败。
        //   这不是产品缺陷，是测试对环境的隐含假设，故此处显式跳过而非误报失败。
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("AMAP_MCP_KEY") == null || System.getenv("AMAP_MCP_KEY").isBlank(),
                "环境已设置 AMAP_MCP_KEY，回退分支不适用，跳过");

        AgentRuntimeConfig.applyConfig("""
                {"credentials":{"amapKey":"web-key"}}
                """);

        assertEquals("web-key", ExternalServices.amapKey());
        assertEquals("web-key", ExternalServices.amapMcpKey());
    }

    @Test
    void omitsDisabledAmapToolsButKeepsEnabledLocalKnowledge() throws Exception {
        AgentRuntimeConfig.applyConfig("""
                {"tools":{"amap":false,"localKnowledge":true}}
                """);

        Toolkit routeTools = new Toolkit();
        utils.mcp.AmapEnhancedToolFactory.register(routeTools,
                utils.mcp.AmapEnhancedToolFactory.AgentType.ROUTE);
        assertTrue(routeTools.getToolNames().isEmpty());

        Toolkit tripTools = new Toolkit();
        utils.mcp.AmapEnhancedToolFactory.register(tripTools,
                utils.mcp.AmapEnhancedToolFactory.AgentType.TRIP);
        assertEquals(Set.of("query_local_knowledge"), tripTools.getToolNames());
    }
}
