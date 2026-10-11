package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 用户资料 / 旅行偏好 / 用户画像的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class ProfileRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 确保 user_profiles 行存在（惰性创建，注册时未建） */
    public void ensureRow(long uid) {
        jdbcTemplate.update("INSERT IGNORE INTO user_profiles(user_id) VALUES (?)", uid);
    }

    /** 账号基础信息（username / role / create_time） */
    public Map<String, Object> findUserBrief(long uid) {
        return jdbcTemplate.queryForMap("SELECT username, role, create_time FROM users WHERE id = ?", uid);
    }

    /** 资料明细（昵称 / 头像 / 邮箱 / 简介） */
    public Map<String, Object> findProfile(long uid) {
        return jdbcTemplate.queryForMap(
                "SELECT nickname, avatar_url, email, bio FROM user_profiles WHERE user_id = ?", uid);
    }

    /** 该邮箱被其它账号占用的数量 */
    public int countEmailUsedByOthers(String email, long uid) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profiles WHERE email = ? AND user_id <> ?", Integer.class, email, uid);
        return cnt == null ? 0 : cnt;
    }

    /** 更新资料 */
    public int updateProfile(long uid, String nickname, String avatarUrl, String email, String bio) {
        return jdbcTemplate.update(
                "UPDATE user_profiles SET nickname = ?, avatar_url = ?, email = ?, bio = ? WHERE user_id = ?",
                nickname, avatarUrl, email, bio, uid);
    }

    /** 读取密码哈希 */
    public String findPasswordHash(long uid) {
        return jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, uid);
    }

    /** 更新密码并 bump 凭证版本号（强制其它端下线） */
    public int updatePasswordAndBumpVersion(long uid, String newHashed) {
        return jdbcTemplate.update(
                "UPDATE users SET password = ?, pwd_version = pwd_version + 1 WHERE id = ?", newHashed, uid);
    }

    /** 读取凭证版本号 */
    public int findPwdVersion(long uid) {
        return ((Number) jdbcTemplate.queryForMap("SELECT pwd_version FROM users WHERE id = ?", uid)
                .get("pwd_version")).intValue();
    }

    /** 注销账号（逻辑删除 status=2） */
    public int deactivate(long uid) {
        return jdbcTemplate.update("UPDATE users SET status = 2 WHERE id = ?", uid);
    }

    /** 读取旅行偏好（无记录返回空列表） */
    public List<Map<String, Object>> findPreferences(long uid) {
        return jdbcTemplate.queryForList(
                "SELECT travel_style, budget_level, pace, avoid_list, use_by_default FROM user_preferences WHERE user_id = ?", uid);
    }

    /** 保存旅行偏好（存在则更新） */
    public int upsertPreferences(long uid, String style, Integer budgetLevel, Integer pace, String avoid, Integer useByDefault) {
        return jdbcTemplate.update(
                "INSERT INTO user_preferences(user_id, travel_style, budget_level, pace, avoid_list, use_by_default) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE travel_style=VALUES(travel_style), budget_level=VALUES(budget_level), " +
                "pace=VALUES(pace), avoid_list=VALUES(avoid_list), use_by_default=VALUES(use_by_default)",
                uid, style, budgetLevel, pace, avoid, useByDefault);
    }

    /** 读取用户画像标签原始 JSON（无记录返回空列表） */
    public List<Map<String, Object>> findProfileTags(long uid) {
        return jdbcTemplate.queryForList("SELECT tags FROM user_profiles_tags WHERE user_id = ?", uid);
    }
}
