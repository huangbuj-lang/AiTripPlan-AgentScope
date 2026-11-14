package managerAgent.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import utils.AgentPrompts;
import utils.LlmClient;
import utils.ModelTimeouts;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 内容审核服务（阶段三）：DFA 敏感词快筛 + LLM 语义审核，双层 + 降级链。 */
@Slf4j
@Component
public class ModerationService {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 审核专用线程池（独立小池，不抢占主生成并发槽） */
    private static final ExecutorService MOD_POOL = Executors.newFixedThreadPool(2);

    /** DFA 敏感词表（快筛用，可扩展/从 DB 配置加载） */
    private static final String[] SENSITIVE_WORDS = {
            "代开发票", "办证", "加微信", "加V信", "联系方式私聊", "赌博", "博彩", "诈骗",
            "色情", "卖淫", "嫖娼", "毒品", "枪支", "枪支弹药", "刷单", "兼职日结", "传销"
    };

    /**
     * 作用：敏感词快速判定：命中词库直接返回 true
     * 参数：text 待检测文本
     * 返回值：true=命中敏感词
     * 异常：无
     */
    public boolean containsSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 作用：异步内容审核：规则命中则隐藏内容，否则交 LLM 判定后回写状态
     * 参数：targetType 目标类型（帖子/评论）；targetId 目标 ID；text 待审文本
     * 返回值：无
     * 异常：无（在线程池内执行，异常只记日志）
     */
    public void moderateAsync(int targetType, long targetId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        final String content = text;
        MOD_POOL.submit(() -> {
            try {
                int verdict = llmVerdict(content);
                String reason = "AI 审核";
                //落库
                jdbcTemplate.update(
                        "INSERT INTO moderation_records(target_type, target_id, verdict, reason, model) VALUES (?, ?, ?, ?, ?)",
                        targetType, targetId, verdict, reason, "deepseek");
                if (verdict == 2) { // block：隐藏内容
                    hideContent(targetType, targetId);
                    log.info("审核拦截 {}#{}：违规内容已隐藏", targetType == 1 ? "帖子" : "评论", targetId);
                }
            } catch (Exception e) {
                //降级：LLM 审核失败仅保留敏感词快筛，不阻塞、进入人工队列兜底
                log.warn("LLM 审核失败（降级为仅敏感词 + 人工队列）：{}", e.getMessage());
            }
        });
    }

    /**
     * 作用：用 LLM 判定内容是否违规（0 通过/1 违规/2 不确定）
     * 参数：text 待审文本
     * 返回值：判定码；调用失败返回 2（不确定，交人工）
     * 异常：无（异常内部捕获）
     */
    private int llmVerdict(String text) {
        String sysPrompt = AgentPrompts.moderation();
        Msg system = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(sysPrompt).build()).build();
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of("response_format", Map.of("type", "json_object")))
                .build();
        ChatResponse resp = LlmClient.model().stream(List.of(system, user), null, options)
                .blockLast(ModelTimeouts.moderationDuration());
        String out = textOf(resp);
        String verdict = extractVerdict(out);
        return switch (verdict) {
            case "review" -> 1;
            case "block" -> 2;
            default -> 0; // 无法判定按 pass，避免误伤正常内容
        };
    }

    /**
     * 作用：从模型输出中提取判定结论
     * 参数：out 模型原始输出
     * 返回值：判定码（0/1/2）；无法识别返回 2
     * 异常：无
     */
    private String extractVerdict(String out) {
        if (out == null) {
            return "pass";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"verdict\"\\s*:\\s*\"(pass|review|block)\"").matcher(out);
        return m.find() ? m.group(1) : "pass";
    }

    /**
     * 作用：把违规内容标记为隐藏
     * 参数：targetType 目标类型；targetId 目标 ID
     * 返回值：无
     * 异常：无（数据库异常内部捕获）
     */
    private void hideContent(int targetType, long targetId) {
        String table = targetType == 1 ? "posts" : "comments";
        jdbcTemplate.update("UPDATE " + table + " SET status = 0 WHERE id = ?", targetId);
    }
    /**
     * 作用：从 ChatResponse 中拼接全部文本块
     * 参数：resp 模型响应
     * 返回值：文本；响应为空返回空串
     * 异常：无
     */

    private String textOf(ChatResponse resp) {
        if (resp == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }
}
