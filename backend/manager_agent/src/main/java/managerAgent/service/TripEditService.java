package managerAgent.service;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.repository.TripEditRepository;
import org.springframework.stereotype.Service;
import utils.AgentPrompts;
import utils.JsonUtils;
import utils.LlmClient;
import utils.ModelTimeouts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程二次编辑业务（阶段四 P2）：读取/保存结构化 TripPlanData、手动微调、增量重排某一天。
 * 只做业务，SQL 在 TripEditRepository。
 */
@Slf4j  // @Slf4j
@Service  // @Service
public class TripEditService {  // 类声明

    @Resource  // @Resource
    private TripEditRepository tripEditRepository;  // 注入仓储

    /**
     * 作用：读取待编辑的行程（校验归属后返回可编辑的行程结构）
     * 参数：id 行程 ID；userId 用户 ID
     * 返回值：含行程结构与 markdown 的 Map；不存在或无权限时返回错误标识
     * 异常：无
     */
    public Map<String, Object> getForEdit(Long id, long userId) {  // getForEdit 签名
        Map<String, Object> result = new HashMap<>();  // 准备结果 Map
        List<Map<String, Object>> rows = tripEditRepository.findForEdit(id, userId);  // 查库：按 id + userId 取（归属写在 SQL 条件里，越权查不到）
        if (rows.isEmpty()) {  // 查到 0 行
            result.put("success", false);  // success=false
            result.put("message", "行程不存在或无权访问");  // 错误文案
            return result;  // 直接返回
        }  // if 结束
        Map<String, Object> row = rows.get(0);  // 取第一行
        TripPlanData plan = null;  // plan 初始 null
        if (row.get("plan_json") != null) {  // 有 plan_json 列
            plan = JsonUtils.parse(String.valueOf(row.get("plan_json")), TripPlanData.class);  // 直接反序列化成 TripPlanData
        }  // if 结束
        //plan_json 列可能为空（旧数据/前端早期版本只把 JSON 追加在 plan_content 尾部）--从 plan_content 兜底解析
        if (plan == null && row.get("plan_content") != null) {  // plan 仍为 null 且有 plan_content
            plan = parsePlanFromContent(String.valueOf(row.get("plan_content")));  // 从 markdown 里抽 JSON 段解析
        }  // if 结束
        if (plan == null) {  // 两种都解析不出来（脏数据）
            plan = new TripPlanData();  // 新建空行程，
            plan.setTitle(String.valueOf(row.getOrDefault("city", "")));  // 标题用城市名兜底
            plan.setDays(row.get("days") == null ? 1 : ((Number) row.get("days")).intValue());  // 天数用库里的值，null 按 1 天
        }  // if 结束
        result.put("success", true);  // success=true
        result.put("plan", plan);  // 返回 plan（前端按这个结构渲染编辑器）
        return result;  // 返回结果
    }  // 方法结束

    /**
     * 作用：把数据库里的行程内容解析成结构化行程
     * 参数：content 行程 JSON 文本
     * 返回值：TripPlanData；解析失败返回 null
     * 异常：无
     */
    private TripPlanData parsePlanFromContent(String content) {  // parsePlanFromContent 签名
        final String mark = "<!--ATPLAN_JSON_START-->";  // 起始标记 ATPlan JSON 块
        final String end = "<!--ATPLAN_JSON_END-->";  // 结束标记
        int i = content.indexOf(mark);  // 找起点
        if (i < 0) {  // 没有起点
            return null;  // 返回 null（不是新格式）
        }  // if 结束
        int j = content.indexOf(end, i);  // 从起点之后找终点
        if (j <= i) {  // 终点不合法
            return null;  // 返回 null
        }  // if 结束
        String raw = content.substring(i + mark.length(), j).trim();  // 截取标记之间的 JSON 文本并 trim
        Object parsed = JsonUtils.parse(raw, Map.class);  // 先按 Map 解析（判断是不是多方案包）
        if (parsed instanceof Map<?, ?> m && m.containsKey("__planSet") && m.get("set") instanceof Map<?, ?> setMap  // 是多方案包（含 __planSet 且 set.plans 非空）
                && setMap.get("plans") instanceof List<?> plans && !plans.isEmpty()) {  // 续行：plans 非空
            //PlanSet（多方案）：编辑页用第一个方案
            return JsonUtils.parse(JsonUtils.toJson(plans.get(0)), TripPlanData.class);  // 取 plans[0] 转成 TripPlanData
        }  // if 结束
        return JsonUtils.parse(raw, TripPlanData.class);  // 普通单方案：直接反序列化
    }  // 方法结束

    /**
     * 作用：保存编辑后的行程（重算 markdown 与天数等派生字段）
     * 参数：id 行程 ID；userId 用户 ID；planJson 编辑后的行程 JSON
     * 返回值：含成功标记与更新字段的 Map
     * 异常：无（失败返回 success=false 的 Map）
     */
    public Map<String, Object> updateTrip(Long id, long userId, String planJson) {  // updateTrip 签名
        Map<String, Object> result = new HashMap<>();  // 准备结果 Map
        TripPlanData plan = JsonUtils.parse(planJson, TripPlanData.class);  // 把前端提交的 JSON 解析成 TripPlanData
        if (plan == null) {  // 解析失败（格式非法）
            result.put("success", false);  // success=false
            result.put("message", "行程数据格式非法");  // 错误文案
            return result;  // 返回
        }  // if 结束
        int rows = tripEditRepository.updateFull(id, userId, planJson, renderMarkdown(plan),  // 落库：整份 plan_json + 重算的 markdown + 目的地 + 天数
                plan.getDestination().isBlank() ? null : plan.getDestination(),  // 参数：目的地为空传 null（不覆盖原值）
                plan.getDays() < 1 ? 1 : plan.getDays());  // 参数：天数小于 1 时按 1
        result.put("success", rows > 0);  // success = 受影响行数 > 0（0 行说明行程不存在或不是本人的）
        result.put("message", rows > 0 ? "已保存" : "保存失败（行程不存在或无权修改）");  // message：成功“已保存”，失败说明原因
        return result;  // 返回
    }  // 方法结束

    /**
     * 作用：单天重排：按用户指令只让模型重写某一天，替换后回存
     * 参数：id 行程 ID；userId 用户 ID；dayIndex 天下标（0 基）；instruction 修改指令
     * 返回值：含新的一天与整体结果的 Map
     * 异常：无（失败返回 success=false 的 Map）
     */
    public Map<String, Object> regenerateDay(Long id, long userId, int dayIndex, String instruction) {  // regenerateDay 签名
        Map<String, Object> result = new HashMap<>();  // 准备结果 Map
        List<Map<String, Object>> rows = tripEditRepository.findPlanJsonAndDays(id, userId);  // 查库：只要 plan_json 与天数（含归属校验）
        if (rows.isEmpty()) {  // 查不到
            result.put("success", false);  // success=false
            result.put("message", "行程不存在或无权访问");  // 错误文案
            return result;  // 返回
        }  // if 结束
        Map<String, Object> row = rows.get(0);  // 取第一行
        TripPlanData plan = row.get("plan_json") == null ? new TripPlanData()  // 有 plan_json 就解析，没有就新建空行程
                : JsonUtils.parse(String.valueOf(row.get("plan_json")), TripPlanData.class);  // 续行：解析
        if (plan == null) {  // 解析失败（脏数据）
            plan = new TripPlanData();  // 新建空行程兜底
        }  // if 结束
        //定位第 dayIndex 天的 timeline 区块
        int timelineIdx = 0;  // timeline 计数（第几个 timeline 就是第几天）
        Section target = null;  // target：要改的那天
        int targetSectionIdx = -1;  // targetSectionIdx：它在 sections 里的下标
        for (int i = 0; i < plan.getSections().size(); i++) {  // 遍历所有区块
            Section sec = plan.getSections().get(i);  // 取一个区块
            if ("timeline".equals(sec.getType())) {  // 是 timeline 类型
                if (timelineIdx == dayIndex) {  // 计数正好等于 dayIndex
                    target = sec;  // 记下 target
                    targetSectionIdx = i;  // 记下下标
                    break;  // 跳出循环
                }  // if 结束
                timelineIdx++;  // 计数 +1
            }  // if 结束
        }  // for 结束
        if (target == null) {  // 没找到那一天
            result.put("success", false);  // success=false
            result.put("message", "未找到第 " + (dayIndex + 1) + " 天的行程");  // 错误文案带上第几天（1 基）
            return result;  // 返回
        }  // if 结束
        //调 LLM 重排该天
        String dayTitle = target.getTitle().isBlank() ? ("第" + (dayIndex + 1) + "天") : target.getTitle();  // dayTitle：原标题为空就用“第 N 天”
        Section newSection = JsonUtils.extractAndParse(regenerateDaySection(plan, dayTitle, instruction), Section.class);  // 调模型重写并解析成 Section（extractAndParse 会剥掉 ``` 包裹）
        if (newSection == null || newSection.getItems().isEmpty()) {  // 解析失败或没有条目
            result.put("success", false);  // success=false
            result.put("message", "重排失败，请稍后重试");  // 错误文案
            return result;  // 返回
        }  // if 结束
        newSection.setType("timeline");  // 强制类型为 timeline（防止模型写错）
        if (newSection.getTitle().isBlank()) {  // 模型没给标题
            newSection.setTitle(dayTitle);  // 用 dayTitle 补上
        }  // if 结束
        plan.getSections().set(targetSectionIdx, newSection);  // 把新的一天替换进去
        tripEditRepository.updatePlanJsonAndContent(id, userId, JsonUtils.toJson(plan), renderMarkdown(plan));  // 回存：plan_json + 重算 markdown（只更新这两列）
        result.put("success", true);  // success=true
        result.put("message", dayTitle + " 已重新规划");  // message：“xxx 已重新规划”
        result.put("plan", plan);  // 返回整份新行程（前端直接刷新）
        return result;  // 返回结果
    }  // 方法结束

    /**
     * 作用：调用模型重写某一天，返回该天的 JSON
     * 参数：plan 完整行程；dayTitle 当天标题；instruction 修改指令
     * 返回值：单天 JSON 文本；失败返回空串
     * 异常：无（异常内部捕获）
     */
    private String regenerateDaySection(TripPlanData plan, String dayTitle, String instruction) {  // regenerateDaySection 签名
        String sysPrompt = AgentPrompts.tripEditor();  // 系统提示词：改行程专用模板
        String userPrompt = "行程背景：" + plan.getTitle() + "，目的地 " + plan.getDestination()  // 用户提示词：行程背景（标题、目的地、天数）
                + "，共 " + plan.getDays() + " 天。\n请重新规划「" + dayTitle + "」"  // 续：请重新规划「dayTitle」
                + (instruction == null || instruction.isBlank() ? "" : "，调整要求：" + instruction) + "。";  // 续：有指令就加“调整要求”，没有就不加
        return callModelJson(sysPrompt, userPrompt);  // 调 callModelJson 并返回
    }  // 方法结束
    /**
     * 作用：改行程专用 LLM 调用（要求 JSON 输出）
     * 参数：sysPrompt 系统提示词；userPrompt 用户提示词
     * 返回值：模型返回文本（可能为空串）
     * 异常：无（异常已在 LlmClient 内部捕获）
     */

    private String callModelJson(String sysPrompt, String userPrompt) {  // callModelJson 签名
        Msg system = Msg.builder().role(MsgRole.SYSTEM)  // 构造 SYSTEM 消息
                .content(TextBlock.builder().text(sysPrompt).build()).build();  // 续行：内容块是 sysPrompt
        Msg user = Msg.builder().role(MsgRole.USER)  // 构造 USER 消息
                .content(TextBlock.builder().text(userPrompt).build()).build();  // 续行：内容块是 userPrompt
        GenerateOptions options = GenerateOptions.builder()  // 构造生成参数
                .additionalBodyParams(Map.of("response_format", Map.of("type", "json_object")))  // response_format=json_object：要求模型输出 JSON
                .build();  // build 参数
        try {  // try
            ChatResponse resp = LlmClient.model().stream(List.of(system, user), null, options)  // 流式调用模型（SafeModel 包装，带限流/熔断）
                    .blockLast(ModelTimeouts.tripEditDuration());  // blockLast 等到流结束，超时用 tripEditDuration 预算
            StringBuilder sb = new StringBuilder();  // 准备拼结果
            if (resp != null) {  // 有响应
                for (ContentBlock block : resp.getContent()) {  // 遍历内容块
                    if (block instanceof TextBlock tb) {  // 是文本块
                        sb.append(tb.getText());  // 拼进 StringBuilder
                    }  // if 结束
                }  // for 结束
            }  // if 结束
            return sb.toString();  // 返回文本
        } catch (Exception e) {  // 异常
            log.error("重排某天 LLM 调用失败: {}", e.getMessage());  // 打 error 日志
            return "";  // 返回空串（上层按“重排失败”处理）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：把结构化行程渲染成 markdown
     * 参数：plan 行程
     * 返回值：markdown 文本
     * 异常：无
     */
    private String renderMarkdown(TripPlanData plan) {  // renderMarkdown 签名
        StringBuilder sb = new StringBuilder();  // 准备输出
        sb.append("# ").append(plan.getTitle()).append("\n\n");  // 一级标题：行程标题
        if (!plan.getWeather().isBlank()) {  // 有天气就加一行
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");  // 续行：天气
        }  // if 结束
        for (Section sec : plan.getSections()) {  // 遍历区块
            sb.append("## ").append(sec.getTitle()).append("\n");  // 二级标题：区块标题
            for (SectionItem it : sec.getItems()) {  // 遍历区块内条目
                sb.append("- ");  // 每条以 "- " 开头
                switch (sec.getType()) {  // 按区块类型渲染
                    case "timeline" -> sb.append("**").append(it.getTime()).append("** ")  // timeline：**时间** 地点 活动
                            .append(it.getPlace()).append(" ").append(it.getActivity());  // 续行
                    case "transport" -> sb.append(it.getFrom()).append(" → ").append(it.getTo())  // transport：从 → 到（方式，时长）
                            .append("（").append(it.getWay()).append("，").append(it.getDuration()).append("）");  // 续行
                    case "stay" -> {  // stay：名称（类型 价格）
                        String kind = it.getKind().isBlank() ? it.getCategory() : it.getKind();  // kind 为空用 category，否则用 kind
                        String detail = (kind + " " + it.getPrice()).trim();  // detail = 类型 + 价格（去首尾空格）
                        sb.append(it.getName());  // 先输出名称
                        if (!detail.isBlank()) sb.append("（").append(detail).append("）");  // 有 detail 才加括号
                    }  // },
                    case "tips" -> sb.append(it.getKind().isBlank() ? "" : it.getKind() + "：").append(it.getText());  // tips：有 kind 就“kind：”，再输出正文
                    default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());  // default：活动+地点+文本兜底
                }  // switch 结束
                sb.append("\n");  // 换行
            }  // for 结束
            sb.append("\n");  // 区块之间空一行
        }  // for 结束
        return sb.toString().trim();  // 返回 trim 后的 markdown
    }  // 方法结束
}  // 类结束

