package managerAgent.plan;

import io.agentscope.core.plan.PlanNotebook;
import utils.Config;

/**
 * 自定义Agent自主分解旅游规划任务的PlanNotebook
 */
public class TripPlan {

    /**
     * 自定义 PlanNotebook 实例
     */
    public PlanNotebook getPlan() {
        return PlanNotebook.builder()
                .needUserConfirm(false) // 计划步骤无需用户确认（Agent创建计划后直接执行）
                .maxSubtasks(Config.getInt("PLAN_MAX_SUBTASKS", 5)) // 分解出来的子任务数量限制
                .build();
    }
}
