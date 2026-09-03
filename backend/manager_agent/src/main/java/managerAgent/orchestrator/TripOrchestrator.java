package managerAgent.orchestrator;

import dto.PlanSet;
import dto.Section;
import dto.SectionItem;
import dto.TravelState;
import dto.TripPlanData;
import dto.TripRequest;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.tool.RemoteTimeout;
import managerAgent.tool.RemoteAgentTool;
import utils.AgentPrompts;
import utils.AgentUtils;
import utils.JsonUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 确定性编排器：解析 → 并行 DAG → 聚合，流程由 Java 确定，LLM 只负责内容生成。 */
@Slf4j
public class TripOrchestrator {

    /** 并行调用远程专家 Agent 的共享线程池（有界，服务长期运行不复用也不关闭） */
    private static final ExecutorService PARALLEL_POOL = Executors.newFixedThreadPool(4);

    private final String sessionId;
    private final Model model;               // 解析/聚合用裸模型
    private final RemoteAgentTool remoteTool; // 专家 Agent 调用封装

    public TripOrchestrator(String sessionId) {
        this.sessionId = sessionId;
        this.model = AgentUtils.buildModel();
        this.remoteTool = new RemoteAgentTool();
        this.remoteTool.setSessionId(sessionId);
    }

    /**
     * 执行编排：解析需求 → 并行调用专家 → 聚合为结构化行程。
     */
    public TripPlanData run(String userPrompt) {
        return run(userPrompt, null);
    }

    /**
     * 执行编排（带偏好参数）：解析需求 → 并行调用专家 → 聚合为结构化行程。
     */

    /**
     * 执行编排（带个人中心沉淀偏好 P0）：解析需求 → 偏好注入 → 并行调用专家 → 聚合。
     */
    public TripPlanData run(String userPrompt, TripRequest.Preferences prefs) {
        return runCore(userPrompt, prefs);
    }

    /** 执行编排核心（个人中心沉淀偏好可 null）：解析需求 → 偏好分类 → 并行调用专家 → 聚合。全程以 TravelState 为唯一事实源。 */
    private TripPlanData runCore(String userPrompt, TripRequest.Preferences prefs) {
        TravelState state = resolveAndFetch(userPrompt, prefs);
        // ★输入侧缺失亮红灯：哪路专家数据为空，聚合前明确告知（避免静默产出半成品还显示成功）
        String missingInput = missingInputs(state.getRouteResult(), state.getPlanResult());
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        // ③ 聚合：结构化 JSON（失败退回 markdown 原文兜底）
        EventStore.add(sessionId, "思考", "正在融合路线与行程数据，规划每日安排...");
        EventStore.add(sessionId, "整合", "正在整合路线与行程数据...");
        TripPlanData plan = aggregate(state);
        if (plan == null) {
            plan = fallbackPlan(state.getRequest(), state.getRouteResult(), state.getPlanResult()); // 兜底已含完整 markdown 原文
        } else {
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段
            // ④ Reviewer 闭环：独立审查 → REVISE 按域定向重跑受影响部分（≤2轮）
            EventStore.add(sessionId, "审查", "独立审查员正在检查行程质量...");
            plan = reviewerLoop(state, plan, null);
        }
        // ⑤ 终版确定性校验 + 六维评分 + 证据链存档
        validateAndReport(state, plan, "方案", true);
        archiveEvidence(state, plan);
        EventStore.add(sessionId, "完成", "结构化行程生成完成");
        return plan;
    }

    /** 确定性校验并把结果写入事件流；withScore=true 时同步输出六维评分。返回校验报告供调用方复用。 */
    private dto.ValidationReport validateAndReport(TravelState state, TripPlanData plan, String label, boolean withScore) {
        try {
            dto.ValidationReport vr = utils.ConstraintEngine.validate(state.getRequest(), plan, state.getCandidatesJson());
            if (!vr.getSkippedChecks().isEmpty()) {
                EventStore.add(sessionId, "校验", label + " 跳过检查: " + String.join("、", vr.getSkippedChecks()));
            }
            EventStore.add(sessionId, "校验", label + " 校验结果 " + vr.summary());
            if (withScore) {
                dto.ScoreResult sr = utils.PlanScorer.score(state.getRequest(), plan, vr,
                        state.getRouteResult() != null && !state.getRouteResult().isBlank(),
                        state.getPlanResult() != null && !state.getPlanResult().isBlank());
                EventStore.add(sessionId, "评分", label + " " + sr.getSummary());
            }
            return vr;
        } catch (Exception e) {
            log.warn("确定性校验/评分异常(不阻断交付): {}", e.getMessage());
            return new dto.ValidationReport();
        }
    }

    /** 证据链存档（仅后端记录，前端不渲染）：候选来源/等级/抓取时间/置信度 → plan.evidence + 事件流摘要 */
    private void archiveEvidence(TravelState state, TripPlanData plan) {
        try {
            if (plan == null) return;
            plan.setCandidatesJson(state.getCandidatesJson());
            java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());
            plan.setEvidence(utils.CandidateUtil.evidenceList(cands));
            String summary = utils.CandidateUtil.evidenceSummary(cands);
            if (!summary.isBlank()) {
                EventStore.add(sessionId, "证据链", summary + "（后端留存，详见行程JSON evidence 字段）");
            }
        } catch (Exception e) {
            log.warn("证据链存档异常(忽略): {}", e.getMessage());
        }
    }

    // ==================== Reviewer 闭环 ====================

    /** Reviewer 单次审查：独立 LLM 审查员按检查清单输出结构化裁定；输入含确定性校验完整摘要（已执行/跳过检查清单）作证据。 */
    private ReviewVerdict reviewPlan(TripRequest req, TripPlanData plan, dto.ValidationReport vr) {
        utils.SafeModel.setStage("行程审查");
        String user = "【用户需求】" + (req == null || req.getRaw() == null ? "" : req.getRaw())
                + "\n\n【确定性校验结果】" + (vr == null ? "未执行" : vr.detailedSummary())
                + "\n\n【行程JSON】\n" + clip(utils.JsonUtils.toJson(plan), 6000);
        String out = callModelJson(AgentPrompts.tripReviewer(), user);
        return ReviewVerdict.parse(out); // null=解析失败，调用方按 PASS 兜底
    }

    /** Reviewer 闭环：审查 → REVISE 按域定向重跑（PDF §十二/§十三：只重跑有问题域，不推翻重来）→ 再审，上限 MAX_REVIEW_ROUNDS 轮。
     *  style 为多方案风格文案（保持重跑不丢风格），单方案传 null。
     *  审查器/重跑故障均不阻断交付（保留当前版本）。 */
    private TripPlanData reviewerLoop(TravelState state, TripPlanData plan, String style) {
        TripRequest req = state.getRequest();
        TripPlanData current = plan;
        for (int round = 1; round <= MAX_REVIEW_ROUNDS; round++) {
            checkCancel();
            dto.ValidationReport vr = null;
            try {
                vr = utils.ConstraintEngine.validate(req, current, state.getCandidatesJson());
            } catch (Exception ignored) {
            }
            ReviewVerdict v = reviewPlan(req, current, vr);
            if (v == null) {
                EventStore.add(sessionId, "审查", "第" + round + "轮审查输出异常，按通过处理");
                break;
            }
            if (!v.needsRevise()) {
                EventStore.add(sessionId, "审查", "第" + round + "轮审查通过"
                        + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : "")
                        + (v.getIssues().isEmpty() ? "" : "（" + v.getIssues().size() + " 条轻微建议已记录）"));
                break;
            }
            EventStore.add(sessionId, "审查", "第" + round + "轮审查 REVISE"
                    + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : "") + "：" + v.feedbackText());
            if (round == MAX_REVIEW_ROUNDS) {
                EventStore.add(sessionId, "审查", "已达修正上限(" + MAX_REVIEW_ROUNDS + " 轮)，交付当前版本");
                break;
            }
            EventStore.add(sessionId, "审查", "带审查反馈重跑聚合（第 " + round + " 次修正）...");
            TripPlanData fixed;
            try {
                fixed = aggregateCore(state, style, v.feedbackText());
            } catch (Exception e) {
                log.warn("Reviewer 修正重跑异常: {}", e.getMessage());
                EventStore.add(sessionId, "审查", "修正重跑异常，交付当前版本");
                break;
            }
            if (fixed == null || fixed.getSections() == null || fixed.getSections().isEmpty()) {
                EventStore.add(sessionId, "审查", "修正重跑未产出更优结果，保留上一版");
                break;
            }
            fixed.setMarkdown(renderMarkdown(fixed));
            current = fixed;
        }
        return current;
    }

    /** 多方案编排：并行聚合出美食向/出片向/省钱向 3 套方案供前端对比。 */
    public PlanSet runMultiScheme(String userPrompt) {
        TravelState state = resolveAndFetch(userPrompt, null);
        String missingInput = missingInputs(state.getRouteResult(), state.getPlanResult()); // 多方案路径同样亮红灯
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        EventStore.add(sessionId, "思考", "正在融合专家数据，规划多套差异化方案...");
        EventStore.add(sessionId, "整合", "正在生成 3 套差异化方案（美食向/出片向/省钱向）...");
        PlanSet set = aggregateMulti(state);
        // 每套方案补降级 markdown（前端打印/导出用）+ 逐套确定性校验与评分，择优 recommendedIndex
        if (set != null) {
            for (int i = 0; i < set.getPlans().size(); i++) {
                TripPlanData p = set.getPlans().get(i);
                if (p != null && p.getMarkdown().isBlank()) {
                    p.setMarkdown(renderMarkdown(p));
                }
                String slogan = i < set.getSlogans().size() ? set.getSlogans().get(i) : ("方案" + (i + 1));
                dto.ValidationReport vr = validateAndReport(state, p, "「" + slogan + "」", false);
                try {
                    dto.ScoreResult sr = utils.PlanScorer.score(state.getRequest(), p, vr,
                            state.getRouteResult() != null && !state.getRouteResult().isBlank(),
                            state.getPlanResult() != null && !state.getPlanResult().isBlank());
                    set.getScores().add(sr.getTotal());
                    EventStore.add(sessionId, "评分", "「" + slogan + "」 " + sr.getSummary());
                } catch (Exception e) {
                    set.getScores().add(null); // 评分异常不阻断，占位保持下标对齐
                    log.warn("方案「{}」评分异常: {}", slogan, e.getMessage());
                }
            }
            int best = utils.PlanScorer.best(set.getScores());
            if (best >= 0) {
                set.setRecommendedIndex(best);
                String bestSlogan = best < set.getSlogans().size() ? set.getSlogans().get(best) : ("方案" + (best + 1));
                EventStore.add(sessionId, "评分", "★ 推荐方案：" + bestSlogan);
                // ★Reviewer 闭环只审推荐方案（省 2/3 审查开销）：REVISE 按域定向重跑（保持风格），修正后重算评分
                TripPlanData rec = set.getPlans().get(best);
                if (rec != null && rec.getSections() != null && !rec.getSections().isEmpty()) {
                    EventStore.add(sessionId, "审查", "对推荐方案「" + bestSlogan + "」执行独立审查...");
                    TripPlanData reviewed = reviewerLoop(state, rec, PLAN_STYLES[best][2]);
                    if (reviewed != rec) {
                        set.getPlans().set(best, reviewed);
                        try {
                            dto.ValidationReport vrf = utils.ConstraintEngine.validate(
                                    state.getRequest(), reviewed, state.getCandidatesJson());
                            dto.ScoreResult sr = utils.PlanScorer.score(state.getRequest(), reviewed, vrf,
                                    state.getRouteResult() != null && !state.getRouteResult().isBlank(),
                                    state.getPlanResult() != null && !state.getPlanResult().isBlank());
                            set.getScores().set(best, sr.getTotal());
                            EventStore.add(sessionId, "评分", "「" + bestSlogan + "」审查修正后 " + sr.getSummary());
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            // 每套方案存档候选与证据链（前端不渲染，增量修正/审计用）
            for (TripPlanData p : set.getPlans()) {
                archiveEvidence(state, p);
            }
        }
        EventStore.add(sessionId, "完成", "多方案生成完成（" + (set == null ? 0 : set.getPlans().size()) + " 套）");
        return set;
    }

    /** 解析需求 + 并行 DAG（单方案/多方案共享的前置步骤；prefs=个人中心沉淀偏好，可 null）。
     *  全部产物（解析结果/专家原文/跨Agent提取的途经点与景点）沉淀进 TravelState 统一状态中心。 */
    private TravelState resolveAndFetch(String userPrompt, TripRequest.Preferences prefs) {
        TravelState state = new TravelState();
        // ① 需求解析（单次结构化 LLM 调用）+ 纯代码规整（预算数值化/约束兜底）
        EventStore.add(sessionId, "解析", "正在解析旅行需求...");
        TripRequest req = parseRequest(userPrompt);
        if (prefs != null && !prefs.isEmpty()) {
            req.setPreferences(prefs);
        }
        RequirementNormalizer.normalize(req); // ★LLM 漏解析预算/约束时正则兜底，保证状态完整
        state.setRequest(req);
        String constraintSummary = req.getConstraints().isEmpty() ? ""
                : "，约束 " + req.getConstraints().size() + " 条" + (req.hasHardConstraints() ? "（含P0硬约束）" : "");
        EventStore.add(sessionId, "解析", "需求：" + req.getOrigin() + " → " + req.getDestination()
                + "，" + req.getDays() + " 天"
                + (req.getBudgetTotal() > 0 ? "，预算 " + (long) req.getBudgetTotal() + " 元" : "")
                + constraintSummary
                + (req.getPreference().isBlank() ? "" : "（" + req.getPreference() + "）")
                + (req.travelTypeLabel().isBlank() ? "" : "，类型「" + req.travelTypeLabel() + "」")
                + (req.getTravelers() > 0 ? "，" + req.getTravelers() + " 人"
                        + (req.travelerTypeLabel().isBlank() ? "" : "（" + req.travelerTypeLabel() + "）") : "")
                + buildPreferenceLabel(req));
        state.addTrace("解析", req.getOrigin() + "→" + req.getDestination() + " " + req.getDays() + "天"
                + (req.getBudgetTotal() > 0 ? " 预算" + (long) req.getBudgetTotal() : "")
                + (req.travelTypeLabel().isBlank() ? "" : " " + req.travelTypeLabel()));
        checkCancel();

        /* ★ 结果缓存（进程内）：key=目的地|天数|出发|沉淀偏好|本次偏好|需求指纹(预算/人数/类型/约束)。
           仅缓存专家产物，LLM 聚合仍每次执行——防串 + 偏好变化即时生效。 */
        String storedPrefs = (req.getPreferences() != null && !req.getPreferences().isEmpty())
                ? req.getPreferences().toPromptText() : "";
        String cacheFingerprint = req.cacheFingerprint();
        utils.cache.PlanCache.Entry hit = utils.cache.PlanCache.get(
                req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs, req.getPreference(), cacheFingerprint);
        if (hit != null) {
            EventStore.add(sessionId, "解析", "· 命中结果缓存，跳过专家调用 ·");
            state.setFromCache(true);
            state.setRouteResult(hit.routeResult);
            state.setPlanResult(hit.planResult);
            state.setCandidatesJson(hit.candidatesJson); // 候选随缓存复用（旧条目为空串则聚合走原文兜底）
            return state;
        }

        // ② 偏好分流：LLM 把文本+个人中心偏好分为行程/路线/聚合三类
        boolean hasTextPref = req.getPreference() != null && !req.getPreference().isBlank();
        boolean hasStoredPref = req.getPreferences() != null && !req.getPreferences().isEmpty();
        if (hasTextPref || hasStoredPref) {
            classifyPreference(req);
        }
        // ③ 编排分支：跨城=先路线(途经点)再行程；市内=先行程(景点)再路线
        //    跨 Agent 提取结果（途经点/景点清单）统一写入 state，禁止散装传参
        boolean crossCity = req.getOrigin() != null && !req.getOrigin().isBlank()
                && req.getDestination() != null && !req.getDestination().isBlank()
                && !req.getOrigin().trim().equals(req.getDestination().trim());
        boolean cityTour = !crossCity;  // 单城 / 起点=终点 / 无起点 → 市内（环线游无起点也走此分支）
        if (crossCity) {
            EventStore.add(sessionId, "路线", "跨城"
                    + (req.travelTypeLabel().isBlank() ? "" : "「" + req.travelTypeLabel() + "」")
                    + "，先规划路线提取途经城市...");
            String routeResult = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callRouteMakingAgent(routeTask(req, ""))), "路线Agent");
            state.setRouteResult(routeResult);
            checkCancel();
            String waypoints = extractWaypoints(routeResult);
            state.setWaypoints(waypoints);
            state.addTrace("路线", "途经城市: " + (waypoints.isBlank() ? "未提取到" : waypoints));
            String planResult = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callTripPlannerAgent(planTask(req, waypoints))), "行程Agent");
            state.setPlanResult(planResult);
            harvestCandidates(state);
            checkCancel();
            utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,
                    req.getPreference(), cacheFingerprint, state.getRouteResult(), state.getPlanResult(), state.getCandidatesJson());
            return state;
        }
        EventStore.add(sessionId, "行程", "市内"
                + (req.travelTypeLabel().isBlank() ? "游" : "「" + req.travelTypeLabel() + "」")
                + "，先规划行程提取景点清单...");
        String planFirst = await(PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(planTask(req, ""))), "行程Agent");
        state.setPlanResult(planFirst);
        harvestCandidates(state);
        checkCancel();
        String sights = extractSights(state.getPlanResult(), req.getDestination());
        state.setSights(sights);
        state.addTrace("行程", "景点清单: " + (sights.isBlank() ? "未提取到" : sights));
        String routeResult2 = await(PARALLEL_POOL.submit(() ->
                remoteTool.callRouteMakingAgent(routeTask(req, sights))), "路线Agent");
        state.setRouteResult(routeResult2);
        checkCancel();
        utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,
                req.getPreference(), cacheFingerprint, state.getRouteResult(), state.getPlanResult(), state.getCandidatesJson());
        return state;
    }

    /** 从行程专家回复中收割三域候选 JSON（结构化候选入状态中心；无块则保持空串，聚合按原文兜底）。
     *  候选块随即从正文中剥离：聚合/缓存/降级渲染消费纯行程文本，不再夹带大段 JSON（防超长截断与解析劣化）。 */
    private void harvestCandidates(TravelState state) {
        try {
            String block = utils.CandidateUtil.extractBlock(state.getPlanResult());
            if (!block.isBlank()) {
                java.util.Map<String, Object> cands = utils.CandidateUtil.parse(block);
                if (cands != null && !cands.isEmpty()) {
                    state.setCandidatesJson(utils.JsonUtils.toJson(cands));
                    state.setPlanResult(utils.CandidateUtil.stripBlock(state.getPlanResult()));
                    String summary = utils.CandidateUtil.evidenceSummary(cands);
                    EventStore.add(sessionId, "候选", "三域候选已入状态中心"
                            + (summary.isBlank() ? "" : "：" + summary));
                    state.addTrace("候选", "三域Top3候选(结构化)");
                }
            }
        } catch (Exception e) {
            log.warn("候选收割异常(忽略，走原文聚合): {}", e.getMessage());
        }
    }

    // ==================== ① 需求解析 ====================

    private TripRequest parseRequest(String prompt) {
        utils.SafeModel.setStage("解析需求"); // ★调用链：该 LLM span 说明标注"在做什么"
        String sysPrompt = AgentPrompts.requestParser();
        String text = callModelJson(sysPrompt, prompt);
        TripRequest req = JsonUtils.extractAndParse(text, TripRequest.class);
        if (req == null) { // 解析失败：从原文粗提取兜底
            req = fallbackParse(prompt);
        }
        if (req.getDays() < 1) {
            req.setDays(1);
        }
        req.setRaw(prompt);
        // ★调用链：把解析结果摘要回填到该 LLM span 说明（"解析需求 · 目的地:xx 天数:x"）
        try {
            StringBuilder sum = new StringBuilder("目的地:")
                    .append(req.getDestination() == null || req.getDestination().isBlank() ? "?" : req.getDestination())
                    .append(" 天数:").append(req.getDays());
            if (req.getBudgetTotal() > 0) {
                sum.append(" 预算:").append((long) req.getBudgetTotal());
            }
            if (!req.getConstraints().isEmpty()) {
                sum.append(" 约束:").append(req.getConstraints().size()).append("条");
            }
            if (req.getOrigin() != null && !req.getOrigin().isBlank()) {
                sum.append(" 起点:").append(req.getOrigin());
            }
            if (req.getPreference() != null && !req.getPreference().isBlank()) {
                sum.append(" 偏好:").append(req.getPreference());
            }
            utils.CallTrace.appendLastLlmDetail(sessionId, sum.toString());
        } catch (Exception ignored) { }
        return req;
    }

    /** 解析失败时从原文粗提取起点/终点/天数 */
    private TripRequest fallbackParse(String prompt) {
        TripRequest req = new TripRequest();
        String[] citys = prompt.replaceAll("帮我|制定|计划|规划|旅游|旅行|游|自驾|路线", " ").trim().split("\\s+");
        int cityIdx = 0;
        for (String token : citys) {
            if (token.isBlank() || token.length() > 6 || token.matches(".*\\d.*")) {
                continue;
            }
            if (cityIdx == 0) {
                req.setOrigin(token);
            } else if (cityIdx == 1) {
                req.setDestination(token);
            } else {
                break;
            }
            cityIdx++;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*[日天]").matcher(prompt);
        if (m.find()) {
            req.setDays(Integer.parseInt(m.group(1)));
        }
        return req;
    }

    /** 偏好标签（事件流展示用，如「步行≤1万米 · 早起 · 博物馆多」） */
    private String buildPreferenceLabel(TripRequest req) {
        return ""; // 前端选项卡已删，偏好仅来自用户输入+个人中心
    }

    /** 偏好约束（聚合 prompt 用，引导模型按偏好安排行程节奏） */
    private String buildPreferencePrompt(TripRequest req) {
        return ""; // 预检约束不再拼入聚合提示词
    }

    // ==================== ② 并行 DAG ====================

    private String routeTask(TripRequest req, String sightList) {
        java.util.List<String> routePrefs = new java.util.ArrayList<>();
        if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) routePrefs.add(req.getRoutePref().trim());
        String routePrefStr = routePrefs.isEmpty() ? "" : String.join(" ", routePrefs);
        // sightList 非空 → 市内游且已由行程Agent定好景点，路线按这些景点查衔接
        String dest = (req.getDestination() == null || req.getDestination().isBlank()) ? "" : req.getDestination();
        boolean noOrigin = req.getOrigin() == null || req.getOrigin().isBlank();
        // ★统一状态下发（PDF §三）：子 Agent 围绕 TravelState 决策，不各自解读原始用户文本
        String brief = req.toStateBrief() + "\n";
        if (noOrigin || sightList != null && !sightList.isBlank()) {
            // 市内/行程先行：路线Agent针对"行程已定的景点"查衔接，不再自己猜景点
            String base = brief + "这是 " + dest + " 的" + req.getDays() + "天市内/周边游（用户未提供出发地）。\n";
            if (sightList != null && !sightList.isBlank()) {
                base += "行程Agent已确定景点清单：" + sightList + "。\n"
                        + "★请用 query_route_unified(mode=urban" + (routePrefStr.isBlank() ? "" : ", routePref=" + routePrefStr)
                        + ") 只查询这些景点彼此之间的衔接方式"
                        + "（短距步行/长距公交地铁），不要额外添加不在清单里的景点，也不要编造跨城里程。\n";
            } else {
                base += "请规划在市内及周边的行程：\n"
                        + "★调用 query_route_unified，mode 固定传 urban" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                        + "（市内衔接：步行/公交），查询市内相邻景点/地点间的衔接方式，不要编造跨城里程。\n";
            }
            base += "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数。\n"
                    + "最终回复：完整可读的市内行程，含每日安排、景点间衔接方式、大致耗时。";
            return base;
        }
        String driveHint = switch (req.getTravelType() == null ? "" : req.getTravelType()) {
            case "LOOP", "SELF_DRIVE" -> "（环线游：每日路线尽量不走回头路，环形串联）";
            default -> "";
        };
        return brief + "请规划从 " + req.getOrigin() + " 到 " + dest + " 的自驾路线，"
                + req.getDays() + " 天，包含每日行车路线、途经景点、驾驶时间与费用" + driveHint + "。\n"
                + "★调用 query_route_unified，mode 参数driving" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                + "（自驾路线与距离），查询各段自驾线路。\n"
                + "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数。\n"
                + "如果 " + req.getDays() + " 天跑不完某条长线，请选择适合 " + req.getDays() + " 天的缩短路线（或减少景点），"
                + "天数只允许少不允许多。";
    }

    private String planTask(TripRequest req, String waypoints) {
        // 行程偏好：LLM 分类出的 tripPref
        java.util.List<String> tripPrefs = new java.util.ArrayList<>();
        if (req.getTripPref() != null && !req.getTripPref().isBlank()) tripPrefs.add(req.getTripPref().trim());
        String prefStr = tripPrefs.isEmpty() ? "" : String.join(" ", tripPrefs);
        // ★统一状态下发（PDF §三）：候选专家围绕 TravelState 决策；brief 参数需原样传给 query_trip_candidates
        String brief = req.toStateBrief() + "\n";
        if (waypoints != null && !waypoints.isBlank()) {
            // 跨城：路线先行提取途经城市后，候选重点查终点城市 + 途经城市，不查起点
            return brief + "请规划从 " + req.getOrigin() + " 到 " + req.getDestination() + " 的" + req.getDays() + "天行程，"
                    + "重点规划【终点城市】与【途经城市】的景点。\n"
                    + "★调用 query_trip_candidates 时，destination 固定传【" + req.getDestination() + "】，"
                    + "把途经城市传 waypoints 参数（逗号分隔）：" + waypoints + "，"
                    + "查终点城市与途经城市的景点/美食；【不要查起点城市 " + req.getOrigin() + " 内部的景点】（起点只是出发地，不是游玩地）；"
                    + "brief 参数传任务开头的【TravelState 统一状态】块原文。\n"
                    + "结合路线按 Day 每日给出行程与安排（前段可排途经城市，后段重点排终点城市）。"
                    + (prefStr.isBlank() ? "" : "★把行程偏好作为 preference 参数传入：" + prefStr + "。");
        }
        return brief + "请规划 " + req.getDestination() + " " + req.getDays() + " 日游的景点与每日行程，"
                + "包含景点、门票、住宿、餐饮美食"
                + (prefStr.isBlank() ? "" : "，偏好：" + prefStr) + "。\n"
                + "★调用 query_trip_candidates 时，把上述偏好作为 preference 参数传入，"
                + "brief 参数传任务开头的【TravelState 统一状态】块原文，"
                + "让三域专家按偏好与约束查询更贴合的数据(如'亲子景点'/'美食餐厅')，不要忽略偏好查通用清单。";
    }

    /** 从路线结果提取途经城市名（带市/区/县后缀，排除起终点，最多5个）。 */
    private String extractWaypoints(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return "";
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\\u4e00-\\u9fa5]{2,6}?(?:市|区|县|镇|州)")
                .matcher(routeResult);
        while (m.find() && seen.size() < 5) {
            seen.add(m.group());
        }
        // 剔除起终点城市名本身
        seen.removeIf(n -> n.contains("起点") || n.contains("终点"));
        return String.join(",", seen);
    }

    /** 从行程结果提取景点名（带景区/园/馆等后缀，最多5个）。 */
    private String extractSights(String planResult, String dest) {
        if (planResult == null || planResult.isBlank()) return "";
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\u4e00-\u9fa5]{2,8}?(?:景区|公园|古镇|山庄|博物馆|街|塔|山|湾|洞|寺|宫|观景台|馆|广场|坊|陵|岛|湖|院|园|桥|河)")
                .matcher(planResult);
        while (m.find() && seen.size() < 5) {
            String name = m.group();
            if (name.equals(dest)) continue; // 排除纯城市名
            seen.add(name);
        }
        return String.join(",", seen);
    }

    /** 从专家产物提取高德天气原文，格式兼容：
     *   【天气】\n<原文>   或   【目的地天气|xxx】\n<原文>   或   【xxx天气】\n<原文> */
    private String extractWeatherRaw(String combined) {
        if (combined == null || combined.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("【[^】]*天气[^】]*】\\s*\\n([\\s\\S]{2,120}?)\\n")
                .matcher(combined);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 兜底：提取 "天气：" 后最近一行
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("天气[\\s:：]([^\\n\\r]{2,80})")
                .matcher(combined);
        return m2.find() ? m2.group(1).trim() : null;
    }

    /** 偏好分类：1次LLM把偏好分为行程(tripPref)/路线(routePref)/聚合(aggPref)三类。 */
    private void classifyPreference(TripRequest req) {
        utils.SafeModel.setStage("偏好分类"); // ★调用链：该 LLM span 说明标注阶段
        String sys = AgentPrompts.preferenceClassifier();
        // 个人中心偏好并入分类输入
        String prefInput = req.getPreference();
        if (req.getPreferences() != null && !req.getPreferences().isEmpty()) {
            prefInput = (prefInput == null || prefInput.isBlank() ? "" : prefInput + "；")
                    + "个人中心设置：" + req.getPreferences().toPromptText();
        }
        String out = callModelJson(sys, "用户偏好：" + prefInput);
        try {
            var node = JsonUtils.extractAndParse(out, java.util.Map.class);
            if (node != null) {
                req.setTripPref(str(node.get("tripPref")));
                req.setRoutePref(str(node.get("routePref")));
                req.setAggPref(str(node.get("aggPref")));
                // ★调用链：分类结果摘要回填
                StringBuilder sum = new StringBuilder("行程偏好:")
                        .append(req.getTripPref() == null || req.getTripPref().isBlank() ? "无" : req.getTripPref());
                if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) {
                    sum.append(" 路线偏好:").append(req.getRoutePref());
                }
                utils.CallTrace.appendLastLlmDetail(sessionId, sum.toString());
                return;
            }
        } catch (Exception ignored) { }
        // 解析失败兜底：全部留给聚合
        req.setAggPref(req.getPreference());
    }

    /** 对象安全转字符串 */
    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 检查中断请求（替代原 planHook 的事件级取消，编排器在步骤边界检查） */
    private void checkCancel() {
        if (EventStore.isCancelRequested(sessionId)) {
            throw new CancelledException();
        }
    }

    /** 等待 Future 结果，超时/异常返回空串并记录事件；★中断感知：每 500ms 轮询取消标志，点中断立即生效（原步骤边界检查太慢） */
    private String await(Future<String> future, String label) {
        EventStore.add(sessionId, "调用工具", "调用「" + label + "」获取真实数据...");
        RemoteTimeout.configure(utils.NacosModelConfig.orchestrationConfig());
        Duration timeout = RemoteTimeout.timeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                checkCancel(); // ★中断检查：阻塞等待期间持续轮询，不再等步骤边界
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainMs <= 0) {
                    future.cancel(true);
                    log.warn("{} 调用超时({}s)，已取消后台任务", label, RemoteTimeout.currentSeconds());
                    EventStore.add(sessionId, "Agent结果",
                            label + " ❌ 调用超时(" + RemoteTimeout.currentSeconds() + "s)");
                    return "";
                }
                try {
                    String r = future.get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);
                    return r == null ? "" : r;
                } catch (ExecutionException ex) {
                    // ★解包：后台任务抛出的 CancelledException（用户中断）要从 Future 里取出来向上传播
                    if (ex.getCause() instanceof CancelledException) {
                        throw (CancelledException) ex.getCause();
                    }
                    throw ex;
                } catch (TimeoutException e) {
                    // 本轮 500ms 内未完成，回到循环头重新检查中断
                }
            }
        } catch (CancelledException e) {
            future.cancel(true); // ★用户中断：取消后台任务后向上传播
            EventStore.add(sessionId, "Agent结果", label + " ⏹ 已中断");
            throw e;
        } catch (Exception e) {
            log.error("{} 调用失败: {}", label, e.getMessage());
            EventStore.add(sessionId, "Agent结果", label + " ❌ 调用失败(" + e.getMessage() + ")");
            return "";
        }
    }

    // ==================== ③ 聚合 ====================

    private TripPlanData aggregate(TravelState state) {
        return aggregateWithStyle(state, null);
    }

    /** 输入侧缺失描述：路线/行程哪路为空返回对应文案，齐全返回空串 */
    private String missingInputs(String routeResult, String planResult) {
        boolean noRoute = routeResult == null || routeResult.isBlank();
        boolean noPlan = planResult == null || planResult.isBlank();
        if (noRoute && noPlan) return "路线与行程数据均缺失";
        if (noRoute) return "路线数据缺失";
        if (noPlan) return "行程数据缺失";
        return "";
    }

    /** 聚合提示词的缺失标注铁律：仅在有输入缺失时注入，要求LLM如实标注禁止编造 */
    private String missingInputRule(String routeResult, String planResult) {
        boolean noRoute = routeResult == null || routeResult.isBlank();
        boolean noPlan = planResult == null || planResult.isBlank();
        if (!noRoute && !noPlan) return "";
        StringBuilder rule = new StringBuilder("\n★数据缺失铁律：本次");
        if (noRoute) rule.append("【路线数据】完全缺失（交通衔接不可用），");
        if (noPlan) rule.append("【行程数据】完全缺失（景点/住宿/美食数据不可用），");
        rule.append("涉及缺失侧的内容必须在 note 字段如实标注「该部分数据获取失败」，")
            .append("严禁凭常识编造衔接方式/景点/价格。宁缺毋假。");
        return rule.toString();
    }

    /** 单套方案聚合（style=null 表示默认无风格；否则注入风格约束） */
    private TripPlanData aggregateWithStyle(TravelState state, String style) {
        return aggregateCore(state, style, "");
    }

    /** 聚合核心：feedback 非空时为 Reviewer 修正重跑（注入审查反馈要求定向修改）。
     *  输入 = 用户需求 + 确定性校验/统一状态 + 三域候选（结构化优先）+ 专家原文。 */
    private TripPlanData aggregateCore(TravelState state, String style, String feedback) {
        TripRequest req = state.getRequest();
        String routeResult = state.getRouteResult();
        String planResult = state.getPlanResult();
        utils.SafeModel.setStage("聚合生成"); // ★调用链：聚合 LLM span 标注阶段
        String sysPrompt = AgentPrompts.tripAggregator()
                + buildStyleHint(style)
                + missingInputRule(routeResult, planResult) // ★缺失侧如实标注铁律（数据齐全时为空串不加约束）
                + (feedback == null || feedback.isBlank() ? "" :
                        "\n★审查修正铁律：上一版方案经审查发现问题：" + feedback + "\n本次输出必须逐条修复上述问题，其余符合要求的内容保持不变。");
        String userPrompt = "用户需求：" + req.getRaw()
                + buildPreferencePrompt(req)  // 偏好约束
                + "\n\n" + req.toStateBrief()
                + buildCandidatesSection(state) // ★三域结构化候选（PDF §八：候选优先，全局权衡）
                + "\n\n【路线数据】\n" + clip(routeResult) + "\n\n【行程数据】\n" + clip(planResult);
        String text = callModelJson(sysPrompt, userPrompt);
        TripPlanData plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        if (plan == null) {
            // 首次解析失败：带精简概览+修改指示重试一次（不整段回灌）
            String fix = AgentPrompts.tripAggregatorParseRetry()
                    + "【用户需求】" + req.getRaw() + "\n"
                    + "【路线数据概要】\n" + clip(routeResult, 800) + "\n"
                    + "【行程数据概要】\n" + clip(planResult, 1500);
            text = callModelJson(fix, "请输出合法 JSON 行程。");
            plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        }
        if (plan == null) {
            return null;
        }
        // 聚合评分（纯代码）：缺关键内容→带缺失提示重试1次→仍缺返回null外层兜底
        String missing = aggregateGap(plan);
        if (!missing.isBlank()) {
            EventStore.add(sessionId, "整合", "聚合结果缺失(" + missing + ")，聚合Agent重试1次...");
            String fix = AgentPrompts.tripAggregatorGapRetry().replace("{missing}", missing)
                    + "【用户需求】" + req.getRaw() + "\n"
                    + "【路线数据概要】\n" + clip(routeResult, 800) + "\n"
                    + "【行程数据概要】\n" + clip(planResult, 1500);
            String retryText = callModelJson(fix, "请补齐缺失内容，输出完整合法 JSON 行程。");
            TripPlanData retry = JsonUtils.extractAndParse(retryText, TripPlanData.class);
            if (retry != null && aggregateGap(retry).isBlank()) {
                plan = retry; // 重试补齐成功
            } else {
                // 仍缺失 → 返回null，外层降级markdown兜底
                EventStore.add(sessionId, "整合", "聚合重试后仍缺失，降级为原文兜底");
                return null;
            }
        }
        // ★天气处理：优先专家数据（高德 query_trip 原文）→ 清洗剔除 LLM 表格残留；
        //   专家文本无有效天气时直连高德预报接口补真实天气（不依赖 LLM 重述，防编造）
        if (plan != null) {
            String weatherRaw = cleanWeather(extractWeatherRaw(routeResult + "\n" + planResult));
            if (weatherRaw == null || weatherRaw.isBlank()) {
                String direct = fetchAmapWeather(req.getDestination(), Math.min(req.getDays(), 7));
                if (direct != null && !direct.isBlank()) {
                    weatherRaw = direct;
                    EventStore.add(sessionId, "整合", "天气直连高德预报：" + direct);
                }
            }
            if (weatherRaw != null && !weatherRaw.isBlank()) {
                plan.setWeather(weatherRaw); // 覆盖 LLM 聚合值（已清洗）
                EventStore.add(sessionId, "整合", "天气透传：" + weatherRaw);
            } else if (plan.getWeather() != null && !plan.getWeather().isBlank()) {
                plan.setWeather(cleanWeather(plan.getWeather())); // 保留 LLM 聚合（已清洗）
                EventStore.add(sessionId, "整合", "专家数据无天气，保留 LLM 聚合：" + plan.getWeather());
            }
        }
        // ★调用链：聚合结果摘要回填（"聚合生成 · 生成:xx日游"）
        try {
            String t = plan.getTitle();
            utils.CallTrace.appendLastLlmDetail(sessionId,
                    "生成:" + (t == null || t.isBlank() ? (req.getDestination() + req.getDays() + "日游") : t));
        } catch (Exception ignored) { }
        if (plan.getTitle().isBlank()) {
            plan.setTitle(req.getDestination() + req.getDays() + "日游");
        }
        if (plan.getDestination().isBlank()) {
            plan.setDestination(req.getDestination());
        }
        if (plan.getDays() < 1) {
            plan.setDays(req.getDays());
        }
        return plan;
    }

    /** 天气清洗：剔除 LLM 输出中的 markdown 表格残留（与前端 cleanWeatherText 一致） */
    private String cleanWeather(String w) {
        if (w == null) return null;
        String s = w.trim();
        if (s.matches("\\|.+\\|")) return ""; // 整行管道符表格结构 → 无有效天气内容
        return s.replaceAll("^\\s*\\|+\\s*|\\s*\\|+\\s*$", "").trim();
    }

    /** ★高德天气直连：按目的地取未来几天真实预报（不依赖 LLM 重述，防编造/表格残留），失败返回 null */
    private String fetchAmapWeather(String city, int days) {
        try {
            String key = utils.ExternalServices.amapKey();
            if (key == null || key.isBlank() || city == null || city.isBlank()) return null;
            String clean = city.trim().replaceAll("\\d+\\s*[日天]|[一二三四五六七八九十]+\\s*[日天]|游$", "").trim();
            if (clean.isBlank()) return null;
            Thread.sleep(350); // QPS 3 限速
            String qs = "city=" + java.net.URLEncoder.encode(clean, "UTF-8")
                    + "&output=json&key=" + key + "&extensions=all";
            java.net.URL u = new java.net.URL("https://restapi.amap.com/v3/weather/weatherInfo?" + qs);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            java.util.Map<String, Object> root = utils.JsonUtils.parse(b, java.util.Map.class);
            if (root == null) return null;
            Object fc = root.get("forecasts");
            if (!(fc instanceof java.util.List) || ((java.util.List<?>) fc).isEmpty()) return null;
            Object castsObj = ((java.util.Map<?, ?>) ((java.util.List<?>) fc).get(0)).get("casts");
            if (!(castsObj instanceof java.util.List)) return null;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object co : (java.util.List<?>) castsObj) {
                if (n >= days) break;
                java.util.Map<?, ?> cast = (java.util.Map<?, ?>) co;
                Object dv = cast.get("date");
                Object dy = cast.get("dayweather");
                Object nt = cast.get("nightweather");
                Object dt = cast.get("daytemp");
                Object ntt = cast.get("nighttemp");
                if (dv == null || dy == null) continue;
                String date = String.valueOf(dv);
                String md = date.length() >= 10
                        ? (Integer.parseInt(date.substring(5, 7)) + "月" + Integer.parseInt(date.substring(8, 10)) + "日")
                        : date;
                String w = String.valueOf(dy);
                if (nt != null && !String.valueOf(nt).equals(w)) w = w + "转" + nt;
                String temps = (ntt == null ? "" : String.valueOf(ntt) + "℃~") + (dt == null ? "" : String.valueOf(dt) + "℃");
                if (sb.length() > 0) sb.append("；");
                sb.append(md).append(" ").append(w).append(" ").append(temps);
                n++;
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 聚合评分：检查结构化行程缺失项（空串=合格），纯代码不调LLM。 */
    private String aggregateGap(TripPlanData plan) {
        if (plan == null) return "整体为空";
        java.util.List<String> gaps = new java.util.ArrayList<>();
        if (plan.getSections() == null || plan.getSections().isEmpty()) {
            gaps.add("sections(行程区块)为空");
        } else {
            boolean hasTimeline = plan.getSections().stream()
                    .anyMatch(s -> "timeline".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty());
            if (!hasTimeline) gaps.add("缺少每日行程timeline区块");
            boolean hasStay = plan.getSections().stream()
                    .anyMatch(s -> "stay".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty());
            if (!hasStay) gaps.add("缺少住宿餐饮(stay)区块");
        }
        if (plan.getTitle() == null || plan.getTitle().isBlank()) gaps.add("标题");
        if (plan.getDestination() == null || plan.getDestination().isBlank()) gaps.add("目的地");
        if (plan.getDays() < 1) gaps.add("天数");
        return String.join("、", gaps);
    }

    /** 三域结构化候选区块（聚合提示词拼装；无候选返回空串） */
    private String buildCandidatesSection(TravelState state) {
        if (state == null || state.getCandidatesJson().isBlank()) return "";
        Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());
        String text = utils.CandidateUtil.toPromptText(cands, 3);
        if (text.isBlank()) return "";
        String groupHint = utils.CandidateUtil.areaGroupHint(cands); // ★区域分组（纯代码聚类，同组尽量同一天）
        return "\n\n【三域候选（结构化，已按需求筛选，含置信度）】\n" + text
                + (groupHint.isBlank() ? "" : "\n\n" + groupHint)
                + "\n（候选来自真实查询，行程安排优先从中选取；⚠标记项在 note 注明\"建议出发前再次确认\"）";
    }

    /** 多方案风格定义（style key / 展示名 / 聚合风格约束文案）：aggregateMulti 与 Reviewer 重跑共用，保证重跑不丢风格 */
    private static final String[][] PLAN_STYLES = {
            {"food", "美食向", "本次方案为「美食向」：以地道美食为主线，突出必吃餐厅、夜市、小吃街，行程围绕用餐地点安排，每个时段尽量给出具体餐厅/小吃推荐。"},
            {"photo", "出片向", "本次方案为「出片向」：以拍照出片为主线，突出网红打卡点、绝美风景、日出日落最佳机位，每个景点标注拍摄建议。"},
            {"budget", "省钱向", "本次方案为「省钱向」：以性价比为主线，突出免费景点、经济住宿、公共交通、平价美食，控制总预算。"},
    };

    /** Reviewer 修正重跑上限（轮） */
    private static final int MAX_REVIEW_ROUNDS = 2;

    /** 3 套差异化方案并行聚合（美食向/出片向/省钱向） */
    private PlanSet aggregateMulti(TravelState state) {
        TripRequest req = state.getRequest();
        String routeResult = state.getRouteResult();
        String planResult = state.getPlanResult();
        String[][] styles = PLAN_STYLES;
        // 并行提交 3 套聚合（PARALLEL_POOL 已 4 线程）
        @SuppressWarnings("unchecked")
        Future<TripPlanData>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures[i] = PARALLEL_POOL.submit(() ->
                    aggregateWithStyle(state, styles[idx][2]));
        }
        PlanSet set = new PlanSet();
        int successCount = 0;
        for (int i = 0; i < 3; i++) {
            TripPlanData plan = null;
            try {
                // ★中断感知：轮询等待，点中断立即生效（原 300s 一次性阻塞，中断要等聚合跑完）
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
                while (true) {
                    checkCancel();
                    long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainMs <= 0) { plan = null; break; }
                    try {
                        plan = futures[i].get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException te) {
                        // 回到循环头重新检查中断
                    }
                }
            } catch (CancelledException ce) {
                for (Future<TripPlanData> f : futures) f.cancel(true); // ★用户中断：取消全部并行聚合
                throw ce;
            } catch (Exception e) {
                log.error("方案「{}」聚合失败: {}", styles[i][1], e.getMessage());
            }
            if (plan == null) {
                plan = fallbackPlan(req, routeResult, planResult); // 该套失败退回 markdown 兜底
                plan.setTitle(req.getDestination() + req.getDays() + "日游（" + styles[i][1] + "）");
            }
            set.getPlans().add(plan);
            set.getStyles().add(styles[i][0]);
            set.getSlogans().add(styles[i][1]);
            if (plan.getSections() != null && !plan.getSections().isEmpty()) {
                successCount++;
            }
        }
        // 全部结构化失败则返回 null（调用方兜底为单方案）
        return successCount > 0 ? set : null;
    }

    /** 风格约束文案（style=null 返回空串） */
    private String buildStyleHint(String style) {
        if (style == null) {
            return "";
        }
        return "\n【风格约束】" + style + "\n";
    }

    /** 聚合失败：退回原文 markdown，sections 留空供前端 markdown 兜底 */
    private TripPlanData fallbackPlan(TripRequest req, String routeResult, String planResult) {
        TripPlanData plan = new TripPlanData();
        plan.setTitle(req.getDestination() + req.getDays() + "日游计划");
        plan.setDestination(req.getDestination());
        plan.setDays(req.getDays());
        plan.setMarkdown(joinRaw(req, routeResult, planResult));
        log.warn("结构化聚合失败，退回 markdown 原文兜底");
        return plan;
    }

    private String joinRaw(TripRequest req, String routeResult, String planResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(req.getDestination()).append(req.getDays()).append("日游计划\n\n");
        if (!routeResult.isBlank()) {
            sb.append("## 路线\n\n").append(routeResult).append("\n\n");
        }
        if (!planResult.isBlank()) {
            sb.append("## 行程\n\n").append(planResult).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 工具方法 ====================

    /** 请求 JSON 输出的单次裸 LLM 调用 */
    private String callModelJson(String sysPrompt, String userPrompt) {
        // 所有裸 LLM 调用统一走 LlmClient；先绑定会话上下文，让 LLM 调用链 span 归到本任务
        utils.SafeModel.setSessionId(sessionId);
        return utils.LlmClient.chatJson(sysPrompt, userPrompt);
    }

    /** 从 ChatResponse 提取文本（遍历 TextBlock） */
    private String textOf(ChatResponse resp) {
        if (resp == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    /** 聚合输入精简：超长文本截断至 maxLen，固定 prompt 规模，防上下文爆炸/超时 */
    private String clip(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n…(数据过长已截断)…";
    }

    /** 聚合输入精简：默认上限 */
    private String clip(String s) {
        return clip(s, 6000);
    }

    /** 结构化行程 → markdown（降级字段 + 旧前端兼容） */
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
                    case "stay" -> sb.append(it.getName()).append("（").append(it.getCategory())
                            .append(it.getPrice().isBlank() ? "" : " " + it.getPrice()).append("）");
                    case "tips" -> sb.append(it.getKind()).append("：").append(it.getText());
                    default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}