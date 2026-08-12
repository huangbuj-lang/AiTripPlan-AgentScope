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
import managerAgent.hook.planHook;
import managerAgent.plan.TripPlan;
import managerAgent.tool.RemoteAgentTool;
import reactor.core.publisher.Flux;
import utils.AgentUtils;
import utils.LimitedMemory;
import utils.ToolUtils;

import java.io.IOException;

/**
 * 主管Agent：拆解任务、协调远程Agent、整合输出
 */
public class ManagerAgent {

    private final ReActAgent agent;

    private static final JarSkillRepositoryAdapter SKILL_REPO = createSkillRepo(); // Skills仓库静态单例：fat jar中zipfs只能挂载一次，复用避免FileSystemAlreadyExistsException

    private static JarSkillRepositoryAdapter createSkillRepo() {
        try {
            return new JarSkillRepositoryAdapter("skills");
        } catch (IOException e) {
            System.err.println("★★★ 主管Skills仓库加载失败（不影响运行）: " + e.getMessage());
            return null;
        }
    }

    public ManagerAgent(String sessionId) {

        TripPlan plan = new TripPlan(); // 计划对象：Agent自主分解任务和步骤执行的核心
        ToolUtils toolUtils = new ToolUtils(); // 工具包：把远程Agent封装为工具
        RemoteAgentTool remoteAgentTool = new RemoteAgentTool();
        remoteAgentTool.setSessionId(sessionId); // ★会话隔离：注入sessionId，Agent结果事件写入本会话
        Toolkit toolkit = toolUtils.getToolkit(remoteAgentTool);

        // 挂载操作手册（SkillBox = 工具包 + 技能手册）
        SkillBox skillBox = new SkillBox(toolkit);
        if (SKILL_REPO != null) { // 复用静态单例仓库（避免fat jar重复挂载zipfs报错）
            try {
                AgentSkill managerSkill = SKILL_REPO.getSkill("Manager-Skills");
                skillBox.registerSkill(managerSkill);
            } catch (Exception e) {
                System.err.println("★★★ 主管Skills加载失败（不影响运行）: " + e.getMessage());
            }
        }

        PlanNotebook planNotebook = plan.getPlan();

        agent = AgentUtils.getReActAgentBuilder(
                "ManagerAgent",
                "主管Agent"
        )

                /* PlanNotebook流程：任务分解→生成步骤→状态跟踪→动态调整→完成 */
                .planNotebook(planNotebook)
                .hook(new planHook(planNotebook, sessionId)) // 拦截器：记录事件到EventStore（按会话隔离）/events实时展示 + 中断支持
                .memory(new LimitedMemory(20))// 限量记忆(20条)：防止上下文无限累积
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
                .doOnNext(msg->System.out.println(msg.getMessage().getTextContent()))
                .blockLast();
    }

    /**
     * Agent 运行并返回最终结果（供HTTP接口使用）
     * 同步 call()  直接拿最终回复
     */
    public String runAndReturn(String userPrompt) {
        Msg reply = agent.call(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userPrompt).build())
                        .build()
        ).block();

        String finalResult = (reply != null && reply.getTextContent() != null)
                ? reply.getTextContent().trim()
                : "（主管Agent没有返回内容）";

        // ★DeepSeek兼容修复：PlanNotebook模式下整合阶段可能把<tool_calls>XML泄漏进最终回复，
        //  导致 /result 返回残缺内容（前端概览卡片无法渲染）。剥离XML块，保留真正的计划文本。
        finalResult = stripToolCallXml(finalResult);

        if (finalResult.isEmpty() || finalResult.equals("（主管Agent没有返回内容）")) { // 兜底：同步调用没拿到内容时改用流式收集（极少发生）
            Flux<Event> stream = AgentUtils.streamResponse(agent, userPrompt);
            StringBuilder result = new StringBuilder();
            stream
                    .doOnNext(msg -> {
                        String text = msg.getMessage().getTextContent();
                        if (text != null && !text.isBlank()) {
                            result.append(text);
                        }
                    })
                    .blockLast();
            finalResult = result.toString().trim();
            finalResult = stripToolCallXml(finalResult); // 流式收集同样可能混入XML
        }
        return finalResult;
    }

    /**
     * 剥离最终回复中泄漏的 &lt;tool_calls&gt; XML 块（DeepSeek PlanNotebook 兼容问题）
     * 形式：&lt;tool_calls&gt;\n&lt;invoke name="xxx"&gt;...&lt;/invoke&gt;\n&lt;/tool_calls&gt;
     * 保留 XML 前后的真正计划文本；若剥离后为空则原样返回（避免误删正常内容）
     */
    private String stripToolCallXml(String text) {
        if (text == null || !text.contains("<tool_calls>")) {
            return text; // 没有XML块，原样返回
        }
        String stripped = text.replaceAll("(?s)<tool_calls>.*?</tool_calls>", "").trim();
        return stripped.isEmpty() ? text : stripped;
    }

}
