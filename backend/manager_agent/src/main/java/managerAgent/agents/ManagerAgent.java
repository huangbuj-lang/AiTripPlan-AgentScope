package managerAgent.agents;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.util.JarSkillRepositoryAdapter;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.planHook;
import managerAgent.plan.TripPlan;
import managerAgent.tool.RemoteAgentTool;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.Config;
import utils.LimitedMemory;
import utils.ToolUtils;

import java.io.IOException;
import java.time.Duration;

/**
 * 主管Agent：拆解任务、协调远程Agent、整合输出
 */
@Slf4j
public class ManagerAgent {

    private final ReActAgent agent;

    private static final JarSkillRepositoryAdapter SKILL_REPO = createSkillRepo(); // Skills仓库静态单例

    private static JarSkillRepositoryAdapter createSkillRepo() {
        try {
            return new JarSkillRepositoryAdapter("skills");
        } catch (IOException e) {
            log.error("★★★ 主管Skills仓库加载失败（不影响运行）: {}", e.getMessage());
            return null;
        }
    }

    public ManagerAgent(String sessionId) {

        TripPlan plan = new TripPlan(); // 创建计划对象
        ToolUtils toolUtils = new ToolUtils(true); // 创建工具包（远程Agent封装为工具）
        RemoteAgentTool remoteAgentTool = new RemoteAgentTool();
        remoteAgentTool.setSessionId(sessionId); // 注入会话ID（结果事件按会话隔离）
        Toolkit toolkit = toolUtils.getToolkit(remoteAgentTool);

        // 挂载操作手册（SkillBox = 工具包 + 技能手册）
        SkillBox skillBox = new SkillBox(toolkit);
        if (SKILL_REPO != null) { // 仓库存在时加载主管技能
            try {
                AgentSkill managerSkill = SKILL_REPO.getSkill("Manager-Skills");
                skillBox.registerSkill(managerSkill);
            } catch (Exception e) {
                log.error("★★★ 主管Skills加载失败（不影响运行）: {}", e.getMessage());
            }
        }

        PlanNotebook planNotebook = plan.getPlan();

        agent = AgentUtils.getReActAgentBuilder(
                "ManagerAgent",
                "主管Agent"
        )

                /* PlanNotebook：任务分解→步骤执行→完成 */
                .planNotebook(planNotebook)
                .hook(new planHook(planNotebook, sessionId)) // 注册事件拦截器（记录事件+中断支持）
                .memory(new LimitedMemory(Config.getInt("MEMORY_SIZE", 20)))// 限量记忆（20条）
                .toolkit(toolkit) // 工具包
                .skillBox(skillBox) // 操作手册
                .build();

    }

    /**
     * Agent 运行（默认任务）
     */
    public void run() {
        String defaultPrompt =
                """
                   帮我制定2026年元旦,
                   深圳到惠州3日游自驾游计划，
                   请包含吃住行，天气，酒店，餐饮美食。
                """;
        run(defaultPrompt);
    }

    /**
     * Agent 运行（用户自定义任务）
     */
    public void run(String userPrompt) {
        Flux<Event> stream = AgentUtils.streamResponse(agent, userPrompt);

        stream // 打印响应并阻塞到结束
                .doOnNext(msg -> log.info("{}", msg.getMessage().getTextContent()))
                .blockLast(Duration.ofSeconds(600)); // 带超时，避免卡死
    }

    /**
     * Agent 运行并返回最终结果（供HTTP接口使用）
     */
    public String runAndReturn(String userPrompt) {
        Msg reply = agent.call(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userPrompt).build())
                        .build()
        ).block(Duration.ofSeconds(600)); // 带超时，避免卡死占死并发槽

        String finalResult = (reply != null && reply.getTextContent() != null)
                ? reply.getTextContent().trim()
                : "（主管Agent没有返回内容）";

        // 剥离回复中泄漏的<tool_calls>XML块
        finalResult = stripToolCallXml(finalResult);

        // 剥离回复开头的引导句/过程叙述段
        finalResult = stripIntroSentence(finalResult);

        if (finalResult.isEmpty() || finalResult.equals("（主管Agent没有返回内容）")) { // 同步调用无结果时改用流式收集兜底
            Flux<Event> stream = AgentUtils.streamResponse(agent, userPrompt);
            StringBuilder result = new StringBuilder();
            stream
                    .doOnNext(msg -> {
                        String text = msg.getMessage().getTextContent();
                        if (text != null && !text.isBlank()) {
                            result.append(text);
                        }
                    })
                    .blockLast(Duration.ofSeconds(600)); // 带超时，避免卡死占死并发槽
            finalResult = result.toString().trim();
            finalResult = stripToolCallXml(finalResult); // 流式结果同样剥离XML块
            finalResult = stripIntroSentence(finalResult); // 流式结果同样剥离引导句
        }
        return finalResult;
    }

    /**
     * 剥离回复中泄漏的 tool_calls XML 块
     */
    private String stripToolCallXml(String text) {
        if (text == null || !text.contains("<tool_calls>")) {
            return text; // 无XML块则原样返回
        }
        // 匹配闭合的XML块
        String stripped = text.replaceAll("(?s)<tool_calls>.*?</tool_calls>", "").trim();
        // 匹配未闭合的XML块（截断场景）
        if (stripped.contains("<tool_calls>")) {
            int idx = stripped.indexOf("<tool_calls>");
            // 只保留XML块前的文本
            String before = stripped.substring(0, idx).trim();
            stripped = before;
        }
        return stripped.isEmpty() ? text : stripped;
    }

    /**
     * 剥离回复开头的引导句/过程叙述段
     */
    private String stripIntroSentence(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // 引导句关键词特征（避免误删计划正文）
        String[] lines = text.split("\\n");
        StringBuilder kept = new StringBuilder();
        boolean stripping = true; // 开头剥离模式标志
        for (String line : lines) {
            String t = line.trim();
            if (stripping) {
                boolean isIntro = t.isEmpty() // 空行视为引导段
                        || (t.length() < 120 // 引导句通常较短
                        && (t.startsWith("我已") || t.startsWith("我已经") || t.startsWith("我已加载")
                        || t.startsWith("我按照") || t.startsWith("我根据") || t.startsWith("以下是")
                        || t.startsWith("下面") || t.startsWith("本次") || t.startsWith("当前")
                        || t.startsWith("经过") || t.contains("已制定完毕") || t.contains("已成功完成")
                        || t.contains("计划状态已标记") || t.contains("已标记为") || t.contains("最终完整方案")
                        || t.contains("以下是为您") || t.contains("以下为您的") || t.contains("最终计划：")
                        || t.contains("完整规划") || t.contains("完整计划已") || t.contains("已输出完整")
                        || t.contains("所有数据均来自") || t.contains("真实返回结果") || t.contains("操作手册")
                        || t.contains("核对完整") || t.contains("按流程") || t.contains("完整流程")));
                if (isIntro) {
                    continue; // 剥离引导段
                }
                stripping = false; // 遇到正文后停止剥离
            }
            kept.append(line).append("\n");
        }
        String result = kept.toString().trim();
        return result.isEmpty() ? text : result; // 全部被剥离时返回原文
    }

}
