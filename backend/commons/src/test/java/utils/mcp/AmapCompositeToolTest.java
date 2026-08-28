package utils.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** ★观测：综合工具失败时能从输出里提取内部 ⚠️ 失败片段（调用链明细内部归因） */
class AmapCompositeToolTest {

    private String extractWarn(String out) throws Exception {
        Method m = AmapCompositeTool.class.getDeclaredMethod("extractWarn", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, out);
    }

    @Test
    void extractsWarnSegmentFromOutput() throws Exception {
        String out = "【衔接】重庆 → 火星\n\n【距离】\n⚠️[query_urban_route] 无法定位起点坐标: 火星\n\n";
        String seg = extractWarn(out);
        assertTrue(seg.startsWith("⚠️") || seg.startsWith("⚠"), "实际: " + seg);
        assertTrue(seg.contains("无法定位起点坐标"), "实际: " + seg);
    }

    @Test
    void longWarnIsTruncatedTo100() throws Exception {
        StringBuilder sb = new StringBuilder("⚠️");
        for (int i = 0; i < 300; i++) sb.append("很长的失败原因");
        String seg = extractWarn(sb.toString());
        assertTrue(seg.length() <= 101, "长度超限: " + seg.length());
    }

    @Test
    void noWarnReturnsEmpty() throws Exception {
        assertEquals("", extractWarn("正常输出，没有警告"));
        assertEquals("", extractWarn(null));
    }
}
