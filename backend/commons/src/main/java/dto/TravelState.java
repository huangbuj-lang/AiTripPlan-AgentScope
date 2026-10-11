package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一旅行状态中心（编排全程唯一事实源）。
 * 解析产物、专家产物、跨 Agent 提取结果全部沉淀于此，
 * 下游任务文本/聚合/校验/评分一律从状态派生，禁止各自理解原始用户输入。
 * 仅在主管进程内流转，不直接序列化给前端。
 */
@Data
@NoArgsConstructor
public class TravelState {

    /** 需求解析产物（含预算总额与 P0~P3 约束） */
    private TripRequest request = new TripRequest();

    /** 路线专家产物原文 */
    private String routeResult = "";

    /** 行程专家产物原文 */
    private String planResult = "";

    /** 跨城途经城市（从路线产物提取，逗号分隔；市内游为空） */
    private String waypoints = "";

    /** 市内景点清单（从行程产物提取，逗号分隔；跨城游为空） */
    private String sights = "";

    /** 三域结构化候选 JSON（景点/酒店/餐饮 Top-3，含来源与置信度；行程子Agent产出，空=无结构化候选） */
    private String candidatesJson = "";

    /** 专家产物是否命中结果缓存 */
    private boolean fromCache = false;

    /** ★天骨架模式（2026-09-06）：每天生成的结构化 JSON（拼装前按天存储，REVISE 时只重编涉及天）。
     *  非骨架模式为空列表。 */
    private List<String> dayPlanJson = new ArrayList<>();

    public List<String> getDayPlanJson() {
        return dayPlanJson;
    }

    public void addDayPlanJson(String dayJson) {
        if (dayJson != null && !dayJson.isBlank()) {
            dayPlanJson.add(dayJson);
        }
    }

    /**
     * ★2026-09-11 降级分级：按【天号】写入天级 JSON，缺天留空位（空字符串占位），
     * 这样 list 的下标始终 = 天号-1，拼装/REVISE 取天时不会错位。
     *
     * @param dayNo 天号（1 起）
     * @param dayJson 该天的 TripPlanData JSON；null/空白 = 该天失败（占位）
     */
    public void addDayPlanJson(int dayNo, String dayJson) {
        if (dayNo < 1) return;
        while (dayPlanJson.size() < dayNo) {
            dayPlanJson.add(""); // 补齐空位（含中间失败的天）
        }
        dayPlanJson.set(dayNo - 1, dayJson == null ? "" : dayJson);
    }

    /** 编排过程摘要（阶段→说明，追溯用） */
    private List<String> trace = new ArrayList<>();

    /** ★可行性预检：往返里程÷日均 > 用户天数时 = 该目的地所需天数（0=可行）。
     *  仅作标记（不中止流程）——不可行时照常出方案 + 如实标注 + 多方案时替代路线占位。 */
    private int infeasibleDays = 0;

    /** 追加一条过程摘要 */
    public void addTrace(String step, String detail) {
        trace.add(step + ": " + detail);
    }

    /** 预算总额（元；0=未识别） */
    public double budgetTotal() {
        return request == null ? 0 : request.getBudgetTotal();
    }

    /** P0 硬约束文本列表 */
    public List<String> hardConstraints() {
        return request == null ? new ArrayList<>() : request.constraintTexts("P0");
    }
}
