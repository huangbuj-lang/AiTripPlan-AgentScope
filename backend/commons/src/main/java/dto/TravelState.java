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
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造
public class TravelState {  // 类声明：旅程编排全程的唯一事实源（只在主管进程内流转）

    /** 需求解析产物（含预算总额与 P0~P3 约束） */
    private TripRequest request = new TripRequest();  // request：需求解析产物（含预算与 P0~P3 约束）

    /** 路线专家产物原文 */
    private String routeResult = "";  // routeResult：路线专家产物原文

    /** 行程专家产物原文 */
    private String planResult = "";  // planResult：行程专家产物原文

    /** 跨城途经城市（从路线产物提取，逗号分隔；市内游为空） */
    private String waypoints = "";  // waypoints：跨城途经城市（从路线产物提取，逗号分隔）

    /** 市内景点清单（从行程产物提取，逗号分隔；跨城游为空） */
    private String sights = "";  // sights：市内景点清单（从行程产物提取，逗号分隔）

    /** 三域结构化候选 JSON（景点/酒店/餐饮 Top-3，含来源与置信度；行程子Agent产出，空=无结构化候选） */
    private String candidatesJson = "";  // candidatesJson：三域结构化候选 JSON（空=无结构化候选）

    /** 专家产物是否命中结果缓存 */
    private boolean fromCache = false;  // fromCache：专家产物是否命中结果缓存

    /** ★天骨架模式（2026-09-06）：每天生成的结构化 JSON（拼装前按天存储，REVISE 时只重编涉及天）。
     *  非骨架模式为空列表。 */
    private List<String> dayPlanJson = new ArrayList<>();  // dayPlanJson：按天生成的结构化 JSON（非骨架模式为空列表）
    /**
     * 作用：读取按天保存的行程 JSON 列表
     * 参数：无
     * 返回值：JSON 列表
     * 异常：无
     */

    public List<String> getDayPlanJson() {  // getDayPlanJson：读取按天 JSON 列表
        return dayPlanJson;  // 返回该列表
    }  // 方法结束
    /**
     * 作用：追加一天的计划 JSON（按当前顺序，重载一）
     * 参数：dayJson 当天行程 JSON
     * 返回值：无
     * 异常：无
     */

    public void addDayPlanJson(String dayJson) {  // addDayPlanJson（单参）：按当前顺序追加一天
        if (dayJson != null && !dayJson.isBlank()) {  // 非空才加
            dayPlanJson.add(dayJson);  // 追加
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：按天号插入/覆盖某天的计划 JSON
     * 参数：dayNo 天号；dayJson 当天行程 JSON
     * 返回值：无
     * 异常：无
     */
    public void addDayPlanJson(int dayNo, String dayJson) {  // addDayPlanJson（双参）：按天号插入/覆盖
        if (dayNo < 1) return;  // 天号非法直接返回
        while (dayPlanJson.size() < dayNo) {  // 列表不够长就补齐
            dayPlanJson.add(""); // 补齐空位（含中间失败的天）  // 补空串占位（含中间失败的天）
        }  // while 结束
        dayPlanJson.set(dayNo - 1, dayJson == null ? "" : dayJson);  // 写入该天位置（null 归空串）
    }  // 方法结束

    /** 编排过程摘要（阶段→说明，追溯用） */
    private List<String> trace = new ArrayList<>();  // trace：编排过程轨迹列表（每项“步骤: 细节”）

    /** ★可行性预检：往返里程÷日均 > 用户天数时 = 该目的地所需天数（0=可行）。
     *  仅作标记（不中止流程）——不可行时照常出方案 + 如实标注 + 多方案时替代路线占位。 */
    private int infeasibleDays = 0;  // infeasibleDays：原目的地所需天数（0=可行，非 0 只标记不中止）

    /**
     * 作用：追加一条执行轨迹（步骤 + 细节），供过程展示与复盘
     * 参数：step 步骤名；detail 细节
     * 返回值：无
     * 异常：无
     */
    public void addTrace(String step, String detail) {  // addTrace：追加一条执行轨迹
        trace.add(step + ": " + detail);  // 拼“步骤: 细节”入列表
    }  // 方法结束

    /**
     * 作用：汇总行程中的预算总额
     * 参数：无
     * 返回值：预算总额；无预算数据返回 0
     * 异常：无
     */
    public double budgetTotal() {  // budgetTotal：取需求里的预算总额
        return request == null ? 0 : request.getBudgetTotal();  // 需求为空按 0
    }  // 方法结束

    /**
     * 作用：读取用户硬性约束列表（必去/回避/预算上限等）
     * 参数：无
     * 返回值：约束文本列表
     * 异常：无
     */
    public List<String> hardConstraints() {  // hardConstraints：取 P0 级硬约束文本
        return request == null ? new ArrayList<>() : request.constraintTexts("P0");  // 需求为空返回空列表，否则取 P0 约束
    }  // 方法结束
}  // 类结束
