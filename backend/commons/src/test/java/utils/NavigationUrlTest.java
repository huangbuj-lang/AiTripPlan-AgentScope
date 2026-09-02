package utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 高德 URI API 导航链接构造单元测试（公交模式）。
 */
class NavigationUrlTest {

    @Test
    void buildsBusModeUrlWithTwoPoints() {
        String url = ExternalServices.buildNavigationUrl(List.of(
                "100.239852,26.888448,黑龙潭公园,poi1",
                "100.230106,26.872221,丽江古城,poi2"));
        assertTrue(url.startsWith("https://uri.amap.com/navigation?"), "应使用官方 URI API");
        assertTrue(url.contains("mode=bus"), "市内默认公交模式");
        assertTrue(url.contains("from=100.239852,26.888448,"), "起点经纬度在前");
        assertTrue(url.contains("to=100.230106,26.872221,"), "终点经纬度在前");
        assertFalse(url.contains("via="), "两点无途经段");
        assertTrue(url.contains("coordinate=gaode"));
        assertTrue(url.contains("callnative=0"));
    }

    @Test
    void buildsViaWithSemicolonForThreePoints() {
        String url = ExternalServices.buildNavigationUrl(List.of(
                "100.1,26.1,A景点,p1",
                "100.2,26.2,B景点,p2",
                "100.3,26.3,C景点,p3"));
        assertTrue(url.contains("via=100.2,26.2,"), "途经点在 via 参数");
        assertFalse(url.contains("|"), "不再使用旧 ssr/dir 的竖线分隔");
        assertTrue(url.indexOf("from=") < url.indexOf("via="));
        assertTrue(url.indexOf("via=") < url.indexOf("to="));
    }

    @Test
    void encodesChineseNames() {
        String url = ExternalServices.buildNavigationUrl(List.of(
                "100.1,26.1,黑龙潭公园,p1",
                "100.2,26.2,丽江古城,p2"));
        assertFalse(url.contains("黑龙潭"), "中文名应被 URL 编码");
        assertTrue(url.contains("from=100.1,26.1,%E9%BB%91"));
    }

    @Test
    void supportsCarModeOverride() {
        String url = ExternalServices.buildNavigationUrl(List.of(
                "100.1,26.1,A", "100.2,26.2,B"), "car");
        assertTrue(url.contains("mode=car"));
    }

    @Test
    void returnsEmptyOnFewerThanTwoPoints() {
        assertEquals("", ExternalServices.buildNavigationUrl(List.of("100.1,26.1,A")));
        assertEquals("", ExternalServices.buildNavigationUrl(List.of()));
        assertEquals("", ExternalServices.buildNavigationUrl(null));
    }
}
