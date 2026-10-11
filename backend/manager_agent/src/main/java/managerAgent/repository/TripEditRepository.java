package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 行程二次编辑（trip_plans）的数据访问层：只放 SQL，不写业务规则。
 * 所有查询都带 user_id 条件，保证只能操作本人的行程。
 */
@Repository
public class TripEditRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 编辑页加载用：行程的城市/天数/结构化 JSON/正文 */
    public List<Map<String, Object>> findForEdit(Long id, long userId) {
        return jdbcTemplate.queryForList(
                "SELECT city, days, plan_json, plan_content FROM trip_plans WHERE id = ? AND user_id = ?", id, userId);
    }

    /** 覆盖保存结构化行程 + 重渲染的正文 */
    public int updateFull(Long id, long userId, String planJson, String planContent, String city, int days) {
        return jdbcTemplate.update(
                "UPDATE trip_plans SET plan_json = ?, plan_content = ?, city = ?, days = ? WHERE id = ? AND user_id = ?",
                planJson, planContent, city, days, id, userId);
    }

    /** 增量重排前读结构化 JSON 与天数 */
    public List<Map<String, Object>> findPlanJsonAndDays(Long id, long userId) {
        return jdbcTemplate.queryForList(
                "SELECT plan_json, days FROM trip_plans WHERE id = ? AND user_id = ?", id, userId);
    }

    /** 只更新结构化 JSON 与正文（重排某天用） */
    public int updatePlanJsonAndContent(Long id, long userId, String planJson, String planContent) {
        return jdbcTemplate.update(
                "UPDATE trip_plans SET plan_json = ?, plan_content = ? WHERE id = ? AND user_id = ?",
                planJson, planContent, id, userId);
    }
}
