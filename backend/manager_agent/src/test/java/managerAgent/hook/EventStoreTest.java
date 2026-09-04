package managerAgent.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventStore.allQuiet()：管理端只读查询事件，不得凭空注册新会话。
 */
class EventStoreTest {

    @Test
    void allQuietReadsExistingSessionWithoutSideEffects() {
        String sid = "ev-test-" + System.nanoTime();
        EventStore.add(sid, "解析", "测试事件1");
        EventStore.add(sid, "审查", "测试事件2");

        List<Map<String, String>> ev = EventStore.allQuiet(sid);
        assertNotNull(ev, "已存在会话应能读到事件");
        assertEquals(2, ev.size());
        assertEquals("解析", ev.get(0).get("type"));
        assertEquals("测试事件2", ev.get(1).get("content"));

        // 读操作不应新增会话数量
        int before = EventStore.listSessions().size();
        EventStore.allQuiet(sid);
        EventStore.allQuiet(sid);
        int after = EventStore.listSessions().size();
        assertEquals(before, after, "allQuiet 不应产生新的会话注册");
    }

    @Test
    void allQuietReturnsNullForUnknownSession() {
        String sid = "ev-unknown-" + System.nanoTime();
        assertNull(EventStore.allQuiet(sid), "未知会话应返回 null（区别于空列表）");
        // 确实没有创建会话
        boolean exists = EventStore.listSessions().stream().anyMatch(m -> sid.equals(m.get("sessionId")));
        assertFalse(exists, "查询未知会话不应注册新会话");
    }

    @Test
    void allQuietNormalizesNullToDefaultLikeAll() {
        EventStore.add("default", "思考", "默认会话事件");
        List<Map<String, String>> ev = EventStore.allQuiet(null);
        assertNotNull(ev, "null/空应落到 default 会话");
        assertTrue(ev.stream().anyMatch(e -> "默认会话事件".equals(e.get("content"))));
    }
}
