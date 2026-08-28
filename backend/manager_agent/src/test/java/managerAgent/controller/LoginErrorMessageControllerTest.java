package managerAgent.controller;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LoginErrorMessageControllerTest {

    @Test
    void missingUserAndWrongPasswordUseTheSamePublicMessage() throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        LoginController emptyController = controller(new EmptyUserJdbcTemplate());
        Map<String, Object> missing = emptyController.login(
                "missing-" + System.nanoTime(), "bad-password", new FakeSession());

        LoginController knownController = controller(new KnownUserJdbcTemplate(encoder.encode("correct-password")));
        Map<String, Object> wrong = knownController.login(
                "known-" + System.nanoTime(), "bad-password", new FakeSession());

        assertFalse((Boolean) missing.get("success"));
        assertFalse((Boolean) wrong.get("success"));
        assertEquals("用户名或密码错误", missing.get("message"));
        assertEquals("用户名或密码错误", wrong.get("message"));
    }

    private LoginController controller(JdbcTemplate jdbcTemplate) throws Exception {
        LoginController controller = new LoginController();
        Field field = LoginController.class.getDeclaredField("jdbcTemplate");
        field.setAccessible(true);
        field.set(controller, jdbcTemplate);
        return controller;
    }

    private static class EmptyUserJdbcTemplate extends JdbcTemplate {
        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return List.of();
        }
    }

    private static class KnownUserJdbcTemplate extends JdbcTemplate {
        private final String passwordHash;

        private KnownUserJdbcTemplate(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return List.of(Map.of(
                    "id", 1L,
                    "password", passwordHash,
                    "status", 1,
                    "role", "user",
                    "pwd_version", 0
            ));
        }
    }

    private static class FakeSession implements HttpSession {
        @Override
        public long getCreationTime() {
            return System.currentTimeMillis();
        }

        @Override
        public String getId() {
            return "test-session";
        }

        @Override
        public long getLastAccessedTime() {
            return System.currentTimeMillis();
        }

        @Override
        public void setMaxInactiveInterval(int interval) {
        }

        @Override
        public int getMaxInactiveInterval() {
            return 0;
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void removeAttribute(String name) {
        }

        @Override
        public void invalidate() {
        }

        @Override
        public boolean isNew() {
            return true;
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }
    }
}
