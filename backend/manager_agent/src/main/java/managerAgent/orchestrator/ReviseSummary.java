package managerAgent.orchestrator;

import managerAgent.hook.EventStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reviewer 闭环结构化观测（任务2）：把每轮 REVISE 的触发原因（issue 域/严重度/定位）与结局
 * 存为结构化数据（EventStore extras + TaskMeta），供管理端展示"返工几轮/每轮为何被驳回/最终结局"。
 * 纯静态装配，不调 LLM、不抛异常（观测失败不影响编排）。
 */
public final class ReviseSummary {  // 类声明：Reviewer 闭环的结构化观测记录

    /** 一轮 REVISE 的结构化记录 */
    public static class Round {  // 内部类 Round：一轮 REVISE 的记录
        public int round;                 // 第几轮（1 起）  // round：第几轮（1 起）
        public String verdict;            // PASS / REVISE / PARSE_ERROR  // verdict：PASS / REVISE / PARSE_ERROR
        public int qualityScore = -1;     // Reviewer 质量分（-1=未给）  // qualityScore：质量分（-1=未给）
        public List<Map<String, Object>> issues = new ArrayList<>(); // {type,severity,target,domain,reason}  // issues：问题清单（type/severity/target/domain/reason）
        public String action = "";        // 本轮处置：rework/maxed_rework（末轮仍REVISE记已达上限）/accepted/none  // action：本轮处置 rework/maxed_rework/accepted/none
    }  // 内部类结束

    /**
     * 作用：记录一轮修改（轮次/结论/评分/问题/采取的动作），供前端展示修改过程
     * 参数：sessionId 会话 ID；round 轮次；verdict 评审结论；qualityScore 质量分；issues 问题清单；action 采取的动作
     * 返回值：无
     * 异常：无
     */
    public static void recordRound(String sessionId, int round, String verdict, int qualityScore,  // recordRound：记录一轮修改
                                   List<Map<String, Object>> issues, String action) {  // 续：issues 问题清单、action 采取的动作
        try {  // try：观测失败绝不影响编排
            List<Map<String, Object>> rounds = roundsOf(sessionId);  // 取该会话已有的轮次记录
            Map<String, Object> r = new java.util.LinkedHashMap<>();  // r：本轮记录
            r.put("round", round);  // 轮次
            r.put("verdict", verdict);  // 结论
            r.put("qualityScore", qualityScore);  // 质量分
            if (issues != null) r.put("issues", issues);  // 有问题才写
            r.put("action", action == null ? "" : action);  // 处置动作（null 归空串）
            rounds.add(r);  // 加入记录
            EventStore.putExtra(sessionId, "reviseRounds", rounds);  // 写回 EventStore 附加数据
            // 同步任务级摘要（历史表列用）
            EventStore.TaskMeta meta = EventStore.metaOf(sessionId);  // 取任务级元信息
            if (meta != null) {  // 元信息存在
                meta.reviseRounds = Math.max(meta.reviseRounds, round);  // 返工轮次取最大值
                if (action != null) meta.reviseOutcome = action;  // 记下最终处置
            }  // if 结束
        } catch (Exception ignored) { }  // 捕获异常忽略
    }  // 方法结束

    /**
     * 作用：标记修改流程结束并写入最终结果
     * 参数：sessionId 会话 ID；outcome 最终结果（DONE/FAILED）
     * 返回值：无
     * 异常：无
     */
    public static void finish(String sessionId, String outcome) {  // finish：标记修改流程结束
        try {  // try
            EventStore.putExtra(sessionId, "reviseOutcome", outcome == null ? "" : outcome);  // 写入最终结果
            EventStore.TaskMeta meta = EventStore.metaOf(sessionId);  // 取任务元信息
            if (meta != null && outcome != null && !outcome.isBlank()) meta.reviseOutcome = outcome;  // 非空才赋值
        } catch (Exception ignored) { }  // 捕获异常忽略
    }  // 方法结束

    /**
     * 作用：读取某会话的修改轮次记录
     * 参数：sessionId 会话 ID
     * 返回值：轮次记录列表
     * 异常：无
     */
    @SuppressWarnings("unchecked")  // @SuppressWarnings：类型强转未检查
    public static List<Map<String, Object>> roundsOf(String sessionId) {  // roundsOf：读该会话的轮次记录
        Object cur = EventStore.getExtra(sessionId, "reviseRounds");  // 从 EventStore 取
        if (cur instanceof List) return (List<Map<String, Object>>) cur;  // 是列表就强转返回
        return new ArrayList<>();  // 否则返回空列表
    }  // 方法结束

    /**
     * 作用：把评审问题转成可序列化快照
     * 参数：is 评审问题对象
     * 返回值：问题快照 Map
     * 异常：无
     */
    public static Map<String, Object> issueSnapshot(ReviewVerdict.Issue is) {  // issueSnapshot：评审问题转可序列化快照
        Map<String, Object> m = new java.util.LinkedHashMap<>();  // m：结果 Map（保序）
        if (is == null) return m;  // 空对象返回空 Map
        m.put("type", is.type);  // 问题类型
        m.put("severity", is.severity);  // 严重度
        m.put("target", is.target);  // 定位
        m.put("domain", is.domain);  // 所属域
        m.put("reason", is.reason);  // 说明
        return m;  // 返回快照
    }  // 方法结束
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ReviseSummary() { }  // 私有构造：禁止 new
}  // 类结束
