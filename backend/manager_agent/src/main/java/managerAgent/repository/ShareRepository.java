package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 行程分享（trip_shares / trip_plans）的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class ShareRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 该行程是否属于该用户（1=是） */
    public int countTripOwned(Long tripId, long uid) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_plans WHERE id = ? AND user_id = ?", Integer.class, tripId, uid);
        return cnt == null ? 0 : cnt;
    }

    /** N 天后的时间（用于分享有效期） */
    public String expiresAtInDays(int validDays) {
        return jdbcTemplate.queryForObject("SELECT DATE_ADD(NOW(), INTERVAL ? DAY)", String.class, validDays);
    }

    /** 创建/刷新分享（uk_trip 唯一，重复分享刷新 code） */
    public int upsertShare(Long tripId, long uid, String code, String pwdHash, String expiresAt) {
        return jdbcTemplate.update(
                "INSERT INTO trip_shares(trip_id, user_id, share_code, password_hash, is_public, expires_at) " +
                "VALUES (?, ?, ?, ?, 1, ?) " +
                "ON DUPLICATE KEY UPDATE share_code=VALUES(share_code), password_hash=VALUES(password_hash), " +
                "is_public=1, expires_at=VALUES(expires_at)",
                tripId, uid, code, pwdHash, expiresAt);
    }

    /** 撤销分享，返回影响行数 */
    public int deleteShare(Long tripId, long uid) {
        return jdbcTemplate.update("DELETE FROM trip_shares WHERE trip_id = ? AND user_id = ?", tripId, uid);
    }

    /** 按分享码查分享记录 */
    public List<Map<String, Object>> findByCode(String code) {
        return jdbcTemplate.queryForList("SELECT * FROM trip_shares WHERE share_code = ?", code);
    }

    /** 是否已过期（1=过期） */
    public Integer isExpired(Object expiresAt) {
        return jdbcTemplate.queryForObject("SELECT NOW() > ?", Integer.class, expiresAt);
    }

    /** 分享行程的内容（结构化 JSON 优先，markdown 兜底） */
    public Map<String, Object> findTripForShare(Long tripId) {
        return jdbcTemplate.queryForMap(
                "SELECT city, days, plan_content, plan_json FROM trip_plans WHERE id = ?", tripId);
    }

    /** 分享行程的城市与结构化数据（路线图用） */
    public Map<String, Object> findTripCityAndJson(Long tripId) {
        return jdbcTemplate.queryForMap("SELECT city, plan_json FROM trip_plans WHERE id = ?", tripId);
    }

    /** 浏览量 +1 */
    public int incrementView(String code) {
        return jdbcTemplate.update("UPDATE trip_shares SET view_count = view_count + 1 WHERE share_code = ?", code);
    }

    /** 分享码对应的行程 id */
    public List<Map<String, Object>> findTripIdByCode(String code) {
        return jdbcTemplate.queryForList("SELECT trip_id FROM trip_shares WHERE share_code = ?", code);
    }

    /** 某行程的分享状态（我的行程页展示分享链接） */
    public List<Map<String, Object>> findShareStatus(Long tripId, long uid) {
        return jdbcTemplate.queryForList(
                "SELECT share_code, view_count, created_at FROM trip_shares WHERE trip_id = ? AND user_id = ?", tripId, uid);
    }
}
