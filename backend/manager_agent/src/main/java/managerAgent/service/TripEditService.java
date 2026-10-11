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
@Slf4j
@Service
public class TripEditService {

    @Resource
    private TripEditRepository tripEditRepository;

    /** 读取某条行程的结构化 JSON（仅本人），供编辑页加载 */
    public Map<String, Object> getForEdit(Long id, long userId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = tripEditRepository.findForEdit(id, userId);
        if (rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "行程不存在或无权访问");
            return result;
        }
        Map<String, Object> row = rows.get(0);
        TripPlanData plan = null;
        if (row.get("plan_json") != null) {
            plan = JsonUtils.parse(String.valueOf(row.get("plan_json")), TripPlanData.class);
        }
        //plan_json 列可能为空（旧数据/前端早期版本只把 JSON 追加在 plan_content 尾部）--从 plan_content 兜底解析
        if (plan == null && row.get("plan_content") != null) {
            plan = parsePlanFromContent(String.valueOf(row.get("plan_content")));
        }
        if (plan == null) {
            plan = new TripPlanData();
            plan.setTitle(String.valueOf(row.getOrDefault("city", "")));
            plan.setDays(row.get("days") == null ? 1 : ((Number) row.get("days")).intValue());
        }
        result.put("success", true);
        result.put("plan", plan);
        return result;
    }

    /** 旧数据的兜底解析：正文尾部 <!--ATPLAN_JSON_START-->...<!--ATPLAN_JSON_END--> 里的 JSON */
    private TripPlanData parsePlanFromContent(String content) {
        final String mark = "<!--ATPLAN_JSON_START-->";
        final String end = "<!--ATPLAN_JSON_END-->";
        int i = content.indexOf(mark);
        if (i < 0) {
            return null;
        }
        int j = content.indexOf(end, i);
        if (j <= i) {
            return null;
        }
        String raw = content.substring(i + mark.length(), j).trim();
        Object parsed = JsonUtils.parse(raw, Map.class);
        if (parsed instanceof Map<?, ?> m && m.containsKey("__planSet") && m.get("set") instanceof Map<?, ?> setMap
                && setMap.get("plans") instanceof List<?> plans && !plans.isEmpty()) {
            //PlanSet（多方案）：编辑页用第一个方案
            return JsonUtils.parse(JsonUtils.toJson(plans.get(0)), TripPlanData.class);
        }
        return JsonUtils.parse(raw, TripPlanData.class);
    }

    /** 保存编辑后的结构化行程（覆盖 plan_json + 重渲染 markdown，仅本人） */
    public Map<String, Object> updateTrip(Long id, long userId, String planJson) {
        Map<String, Object> result = new HashMap<>();
        TripPlanData plan = JsonUtils.parse(planJson, TripPlanData.class);
        if (plan == null) {
            result.put("success", false);
            result.put("message", "行程数据格式非法");
            return result;
        }
        int rows = tripEditRepository.updateFull(id, userId, planJson, renderMarkdown(plan),
                plan.getDestination().isBlank() ? null : plan.getDestination(),
                plan.getDays() < 1 ? 1 : plan.getDays());
        result.put("success", rows > 0);
        result.put("message", rows > 0 ? "已保存" : "保存失败（行程不存在或无权修改）");
        return result;
    }

    /** 增量重排某一天：用 LLM 重新生成该天 timeline 区块（可带调整要求），仅本人 */
    public Map<String, Object> regenerateDay(Long id, long userId, int dayIndex, String instruction) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = tripEditRepository.findPlanJsonAndDays(id, userId);
        if (rows.isEmpty()) {
            result.put("success", false);
            result.put("message", "行程不存在或无权访问");
            return result;
        }
        Map<String, Object> row = rows.get(0);
        TripPlanData plan = row.get("plan_json") == null ? new TripPlanData()
                : JsonUtils.parse(String.valueOf(row.get("plan_json")), TripPlanData.class);
        if (plan == null) {
            plan = new TripPlanData();
        }
        //定位第 dayIndex 天的 timeline 区块
        int timelineIdx = 0;
        Section target = null;
        int targetSectionIdx = -1;
        for (int i = 0; i < plan.getSections().size(); i++) {
            Section sec = plan.getSections().get(i);
            if ("timeline".equals(sec.getType())) {
                if (timelineIdx == dayIndex) {
                    target = sec;
                    targetSectionIdx = i;
                    break;
                }
                timelineIdx++;
            }
        }
        if (target == null) {
            result.put("success", false);
            result.put("message", "未找到第 " + (dayIndex + 1) + " 天的行程");
            return result;
        }
        //调 LLM 重排该天
        String dayTitle = target.getTitle().isBlank() ? ("第" + (dayIndex + 1) + "天") : target.getTitle();
        Section newSection = JsonUtils.extractAndParse(regenerateDaySection(plan, dayTitle, instruction), Section.class);
        if (newSection == null || newSection.getItems().isEmpty()) {
            result.put("success", false);
            result.put("message", "重排失败，请稍后重试");
            return result;
        }
        newSection.setType("timeline");
        if (newSection.getTitle().isBlank()) {
            newSection.setTitle(dayTitle);
        }
        plan.getSections().set(targetSectionIdx, newSection);
        tripEditRepository.updatePlanJsonAndContent(id, userId, JsonUtils.toJson(plan), renderMarkdown(plan));
        result.put("success", true);
        result.put("message", dayTitle + " 已重新规划");
        result.put("plan", plan);
        return result;
    }

    /** 调 LLM 生成某天的新行程区块（返回 JSON 字符串） */
    private String regenerateDaySection(TripPlanData plan, String dayTitle, String instruction) {
        String sysPrompt = AgentPrompts.tripEditor();
        String userPrompt = "行程背景：" + plan.getTitle() + "，目的地 " + plan.getDestination()
                + "，共 " + plan.getDays() + " 天。\n请重新规划「" + dayTitle + "」"
                + (instruction == null || instruction.isBlank() ? "" : "，调整要求：" + instruction) + "。";
        return callModelJson(sysPrompt, userPrompt);
    }

    private String callModelJson(String sysPrompt, String userPrompt) {
        Msg system = Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(sysPrompt).build()).build();
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(userPrompt).build()).build();
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of("response_format", Map.of("type", "json_object")))
                .build();
        try {
            ChatResponse resp = LlmClient.model().stream(List.of(system, user), null, options)
                    .blockLast(ModelTimeouts.tripEditDuration());
            StringBuilder sb = new StringBuilder();
            if (resp != null) {
                for (ContentBlock block : resp.getContent()) {
                    if (block instanceof TextBlock tb) {
                        sb.append(tb.getText());
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("重排某天 LLM 调用失败: {}", e.getMessage());
            return "";
        }
    }

    /** 结构化行程 -> markdown（降级字段，保持 plan_content 与 plan_json 一致） */
    private String renderMarkdown(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.getTitle()).append("\n\n");
        if (!plan.getWeather().isBlank()) {
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");
        }
        for (Section sec : plan.getSections()) {
            sb.append("## ").append(sec.getTitle()).append("\n");
            for (SectionItem it : sec.getItems()) {
                sb.append("- ");
                switch (sec.getType()) {
                    case "timeline" -> sb.append("**").append(it.getTime()).append("** ")
                            .append(it.getPlace()).append(" ").append(it.getActivity());
                    case "transport" -> sb.append(it.getFrom()).append(" → ").append(it.getTo())
                            .append("（").append(it.getWay()).append("，").append(it.getDuration()).append("）");
                    case "stay" -> {
                        String kind = it.getKind().isBlank() ? it.getCategory() : it.getKind();
                        String detail = (kind + " " + it.getPrice()).trim();
                        sb.append(it.getName());
                        if (!detail.isBlank()) sb.append("（").append(detail).append("）");
                    }
                    case "tips" -> sb.append(it.getKind().isBlank() ? "" : it.getKind() + "：").append(it.getText());
                    default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
