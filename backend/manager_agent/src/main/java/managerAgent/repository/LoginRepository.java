package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 用户账号（users）的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class LoginRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 用户名是否已存在 */
    public int countByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    /** 新增用户（密码已加密） */
    public int insert(String username, String hashedPassword) {
        return jdbcTemplate.update("INSERT INTO users(username, password) VALUES (?, ?)", username, hashedPassword);
    }

    /** 按用户名查用户（不存在返回空列表） */
    public List<Map<String, Object>> findByUsername(String username) {
        return jdbcTemplate.queryForList("SELECT * FROM users WHERE username = ?", username);
    }
}
