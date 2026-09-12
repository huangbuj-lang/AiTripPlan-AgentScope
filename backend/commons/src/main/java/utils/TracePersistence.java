package utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 观测数据落库：任务历史 / 调用链 span / LLM 成本日汇总 持久化到 MySQL。 */
public final class TracePersistence {

    private static final String DB = "aitripplan";

    private TracePersistence() {}

    private static Connection getConn() throws Exception {
        String host = Config.get("RDS_HOST", "127.0.0.1:3306");
        String user = Config.get("RDS_USER", "root");
        String pass = Config.get("RDS_PASSWORD", "");
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://" + host + "/" + DB
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
        return DriverManager.getConnection(url, user, pass);
    }

    /** 任务历史 upsert（提交时插入，终态更新） */
    public static void upsertTask(String sessionId, long userId, String status, String step,
                                  long submitMs, long lastActiveMs, int modelCalls, String prompt) {
        try (Connection c = getConn()) {
            String sql = "INSERT INTO task_history(session_id,user_id,status,current_step,submit_ms,last_active_ms,model_calls_used,prompt) "
                    + "VALUES(?,?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE status=VALUES(status),current_step=VALUES(current_step),"
                    + "last_active_ms=VALUES(last_active_ms),model_calls_used=VALUES(model_calls_used),prompt=VALUES(prompt)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, sessionId);
                ps.setLong(2, userId);
                ps.setString(3, status);
                ps.setString(4, step == null ? "" : step);
                ps.setLong(5, submitMs);
                ps.setLong(6, lastActiveMs);
                ps.setInt(7, modelCalls);
                ps.setString(8, prompt == null ? "" : prompt);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[TracePersistence] upsertTask 失败(忽略): " + e.getMessage());
        }
    }

    /** 批量落库某任务的调用链 span（任务终态时调用； */
    public static void saveSpans(String sessionId, List<Map<String, Object>> spans) {
        if (spans == null || spans.isEmpty()) return;
        try (Connection c = getConn()) {
            String sql = "INSERT INTO trace_span(session_id,agent,layer,name,detail,success,cost_ms,ts,attempts) "
                    + "VALUES(?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Map<String, Object> s : spans) {
                    Object ts = s.get("ts");
                    if (!(ts instanceof Number)) continue;
                    ps.setString(1, sessionId);
                    ps.setString(2, s.get("agent") == null ? "" : String.valueOf(s.get("agent")));
                    ps.setString(3, s.get("layer") == null ? "" : String.valueOf(s.get("layer")));
                    ps.setString(4, s.get("name") == null ? "" : String.valueOf(s.get("name")));
                    ps.setString(5, s.get("detail") == null ? "" : String.valueOf(s.get("detail")));
                    ps.setInt(6, Boolean.TRUE.equals(s.get("success")) ? 1 : 0);
                    Object cost = s.get("costMs");
                    ps.setLong(7, cost instanceof Number n ? n.longValue() : -1L);
                    ps.setLong(8, ((Number) ts).longValue());
                    Object atts = s.get("attempts");
                    ps.setString(9, atts == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(atts));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception e) {
            System.err.println("[TracePersistence] saveSpans 失败(忽略): " + e.getMessage());
        }
    }

    /** 读取某任务的历史 span（重启后内存丢失，从库恢复） */
    public static List<Map<String, Object>> loadSpans(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = getConn()) {
            String sql = "SELECT agent,layer,name,detail,success,cost_ms,ts,attempts FROM trace_span WHERE session_id=? ORDER BY ts ASC";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("agent", rs.getString("agent"));
                        m.put("layer", rs.getString("layer"));
                        m.put("name", rs.getString("name"));
                        m.put("detail", rs.getString("detail"));
                        m.put("success", rs.getInt("success") == 1);
                        m.put("costMs", rs.getLong("cost_ms"));
                        m.put("ts", rs.getLong("ts"));
                        String atts = rs.getString("attempts");
                        if (atts != null && !atts.isBlank()) {
                            try {
                                m.put("attempts", new com.fasterxml.jackson.databind.ObjectMapper().readValue(atts, List.class));
                            } catch (Exception ignored) { }
                        }
                        out.add(m);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TracePersistence] loadSpans 失败(忽略): " + e.getMessage());
        }
        return out;
    }

    /** 历史任务列表（新的在前，limit 条） */
    public static List<Map<String, Object>> listHistory(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = getConn()) {
            String sql = "SELECT session_id,user_id,status,current_step,submit_ms,last_active_ms,model_calls_used FROM task_history "
                    + "ORDER BY submit_ms DESC LIMIT " + Math.max(1, Math.min(limit, 500));
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("sessionId", rs.getString("session_id"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("status", rs.getString("status"));
                    m.put("currentStep", rs.getString("current_step"));
                    m.put("submitMs", rs.getLong("submit_ms"));
                    m.put("lastActiveMs", rs.getLong("last_active_ms"));
                    m.put("modelCallsUsed", rs.getInt("model_calls_used"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            System.err.println("[TracePersistence] listHistory 失败(忽略): " + e.getMessage());
        }
        return out;
    }

    /** LLM 成本 upsert 当日维度（INSERT IGNORE + UPDATE 防并发） */
    public static void upsertLlmCost(String dimension, long calls, long inTokens, long outTokens) {
        try (Connection c = getConn()) {
            String day = new java.sql.Date(System.currentTimeMillis()).toString();
            String sql = "INSERT INTO llm_cost_daily(day,dimension,calls,input_tokens,output_tokens) VALUES(?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE calls=VALUES(calls),input_tokens=VALUES(input_tokens),output_tokens=VALUES(output_tokens)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, day);
                ps.setString(2, dimension);
                ps.setLong(3, calls);
                ps.setLong(4, inTokens);
                ps.setLong(5, outTokens);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[TracePersistence] upsertLlmCost 失败(忽略): " + e.getMessage());
        }
    }

    /** 近 N 日成本趋势（按日） */
    public static List<Map<String, Object>> costTrend(int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = getConn()) {
            String sql = "SELECT day, SUM(calls) calls, SUM(input_tokens) in_tok, SUM(output_tokens) out_tok "
                    + "FROM llm_cost_daily WHERE day >= DATE_SUB(CURDATE(), INTERVAL ? DAY) GROUP BY day ORDER BY day ASC";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, Math.max(1, Math.min(days, 90)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("day", String.valueOf(rs.getDate("day")));
                        m.put("calls", rs.getLong("calls"));
                        m.put("inputTokens", rs.getLong("in_tok"));
                        m.put("outputTokens", rs.getLong("out_tok"));
                        out.add(m);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TracePersistence] costTrend 失败(忽略): " + e.getMessage());
        }
        return out;
    }
}
