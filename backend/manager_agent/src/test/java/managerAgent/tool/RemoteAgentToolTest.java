package managerAgent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAgentToolTest {

    @Test
    void returnsDegradationMarkerWhenRemoteAgentSwitchIsOff() throws Exception {
        utils.AgentRuntimeConfig.applyConfig("""
                {"tools":{"remoteRouteAgent":false,"remoteTripAgent":false}}
                """);
        RemoteAgentTool tool = new RemoteAgentTool();

        assertTrue(tool.callRouteMakingAgent("test").contains("已通过Nacos开关关闭"));
        assertTrue(tool.callTripPlannerAgent("test").contains("已通过Nacos开关关闭"));
    }
}
