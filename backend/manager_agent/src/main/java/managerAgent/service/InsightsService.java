package managerAgent.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.repository.InsightsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户侧行程参考信息业务：参数校验、定价与客流的组装、缺数据时降级为 available=false。
 * 只做业务，SQL 在 InsightsRepository；异常兜底也在这里（读库失败不影响主流程）。
 */
@Slf4j
@Service
public class InsightsService {

    @Resource
    private InsightsRepository insightsRepository;

    public Map<String, Object> insights(String city, LocalDate startDate, Integer days) {
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
            List<Map<String, Object>> rows = insightsRepository.findPricing(city);
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
            List<Map<String, Object>> rows = insightsRepository.findForecast(city, start, end);
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
