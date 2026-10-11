package managerAgent.repository;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 行程参考信息（城市定价 / 客流预测结果表）的数据访问层：只放 SQL，不写业务规则。
 */
@Repository
public class InsightsRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 城市定价参考（无该城市返回空列表） */
    public List<Map<String, Object>> findPricing(String city) {
        return jdbcTemplate.queryForList(
                "SELECT hotel_base, hotel_price, food_base, food_price, ticket_base, ticket_price "
                        + "FROM pricing_output WHERE city = ? LIMIT 1", city);
    }

    /** 城市客流参考（按日期区间） */
    public List<Map<String, Object>> findForecast(String city, LocalDate start, LocalDate end) {
        return jdbcTemplate.queryForList(
                "SELECT day, forecast_idx FROM forecast_output "
                        + "WHERE city = ? AND day BETWEEN ? AND ? ORDER BY day", city, start, end);
    }
}
