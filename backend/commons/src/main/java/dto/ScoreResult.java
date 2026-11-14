package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 方案评分结果（PlanScorer 产物）。
 * 六维加权：用户匹配 0.30 + 可行性 0.25 + 时间效率 0.15 + 预算匹配 0.15 + 数据可靠 0.10 + 体验 0.05。
 */
@Data  // @Data：Lombok 自动生成 getter/setter/toString
@NoArgsConstructor  // @NoArgsConstructor：自动生成无参构造（Jackson 反序列化需要）
public class ScoreResult {  // 类声明：评分结果 DTO

    /** 加权总分（0~100 四舍五入） */
    private int total = 0;  // total 初始 0

    /** 各维度原始分（key：match/feasible/efficiency/budget/reliability/experience） */
    private Map<String, Integer> breakdown = new LinkedHashMap<>();  // breakdown 用 LinkedHashMap 保证事件流展示时维度顺序稳定

    /** 一行摘要（事件流展示用） */
    private String summary = "";  // summary 初始空串

    /**
     * 作用：生成评分结果的一句话摘要（多方案对比展示用）
     * 参数：无
     * 返回值：摘要文本，如"总分 86：可行性优 / 体验良"
     * 异常：无
     */
    public ScoreResult buildSummary() {  // buildSummary 签名：把分数拼成摘要
        summary = "总分" + total  // 摘要 = “总分” + total
                + " 匹配" + breakdown.getOrDefault("match", 0)  // ＋ 匹配维度分（缺失按 0）
                + " 可行" + breakdown.getOrDefault("feasible", 0)  // ＋ 可行维度分
                + " 时效" + breakdown.getOrDefault("efficiency", 0)  // ＋ 时效维度分
                + " 预算" + breakdown.getOrDefault("budget", 0)  // ＋ 预算维度分
                + " 可靠" + breakdown.getOrDefault("reliability", 0)  // ＋ 可靠维度分
                + " 体验" + breakdown.getOrDefault("experience", 0);  // ＋ 体验维度分
        return this;  // 返回自身（便于链式调用）
    }  // 方法结束
}  // 类结束

