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

    /** 执行编排核心（个人中心沉淀偏好可 null）：解析需求 → 并行调用专家（候选/路线就绪）→ 行程Agent一次定稿 → Reviewer闭环。全程以 TravelState 为唯一事实源。
     *  ★删聚合（2026-09-05）：行程Agent 合并聚合职责——候选+路线数据在其定稿前全部就绪（候选前置查询+路线按簇/跨城先行），
     *  它基于全量信息一次输出结构化终版【行程JSON】块；主管只做解析/校验/审查，不再有"聚合LLM二次整合"环节。 */
    private TripPlanData runCore(String userPrompt, TripRequest.Preferences prefs) {
        TravelState state = resolveAndFetch(userPrompt, prefs, true);
        // ★输入侧缺失亮红灯：哪路专家数据为空，定稿前明确告知（避免静默产出半成品还显示成功）
        String missingInput = missingInputs(state.getRouteResult(), state.getPlanResult());
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        EventStore.add(sessionId, "思考", "行程Agent 正在基于全量信息编排最终行程...");
        // ★删聚合：行程Agent 回复中提取结构化终版【行程JSON】块 → TripPlanData；无块/解析失败降级原文兜底
        TripPlanData plan = parseTripPlan(state);
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) {
            plan = fallbackPlan(state.getRequest(), state.getRouteResult(), state.getPlanResult()); // 兜底已含完整 markdown 原文
            EventStore.add(sessionId, "整合", "行程Agent未返回结构化终版JSON，降级为原文交付");
        } else {
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段
            // ④ Reviewer 闭环：每轮审查前先跑确定性校验+六维评分（纯代码，作为审查依据）→ REVISE 定向重编（≤2轮）
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
            EventStore.add(sessionId, "校验", label + " 校验结果 " + vr.summary() + vr.checksBrief());
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

    /** Reviewer 单次审查：独立 LLM 审查员按检查清单输出结构化裁定。
     *  ★⑦⑧作为审查依据（2026-09-05）：输入含确定性校验完整摘要（已执行/跳过检查清单）+ 六维评分结果（纯代码），
     *  审查员基于 校验报告+评分 判断哪不合格、给 REVISE 定向反馈；评分是审查输入而非终版补算。 */
    private ReviewVerdict reviewPlan(TripRequest req, TripPlanData plan, dto.ValidationReport vr, dto.ScoreResult sc) {
        utils.SafeModel.setStage("行程审查");
        String user = "【用户需求】" + (req == null || req.getRaw() == null ? "" : req.getRaw())
                + "\n\n【确定性校验结果】" + (vr == null ? "未执行" : vr.detailedSummary())
                + "\n\n【六维评分】" + (sc == null ? "未执行" : sc.getSummary())
                + "\n\n【行程JSON】\n" + clip(utils.JsonUtils.toJson(plan), 6000);
        String out = callModelJson(AgentPrompts.tripReviewer(), user);
        return ReviewVerdict.parse(out); // null=解析失败，调用方按 PASS 兜底
    }

    /** Reviewer 闭环：审查 → REVISE 定向重编（★删聚合：重编=重调行程Agent带反馈，上版JSON为局部修改基准）→ 再审，上限 MAX_REVIEW_ROUNDS 轮。
     *  style 为多方案风格文案（保持重跑不丢风格），单方案传 null。
     *  审查器/重跑故障均不阻断交付（保留当前版本）。 */
    private TripPlanData reviewerLoop(TravelState state, TripPlanData plan, String style) {
        TripRequest req = state.getRequest();
        TripPlanData current = plan;
        boolean routeOk = state.getRouteResult() != null && !state.getRouteResult().isBlank();
        boolean planOk = state.getPlanResult() != null && !state.getPlanResult().isBlank();
        for (int round = 1; round <= MAX_REVIEW_ROUNDS; round++) {
            checkCancel();
            dto.ValidationReport vr = null;
            try {
                vr = utils.ConstraintEngine.validate(req, current, state.getCandidatesJson());
            } catch (Exception ignored) {
            }
            if (vr != null) { // ★审查轮内校验也入事件流：看每轮重跑前后质量变化，不用翻日志
                EventStore.add(sessionId, "校验", "第" + round + "轮审查前校验 " + vr.summary() + vr.checksBrief());
            }
            // ★⑦⑧ 审查依据（纯代码，零成本）：确定性校验 + 六维评分 → 喂审查员
            dto.ScoreResult sc = null;
            try {
                sc = utils.PlanScorer.score(req, current, vr, routeOk, planOk);
                EventStore.add(sessionId, "评分", "第" + round + "轮审查前 " + sc.getSummary());
            } catch (Exception e) {
                log.warn("第{}轮审查前评分异常(不阻断): {}", round, e.getMessage());
            }
            ReviewVerdict v = reviewPlan(req, current, vr, sc);
            if (v == null) {
                EventStore.add(sessionId, "审查", "第" + round + "轮审查输出异常，按通过处理");
                ReviseSummary.recordRound(sessionId, round, "PARSE_ERROR", -1, null, "accepted"); // 解析失败按通过收尾
                ReviseSummary.finish(sessionId, "parse_error");
                break;
            }
            if (!v.needsRevise()) {
                EventStore.add(sessionId, "审查", "第" + round + "轮审查通过"
                        + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : "")
                        + (v.getIssues().isEmpty() ? "" : "（" + v.getIssues().size() + " 条轻微建议已记录）"));
                ReviseSummary.recordRound(sessionId, round, "PASS", v.getScore(), null, "accepted");
                ReviseSummary.finish(sessionId, round == 1 ? "pass_first" : "pass_after_rework");
                break;
            }
            EventStore.add(sessionId, "审查", "第" + round + "轮审查 REVISE"
                    + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : "") + "：" + v.feedbackText());
            if (round == MAX_REVIEW_ROUNDS) {
                EventStore.add(sessionId, "审查", "已达修正上限(" + MAX_REVIEW_ROUNDS + " 轮)，交付当前版本");
                ReviseSummary.recordRound(sessionId, round, "REVISE", v.getScore(), snapshotIssues(v), "maxed_rework");
                ReviseSummary.finish(sessionId, "maxed");
                break;
            }
            ReviseSummary.recordRound(sessionId, round, "REVISE", v.getScore(), snapshotIssues(v), "rework");
            EventStore.add(sessionId, "审查", "带审查反馈重编行程（第 " + round + " 次修正）...");
            // ★审查补查：问题涉及交通/衔接(domain=transport 或 type=route)时，先按当前方案景点补查真实路线，
            //   回填 routeResult 再重编——行程Agent 改完行程后衔接数据不过期、不靠模型脑补
            if (needRouteRefetch(v)) {
                refetchRouteForIssues(state, current);
            }
            TripPlanData fixed;
            try {
                fixed = repairPlanByAgent(state, style, v.feedbackText(), current);
            } catch (Exception e) {
                log.warn("Reviewer 修正重编异常: {}", e.getMessage());
                EventStore.add(sessionId, "审查", "修正重编异常，交付当前版本");
                ReviseSummary.finish(sessionId, "rework_failed");
                break;
            }
            if (fixed == null) {
                EventStore.add(sessionId, "审查", "修正重编未产出更优结果，保留上一版");
                ReviseSummary.finish(sessionId, "rework_no_improvement");
                break;
            }
            fixed.setMarkdown(renderMarkdown(fixed));
            current = fixed;
        }
        return current;
    }

    /** 审查问题是否涉及交通/衔接（domain=transport 或 type=route 或 type=schedule 中提及衔接）→ 需要补查真实路线 */
    private boolean needRouteRefetch(ReviewVerdict v) {
        if (v == null || v.getIssues() == null || v.getIssues().isEmpty()) return false;
        for (ReviewVerdict.Issue is : v.getIssues()) {
            if (is == null) continue;
            String dom = is.domain == null ? "" : is.domain.trim().toLowerCase();
            String typ = is.type == null ? "" : is.type.trim().toLowerCase();
            String reason = is.reason == null ? "" : is.reason;
            if ("transport".equals(dom) || "route".equals(typ) || "route".equals(dom)) return true;
            // reason 明确提及衔接/交通/绕路时也触发（审查器未标 domain 的兜底）
            if (reason.contains("衔接") || reason.contains("交通") || reason.contains("绕路")
                    || reason.contains("步行") || reason.contains("公交")) return true;
        }
        return false;
    }

    /** ★审查补查：按当前方案从结构化行程提取景点清单 → 重调路线Agent查真实衔接 → 回填 routeResult。
     *  仅市内（sights 非空走 urban 衔接模式）；跨城审查补查保持原样（城市序列不变时无需重查）。 */
    private void refetchRouteForIssues(TravelState state, TripPlanData plan) {
        try {
            TripRequest req = state.getRequest();
            if (req == null || plan == null) return;
            boolean crossCity = req.getOrigin() != null && !req.getOrigin().isBlank()
                    && req.getDestination() != null && !req.getDestination().isBlank()
                    && !req.getOrigin().trim().equals(req.getDestination().trim());
            if (crossCity) return; // 跨城：路线决定途经城市，聚合若改城市属结构性变化，走缓存键失效重建，不在此补查
            String sights = sightsFromPlan(plan);
            if (sights.isBlank()) return; // 结构化行程无景点 → 无清单可查
            EventStore.add(sessionId, "路线", "审查发现交通/衔接问题，按当前方案景点补查真实路线...");
            String freshRoute = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callRouteMakingAgent(routeTask(req, sights))), "路线Agent·审查补查");
            if (freshRoute != null && !freshRoute.isBlank()) {
                state.setRouteResult(freshRoute); // 回填：后续行程Agent重编用新衔接数据
                state.addTrace("路线", "审查补查: 已按当前方案重查衔接");
                EventStore.add(sessionId, "路线", "路线补查完成(衔接数据已更新)");
            } else {
                EventStore.add(sessionId, "路线", "路线补查失败(沿用原衔接数据)");
            }
        } catch (Exception e) {
            log.warn("审查补查路线异常(忽略，沿用原衔接): {}", e.getMessage());
        }
    }

    /** 从结构化行程提取景点清单（timeline 区块的 place，去重，最多 5 个） */
    private String sightsFromPlan(TripPlanData plan) {
        if (plan == null || plan.getSections() == null) return "";
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (dto.Section sec : plan.getSections()) {
            if (sec == null || sec.getItems() == null) continue;
            for (dto.SectionItem it : sec.getItems()) {
                if (it == null || it.getPlace() == null || it.getPlace().isBlank()) continue;
                seen.add(it.getPlace().trim());
                if (seen.size() >= 5) break;
            }
            if (seen.size() >= 5) break;
        }
        return String.join(",", seen);
    }

    /** 多方案编排：候选/路线就绪后并行调 3 次行程Agent（美食向/出片向/省钱向）供前端对比。 */
    public PlanSet runMultiScheme(String userPrompt) {
        // ★多方案：跳过默认定稿（withPlanAgent=false），3套风格在 aggregateMulti 内并行定稿，避免白跑一次
        TravelState state = resolveAndFetch(userPrompt, null, false);
        String missingInput = missingInputs(state.getRouteResult(), ""); // 多方案仅路线前置必须齐全
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        EventStore.add(sessionId, "思考", "正在基于候选与衔接数据规划多套差异化方案...");
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
     *  @param withPlanAgent true=单方案：候选/路线就绪后再调行程Agent一次定稿并写缓存；
     *                       false=多方案：只备齐候选/路线（行程Agent 由调用方按风格并行定稿，不写缓存）。
     *  全部产物（解析结果/专家原文/跨Agent提取的途经点与景点）沉淀进 TravelState 统一状态中心。 */
    private TravelState resolveAndFetch(String userPrompt, TripRequest.Preferences prefs, boolean withPlanAgent) {
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
            // ① 路线Agent 先行（多候选路线，行程Agent 择优）
            String routeResult = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callRouteMakingAgent(routeTask(req, ""))), "路线Agent");
            state.setRouteResult(routeResult);
            checkCancel();
            String waypoints = extractWaypoints(routeResult);
            state.setWaypoints(waypoints);
            state.addTrace("路线", "途经城市: " + (waypoints.isBlank() ? "未提取到" : waypoints));
            String routeCandidates = extractRouteCandidates(routeResult); // 多条候选路线概要（供行程Agent择优）
            // ② ★候选前置（删聚合配套）：主管直连 8085 并行调度三域专家查候选（终点+途经城），不依赖行程Agent ReAct 内查询
            fetchCandidatesPreflight(state, waypoints, req);
            checkCancel();
            if (withPlanAgent) {
                // ③ 行程Agent 一次定稿：输入=候选+簇+候选路线表 → 输出结构化终版【行程JSON】块
                String planResult = await(PARALLEL_POOL.submit(() ->
                        remoteTool.callTripPlannerAgent(finalPlanTask(state, null, null, null))), "行程Agent");
                state.setPlanResult(planResult);
                // ④ 收割兜底：行程Agent若因候选不足补查过（回复带新候选块）→ 并入状态；正常情况候选已前置注入
                harvestCandidates(state);
                checkCancel();
                // ★失败不缓存：行程Agent未返回(空)时写缓存会污染后续命中(拿到空planResult直接降级)——宁可下次重跑
                if (state.getPlanResult() != null && !state.getPlanResult().isBlank()) {
                    utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,
                            req.getPreference(), cacheFingerprint, state.getRouteResult(), state.getPlanResult(), state.getCandidatesJson());
                }
            }
            return state;
        }
        EventStore.add(sessionId, "行程", "市内"
                + (req.travelTypeLabel().isBlank() ? "游" : "「" + req.travelTypeLabel() + "」")
                + "，先并行查询三域候选...");
        // ① ★候选前置（删聚合配套）：域专家三线程并行查候选（无途经城市），成簇后路线与行程都有全量依据
        fetchCandidatesPreflight(state, "", req);
        checkCancel();
        // ② 路线按簇查衔接：从候选提取"每簇代表景点"清单（坐标5km成簇，代码），路线Agent只查簇间真实衔接
        java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());
        String clusterSights = cands == null ? ""
                : utils.CandidateUtil.clusterRepSights(cands, 8);
        state.setSights(clusterSights);
        state.addTrace("行程", "簇代表景点: " + (clusterSights.isBlank() ? "未提取到（候选缺失/无坐标）" : clusterSights));
        EventStore.add(sessionId, "候选", clusterSights.isBlank()
                ? "候选缺失或无坐标景点，路线按无清单模式自拟"
                : "路线按簇代表景点查衔接：" + clusterSights);
        String routeResult2 = await(PARALLEL_POOL.submit(() ->
                remoteTool.callRouteMakingAgent(routeTask(req, clusterSights))), "路线Agent");
        state.setRouteResult(routeResult2);
        checkCancel();
        if (withPlanAgent) {
            // ③ 行程Agent 一次定稿：输入=候选+簇分组+簇间衔接 → 输出结构化终版【行程JSON】块
            String planFirst = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callTripPlannerAgent(finalPlanTask(state, null, null, null))), "行程Agent");
            state.setPlanResult(planFirst);
            harvestCandidates(state);
            checkCancel();
            // ★失败不缓存：行程Agent未返回(空)时写缓存会污染后续命中(拿到空planResult直接降级)——宁可下次重跑
            if (state.getPlanResult() != null && !state.getPlanResult().isBlank()) {
                utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,
                        req.getPreference(), cacheFingerprint, state.getRouteResult(), state.getPlanResult(), state.getCandidatesJson());
            }
        }
        return state;
    }

    /** 从行程专家回复中收割三域候选 JSON（结构化候选入状态中心；无块则保持空串，聚合按原文兜底）。
     *  候选块随即从正文中剥离：聚合/缓存/降级渲染消费纯行程文本，不再夹带大段 JSON（防超长截断与解析劣化）。
     *  ★观测代码化：回复中无块（门面 LLM 未透传）时改由代码回取行程Agent进程内存档块，结构化候选不依赖 LLM 自觉。 */
    private void harvestCandidates(TravelState state) {
        try {
            String block = utils.CandidateUtil.extractBlock(state.getPlanResult());
            boolean fetched = false;
            if (block.isBlank()) {
                block = fetchTripCandidateBlock(); // ★观测代码化：回复无块 → 代码回取子Agent存档块
                fetched = !block.isBlank();
            }
            if (!block.isBlank()) {
                java.util.Map<String, Object> cands = utils.CandidateUtil.parse(block);
                if (cands != null && !cands.isEmpty()) {
                    state.setCandidatesJson(utils.JsonUtils.toJson(cands));
                    state.setPlanResult(utils.CandidateUtil.stripBlock(state.getPlanResult()));
                    String summary = utils.CandidateUtil.evidenceSummary(cands);
                    String cluster = utils.CandidateUtil.clusterHintSummary(cands); // ★聚类生效可观测（管理端事件流）
                    EventStore.add(sessionId, "候选", "三域候选已入状态中心"
                            + (fetched ? "（候选块由代码回取，行程Agent回复未透传）" : "")
                            + (summary.isBlank() ? "" : "：" + summary)
                            + (cluster.isBlank() ? "" : "｜" + cluster));
                    EventStore.putExtra(sessionId, "clusterStats", utils.CandidateUtil.clusterStats(cands)); // ★任务3：聚类效果结构化统计
                    state.addTrace("候选", "三域候选(结构化,每域随天数)"
                            + (fetched ? "·代码回取" : ""));
                }
            }
        } catch (Exception e) {
            log.warn("候选收割异常(忽略，走原文聚合): {}", e.getMessage());
        }
    }

    /** ★观测代码化：行程门面 LLM 若未把工具返回的【域候选JSON】块透传（模型不可靠，只摘摘要丢块），
     *  主管直连行程Agent /candidate-block 端点，取本进程内由 DomainCandidateService 代码存档的候选块。 */
    private String fetchTripCandidateBlock() {
        try {
            String qs = "sessionId=" + java.net.URLEncoder.encode(sessionId, "UTF-8");
            java.net.URL u = new java.net.URL("http://localhost:8085/candidate-block?" + qs);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");
            java.util.Map<String, Object> root = utils.JsonUtils.parse(b, java.util.Map.class);
            if (root == null) return "";
            Object blk = root.get("block");
            return blk instanceof String s ? s : "";
        } catch (Exception e) {
            log.debug("候选块回取失败(忽略，走原文聚合): {}", e.getMessage());
            return "";
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
        // sightList 非空 → 市内游：候选簇代表景点清单（路线按簇查衔接，供行程Agent编排时使用）
        String dest = (req.getDestination() == null || req.getDestination().isBlank()) ? "" : req.getDestination();
        boolean noOrigin = req.getOrigin() == null || req.getOrigin().isBlank();
        // ★统一状态下发（PDF §三）：子 Agent 围绕 TravelState 决策，不各自解读原始用户文本
        String brief = req.toStateBrief() + "\n";
        if (noOrigin || sightList != null && !sightList.isBlank()) {
            // 市内：路线Agent针对"候选景点簇代表清单"查簇间衔接（候选已前置查好，代码成簇），不再自己猜景点
            String base = brief + "这是 " + dest + " 的" + req.getDays() + "天市内/周边游（用户未提供出发地）。\n";
            if (sightList != null && !sightList.isBlank()) {
                base += "需查询衔接的景点清单（已由代码按候选成簇/终版行程确定）：" + sightList + "。\n"
                        + "★请用 query_route_unified(mode=urban" + (routePrefStr.isBlank() ? "" : ", routePref=" + routePrefStr)
                        + ") 查询这些景点【彼此之间/所在区域之间】的衔接方式"
                        + "（短距步行/长距公交地铁，给出大致耗时），供行程Agent按真实衔接串联每日；"
                        + "不要额外添加不在清单里的景点，也不要编造跨城里程。\n";
            } else {
                base += "请规划在市内及周边的行程：\n"
                        + "★调用 query_route_unified，mode 固定传 urban" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                        + "（市内衔接：步行/公交），查询市内相邻景点/地点间的衔接方式，不要编造跨城里程。\n";
            }
            base += "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数。\n"
                    + "最终回复：完整可读的市内衔接方案，含代表景点间的衔接方式与大致耗时（供行程Agent编排引用）。";
            return base;
        }
        String driveHint = switch (req.getTravelType() == null ? "" : req.getTravelType()) {
            case "LOOP", "SELF_DRIVE" -> "（环线游：每日路线尽量不走回头路，环形串联）";
            default -> "";
        };
        return brief + "请规划从 " + req.getOrigin() + " 到 " + dest + " 的自驾方案，"
                + req.getDays() + " 天" + driveHint + "。\n"
                + "★请给出【2~3 条候选路线方案】（单条路线可能错过好景点，须供行程Agent择优选择）：\n"
                + "每条方案用以下固定格式分条标注：\n"
                + "★候选路线一：途经城市：城市A,城市B,城市C\n"
                + "每日概要：第1天…；第2天…（逐日简述行车段与停留点）\n"
                + "（候选路线二/三同格式；各方案走不同途经城市组合与节奏，不要雷同）\n"
                + "★调用 query_route_unified，mode 参数driving" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                + "（自驾路线与距离），查询每条候选方案各段自驾线路的真实里程/时长。\n"
                + "★严格限制：每条候选路线的总天数必须正好 " + req.getDays() + " 天，不得增加；"
                + "若某条长线 " + req.getDays() + " 天跑不完，则该方案应选择适合的缩短路线（或减少景点），天数只允许少不允许多。\n"
                + "最终回复：完整列出各候选路线方案（含途经城市与逐日概要），不要只给单一定稿路线。";
    }

    /** ★删聚合·行程Agent定稿任务（原 planTask 职责并入）：候选已由主管前置查询并注入（【候选预置】标记），
     *  行程Agent 基于 候选+簇分组+簇间衔接(市内)/候选路线表(跨城) 一次输出结构化终版【行程JSON】块。
     *  @param style 多方案风格（PLAN_STYLES[i][2]，null=默认）；@param feedback 审查反馈（REVISE重编时，null=首次）；
     *  @param prevPlanJson 上版行程JSON（审查重编的局部修改基准，null=无）。 */
    private String finalPlanTask(TravelState state, String style, String feedback, String prevPlanJson) {
        TripRequest req = state.getRequest();
        String brief = req.toStateBrief() + "\n";
        String waypoints = state.getWaypoints();
        StringBuilder sb = new StringBuilder();
        sb.append("【候选预置】三域候选已由主管查好（见下方【三域候选】区）。禁止调用 query_trip_candidates 重复查询（重复=浪费与等待）；仅当某域候选明显不足（degraded 或数量<3）时才允许补查一次。\n\n");
        sb.append(brief);
        sb.append("用户需求：").append(req.getRaw()).append("\n");
        if (style != null && !style.isBlank()) {
            sb.append("\n【风格约束】").append(style).append("\n");
        }
        if (waypoints != null && !waypoints.isBlank()) {
            // 跨城：候选路线择一为主线 + 按主线途经城市安排（前段途经、后段终点）
            String routeCandidates = extractRouteCandidates(state.getRouteResult());
            sb.append("请规划从 ").append(req.getOrigin()).append(" 到 ").append(req.getDestination())
                    .append(" 的").append(req.getDays()).append("天行程，重点规划【终点城市】与【途经城市】的景点，不要规划起点城市 ")
                    .append(req.getOrigin()).append(" 内部的景点（起点只是出发地）。\n");
            if (!routeCandidates.isBlank()) {
                sb.append("路线Agent给出多条候选路线方案，择一作为主线：\n").append(routeCandidates).append("\n");
            }
            sb.append("【你选定主线的途经城市】").append(waypoints)
                    .append("。每日安排与所选路线的途经城市/行车段一致，跨城衔接以路线Agent返回为准，禁止编造跨城里程。\n");
        } else {
            sb.append("请规划 ").append(req.getDestination()).append(" ").append(req.getDays())
                    .append(" 日游的景点与每日行程，包含景点、门票、住宿、餐饮美食");
            String pref = req.getTripPref();
            if (pref != null && !pref.isBlank()) {
                sb.append("，偏好：").append(pref);
            }
            sb.append("。\n");
            // 市内：路线Agent已按簇代表查过真实衔接 → 行程Agent编排必须依据它（不再事后补查）
            String route = state.getRouteResult();
            if (route != null && !route.isBlank()) {
                sb.append("【簇间衔接（路线Agent按候选景点簇查询的真实交通：步行/公交/时长）】\n")
                        .append(clip(route, 3000)).append("\n");
            }
        }
        // 三域候选可读清单 + 区域分组（复用原聚合拼装；候选JSON原文已由代码存入状态中心，此处只注入可读摘要供选点）
        String candSection = buildCandidatesSection(state);
        if (!candSection.isBlank()) {
            sb.append(candSection).append("\n");
        }
        if (feedback != null && !feedback.isBlank()) {
            sb.append("\n【审查反馈】上一版行程经独立审查发现问题：").append(feedback)
                    .append("\n本次只修复上述问题；未涉及的天与区块保持原样（增量修正铁律，禁止无中生有全盘重排）。\n");
        }
        if (prevPlanJson != null && !prevPlanJson.isBlank()) {
            sb.append("上一版行程JSON（修改基准，仅修订反馈涉及部分）：\n")
                    .append("【行程JSON】\n").append(prevPlanJson).append("\n【行程JSON结束】\n");
        }
        sb.append("\n最终回复：完整可读的行程文本（含每日安排/衔接/住宿餐饮/天气），末尾附【行程JSON】结构化块（格式见系统规则）。");
        return sb.toString().trim();
    }

    /** ★候选前置查询（删聚合配套）：主管直连 8085 域专家并行查三域候选（域间3线程），
     *  结果写入 state.candidatesJson（结构化，含证据/置信度），供路线按簇查衔接与行程Agent定稿共用。
     *  失败不阻断：state.candidatesJson 保持空，路线按无清单自拟、行程Agent 自行补查兜底。 */
    private void fetchCandidatesPreflight(TravelState state, String waypoints, TripRequest req) {
        String pref = req.getTripPref() == null ? "" : req.getTripPref();
        String n = String.valueOf(utils.CandidateUtil.candidateCountPerDomain(String.valueOf(req.getDays())));
        EventStore.add(sessionId, "候选", "候选前置查询(域专家三线程并行，每域" + n + "个)"
                + (waypoints == null || waypoints.isBlank() ? "" : " · 途经:" + waypoints) + " ...");
        try {
            String qs = "destination=" + java.net.URLEncoder.encode(
                    req.getDestination() == null ? "" : req.getDestination(), "UTF-8")
                    + "&days=" + req.getDays()
                    + "&preference=" + java.net.URLEncoder.encode(pref, "UTF-8")
                    + "&waypoints=" + java.net.URLEncoder.encode(waypoints == null ? "" : waypoints, "UTF-8")
                    + "&brief=" + java.net.URLEncoder.encode(req.toStateBrief(), "UTF-8")
                    + "&sessionId=" + java.net.URLEncoder.encode(sessionId, "UTF-8");
            java.net.URL u = new java.net.URL("http://localhost:8085/trip/candidates?" + qs);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(300000); // 域专家并行最长120s + 置信度重试
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");
            java.util.Map<String, Object> root = utils.JsonUtils.parse(body, java.util.Map.class);
            String reply = root == null ? null : str(root.get("reply"));
            String block = reply == null ? "" : utils.CandidateUtil.extractBlock(reply);
            java.util.Map<String, Object> cands = block.isBlank() ? null : utils.CandidateUtil.parse(block);
            if (cands != null && !cands.isEmpty()) {
                state.setCandidatesJson(utils.JsonUtils.toJson(cands));
                String summary = utils.CandidateUtil.evidenceSummary(cands);
                String cluster = utils.CandidateUtil.clusterHintSummary(cands);
                EventStore.add(sessionId, "候选", "三域候选已前置入状态"
                        + (summary.isBlank() ? "" : "：" + summary)
                        + (cluster.isBlank() ? "" : "｜" + cluster));
                EventStore.putExtra(sessionId, "clusterStats", utils.CandidateUtil.clusterStats(cands));
                state.addTrace("候选", "三域候选(结构化,前置查询,每域" + n + ")");
                return;
            }
            EventStore.add(sessionId, "候选", "候选前置查询失败/无结构化结果，行程Agent将自行补查兜底");
        } catch (Exception e) {
            log.warn("候选前置查询异常(行程Agent自行补查兜底): {}", e.getMessage());
            EventStore.add(sessionId, "候选", "候选前置查询异常(" + e.getMessage() + ")，行程Agent将自行补查兜底");
        }
    }

    /** ★删聚合·解析行程Agent终版：【行程JSON】块 → TripPlanData；补齐标题/目的地/天数/天气；完整性缺口返回 null（外层降级原文）。 */
    private TripPlanData parseTripPlan(TravelState state) {
        String reply = state.getPlanResult();
        if (reply == null || reply.isBlank()) return null;
        String block = utils.CandidateUtil.extractPlanBlock(reply);
        if (block.isBlank()) return null;
        TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);
        if (plan == null) return null;
        // 完整性缺口（原 aggregateGap 语义：sections空/timeline缺/stay缺 → 视为不可交付）
        String gap = aggregateGap(plan);
        if (!gap.isBlank()) {
            EventStore.add(sessionId, "整合", "行程JSON缺关键内容(" + gap + ")，降级为原文兜底");
            return null;
        }
        normalizePlan(state, plan);
        return plan;
    }

    /** 标题/目的地/天数/天气补齐（parse 与 REVISE 重编共用）；天气：专家文本优先 → 高德直连兜底（防编造）。 */
    private void normalizePlan(TravelState state, TripPlanData plan) {
        TripRequest req = state.getRequest();
        if (plan.getTitle() == null || plan.getTitle().isBlank()) {
            plan.setTitle(req.getDestination() + req.getDays() + "日游");
        }
        if (plan.getDestination() == null || plan.getDestination().isBlank()) {
            plan.setDestination(req.getDestination());
        }
        if (plan.getDays() < 1) {
            plan.setDays(req.getDays());
        }
        String routeResult = state.getRouteResult();
        String planResult = state.getPlanResult();
        // ★天气提取前必须剥离【行程JSON】块：否则正则会把块内 JSON 字段("weather":"...","place":...)当天气抓到 weather 字段
        String planText = planResult == null ? "" : utils.CandidateUtil.stripPlanBlock(planResult);
        String weatherRaw = cleanWeather(extractWeatherRaw((routeResult == null ? "" : routeResult)
                + "\n" + planText));
        if (weatherRaw == null || weatherRaw.isBlank()) {
            String direct = fetchAmapWeather(req.getDestination(), Math.min(req.getDays(), 7));
            if (direct != null && !direct.isBlank()) {
                weatherRaw = direct;
                EventStore.add(sessionId, "整合", "天气直连高德预报：" + direct);
            }
        }
        if (weatherRaw != null && !weatherRaw.isBlank()) {
            plan.setWeather(weatherRaw); // 覆盖行程Agent值（已清洗）
            EventStore.add(sessionId, "整合", "天气透传：" + weatherRaw);
        } else if (plan.getWeather() != null && !plan.getWeather().isBlank()) {
            plan.setWeather(cleanWeather(plan.getWeather()));
        }
    }

    /** ★删聚合·REVISE 重编：带审查反馈重调行程Agent（上版JSON为局部修改基准，候选仍前置注入）→ 提取新终版。失败返回 null（调用方保留上版）。 */
    private TripPlanData repairPlanByAgent(TravelState state, String style, String feedback, TripPlanData prev) {
        EventStore.add(sessionId, "审查", "带审查反馈重调行程Agent重编（候选已就绪，局部修正）...");
        String task = finalPlanTask(state, style, feedback,
                prev == null ? null : utils.JsonUtils.toJson(prev));
        String reply = await(PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(task)), "行程Agent(审查重编)");
        if (reply == null || reply.isBlank()) return null;
        String block = utils.CandidateUtil.extractPlanBlock(reply);
        if (block.isBlank()) return null;
        TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) {
            EventStore.add(sessionId, "审查", "重编未产出结构化终版JSON，保留上一版");
            return null;
        }
        normalizePlan(state, plan);
        return plan;
    }

    /** 从路线结果提取途经城市名（带市/区/县/镇/州后缀，排除起终点，最多5个）。
     *  ★2026-09-05 收紧：优先只取 routeTask 约定的"途经城市："行（候选路线固定格式，逗号分隔干净城市名），
     *  不再全文正则扫描——否则每日概要里的"下午抵惠城区/含部分景区"等噪音会进 waypoints 污染候选查询与行程任务。 */
    private String extractWaypoints(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return "";
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        // ① 优先：固定格式行（可多行=多条候选路线，合并去重）
        java.util.regex.Matcher line = java.util.regex.Pattern
                .compile("途经城市[：:]\\s*([^\\n\\r]{0,120})")
                .matcher(routeResult);
        while (line.find() && seen.size() < 5) {
            for (String tok : line.group(1).split("[,，、;；\\s]+")) {
                String t = tok.trim();
                if (t.isBlank()) continue;
                if (t.length() <= 6 && t.matches(".*(?:市|区|县|镇|州)$")) {
                    seen.add(t);
                    if (seen.size() >= 5) break;
                }
            }
        }
        // ② 兜底：无"途经城市："行时退回旧全文正则（老格式/自由文本路线）
        if (seen.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("[\\u4e00-\\u9fa5]{2,6}?(?:市|区|县|镇|州)")
                    .matcher(routeResult);
            while (m.find() && seen.size() < 5) {
                seen.add(m.group());
            }
        }
        // 剔除起终点城市名本身
        seen.removeIf(n -> n.contains("起点") || n.contains("终点"));
        return String.join(",", seen);
    }

    /** 从路线结果提取多条候选路线概要（格式见 routeTask："★候选路线一：途经城市：A市,B市..."），
     *  供行程Agent择优选择主线；提不到时返回空串（行程Agent按默认 waypoints 编排）。 */
    private String extractRouteCandidates(String routeResult) {
        if (routeResult == null || routeResult.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "★?\\s*候选路线[一二三四1234]+[：:].*?途经城市[：:]\\s*([^\\n\\r]+)");
        java.util.regex.Matcher m = p.matcher(routeResult);
        int idx = 1;
        while (m.find() && idx <= 3) {
            String cities = m.group(1).trim();
            if (!cities.isBlank()) {
                sb.append("候选路线").append(idx).append("：途经城市 ").append(cities).append("\n");
                idx++;
            }
        }
        return sb.toString();
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

    // ==================== ③ 专家产物与终版解析（★删聚合：无LLM二次整合环节） ====================

    /** 输入侧缺失描述：路线/行程哪路为空返回对应文案，齐全返回空串 */
    private String missingInputs(String routeResult, String planResult) {
        boolean noRoute = routeResult == null || routeResult.isBlank();
        boolean noPlan = planResult == null || planResult.isBlank();
        if (noRoute && noPlan) return "路线与行程数据均缺失";
        if (noRoute) return "路线数据缺失";
        if (noPlan) return "行程数据缺失";
        return "";
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
        int perDomain = utils.CandidateUtil.candidateCountPerDomain(
                state.getRequest() == null ? null : String.valueOf(state.getRequest().getDays()));
        String text = utils.CandidateUtil.toPromptText(cands, perDomain);
        if (text.isBlank()) return "";
        String groupHint = utils.CandidateUtil.coordinateGroupHint(cands); // ★坐标聚类（P1b：真实坐标邻近分组）
        if (groupHint.isBlank()) groupHint = utils.CandidateUtil.areaGroupHint(cands); // 坐标缺失回退文本分组
        return "\n\n【三域候选（结构化，已按需求筛选，含置信度）】\n" + text
                + (groupHint.isBlank() ? "" : "\n\n" + groupHint)
                + "\n（候选来自真实查询，行程安排优先从中选取；⚠标记项在 note 注明\"建议出发前再次确认\"）";
    }

    /** ReviewVerdict issues → 观测快照（只取前 6 条，与 feedbackText 口径一致） */
    private static java.util.List<Map<String, Object>> snapshotIssues(ReviewVerdict v) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (v == null || v.getIssues() == null) return out;
        for (int i = 0; i < v.getIssues().size() && i < 6; i++) {
            out.add(ReviseSummary.issueSnapshot(v.getIssues().get(i)));
        }
        return out;
    }

    /** 多方案风格定义（style key / 展示名 / 风格约束文案）：多方案生成与 Reviewer 重编共用，保证重跑不丢风格 */
    private static final String[][] PLAN_STYLES = {
            {"food", "美食向", "本次方案为「美食向」：以地道美食为主线，突出必吃餐厅、夜市、小吃街，行程围绕用餐地点安排，每个时段尽量给出具体餐厅/小吃推荐。"},
            {"photo", "出片向", "本次方案为「出片向」：以拍照出片为主线，突出网红打卡点、绝美风景、日出日落最佳机位，每个景点标注拍摄建议。"},
            {"budget", "省钱向", "本次方案为「省钱向」：以性价比为主线，突出免费景点、经济住宿、公共交通、平价美食，控制总预算。"},
    };

    /** Reviewer 修正重跑上限（轮） */
    private static final int MAX_REVIEW_ROUNDS = 2;

    /** ★删聚合·3 套差异化方案（美食向/出片向/省钱向）：候选/路线已由 resolveAndFetch 就绪，
     *  并行重调 3 次行程Agent（各带风格约束，同一批专家数据）→ 各自输出结构化终版JSON。
     *  单套失败退回原文兜底（标题带风格后缀）。 */
    private PlanSet aggregateMulti(TravelState state) {
        TripRequest req = state.getRequest();
        String routeResult = state.getRouteResult();
        String planResult = state.getPlanResult();
        String[][] styles = PLAN_STYLES;
        // 并行提交 3 套行程Agent 定稿（PARALLEL_POOL 已 4 线程；候选前置已共享，不重复查询）
        @SuppressWarnings("unchecked")
        Future<TripPlanData>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures[i] = PARALLEL_POOL.submit(() -> planOneStyle(state, styles[idx][2]));
        }
        PlanSet set = new PlanSet();
        int successCount = 0;
        for (int i = 0; i < 3; i++) {
            TripPlanData plan = null;
            try {
                // ★中断感知：轮询等待，点中断立即生效（原 300s 一次性阻塞，中断要等跑完）
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
                for (Future<TripPlanData> f : futures) f.cancel(true); // ★用户中断：取消全部并行方案
                throw ce;
            } catch (Exception e) {
                log.error("方案「{}」生成失败: {}", styles[i][1], e.getMessage());
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

    /** 单套风格方案：调行程Agent（风格注入，候选预置）→ 解析终版JSON；无块/解析失败返回 null（外层降级）。
     *  ★同步 A2A 调用：本方法在 PARALLEL_POOL 内执行，若再向池提交并 await 会与其它方案互等（4线程池死锁）。 */
    private TripPlanData planOneStyle(TravelState state, String style) {
        try {
            String task = finalPlanTask(state, style, null, null);
            String reply = remoteTool.callTripPlannerAgent(task); // 同步阻塞（A2A 自身超时控制）
            if (reply == null || reply.isBlank()) return null;
            String block = utils.CandidateUtil.extractPlanBlock(reply);
            if (block.isBlank()) return null;
            TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);
            if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) return null;
            normalizePlan(state, plan);
            return plan;
        } catch (Exception e) {
            log.warn("方案「{}」行程Agent调用异常: {}", style, e.getMessage());
            return null;
        }
    }

    /** 终版缺失/解析失败：退回原文 markdown（行程Agent可读文本 + 路线原文），sections 留空供前端 markdown 兜底 */
    private TripPlanData fallbackPlan(TripRequest req, String routeResult, String planResult) {
        TripPlanData plan = new TripPlanData();
        plan.setTitle(req.getDestination() + req.getDays() + "日游计划");
        plan.setDestination(req.getDestination());
        plan.setDays(req.getDays());
        plan.setMarkdown(joinRaw(req, routeResult, planResult));
        log.warn("行程终版缺失，退回 markdown 原文兜底");
        return plan;
    }

    /** 原文兜底拼接：行程Agent可读文本（剥离JSON块）+ 路线原文 */
    private String joinRaw(TripRequest req, String routeResult, String planResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(req.getDestination()).append(req.getDays()).append("日游计划\n\n");
        if (!routeResult.isBlank()) {
            sb.append("## 路线\n\n").append(routeResult).append("\n\n");
        }
        String readable = planResult == null ? "" : utils.CandidateUtil.stripPlanBlock(planResult);
        if (!readable.isBlank()) {
            sb.append("## 行程\n\n").append(readable).append("\n");
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