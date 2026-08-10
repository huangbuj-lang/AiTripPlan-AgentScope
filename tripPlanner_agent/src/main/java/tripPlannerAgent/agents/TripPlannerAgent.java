package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.JarSkillRepositoryAdapter;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tripPlannerAgent.tool.Calculate;
import utils.AgentUtils;
import utils.LimitedMemory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/**
 * 行程规划Agent
 */
@Component
public class TripPlannerAgent {

    @Bean
    public ReActAgent getTripPlannerAgent() throws URISyntaxException, IOException {

        Toolkit toolkit = new Toolkit();

        SuggestSightAgent SuggestSightAgent = new SuggestSightAgent();

        toolkit.registration().subAgent( // 将子Agent作为工具注册
                ()->SuggestSightAgent.getSuggestSightAgent()
        ).apply();

        /* AgentScope框架自带注册中心AgentScopeA2aServer，
           智能体卡片通过SpringBoot Bean自动注入（或用下方注释代码手动写入） */

        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder( // 行程规划Agent Builder
                "TripPlannerAgent",
                "擅长处理景点行程规划"
        )
                .memory(new LimitedMemory(20)) // 限量记忆：单例Agent上下文无限增长会导致状态卡死/大模型400
                .toolkit(toolkit) // 挂载工具包

                ;

        return builder.build();

        //=========== 手动写入注册中心，项目不用种方式 START ====

//        //行程规划Agent 智能体卡片
//        ConfigurableAgentCard agentCard =  new ConfigurableAgentCard.Builder()
//                .name("TripPlannerAgent")
//                .description("行程规划Agent")
//                .build();
//
//        //将智能体卡片写入到AgentScope自带的注册中心
//        AgentScopeA2aServer.builder(builder)
//                .agentCard(agentCard)
//                .deploymentProperties(
//                       new DeploymentProperties(
//                               "localhost",
//                               8080)
//                )
//                .build();

        //还需要AgentScopeA2aServer启动

        //======== 手动写入注册中心，项目不用种方式 END ====

    }
}
