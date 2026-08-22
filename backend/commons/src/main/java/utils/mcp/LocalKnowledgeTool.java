package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * ★本地知识库工具：查询预处理打标后的特征表（attractions_features/foods_features/hotels_features）
 *    供行程Agent与高德 query_trip 并行调用，融合"本地真实数据(评分/档次/类型)" + 高德实时数据。
 *
 * 数据源：data-pipeline/chat_server.py 的 GET /local-knowledge（内含 MySQL 查询）。
 * 不直接连库（避免给核心链路 jar 加 JDBC/MySQL 依赖），走 HTTP，零新依赖。
 * 服务未启动时返回降级标记，不影响高德主链路。
 */
public class LocalKnowledgeTool {

    /** 本地知识查询（按城市+偏好）。返回 JSON 文本或 "【本地数据获取失败】"。 */
    @Tool(name = "query_local_knowledge",
            description = "查询本地沉淀的景点/美食/酒店知识库（含评分、类型、档次）。与高德综合工具并行调用，" +
                    "作为高德实时数据的补充，帮助选择高分/符合偏好的地点。传 city 城市名，preference 偏好关键词可选（如 美食/亲子/自然）。")
    public String queryLocalKnowledge(
            @ToolParam(name = "city", description = "城市名，如：成都") String city,
            @ToolParam(name = "preference", description = "偏好关键词，可选，如：美食/亲子/自然/人文") String preference) {
        try {
            String url = "http://127.0.0.1:8090/local-knowledge?city="
                    + URLEncoder.encode(city == null ? "" : city, StandardCharsets.UTF_8)
                    + "&preference=" + URLEncoder.encode(preference == null ? "" : preference, StandardCharsets.UTF_8)
                    + "&limit=6";
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(3));
            conn.setReadTimeout((int) TimeUnit.SECONDS.toMillis(5));
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "AiTripPlan");
            int code = conn.getResponseCode();
            if (code != 200) {
                return "【本地知识获取失败】服务返回 " + code + "（不影响高德实时查询，请直接使用高德数据）";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String body = sb.toString();
            if (body.contains("\"ok\":false") || body.isEmpty()) {
                return "【本地知识获取失败】" + body;
            }
            return body;
        } catch (Exception e) {
            return "【本地知识获取失败】" + e.getClass().getSimpleName() + "（不影响高德实时查询，请直接使用高德数据）";
        }
    }
}