package managerAgent.orchestrator;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★2026-09-11 从 TripOrchestrator（2406 行上帝类）抽出的「路线Agent 返回解析层」。
 *
 * <p>抽出的都是纯函数、无实例状态——此前它们混在编排/HTTP/prompt 拼接/业务规则里，
 * 改一处要通读整个 god-class，且无法单独测。本类只做“文本 → 结构化数据”的解析，
 * 不认识会话、不写事件、不发请求。
 *
 * <p><b>行为与原实现逐行一致</b>（只移动，不改逻辑）：parseRouteJson / toInt /
 * extractTotalDistance / extractWaypoints + RouteData 数据类。
 */
public final class RoutePlanParser {  // 类声明：路线 Agent 返回解析层（纯函数，无状态）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private RoutePlanParser() {}  // 私有构造：禁止 new

    /** 路线Agent 结构化输出（原 TripOrchestrator.RouteData，随解析层一同搬来） */
    public static class RouteData {  // 内部类 RouteData：路线结构化数据
        public int totalKm = -1;      // 往返闭环总里程（含返程）  // totalKm：往返闭环总里程（-1=未知）
        public int oneWayKm = -1;     // 单程去程里程  // oneWayKm：单程去程里程（-1=未知）
        public String waypoints = ""; // 途经城市（逗号分隔）  // waypoints：途经城市（逗号分隔）
        public int routeCount = 0;    // 候选路线条数  // routeCount：候选路线条数
    }  // 内部类结束

    /**
     * 作用：解析路线子 Agent 的输出为结构化路线数据（总里程/分段/停靠点）
     * 参数：routeResult 路线原文
     * 返回值：路线数据对象；解析失败返回空数据
     * 异常：无（异常内部捕获）
     */
    public static RouteData parseRouteJson(String routeResult) {  // parseRouteJson：解析路线输出为结构化数据
        if (routeResult == null || routeResult.isBlank()) return null;  // 原文为空返回 null
        String json = null;  // json：抠出来的 JSON
        Matcher m = Pattern  // 先找【路线JSON】…【路线JSON结束】块
                .compile("【路线JSON】\\s*(\\{[\\s\\S]*?\\})\\s*【路线JSON结束】").matcher(routeResult);  // 续：非贪婪匹配到第一个完整对象
        if (m.find()) json = m.group(1);  // 匹配到就取正文
        if (json == null) json = utils.JsonUtils.extractJsonObject(routeResult);  // 没有块名则从全文抠第一个 JSON 对象
        if (json == null) return null;  // 都找不到返回 null
        Object o = utils.JsonUtils.parse(json, Object.class);  // 解析成通用对象
        if (!(o instanceof java.util.Map)) return null;  // 不是 Map 说明结构不对
        @SuppressWarnings("unchecked")  // @SuppressWarnings：下面的强转未检查
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) o;  // 强转成 Map
        RouteData rd = new RouteData();  // rd：结果对象
        rd.totalKm = toInt(map.get("totalKm"));  // 总里程
        rd.oneWayKm = toInt(map.get("oneWayKm"));  // 单程里程
        rd.waypoints = map.get("waypoints") == null ? "" : map.get("waypoints").toString().trim();  // 途经城市（null 归空串）
        rd.routeCount = toInt(map.get("routeCount"));  // 候选条数
        return rd.totalKm > 0 ? rd : null;  // 总里程有效才返回（否则算解析失败）
    }  // 方法结束

    /**
     * 作用：把任意对象安全转成整数
     * 参数：o 任意对象（数字或数字文本）
     * 返回值：整数值；无法转换返回 0
     * 异常：无
     */
    public static int toInt(Object o) {  // toInt：任意对象转整数
        if (o instanceof Number) return ((Number) o).intValue();  // 是数字直接取值
        if (o == null) return -1;  // null 返回 -1
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException e) { return -1; }  // 否则按文本解析，失败也返回 -1
    }  // 方法结束

    /**
     * 作用：从路线原文中提取总里程（公里）
     * 参数：routeResult 路线原文
     * 返回值：总公里数；提取不到返回 -1
     * 异常：无
     */
    public static int extractTotalDistance(String routeResult) {  // extractTotalDistance：从原文提取总里程
        if (routeResult == null || routeResult.isBlank()) return -1;  // 原文为空返回 -1
        // ① 优先结构化【路线JSON】块的 totalKm
        RouteData rd = parseRouteJson(routeResult);  // 先试结构化块
        if (rd != null && rd.totalKm > 0) return rd.totalKm;  // 拿到有效总里程就返回
        // ② 退回标注行（"往返总里程/总里程/去程里程：约 NNNkm"），不做散落 km 累加
        String[] keys = {"往返总里程", "总里程", "去程里程"};  // 退回标注行（三种说法）
        for (String key : keys) {  // 逐个尝试
            Matcher m = Pattern  // 正则：关键词后面跟数字 km
                    .compile(key + "[^\\d]*(\\d+(?:\\.\\d+)?)\\s*km").matcher(routeResult);  // 续行
            if (m.find()) {  // 匹配到
                try { return (int) Math.round(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) { }  // 四舍五入成整数返回
            }  // if 结束
        }  // for 结束
        return -1;  // 都没找到返回 -1
    }  // 方法结束

    /**
     * 作用：从路线原文中提取停靠点清单
     * 参数：routeResult 路线原文
     * 返回值：停靠点文本；提取不到返回空串
     * 异常：无
     */
    public static String extractWaypoints(String routeResult) {  // extractWaypoints：从原文提取途经城市
        if (routeResult == null || routeResult.isBlank()) return "";  // 原文为空返回空串
        // ① 优先结构化【路线JSON】块的 waypoints（精确字段，不用正则猜）
        RouteData rd = parseRouteJson(routeResult);  // 先试结构化块
        if (rd != null && !rd.waypoints.isBlank()) {  // 有 waypoints 字段
            return rd.waypoints;  // 直接返回（精确字段，不用正则猜）
        }  // if 结束
        Set<String> seen = new LinkedHashSet<>();  // seen：去重且保序
        // ① 优先：固定格式行（可多行=多条候选路线，合并去重）
        Matcher line = Pattern  // 优先匹配固定格式行“途经城市：xxx”
                .compile("途经城市[：:]\\s*([^\\n\\r]{0,120})")  // 续行
                .matcher(routeResult);  // 续行
        while (line.find() && seen.size() < 5) {  // 最多取 5 条，且最多扫 5 个城市
            for (String tok : line.group(1).split("[,，、;；\\s]+")) {  // 按逗号/顿号/分号/空白拆分
                String t = tok.trim();  // 去空格
                if (t.isBlank()) continue;  // 空片段跳过
                if (t.length() <= 6 && t.matches(".*(?:市|区|县|镇|州)$")) {  // 长度≤6 且以市/区/县/镇/州结尾才算地名
                    seen.add(t);  // 加入集合
                    if (seen.size() >= 5) break;  // 够 5 个就跳出
                }  // if 结束
            }  // for 结束
        }  // while 结束
        // ② 兜底：无"途经城市："行时退回旧全文正则（老格式/自由文本路线）
        if (seen.isEmpty()) {  // 没有固定格式行时兜底
            Matcher m = Pattern  // 全文正则找“2~6 个汉字 + 市/区/县/镇/州”
                    .compile("[\\u4e00-\\u9fa5]{2,6}?(?:市|区|县|镇|州)")  // 续行
                    .matcher(routeResult);  // 续行
            while (m.find() && seen.size() < 5) {  // 最多取 5 个
                seen.add(m.group());  // 加入集合
            }  // while 结束
        }  // if 结束
        // 剔除起终点城市名本身
        seen.removeIf(n -> n.contains("起点") || n.contains("终点"));  // 剔除“起点/终点”这类占位词
        return String.join(",", seen);  // 逗号拼接返回
    }  // 方法结束
}  // 类结束
