package utils.mcp;

import lombok.extern.slf4j.Slf4j;
import utils.Config;
import utils.JsonUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主管服务直连行程子服务内部候选端点（POST /internal/candidates）的客户端：
 * 审查定向重跑/增量修正场景绕过门面 ReAct——省去"决定调工具 + 复述JSON块"两轮大 prompt LLM 调用
 * （实测占定向重查 143s 中的 ~125s）。参数由调用方从 TravelState 精确构造，无 LLM 参与。
 * 返回与 query_trip_candidates 同构的输出（摘要 + 【域候选JSON】块）。
 */
@Slf4j
public final class CandidatesDirectClient {

    private CandidatesDirectClient() {}

    /**
     * 直连查询/修订三域候选。
     * @param refineJson 修订模式 JSON（null/空=全新查询，重跑全部三域）
     * @param timeoutMs  读超时：单域上限 120s，含低置信重查需放宽至 ~270s
     */
    public static String query(String destination, String days, String preference,
                               String waypoints, String brief, String refineJson, int timeoutMs) throws Exception {
        String base = Config.get("TRIP_PLANNER_BASE_URL", "http://localhost:8085");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("destination", destination == null ? "" : destination);
        payload.put("days", days == null ? "" : days);
        payload.put("preference", preference == null ? "" : preference);
        payload.put("waypoints", waypoints == null ? "" : waypoints);
        payload.put("brief", brief == null ? "" : brief);
        payload.put("refine", refineJson == null ? "" : refineJson);
        byte[] data = JsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/internal/candidates").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (var os = conn.getOutputStream()) {
            os.write(data);
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) {
            throw new IllegalStateException("internal/candidates HTTP " + code + ": "
                    + (body.length() > 200 ? body.substring(0, 200) : body));
        }
        return body;
    }
}
