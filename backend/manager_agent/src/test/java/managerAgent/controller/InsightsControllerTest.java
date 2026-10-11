package managerAgent.controller;

import managerAgent.repository.InsightsRepository;
import managerAgent.service.InsightsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsControllerTest {

    @Test
    void returnsPricingAndForecastForDateRange() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.pricingRows = List.of(Map.of(
                "hotel_base", 300.0,
                "hotel_price", 318.0,
                "food_base", 182.0,
                "food_price", 192.9,
                "ticket_base", 37.5,
                "ticket_price", 39.8
        ));
        jdbc.forecastRows = List.of(Map.of("day", java.sql.Date.valueOf("2026-08-27"), "forecast_idx", 1.2));
        InsightsController controller = controller(jdbc);

        Map<String, Object> result = controller.insights("成都", LocalDate.parse("2026-08-27"), 2);

        assertTrue((Boolean) result.get("available"));
        assertEquals(Map.of("base", 300.0, "price", 318.0), result.get("pricing") == null ? null : ((Map<?, ?>) result.get("pricing")).get("hotel"));
        assertEquals(List.of(Map.of("day", "2026-08-27", "index", 1.2)), result.get("forecast"));
    }

    @Test
    void hidesCardWhenNoReferenceDataExists() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        InsightsController controller = controller(jdbc);

        Map<String, Object> result = controller.insights("未知城市", LocalDate.parse("2026-08-27"), 1);

        assertFalse((Boolean) result.get("available"));
    }

    private InsightsController controller(JdbcTemplate jdbcTemplate) throws Exception {
        InsightsRepository repository = new InsightsRepository();
        inject(repository, "jdbcTemplate", jdbcTemplate);
        InsightsService service = new InsightsService();
        inject(service, "insightsRepository", repository);
        InsightsController controller = new InsightsController();
        inject(controller, "insightsService", service);
        return controller;
    }

    /** 反射注入：重构后 SQL 在 repository、业务在 service，测试按同一条链装配 */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }


    private static class RecordingJdbcTemplate extends JdbcTemplate {
        private List<Map<String, Object>> pricingRows = List.of();
        private List<Map<String, Object>> forecastRows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return sql.contains("pricing_output") ? pricingRows : forecastRows;
        }
    }
}
