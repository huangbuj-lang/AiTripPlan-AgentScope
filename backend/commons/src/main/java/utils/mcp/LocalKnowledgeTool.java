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
 * 数据源：MySQL aitripplan 库（连接信息从环境变量 RDS_HOST/RDS_USER/RDS_PASSWORD 读取，
 *    由 start-local.sh source .env 注入）。直接 JDBC 直连，不依赖 8090 Python 中转。
 * 表缺失/连接失败时返回降级标记，不影响高德主链路。
 */
public class LocalKnowledgeTool {

    private static final String DB = "aitripplan";
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

    /** 本地知识查询（按城市+偏好）。返回 JSON 文本或 "【本地数据获取失败】". */
    @Tool(name = "query_local_knowledge",
            description = "查询本地沉淀的景点/美食/酒店知识库（含评分、类型、档次）。与高德综合工具并行调用，" +
                    "作为高德实时数据的补充，帮助选择高分/符合偏好的地点。传 city 城市名，preference 偏好关键词可选（如 美食/亲子/自然）。")
    public String queryLocalKnowledge(
            @ToolParam(name = "city", description = "城市名，如：成都") String city,
            @ToolParam(name = "preference", description = "偏好关键词，可选，如：美食/亲子/自然/人文") String preference) {
        if (city == null || city.isBlank()) {
            return "【本地知识获取失败】city 必填";
        }
        String p = preference == null ? "" : preference.trim();
        // 判断查哪几类：有偏好关键词就按偏好类；否则全查
        List<String> intents = new ArrayList<>();
        if (p.contains("美食") || p.contains("吃") || p.contains("餐厅") || p.contains("火锅") || p.contains("小吃")) intents.add("美食");
        if (p.contains("酒店") || p.contains("住") || p.contains("住宿") || p.contains("宾馆")) intents.add("酒店");
        if (p.contains("景点") || p.contains("景区") || p.contains("亲子") || p.contains("自然") || p.contains("人文") || p.contains("儿童")) intents.add("景点");
        if (intents.isEmpty()) {
            intents.add("景点"); intents.add("美食"); intents.add("酒店");
        }
        StringBuilder sb = new StringBuilder("【本地知识库|").append(city).append("】（时长/提示为通用参考，非实时评价）");
        boolean any = false;
        try (Connection conn = getConnection()) {
            for (String intent : intents) {
                String table = null;
                for (String[] t : TABLES) {
                    if (t[0].equals(intent)) { table = t[1]; break; }
                }
                if (table == null) continue;
                boolean isAttraction = "attractions_features".equals(table);
                String sql = isAttraction
                        ? "SELECT name, label, rating, level, suggest_duration, tips, best_time FROM " + table
                          + " WHERE city = ? ORDER BY rating DESC LIMIT 6"
                        : "SELECT name, label, rating, level, NULL, NULL, NULL FROM " + table
                          + " WHERE city = ? ORDER BY rating DESC LIMIT 6";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, city);
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean head = false;
                        while (rs.next()) {
                            if (!head) { sb.append("\n【").append(intent).append("】"); head = true; }
                            sb.append("\n- ").append(rs.getString("name"))
                              .append("（类型:").append(nvl(rs.getString("label")))
                              .append(" 评分:").append(nvl(rs.getString("rating")))
                              .append(" 档次:").append(nvl(rs.getString("level"))).append("）");
                            String dur = rs.getString("suggest_duration");
                            String tips = rs.getString("tips");
                            String best = rs.getString("best_time");
                            if (dur != null && !dur.isBlank()) sb.append(" 建议时长:").append(dur);
                            if (best != null && !best.isBlank()) sb.append(" 最佳时段:").append(best);
                            if (tips != null && !tips.isBlank()) sb.append(" 参考提示:").append(tips);
                            any = true;
                        }
                    }
                }
            }
            sb.append("\n]");
            if (!any) {
                return "【本地知识获取失败】该城市暂无沉淀数据，请依赖高德实时查询";
            }
            return sb.toString();
        } catch (Exception e) {
            return "【本地知识获取失败】连接数据库异常: " + e.getMessage() + "（不影响高德实时查询，请直接使用高德数据）";
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}