package managerAgent.service;

import jakarta.annotation.Resource;
import managerAgent.controller.NotificationHub;
import managerAgent.repository.ForumRepository;
import managerAgent.repository.NotificationRepository;
import managerAgent.tool.ProfileHook;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 论坛业务（P1）：三版块 / 发帖 / 评论(两级楼中楼) / 点赞收藏 / 搜索 / 同款行程复制 / 业务日配额。
 * 只做业务，SQL 在 ForumRepository，通知写入在 NotificationRepository，内容审核在 ModerationService。
 */
@Service
public class ForumService {

    @Resource
    private ForumRepository forumRepository;

    @Resource
    private NotificationRepository notificationRepository;

    @Resource
    private ModerationService moderationService;

    @Resource
    private AiAnswerService aiAnswerService;

    private static final int POST_DAILY_LIMIT = 5;      // 发帖日配额
    private static final int COMMENT_DAILY_LIMIT = 30;  // 评论日配额
    private static final int AI_ANSWER_DAILY_LIMIT = 3; // AI 答主请求日配额

    /** 业务日配额校验：当天该操作是否超限（table 只允许 posts / comments） */
    private boolean overDailyLimit(long userId, String table) {
        int limit = "posts".equals(table) ? POST_DAILY_LIMIT : COMMENT_DAILY_LIMIT;
        return forumRepository.countTodayByTable(table, userId) >= limit;
    }

    /** 写入一条通知（被评论/点赞/收藏时通知帖子作者），并实时 SSE 推送 */
    private void notify(long toUserId, int type, String content, String linkUrl) {
        try {
            notificationRepository.insert(toUserId, type, content, linkUrl);
            //SSE 载荷沿用原字段（type/content/link），改用 Jackson 序列化，免手工转义
            java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("type", type);
            msg.put("content", content);
            msg.put("link", linkUrl == null ? "" : linkUrl);
            NotificationHub.push(toUserId, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg));
        } catch (Exception ignored) { /* 通知失败不阻塞主流程 */ }
    }

    //==================== 版块 / 帖子 ====================

    public Map<String, Object> boards() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("boards", forumRepository.listBoards());
        return result;
    }

    /** 帖子列表：按版块过滤、排序(latest/hot)、关键词搜索、分页 */
    public Map<String, Object> posts(Integer board, String sort, String q, int page, int pageSize) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("posts", forumRepository.listPosts(board, sort, q, page, pageSize));
        return result;
    }

    /** 发帖（可关联行程 + 结伴字段） */
    public Map<String, Object> createPost(long userId, String title, String content, int boardId, Long tripId,
                                          String destination, String travelDate) {
        Map<String, Object> result = new HashMap<>();
        if (title.isBlank() || content.isBlank()) {
            result.put("success", false);
            result.put("message", "标题和内容不能为空");
            return result;
        }
        if (overDailyLimit(userId, "posts")) {
            result.put("success", false);
            result.put("message", "今日发帖已达上限（" + POST_DAILY_LIMIT + " 篇/天）");
            return result;
        }
        //审核第一层：DFA 敏感词快筛，命中直接拒绝
        if (moderationService.containsSensitive(title + content)) {
            result.put("success", false);
            result.put("message", "内容含违规信息，请修改后重试");
            return result;
        }
        //关联行程时内嵌结构化 JSON（供详情页展示 + 同款复制）
        String planJson = null;
        if (tripId != null) {
            List<Map<String, Object>> trips = forumRepository.findTripPlanJson(tripId, userId);
            planJson = trips.isEmpty() ? null : (String) trips.get(0).get("plan_json");
        }
        long newId = forumRepository.insertPost(userId, boardId, title.trim(), content, tripId, planJson,
                destination == null || destination.isBlank() ? null : destination.trim(),
                travelDate == null || travelDate.isBlank() ? null : travelDate.trim());
        ProfileHook.trigger(userId); // 发帖影响风格标签
        //审核第二层：异步 LLM 语义审核（block隐藏/review进人工队列）
        if (newId > 0) {
            moderationService.moderateAsync(1, newId, title + "\n" + content);
        }
        result.put("success", true);
        result.put("message", "发帖成功");
        return result;
    }

    /** 帖子详情（浏览量 +1） */
    public Map<String, Object> postDetail(Long id) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = forumRepository.findPostDetail(id);
        if (rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "帖子不存在或已隐藏");
            return result;
        }
        forumRepository.incrementPostView(id);
        result.put("success", true);
        result.put("post", rows.get(0));
        return result;
    }

    /** 删除帖子（仅本人） */
    public Map<String, Object> deletePost(Long id, long userId) {
        int rows = forumRepository.deletePost(id, userId);
        if (rows > 0) {
            ProfileHook.trigger(userId); // 删帖后重算画像
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已删除" : "删除失败（无权删除）");
        return result;
    }

    //==================== 评论 ====================

    public Map<String, Object> comments(Long postId) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("comments", forumRepository.listComments(postId));
        return result;
    }

    /** 发表评论（两级楼中楼：parent_id 指向被回复评论） */
    public Map<String, Object> addComment(Long postId, long userId, String content, Long parentId, Long replyUserId) {
        Map<String, Object> result = new HashMap<>();
        if (content == null || content.isBlank()) {
            result.put("success", false);
            result.put("message", "评论内容不能为空");
            return result;
        }
        if (overDailyLimit(userId, "comments")) {
            result.put("success", false);
            result.put("message", "今日评论已达上限（" + COMMENT_DAILY_LIMIT + " 条/天）");
            return result;
        }
        //审核第一层：DFA 敏感词快筛
        if (moderationService.containsSensitive(content)) {
            result.put("success", false);
            result.put("message", "内容含违规信息，请修改后重试");
            return result;
        }
        Long postAuthor = forumRepository.findPostAuthor(postId); //取帖子作者，用于通知
        long newId = forumRepository.insertComment(postId, userId, parentId, replyUserId, content.trim());
        forumRepository.incrementCommentCount(postId);
        //审核第二层：异步 LLM 语义审核
        if (newId > 0) {
            moderationService.moderateAsync(2, newId, content);
        }
        //通知：不通知自己
        if (postAuthor != null && postAuthor != userId) {
            notify(postAuthor, 1, forumRepository.findAuthorName(userId) + " 评论了你的帖子", "/post.html?id=" + postId);
        }
        result.put("success", true);
        result.put("message", "评论成功");
        return result;
    }

    //==================== 点赞 / 收藏 ====================

    /** 点赞（切换：点赞<->取消） */
    public Map<String, Object> like(Long postId, long userId) {
        Map<String, Object> result = new HashMap<>();
        boolean exists = forumRepository.countLike(userId, postId) > 0;
        Long postAuthor = forumRepository.findPostAuthor(postId);
        if (exists) {
            forumRepository.unlike(userId, postId);
            result.put("liked", false);
        } else {
            forumRepository.like(userId, postId);
            if (postAuthor != null && postAuthor != userId) {
                notify(postAuthor, 2, forumRepository.findAuthorName(userId) + " 赞了你的帖子", "/post.html?id=" + postId);
            }
            result.put("liked", true);
        }
        result.put("success", true);
        return result;
    }

    /** 收藏（切换） */
    public Map<String, Object> favorite(Long postId, long userId) {
        Map<String, Object> result = new HashMap<>();
        if (forumRepository.countFavorite(userId, postId) > 0) {
            forumRepository.unfavorite(userId, postId);
            result.put("favorited", false);
        } else {
            forumRepository.favorite(userId, postId);
            result.put("favorited", true);
        }
        result.put("success", true);
        return result;
    }

    //==================== AI 答主 / 采纳 / 举报 ====================

    /** 请求 AI 答主回答（仅楼主，问答版块） */
    public Map<String, Object> aiAnswer(Long postId, long userId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> posts = forumRepository.findPostOwner(postId);
        if (posts.isEmpty()) {
            result.put("success", false);
            result.put("message", "帖子不存在");
            return result;
        }
        if (((Number) posts.get(0).get("user_id")).longValue() != userId) {
            result.put("success", false);
            result.put("message", "仅楼主可请求 AI 答主");
            return result;
        }
        if (forumRepository.countAiAnswersToday(userId) >= AI_ANSWER_DAILY_LIMIT) {
            result.put("success", false);
            result.put("message", "今日 AI 答主请求已达上限（" + AI_ANSWER_DAILY_LIMIT + " 次/天）");
            return result;
        }
        aiAnswerService.answerAsync(postId, userId);
        result.put("success", true);
        result.put("message", "AI 答主已受理，正在生成回答，稍后刷新查看");
        return result;
    }

    /** 采纳 AI 回答（仅楼主，采纳后标注「已采纳」） */
    public Map<String, Object> adopt(Long commentId, long userId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = forumRepository.findCommentForAdopt(commentId);
        if (rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("is_ai")).intValue() != 1) {
            result.put("success", false);
            result.put("message", "仅 AI 回答可采纳");
            return result;
        }
        if (((Number) row.get("user_id")).longValue() != userId) {
            result.put("success", false);
            result.put("message", "仅楼主可采纳");
            return result;
        }
        forumRepository.adoptComment(commentId);
        result.put("success", true);
        result.put("message", "已采纳该 AI 回答");
        return result;
    }

    /** 举报帖子/评论（进管理员审核队列） */
    public Map<String, Object> report(long userId, int targetType, Long targetId, String reason) {
        Map<String, Object> result = new HashMap<>();
        if (reason == null || reason.isBlank()) {
            result.put("success", false);
            result.put("message", "请填写举报理由");
            return result;
        }
        forumRepository.insertReport(userId, targetType, targetId, reason.trim());
        result.put("success", true);
        result.put("message", "举报已提交，管理员将尽快处理");
        return result;
    }

    //==================== 同款行程复制 ====================

    /** 复制帖子内嵌行程到自己账户 */
    public Map<String, Object> copyTrip(Long postId, long userId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> posts = forumRepository.findPostTrip(postId);
        if (posts.isEmpty() || posts.get(0).get("trip_plan_json") == null) {
            result.put("success", false);
            result.put("message", "该帖子未内嵌行程");
            return result;
        }
        Map<String, Object> post = posts.get(0);
        String planJson = (String) post.get("trip_plan_json");
        //从结构化 JSON 提取目的地/天数（posts.destination 结伴字段优先）
        String destination = post.get("destination") == null ? "" : String.valueOf(post.get("destination"));
        int days = 0;
        try {
            Map<String, Object> plan = new com.fasterxml.jackson.databind.ObjectMapper().readValue(planJson, Map.class);
            if (destination.isBlank() && plan.get("destination") != null) {
                destination = String.valueOf(plan.get("destination"));
            }
            if (plan.get("days") != null) {
                days = ((Number) plan.get("days")).intValue();
            }
        } catch (Exception ignored) { /* JSON 解析失败用默认值 */ }
        String markdown = "# 同款行程\n\n" + planJson; // 简化 markdown 兜底（含 JSON 原文）
        forumRepository.insertCopiedTrip(userId, destination.isBlank() ? null : destination, days, markdown, planJson);
        result.put("success", true);
        result.put("message", "同款行程已复制到「我的行程」");
        return result;
    }

    //==================== 我的内容 ====================

    public Map<String, Object> mine(long userId, String type) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        switch (type) {
            case "comment" -> result.put("items", forumRepository.listMyComments(userId));
            case "favorite" -> result.put("items", forumRepository.listMyFavorites(userId));
            default -> result.put("items", forumRepository.listMyPosts(userId));
        }
        return result;
    }
}
