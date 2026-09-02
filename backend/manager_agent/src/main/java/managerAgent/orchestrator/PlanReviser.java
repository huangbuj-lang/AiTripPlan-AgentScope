package managerAgent.orchestrator;

import dto.TripPlanData;
import dto.TripRequest;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.tool.RemoteAgentTool;
import utils.AgentPrompts;
import utils.AgentUtils;
import utils.CandidateUtil;
import utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话式增量修正器（PDF §十三：用户中途修改 → 影响分析 → 只重跑受影响 Agent → 再校验 → 合并）。
 * 不推翻整份行程：受影响域定向重查候选 → 仅受影响天重聚合 → 确定性校验 + 聚焦审查 → 存档修订记录。
 * 任一环节失败保留上一版交付（修正失败不劣化）。
 */
@Slf4j
public class PlanReviser {

    private final String sessionId;
    private final Model model;
    private final RemoteAgentTool remoteTool;

    public PlanReviser(String sessionId) {
        this.sessionId = sessionId;
        this.model = AgentUtils.buildModel();
        this.remoteTool = new RemoteAgentTool();
        this.remoteTool.setSessionId(sessionId);
    }

    /** 影响分析结果（LLM 结构化产物） */
    public static class Impact {
        public String understanding = "";
        public String changeType = "";
        public List<String> targetDays = new ArrayList<>();
        public List<String> domains = new ArrayList<>();

        String daysText() {
            return targetDays.isEmpty() ? "overall" : String.join("、", targetDays);
        }
    }

    /**
     * 执行增量修正。
     *
     * @param instruction 用户修改指令（如"第三天不去稻城了，改去四姑娘山"）
     * @param prev        上一版行程（含候选存档 candidatesJson）
     * @return 修正后的行程；无法修正时返回 null（调用方提示保留原方案）
     */
    public TripPlanData revise(String instruction, TripPlanData prev) {
        // ① 影响分析：判定修改牵动哪些天/哪些域（最小影响范围，宁少勿多）
        EventStore.add(sessionId, "修订", "影响分析：正在解析修改指令的影响范围...");
        utils.SafeModel.setStage("修订影响分析");
        Impact impact = analyzeImpact(instruction, prev);
        if (impact == null) {
            EventStore.add(sessionId, "修订", "影响分析失败，按全局修订处理");
            impact = new Impact();
            impact.understanding = instruction;
            impact.targetDays.add("overall");
        }
        EventStore.add(sessionId, "修订", "影响范围：" + impact.daysText()
                + "｜受影响域：" + (impact.domains.isEmpty() ? "无（仅重排）" : String.join("、", impact.domains))
                + "（" + impact.understanding + "）");
        checkCancel();

        // ② 受影响域定向重查（只重跑受影响 Agent，PDF §十三核心；其余域沿用已有候选）
        String candidatesJson = prev.getCandidatesJson() == null ? "" : prev.getCandidatesJson();
        if (!impact.domains.isEmpty()) {
            candidatesJson = refetchDomains(prev, impact, instruction, candidatesJson);
        }

        // ③ 局部重聚合：只改受影响天，其余天逐字保留
        EventStore.add(sessionId, "修订", "局部重聚合：只重排 " + impact.daysText() + "，其余天保持不变...");
        utils.SafeModel.setStage("修订聚合");
        TripRequest req = minimalRequest(prev);
        TripPlanData revised = refoldPlan(prev, instruction, impact, candidatesJson, "");
        if (revised == null) {
            EventStore.add(sessionId, "修订", "局部重聚合失败，保留原方案");
            return null;
        }

        // ④ 确定性校验（含新增的营业时间/酒店位置检查）→ FAIL 带反馈重聚合一次
        dto.ValidationReport vr = safeValidate(req, revised, candidatesJson);
        EventStore.add(sessionId, "校验", "修订后校验结果 " + vr.summary());
        if ("FAIL".equals(vr.getStatus())) {
            EventStore.add(sessionId, "修订", "校验未通过，带反馈重聚合一次...");
            TripPlanData fixed = refoldPlan(prev, instruction, impact, candidatesJson,
                    "★校验反馈（必须修复）：" + vr.summary());
            if (fixed != null) {
                revised = fixed;
                vr = safeValidate(req, revised, candidatesJson);
                EventStore.add(sessionId, "校验", "二次校验结果 " + vr.summary());
            }
        }
        checkCancel();

        // ⑤ 聚焦审查：Reviewer 只盯受影响天与本次修改
        utils.SafeModel.setStage("修订审查");
        ReviewVerdict v = reviewRevise(req, instruction, impact, revised, vr);
        if (v != null && v.needsRevise()) {
            EventStore.add(sessionId, "审查", "修订审查 REVISE：" + v.feedbackText());
            TripPlanData fixed = refoldPlan(prev, instruction, impact, candidatesJson,
                    "★审查反馈（必须修复）：" + v.feedbackText());
            if (fixed != null) {
                revised = fixed;
                EventStore.add(sessionId, "审查", "按审查反馈完成二次修正");
            }
        } else if (v != null) {
            EventStore.add(sessionId, "审查", "修订审查通过"
                    + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : ""));
        }

        // ⑥ 评分 + 证据链 + 修订记录存档
        revised.setCandidatesJson(candidatesJson);
        revised.setEvidence(CandidateUtil.evidenceList(CandidateUtil.parse(candidatesJson)));
        revised.setRevision(impact.understanding + "（影响：" + impact.daysText()
                + (impact.domains.isEmpty() ? "" : "/" + String.join("+", impact.domains)) + "）");
        revised.setMarkdown(renderMarkdown(revised));
        try {
            dto.ScoreResult sr = utils.PlanScorer.score(req, revised, vr, true, true);
            EventStore.add(sessionId, "评分", "修订后 " + sr.getSummary());
        } catch (Exception e) {
            log.warn("修订评分异常(忽略): {}", e.getMessage());
        }
        EventStore.add(sessionId, "完成", "增量修正完成（仅重排 " + impact.daysText() + "，其余天未改动）");
        return revised;
    }

    // ==================== 影响分析 ====================

    /** LLM 影响分析：修改指令 + 行程概要 → 影响范围结构化 */
    private Impact analyzeImpact(String instruction, TripPlanData prev) {
        String user = "【修改指令】" + instruction
                + "\n\n【行程概要】\n" + planDigest(prev)
                + (prev.getCandidatesJson() == null || prev.getCandidatesJson().isBlank() ? ""
                        : "\n\n【已有候选摘要】\n" + CandidateUtil.toPromptText(CandidateUtil.parse(prev.getCandidatesJson()), 3));
        String out = callModelJson(AgentPrompts.reviseImpactAnalyzer(), user);
        Map<?, ?> node = JsonUtils.extractAndParse(out, Map.class);
        if (node == null) return null;
        Impact impact = new Impact();
        impact.understanding = str(node.get("understanding"));
        impact.changeType = str(node.get("changeType"));
        if (node.get("targetDays") instanceof List<?> days) {
            for (Object o : days) {
                String d = str(o);
                if (!d.isBlank()) impact.targetDays.add(d);
            }
        }
        if (node.get("domains") instanceof List<?> ds) {
            for (Object o : ds) {
                String d = str(o).trim().toLowerCase();
                if (d.equals("attraction") || d.equals("hotel") || d.equals("dining") || d.equals("transport")) {
                    impact.domains.add(d);
                }
            }
        }
        if (impact.understanding.isBlank()) impact.understanding = instruction;
        return impact;
    }

    // ==================== 受影响域定向重查 ====================

    /** 直连行程子服务内部候选端点（修订模式）：只重查受影响域，其余沿用已有候选；失败返回原候选。
     *  绕过门面 ReAct（省两轮大 prompt LLM），参数从上一版行程精确构造。 */
    private String refetchDomains(TripPlanData prev, Impact impact, String instruction, String candidatesJson) {
        Map<String, Object> existing = CandidateUtil.parse(candidatesJson);
        if (existing == null || existing.isEmpty()) {
            EventStore.add(sessionId, "修订", "无候选存档（旧版本行程），跳过定向重查，按指令直接重排");
            return candidatesJson;
        }
        try {
            List<String> fetchDomains = new ArrayList<>(impact.domains);
            fetchDomains.removeIf(d -> !d.equals("attraction") && !d.equals("hotel") && !d.equals("dining"));
            if (fetchDomains.isEmpty()) return candidatesJson;
            String refine = JsonUtils.toJson(Map.of(
                    "domains", fetchDomains, "existing", existing, "focus", instruction));
            EventStore.add(sessionId, "修订", "定向重查受影响域："
                    + String.join("、", fetchDomains.stream().map(CandidateUtil::domainLabel).toList())
                    + "（其余域沿用已有候选）");
            String reply = utils.mcp.CandidatesDirectClient.query(
                    prev.getDestination(), String.valueOf(prev.getDays()), "",
                    "", stateBriefOf(prev), refine, 270_000);
            String block = CandidateUtil.extractBlock(reply);
            Map<String, Object> updated = CandidateUtil.parse(block);
            if (updated != null && !updated.isEmpty()) {
                EventStore.add(sessionId, "修订", "受影响域候选已更新（局部重跑，未推翻整份行程）");
                return JsonUtils.toJson(updated);
            }
        } catch (managerAgent.hook.CancelledException ce) {
            throw ce;
        } catch (Exception e) {
            log.warn("定向重查失败(沿用原候选): {}", e.getMessage());
            EventStore.add(sessionId, "修订", "定向重查失败，沿用原候选继续修正");
        }
        return candidatesJson;
    }

    // ==================== 局部重聚合 ====================

    /** 只改受影响天的重聚合（LLM）；feedback 非空为校验/审查失败后的修正重跑。失败返回 null。 */
    private TripPlanData refoldPlan(TripPlanData prev, String instruction, Impact impact,
                                    String candidatesJson, String feedback) {
        String sys = AgentPrompts.reviseDayAggregator()
                + (feedback == null || feedback.isBlank() ? "" : "\n" + feedback);
        String user = "【修改指令】" + instruction
                + "\n【影响范围】" + impact.daysText() + "｜受影响域：" + impact.domains
                + "\n\n【原行程JSON】\n" + clip(JsonUtils.toJson(prev), 12000)
                + (candidatesJson.isBlank() ? "" :
                        "\n\n【三域候选（受影响域已重查，含置信度）】\n"
                                + CandidateUtil.toPromptText(CandidateUtil.parse(candidatesJson), 3)
                                + "\n完整候选JSON：\n" + clip(candidatesJson, 4000));
        String out = callModelJson(sys, user);
        TripPlanData revised = JsonUtils.extractAndParse(out, TripPlanData.class);
        if (revised == null || revised.getSections() == null || revised.getSections().isEmpty()) {
            return null;
        }
        // 保留降级渲染需要的字段
        if (revised.getTitle() == null || revised.getTitle().isBlank()) revised.setTitle(prev.getTitle());
        if (revised.getDestination() == null || revised.getDestination().isBlank()) revised.setDestination(prev.getDestination());
        if (revised.getDays() < 1) revised.setDays(prev.getDays());
        if (revised.getDate() == null || revised.getDate().isBlank()) revised.setDate(prev.getDate());
        if (revised.getWeather() == null || revised.getWeather().isBlank()) revised.setWeather(prev.getWeather());
        return revised;
    }

    /** 修订聚焦审查：与常规审查共用清单，重点盯受影响天 */
    private ReviewVerdict reviewRevise(TripRequest req, String instruction, Impact impact,
                                       TripPlanData plan, dto.ValidationReport vr) {
        String user = "【本次修改】" + instruction + "（影响：" + impact.daysText() + "）"
                + "\n\n【确定性校验结果】" + (vr == null ? "未执行" : vr.summary())
                + "\n\n★重点检查受影响天（" + impact.daysText() + "）的修改质量与前后衔接；未受影响天只需确认未被误改。\n"
                + "\n【行程JSON】\n" + clip(JsonUtils.toJson(plan), 6000);
        String out = callModelJson(AgentPrompts.tripReviewer(), user);
        return ReviewVerdict.parse(out);
    }

    // ==================== 工具方法 ====================

    /** 修订用最小 TripRequest（从上一版行程推导；预算/约束不可得则留空，校验自动跳过对应项） */
    private TripRequest minimalRequest(TripPlanData prev) {
        TripRequest req = new TripRequest();
        req.setDestination(prev.getDestination());
        req.setDays(prev.getDays());
        req.setDate(prev.getDate());
        req.setRaw("（增量修正）" + prev.getTitle());
        return req;
    }

    /** 行程概要（影响分析输入）：每天标题 + 地点串 */
    private String planDigest(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题: ").append(plan.getTitle()).append("；目的地: ").append(plan.getDestination())
                .append("；").append(plan.getDays()).append(" 天\n");
        if (plan.getSections() != null) {
            for (dto.Section s : plan.getSections()) {
                if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;
                sb.append(s.getTitle()).append(": ");
                for (dto.SectionItem it : s.getItems()) {
                    if (it.getPlace() != null && !it.getPlace().isBlank()) sb.append(it.getPlace()).append("、");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** 修订任务用状态简报（上一版行程推导的降级版） */
    private String stateBriefOf(TripPlanData prev) {
        return "【TravelState 统一状态·修订快照】\n目的地: " + prev.getDestination()
                + "\n天数: " + prev.getDays() + " 天\n"
                + (prev.getDate() == null || prev.getDate().isBlank() ? "" : "出行日期: " + prev.getDate() + "\n");
    }

    private dto.ValidationReport safeValidate(TripRequest req, TripPlanData plan, String candidatesJson) {
        try {
            return utils.ConstraintEngine.validate(req, plan, candidatesJson);
        } catch (Exception e) {
            log.warn("修订校验异常: {}", e.getMessage());
            return new dto.ValidationReport();
        }
    }

    private String callModelJson(String sysPrompt, String userPrompt) {
        utils.SafeModel.setSessionId(sessionId);
        return utils.LlmClient.chatJson(sysPrompt, userPrompt);
    }

    private void checkCancel() {
        if (EventStore.isCancelRequested(sessionId)) {
            throw new CancelledException();
        }
    }

    private String clip(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n…(数据过长已截断)…";
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 结构化行程 → markdown（与 TripOrchestrator.renderMarkdown 一致的降级渲染） */
    private String renderMarkdown(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.getTitle()).append("（修订版）\n\n");
        if (plan.getWeather() != null && !plan.getWeather().isBlank()) {
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");
        }
        if (plan.getSections() != null) {
            for (dto.Section sec : plan.getSections()) {
                sb.append("## ").append(sec.getTitle()).append("\n");
                if (sec.getItems() == null) continue;
                for (dto.SectionItem it : sec.getItems()) {
                    sb.append("- ");
                    switch (sec.getType()) {
                        case "timeline" -> sb.append("**").append(it.getTime()).append("** ")
                                .append(it.getPlace()).append(" ").append(it.getActivity());
                        case "transport" -> sb.append(it.getFrom()).append(" → ").append(it.getTo())
                                .append("（").append(it.getWay()).append("，").append(it.getDuration()).append("）");
                        case "stay" -> sb.append(it.getName()).append("（").append(it.getCategory())
                                .append(it.getPrice().isBlank() ? "" : " " + it.getPrice()).append("）");
                        case "tips" -> sb.append(it.getKind()).append("：").append(it.getText());
                        default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }
}
