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

    /** 专家产物是否命中结果缓存 */
    private boolean fromCache = false;

    /** 编排过程摘要（阶段→说明，追溯用） */
    private List<String> trace = new ArrayList<>();

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
