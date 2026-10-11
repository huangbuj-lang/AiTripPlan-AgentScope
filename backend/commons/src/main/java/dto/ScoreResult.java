package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 方案评分结果（PlanScorer 产物）。
 * 六维加权：用户匹配 0.30 + 可行性 0.25 + 时间效率 0.15 + 预算匹配 0.15 + 数据可靠 0.10 + 体验 0.05。
 */
@Data
@NoArgsConstructor
public class ScoreResult {

    /** 加权总分（0~100 四舍五入） */
    private int total = 0;

    /** 各维度原始分（key：match/feasible/efficiency/budget/reliability/experience） */
    private Map<String, Integer> breakdown = new LinkedHashMap<>();

    /** 一行摘要（事件流展示用） */
    private String summary = "";

    /** 生成摘要（按 breakdown 拼可读文本） */
    public ScoreResult buildSummary() {
        summary = "总分" + total
                + " 匹配" + breakdown.getOrDefault("match", 0)
                + " 可行" + breakdown.getOrDefault("feasible", 0)
                + " 时效" + breakdown.getOrDefault("efficiency", 0)
                + " 预算" + breakdown.getOrDefault("budget", 0)
                + " 可靠" + breakdown.getOrDefault("reliability", 0)
                + " 体验" + breakdown.getOrDefault("experience", 0);
        return this;
    }
}
