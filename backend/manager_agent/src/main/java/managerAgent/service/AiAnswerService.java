package managerAgent.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.controller.NotificationHub;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import utils.LlmClient;
import utils.ModelTimeouts;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** AI 答主服务（阶段三）：问答版块异步生成 AI 候选回答，标注「AI 生成」，楼主可采纳。 */
@Slf4j
@Component
public class AiAnswerService {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** AI 答主专用线程池 + 并发闸门（独立小池，防止论坛流量饿死正常生成） */
    private static final ExecutorService AI_POOL = Executors.newFixedThreadPool(2);
    private static final Semaphore AI_SLOTS = new Semaphore(2);

    /** AI 回答作为评论落库的保留用户 ID（无真实用户，前端按 is_ai 显示「AI答主」） */
    public static final long AI_USER_ID = 0L;

    /**
     * 作用：异步生成论坛帖的 AI 回答并通知提问者（独立线程池执行，不阻塞请求）
     * 参数：postId 帖子 ID；requesterId 提问用户 ID
     * 返回值：无
     * 异常：无（生成或通知失败只记日志）
     */
    public void answerAsync(long postId, long requesterId) {
        AI_POOL.submit(() -> {
            if (!AI_SLOTS.tryAcquire()) {
                //并发子池满：直接返回，避免排队积压
                return;
            }
            try {
                List<Map<String, Object>> posts = jdbcTemplate.queryForList(
                        "SELECT user_id, board_id, title, content FROM posts WHERE id = ? AND status = 1", postId);
                if (posts.isEmpty()) {
                    return;
                }
                Map<String, Object> post = posts.get(0);
                long authorId = ((Number) post.get("user_id")).longValue();
                String title = String.valueOf(post.get("title"));
                String content = String.valueOf(post.get("content"));

                String answer = generate(title, content);
                if (answer == null || answer.isBlank()) {
                    log.warn("AI 答主生成空回答，postId={}", postId);
                    return;
                }
                String safe = answer.trim(); // 兜底截断：老库content仍是varchar(1000)时不至于写失败丢回答
                if (safe.length() > 900) safe = safe.substring(0, 900);
                jdbcTemplate.update(
                        "INSERT INTO comments(post_id, user_id, content, is_ai) VALUES (?, ?, ?, 1)",
                        postId, AI_USER_ID, safe);
                jdbcTemplate.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?", postId);
                //通知楼主（type=4 AI 回答完成）
                if (authorId != requesterId) {
                    notifyUser(authorId, 4, "AI 答主已回复你的问题", "/post.html?id=" + postId);
                }
            } catch (Exception e) {
                log.warn("AI 答主生成失败，postId={}: {}", postId, e.getMessage());
            } finally {
                AI_SLOTS.release();
            }
        });
    }

    /**
     * 作用：生成 AI 回答：按时长预算调用模型，超时或空结果走兜底文案
     * 参数：title 帖子标题；content 帖子正文
     * 返回值：回答文本，永不为空
     * 异常：无（异常内部捕获，返回兜底文案）
     */
    private String generate(String title, String content) {
        return utils.CostDimension.runAs(utils.CostDimension.FORUM, () -> generate0(title, content));
    }
    /**
     * 作用：实际模型调用：拼提示词并要求回答控制在限定篇幅
     * 参数：title 帖子标题；content 帖子正文
     * 返回值：模型回答文本，可能为空串
     * 异常：无（异常内部捕获）
     */

    private String generate0(String title, String content) {
        String sysPrompt = "你是专业的旅行问答 AI 答主。请用中文详细、具体、可操作地回答用户问题，"
                + "给出景点/交通/住宿/预算等实在建议，不要泛泛而谈。直接给出回答正文，不要开场白。";
        Msg system = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(sysPrompt).build()).build();
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("问题：" + title + "\n\n" + content).build()).build();
        ChatResponse resp = LlmClient.model().stream(List.of(system, user), null, null)
                .blockLast(ModelTimeouts.aiAnswerDuration());
        return textOf(resp);
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

    /**
     * 作用：向用户推送一条站内通知
     * 参数：userId 用户 ID；type 通知类型；content 通知内容；linkUrl 跳转链接
     * 返回值：无
     * 异常：无（推送失败内部捕获）
     */
    private void notifyUser(long userId, int type, String content, String linkUrl) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO notifications(user_id, type, content, link_url) VALUES (?, ?, ?, ?)",
                    userId, type, content, linkUrl);
            NotificationHub.push(userId, "{\"type\":" + type + ",\"content\":\"" +
                    content.replace("\"", "\\\"") + "\",\"link\":\"" + (linkUrl == null ? "" : linkUrl) + "\"}");
        } catch (Exception ignored) { /* 通知失败不阻塞 */ }
    }
}
