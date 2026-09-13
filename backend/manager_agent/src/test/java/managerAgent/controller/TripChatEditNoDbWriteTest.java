package managerAgent.controller;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 铁律：对话式改行程（重排）【只改画面、不落库】——
 * 数据库只在用户点【保存到我的行程】时才写。
 * 这里直接对源码做守卫断言：重排接口里不许出现任何写库调用；
 * 同时确认"保存"这条路仍然存在（改名/误删会立刻红）。
 */
class TripChatEditNoDbWriteTest {

    private static Path src(String rel) {
        Path[] cands = {
                Paths.get(rel),
                Paths.get("backend/manager_agent/" + rel),
                Paths.get("../manager_agent/" + rel),
        };
        for (Path c : cands) if (Files.exists(c)) return c;
        return null;
    }

    private static String readController() throws IOException {
        Path f = src("src/main/java/managerAgent/controller/TripChatEditController.java");
        Assumptions.assumeTrue(f != null, "找不到源码文件（非源码树环境）→ 跳过");
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("重排接口【不许写库】：源码里不得出现 jdbcTemplate.update / UPDATE trip_plans")
    void chatEditMustNotWriteToDatabase() throws IOException {
        String s = readController();
        assertFalse(s.contains("jdbcTemplate.update("), "重排接口里出现了写库调用 ✗（用户口径：只有保存才落库）");
        assertFalse(s.contains("queryForMap("), "重排接口里出现了读库行快照的调用 ✗");
        assertFalse(s.contains("UPDATE trip_plans SET"), "重排接口里出现了真正的 UPDATE trip_plans 语句 ✗");
        assertFalse(s.contains("trip-chat-edit/undo"), "撤销回滚接口应已撤掉（重排不落库，无需回滚）✗");
    }

    @Test
    @DisplayName("重排要把结果交回前端（plan + markdown），否则「保存到我的行程」会没数据")
    void chatEditMustReturnPlanForSaving() throws IOException {
        String s = readController();
        assertTrue(s.contains("result.put(\"plan\", plan)"), "必须把整份行程返回给前端");
        assertTrue(s.contains("result.put(\"markdown\", savedMd)") || s.contains("markdown"),
                "必须把 markdown 返回给前端（保存用）");
        assertTrue(s.contains("prevSection"), "必须返回改动前那天（撤销只还原画面用）");
    }

    @Test
    @DisplayName("「保存到我的行程」这条路必须还在（写库能力没有被误删）")
    void savePathStillWrites() throws IOException {
        Path root = src("src/main/java/managerAgent/controller");
        Assumptions.assumeTrue(root != null, "非源码树环境 → 跳过");
        List<String> writers = new ArrayList<>();
        Files.walk(root).filter(f -> f.toString().endsWith(".java")).forEach(f -> {
            try {
                String t = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                if (t.contains("UPDATE trip_plans") && t.contains("plan_json")) writers.add(f.getFileName().toString());
            } catch (Exception ignore) { }
        });
        assertFalse(writers.isEmpty(), "整个项目里连一处写 trip_plans 的地方都没有了 ✗（保存功能被误删？）");
    }
}
