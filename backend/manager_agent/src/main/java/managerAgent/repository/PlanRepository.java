package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 行程（trip_plans）的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class PlanRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 新增行程，返回自增 id（-1 表示取不到） */
    public long insert(long userId, String city, int days, String planContent, String planJson) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO trip_plans(user_id, city, days, plan_content, plan_json) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, userId);
            ps.setObject(2, city);
            ps.setObject(3, days);
            ps.setObject(4, planContent);
            ps.setObject(5, planJson);
            return ps;
        }, kh);
        return kh.getKey() != null ? kh.getKey().longValue() : -1L;
    }

    /** 某用户的全部行程（按 id 倒序） */
    public List<Map<String, Object>> listByUser(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM trip_plans WHERE user_id = ? ORDER BY id DESC", userId);
    }

    /** 按 id + user_id 双重条件删除，返回影响行数 */
    public int deleteOwn(long planId, long userId) {
        return jdbcTemplate.update(
                "DELETE FROM trip_plans WHERE id = ? AND user_id = ?", planId, userId);
    }
}
