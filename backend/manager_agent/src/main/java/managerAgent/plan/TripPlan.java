package managerAgent.plan;

import io.agentscope.core.plan.PlanNotebook;
import utils.Config;

/** 自定义Agent自主分解旅游规划任务的PlanNotebook */
public class TripPlan {  // 类声明：封装 AgentScope 的计划本

    /**
     * 作用：读取当前任务计划本（AgentScope 计划能力）
     * 参数：无
     * 返回值：PlanNotebook 实例
     * 异常：无
     */
    public PlanNotebook getPlan() {  // getPlan 签名：每次调用构造一个新的 PlanNotebook
        return PlanNotebook.builder()  // PlanNotebook.builder() 开始构建
                .needUserConfirm(false) // 计划步骤无需用户确认（Agent创建计划后直接执行）  // needUserConfirm(false)：计划步骤不弹用户确认，生成后直接执行
                .maxSubtasks(Config.getInt("PLAN_MAX_SUBTASKS", 5)) // 分解出来的子任务数量限制  // maxSubtasks：最多拆几个子任务，读配置 PLAN_MAX_SUBTASKS（默认 5）
                .build();  // build() 完成构建
    }  // 返回计划本
}  // 类结束

