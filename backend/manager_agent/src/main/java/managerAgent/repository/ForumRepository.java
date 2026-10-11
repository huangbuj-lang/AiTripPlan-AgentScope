package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 论坛（版块 / 帖子 / 评论 / 点赞 / 收藏 / 举报）的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class ForumRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 版块列表 */
    public List<Map<String, Object>> listBoards() {
        return jdbcTemplate.queryForList("SELECT * FROM forum_boards ORDER BY sort_order");
    }

    /** 帖子列表：按版块过滤、排序(latest/hot)、关键词搜索、分页 */
    public List<Map<String, Object>> listPosts(Integer board, String sort, String q, int page, int pageSize) {
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
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 关联行程时取内嵌的结构化 JSON（仅本人行程） */
    public List<Map<String, Object>> findTripPlanJson(Long tripId, long userId) {
        return jdbcTemplate.queryForList(
                "SELECT plan_json FROM trip_plans WHERE id = ? AND user_id = ?", tripId, userId);
    }

    /** 发帖，返回自增 id（-1 = 取不到） */
    public long insertPost(long userId, int boardId, String title, String content, Long tripId, String planJson,
                           String destination, String travelDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO posts(user_id, board_id, title, content, trip_id, trip_plan_json, destination, travel_date) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setInt(2, boardId);
            ps.setString(3, title);
            ps.setString(4, content);
            ps.setObject(5, tripId);
            ps.setString(6, planJson);
            ps.setString(7, destination);
            ps.setString(8, travelDate);
            return ps;
        }, keyHolder);
        Number newId = keyHolder.getKey();
        return newId == null ? -1L : newId.longValue();
    }

    /** 帖子详情（含作者展示名） */
    public List<Map<String, Object>> findPostDetail(Long id) {
        return jdbcTemplate.queryForList(
                "SELECT po.*, COALESCE(pr.nickname, u.username) AS author " +
                "FROM posts po JOIN users u ON u.id = po.user_id " +
                "LEFT JOIN user_profiles pr ON pr.user_id = po.user_id WHERE po.id = ? AND po.status = 1", id);
    }

    /** 浏览量 +1 */
    public int incrementPostView(Long id) {
        return jdbcTemplate.update("UPDATE posts SET view_count = view_count + 1 WHERE id = ?", id);
    }

    /** 删除帖子（仅本人），返回影响行数 */
    public int deletePost(Long id, long userId) {
        return jdbcTemplate.update("DELETE FROM posts WHERE id = ? AND user_id = ?", id, userId);
    }

    /** 帖子评论（两级楼中楼，含回复对象展示名） */
    public List<Map<String, Object>> listComments(Long postId) {
        return jdbcTemplate.queryForList(
                "SELECT c.id, c.content, c.created_at, c.parent_id, c.reply_user_id, c.user_id, " +
                "c.is_ai, c.is_adopted, " +
                "COALESCE(pr.nickname, u.username) AS author, " +
                "COALESCE(pr2.nickname, u2.username) AS reply_author " +
                "FROM comments c LEFT JOIN users u ON u.id = c.user_id " +
                "LEFT JOIN user_profiles pr ON pr.user_id = c.user_id " +
                "LEFT JOIN users u2 ON u2.id = c.reply_user_id " +
                "LEFT JOIN user_profiles pr2 ON pr2.user_id = c.reply_user_id " +
                "WHERE c.post_id = ? AND c.status = 1 ORDER BY c.created_at ASC", postId);
    }

    /** 帖子作者 id（通知用） */
    public Long findPostAuthor(Long postId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM posts WHERE id = ?", Long.class, postId);
    }

    /** 发表评论，返回自增 id（-1 = 取不到） */
    public long insertComment(Long postId, long userId, Long parentId, Long replyUserId, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO comments(post_id, user_id, parent_id, reply_user_id, content) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setLong(2, userId);
            ps.setObject(3, parentId);
            ps.setObject(4, replyUserId);
            ps.setString(5, content);
            return ps;
        }, keyHolder);
        Number newId = keyHolder.getKey();
        return newId == null ? -1L : newId.longValue();
    }

    /** 帖子评论数 +1 */
    public int incrementCommentCount(Long postId) {
        return jdbcTemplate.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?", postId);
    }

    /** 作者展示名：优先 user_profiles.nickname，否则 username */
    public String findAuthorName(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(p.nickname, u.username) AS name FROM users u " +
                "LEFT JOIN user_profiles p ON p.user_id = u.id WHERE u.id = ?", userId);
        return rows.isEmpty() ? "用户" : String.valueOf(rows.get(0).get("name"));
    }

    /** 当天该表该用户的行数（业务日配额用；表名只允许 posts / comments） */
    public int countTodayByTable(String table, long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ? AND DATE(created_at) = CURDATE()",
                Integer.class, userId);
        return cnt == null ? 0 : cnt;
    }

    /** 今天该用户请求的 AI 回答数 */
    public int countAiAnswersToday(long userId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id " +
                "WHERE p.user_id = ? AND c.is_ai = 1 AND DATE(c.created_at) = CURDATE()",
                Integer.class, userId);
        return cnt == null ? 0 : cnt;
    }

    /** 帖子归属（仅状态正常的帖子） */
    public List<Map<String, Object>> findPostOwner(Long postId) {
        return jdbcTemplate.queryForList("SELECT user_id FROM posts WHERE id = ? AND status = 1", postId);
    }

    /** 点赞记录数 */
    public int countLike(long userId, Long postId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_likes WHERE user_id = ? AND post_id = ?", Integer.class, userId, postId);
        return cnt == null ? 0 : cnt;
    }

    /** 取消点赞 + 计数 -1（不小于 0） */
    public void unlike(long userId, Long postId) {
        jdbcTemplate.update("DELETE FROM post_likes WHERE user_id = ? AND post_id = ?", userId, postId);
        jdbcTemplate.update("UPDATE posts SET like_count = GREATEST(like_count - 1, 0) WHERE id = ?", postId);
    }

    /** 点赞 + 计数 +1 */
    public void like(long userId, Long postId) {
        jdbcTemplate.update("INSERT IGNORE INTO post_likes(user_id, post_id) VALUES (?, ?)", userId, postId);
        jdbcTemplate.update("UPDATE posts SET like_count = like_count + 1 WHERE id = ?", postId);
    }

    /** 收藏记录数 */
    public int countFavorite(long userId, Long postId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_favorites WHERE user_id = ? AND post_id = ?", Integer.class, userId, postId);
        return cnt == null ? 0 : cnt;
    }

    /** 取消收藏 / 收藏 */
    public void unfavorite(long userId, Long postId) {
        jdbcTemplate.update("DELETE FROM post_favorites WHERE user_id = ? AND post_id = ?", userId, postId);
    }

    public void favorite(long userId, Long postId) {
        jdbcTemplate.update("INSERT IGNORE INTO post_favorites(user_id, post_id) VALUES (?, ?)", userId, postId);
    }

    /** 采纳记录（含评论是否 AI 回答、帖子归属） */
    public List<Map<String, Object>> findCommentForAdopt(Long commentId) {
        return jdbcTemplate.queryForList(
                "SELECT c.post_id, c.is_ai, p.user_id FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.id = ?", commentId);
    }

    /** 标记采纳 */
    public int adoptComment(Long commentId) {
        return jdbcTemplate.update("UPDATE comments SET is_adopted = 1 WHERE id = ?", commentId);
    }

    /** 举报入库 */
    public int insertReport(long userId, int targetType, Long targetId, String reason) {
        return jdbcTemplate.update(
                "INSERT INTO reports(reporter_id, target_type, target_id, reason) VALUES (?, ?, ?, ?)",
                userId, targetType, targetId, reason);
    }

    /** 帖子内嵌行程（同款复制用） */
    public List<Map<String, Object>> findPostTrip(Long postId) {
        return jdbcTemplate.queryForList(
                "SELECT trip_plan_json, destination, title FROM posts WHERE id = ?", postId);
    }

    /** 把帖子内嵌行程复制到自己账户 */
    public int insertCopiedTrip(long userId, String destination, int days, String markdown, String planJson) {
        return jdbcTemplate.update(
                "INSERT INTO trip_plans(user_id, city, days, plan_content, plan_json) VALUES (?, ?, ?, ?, ?)",
                userId, destination, days, markdown, planJson);
    }

    /** 我的评论 */
    public List<Map<String, Object>> listMyComments(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT c.id, c.content, c.created_at, p.title AS post_title, p.id AS post_id " +
                "FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.user_id = ? ORDER BY c.created_at DESC", userId);
    }

    /** 我的收藏 */
    public List<Map<String, Object>> listMyFavorites(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT f.post_id, f.created_at, p.title, COALESCE(pr.nickname, u.username) AS author " +
                "FROM post_favorites f JOIN posts p ON p.id = f.post_id " +
                "JOIN users u ON u.id = p.user_id LEFT JOIN user_profiles pr ON pr.user_id = p.user_id " +
                "WHERE f.user_id = ? ORDER BY f.created_at DESC", userId);
    }

    /** 我的帖子 */
    public List<Map<String, Object>> listMyPosts(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, title, board_id, like_count, comment_count, view_count, created_at FROM posts " +
                "WHERE user_id = ? ORDER BY created_at DESC", userId);
    }
}
