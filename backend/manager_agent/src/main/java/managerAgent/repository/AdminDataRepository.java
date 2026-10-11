package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 管理端「数据管理 / 审核 / 看板 / 数据中心」的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class AdminDataRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    //==================== 用户与行程 ====================

    /** 用户列表（含角色） */
    public List<Map<String, Object>> listUsers() {
        return jdbcTemplate.queryForList("SELECT id, username, role FROM users ORDER BY id");
    }

    /** 删除用户及其名下全部数据，返回 users 表删除行数 */
    public int deleteUserAndOwned(Long id) {
        jdbcTemplate.update("DELETE FROM trip_plans WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM posts WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM post_likes WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM user_preferences WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM user_profiles_tags WHERE user_id = ?", id); // 同步清理画像标签
        return jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
    }

    /** 行程列表（关联用户名，内容预览 200 字） */
    public List<Map<String, Object>> listPlans() {
        return jdbcTemplate.queryForList(
                "SELECT p.id, p.user_id, u.username, p.city, p.days, " +
                "LEFT(p.plan_content, 200) AS preview, p.create_time " +
                "FROM trip_plans p LEFT JOIN users u ON p.user_id = u.id ORDER BY p.id DESC");
    }

    /** 标准城市库（用于城市名归一化） */
    public List<String> listStdCities() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT city FROM attractions WHERE city IS NOT NULL AND city != ''", String.class);
    }

    /** 删除指定行程 */
    public int deletePlan(Long id) {
        return jdbcTemplate.update("DELETE FROM trip_plans WHERE id = ?", id);
    }

    //==================== 内容审核 ====================

    /** 待处理举报 */
    public List<Map<String, Object>> listPendingReports() {
        return jdbcTemplate.queryForList(
                "SELECT r.id, r.reporter_id, u.username AS reporter, r.target_type, r.target_id, " +
                "r.reason, r.status, r.created_at FROM reports r LEFT JOIN users u ON u.id = r.reporter_id " +
                "WHERE r.status = 0 ORDER BY r.created_at DESC LIMIT 100");
    }

    /** AI 审核命中记录 */
    public List<Map<String, Object>> listModerationRecords() {
        return jdbcTemplate.queryForList(
                "SELECT m.id, m.target_type, m.target_id, m.verdict, m.reason, m.model, m.created_at " +
                "FROM moderation_records m WHERE m.verdict > 0 ORDER BY m.created_at DESC LIMIT 100");
    }

    /** 被隐藏的帖子 */
    public List<Map<String, Object>> listHiddenPosts() {
        return jdbcTemplate.queryForList(
                "SELECT p.id, p.title, p.user_id, u.username, p.created_at FROM posts p " +
                "LEFT JOIN users u ON u.id = p.user_id WHERE p.status = 0 ORDER BY p.created_at DESC LIMIT 100");
    }

    /** 被隐藏的评论 */
    public List<Map<String, Object>> listHiddenComments() {
        return jdbcTemplate.queryForList(
                "SELECT c.id, c.content, c.post_id, c.user_id, u.username, c.created_at FROM comments c " +
                "LEFT JOIN users u ON u.id = c.user_id WHERE c.status = 0 ORDER BY c.created_at DESC LIMIT 100");
    }

    /** 举报标记为已处理 */
    public int resolveReport(Long id) {
        return jdbcTemplate.update("UPDATE reports SET status = 1 WHERE id = ?", id);
    }

    /** 隐藏/恢复内容（table 只允许 posts / comments） */
    public int updateContentStatus(String table, Long targetId, int status) {
        return jdbcTemplate.update("UPDATE " + table + " SET status = ? WHERE id = ?", status, targetId);
    }

    //==================== 业务看板 ====================

    public int countUsers() {
        return intQuery("SELECT COUNT(*) FROM users");
    }

    public int countPlans() {
        return intQuery("SELECT COUNT(*) FROM trip_plans");
    }

    public int countTodayPlans() {
        return intQuery("SELECT COUNT(*) FROM trip_plans WHERE DATE(create_time) = CURDATE()");
    }

    public int countPosts() {
        return intQuery("SELECT COUNT(*) FROM posts WHERE status = 1");
    }

    public int countTodayPosts() {
        return intQuery("SELECT COUNT(*) FROM posts WHERE DATE(created_at) = CURDATE()");
    }

    /** 日活：当天有行程或有发帖的去重用户数 */
    public int countDau() {
        return intQuery("SELECT COUNT(DISTINCT user_id) FROM (" +
                "SELECT user_id FROM trip_plans WHERE DATE(create_time) = CURDATE() " +
                "UNION SELECT user_id FROM posts WHERE DATE(created_at) = CURDATE()) t");
    }

    /** 热门目的地 Top10 */
    public List<Map<String, Object>> listHotDestinations() {
        return jdbcTemplate.queryForList(
                "SELECT city AS destination, COUNT(*) AS cnt FROM trip_plans " +
                "WHERE city IS NOT NULL AND city != '' " +
                "GROUP BY city ORDER BY cnt DESC LIMIT 10");
    }

    //==================== 公告 ====================

    public List<Map<String, Object>> listAnnouncements() {
        return jdbcTemplate.queryForList(
                "SELECT a.id, a.title, a.content, a.created_by, u.username, a.created_at " +
                "FROM announcements a LEFT JOIN users u ON u.id = a.created_by ORDER BY a.created_at DESC LIMIT 50");
    }

    public int insertAnnouncement(String title, String content, long adminId) {
        return jdbcTemplate.update(
                "INSERT INTO announcements(title, content, created_by) VALUES (?, ?, ?)", title, content, adminId);
    }

    /** 全部正常状态用户 id（公告全员推送用） */
    public List<Long> listActiveUserIds() {
        return jdbcTemplate.queryForList("SELECT id FROM users WHERE status = 1", Long.class);
    }

    //==================== 数据中心 ====================

    public List<Map<String, Object>> listAttractionsDetail() {
        return jdbcTemplate.queryForList(
                "SELECT a.id AS id, a.name AS name, a.city AS city, a.rating AS rating, "
              + "COALESCE(NULLIF(af.ticket_price, ''), NULLIF(a.cost, '')) AS cost, a.address AS address "
              + "FROM attractions a LEFT JOIN attractions_features af ON af.src_id = a.id "
              + "ORDER BY a.city, a.rating DESC LIMIT 500");
    }

    public List<Map<String, Object>> listFoodsDetail() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, city, rating, cost, address FROM foods ORDER BY city, rating DESC LIMIT 500");
    }

    public List<Map<String, Object>> listHotelsDetail() {
        return jdbcTemplate.queryForList(
                "SELECT h.id AS id, h.name AS name, h.city AS city, h.rating AS rating, "
              + "COALESCE(NULLIF(hf.cost, ''), NULLIF(h.cost, '')) AS cost, h.address AS address "
              + "FROM hotels h LEFT JOIN hotels_features hf ON hf.src_id = h.id "
              + "ORDER BY h.city, h.rating DESC LIMIT 500");
    }

    public List<Map<String, Object>> listCitiesDetail() {
        return jdbcTemplate.queryForList(
                "SELECT a.city AS city, "
              + "(SELECT COUNT(*) FROM attractions x WHERE x.city=a.city) AS attractions, "
              + "(SELECT COUNT(*) FROM foods f WHERE f.city=a.city) AS foods, "
              + "(SELECT COUNT(*) FROM hotels h WHERE h.city=a.city) AS hotels "
              + "FROM (SELECT DISTINCT city FROM attractions) a ORDER BY a.city LIMIT 200");
    }

    public List<Map<String, Object>> listProfilesDetail() {
        return jdbcTemplate.queryForList(
                "SELECT IFNULL(u.username,'-') AS username, t.user_id, t.tags, t.updated_at " +
                "FROM user_profiles_tags t LEFT JOIN users u ON u.id = t.user_id ORDER BY t.updated_at DESC LIMIT 200");
    }

    /** 明细主表字段更新（表名与列名都经业务层白名单校验） */
    public int updateMainField(String table, String column, String value, Long id) {
        return jdbcTemplate.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", value, id);
    }

    /** 明细特征表费用同步（attractions_features / foods_features / hotels_features） */
    public int updateFeatureCost(String type, String value, Long id) {
        String sql = switch (type) {
            case "attractions" -> "UPDATE attractions_features SET ticket_price = ? WHERE src_id = ?";
            case "foods" -> "UPDATE foods_features SET cost = ? WHERE src_id = ?";
            default -> "UPDATE hotels_features SET cost = ? WHERE src_id = ?";
        };
        return jdbcTemplate.update(sql, value, id);
    }

    //==================== 管线结果查询 ====================

    /** 各表行数（表不存在按 0 处理） */
    public int countSafe(String sql) {
        try {
            Integer v = jdbcTemplate.queryForObject(sql, Integer.class);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 表存在性容错查询：表不存在返回空列表 */
    public List<Map<String, Object>> querySafe(String sql) {
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    public List<Map<String, Object>> listPricing() {
        return querySafe("SELECT city, hotel_base, hotel_price, food_base, food_price, ticket_base, ticket_price, factors FROM pricing_output ORDER BY hotel_price DESC");
    }

    public List<Map<String, Object>> listForecast() {
        return querySafe("SELECT city, day, forecast_idx FROM forecast_output ORDER BY day, city");
    }

    public List<Map<String, Object>> listProfileTags() {
        return querySafe("SELECT t.user_id, u.username, t.tags FROM user_profiles_tags t LEFT JOIN users u ON u.id = t.user_id");
    }

    private int intQuery(String sql) {
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }
}
