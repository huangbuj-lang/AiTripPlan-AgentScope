package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 通知中心的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class NotificationRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 某用户的通知列表（分页，新 -> 旧） */
    public List<Map<String, Object>> listByUser(long userId, int page, int pageSize) {
        return jdbcTemplate.queryForList(
                "SELECT id, type, content, link_url, is_read, created_at FROM notifications " +
                "WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                userId, pageSize, page * pageSize);
    }

    /** 某用户的未读数量 */
    public Integer countUnread(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0", Integer.class, userId);
    }

    /** 全部标记为已读，返回影响行数 */
    public int markAllRead(long userId) {
        return jdbcTemplate.update("UPDATE notifications SET is_read = 1 WHERE user_id = ?", userId);
    }

    /** 写入一条通知（论坛被评论/点赞等场景） */
    public int insert(long userId, int type, String content, String linkUrl) {
        return jdbcTemplate.update(
                "INSERT INTO notifications(user_id, type, content, link_url) VALUES (?, ?, ?, ?)",
                userId, type, content, linkUrl);
    }
}
