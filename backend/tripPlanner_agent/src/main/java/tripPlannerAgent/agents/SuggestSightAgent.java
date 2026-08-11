package tripPlannerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.util.JarSkillRepositoryAdapter;
import io.agentscope.core.tool.Toolkit;
import tripPlannerAgent.tool.Calculate;
import utils.AgentUtils;

import java.io.IOException;

/**
 * 景点推荐Agent
 */
public class SuggestSightAgent {

    private static final JarSkillRepositoryAdapter SKILL_REPO = createSkillRepo(); // Skills仓库静态单例：fat jar中zipfs只能挂载一次，复用避免FileSystemAlreadyExistsException

    private static JarSkillRepositoryAdapter createSkillRepo() {
        try {
            return new JarSkillRepositoryAdapter("skills");
        } catch (IOException e) {
            throw new RuntimeException("Skills仓库加载失败", e);
        }
    }

    public ReActAgent getSuggestSightAgent() { // 创建景点推荐Agent

        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit); // 构建SkillBox（工具包 + 技能手册）

        JarSkillRepositoryAdapter repo = SKILL_REPO; // 复用静态单例仓库（避免fat jar重复挂载zipfs）
        AgentSkill SuggestSightsSkill = repo.getSkill("Suggest-Sights"); // 景点推荐技能
        AgentSkill MakeATableSkill = repo.getSkill("Make-A-Table"); // 表格制作技能
        skillBox.registerSkill(SuggestSightsSkill);
        skillBox.registerSkill(MakeATableSkill);

        skillBox.registration().tool(new Calculate()); // 注册工具

        ReActAgent.Builder builder = AgentUtils.getReActAgentBuilder(
                        "SuggestSightAgent",
                        "擅长景点推荐"
                )
                .toolkit(toolkit) // 挂载工具包
                .skillBox(skillBox) // 挂载Skills

                ;

        return builder.build();
    }
}
