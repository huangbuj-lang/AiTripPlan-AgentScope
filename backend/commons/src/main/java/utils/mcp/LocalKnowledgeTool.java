package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import utils.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * ★本地知识库工具：直接查询预处理打标后的特征表（attractions_features/foods_features/hotels_features）
 *    供行程Agent与高德 query_trip 并行调用，融合"本地真实数据(评分/档次/类型)" + 高德实时数据。
 *
 * ★偏好下推（相似度归一）：自由文本偏好经 PrefSynonyms 同义词表归一成标准标签，
 *    两段式查询——先按 label/suitable_tags 过滤（命中0条回退该城评分top），保证有数据返回。
 *
 * 数据源：MySQL aitripplan 库（连接信息从环境变量 RDS_HOST/RDS_USER/RDS_PASSWORD 读取，
 *    由 start-local.sh source .env 注入）。直接 JDBC 直连，不依赖 8090 Python 中转。
 * 表缺失/连接失败时返回降级标记，不影响高德主链路。
 */
public class LocalKnowledgeTool {

    private static final String DB = "aitripplan";

    // ==================== ★观测埋点：知识库查询成败/次数/耗时（管理端子Agent健康展示） ====================
    public static final java.util.concurrent.atomic.AtomicLong LK_CALLS = new java.util.concurrent.atomic.AtomicLong(0);
    public static final java.util.concurrent.atomic.AtomicLong LK_FAILS = new java.util.concurrent.atomic.AtomicLong(0);
    /** 累计耗时（ms），配合 CALLS 算平均 */
    public static final java.util.concurrent.atomic.AtomicLong LK_COST_MS = new java.util.concurrent.atomic.AtomicLong(0);

    // 知识点/美食/酒店 特征表
    private static final String[][] TABLES = {
        {"景点", "attractions_features"},
        {"美食", "foods_features"},
        {"酒店", "hotels_features"},
    };

    private Connection getConnection() throws Exception {
        String host = Config.get("RDS_HOST", "127.0.0.1:3306");
        String user = Config.get("RDS_USER", "root");
        String pass = Config.get("RDS_PASSWORD", "");
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://" + host + "/" + DB + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
        return DriverManager.getConnection(url, user, pass);
    }

    /** 本地知识查询（按城市+偏好，偏好经同义词归一后下推SQL过滤）。 */
    @Tool(name = "query_local_knowledge",
            description = "查询本地沉淀的景点/美食/酒店知识库（含评分、类型、档次、门票、时长、开放时间、游客提示）。与高德综合工具并行调用，" +
                    "作为高德实时数据的补充。传 city 城市名；preference 偏好可用自然语言（如 带娃/出片/吃得地道/性价比），会自动匹配标签。")
    public String queryLocalKnowledge(
            @ToolParam(name = "city", description = "城市名，如：成都") String city,
            @ToolParam(name = "preference", description = "偏好描述，可用自然语言，如：带娃亲子/出片拍照/地道美食/性价比高") String preference) {
        if (city == null || city.isBlank()) {
            LK_CALLS.incrementAndGet(); LK_FAILS.incrementAndGet(); // 参数缺失也算失败调用
            return "【本地知识获取失败】city 必填";
        }
        long lkStart = System.currentTimeMillis();
        LK_CALLS.incrementAndGet();
        try {
            String out = doQuery(city, preference);
            LK_COST_MS.addAndGet(System.currentTimeMillis() - lkStart);
            boolean lkOk = !out.contains("获取失败");
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_local_knowledge",
                    lkOk ? "知识库调用成功 · " + city + (preference == null || preference.isBlank() ? "" : " · " + preference)
                         : "知识库调用失败 · " + out.substring(0, Math.min(60, out.length())),
                    lkOk, System.currentTimeMillis() - lkStart);
            return out;
        } catch (RuntimeException re) {
            LK_FAILS.incrementAndGet();
            LK_COST_MS.addAndGet(System.currentTimeMillis() - lkStart);
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_local_knowledge",
                    "异常: " + re.getMessage(), false, System.currentTimeMillis() - lkStart);
            throw re;
        }
    }

    /** 原查询逻辑（观测埋点拆出的主体） */
    private String doQuery(String city, String preference) {
        String p = preference == null ? "" : preference.trim();
        // ★偏好归一：自由文本 → 标准标签（同义词表）
        List<String> prefTags = PrefSynonyms.normalize(p);

        // 意图（查哪几类）：优先按归一标签映射；无标签则按原关键词；默认全查
        List<String> intents = new ArrayList<>();
        String[] allIntentWords = {"美食", "吃", "餐厅", "火锅", "小吃", "地方菜", "异国料理", "海鲜", "素食", "饮品甜点",
                                   "酒店民宿", "住", "住宿", "宾馆", "经济型", "豪华型"};
        boolean foodHit = false, hotelHit = false, spotHit = false;
        for (String tag : prefTags) {
            if (tag.equals("美食") || tag.equals("火锅") || tag.equals("地方菜") || tag.equals("小吃快餐")
                || tag.equals("异国料理") || tag.equals("海鲜") || tag.equals("素食") || tag.equals("饮品甜点")) foodHit = true;
            if (tag.equals("酒店民宿") || tag.equals("经济型") || tag.equals("豪华型")) hotelHit = true;
            if (tag.equals("自然风光") || tag.equals("人文历史") || tag.equals("主题乐园") || tag.equals("特色街区")
                || tag.equals("城市公园") || tag.equals("小众") || tag.equals("亲子") || tag.equals("拍照")
                || tag.equals("悠闲") || tag.equals("徒步") || tag.equals("刺激") || tag.equals("夜生活")
                || tag.equals("温泉") || tag.equals("购物") || tag.equals("演艺") || tag.equals("骑行")) spotHit = true;
        }
        if (!foodHit && !hotelHit && !spotHit) {
            // 归一未命中 → 用原始关键词判断
            if (p.contains("美食") || p.contains("吃") || p.contains("餐厅") || p.contains("火锅") || p.contains("小吃")) foodHit = true;
            if (p.contains("酒店") || p.contains("住") || p.contains("住宿") || p.contains("宾馆")) hotelHit = true;
            if (p.contains("景点") || p.contains("景区") || p.contains("亲子") || p.contains("自然") || p.contains("人文") || p.contains("儿童")) spotHit = true;
        }
        if (foodHit) intents.add("美食");
        if (hotelHit) intents.add("酒店");
        if (spotHit) intents.add("景点");
        if (intents.isEmpty()) {
            intents.add("景点"); intents.add("美食"); intents.add("酒店");
        }

        StringBuilder sb = new StringBuilder("【本地知识库|").append(city).append("】");
        if (!prefTags.isEmpty()) {
            sb.append("（偏好:").append(String.join("/", prefTags)).append("，已按偏好筛选；未命中项已回退高分推荐）");
        }
        boolean any = false;
        try (Connection conn = getConnection()) {
            for (String intent : intents) {
                String table = null;
                for (String[] tb : TABLES) {
                    if (tb[0].equals(intent)) { table = tb[1]; break; }
                }
                if (table == null) continue;
                boolean isAttraction = "attractions_features".equals(table);
                boolean hasSuitableTags = isAttraction || "foods_features".equals(table); // ★hotels_features 无 suitable_tags 列
                String sel = isAttraction
                        ? "SELECT name, label, rating, level, suggest_duration, tips, best_time FROM " + table
                        : "SELECT name, label, rating, level, NULL AS suggest_duration, NULL AS tips, NULL AS best_time FROM " + table;

                // 两段式：先带偏好标签过滤；命中0条回退该城评分top
                List<String> rows = new ArrayList<>();
                if (!prefTags.isEmpty() && hasSuitableTags) {
                    StringBuilder cond = new StringBuilder();
                    for (String tag : prefTags) {
                        if (cond.length() > 0) cond.append(" OR ");
                        cond.append("(label LIKE ? OR suitable_tags LIKE ?)");
                    }
                    String sqlPref = sel + " WHERE city = ? AND (" + cond + ") ORDER BY rating DESC LIMIT 6";
                    try (PreparedStatement ps = conn.prepareStatement(sqlPref)) {
                        ps.setString(1, city);
                        int pi = 2;
                        for (String tag : prefTags) {
                            ps.setString(pi++, "%" + tag + "%");
                            ps.setString(pi++, "%" + tag + "%");
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) rows.add(renderRow(rs));
                        }
                    }
                }
                String mode;
                if (!rows.isEmpty()) {
                    mode = "";
                } else {
                    // 回退：偏好无命中（或无偏好）→ 该城评分top
                    String sqlAll = sel + " WHERE city = ? ORDER BY rating DESC LIMIT 6";
                    try (PreparedStatement ps = conn.prepareStatement(sqlAll)) {
                        ps.setString(1, city);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) rows.add(renderRow(rs));
                        }
                    }
                    mode = !prefTags.isEmpty() ? "（该偏好暂无精确匹配，以下为高分推荐）" : "";
                }
                if (!rows.isEmpty()) {
                    sb.append("\n【").append(intent).append("】").append(mode);
                    for (String r : rows) sb.append("\n- ").append(r);
                    any = true;
                }
            }
            if (!any) {
                LK_FAILS.incrementAndGet(); // 无数据也算业务失败（健康页可见）
                return "【本地知识获取失败】该城市暂无沉淀数据，请依赖高德实时查询";
            }
            return sb.toString();
        } catch (Exception e) {
            LK_FAILS.incrementAndGet(); // 数据库异常计失败
            return "【本地知识获取失败】连接数据库异常: " + e.getMessage() + "（不影响高德实时查询，请直接使用高德数据）";
        }
    }

    /** 单行渲染：名称+类型+评分+档次+时长+提示+开放时间 */
    private static String renderRow(ResultSet rs) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(rs.getString("name"))
          .append("（类型:").append(nvl(rs.getString("label")))
          .append(" 评分:").append(nvl(rs.getString("rating")))
          .append(" 档次:").append(nvl(rs.getString("level"))).append("）");
        String dur = rs.getString("suggest_duration");
        String tips = rs.getString("tips");
        String best = rs.getString("best_time");
        if (dur != null && !dur.isBlank()) b.append(" 建议时长:").append(dur);
        if (best != null && !best.isBlank()) b.append(" 开放时间:").append(best);
        if (tips != null && !tips.isBlank()) b.append(" 参考提示:").append(tips);
        return b.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
