package utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ★2026-09-11 结果回捞的契约测试。
 * 背景：主管和子Agent 分处两个进程，任务指纹必须【双方一致】才能命中——
 * 一旦算法被改动而两边不同步，回捞会静默永远 miss（最坏情况：功能看着在、实际从未生效）。
 */
class LastResultStoreTest {

    @Test
    void keyIsStableForSameTask() {
        String task = "你是行程定稿Agent。本次任务：只编排整个行程的【第 1 天】（共 3 天）...";
        assertEquals(LastResultStore.keyOf(task), LastResultStore.keyOf(task));
    }

    @Test
    void keyIgnoresWhitespaceDifferences() {
        // 归一化去空白：换行/空格差异不应导致指纹不同（防止序列化差异造成 miss）
        assertEquals(LastResultStore.keyOf("第1天\n编排  行程"), LastResultStore.keyOf("第1天编排行程"));
    }

    @Test
    void keyDiffersForDifferentDays() {
        // 天骨架并发 3 天，指纹必须能区分是哪一天，否则回捞会串天
        assertNotEquals(LastResultStore.keyOf("编排第1天"), LastResultStore.keyOf("编排第2天"));
    }

    @Test
    void keyHandlesNullAndBlank() {
        assertEquals("", LastResultStore.keyOf(null));
        assertEquals("", LastResultStore.keyOf("   \n  "));
    }

    @Test
    void takeRemovesEntry() {
        String key = LastResultStore.keyOf("任务A-take测试");
        LastResultStore.put(key, "结果内容A");
        assertNotNull(LastResultStore.peek(key));
        assertNotNull(LastResultStore.take(key));
        assertNull(LastResultStore.take(key), "命中即取走：避免陈旧结果被反复回捞");
    }

    @Test
    void blankResultIsNotStored() {
        String key = LastResultStore.keyOf("任务B-空内容测试");
        LastResultStore.put(key, "   ");
        assertNull(LastResultStore.peek(key));
    }

    @Test
    void latestSinceFindsRecentEntry() {
        String key = LastResultStore.keyOf("任务C-latest测试");
        long before = System.currentTimeMillis() - 1000;
        LastResultStore.put(key, "结果内容C");
        LastResultStore.Entry e = LastResultStore.latestSince(before);
        assertNotNull(e);
        assertEquals("结果内容C", e.text);
    }

    @Test
    void tracePrefixChangesKey() {
        // ★回归守护（真实踩过）：主管发给子Agent 的任务是「【trace:<sessionId>】+ task」，
        // 子Agent 按收到的原文存指纹；而回捞一度拿"裸 task"去查 → 两边算的不是同一个字符串，
        // 必然全部 miss（实测存档 3 条、回捞 3 次全 hit=false，白存）。这条测试锁住这个差异，
        // 提醒任何改动回捞侧的人：算指纹必须用"实际发出去的文本"。
        String task = "你是行程定稿Agent。本次任务：只编排整个行程的【第 1 天】...";
        assertNotEquals(LastResultStore.keyOf(task),
                LastResultStore.keyOf("【trace:session-abc】" + task),
                "带 trace 前缀的任务文本必须与原任务文本产生不同指纹");
    }

    @Test
    void latestSinceIgnoresOlderEntries() {
        // sinceMs 兜底只应命中"本次调用之后"的结果：更早的陈旧结果不能被抓来冒充（否则会串任务）
        String key = LastResultStore.keyOf("任务D-陈旧测试");
        LastResultStore.put(key, "陈旧结果");
        long later = System.currentTimeMillis() + 60_000; // 比存档时间还晚
        assertNull(LastResultStore.latestSince(later));
    }
}
