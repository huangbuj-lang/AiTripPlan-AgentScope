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
public final class TracePersistence {  // 类声明：观测数据落库（任务历史/调用链 span/LLM 成本）

    private static final String DB = "aitripplan";  // DB：库名 aitripplan
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private TracePersistence() {}  // 私有构造：禁止 new
    /**
     * 作用：获取数据库连接（MySQL，配置见 Config）
     * 参数：无
     * 返回值：Connection 实例
     * 异常：Exception 驱动缺失或连接失败时抛出
     */

    private static Connection getConn() throws Exception {  // getConn：取 MySQL 连接
        String host = Config.get("RDS_HOST", "127.0.0.1:3306");  // host 取配置 RDS_HOST（默认 127.0.0.1:3306）
        String user = Config.get("RDS_USER", "root");  // 用户名取 RDS_USER（默认 root）
        String pass = Config.get("RDS_PASSWORD", "");  // 密码取 RDS_PASSWORD
        Class.forName("com.mysql.cj.jdbc.Driver");  // 加载 MySQL 8 驱动
        String url = "jdbc:mysql://" + host + "/" + DB  // 拼 JDBC URL（含库名）
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"  // 续：UTF-8 编码 + 时区上海
                + "&allowPublicKeyRetrieval=true&useSSL=false";  // 续：允许公钥检索、关闭 SSL
        return DriverManager.getConnection(url, user, pass);  // 建立连接并返回
    }  // 方法结束

    /**
     * 作用：按会话写入/更新任务历史（终态落库），同会话重复提交则更新
     * 参数：sessionId 会话 ID；userId 用户 ID（-1 表示未登录）；status 终态（DONE/FAILED/CANCELLED）；step 当前步骤描述；submitMs 提交时间戳；lastActiveMs 最后活动时间戳；modelCalls 本任务模型调用次数；prompt 任务提示词
     * 返回值：无
     * 异常：无（数据库异常内部捕获并记日志，不影响主流程）
     */
    public static void upsertTask(String sessionId, long userId, String status, String step,  // upsertTask：按会话写任务历史（同会话重复提交则更新）
                                  long submitMs, long lastActiveMs, int modelCalls, String prompt) {  // 续行：submitMs 提交时间、lastActiveMs 最后活动、modelCalls 模型调用数
        try (Connection c = getConn()) {  // try-with-resources 取连接
            String sql = "INSERT INTO task_history(session_id,user_id,status,current_step,submit_ms,last_active_ms,model_calls_used,prompt) "  // INSERT ... ON DUPLICATE KEY UPDATE
                    + "VALUES(?,?,?,?,?,?,?,?) "  // 续：8 个占位符
                    + "ON DUPLICATE KEY UPDATE status=VALUES(status),current_step=VALUES(current_step),"  // 续：冲突时更新状态/步骤/
                    + "last_active_ms=VALUES(last_active_ms),model_calls_used=VALUES(model_calls_used),prompt=VALUES(prompt)";  // 续：最后活动/模型调用数/提示词
            try (PreparedStatement ps = c.prepareStatement(sql)) {  // 预编译语句
                ps.setString(1, sessionId);  // ① session_id
                ps.setLong(2, userId);  // ② user_id
                ps.setString(3, status);  // ③ status 终态
                ps.setString(4, step == null ? "" : step);  // ④ current_step（null 归空串）
                ps.setLong(5, submitMs);  // ⑤ submit_ms
                ps.setLong(6, lastActiveMs);  // ⑥ last_active_ms
                ps.setInt(7, modelCalls);  // ⑦ model_calls_used
                ps.setString(8, prompt == null ? "" : prompt);  // ⑧ prompt（null 归空串）
                ps.executeUpdate();  // 执行更新
            }  // ps 关闭
        } catch (Exception e) {  // 捕获异常
            System.err.println("[TracePersistence] upsertTask 失败(忽略): " + e.getMessage());  // 打印“失败(忽略)”，不影响主流程
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：批量保存任务的调用链 span（先删旧再插新）
     * 参数：sessionId 会话 ID；spans span 的 Map 列表
     * 返回值：无
     * 异常：无（数据库异常内部捕获）
     */
    public static void saveSpans(String sessionId, List<Map<String, Object>> spans) {  // saveSpans：批量写入调用链 span
        if (spans == null || spans.isEmpty()) return;  // 空列表直接返回
        try (Connection c = getConn()) {  // try-with-resources 取连接
            String sql = "INSERT INTO trace_span(session_id,agent,layer,name,detail,success,cost_ms,ts,attempts) "  // INSERT 语句（9 个字段）
                    + "VALUES(?,?,?,?,?,?,?,?,?)";  // 续行
            try (PreparedStatement ps = c.prepareStatement(sql)) {  // 预编译语句
                for (Map<String, Object> s : spans) {  // 逐条 span 处理
                    Object ts = s.get("ts");  // ts：时间戳
                    if (!(ts instanceof Number)) continue;  // 没有数字时间戳就跳过（数据库必填）
                    ps.setString(1, sessionId);  // ① session_id
                    ps.setString(2, s.get("agent") == null ? "" : String.valueOf(s.get("agent")));  // ② agent（null 归空串）
                    ps.setString(3, s.get("layer") == null ? "" : String.valueOf(s.get("layer")));  // ③ layer 层（网关/编排/模型…）
                    ps.setString(4, s.get("name") == null ? "" : String.valueOf(s.get("name")));  // ④ name 节点名
                    ps.setString(5, s.get("detail") == null ? "" : String.valueOf(s.get("detail")));  // ⑤ detail 详情
                    ps.setInt(6, Boolean.TRUE.equals(s.get("success")) ? 1 : 0);  // ⑥ success 转 1/0
                    Object cost = s.get("costMs");  // cost：耗时字段
                    ps.setLong(7, cost instanceof Number n ? n.longValue() : -1L);  // ⑦ cost_ms（非数字记 -1）
                    ps.setLong(8, ((Number) ts).longValue());  // ⑧ ts
                    Object atts = s.get("attempts");  // atts：重试记录
                    ps.setString(9, atts == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(atts));  // ⑨ attempts 序列化成 JSON（null 存 null）
                    ps.addBatch();  // 加入批处理
                }  // for 结束
                ps.executeBatch();  // 一次提交整批
            }  // ps 关闭
        } catch (Exception e) {  // 捕获异常
            System.err.println("[TracePersistence] saveSpans 失败(忽略): " + e.getMessage());  // 打印“失败(忽略)”
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取某会话已落库的调用链 span
     * 参数：sessionId 会话 ID
     * 返回值：span 的 Map 列表，无记录时为空列表
     * 异常：无（数据库异常内部捕获，返回已读到的部分）
     */
    public static List<Map<String, Object>> loadSpans(String sessionId) {  // loadSpans：读某会话的 span
        List<Map<String, Object>> out = new ArrayList<>();  // out：结果列表
        try (Connection c = getConn()) {  // try-with-resources 取连接
            String sql = "SELECT agent,layer,name,detail,success,cost_ms,ts,attempts FROM trace_span WHERE session_id=? ORDER BY ts ASC";  // 按 ts 升序查询
            try (PreparedStatement ps = c.prepareStatement(sql)) {  // 预编译语句
                ps.setString(1, sessionId);  // 绑定 session_id
                try (ResultSet rs = ps.executeQuery()) {  // 执行查询
                    while (rs.next()) {  // 逐行读结果
                        Map<String, Object> m = new HashMap<>();  // m：一条 span 的 Map
                        m.put("agent", rs.getString("agent"));  // agent 名
                        m.put("layer", rs.getString("layer"));  // layer 层
                        m.put("name", rs.getString("name"));  // name 节点名
                        m.put("detail", rs.getString("detail"));  // detail 详情
                        m.put("success", rs.getInt("success") == 1);  // success 转布尔
                        m.put("costMs", rs.getLong("cost_ms"));  // costMs 耗时毫秒
                        m.put("ts", rs.getLong("ts"));  // ts 时间戳
                        String atts = rs.getString("attempts");  // atts：重试记录 JSON
                        if (atts != null && !atts.isBlank()) {  // 有内容才反序列化
                            try {  // try
                                m.put("attempts", new com.fasterxml.jackson.databind.ObjectMapper().readValue(atts, List.class));  // JSON 反序列化成 List 放回 Map
                            } catch (Exception ignored) { }  // 解析失败忽略（只是辅助信息）
                        }  // catch 结束
                        out.add(m);  // 加入结果列表
                    }  // while 结束
                }  // ResultSet 关闭
            }  // PreparedStatement 关闭
        } catch (Exception e) {  // 捕获异常
            System.err.println("[TracePersistence] loadSpans 失败(忽略): " + e.getMessage());  // 打印“失败(忽略)”
        }  // catch 结束
        return out;  // 返回已读到的部分
    }  // 方法结束

    /**
     * 作用：按时间倒序读取任务历史（管理端展示）
     * 参数：limit 最多返回条数
     * 返回值：任务 Map 列表，异常时为空列表
     * 异常：无
     */
    public static List<Map<String, Object>> listHistory(int limit) {  // listHistory：按时间倒序读任务历史（管理端用）
        List<Map<String, Object>> out = new ArrayList<>();  // out：结果列表
        try (Connection c = getConn()) {  // try-with-resources 取连接
            String sql = "SELECT session_id,user_id,status,current_step,submit_ms,last_active_ms,model_calls_used FROM task_history "  // 查 7 个字段
                    + "ORDER BY submit_ms DESC LIMIT " + Math.max(1, Math.min(limit, 500));  // 续：按提交时间倒序，limit 夹在 1~500
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {  // Statement + ResultSet（limit 已夹紧，无注入风险）
                while (rs.next()) {  // 逐行读
                    Map<String, Object> m = new HashMap<>();  // m：一行任务
                    m.put("sessionId", rs.getString("session_id"));  // sessionId
                    m.put("userId", rs.getLong("user_id"));  // userId
                    m.put("status", rs.getString("status"));  // status 终态
                    m.put("currentStep", rs.getString("current_step"));  // currentStep 当前步骤
                    m.put("submitMs", rs.getLong("submit_ms"));  // submitMs 提交时间
                    m.put("lastActiveMs", rs.getLong("last_active_ms"));  // lastActiveMs 最后活动
                    m.put("modelCallsUsed", rs.getInt("model_calls_used"));  // modelCallsUsed 模型调用次数
                    out.add(m);  // 加入结果
                }  // while 结束
            }  // 资源关闭
        } catch (Exception e) {  // 捕获异常
            System.err.println("[TracePersistence] listHistory 失败(忽略): " + e.getMessage());  // 打印“失败(忽略)”
        }  // catch 结束
        return out;  // 返回结果
    }  // 方法结束

    /**
     * 作用：按维度累加当日模型调用次数与 token 用量
     * 参数：dimension 统计维度；calls 调用次数；inTokens 输入 token；outTokens 输出 token
     * 返回值：无
     * 异常：无（数据库异常内部捕获）
     */
    public static void upsertLlmCost(String dimension, long calls, long inTokens, long outTokens) {  // upsertLlmCost：按维度累加当日调用与 token
        try (Connection c = getConn()) {  // try-with-resources 取连接
            String day = new java.sql.Date(System.currentTimeMillis()).toString();  // day：今天（yyyy-MM-dd）
            String sql = "INSERT INTO llm_cost_daily(day,dimension,calls,input_tokens,output_tokens) VALUES(?,?,?,?,?) "  // INSERT 进每日成本表
                    + "ON DUPLICATE KEY UPDATE calls=VALUES(calls),input_tokens=VALUES(input_tokens),output_tokens=VALUES(output_tokens)";  // 续：同一天同一维度冲突则累加值覆盖
            try (PreparedStatement ps = c.prepareStatement(sql)) {  // 预编译语句
                ps.setString(1, day);  // ① day
                ps.setString(2, dimension);  // ② dimension 维度
                ps.setLong(3, calls);  // ③ calls 调用次数
                ps.setLong(4, inTokens);  // ④ input_tokens
                ps.setLong(5, outTokens);  // ⑤ output_tokens
                ps.executeUpdate();  // 执行
            }  // 资源关闭
        } catch (Exception e) {  // 捕获异常
            System.err.println("[TracePersistence] upsertLlmCost 失败(忽略): " + e.getMessage());  // 打印“失败(忽略)”
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取最近若干天的成本趋势
     * 参数：days 天数
     * 返回值：按天聚合的 Map 列表，异常时为空列表
     * 异常：无
     */
    public static List<Map<String, Object>> costTrend(int days) {  // costTrend：读最近 N 天的成本趋势
        List<Map<String, Object>> out = new ArrayList<>();  // out：结果列表
        try (Connection c = getConn()) {  // try-with-resources 取连接
            String sql = "SELECT day, SUM(calls) calls, SUM(input_tokens) in_tok, SUM(output_tokens) out_tok "  // 按天聚合 calls / in_tok / out_tok
                    + "FROM llm_cost_daily WHERE day >= DATE_SUB(CURDATE(), INTERVAL ? DAY) GROUP BY day ORDER BY day ASC";  // 续：只取最近 ? 天，按天升序
            try (PreparedStatement ps = c.prepareStatement(sql)) {  // 预编译语句
                ps.setInt(1, Math.max(1, Math.min(days, 90)));  // days 夹在 1~90
                try (ResultSet rs = ps.executeQuery()) {  // 执行查询
                    while (rs.next()) {  // 逐行读
                        Map<String, Object> m = new HashMap<>();  // m：一天的数据
                        m.put("day", String.valueOf(rs.getDate("day")));  // day 日期字符串
                        m.put("calls", rs.getLong("calls"));  // 当日总调用次数
                        m.put("inputTokens", rs.getLong("in_tok"));  // 输入 token 合计
                        m.put("outputTokens", rs.getLong("out_tok"));  // 输出 token 合计
                        out.add(m);  // 加入结果
                    }  // while 结束
                }  // 资源关闭
            }  // 资源关闭
        } catch (Exception e) {  // 捕获异常
            System.err.println("[TracePersistence] costTrend 失败(忽略): " + e.getMessage());  // 打印“失败(忽略)”
        }  // catch 结束
        return out;  // 返回结果
    }  // 方法结束
}  // 类结束

