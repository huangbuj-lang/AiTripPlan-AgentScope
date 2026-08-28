package routeMakingAgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import routeMakingAgent.agents.RouteMakingAgent;
import utils.NacosModelConfig;
import utils.AgentRuntimeConfig;

/**
 * 启动类
 */
@SpringBootApplication
public class RouteMakingAgentApplication {
    public static void main(String[] args) {
        NacosModelConfig.start();
        AgentRuntimeConfig.start();
        utils.AgentPromptStore.start();
        SpringApplication.run(RouteMakingAgentApplication.class, args);
    }
}
