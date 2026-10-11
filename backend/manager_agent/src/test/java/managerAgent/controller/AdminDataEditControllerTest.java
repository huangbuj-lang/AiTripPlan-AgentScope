package managerAgent.controller;

import managerAgent.repository.AdminDataRepository;
import managerAgent.service.AdminDataService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminDataEditControllerTest {

    @Test
    void reportsMissingRecordWithoutFeatureUpdate() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.mainRows = 0;
        AdminDataController controller = controller(jdbc);

        Map<String, Object> result = controller.editDataDetail(
                "attractions", 99L, "cost", "120");

        assertFalse((Boolean) result.get("success"));
        assertEquals("主记录不存在，未保存", result.get("message"));
        assertEquals(List.of("UPDATE attractions SET cost = ? WHERE id = ?"), jdbc.statements);
    }

    @Test
    void reportsPartialSaveWhenFeatureRecordIsMissing() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.mainRows = 1;
        jdbc.featureRows = 0;
        AdminDataController controller = controller(jdbc);

        Map<String, Object> result = controller.editDataDetail(
                "foods", 12L, "cost", "45");

        assertFalse((Boolean) result.get("success"));
        assertEquals("主记录已保存，但关联特征记录不存在；动态定价可能不会生效", result.get("message"));
        assertEquals(List.of(
                "UPDATE foods SET cost = ? WHERE id = ?",
                "UPDATE foods_features SET cost = ? WHERE src_id = ?"
        ), jdbc.statements);
    }

    @Test
    void savesFoodCostAgainstFoodFeatureTable() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.mainRows = 1;
        jdbc.featureRows = 1;
        AdminDataController controller = controller(jdbc);

        Map<String, Object> result = controller.editDataDetail(
                "foods", 12L, "cost", "45");

        assertTrue((Boolean) result.get("success"));
        assertEquals("已保存", result.get("message"));
        assertEquals("45", jdbc.values.get(2));
        assertTrue(jdbc.statements.get(1).startsWith("UPDATE foods_features"));
    }

    @Test
    void reportsMissingRecordForPlainField() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.mainRows = 0;
        AdminDataController controller = controller(jdbc);

        Map<String, Object> result = controller.editDataDetail(
                "hotels", 99L, "name", "测试酒店");

        assertFalse((Boolean) result.get("success"));
        assertEquals("记录不存在，未保存", result.get("message"));
    }

    private AdminDataController controller(JdbcTemplate jdbcTemplate) throws Exception {
        AdminDataRepository repository = new AdminDataRepository();
        inject(repository, "jdbcTemplate", jdbcTemplate);
        AdminDataService service = new AdminDataService();
        inject(service, "adminDataRepository", repository);
        AdminDataController controller = new AdminDataController();
        inject(controller, "adminDataService", service);
        return controller;
    }

    /** 反射注入：重构后 SQL 在 repository、业务在 service，测试按同一条链装配 */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }


    private static class RecordingJdbcTemplate extends JdbcTemplate {
        private int mainRows;
        private int featureRows;
        private final List<String> statements = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            statements.add(sql);
            for (Object arg : args) {
                values.add(arg);
            }
            return sql.contains("_features") ? featureRows : mainRows;
        }
    }
}
