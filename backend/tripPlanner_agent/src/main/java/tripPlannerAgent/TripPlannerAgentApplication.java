package tripPlannerAgent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import utils.NacosModelConfig;
import utils.AgentRuntimeConfig;

/**
 * 启动类
 */
@SpringBootApplication
public class TripPlannerAgentApplication {
    public static void main(String[] args) {
        NacosModelConfig.start();
        AgentRuntimeConfig.start();
        utils.AgentPromptStore.start();
        SpringApplication.run(TripPlannerAgentApplication.class, args);

    }
}
