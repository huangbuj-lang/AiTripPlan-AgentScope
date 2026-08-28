package managerAgent.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户侧行程参考信息：只读展示数据库已生成的城市级定价与客流结果。
 */
@Slf4j
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @GetMapping
    public Map<String, Object> insights(
            @RequestParam String city,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false, defaultValue = "1") Integer days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("available", false);

        String normalizedCity = city == null ? "" : city.trim();
        if (normalizedCity.isEmpty() || days == null || days < 1 || days > 30) {
            return result;
        }
        LocalDate start = startDate == null ? LocalDate.now().plusDays(1) : startDate;
        LocalDate end = start.plusDays(Math.max(days, 1) - 1L);

        Map<String, Object> pricing = readPricing(normalizedCity);
        List<Map<String, Object>> forecast = readForecast(normalizedCity, start, end);
        if (pricing == null && forecast.isEmpty()) {
            return result;
        }

        result.put("city", normalizedCity);
        result.put("range", Map.of("startDate", start.toString(), "endDate", end.toString()));
        if (pricing != null) {
            result.put("pricing", pricing);
        }
        if (!forecast.isEmpty()) {
            result.put("forecast", forecast);
        }
        result.put("available", true);
        return result;
    }

    private Map<String, Object> readPricing(String city) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT hotel_base, hotel_price, food_base, food_price, ticket_base, ticket_price "
                            + "FROM pricing_output WHERE city = ? LIMIT 1", city);
            return rows.isEmpty() ? null : priceGroup(rows.get(0));
        } catch (Exception e) {
            log.warn("读取城市定价参考失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> priceGroup(Map<String, Object> row) {
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("hotel", pricePair(row.get("hotel_base"), row.get("hotel_price")));
        pricing.put("food", pricePair(row.get("food_base"), row.get("food_price")));
        pricing.put("ticket", pricePair(row.get("ticket_base"), row.get("ticket_price")));
        return pricing;
    }

    private Map<String, Object> pricePair(Object base, Object price) {
        if (base == null || price == null) {
            return Map.of();
        }
        return Map.of("base", base, "price", price);
    }

    private List<Map<String, Object>> readForecast(String city, LocalDate start, LocalDate end) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT day, forecast_idx FROM forecast_output "
                            + "WHERE city = ? AND day BETWEEN ? AND ? ORDER BY day", city, start, end);
            List<Map<String, Object>> forecast = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object idx = row.get("forecast_idx");
                if (row.get("day") != null && idx != null) {
                    forecast.add(Map.of("day", row.get("day").toString(), "index", idx));
                }
            }
            return forecast;
        } catch (Exception e) {
            log.warn("读取城市客流参考失败: {}", e.getMessage());
            return List.of();
        }
    }
}
