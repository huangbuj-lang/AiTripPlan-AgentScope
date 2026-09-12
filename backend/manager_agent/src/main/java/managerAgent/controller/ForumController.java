package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.AiAnswerService;
import managerAgent.service.ModerationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 论坛接口（P1）：三版块 / 发帖 / 评论(两级楼中楼) / 点赞收藏 / 搜索 / 同款行程复制 / 业务日配额。 */
@RestController
@RequestMapping("/api/forum")
public class ForumController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ModerationService moderationService;

    @Resource
    private AiAnswerService aiAnswerService;

    private static final int POST_DAILY_LIMIT = 5;      // 发帖日配额
    private static final int COMMENT_DAILY_LIMIT = 30;  // 评论日配额
    private static final int AI_ANSWER_DAILY_LIMIT = 3; // AI 答主请求日配额

    private long uid(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    /** 作者展示名：优先 user_profiles.nickname，否则 username */
    private String authorName(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(p.nickname, u.username) AS name FROM users u " +
                "LEFT JOIN user_profiles p ON p.user_id = u.id WHERE u.id = ?", userId);
        return rows.isEmpty() ? "用户" : String.valueOf(rows.get(0).get("name"));
    }

    /** 业务日配额校验：当天该操作是否超限 */
    private boolean overDailyLimit(long userId, String table) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ? AND DATE(created_at) = CURDATE()",
                Integer.class, userId);
        int limit = "posts".equals(table) ? POST_DAILY_LIMIT : COMMENT_DAILY_LIMIT;
        return cnt != null && cnt >= limit;
    }

    /** 写入一条通知（被评论/点赞/收藏时通知帖子作者），并实时 SSE 推送 */
    private void notify(long toUserId, int type, String content, String linkUrl) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO notifications(user_id, type, content, link_url) VALUES (?, ?, ?, ?)",
                    toUserId, type, content, linkUrl);
            NotificationHub.push(toUserId, "{\"type\":" + type + ",\"content\":\"" +
                    content.replace("\"", "\\\"") + "\",\"link\":\"" +
                    (linkUrl == null ? "" : linkUrl) + "\"}");
        } catch (Exception ignored) { /* 通知失败不阻塞主流程 */ }
    }

    //==================== 版块 ====================

    @GetMapping("/boards")
    public Map<String, Object> boards() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("boards", jdbcTemplate.queryForList(
                "SELECT * FROM forum_boards ORDER BY sort_order"));
        return result;
    }

    //==================== 帖子 ====================

    /** 帖子列表：按版块过滤、排序(latest/hot)、关键词搜索、分页 */
    @GetMapping("/posts")
    public Map<String, Object> posts(@RequestParam(required = false) Integer board,
                                     @RequestParam(required = false, defaultValue = "latest") String sort,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     @RequestParam(required = false, defaultValue = "10") int pageSize,
                                     HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        StringBuilder sql = new StringBuilder(
                "SELECT po.id, po.title, po.board_id, po.destination, po.travel_date, " +
                "po.like_count, po.comment_count, po.view_count, po.created_at, po.user_id, " +
                "(po.trip_plan_json IS NOT NULL OR po.trip_id IS NOT NULL) AS has_trip, " +
                "COALESCE(pr.nickname, u.username) AS author, b.name AS board_name " +
                "FROM posts po JOIN users u ON u.id = po.user_id " +
                "LEFT JOIN user_profiles pr ON pr.user_id = po.user_id " +
                "JOIN forum_boards b ON b.id = po.board_id WHERE po.status = 1");
        List<Object> args = new ArrayList<>();
        if (board != null) {
            sql.append(" AND po.board_id = ?");
            args.add(board);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (po.title LIKE ? OR po.content LIKE ?)");
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        sql.append(" ORDER BY ").append("hot".equals(sort) ? "po.like_count DESC, po.comment_count DESC" : "po.created_at DESC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(pageSize);
        args.add(page * pageSize);

        result.put("success", true);
        result.put("posts", jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        return result;
    }

    /** 发帖（可关联行程 + 结伴字段） */
    @PostMapping("/posts")
    public Map<String, Object> createPost(@RequestParam String title,
                                          @RequestParam String content,
                                          @RequestParam int boardId,
                                          @RequestParam(required = false) Long tripId,
                                          @RequestParam(required = false) String destination,
                                          @RequestParam(required = false) String travelDate,
                                          HttpSession session) {
        long userId = uid(session);
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
        final String planJson;
        if (tripId != null) {
            List<Map<String, Object>> trips = jdbcTemplate.queryForList(
                    "SELECT plan_json FROM trip_plans WHERE id = ? AND user_id = ?", tripId, userId);
            planJson = trips.isEmpty() ? null : (String) trips.get(0).get("plan_json");
        } else {
            planJson = null;
        }
        org.springframework.jdbc.core.PreparedStatementCreator psc = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO posts(user_id, board_id, title, content, trip_id, trip_plan_json, destination, travel_date) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
        managerAgent.tool.ProfileHook.trigger(userId); // 发帖影响风格标签
            ps.setLong(1, userId);
            ps.setInt(2, boardId);
            ps.setString(3, title.trim());
            ps.setString(4, content);
            ps.setObject(5, tripId);
            ps.setString(6, planJson);
            ps.setString(7, destination == null || destination.isBlank() ? null : destination.trim());
            ps.setString(8, travelDate == null || travelDate.isBlank() ? null : travelDate.trim());
            return ps;
        };
        org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(psc, keyHolder);
        Number newId = keyHolder.getKey();
        //审核第二层：异步 LLM 语义审核（block隐藏/review进人工队列）
        if (newId != null) {
            moderationService.moderateAsync(1, newId.longValue(), title + "\n" + content);
        }
        result.put("success", true);
        result.put("message", "发帖成功");
        return result;
    }

    /** 帖子详情（浏览量 +1） */
    @GetMapping("/posts/{id}")
    public Map<String, Object> postDetail(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT po.*, COALESCE(pr.nickname, u.username) AS author " +
                "FROM posts po JOIN users u ON u.id = po.user_id " +
                "LEFT JOIN user_profiles pr ON pr.user_id = po.user_id WHERE po.id = ? AND po.status = 1", id);
        if (rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "帖子不存在或已隐藏");
            return result;
        }
        jdbcTemplate.update("UPDATE posts SET view_count = view_count + 1 WHERE id = ?", id);
        result.put("success", true);
        result.put("post", rows.get(0));
        return result;
    }

    /** 删除帖子（仅本人） */
    @DeleteMapping("/posts/{id}")
    public Map<String, Object> deletePost(@PathVariable Long id, HttpSession session) {
        long userId = uid(session);
        int rows = jdbcTemplate.update("DELETE FROM posts WHERE id = ? AND user_id = ?", id, userId);
        if (rows > 0) { managerAgent.tool.ProfileHook.trigger(userId); } // 删帖后重算画像
        Map<String, Object> result = new HashMap<>();
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已删除" : "删除失败（无权删除）");
        return result;
    }

    //==================== 评论 ====================

    @GetMapping("/posts/{id}/comments")
    public Map<String, Object> comments(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("comments", jdbcTemplate.queryForList(
                "SELECT c.id, c.content, c.created_at, c.parent_id, c.reply_user_id, c.user_id, " +
                "c.is_ai, c.is_adopted, " +
                "COALESCE(pr.nickname, u.username) AS author, " +
                "COALESCE(pr2.nickname, u2.username) AS reply_author " +
                "FROM comments c LEFT JOIN users u ON u.id = c.user_id " +
                "LEFT JOIN user_profiles pr ON pr.user_id = c.user_id " +
                "LEFT JOIN users u2 ON u2.id = c.reply_user_id " +
                "LEFT JOIN user_profiles pr2 ON pr2.user_id = c.reply_user_id " +
                "WHERE c.post_id = ? AND c.status = 1 ORDER BY c.created_at ASC", id));
        return result;
    }

    /** 发表评论（两级楼中楼：parent_id 指向被回复评论） */
    @PostMapping("/posts/{id}/comments")
    public Map<String, Object> addComment(@PathVariable Long id,
                                          @RequestParam String content,
                                          @RequestParam(required = false) Long parentId,
                                          @RequestParam(required = false) Long replyUserId,
                                          HttpSession session) {
        long userId = uid(session);
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
        //取帖子作者，用于通知
        Long postAuthor = jdbcTemplate.queryForObject(
                "SELECT user_id FROM posts WHERE id = ?", Long.class, id);
        org.springframework.jdbc.core.PreparedStatementCreator psc = con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO comments(post_id, user_id, parent_id, reply_user_id, content) VALUES (?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, id);
            ps.setLong(2, userId);
            ps.setObject(3, parentId);
            ps.setObject(4, replyUserId);
            ps.setString(5, content.trim());
            return ps;
        };
        org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(psc, keyHolder);
        jdbcTemplate.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?", id);
        //审核第二层：异步 LLM 语义审核
        Number newId = keyHolder.getKey();
        if (newId != null) {
            moderationService.moderateAsync(2, newId.longValue(), content);
        }
        //通知：不通知自己
        if (postAuthor != null && postAuthor != userId) {
            notify(postAuthor, 1, authorName(userId) + " 评论了你的帖子", "/post.html?id=" + id);
        }
        result.put("success", true);
        result.put("message", "评论成功");
        return result;
    }

    //==================== 点赞 / 收藏 ====================

    /** 点赞（切换：点赞<->取消） */
    @PostMapping("/posts/{id}/like")
    public Map<String, Object> like(@PathVariable Long id, HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_likes WHERE user_id = ? AND post_id = ?", Integer.class, userId, id);
        Long postAuthor = jdbcTemplate.queryForObject("SELECT user_id FROM posts WHERE id = ?", Long.class, id);
        if (exists != null && exists > 0) {
            jdbcTemplate.update("DELETE FROM post_likes WHERE user_id = ? AND post_id = ?", userId, id);
            jdbcTemplate.update("UPDATE posts SET like_count = GREATEST(like_count - 1, 0) WHERE id = ?", id);
            result.put("liked", false);
        } else {
            jdbcTemplate.update("INSERT IGNORE INTO post_likes(user_id, post_id) VALUES (?, ?)", userId, id);
            jdbcTemplate.update("UPDATE posts SET like_count = like_count + 1 WHERE id = ?", id);
            if (postAuthor != null && postAuthor != userId) {
                notify(postAuthor, 2, authorName(userId) + " 赞了你的帖子", "/post.html?id=" + id);
            }
            result.put("liked", true);
        }
        result.put("success", true);
        return result;
    }

    /** 收藏（切换） */
    @PostMapping("/posts/{id}/favorite")
    public Map<String, Object> favorite(@PathVariable Long id, HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_favorites WHERE user_id = ? AND post_id = ?", Integer.class, userId, id);
        if (exists != null && exists > 0) {
            jdbcTemplate.update("DELETE FROM post_favorites WHERE user_id = ? AND post_id = ?", userId, id);
            result.put("favorited", false);
        } else {
            jdbcTemplate.update("INSERT IGNORE INTO post_favorites(user_id, post_id) VALUES (?, ?)", userId, id);
            result.put("favorited", true);
        }
        result.put("success", true);
        return result;
    }

    //==================== AI 答主 / 采纳 / 举报 ====================

    /** 请求 AI 答主回答（仅楼主，问答版块； */
    @PostMapping("/answers/ai")
    public Map<String, Object> aiAnswer(@RequestParam Long postId, HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> posts = jdbcTemplate.queryForList(
                "SELECT user_id FROM posts WHERE id = ? AND status = 1", postId);
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
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id " +
                "WHERE p.user_id = ? AND c.is_ai = 1 AND DATE(c.created_at) = CURDATE()",
                Integer.class, userId);
        if (cnt != null && cnt >= AI_ANSWER_DAILY_LIMIT) {
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
    @PostMapping("/comments/{id}/adopt")
    public Map<String, Object> adopt(@PathVariable Long id, HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.post_id, c.is_ai, p.user_id FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.id = ?", id);
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
        jdbcTemplate.update("UPDATE comments SET is_adopted = 1 WHERE id = ?", id);
        result.put("success", true);
        result.put("message", "已采纳该 AI 回答");
        return result;
    }

    /** 举报帖子/评论（进管理员审核队列） */
    @PostMapping("/reports")
    public Map<String, Object> report(@RequestParam int targetType,
                                      @RequestParam Long targetId,
                                      @RequestParam String reason,
                                      HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        if (reason == null || reason.isBlank()) {
            result.put("success", false);
            result.put("message", "请填写举报理由");
            return result;
        }
        jdbcTemplate.update(
                "INSERT INTO reports(reporter_id, target_type, target_id, reason) VALUES (?, ?, ?, ?)",
                userId, targetType, targetId, reason.trim());
        result.put("success", true);
        result.put("message", "举报已提交，管理员将尽快处理");
        return result;
    }

    //==================== 同款行程复制 ====================

    /** 复制帖子内嵌行程到自己账户 */
    @PostMapping("/posts/{id}/copy-trip")
    public Map<String, Object> copyTrip(@PathVariable Long id, HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> posts = jdbcTemplate.queryForList(
                "SELECT trip_plan_json, destination, title FROM posts WHERE id = ?", id);
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
        jdbcTemplate.update(
                "INSERT INTO trip_plans(user_id, city, days, plan_content, plan_json) VALUES (?, ?, ?, ?, ?)",
                userId, destination.isBlank() ? null : destination, days, markdown, planJson);
        result.put("success", true);
        result.put("message", "同款行程已复制到「我的行程」");
        return result;
    }

    //==================== 我的内容 ====================

    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestParam(required = false, defaultValue = "post") String type,
                                    HttpSession session) {
        long userId = uid(session);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        switch (type) {
            case "comment" -> result.put("items", jdbcTemplate.queryForList(
                    "SELECT c.id, c.content, c.created_at, p.title AS post_title, p.id AS post_id " +
                    "FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.user_id = ? ORDER BY c.created_at DESC", userId));
            case "favorite" -> result.put("items", jdbcTemplate.queryForList(
                    "SELECT f.post_id, f.created_at, p.title, COALESCE(pr.nickname, u.username) AS author " +
                    "FROM post_favorites f JOIN posts p ON p.id = f.post_id " +
                    "JOIN users u ON u.id = p.user_id LEFT JOIN user_profiles pr ON pr.user_id = p.user_id " +
                    "WHERE f.user_id = ? ORDER BY f.created_at DESC", userId));
            default -> result.put("items", jdbcTemplate.queryForList(
                    "SELECT id, title, board_id, like_count, comment_count, view_count, created_at FROM posts " +
                    "WHERE user_id = ? ORDER BY created_at DESC", userId));
        }
        return result;
    }
}
