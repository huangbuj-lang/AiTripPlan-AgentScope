package managerAgent.orchestrator;

import managerAgent.service.TripTaskService;
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

// ★2026-09-11 解析层抽出（RoutePlanParser）：显式静态导入，使下方调用点零改动，最大化降低回归风险
import static managerAgent.orchestrator.RoutePlanParser.RouteData;
import static managerAgent.orchestrator.RoutePlanParser.extractTotalDistance;
import static managerAgent.orchestrator.RoutePlanParser.extractWaypoints;
import static managerAgent.orchestrator.RoutePlanParser.parseRouteJson;
import static managerAgent.orchestrator.RoutePlanParser.toInt;

/** 确定性编排器：解析 → 并行 DAG → 聚合，流程由 Java 确定，LLM 只负责内容生成。 */
@Slf4j
public class TripOrchestrator {

    // ★2026-09-11 并发参数对齐（原评审点：并发闸 / PARALLEL_POOL / TASK_POOL 三处互不匹配）：
    //   单个任务内部就要并行 3 套方案 + 天级并行（见下方 planOneStyle / daySkeletonTask 处）。
    //   ★修正（2026-09-11 晚）：池子大小不能只由"闸"决定，而应由【下游 8085 的承受力】决定——
    //   代码原注释实测"4+ 并发 A2A 时后提交的任务会失败"，所以单任务并行需求约 4（3 天并行 + 余量）；
    //   之前按闸×6 放大到 8/64 是越线，会让更多请求同时打到 8085。现按闸×4（闸=1 → 4，即原值）。
    private static final int POOL_CORE = 8;      // ★2026-09-11 4→8：单任务真实并行需求 = 3 天天并行 + 主线程 + 路线/修复，注释自己写的是"约需 6 线程"，
                                                 //   原来只给 4 线程且 core=max（永不扩容）→ 超出即进 256 队列排队，
                                                 //   而排队时间被 await 的 300s 超时计入 → 曾出现"子Agent调用超时"。
                                                 //   真正的下游并发上限由 8085 的 Semaphore(TRIP_AGENT_SLOTS) 把关，放大本池不会压垮子Agent。
    private static final int POOL_MAX_CAP = 16;  // 线程上限（防失控；真正的并发上限由 8085 的信号量把关）
    private static final java.util.concurrent.ThreadPoolExecutor PARALLEL_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    POOL_CORE, POOL_CORE, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(256),
                    r -> { Thread t = new Thread(r, "trip-parallel"); t.setDaemon(true); return t; },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /** ★2026-09-11 线程池大小与并发闸联动：闸=N → 供 N×6 个并行线程（截断到 POOL_MAX_CAP），让闸真正生效。
     *  单任务并行需求（3套方案 + 天级重编并行）约 6 线程/任务，故取 6 倍。 */
    private static volatile int lastPoolSize = -1; // 首次/变化时打一次日志，便于确认联动生效

    private static void syncPoolSize() {
        try {
            int gate = managerAgent.service.TripTaskService.maxConcurrent();
            int target = Math.min(POOL_MAX_CAP, Math.max(POOL_CORE, gate * 6)); // 与"单任务约需 6 线程"一致（原 ×4 会小于实际需求）
            if (PARALLEL_POOL.getCorePoolSize() != target) {
                PARALLEL_POOL.setMaximumPoolSize(target); // 先放宽 max（保证 max >= core，否则抛异常）
                PARALLEL_POOL.setCorePoolSize(target);    // 再对齐 core，线程数即刻与闸一致
            }
            if (lastPoolSize != target) { // 首次或数值变化时记录，便于运维确认联动生效
                log.info("[pool] PARALLEL_POOL = {} 线程（并发闸={}，单任务约需 6 线程）", target, gate);
                lastPoolSize = target;
            }
        } catch (Exception e) {
            log.warn("[pool] 调整 PARALLEL_POOL 失败（忽略，保持原大小）: {}", e.getMessage());
        }
    }

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
        syncPoolSize(); // ★2026-09-11 并发对齐：按当前并发闸调整并行池大小，避免闸调大后仍在旧线程数上排队
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
            EventStore.add(sessionId, "定稿", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        EventStore.add(sessionId, "思考", "行程Agent 正在基于全量信息编排最终行程...");
        // ★删聚合：行程Agent 回复中提取结构化终版【行程JSON】块 → TripPlanData；无块/解析失败降级原文兜底
        TripPlanData plan = parseTripPlan(state);
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) {
            plan = fallbackPlan(state.getRequest(), state.getRouteResult(), state.getPlanResult()); // 兜底已含完整 markdown 原文
            EventStore.add(sessionId, "定稿", "行程Agent未返回结构化终版JSON，降级为原文交付");
            // ★诊断留档（2026-09-06）：回复原文入事件流落库，事后可查"为什么没带 JSON 块"（长文本/格式错/截断）
            if (state.getPlanResult() != null && !state.getPlanResult().isBlank()) {
                EventStore.add(sessionId, "行程原文", "（定稿降级·行程Agent回复全文）\n" + state.getPlanResult());
            }
            // ★观测完整性：降级路径没有可审查的结构化方案，审查闭环客观未执行——必须显式记录而非静默跳过，
            //   否则管理端质量观测缺失审查节点，用户无法区分"审了通过"与"根本没审"
            EventStore.add(sessionId, "审查", "⚠️ 原文降级无结构化方案，审查闭环未执行（仅做确定性校验+评分）");
        } else {
            // ★2026-09-12 区块规整化（实测 bug）：整单路径一次产出 23 个区块，含 3 个逐字重复的
            //   「住宿与餐饮」、7 种命名的 stay 碎片、甚至住宿排在当天行程之前。这里做代码级规整：
            //   ① 去重（type+title+items 完全相同的只留一个）② 按天号排序（同天的 timeline 在前、住宿在后）
            normalizePlanSections(plan, sessionId);
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段
            // ④ Reviewer 闭环：每轮审查前先跑确定性校验+六维评分（纯代码，作为审查依据）→ REVISE 定向重编（≤2轮）
            EventStore.add(sessionId, "审查", "独立审查员正在检查行程质量...");
            plan = reviewerLoop(state, plan, null);
        }
        // ★不可行兜底：单方案模式下额外输出【替代路线】（原目的地物理上完不成时，给用户二选一）
        if (state.getInfeasibleDays() > 0 && plan != null) {
            try {
                EventStore.add(sessionId, "路线", "原目的地往返需约 " + state.getInfeasibleDays()
                        + " 天 > 用户 " + state.getRequest().getDays() + " 天，生成【替代路线】建议（天数内可行）...");
                final TripRequest altReq = state.getRequest();
                final int altNeed = state.getInfeasibleDays();
                String alt = await(PARALLEL_POOL.submit(() ->
                        remoteTool.callRouteMakingAgent(alternativeTask(altReq, altNeed))), "路线Agent·替代");
                if (alt != null && !alt.isBlank()) {
                    // 从【替代路线】标记处截取（去掉路线Agent 的英文思考前言）
                    int mk = alt.indexOf("【替代路线】");
                    String altText = clip(mk >= 0 ? alt.substring(mk) : alt, 1200);
                    plan.setAlternative(altText);
                    EventStore.add(sessionId, "替代路线", altText);
                }
            } catch (Exception e) {
                log.warn("替代路线生成失败(不阻断交付): {}", e.getMessage());
            }
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
        String loopLine = needsReturnLoop(req)
                ? "\n\n【闭环要求】本行程【需要返程闭环】：最后一天必须回到出发地 " + req.getOrigin()
                  + "。若行程未回到出发地、且无合理说明（如明确单程/异地还车），属 high 问题（type=route，target=overall，verdict=REVISE）。"
                : "\n\n【闭环要求】本行程【不需要返程闭环】（单城市游/单程/无出发地），不要因“没有返程”判 REVISE。";
        String user = "【用户需求】" + (req == null || req.getRaw() == null ? "" : req.getRaw())
                + "\n\n【确定性校验结果】" + (vr == null ? "未执行" : vr.detailedSummary())
                + "\n\n【六维评分】" + (sc == null ? "未执行" : sc.getSummary())
                + "\n\n【行程JSON】\n" + clip(utils.JsonUtils.toJson(plan), 6000)
                + loopLine;
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
            // ★分路段候选补查：校验报"第N天内容过少/游玩时长不足"→ 定向补查该天路段候选，合并后重编
            refetchCandidatesForThinDay(state, current, vr);
            TripPlanData fixed = null; // 显式初始化：下面各分支若全部修复失败，fixed 保持 null（调用方会保留上一版）
            try {
                // ★2026-09-11 治本改造：**取消整单重编**（原「仅当反馈涉及多天/整体才回退整单重编」）。
                //
                // 实测依据：A2A 单次长连接约 40s 会被切断（两次实测 39s/40s，几乎同一时点），
                // 即「框架给单次 A2A 调用的预算是 ~40s」。而任务文本大小直接决定耗时：
                //   天骨架单天  ~1.4k 字符 → 20~36s  ✅ 跑得完
                //   整单重编    ~16k 字符  → 必然 >40s ❌ 必然被切断（重发同样失败）
                // 所以「跑不完 40s 的任务」在架构上就不该存在——原来的整单重编正是这种任务，
                // 它一失败，审查发现的缺陷（如"第1天动线严重折返"）就一个都落不了地。
                //
                // 现在统一走「按天修复」：
                //   ① 反馈里有具体天号 → 只修那几天
                //   ② 反馈没提具体天（整体类问题，如"住宿区域不统一"）→ 逐天全量修
                //   ③ 只有非骨架模式（没有按天 JSON）才回退整单（那是它本来的生成方式，另当别论）
                // 顺序执行（不可并发）：repairDayByAgent 每次都会改写 state.dayPlanJson。
                java.util.Set<Integer> days = skeletonDayNos(state, v.feedbackText());
                TripRequest reqR = state == null ? null : state.getRequest();
                int totalDays = reqR == null ? 0 : reqR.getDays();
                java.util.List<String> djR = state == null ? null : state.getDayPlanJson();
                boolean skeletonMode = djR != null && !djR.isEmpty() && djR.size() == totalDays;
                // ★2026-09-11 全局性意见（如"自驾/交通方式/住宿区域统一"）必须贯彻到每一天：
                //   实测踩过——审查说"把全部衔接改写为自驾口径"，但正文只提到某天，于是只修了那天，
                //   第 2 天整段仍是"打车"。所以命中全局关键词时，清空"提到哪天才修哪天"的限定，改为全量。
                boolean global = isGlobalFeedback(v.feedbackText());
                if (global && skeletonMode) {
                    days.clear();
                }
                if (!days.isEmpty()) {
                    EventStore.add(sessionId, "审查", "按天修复（第 "
                            + days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"))
                            + " 天）...");
                    if (days.size() == 1) {
                        fixed = repairDayByAgent(state, style, v.feedbackText(), days.iterator().next());
                    } else {
                        fixed = repairDaysInParallel(state, style, days, v.feedbackText()); // ★并行：原来逐天串行 3 天=282s
                    }
                } else {
                    if (skeletonMode) {
                        EventStore.add(sessionId, "审查", "审查意见未限定具体天，逐天全量修复（避免整单重编超时）...");
                        java.util.Set<Integer> allDays = new java.util.TreeSet<>();
                        for (int d = 1; d <= totalDays; d++) allDays.add(d);
                        fixed = repairDaysInParallel(state, style, allDays, v.feedbackText()); // ★并行
                    } else {
                        // 非骨架模式：本就是整单生成的，保持原路径
                        fixed = repairPlanByAgent(state, style, v.feedbackText(), current);
                    }
                }
            } catch (Exception e) {
                log.warn("Reviewer 修正重编异常: {}", e.getMessage());
                EventStore.add(sessionId, "审查", "修正重编异常，交付当前版本");
                ReviseSummary.finish(sessionId, "rework_failed");
                break;
            }
            if (fixed == null) {
                // ★2026-09-11 致命问题不静默（实测依据）：如果"修复失败 + 当前版本结构本就不达标"，
                //   这不是"小瑕疵保留上一版"，而是**交付一份错行程**（实测：3天请求交付2天、且全程打车无自驾），
                //   之前这种情况只写"保留上一版"，最终报"完成/无降级"，错误无人察觉。
                //   现在只要结构校验不通过就显式标"降级交付"，让 E2E 与用户都能看见。
                TripRequest reqC = state == null ? null : state.getRequest();
                int wantC = reqC == null ? 0 : reqC.getDays();
                String curText = state == null ? null : state.getPlanResult();
                if (!planStructOk(curText, wantC)) {
                    EventStore.add(sessionId, "审查", "⚠️ 降级交付：修复未成功，且当前版本结构校验不通过（应 "
                            + wantC + " 天，实际区块不符）—— 此结果需重新生成，不可直接采用");
                    log.warn("[review] 修复失败且结构校验不通过（应 {} 天），已标降级交付", wantC);
                }
                EventStore.add(sessionId, "审查", "修正重编未产出更优结果，保留上一版");
                ReviseSummary.finish(sessionId, "rework_no_improvement");
                break;
            }
            // ★区块回填（2026-09-06）：整单/天级重编只修审查指出问题，transport/stay/tips 与问题无关、
            //   上一版有效——模型重编若把这类区块写空/漏写（如 tips 空 → 前端"实用贴士"空卡），
            //   用上一版对应区块回填，防交付外观降级
            backfillSideSections(fixed, current);
            fixed.setMarkdown(renderMarkdown(fixed));
            current = fixed;
        }
        return current;
    }

    /** ★重编区块回填：整单/天级重编只修审查指出问题，transport/stay/tips 与问题无关、上一版有效。
     *  模型重编若把这类区块写空/漏写 → 新版该区块空时整体回填上一版对应区块（连 title）。 */
    private void backfillSideSections(TripPlanData fresh, TripPlanData prev) {
        if (fresh == null || prev == null) return;
        for (String typ : new String[]{"transport", "stay", "tips"}) {
            Section fs = findSectionByType(fresh, typ);
            Section ps = findSectionByType(prev, typ);
            if (fs == null || ps == null) continue;
            boolean freshEmpty = fs.getItems() == null || fs.getItems().isEmpty();
            boolean prevFull = ps.getItems() != null && !ps.getItems().isEmpty();
            if (freshEmpty && prevFull) {
                fs.setItems(ps.getItems());
                log.info("重编区块回填 {}：{} 条（新版空→沿用上一版）", typ, ps.getItems().size());
            }
        }
    }

    private static Section findSectionByType(TripPlanData plan, String type) {
        if (plan == null || plan.getSections() == null) return null;
        for (Section s : plan.getSections()) {
            if (s != null && type.equals(s.getType())) return s;
        }
        return null;
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

    /** ★分路段候选补查：确定性校验报"第N天内容过少/游玩时长不足"→ 定向补查该天所在路段候选，合并进 state 候选再重编。
     *  候选先行后某路段候选稀疏是客观的（如5天往返稻城亚丁太赶）；补查该路段让行程Agent有料可排，而非凭空编造。 */
    private void refetchCandidatesForThinDay(TravelState state, TripPlanData plan, dto.ValidationReport vr) {
        try {
            if (state == null || plan == null || vr == null || vr.getIssues() == null) return;
            int thinDay = -1;
            for (dto.ValidationReport.Issue is : vr.getIssues()) {
                String r = is == null || is.getReason() == null ? "" : is.getReason();
                if (r.contains("内容过少") || r.contains("游玩时长")) {
                    int d = parseDayNo(is.getTarget());
                    if (d > 0) { thinDay = d; break; }
                }
            }
            if (thinDay < 1) return;
            String region = regionOfDay(plan, thinDay);
            if (region.isBlank()) return;
            TripRequest req = state.getRequest();
            EventStore.add(sessionId, "候选", "第" + thinDay + "天内容不足，定向补查「" + region + "」景点候选...");
            java.util.Map<String, Object> extra = queryCandidatesOnce(req, region,
                    req == null ? "" : (req.getTripPref() == null ? "" : req.getTripPref()), "attraction");
            if (extra == null || extra.isEmpty() || utils.CandidateUtil.items(extra, "attraction").isEmpty()) {
                EventStore.add(sessionId, "候选", "第" + thinDay + "天补查无新景点候选（该路段确实稀疏）");
                return;
            }
            java.util.Map<String, Object> base = utils.CandidateUtil.parse(state.getCandidatesJson());
            int before = utils.CandidateUtil.items(base, "attraction").size();
            java.util.Map<String, Object> merged = mergeCandidates(state.getCandidatesJson(), extra);
            state.setCandidatesJson(utils.JsonUtils.toJson(merged));
            int after = utils.CandidateUtil.items(merged, "attraction").size();
            EventStore.add(sessionId, "候选", "第" + thinDay + "天路段候选补查完成（景点 " + before + "→" + after + " 个）");
        } catch (Exception e) {
            log.warn("分路段候选补查异常(忽略): {}", e.getMessage());
        }
    }

    /** "第N天"/"dayN"/"DayN" → N；无法解析返回 -1 */
    private int parseDayNo(String target) {
        if (target == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(target);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 从行程提取第 N 天的景点/地点名，拼成补查区域（最多4个地点，作为 query 范围） */
    private String regionOfDay(TripPlanData plan, int day) {
        if (plan == null || plan.getSections() == null) return "";
        java.util.LinkedHashSet<String> places = new java.util.LinkedHashSet<>();
        for (dto.Section sec : plan.getSections()) {
            if (sec == null || sec.getItems() == null) continue;
            String title = sec.getTitle() == null ? "" : sec.getTitle();
            if (!title.contains("第" + day + "天") && !title.contains("Day" + day) && !title.contains("day" + day)) continue;
            for (dto.SectionItem it : sec.getItems()) {
                if (it == null || it.getPlace() == null || it.getPlace().isBlank()) continue;
                places.add(it.getPlace().trim());
                if (places.size() >= 4) break;
            }
            if (!places.isEmpty()) break;
        }
        return String.join(",", places);
    }

    /** 合并候选：extra 的候选按 name 去重后追加进 base（就地修改原 list） */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> mergeCandidates(String existingJson, java.util.Map<String, Object> extra) {
        java.util.Map<String, Object> base = utils.CandidateUtil.parse(existingJson);
        if (base == null) base = new java.util.LinkedHashMap<>();
        for (String d : utils.CandidateUtil.DOMAINS) {
            java.util.List<java.util.Map<String, Object>> extraItems = utils.CandidateUtil.items(extra, d);
            if (extraItems.isEmpty()) continue;
            java.util.Map<String, Object> node = utils.CandidateUtil.domain(base, d);
            if (node == null) {
                node = new java.util.LinkedHashMap<>();
                base.put(d, node);
            }
            java.util.List<Object> list;
            if (node.get("candidates") instanceof java.util.List) {
                list = (java.util.List<Object>) node.get("candidates");
            } else {
                list = new java.util.ArrayList<>();
                node.put("candidates", list);
            }
            java.util.Set<String> existing = new java.util.LinkedHashSet<>();
            for (Object o : list) {
                if (o instanceof java.util.Map) {
                    Object n = ((java.util.Map<String, Object>) o).get("name");
                    if (n != null) existing.add(n.toString());
                }
            }
            for (java.util.Map<String, Object> it : extraItems) {
                Object n = it.get("name");
                if (n != null && existing.add(n.toString())) list.add(it);
            }
        }
        return base;
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
        syncPoolSize(); // ★2026-09-11 并发对齐：多方案入口同样按闸调整并行池（3套方案并行需要更多线程）
        // ★多方案：跳过默认定稿（withPlanAgent=false），3套风格在 aggregateMulti 内并行定稿，避免白跑一次
        TravelState state = resolveAndFetch(userPrompt, null, false);
        String missingInput = missingInputs(state.getRouteResult(), ""); // 多方案仅路线前置必须齐全
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "定稿", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        EventStore.add(sessionId, "思考", "正在基于候选与衔接数据规划多套差异化方案...");
        EventStore.add(sessionId, "定稿", "正在生成 3 套差异化方案（经典主线/深度环线/轻松向）...");
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
        if (hit != null && !cacheEntryUsable(hit, req)) {
            // ★2026-09-11 缓存结构校验（实测依据）：缓存里存过"标称 3 天、实际只有 2 天区块"的旧结果。
            //   命中后审查员正确指出「每日行程区块为 2 天，与请求 3 天不符」「完全没有自驾安排」，
            //   但修复走整单重编失败 → "保留上一版" → **把错行程静默交付出去，还报完成/无降级**。
            //   所以采用前先校验结构与天数，不通过就当作未命中，直接走真实生成。
            EventStore.add(sessionId, "解析", "· 结果缓存结构校验不通过（天数/区块不符），改为重新生成 ·");
            log.warn("[cache] 命中但校验不通过（期望 {} 天，缓存文本 {} 字符），按未命中处理",
                    req.getDays(), hit.planResult == null ? 0 : hit.planResult.length());
            hit = null;
        }
        if (hit != null) {
            EventStore.add(sessionId, "解析", "· 命中结果缓存，跳过专家调用 ·");
            EventStore.add(sessionId, "审查", "结果缓存命中，正在执行校验/审查闭环（专家生成已跳过）...");
            state.setFromCache(true);
            state.setRouteResult(hit.routeResult);
            state.setPlanResult(hit.planResult);
            state.setCandidatesJson(hit.candidatesJson); // 候选随缓存复用（旧条目为空串则聚合走原文兜底）
            // ★2026-09-11 关键补充：还原"按天结构"。否则 dayPlanJson 为空 → skeletonMode=false
            //   → 审查发现问题后只能走"整单重编"（本项目判定必然失败的那条路）→ 修复失败 → 交付带缺陷结果。
            restoreDayPlanJsonFromText(state, req);
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
                    + "，候选先行：先沿途查候选定核心区域，再按核心区域规划路线...");
            // ① ★候选先行：沿途查候选（出发地→目的地，域专家 query_trip 沿途），不再依赖路线Agent先定途经城市
            String originDest = req.getOrigin().trim() + "," + req.getDestination().trim();
            fetchCandidatesPreflight(state, originDest, req);
            checkCancel();
            // ② 候选聚类定核心景点区域（沿途哪值得停由候选数据决定，不是LLM拍脑袋）
            java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());
            // ★2026-09-12 不再按"景点个数"限死上限（原写死 8 → 长线只能得 8 簇、切不出 14 天）。
            //   代表景点清单纯粹是"给路线Agent查衔接用的骨架"，天数分配由 cutIntoDays 按【游玩时长】决定，
            //   所以这里放开到候选全量（上限取候选个数，不低于 8 保证短行程行为不变）。
            int repCap = cands == null ? 8 : Math.max(8, cands.size() * 4);
            String clusterSights = cands == null ? "" : utils.CandidateUtil.clusterRepSights(cands, repCap);
            state.setSights(clusterSights);
            state.addTrace("路线", "候选核心区域: " + (clusterSights.isBlank() ? "未提取到（候选缺失/无坐标）" : clusterSights));
            // ③ 路线Agent 基于候选核心区域规划路线（途经城市由景点分布决定）
            String routeResult = await(PARALLEL_POOL.submit(() ->
                    remoteTool.callRouteMakingAgent(routeTask(req, clusterSights))), "路线Agent");
            state.setRouteResult(routeResult);
            checkCancel();
            // ★强制校验前移：往返里程÷300 > 天数 → 尝试重调缩短（最多2轮）
            //  ★效果检测：重调后里程没降（降幅<5%）说明物理上缩不动（目的地往返里程由主干路线决定，
            //   砍停靠点省不了），立即停止——避免"越缩越多"（实测路线Agent被逼会排更多途经点，里程反升）。
            int routeKm = extractTotalDistance(routeResult);
            String bestResult = routeResult;
            int bestKm = routeKm;
            for (int shrink = 0; shrink < 2 && routeKm > 0 && (int) Math.ceil(routeKm / 300.0) > req.getDays(); shrink++) {
                final int needDays = (int) Math.ceil(routeKm / 300.0);
                final int curKm = routeKm;
                final int userDays = req.getDays();
                EventStore.add(sessionId, "路线", "往返里程 " + curKm + "km 需约 " + needDays
                        + " 天 > 用户 " + userDays + " 天，尝试缩短路线（第 " + (shrink + 1) + " 次）...");
                String shorter = await(PARALLEL_POOL.submit(() ->
                        remoteTool.callRouteMakingAgent(routeTask(req, clusterSights,
                                "往返里程 " + curKm + "km 需 " + needDays + " 天，用户只要 " + userDays
                                        + " 天。①若物理上可行，砍非核心中转停靠点/改走更紧的小环线使往返里程下降；"
                                        + "②若这已是最近路线（目的地往返里程由主干路线决定、缩不动），"
                                        + "保持路线不变、绝对不要增加途经点，并如实说明缩不动的原因。"
                                        + "用户明确要去的景点（如稻城亚丁）必须保留"))), "路线Agent·缩短");
                if (shorter == null || shorter.isBlank()) break;
                int newKm = extractTotalDistance(shorter);
                // 效果检测：里程没降（降幅 < 5%）→ 判定物理上缩不动，停止重调（不再浪费下一轮）
                if (newKm <= 0 || newKm >= (int) (curKm * 0.95)) {
                    EventStore.add(sessionId, "路线", "重调后里程 " + (newKm <= 0 ? "未知" : newKm + "km")
                            + " 未下降（降幅 <5%）——判定缩不动，停止重调");
                    break;
                }
                if (newKm < bestKm) { bestKm = newKm; bestResult = shorter; }
                routeResult = shorter;
                state.setRouteResult(routeResult);
                routeKm = newKm;
            }
            // 保留里程最小的一版（防止最后一版反而更大）
            if (bestKm > 0 && bestResult != routeResult && bestKm < extractTotalDistance(routeResult)) {
                routeResult = bestResult;
                state.setRouteResult(routeResult);
                routeKm = bestKm;
                EventStore.add(sessionId, "路线", "保留里程最小版本 " + bestKm + "km（放弃更大的重调版）");
            }
            checkCancel();
            String waypoints = extractWaypoints(routeResult);
            state.setWaypoints(waypoints);
            suggestDaysByDistance(routeResult, req);
            // ★可行性预检（不中止）：往返里程÷日均 > 用户天数 → 标记 infeasibleDays，供兜底/替代路线用
            int feasKm = extractTotalDistance(routeResult);
            if (feasKm > 0) {
                String ftype = req.getTravelType() == null ? "" : req.getTravelType().trim().toUpperCase();
                int fDaily = "CROSS_CITY".equals(ftype) ? 500 : 300;
                int needDays = (int) Math.ceil(feasKm / (double) fDaily);
                if (needDays > req.getDays()) state.setInfeasibleDays(needDays);
            }
            state.addTrace("路线", "途经城市: " + (waypoints.isBlank() ? "未提取到" : waypoints));
            String routeCandidates = extractRouteCandidates(routeResult); // 多条候选路线概要（供行程Agent择优）
            if (withPlanAgent) {
                // ③ 行程Agent 一次定稿：输入=候选+簇+候选路线表 → 输出结构化终版【行程JSON】块
                EventStore.add(sessionId, "行程", "跨城行程Agent 基于候选路线与候选定稿...");
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
                : utils.CandidateUtil.clusterRepSights(cands, Math.max(8, cands.size() * 4));
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
            // ③ ★天骨架并行定稿（2026-09-06）：候选簇→地理链→按天切段→逐日短任务并行→拼装整单；
            //    失败（骨架不可用/某天失败）自动回退原整单 finalPlanTask
            String planText = planByDaySkeleton(state, req);
            if (planText == null || planText.isBlank()) {
                EventStore.add(sessionId, "定稿", "整单生成（天骨架不可用或回退，行程Agent一次定稿）...");
                planText = await(PARALLEL_POOL.submit(() ->
                        remoteTool.callTripPlannerAgent(finalPlanTask(state, null, null, null))), "行程Agent");
            }
            state.setPlanResult(planText);
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
            java.net.URL u = new java.net.URL(utils.AgentEndpoints.tripAgent() + "/candidate-block?" + qs);
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

    /**
     * ★2026-09-11 地名歧义防护：给裸地名加**城市限定前缀**（"环球中心" → "成都·环球中心"）。
     *
     * <p>实测依据（探针逐阶段计时）：成都有地标"环球中心"，但高德把**裸名**"环球中心"解析到了
     * **新疆阿克苏**，路线Agent 那一轮查询白跑、触发"独立重试铁律"重查一次，
     * 单次多花 **48.6s**（占该次整单 258s 的 19%）。加城市前缀后歧义不存在，无需重查。
     */
    static String qualifySights(String city, String sightList) {
        if (sightList == null || sightList.isBlank()) return sightList == null ? "" : sightList;
        if (city == null || city.isBlank()) return sightList;
        String c = city.trim();
        StringBuilder sb = new StringBuilder();
        for (String raw : sightList.split("[、,，;；/]")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("、");
            // 已含城市限定 → 原样保留，避免出现"成都·成都·环球中心"
            if (s.startsWith(c) || s.contains(c + "·") || s.contains(c + "-") || s.contains(c + "市")) {
                sb.append(s);
            } else {
                sb.append(c).append("·").append(s);
            }
        }
        return sb.toString();
    }

    private String routeTask(TripRequest req, String sightList) {
        return routeTask(req, sightList, "");
    }

    private String routeTask(TripRequest req, String sightList, String shrinkFeedback) {
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
                base += "需查询衔接的景点清单（已由代码按候选成簇/终版行程确定）：" + qualifySights(dest, sightList) + "。\n"
                        + "★地名歧义防护：清单里每个地名都已带城市限定（如「成都·环球中心」），调用工具时必须**连同城市一起传**，"
                        + "不得只传裸名——实测裸名「环球中心」被高德解析到新疆阿克苏，会让整轮查询白跑并触发重查（多花 40s+）。\n"
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
                + (sightList != null && !sightList.isBlank()
                    ? "★候选核心景点区域（已由候选聚类确定，途经城市/停靠点应优先覆盖这些区域）：" + qualifySights(dest, sightList) + "\\n"
                      + "★地名歧义防护：以上地名均已带城市限定（如「成都·环球中心」），调用 query_trip/query_route_unified 时必须**连同城市一起传**，"
                      + "不得只传裸名——实测裸名「环球中心」被高德解析到新疆阿克苏，导致整轮查询白跑并触发重查（多花 40s+）。\\n"
                    : "")
                + "★请给出【2~3 条候选路线方案】（单条路线可能错过好景点，须供行程Agent择优选择）：\n"
                + "每条方案用以下固定格式分条标注：\n"
                + "★候选路线一：途经城市：城市A,城市B,城市C\n"
                + "每日概要：第1天…；第2天…（逐日简述行车段与停留点）\n"
                + "★去程里程：约 NNNkm；★往返总里程：约 NNNkm（自驾/环线必须按【闭环】规划——出发地→各途经→目的地→返回出发地；"
                + "往返总里程 = 含回程的全程真实里程，用 query_route_unified 各段累加）。★这条「往返总里程」标注行是里程数据的唯一权威来源（代码只读这一行），禁止省略或改用表格替代。\n"
                + "（候选路线二/三同格式；各方案走不同途经城市组合与节奏，不要雷同）\n"
                + "★先调用 query_trip 查询出发地→目的地【沿途】的景点/观景点分布，据此确定途经城市与停靠点（以沿途真实景点为准，不要凭记忆拍脑袋定途经城市）；"
                + "再调用 query_route_unified，mode 参数driving" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                + "（自驾路线与距离），查询每条候选方案各段自驾线路的真实里程/时长。\n"
                + "★严格限制：每条候选路线的总天数必须正好 " + req.getDays() + " 天，不得增加；"
                + "★里程天数门禁：往返总里程 ÷ 日均 300km（高原/山路）若超过 " + req.getDays()
                + " 天，该方案必须【主动缩短路线】——砍掉最远节点/改走小环线/减少停靠点，保证单日车程不超 6 小时，严禁硬排超长赶路日。\n"
                + (shrinkFeedback == null || shrinkFeedback.isBlank()
                    ? "" : "★强制缩短（上一版往返里程超载，本次必须执行）：" + shrinkFeedback
                        + "。★缩短的是【路线长度】（砍非核心中转停靠点/改走更紧的小环线），"
                        + "绝不可砍掉返程——路线必须保持闭环（出发地→…→目的地→返回出发地），"
                        + "totalKm 仍是含返程的往返闭环总里程。\n")
                + "最终回复：完整列出各候选路线方案（含途经城市、逐日概要、总里程），不要只给单一定稿路线。\n"
                + "★回复末尾必须附加一个【路线JSON】块（代码据此读往返里程/途经城市，字段齐全、禁止省略）：\n"
                + "【路线JSON】\n"
                + "{\"totalKm\":往返闭环总里程整数(含返程,出发地→…→目的地→返回出发地全程),\"oneWayKm\":单程去程里程整数,\"waypoints\":\"途经城市逗号分隔\",\"routeCount\":候选路线条数}\n"
                + "【路线JSON结束】\n"
                + "★口径铁律：totalKm 必须是【往返闭环】总里程（=去程+返程，绝非单程）；即使只规划了单程，totalKm 也必须按往返口径给（单程×2）。";
    }

    /** ★删聚合·行程Agent定稿任务（原 planTask 职责并入）：候选已由主管前置查询并注入（【候选预置】标记），
     *  行程Agent 基于 候选+簇分组+簇间衔接(市内)/候选路线表(跨城) 一次输出结构化终版【行程JSON】块。
     *  @param style 多方案风格（PLAN_STYLES[i][2]，null=默认）；@param feedback 审查反馈（REVISE重编时，null=首次）；
     *  @param prevPlanJson 上版行程JSON（审查重编的局部修改基准，null=无）。 */
    private String finalPlanTask(TravelState state, String style, String feedback, String prevPlanJson) {
        return finalPlanTask(state, style, feedback, prevPlanJson, "");
    }

    private String finalPlanTask(TravelState state, String style, String feedback, String prevPlanJson, String routeLine) {
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
                    .append(req.getOrigin()).append(" 内部的景点（起点只是出发地）");
            String prefCross = req.getTripPref();
            if (prefCross != null && !prefCross.isBlank()) {
                sb.append("，偏好：").append(prefCross);
            }
            sb.append("。\n");
            if (!routeCandidates.isBlank()) {
                sb.append("路线Agent给出多条候选路线方案，择一作为主线：\n").append(routeCandidates).append("\n");
            }
            sb.append("【你选定主线的途经城市】").append(waypoints)
                    .append("。每日安排与所选路线的途经城市/行车段一致，跨城衔接以路线Agent返回为准，禁止编造跨城里程。\n");
            if (routeLine != null && !routeLine.isBlank()) {
                sb.append("★本方案指定骨架：").append(routeLine)
                        .append("——请【严格以这条候选路线为主线】安排每日行程（这是与其它方案区分的关键，不要自行改走别的路线）。\n");
            }
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
            if (req != null && req.getDays() > 0) {
                sb.append("★总天数铁律：行程总天数必须保持用户要求的 ")
                        .append(req.getDays())
                        .append(" 天（timeline 区块数 = ").append(req.getDays())
                        .append("），禁止增减天数——返程/晚间/收尾一律写进最后一天内部，不得为此新开一天。\n");
            }
        }
        // ★交通工具铁律：自驾/环线全程必须自驾（含返程），禁止擅自改成飞机/高铁规避超长返程
        if ("SELF_DRIVE".equals(req.getTravelType() == null ? "" : req.getTravelType())
                || "LOOP".equals(req.getTravelType() == null ? "" : req.getTravelType())) {
            sb.append("★交通工具铁律：本行程为【自驾/环线】，去程与返程全程必须自驾，"
                    + "禁止擅自把返程改成飞机/高铁/火车来规避超长返程——即使返程里程长、超出单日车程上限，"
                    + "也如实标注该日车程并给出拆分建议（如中途住宿），不得换交通工具。\n");
        }
        // ★闭环铁律（仅需返程时）：行程是一个圈——最后一天必须回到出发地；返程按正常行程安排
        if (needsReturnLoop(req)) {
            sb.append("★闭环铁律：本行程是一个【闭环】——从 " + req.getOrigin() + " 出发，最后一天【必须回到 ")
                    .append(req.getOrigin())
                    .append("】。返程是行程的一部分（不是可省略项），必须按正常行程安排（在途≤6h、在途>4h有景点停靠、游玩4~8h）；")
                    .append("若一天排不完返程，就【分多天边走边玩返回】，严禁终止于中途城镇或省略返程；")
                    .append("返程若确实超时，如实标注车程即可，但不得因此不排返程。\n");
        }
        if (prevPlanJson != null && !prevPlanJson.isBlank()) {
            sb.append("上一版行程JSON（修改基准，仅修订反馈涉及部分）：\n")
                    .append("【行程JSON】\n").append(prevPlanJson).append("\n【行程JSON结束】\n");
        }
        sb.append("\n最终回复：简短概要（每天一句话，总长不超过300字——完整可读文本由主管从你的JSON生成，概要只作兜底），末尾【必须】原样附完整【行程JSON】结构化块（格式见系统规则）。"
                + "输出预算优先保证 JSON 块完整，宁可概要更短也不可截断 JSON。"
                + "JSON 的区块必须齐全：每天 timeline、stay、transport、tips 等区块一个都不能少——内容精简时压缩区块内每条 note/描述文字（宁短勿长），禁止整块删除或留空区块；tips（实用贴士）必须保留 5-8 条真实可用的提示。");
        return sb.toString().trim();
    }

    /** ★候选前置查询（删聚合配套）：主管直连 8085 域专家并行查三域候选（域间3线程），
     *  结果写入 state.candidatesJson（结构化，含证据/置信度），供路线按簇查衔接与行程Agent定稿共用。
     *  ★2026-09-06 分域补查：全域主查后检测缺失域（域专家偶发中断/降级致 candidates 空），只对缺失域
     *  （domains 参数）补查一次并合并——比整包重查省时（补查只跑 1 域 ≈20s vs 整包 70s）且再失败概率低。
     *  失败不阻断：state.candidatesJson 保持空，路线按无清单自拟、行程Agent 自行补查兜底。 */
    private void fetchCandidatesPreflight(TravelState state, String waypoints, TripRequest req) {
        String pref = req.getTripPref() == null ? "" : req.getTripPref();
        String n = String.valueOf(utils.CandidateUtil.candidateCountPerDomain(String.valueOf(req.getDays())));
        EventStore.add(sessionId, "候选", "候选前置查询(域专家三线程并行，每域" + n + "个)"
                + (waypoints == null || waypoints.isBlank() ? "" : " · 途经:" + waypoints) + " ...");
        try {
            // ① 全域主查
            java.util.Map<String, Object> cands = queryCandidatesOnce(req, waypoints, pref, null);
            if (cands == null || cands.isEmpty()) {
                // 整包失败：等 3s 后整包再查一次（极端情况，如 8085 瞬时不可用）
                EventStore.add(sessionId, "候选", "候选前置查询失败/无结构化结果，3 秒后整包重查一次...");
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                cands = queryCandidatesOnce(req, waypoints, pref, null);
            }
            if (cands != null && !cands.isEmpty()) {
                // ② 分域补查：缺失域只补缺失（domains=xxx）
                java.util.List<String> missing = missingDomains(cands);
                if (!missing.isEmpty()) {
                    EventStore.add(sessionId, "候选", "候选域缺失（" + String.join("、", missing)
                            + "），3 秒后分域补查一次...");
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    java.util.Map<String, Object> cands2 = queryCandidatesOnce(req, waypoints, pref,
                            String.join(",", missing));
                    if (cands2 != null) {
                        for (String d : missing) {
                            if (!candDomainList(cands2, d).isEmpty()) {
                                cands.put(d, cands2.get(d)); // 只合并补到的域
                            }
                        }
                    }
                }
                // ③ 坐标补齐（域专家 location 覆盖率不稳 → 高德按名补查，保证骨架成簇可用）
                int fixed = enrichSightLocations(cands, req.getDestination());
                if (fixed > 0) {
                    EventStore.add(sessionId, "候选", "景点坐标补齐：" + fixed + " 个（高德按名补查）");
                }
                boolean hasAttr = !candDomainList(cands, "attraction").isEmpty();
                if (hasAttr) {
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
            }
            EventStore.add(sessionId, "候选", "候选前置查询重查后仍失败/缺景点域，行程Agent将自行补查兜底");
        } catch (Exception e) {
            log.warn("候选前置查询异常(行程Agent自行补查兜底): {}", e.getMessage());
            EventStore.add(sessionId, "候选", "候选前置查询异常(" + e.getMessage() + ")，行程Agent将自行补查兜底");
        }
    }

    /** 单次候选查询（domains 为 null=全域；非空=只查指定域，逗号分隔）。异常/无块返回 null。 */
    private java.util.Map<String, Object> queryCandidatesOnce(TripRequest req, String waypoints,
                                                              String pref, String domains) {
        try {
            StringBuilder qs = new StringBuilder("destination=")
                    .append(java.net.URLEncoder.encode(req.getDestination() == null ? "" : req.getDestination(), "UTF-8"))
                    .append("&days=").append(req.getDays())
                    .append("&preference=").append(java.net.URLEncoder.encode(pref, "UTF-8"))
                    .append("&waypoints=").append(java.net.URLEncoder.encode(waypoints == null ? "" : waypoints, "UTF-8"))
                    .append("&brief=").append(java.net.URLEncoder.encode(req.toStateBrief(), "UTF-8"));
            if (domains != null && !domains.isBlank()) {
                qs.append("&domains=").append(java.net.URLEncoder.encode(domains, "UTF-8"));
            }
            qs.append("&sessionId=").append(java.net.URLEncoder.encode(sessionId, "UTF-8"));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    utils.AgentEndpoints.tripAgent() + "/trip/candidates?" + qs).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(300000); // 域专家并行最长120s + 置信度重试
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");
            java.util.Map<String, Object> root = utils.JsonUtils.parse(body, java.util.Map.class);
            String reply = root == null ? null : str(root.get("reply"));
            String block = reply == null ? "" : utils.CandidateUtil.extractBlock(reply);
            return block.isBlank() ? null : utils.CandidateUtil.parse(block);
        } catch (Exception e) {
            log.warn("候选查询请求异常: {}", e.getMessage());
            return null;
        }
    }

    /** 缺失域列表：attraction/hotel/dining 中无有效 candidates 的域（degraded 节点也算缺失）。 */
    private static java.util.List<String> missingDomains(java.util.Map<String, Object> cands) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (cands == null) {
            missing.add("attraction");
            missing.add("hotel");
            missing.add("dining");
            return missing;
        }
        for (String d : utils.CandidateUtil.DOMAINS) {
            if (candDomainList(cands, d).isEmpty()) missing.add(d);
        }
        return missing;
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
            EventStore.add(sessionId, "定稿", "行程JSON缺关键内容(" + gap + ")，降级为原文兜底");
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
                EventStore.add(sessionId, "定稿", "天气直连高德预报：" + direct);
            }
        }
        if (weatherRaw != null && !weatherRaw.isBlank()) {
            plan.setWeather(weatherRaw); // 覆盖行程Agent值（已清洗）
            EventStore.add(sessionId, "定稿", "天气透传：" + weatherRaw);
        } else if (plan.getWeather() != null && !plan.getWeather().isBlank()) {
            plan.setWeather(cleanWeather(plan.getWeather()));
        }
    }

    /** ★REVISE 天级判定：天骨架模式（state.dayPlanJson 完整）且反馈只指向唯一一天时返回该天号（1-based），
     *  否则返回 0（走整单重编）。反馈可能含 [dayN]/第N天/多条天号（整体问题）。 */
    private int skeletonDayNo(TravelState state, String feedback) {
        java.util.List<String> dj = state.getDayPlanJson();
        if (dj == null || dj.isEmpty() || feedback == null) return 0;
        TripRequest req = state.getRequest();
        int days = req == null ? 0 : req.getDays();
        if (days < 1 || dj.size() != days) return 0; // 非骨架模式/段数不完整
        java.util.Set<Integer> hit = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*天|day\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(feedback);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            if (g == null) continue;
            try {
                int n = Integer.parseInt(g);
                if (n >= 1 && n <= days) hit.add(n);
            } catch (NumberFormatException ignored) { }
        }
        return hit.size() == 1 ? hit.iterator().next() : 0;
    }

    /**
     * ★2026-09-11 整单重编失败时的兜底：取反馈里提到的**所有**天号（skeletonDayNo 的复数版）。
     *
     * <p>背景（实测证据）：审查意见跨天时走「整单重编」，任务文本 16k 字符是全场最重的，
     * 必然超过 A2A 那道 40s 的流切断阈值 → 重发也失败 → 已发现的缺陷一个都修不上、直接交付。
     * 而天骨架那种 1.4k 字符的短任务实测 3/3 全部成功。所以整单失败后，退化为按天逐个修，
     * 至少把"只涉及某一天"的意见（如"第1天动线严重折返"）真正落地。
     */
    private java.util.Set<Integer> skeletonDayNos(TravelState state, String feedback) {
        java.util.Set<Integer> hit = new java.util.TreeSet<>();
        java.util.List<String> dj = state == null ? null : state.getDayPlanJson();
        if (dj == null || dj.isEmpty() || feedback == null) return hit;
        TripRequest req = state.getRequest();
        int days = req == null ? 0 : req.getDays();
        if (days < 1 || dj.size() != days) return hit; // 非骨架模式/段数不完整
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*天|day\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(feedback);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            if (g == null) continue;
            try {
                int n = Integer.parseInt(g);
                if (n >= 1 && n <= days) hit.add(n);
            } catch (NumberFormatException ignored) { }
        }
        return hit;
    }

    /**
     * ★2026-09-11 从"整单行程文本"还原**按天结构**（`state.dayPlanJson`）。
     *
     * <p>为什么需要（实测）：缓存命中时只还原了文本 `planResult`，`dayPlanJson` 为空 →
     * 于是 `skeletonMode=false` → 审查发现问题后**只能走"整单重编"**，而整单重编是本项目
     * 判定为"必然失败"的那条路（任务 13k 字符）→ 修复失败 → 保留上一版 → 交付带缺陷结果。
     *
     * <p>做法：解析文本里的【行程JSON】块 → 按 section.title 里的"第N天"分组 → 每天存一份 JSON，
     * 与天骨架成功后的存储结构一致（`addDayPlanJson(天号, json)`）。这样缓存路径也能"按天修复"。
     */
    private void restoreDayPlanJsonFromText(TravelState state, TripRequest req) {
        try {
            if (state == null || req == null) return;
            int days = req.getDays();
            if (days <= 0) return;
            String text = state.getPlanResult();
            if (text == null || text.isBlank()) return;
            String block = utils.CandidateUtil.extractPlanBlock(text);
            if (block == null || block.isBlank()) return;
            dto.TripPlanData whole = utils.JsonUtils.extractAndParse(block, dto.TripPlanData.class);
            if (whole == null || whole.getSections() == null || whole.getSections().isEmpty()) return;

            java.util.Map<Integer, java.util.List<dto.Section>> byDay = new java.util.TreeMap<>();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*天");
            for (dto.Section s : whole.getSections()) {
                String t = s.getTitle() == null ? "" : s.getTitle();
                java.util.regex.Matcher m = p.matcher(t);
                if (!m.find()) continue;
                try {
                    int d = Integer.parseInt(m.group(1));
                    if (d >= 1 && d <= days) byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(s);
                } catch (NumberFormatException ignored) { }
            }
            if (byDay.isEmpty()) return; // 拆分不出天 → 保持原样（不要造出半个结构）

            java.util.List<String> dj = state.getDayPlanJson();
            if (dj == null) return;
            dj.clear();
            for (int d = 1; d <= days; d++) {
                java.util.List<dto.Section> ss = byDay.get(d);
                if (ss == null || ss.isEmpty()) {
                    state.addDayPlanJson(d, ""); // 缺天留空位（与天骨架部分成功的行为一致）
                    continue;
                }
                dto.TripPlanData one = new dto.TripPlanData();
                one.setTitle(whole.getTitle());
                one.setDestination(whole.getDestination());
                one.setDays(whole.getDays());
                one.setDate(whole.getDate());
                one.setSections(ss);
                state.addDayPlanJson(d, utils.JsonUtils.toJson(one));
            }
            EventStore.add(sessionId, "解析", "· 缓存结果已还原按天结构（"
                    + byDay.size() + "/" + days + " 天），审查修复可按天进行 ·");
            log.info("[cache] 已从缓存文本还原按天结构: {}/{} 天", byDay.size(), days);
        } catch (Throwable t) {
            // 还原失败不致命：最多退回原来的整单修复路径
            log.warn("[cache] 还原按天结构失败（忽略，退回整单路径）: {}", t.toString());
        }
    }

    /**
     * ★2026-09-12 区块规整化：去重 + 按天排序。
     *
     * <p>实测依据（`trip_plans` id=76「川西14日游」）：整单路径一次产出 **23 个区块**，其中
     * 3 个「住宿与餐饮」**逐字完全相同**、`stay` 有 **7 种标题写法**（住宿 / 住宿与餐饮 / 今日住宿 /
     * 第4天住宿 / 第10天 住宿（朴头镇）/ 今晚住宿（漳扎镇）/ 第6天住宿与餐饮），且**住宿区块排在行程之前**。
     *
     * <p>规则：①`type+title+items` 完全相同的只保留一个；②按时出现的天号升序排列，
     * 同一天内 timeline 在前、住宿等附属区块在后；③识别不出天号的区块统一放到最后（不丢）。
     * 只在真的发生去重/重排时报事件，正常结果不产生噪声。
     */
    private void normalizePlanSections(dto.TripPlanData plan, String sessionId) {
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) return;
        java.util.List<dto.Section> src = new java.util.ArrayList<>(plan.getSections());

        // ① 去重（保序）
        java.util.LinkedHashMap<String, dto.Section> uniq = new java.util.LinkedHashMap<>();
        for (dto.Section s : src) {
            if (s == null) continue;
            String key = str(s.getType()) + "|" + str(s.getTitle()) + "|"
                    + utils.JsonUtils.toJson(s.getItems());
            uniq.putIfAbsent(key, s);
        }

        // ② 分组：能识别天号的按天号，其余入 rest
        java.util.TreeMap<Integer, java.util.List<dto.Section>> byDay = new java.util.TreeMap<>();
        java.util.List<dto.Section> rest = new java.util.ArrayList<>();
        for (dto.Section s : uniq.values()) {
            Integer d = dayNoInText(s.getTitle());
            if (d == null) {
                // 区块标题没写天号时，退回看它的 items 文本（住宿条目常带"第N天"）
                StringBuilder sb = new StringBuilder();
                if (s.getItems() != null) {
                    for (dto.SectionItem it : s.getItems()) {
                        if (it != null) sb.append(str(it.getName())).append(' ').append(str(it.getActivity())).append(' ');
                    }
                }
                d = dayNoInText(sb.toString());
            }
            if (d != null && d > 0) byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(s);
            else rest.add(s);
        }

        java.util.List<dto.Section> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.List<dto.Section>> e : byDay.entrySet()) {
            e.getValue().sort(java.util.Comparator.comparingInt(this::sectionRank));
            out.addAll(e.getValue());
        }
        out.addAll(rest); // 识别不出天号的原样保留到最后，绝不丢弃

        if (out.size() != src.size()) {
            plan.setSections(out);
            EventStore.add(sessionId, "定稿", "区块规整化：去掉 " + (src.size() - out.size())
                    + " 个重复区块（" + src.size() + " → " + out.size() + "）");
        } else {
            plan.setSections(out);
        }
    }

    /** 区块排序权重：行程(时间表)在前，住宿餐饮类其次，其余最后 */
    private int sectionRank(dto.Section s) {
        String t = str(s.getType()).toLowerCase();
        if ("timeline".equals(t)) return 0;
        if ("stay".equals(t)) return 1;
        return 2;
    }

    /** 从任意文本里提取"第N天"的 N（取首个匹配）；无则返回 null */
    private Integer dayNoInText(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*天").matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /**
     * ★2026-09-11 缓存可用性校验：**天数必须与请求一致**，文本不能空，且天数编号要覆盖 1..N、不超界。
     *
     * <p>为什么必须校验（实测）：缓存里存过"标称 3 天、实际只有 2 天区块、且全程地铁打车"的旧结果。
     * 命中后审查员**正确指出**了「每日行程区块为 2 天，与请求 3 天不符」「完全没有自驾安排」，
     * 但修复动作走整单重编失败 → "保留上一版" → **把错误行程静默交付，还报完成/无降级**。
     * 宁可重新生成（多花 3~4 分钟），也不能交付一份天数都不对的行程。
     */
    private static boolean cacheEntryUsable(utils.cache.PlanCache.Entry hit, TripRequest req) {
        if (hit == null || hit.planResult == null || hit.planResult.isBlank()) return false;
        int want = req == null ? 0 : req.getDays();
        return planStructOk(hit.planResult, want);
    }

    /**
     * ★2026-09-11 行程文本的**结构校验**：天数编号必须恰好覆盖 1..wantDays。缓存与"保留上一版"共用。
     *
     * @param wantDays <=0 表示请求未指定天数 → 无从校验，放行
     */
    private static boolean planStructOk(String planText, int wantDays) {
        if (planText == null || planText.isBlank()) return false;
        if (wantDays <= 0) return true;
        java.util.TreeSet<Integer> got = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*天")
                .matcher(planText);
        while (m.find()) {
            try {
                got.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) { }
        }
        if (got.isEmpty()) return false;                      // 连一天都识别不出 → 不可用
        // 要求覆盖 1..wantDays 且不超出（超天数也是历史踩过的坑：3 日游被扩成 5 天）
        return got.first() == 1 && got.last() == wantDays && got.size() >= wantDays;
    }

    /**
     * ★2026-09-11 判断审查意见是否为「全局性要求」——即必须贯彻到**每一天**的意见。
     *
     * <p>实测问题（浏览器端验证发现）：用户要求"自驾游"，审查意见也写了"把全部衔接改写为自驾口径"，
     * 但因为意见正文里只提到了某一天（提取出 days={1}），于是只修了第 1 天 —— **第 2 天整段仍是"打车"**，
     * 用户的出行方式要求没被贯彻。交通方式/住宿一致性这类要求在语义上天然是全局的，
     * 只按"正文提到哪天"来决定修哪天，会漏掉未提及的天。
     */
    private static boolean isGlobalFeedback(String feedback) {
        if (feedback == null || feedback.isBlank()) return false;
        String[] globalKeys = {
                "自驾", "交通方式", "公共交通", "包车", "租车",   // 出行方式：天然覆盖全程
                "全程", "每一天", "每天", "所有天", "各天", "全天数", // 显式全量表述
                "住宿区域", "统一", "衔接一致"                      // 跨天一致性要求
        };
        for (String k : globalKeys) {
            if (feedback.contains(k)) return true;
        }
        return false;
    }

    /**
     * ★2026-09-11 抽出的**只读纯函数**：构造"只修第 dayNo 天"的任务文本。
     * 只读取 state（dayPlanJson 的该天、routeResult），不改任何状态 —— 因此可以被多天**并行**调用，
     * 这是把"天级重编"从串行 282s 压到并行 ~80s 的前提。
     */
    private String buildDayRepairTask(TravelState state, int dayNo, String feedback) {
        TripRequest req = state.getRequest();
        java.util.List<String> dj = state.getDayPlanJson();
        String oldJson = (dj != null && dayNo >= 1 && dayNo <= dj.size()) ? dj.get(dayNo - 1) : "";
        StringBuilder sb = new StringBuilder();
        sb.append("你是行程定稿Agent。上一版第 ").append(dayNo).append(" 天行程经独立审查发现问题，本次【只修正第 ")
                .append(dayNo).append(" 天】（共 ").append(req == null ? "" : req.getDays()).append(" 天），禁止动其它天。\n\n");
        sb.append("【审查反馈】").append(feedback).append("\n\n");
        sb.append("【上一版第").append(dayNo).append("天JSON（修改基准：只修订反馈指出的问题，未涉及的安排保持原样，")
                .append("禁止无中生有全盘重排）】\n【行程JSON】\n").append(oldJson).append("\n【行程JSON结束】\n");
        // ★路线衔接数据（交通类 REVISE 补查后更新；整段注入不按天匹配——路线数据格式不稳定，模型自行找相关行）
        String route = state.getRouteResult();
        if (route != null && !route.isBlank()) {
            sb.append("\n【真实路线衔接数据（主管已查/补查，供参考移动方式/车程/途经；只采用与当天相关的行，")
                    .append("其余天数据忽略）】\n").append(clip(route, 3000)).append("\n");
        }
        sb.append("\n【跨天连续铁律】今天的出发地与住宿地以上一版为准（上版开头的出发地与末尾住宿 = 真实链条）：\n")
                .append("① 不得把今天出发点改到上版之外的城镇（防\"昨天在城北、今天起点在城南\"）；\n")
                .append("② 住宿地尽量保持上版不变；确因反馈必须更换住宿区域时，只能换到当天景点所在城镇内，")
                .append("并在当天末尾注明\"次日将从 <新住宿地> 出发\"；\n")
                .append("③ 长途移动（车程超 3 小时）必须在时间线中写明出发时间与车程，不得与景点时间矛盾。\n");
        sb.append("\n输出要求：按系统规则输出【行程JSON】块，sections 只含第 " + dayNo
                + " 天的 timeline 与 stay 区块（title 用「第" + dayNo + "天」），禁止输出其它天；"
                + "当天所有条目 place 文本不得重复，cat=景点的条目不超过5个，概要不超过60字，JSON 完整优先、宁短勿长。");
        return sb.toString().trim();
    }

    /** 校验"天级重编"的回复是否越权输出了其它天的 timeline */
    private boolean dayReplyHasOtherDay(dto.TripPlanData dp, int dayNo) {
        if (dp == null || dp.getSections() == null) return false;
        for (dto.Section s : dp.getSections()) {
            String type = s.getType() == null ? "" : s.getType();
            String title = s.getTitle() == null ? "" : s.getTitle();
            if ("timeline".equals(type) && !title.contains("第" + dayNo + "天")) return true;
        }
        return false;
    }

    /**
     * ★2026-09-11 并行版多天修复：把 N 天的"天级重编"同时发出，最后统一合并。
     *
     * <p>实测依据：原来"提交→等待→改 state→重拼"逐天串行，3 天串成 282s（占整单耗时 59%）。
     * 而每次修复只依赖"该天旧 JSON + routeResult"（都被 {@link #buildDayRepairTask} 只读使用），
     * 天与天之间没有数据依赖 → 可以并行。合并阶段才写 state，避免并发改写。
     *
     * <p>下游安全：真正的并发上限由 8085 的 Semaphore(TRIP_AGENT_SLOTS) 把关，并行提交不会压垮子Agent。
     *
     * @return 合并后的最新整单（无任何一天成功则返回 null，调用方保留上一版）
     */
    private dto.TripPlanData repairDaysInParallel(TravelState state, String style,
                                                  java.util.Set<Integer> days, String feedback) {
        java.util.List<String> dj = state.getDayPlanJson();
        TripRequest req = state.getRequest();
        if (dj == null || days == null || days.isEmpty()) return null;
        final java.util.Map<Integer, String> newJsons = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        EventStore.add(sessionId, "审查", "并行修复第 "
                + days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"))
                + " 天（同时发起，缩短总耗时）...");
        for (int d : days) {
            final int dd = d;
            futures.add(PARALLEL_POOL.submit(() -> {
                try {
                    // ★不再嵌套 submit：本任务已在线程池里，再向内层提交会在池满时自锁
                    //（外层占线程 + 内层等线程）。直接调用即可。
                    String reply = remoteTool.callTripPlannerAgent(buildDayRepairTask(state, dd, feedback));
                    dto.TripPlanData dp = parseDayReply(reply, dd, "天级重编");
                    if (dp == null) return;
                    if (dayReplyHasOtherDay(dp, dd)) {
                        EventStore.add(sessionId, "审查", "第" + dd + "天重编越权输出其它天，丢弃该天结果");
                        return;
                    }
                    newJsons.put(dd, utils.JsonUtils.toJson(dp));
                } catch (CancelledException ce) {
                    throw ce; // 用户中断要向上传播
                } catch (Throwable t) {
                    log.warn("第{}天并行修复异常: {}", dd, t.toString());
                }
            }));
        }
        // 统一等待（全部完成或各自 240s 预算耗尽）
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get(300, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 单天失败不影响其它天；越权/异常已在任务内记录
            }
        }
        if (newJsons.isEmpty()) return null;
        // 合并（串行写 state，避免并发改写）
        for (java.util.Map.Entry<Integer, String> e : newJsons.entrySet()) {
            if (e.getKey() >= 1 && e.getKey() <= dj.size()) dj.set(e.getKey() - 1, e.getValue());
        }
        String text = assembleDayPlanText(state, req);
        if (text == null || text.isBlank()) return null;
        state.setPlanResult(text);
        dto.TripPlanData whole = utils.JsonUtils.extractAndParse(
                utils.CandidateUtil.extractPlanBlock(text), dto.TripPlanData.class);
        EventStore.add(sessionId, "审查", "并行修复完成（成功 "
                + newJsons.size() + "/" + days.size() + " 天），整单已重拼");
        return whole;
    }

    /** ★REVISE 天级重编：只重编第 dayNo 天（上版该天 JSON 为基准+审查反馈，短输出 240s 预算必按时返回）。
     *  成功后替换 state.dayPlanJson 对应段并重拼整单、回填 planResult；失败返回 null（调用方保留上一版）。 */
    private dto.TripPlanData repairDayByAgent(TravelState state, String style, String feedback, int dayNo) {
        java.util.List<String> dj = state.getDayPlanJson();
        if (dj == null || dayNo < 1 || dayNo > dj.size()) return null;
        TripRequest req = state.getRequest();
        EventStore.add(sessionId, "审查", "第" + dayNo + "天按天重编（单天短任务，防整单长输出截断）...");
        String reply = waitBudget(PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(buildDayRepairTask(state, dayNo, feedback))),
                "行程Agent·第" + dayNo + "天(天级重编)", 240);
        dto.TripPlanData dp = parseDayReply(reply, dayNo, "天级重编");
        if (dp == null) return null;
        // 校验该天只含第 dayNo 天 timeline（防越权）
        if (dayReplyHasOtherDay(dp, dayNo)) {
            EventStore.add(sessionId, "审查", "天级重编越权输出其它天，丢弃重编结果");
            return null;
        }
        dj.set(dayNo - 1, utils.JsonUtils.toJson(dp));
        String text = assembleDayPlanText(state, req);
        if (text == null || text.isBlank()) return null;
        state.setPlanResult(text);
        dto.TripPlanData whole = utils.JsonUtils.extractAndParse(
                utils.CandidateUtil.extractPlanBlock(text), dto.TripPlanData.class);
        EventStore.add(sessionId, "审查", "第" + dayNo + "天天级重编完成，整单已重拼");
        return whole;
    }

    /** ★删聚合·REVISE 重编：带审查反馈重调行程Agent（上版JSON为局部修改基准，候选仍前置注入）→ 提取新终版。失败返回 null（调用方保留上版）。 */
    private TripPlanData repairPlanByAgent(TravelState state, String style, String feedback, TripPlanData prev) {
        EventStore.add(sessionId, "审查", "带审查反馈重调行程Agent重编（候选已就绪，局部修正）...");
        String task = finalPlanTask(state, style, feedback,
                prev == null ? null : trimReviseBaseline(prev, feedback));
        String reply = await(PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(task)), "行程Agent(审查重编)");
        if (reply == null || reply.isBlank()) return null;
        String block = utils.CandidateUtil.extractPlanBlock(reply);
        if (block.isBlank()) {
            EventStore.add(sessionId, "行程原文", "（REVISE重编·无JSON块，行程Agent回复全文）\n" + reply);
            return null;
        }
        TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) {
            EventStore.add(sessionId, "行程原文", "（REVISE重编·JSON块解析失败，块原文）\n" + block);
            EventStore.add(sessionId, "审查", "重编未产出结构化终版JSON，保留上一版");
            return null;
        }
        // ★天数硬校验（2026-09-06）：整单重编模型可能把"补最后一天返程/收尾"误解成加天数（实测 3 日游被扩成 5 天）——
        //   天数必须与用户要求一致，不符即作废保留上一版，绝不交付错误天数的行程
        TripRequest req0 = state == null ? null : state.getRequest();
        int expectDays = req0 == null ? 0 : req0.getDays();
        if (expectDays > 0) {
            int got = 0;
            for (Section s : plan.getSections()) {
                if (s != null && "timeline".equals(s.getType())) got++;
            }
            if (got != expectDays) {
                EventStore.add(sessionId, "审查", "整单重编天数不符（应为 " + expectDays
                        + " 天，模型输出 " + got + " 天），作废保留上一版");
                return null;
            }
        }
        normalizePlan(state, plan);
        return plan;
    }

    /** ★景点坐标补齐：对景点候选缺失 location 的条目，用高德 place/text 按名称+目的地检索补坐标。
     *  返回补齐数量；QPS 限流规避（单次 350ms 间隔）。失败静默（保留原空值，下游自行兜底）。 */
    private int enrichSightLocations(java.util.Map<String, Object> cands, String dest) {
        int fixed = 0;
        try {
            java.util.List<java.util.Map<String, Object>> spots = candDomainList(cands, "attraction");
            for (java.util.Map<String, Object> it : spots) {
                Object loc = it.get("location");
                if (loc != null && !String.valueOf(loc).trim().isBlank()) continue;
                String name = str(it.get("name")).trim();
                if (name.isBlank()) continue;
                String city = dest == null ? "" : dest.trim();
                try {
                    String qs = "keywords=" + java.net.URLEncoder.encode(name, "UTF-8")
                            + "&city=" + java.net.URLEncoder.encode(city, "UTF-8")
                            + "&output=json&key=" + utils.ExternalServices.amapKey() + "&offset=1&extensions=base";
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                            utils.ExternalServices.amapPlaceSearchUrl(qs)).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    String body = new String(c.getInputStream().readAllBytes(), "UTF-8");
                    com.fasterxml.jackson.databind.JsonNode node = utils.JsonUtils.parse(body, com.fasterxml.jackson.databind.JsonNode.class);
                    com.fasterxml.jackson.databind.JsonNode pois = node == null ? null : node.path("pois");
                    if (pois != null && pois.isArray() && pois.size() > 0) {
                        String l = pois.get(0).path("location").asText("");
                        if (l != null && !l.isBlank()) {
                            it.put("location", l.trim());
                            fixed++;
                        }
                    }
                } catch (Exception ignored) {
                    // 单点失败静默（不阻塞整体）
                }
                try { Thread.sleep(350); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } catch (Exception e) {
            log.warn("景点坐标补齐异常(忽略): {}", e.getMessage());
        }
        return fixed;
    }

    /** ★REVISE 重编输入增量（2026-09-06）：上一版行程 JSON 按反馈涉及天裁剪后注入——
     *  涉及天（反馈出现「第N天/dayN」）全字段原样；未涉及天保留安排骨架（删 note 等细碎文本字段），
     *  模型照抄骨架不自由发挥，防走样；transport/stay/tips 区块原样。目的：重编任务输入减负，
     *  模型上下文更宽松（配合输出指示：预算集中给 JSON 块，防截断丢块）。解析失败则回退全量 JSON。 */
    private String trimReviseBaseline(TripPlanData prev, String feedback) {
        String full = utils.JsonUtils.toJson(prev);
        if (full == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = utils.JsonUtils.parse(full, com.fasterxml.jackson.databind.JsonNode.class);
            if (root == null || !root.isObject()) return full;
            java.util.Set<String> affected = new java.util.HashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("第\\s*(\\d+)\\s*天|day\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(feedback == null ? "" : feedback);
            while (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                if (g != null) affected.add(g);
            }
            if (affected.isEmpty()) return full;
            com.fasterxml.jackson.databind.JsonNode secs = root.get("sections");
            if (secs == null || !secs.isArray()) return full;
            java.util.regex.Pattern dayPat = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*天");
            for (com.fasterxml.jackson.databind.JsonNode sec : secs) {
                if (!"timeline".equals(sec.path("type").asText(""))) continue;
                java.util.regex.Matcher dm = dayPat.matcher(sec.path("title").asText(""));
                if (!dm.find()) continue;
                if (affected.contains(dm.group(1))) continue; // 涉及天：全字段原样
                com.fasterxml.jackson.databind.JsonNode items = sec.get("items");
                if (items == null || !items.isArray()) continue;
                for (com.fasterxml.jackson.databind.JsonNode it : items) {
                    if (it.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) it).remove("note");
                }
            }
            return utils.JsonUtils.toJson(root);
        } catch (Exception e) {
            return full;
        }
    }

    /** 从路线Agent回复提取【路线JSON】块解析出 totalKm/waypoints/routeCount（失败返回 null，退回旧正则提取）。
     *  ★用 Map 接收（非 DTO），避免 Jackson 默认 FAIL_ON_UNKNOWN_PROPERTIES 因路线Agent多写字段而整体失败。 */
    @SuppressWarnings("unchecked")
    /** ★里程推算天数软提示：路线Agent返回后，用真实总里程 ÷ 日均合理里程推算建议天数，偏紧时提醒（替代硬编码关键词表，天然覆盖所有长途路线） */
    private void suggestDaysByDistance(String routeResult, TripRequest req) {
        if (req == null || req.getDays() < 1) return;
        int totalKm = extractTotalDistance(routeResult);
        if (totalKm <= 0) return;
        String type = req.getTravelType() == null ? "" : req.getTravelType().trim().toUpperCase();
        int dailyKm = "CROSS_CITY".equals(type) ? 500 : 300; // 跨城高速日均500km，自驾/环线复杂路况日均300km
        // totalKm 现为【往返闭环】口径（routeTask 已要求含返程，见【路线JSON】口径铁律），不再 ×2
        int suggestDays = (int) Math.ceil((double) totalKm / dailyKm);
        if (suggestDays > req.getDays()) {
            EventStore.add(sessionId, "解析", "⚠ 天数偏紧：往返约 " + totalKm + " km（含返程），按日均 " + dailyKm
                    + " km 需约 " + suggestDays + " 天，当前仅 " + req.getDays() + " 天。"
                    + "该目的地往返里程由主干路线决定、缩短空间有限（重调缩不动即停）。"
                    + "建议：加到 " + suggestDays + " 天，或改走更近的替代路线（见【替代路线】方案）；已如实标注，行程按现有天数编排。");
        }
    }

    /** 从路线结果提取多条候选路线概要（格式见 routeTask："★候选路线一：途经城市：A市,B市..."），
     *  供行程Agent择优选择主线；提不到时返回空串（行程Agent按默认 waypoints 编排）。 */
    /** 候选路线拆分为列表（多方案每套各用一条作骨架，保证方案间真正路线不同）。 */
    private java.util.List<String> routeCandidateLines(String routeResult) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String block = extractRouteCandidates(routeResult);
        for (String line : block.split("\n")) {
            String t = line.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

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
            {"classic", "经典主线", "本次方案为「经典主线」：走最直接的主干路线（最短国道/高速主干），途经点取主干沿线，优先控制总里程与在途时间——里程最优、节奏紧凑。"},
            {"loop", "深度环线", "本次方案为「深度环线」：去程与返程走不同路线（不走回头路，环形串联），覆盖更多沿途观景点——里程略长但体验更丰富，适合时间充裕。"},
            {"leisure", "轻松向", "本次方案为「轻松向」：以每日车程短为第一优先（尽量≤4h），减少远距离节点、增加停留时长，必要时舍弃最远打卡点——适合不想赶路的用户。"},
    };

    /** ★替代路线风格（不可行兜底）：物理上完不成原目的地时，要求给用户一条天数内【真正可行】的替代路线。 */
    private String[] alternativeStyle(TripRequest req, int needDays) {
        return new String[]{"alternative", "替代路线",
                "本次方案为「替代路线」：原目的地（" + req.getDestination() + "）往返里程需约 " + needDays
                        + " 天、超出用户 " + req.getDays() + " 天，物理上无法舒适完成。"
                        + "请规划一条 " + req.getDays() + " 天内【真正可行】的替代路线（如改走更近的环线/缩短最远节点），"
                        + "硬约束：每天在途≤6小时、在途>4小时必须有景点停靠、每天游玩4~8小时；"
                        + "并在方案开头用一句话说明：与原目的地的取舍（放弃了什么、换来了什么）。"};
    }

    /** ★是否需要返程闭环：不是所有行程都要返程——
     *  自驾/环线（车要开回来）→ 必须；跨城看用户原话是否要求往返；单城市游/无出发地 → 不涉及。 */
    private boolean needsReturnLoop(TripRequest req) {
        if (req == null) return false;
        String origin = req.getOrigin();
        if (origin == null || origin.isBlank()) return false;
        String t = req.getTravelType() == null ? "" : req.getTravelType().trim().toUpperCase();
        if ("SELF_DRIVE".equals(t) || "LOOP".equals(t)) return true;
        String raw = req.getRaw() == null ? "" : req.getRaw();
        return raw.contains("往返") || raw.contains("返回") || raw.contains("来回") || raw.contains("回程");
    }

    /** ★替代路线任务（不可行兜底·单方案）：原目的地天数不够 → 要一条天数内【真正可行】的替代路线。 */
    private String alternativeTask(TripRequest req, int needDays) {
        return req.toStateBrief() + "\n"
                + "用户想去 " + req.getDestination() + "，从 " + req.getOrigin() + " 出发，行程 " + req.getDays() + " 天。"
                + "但该目的地往返里程需约 " + needDays + " 天，物理上无法在 " + req.getDays() + " 天内舒适完成。\n"
                + "请给出一条 " + req.getDays() + " 天内【真正可行】的替代路线"
                + "（如改走更近的环线 / 缩短最远节点 / 替换为同方向更近的目的地），例如川西可把小环线作为稻城亚丁的替代。\n"
                + "硬要求：①每天在途≤6小时、在途>4小时必须有景点停靠、每天游玩4~8小时；"
                + "②用 query_route_unified（mode=driving）查真实里程，不凭记忆估算；"
                + "③说明与原目的地的取舍（放弃了什么、换来了什么）。\n"
                + "最终回复：开头一行用【替代路线】标注 路线名 + 往返总里程，随后给逐日概要（简洁，不需完整行程）。";
    }

    /** Reviewer 修正重跑上限（轮） */
    private static final int MAX_REVIEW_ROUNDS = 2;

    /** ★删聚合·3 套差异化方案（美食向/出片向/省钱向）：候选/路线已由 resolveAndFetch 就绪，
     *  并行重调 3 次行程Agent（各带风格约束，同一批专家数据）→ 各自输出结构化终版JSON。
     *  单套失败退回原文兜底（标题带风格后缀）。 */
    private PlanSet aggregateMulti(TravelState state) {
        TripRequest req = state.getRequest();
        String routeResult = state.getRouteResult();
        String planResult = state.getPlanResult();
        java.util.List<String> candLines = routeCandidateLines(state.getRouteResult());
        String[][] styles = PLAN_STYLES;
        // ★不可行兜底：原目的地物理上完不成时，第3套（轻松向）换成【替代路线】——给用户一个天数内真正可行的选项（占多方案一席）
        if (state.getInfeasibleDays() > 0) {
            styles = new String[][]{PLAN_STYLES[0], PLAN_STYLES[1], alternativeStyle(req, state.getInfeasibleDays())};
            EventStore.add(sessionId, "定稿", "⚠ 原目的地（" + req.getDestination() + "）往返需约 " + state.getInfeasibleDays()
                    + " 天 > 用户 " + req.getDays() + " 天——第3套改为【替代路线】（天数内可行），供对比选择");
        }
        // 并行提交 3 套行程Agent 定稿（PARALLEL_POOL 已 4 线程；候选前置已共享，不重复查询）
        @SuppressWarnings("unchecked")
        final String[][] useStyles = styles; // lambda 捕获需 effectively final（styles 可能被替代路线覆盖）
        Future<TripPlanData>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final String routeLine = (idx < candLines.size()) ? candLines.get(idx) : "";
            futures[i] = PARALLEL_POOL.submit(() -> planOneStyle(state, useStyles[idx][2], routeLine));
        }
        PlanSet set = new PlanSet();
        int successCount = 0;
        for (int i = 0; i < 3; i++) {
            TripPlanData plan = null;
            try {
                // ★中断感知：轮询等待，点中断立即生效（原 300s 一次性阻塞，中断要等跑完）
                // ★2026-09-05 上限=2次A2A尝试×单次预算（默认600s）：与 RemoteAgentTool 每attempt独立300s一致，否则future先超时把重试中的套放弃
                long deadline = System.nanoTime() + managerAgent.tool.RemoteTimeout.timeout().toNanos() * 2;
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
        return planOneStyle(state, style, "");
    }

    private TripPlanData planOneStyle(TravelState state, String style, String routeLine) {
        try {
            String task = finalPlanTask(state, style, null, null, routeLine);
            String reply = remoteTool.callTripPlannerAgent(task); // 同步阻塞（A2A 自身超时控制）
            if (reply == null || reply.isBlank()) return null;
            String block = utils.CandidateUtil.extractPlanBlock(reply);
            if (block.isBlank()) {
                EventStore.add(sessionId, "行程原文", "（多方案单套·无JSON块，行程Agent回复全文）\n" + reply);
                return null;
            }
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

    // ==================== ★天骨架并行定稿（2026-09-06 架构） ====================
    // 动机：行程Agent 单次生成整单（7天终版JSON≈9千字）受模型长输出速度影响，单轮常超300s被掐断重试。
    // 改法：主管按候选坐标贪心成簇(≤5km)→最近邻地理链→按天切连续段→每天独立短任务(只输出当天，30-90s)
    //      并行生成→拼装成标准整单JSON文本(【行程JSON】块)，下游 parse/审查/缓存/降级链路完全不变。
    // 跨天连续：每天出发地=昨天末簇住宿地，由高德 regeo 城镇链代码锁定；簇不跨天=景点天然不重复、不回头。
    // 失败/不可用（候选无坐标、簇<2、天数<2、任一天失败）→ 返回 null，调用方回退原整单生成。
    /** 每天硬上限分钟数（8h）：分天/合并时的容量约束，与 ConstraintEngine 的"过于赶场"口径一致 */
    static final int PER_DAY_MAX_MIN = 480;

    static class DaySkel {
        int day;
        String fromTown = "";
        String endTown = "";
        double[] center = null;   // 当天候选质心 [lng,lat]
        double moveKm = -1;       // 昨天末簇质心 → 今天质心的直线距离（-1=无前序/不可算）
        final java.util.List<java.util.Map<String, Object>> members = new java.util.ArrayList<>();
        final java.util.List<double[]> locs = new java.util.ArrayList<>();
    }

    /** 主控：骨架并行生成整单行程JSON文本（可读概要+【行程JSON】块），失败返回 null。 */
    private String planByDaySkeleton(TravelState state, TripRequest req) {
        if (req == null || req.getDays() < 2) return null;
        java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());
        java.util.List<java.util.Map<String, Object>> spots = candDomainList(cands, "attraction");
        if (spots.size() < 3) return null;
        java.util.List<java.util.List<java.util.Map<String, Object>>> clusters = clusterKm(spots);
        if (clusters == null || clusters.size() < 2) return null;
        java.util.List<java.util.List<java.util.Map<String, Object>>> chain = nearestChain(clusters);
        if (chain == null) return null;
        java.util.List<DaySkel> days = cutIntoDays(chain, req.getDays(), req);
        if (days == null || days.size() != req.getDays()) return null;
        EventStore.add(sessionId, "定稿", "天骨架并行定稿：候选按坐标成" + clusters.size() + "簇→地理链切"
                + days.size() + "天，逐日短任务并行生成后拼装（防单次长输出超时）");
        // regeo 城镇链：每天质心反查住宿城镇；第1天出发=origin，其后出发=昨天住宿城镇
        for (int i = 0; i < days.size(); i++) {
            DaySkel sk = days.get(i);
            sk.day = i + 1;
            if (!sk.locs.isEmpty()) {
                // 住宿城镇取当天全部候选的质心反查（比"最后成员"更代表当日活动区域）
                double sx = 0, sy = 0;
                for (double[] p : sk.locs) { sx += p[0]; sy += p[1]; }
                sk.center = new double[]{sx / sk.locs.size(), sy / sk.locs.size()};
                sk.endTown = townNear(sk.center[0], sk.center[1]);
                if (sk.endTown == null) sk.endTown = "";
            }
            sk.fromTown = i == 0
                    ? (req.getOrigin() == null || req.getOrigin().isBlank() ? "" : req.getOrigin())
                    : days.get(i - 1).endTown;
        }
        // ★长途移动显式化（设计③）：昨天质心 → 今天质心的直线距离（山区实际车程≈直线×1.8-2.5），
        //   超阈值时在当天任务注入"长途移动日"模板——模型按真实移动写早出发/途中休整/到后轻量，不假装瞬移
        for (int i = 1; i < days.size(); i++) {
            DaySkel prev = days.get(i - 1);
            DaySkel cur = days.get(i);
            if (prev.center != null && cur.center != null) {
                cur.moveKm = havKm(prev.center[1], prev.center[0], cur.center[1], cur.center[0]);
            }
        }
        // 分批并行执行每天任务（8085 并发能力有限：实测 4+ 并发 A2A 时后提交任务失败——每批最多 3 个，
        // 批内并行、批间串行）；单天失败自动重试 1 次（每段独立 240s 预算，短输出必按时返回）
        int batchSize = 3;
        // ★2026-09-11 改为按【天号】索引：补生成/缺天交付时能精确定位是哪一天，拼装也不会错位
        java.util.Map<Integer, dto.TripPlanData> dayPlans = new java.util.TreeMap<>();
        java.util.List<String> failed = new java.util.ArrayList<>();
        int processed = 0;
        while (processed < days.size()) {
            int batchEnd = Math.min(processed + batchSize, days.size());
            java.util.List<java.util.concurrent.Future<String>> futs = new java.util.ArrayList<>();
            for (int i = processed; i < batchEnd; i++) {
                final DaySkel f = days.get(i);
                futs.add(PARALLEL_POOL.submit(() -> remoteTool.callTripPlannerAgent(daySkeletonTask(state, req, f))));
            }
            java.util.Map<Integer, dto.TripPlanData> batchPlans = new java.util.TreeMap<>();
            for (int i = 0; i < futs.size(); i++) {
                int dayNo = processed + i + 1;
                checkCancel();
                String label = "行程Agent·第" + dayNo + "天";
                String reply = waitBudget(futs.get(i), label, 240);
                dto.TripPlanData dp = parseDayReply(reply, dayNo, "骨架");
                if (dp == null) {
                    // 单天重试一次
                    EventStore.add(sessionId, "定稿", "第" + dayNo + "天首次生成失败，重试...");
                    final DaySkel f = days.get(processed + i);
                    String reply2 = waitBudget(PARALLEL_POOL.submit(() ->
                            remoteTool.callTripPlannerAgent(daySkeletonTask(state, req, f))), label + "(重试)", 240);
                    dp = parseDayReply(reply2, dayNo, "骨架重试");
                }
                if (dp == null) {
                    failed.add(String.valueOf(dayNo));
                    continue;
                }
                batchPlans.put(dayNo, dp);
            }
            if (!batchPlans.isEmpty()) {
                for (java.util.Map.Entry<Integer, dto.TripPlanData> e : batchPlans.entrySet()) {
                    dayPlans.put(e.getKey(), e.getValue()); // 按天号入表（TreeMap 天然有序）
                    EventStore.add(sessionId, "定稿", "第" + e.getKey() + "天生成完成("
                            + e.getValue().getSections().size() + "区块)");
                }
            }
            processed = batchEnd;
        }
        if (!failed.isEmpty()) {
            // ★2026-09-11 天级兜底（精准重试失败的部分）：
            //   并发是失败主因（8085 实测 4+ 并发 A2A 时后提交的任务会失败），所以补生成改为【串行】——
            //   只针对失败的那几天重试，已成功的天原样保留，不再把整批结果清空重来。
            EventStore.add(sessionId, "定稿", "天骨架有 " + failed.size() + " 天未成功（第"
                    + String.join("、", failed) + "天），正在串行补生成…");
            java.util.List<String> stillFailed = new java.util.ArrayList<>();
            for (String f : new java.util.ArrayList<>(failed)) {
                checkCancel();
                int dayNo = Integer.parseInt(f);
                DaySkel sk = days.get(dayNo - 1);
                String reply = waitBudget(PARALLEL_POOL.submit(() ->
                        remoteTool.callTripPlannerAgent(daySkeletonTask(state, req, sk))), "行程Agent·第" + dayNo + "天(串行补)", 240);
                dto.TripPlanData dp = parseDayReply(reply, dayNo, "串行补");
                if (dp == null) {
                    stillFailed.add(f);
                } else {
                    dayPlans.put(dayNo, dp); // 补成功：放回对应天号位置
                    EventStore.add(sessionId, "定稿", "第" + dayNo + "天串行补生成成功");
                }
            }
            failed.clear();
            failed.addAll(stillFailed);
        }

        if (failed.isEmpty()) {
            // 全部天成功：天级 JSON 存档（★REVISE 只重编涉及天）→ 统一拼装
            for (java.util.Map.Entry<Integer, dto.TripPlanData> e : dayPlans.entrySet()) {
                state.addDayPlanJson(utils.JsonUtils.toJson(e.getValue()));
            }
            state.addTrace("定稿", "天骨架并行生成完成(" + days.size() + "天，簇链切分)");
            return assembleDayPlanText(state, req, new java.util.ArrayList<>(dayPlans.keySet()));
        }

        // ★2026-09-11 降级分级：补生成后仍有天失败 → 保留已成功的天按天交付并如实标注，
        //   而不是像原来那样 clear() 后回退"整单生成"（整单更容易失败，一失败就退成纯文本）。
        if (dayPlans.isEmpty()) {
            EventStore.add(sessionId, "定稿", "天骨架全部天均失败（第" + String.join("、", failed) + "天），回退整单生成");
            state.getDayPlanJson().clear();
            return null;
        }
        EventStore.add(sessionId, "定稿", "⚠️ 第" + String.join("、", failed) + "天最终未生成成功，"
                + "其余 " + dayPlans.size() + " 天正常交付（缺失天已在行程内标注）");
        for (java.util.Map.Entry<Integer, dto.TripPlanData> e : dayPlans.entrySet()) {
            dto.TripPlanData dp = e.getValue();
            state.addDayPlanJson(e.getKey(), utils.JsonUtils.toJson(dp)); // 按天号对齐，缺天留空位
        }
        state.addTrace("定稿", "天骨架部分生成(" + dayPlans.size() + "/" + days.size() + "天)");
        return assembleDayPlanText(state, req, new java.util.ArrayList<>(dayPlans.keySet()));
    }

    /** 天骨架拼装（全部天）：兼容调用（天号=1..N） */
    private String assembleDayPlanText(TravelState state, TripRequest req) {
        java.util.List<Integer> all = new java.util.ArrayList<>();
        int n = state.getDayPlanJson() == null ? 0 : state.getDayPlanJson().size();
        for (int i = 1; i <= n; i++) all.add(i);
        return assembleDayPlanText(state, req, all);
    }

    /** 天骨架拼装：把 state.dayPlanJson（每天一段 TripPlanData JSON，<b>按天号对齐</b>，缺天为空位）
     *  拼成标准整单（可读概要 + 【行程JSON】块）。下游 parse/审查/缓存链路与整单模式一致。
     *
     *  ★2026-09-11 降级分级：只拼成功的天，缺的天在概要里<b>如实标注</b>，不再整体退回纯文本。
     *  失败（无任何有效天）返回 null。 */
    private String assembleDayPlanText(TravelState state, TripRequest req, java.util.List<Integer> dayNos) {
        java.util.List<String> dj = state.getDayPlanJson();
        if (dj == null || dj.isEmpty() || req == null || dayNos == null || dayNos.isEmpty()) return null;
        java.util.List<Object> allSecs = new java.util.ArrayList<>();
        java.util.List<String> summaries = new java.util.ArrayList<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < dj.size(); i++) {
            int dayNo = i + 1;
            String raw = dj.get(i);
            if (raw == null || raw.isBlank()) { missing.add("第" + dayNo + "天"); continue; } // 缺天：留空位
            dto.TripPlanData dp = utils.JsonUtils.extractAndParse(raw, dto.TripPlanData.class);
            if (dp == null || dp.getSections() == null || dp.getSections().isEmpty()) {
                missing.add("第" + dayNo + "天");
                continue;
            }
            String dayTag = "第" + dayNo + "天";
            for (dto.Section s : dp.getSections()) {
                String type = s.getType() == null ? "" : s.getType();
                String title = s.getTitle() == null ? "" : s.getTitle();
                if ("timeline".equals(type) && !title.contains(dayTag)) continue; // 防模型越权输出其它天
                allSecs.add(s);
            }
            summaries.add(oneLineDay(dp, dayNo));
        }
        if (allSecs.isEmpty()) return null; // 没有任何有效天 → 交给上游决定是否回退整单
        if (!missing.isEmpty()) { // ★缺天如实标注（进度/前端可见，不隐瞒）
            summaries.add("⚠️ " + String.join("、", missing) + "未能生成成功（已自动重试仍未成功），"
                    + "其余天正常交付；如需补齐，可对缺失的天重新提交生成。");
        }
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("title", (req.getDestination() == null ? "" : req.getDestination()) + req.getDays() + "日游");
        root.put("destination", req.getDestination());
        root.put("days", req.getDays());
        root.put("weather", "");
        root.put("sections", allSecs);
        String planJson = utils.JsonUtils.toJson(root);
        if (planJson == null || planJson.isBlank()) return null;
        return String.join("\n", summaries) + "\n\n【行程JSON】\n" + planJson + "\n【行程JSON结束】";
    }

    /** 单天任务文本：只编第 day 天；候选仅该天簇成员；给出出发地/住宿区域，输出标准【行程JSON】块（仅当天区块）。 */
    private String daySkeletonTask(TravelState state, TripRequest req, DaySkel sk) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是行程定稿Agent。本次任务：只编排整个行程的【第 ").append(sk.day).append(" 天】（共 ")
                .append(req.getDays()).append(" 天），【禁止】安排或提及其它天，也禁止编造候选清单之外的景点。\n\n");
        // ★2026-09-11 明确"候选预置"：三域候选已由主管查好并注入本任务（景点在下方候选区，酒店/餐饮在文末），
        //   禁止再调用 query_trip_candidates / query_trip 重复查询——这是 Agent 发起补查、补查完以叙述收尾的直接诱因。
        sb.append("【候选预置】本任务的候选（景点/酒店/餐饮）已由主管查好并附在文末与候选区，"
                + "【禁止】再调用 query_trip_candidates 或 query_trip 重复查询；确需补查也必须在本轮内完成并接着输出【行程JSON】。\n\n");
        sb.append("【整体需求】").append(req.getDestination()).append(" ").append(req.getDays()).append(" 日游");
        if (req.getPreference() != null && !req.getPreference().isBlank()) {
            sb.append("，偏好：").append(req.getPreference());
        }
        String tripPref = req.getTripPref();
        if (tripPref != null && !tripPref.isBlank()) {
            sb.append("；行程节奏偏好：").append(tripPref);
        }
        if (req.getBudgetTotal() > 0) {
            sb.append("；预算：").append((long) req.getBudgetTotal()).append(" 元");
        }
        sb.append("\n\n【今日出发地】")
                .append(sk.fromTown.isBlank() ? "行程起点（从出发城市启程）" : sk.fromTown)
                .append(sk.day == 1 ? "（第1天从行程起点出发）" : "（昨天住宿地，今天从这里前往今日景点；如与今日首景点相距较远请先写清移动方式与车程）")
                .append("\n");
        sb.append("【今日住宿区域】").append(sk.endTown.isBlank() ? "今日游玩区域就近" : sk.endTown)
                .append("（选择该区域真实酒店）\n");
        // ★长途移动日模板（设计③）：昨天末点→今天首点的直线距离超阈值时显式点名赶路，防"瞬移式"行程
        if (sk.moveKm > 150) {
            int est = (int) Math.round(sk.moveKm * (sk.moveKm > 300 ? 2.2 : 1.8)); // 山区系数粗估车程
            sb.append("【长途移动日】今天需从昨天行程末点（直线约 ").append((int) Math.round(sk.moveKm))
                    .append(" km，山区实际车程约 ").append(est).append(" 分钟）赶赴今日主景区。时间线必须按真实移动编排：")
                    .append("① 早出发（6:00-8:00，视车程定）；② 途中给 1-2 个真实途经休整/观景短停（写明地点，宁少勿编）；")
                    .append("③ 主景区游玩安排在到达后并给足时间；④ 若到达已过 15:00，今日只安排抵达+入住+轻量活动（如街区漫步/晚餐），")
                    .append("主景区改次日——禁止把长途车程写成一笔带过或让景点时间与车程矛盾。\n");
        } else if (sk.moveKm > 80) {
            sb.append("【车程较长】今天从昨天行程末点赴今日景区直线约 ").append((int) Math.round(sk.moveKm))
                    .append(" km，请给出合理出发时间与车程，避免景点时间与移动时长矛盾。\n");
        }
        sb.append("【今日时间预算】景点游玩 + 车程 合计 ≤ 8 小时（用餐/休整已另计）。"
                + "下方候选里标注的「建议游玩」是净游玩时长，请据此安排时间线；"
                + "若排不下就少安排一个景点，【禁止】压缩各景点的游玩时间凑数，"
                + "【禁止】为填满时间而编造候选之外的地点或把自由活动写成景点。\n");
        sb.append("【今日可安排景点候选（物理邻近组，可全选或选部分，最多5个）】\n");
        for (java.util.Map<String, Object> it : sk.members) {
            String nm = str(it.get("name"));
            String loc = str(it.get("location"));
            String pr = str(it.get("price"));
            String ot = str(it.get("openTime"));
            String du = str(it.get("duration"));
            sb.append("- ").append(nm.isBlank() ? "(未命名)" : nm)
                    .append(du.isBlank() ? "" : "（建议游玩 " + du + "）")
                    .append(loc.isBlank() ? "" : "（坐标 " + loc + "）")
                    .append(pr.isBlank() ? "" : " 票价:" + pr)
                    .append(ot.isBlank() ? "" : " 开放:" + ot).append("\n");
        }
        sb.append("\n【输出要求】按系统规则输出【行程JSON】块：sections 只含今天的 type=timeline（title 用「第")
                .append(sk.day).append("天」）与 type=stay；timeline 覆盖全天（到达/景点游玩/午餐晚餐/晚间），")
                .append("一个景区内部多站点合并成一条并写进 activity；当天所有条目 place 文本不得重复；cat=景点的条目不超过5个；")
                .append("概要每天一句话不超过60字。JSON 完整优先，宁短勿长，禁止截断 JSON 块。");
        // ★2026-09-11 修「Agent 只拿到景点候选 → 自己发起补查 → 补查完以叙述收尾、不输出 JSON」：
        //   原来只注入景点候选，Agent 判断酒店/餐饮缺失就去补查，补查后整轮以"我将补查…"这类计划性叙述结束，
        //   这一轮就作废（manager 只能判失败→重试，白等 2~5 分钟）。把主管已查好的酒店/餐饮候选一并注入。
        try {
            java.util.Map<String, Object> cands = utils.JsonUtils.extractAndParse(
                    state.getCandidatesJson(), java.util.Map.class);
            String[][] extra = {{"hotel", "今日住宿候选"}, {"dining", "今日餐饮候选"}};
            for (String[] de : extra) {
                java.util.List<java.util.Map<String, Object>> list = candDomainList(cands, de[0]);
                if (list.isEmpty()) continue;
                sb.append("\n【").append(de[1]).append("（主管已查好，直接选用，禁止再查）】\n");
                int n = 0;
                for (java.util.Map<String, Object> it : list) {
                    if (n >= 6) break;
                    String nm = str(it.get("name"));
                    if (nm.isBlank()) continue;
                    n++;
                    sb.append("- ").append(nm);
                    String loc = str(it.get("location"));
                    if (!loc.isBlank()) sb.append("（坐标 ").append(loc).append("）");
                    String pr = str(it.get("price"));
                    if (!pr.isBlank()) sb.append(" 人均:").append(pr);
                    String ad = str(it.get("address"));
                    if (!ad.isBlank()) sb.append(" 地址:").append(ad);
                    sb.append("\n");
                }
            }
        } catch (Exception ignored) {
            // 候选注入失败不影响主流程（Agent 仍可退回自行补查）
        }
        sb.append("\n★【本轮铁律】不要以「我先获取…」「我将补查…」等计划性叙述作为回复结束——"
                + "那等于本轮没有产出。候选数据已全部给在任务里：直接编排并【立即输出完整【行程JSON】块】。"
                + "确需补查时必须在本轮内先调用工具拿到数据，再在同一轮输出【行程JSON】。");
        return sb.toString().trim();
    }

    /** 从候选 Map 取某域 candidates 条目列表（域内字段平铺成新 Map）。 */
    private static java.util.List<java.util.Map<String, Object>> candDomainList(
            java.util.Map<String, Object> cands, String domain) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (cands == null) return out;
        Object dom = cands.get(domain);
        if (!(dom instanceof java.util.Map<?, ?> dm)) return out;
        Object cs = dm.get("candidates");
        if (cs instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) {
                    java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<?, ?> e : m.entrySet()) mm.put(String.valueOf(e.getKey()), e.getValue());
                    out.add(mm);
                }
            }
        }
        return out;
    }

    /** 解析高德 location "经度,纬度" → [lng,lat]；非法返回 null。 */
    private static double[] locOf(java.util.Map<String, Object> it) {
        if (it == null) return null;
        Object v = it.get("location");
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        int c = s.indexOf(',');
        if (c <= 0) return null;
        try {
            double lng = Double.parseDouble(s.substring(0, c).trim());
            double lat = Double.parseDouble(s.substring(c + 1).trim());
            return new double[]{lng, lat};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double havKm(double la1, double lo1, double la2, double lo2) {
        double R = 6371.0;
        double dLat = Math.toRadians(la2 - la1);
        double dLng = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 坐标候选贪心成簇（≤5km，同 CandidateUtil 口径）。不足2个有效坐标返回 null。 */
    private static java.util.List<java.util.List<java.util.Map<String, Object>>> clusterKm(
            java.util.List<java.util.Map<String, Object>> spots) {
        java.util.List<java.util.Map<String, Object>> coord = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> it : spots) if (locOf(it) != null) coord.add(it);
        if (coord.size() < 2) return null;
        java.util.List<java.util.List<java.util.Map<String, Object>>> groups = new java.util.ArrayList<>();
        java.util.List<double[]> cents = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> it : coord) {
            double[] p = locOf(it);
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int g = 0; g < groups.size(); g++) {
                double[] c = cents.get(g);
                double d = havKm(c[1], c[0], p[1], p[0]);
                if (d <= 5.0 && d < bestD) { best = g; bestD = d; }
            }
            if (best >= 0) {
                groups.get(best).add(it);
                double[] c = cents.get(best);
                int n = groups.get(best).size();
                c[0] = (c[0] * (n - 1) + p[0]) / n;
                c[1] = (c[1] * (n - 1) + p[1]) / n;
            } else {
                java.util.List<java.util.Map<String, Object>> g = new java.util.ArrayList<>();
                g.add(it);
                groups.add(g);
                cents.add(new double[]{p[0], p[1]});
            }
        }
        return groups;
    }

    /** 簇质心最近邻贪心排链（不回头），返回簇序列。 */
    private static java.util.List<java.util.List<java.util.Map<String, Object>>> nearestChain(
            java.util.List<java.util.List<java.util.Map<String, Object>>> clusters) {
        if (clusters == null || clusters.isEmpty()) return null;
        double[][] cents = new double[clusters.size()][2];
        for (int i = 0; i < clusters.size(); i++) {
            double sx = 0, sy = 0;
            java.util.List<double[]> ls = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> it : clusters.get(i)) {
                double[] p = locOf(it);
                if (p != null) { sx += p[0]; sy += p[1]; ls.add(p); }
            }
            if (ls.isEmpty()) return null;
            cents[i] = new double[]{sx / ls.size(), sy / ls.size()};
        }
        java.util.List<java.util.List<java.util.Map<String, Object>>> chain = new java.util.ArrayList<>();
        boolean[] used = new boolean[clusters.size()];
        int cur = 0;
        chain.add(clusters.get(0));
        used[0] = true;
        for (int step = 1; step < clusters.size(); step++) {
            int next = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < clusters.size(); i++) {
                if (used[i]) continue;
                double d = havKm(cents[cur][1], cents[cur][0], cents[i][1], cents[i][0]);
                if (d < best) { best = d; next = i; }
            }
            if (next < 0) break;
            chain.add(clusters.get(next));
            used[next] = true;
            cur = next;
        }
        return chain;
    }

    /** 地理链按天切连续段。
     *  区域环线/长途自驾（LOOP/SELF_DRIVE）：每天 1 个主景区簇（+顺路 <30km 的邻近簇成员）——川西这类
     *  景区间动辄 100-300km，同天塞两个相距 200km 的景区=审查必抓的"路线不合理"；多余簇为候选池供挑选，丢弃不进天。
     *  城市游：每天 ceil(C/D) 簇（同城景点密集、簇间短途，可多簇/天），链必须全部分完。
     *  每天景点候选数 ≤5。切不出完整天数返回 null（调用方回退整单）。 */
    static java.util.List<DaySkel> cutIntoDays(
            java.util.List<java.util.List<java.util.Map<String, Object>>> chain, int days, TripRequest req) {
        if (chain == null || days < 1 || chain.size() < 1) return null;
        boolean loop = req != null && req.getTravelType() != null
                && ("LOOP".equals(req.getTravelType().trim().toUpperCase())
                || "SELF_DRIVE".equals(req.getTravelType().trim().toUpperCase()));

        // ★2026-09-12 重写为【按景点游玩时长分天】（原逻辑"一簇一天"与设计不符）：
        //   候选景点自带 duration（"建议半天/建议1天/建议1-2天/建议2-3小时"），这才是分天依据——
        //   大景区（稻城亚丁"建议1-2天"）本就该占 1~2 天，半天景区可在同区域合并；
        //   每天控制在 4~8 小时（与 ConstraintEngine 的节奏口径一致）。
        //   天数(days)是硬约束：分出的组数不足则拆分最大的组，超出则并入相邻组。
        java.util.List<java.util.Map<String, Object>> flat = new java.util.ArrayList<>();
        for (java.util.List<java.util.Map<String, Object>> cl : chain) {
            // ★2026-09-12 簇内排序（决定"同区域里先玩谁"）：priority 升序（1=必去优先）→ rating 降序。
            //   priority 由景点候选专家输出（1 必去 / 2 强烈推荐 / 3 可选补充），缺失按 2 处理（兼容旧数据）。
            //   跨簇顺序仍由地理链决定（不绕路）。
            java.util.List<java.util.Map<String, Object>> cl2 = new java.util.ArrayList<>(cl);
            cl2.sort((a, b) -> {
                int pa = priorityOf(a), pb = priorityOf(b);
                if (pa != pb) return Integer.compare(pa, pb);
                return Double.compare(ratingOf(b), ratingOf(a));
            });
            flat.addAll(cl2);
        }
        if (flat.isEmpty()) return null;

        java.util.List<Integer> mins = new java.util.ArrayList<>();
        int total = 0;
        for (java.util.Map<String, Object> it : flat) {
            int m = durationMinutes(it.get("duration"));
            mins.add(m);
            total += m;
        }
        // 每天目标游玩时长：总时长均摊，夹在 240~480 分钟（4~8h）
        int perDay = Math.max(240, Math.min(480, (int) Math.ceil(total / (double) days)));

        java.util.List<java.util.List<java.util.Map<String, Object>>> groups = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> cur = new java.util.ArrayList<>();
        int acc = 0;
        for (int i = 0; i < flat.size(); i++) {
            int m = mins.get(i);
            // ★2026-09-12 大景区独占连续多天（张家界/稻城亚丁这类"建议2-3天"的景区，
            //   在行程里就是连续玩好几天，不能塞进某一天、也不能被当成半天）。
            //   判定用【单天硬上限 480】而不是【每天均摊目标 per】——
            //   实测踩过：per=ceil(6540/14)=468 时，ceil(480/468)=2，把"正好1天"的景区
            //   （九寨沟/四姑娘山/海螺沟）全判成占 2 天，一天变两天，天数直接爆掉。
            int need = Math.max(1, (int) Math.ceil(m / (double) PER_DAY_MAX_MIN));
            if (need > 1) {
                if (!cur.isEmpty()) { groups.add(cur); cur = new java.util.ArrayList<>(); acc = 0; }
                for (int d = 0; d < need && groups.size() < days; d++) {
                    java.util.List<java.util.Map<String, Object>> g = new java.util.ArrayList<>();
                    g.add(flat.get(i)); // 同一个景区出现在连续多天：每天写它的不同片区/线路
                    groups.add(g);
                }
                continue;
            }
            if (!cur.isEmpty() && acc + m > perDay) {
                groups.add(cur);
                cur = new java.util.ArrayList<>();
                acc = 0;
            }
            cur.add(flat.get(i));
            acc += m;
        }
        if (!cur.isEmpty()) groups.add(cur);

        // 天数对齐：不足则拆分最大的组（避免出现空天）；超出则并入相邻组（保地理连续）
        int guard = 0;
        while (groups.size() < days && guard++ < 200) {
            int best = -1, bestN = 1;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).size() > bestN) { bestN = groups.get(i).size(); best = i; }
            }
            if (best < 0) {
                groups.add(new java.util.ArrayList<>()); // 确实无组可拆：留一个机动/休整日（不硬塞景点）
            } else {
                java.util.List<java.util.Map<String, Object>> g = groups.get(best);
                int half = Math.max(1, g.size() / 2);
                groups.set(best, new java.util.ArrayList<>(g.subList(0, half)));
                groups.add(best + 1, new java.util.ArrayList<>(g.subList(half, g.size())));
            }
        }
        // ★2026-09-12 天数超额的处理顺序（"值最优"取舍，不是单纯删景点）：
        //   ① 先尝试把"景点数最少的组"并入相邻组（不丢景点，只是当天排得紧一点）；
        //   ② 仍超额才舍弃 priority=3（可选补充）的景点；③ 最后才动 priority=2。
        //   priority=1（必去）永不因为"天数不够"被丢弃。
        //   ★注意：不能合并"大景区独占多天"产生的单成员组（否则大景区又被压回1天）——
        //   判定见 sameSpotAllDays()：这些组虽然只有1个成员，但整个行程里该景点占了多天。
        int guard2 = 0;
        while (groups.size() > days && guard2++ < 200) {
            // ① 优先舍 priority=3（可选补充）——这是"值最优"的取舍，比硬合并更符合业务
            int bi = -1, bj = -1;
            for (int i = 0; i < groups.size(); i++) {
                for (int j = 0; j < groups.get(i).size(); j++) {
                    if (priorityOf(groups.get(i).get(j)) >= 3) { bi = i; bj = j; break; }
                }
                if (bi >= 0) break;
            }
            if (bi >= 0) {
                groups.get(bi).remove(bj);
                if (groups.get(bi).isEmpty()) groups.remove(bi);
                continue;
            }
            // ② 合并"单成员且非大景区"的组到相邻组——★必须检查容量，否则会出现
            //   "稻城亚丁960+九寨沟480=24小时"这种物理上排不完的一天（实测复现过）
            int mi = -1, mn = Integer.MAX_VALUE;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).size() > 1) continue;   // 只考虑"单成员组"
                if (isMultiDaySpot(groups, i)) continue;  // 大景区独占的多天：不许合并
                int tgt = i > 0 ? i - 1 : (i + 1 < groups.size() ? i + 1 : -1);
                if (tgt < 0) continue;
                int load = spotMinutes(groups.get(tgt)) + spotMinutes(groups.get(i));
                if (load > PER_DAY_MAX_MIN) continue;     // ★装不下就不合并
                if (groups.get(i).size() < mn) { mn = groups.get(i).size(); mi = i; }
            }
            if (mi >= 0) {
                java.util.List<java.util.Map<String, Object>> g = groups.get(mi);
                if (!g.isEmpty()) {
                    java.util.Map<String, Object> spot = g.get(0);
                    int tgt = mi > 0 ? mi - 1 : (mi + 1 < groups.size() ? mi + 1 : -1);
                    if (tgt >= 0) groups.get(tgt).add(spot);
                }
                groups.remove(mi);
                continue;
            }
            // ③ 兜底：候选总量本身就超过 days 天容量（例：14 天行程、候选合计≈15 天量），
            //   容量约束已无解 → 把最后一组并入"全局负荷最小"的那一组，避免全堆在末尾。
            java.util.List<java.util.Map<String, Object>> g = groups.remove(groups.size() - 1);
            int tgt = 0, minLoad = Integer.MAX_VALUE;
            for (int i = 0; i < groups.size(); i++) {
                int load = spotMinutes(groups.get(i));
                if (load < minLoad) { minLoad = load; tgt = i; }
            }
            groups.get(tgt).addAll(g);
        }
        if (groups.size() != days) return null;

        java.util.List<DaySkel> out = new java.util.ArrayList<>();
        for (java.util.List<java.util.Map<String, Object>> g : groups) {
            DaySkel sk = new DaySkel();
            for (java.util.Map<String, Object> it : g) {
                sk.members.add(it);
                double[] p = locOf(it);
                if (p != null) sk.locs.add(p);
            }
            out.add(sk);
        }
        return out;
    }

    /**
     * 一组景点"摊到当天"的游玩分钟数（容量校验用）。
     * ★大景区独占多天时必须按天均摊：稻城亚丁"建议1-2天"=960min 占了连续 2 天，
     *   每天实际只有 480min——若按 960 算，容量校验会把这种正常情况误判成"超载"。
     */
    static int spotMinutes(java.util.List<java.util.Map<String, Object>> group) {
        if (group == null) return 0;
        int s = 0;
        for (java.util.Map<String, Object> it : group) {
            int m = durationMinutes(it.get("duration"));
            int need = Math.max(1, (int) Math.ceil(m / (double) PER_DAY_MAX_MIN));
            s += (need > 1) ? (int) Math.ceil(m / (double) need) : m; // 跨天均摊
        }
        return s;
    }

    /**
     * 判断第 i 组是否是"大景区独占多天"中的一天：该组的唯一景点在**其它组里也出现**。
     * 用途：天数超额时禁止合并这种组（否则张家界/稻城亚丁这种"建议2-3天"的大景区
     * 会被压回 1 天，正好推翻"大景区独占连续多天"的设计）。
     */
    static boolean isMultiDaySpot(
            java.util.List<java.util.List<java.util.Map<String, Object>>> groups, int i) {
        if (groups == null || i < 0 || i >= groups.size()) return false;
        java.util.List<java.util.Map<String, Object>> g = groups.get(i);
        if (g.size() != 1) return false;
        Object name = g.get(0).get("name");
        if (name == null) return false;
        for (int j = 0; j < groups.size(); j++) {
            if (j == i) continue;
            for (java.util.Map<String, Object> m : groups.get(j)) {
                if (name.equals(m.get("name"))) return true;
            }
        }
        return false;
    }

    /** 读取候选的 priority（1 必去 / 2 强烈推荐 / 3 可选补充），缺失或非法按 2（兼容旧数据/不误伤） */
    static int priorityOf(java.util.Map<String, Object> it) {
        if (it == null) return 2;
        Object v = it.get("priority");
        if (v == null) return 2;
        try {
            int p = (int) Math.round(Double.parseDouble(String.valueOf(v).replaceAll("[^0-9.]", "")));
            return (p >= 1 && p <= 3) ? p : 2;
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** 读取候选的 rating（评分），认不出返回 0（排序用，缺失不影响正确性） */
    static double ratingOf(java.util.Map<String, Object> it) {
        if (it == null) return 0;
        Object v = it.get("rating");
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 把候选景点的 duration 文本解析成分钟数（用于按游玩时长分天）。
     * 输入形如："建议1-2天" / "建议1天" / "建议半天-1天" / "建议半天" / "建议2-3小时" / "3小时" / 空。
     * 空值给 240（半天）—— 宁可低估也不要放大，避免把整天空掉。
     */
    static int durationMinutes(Object dur) {
        String s = dur == null ? "" : String.valueOf(dur);
        if (s.isBlank()) return 240;
        // ★"半天-1天/半天~1天"必须先判：否则下面的"X天"正则会先吃掉里面的"1天"（实测踩过）
        if (s.contains("半天-1天") || s.contains("半天~1天")) return 360;
        // ★"X天/X-Y天"通用解析（张家界、稻城亚丁这类大景区会写"建议2-3天"）——
        //   取上限（宁可按最长算，也不要把大景区压成半天导致分天排不下）
        java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("(\\d+)\\s*\\s*(?:-|~|—|–)\\s*(\\d+)\\s*天")
                .matcher(s);
        if (dm.find()) {
            try {
                int hi = Integer.parseInt(dm.group(2));
                if (hi > 0) return Math.min(30, hi) * 480;
            } catch (NumberFormatException ignored) { }
        }
        java.util.regex.Matcher dm2 = java.util.regex.Pattern.compile("(\\d+)\\s*天").matcher(s);
        if (dm2.find()) {
            try {
                int n = Integer.parseInt(dm2.group(1));
                if (n > 0) return Math.min(30, n) * 480;
            } catch (NumberFormatException ignored) { }
        }
        if (s.contains("半天-1天") || s.contains("半天~1天")) return 360;
        if (s.contains("半天")) return 240;
        if (s.contains("2-3小时") || s.contains("2~3小时")) return 150;
        // 其它："X小时" / "X-Y小时" → 取最大值的小时数
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*(?:\\.\\d+)?\\s*(?:-\\s*(\\d+)\\s*)?(小时|h|H)")
                .matcher(s);
        if (m.find()) {
            int h = 0;
            try {
                h = Integer.parseInt(m.group(1));
                if (m.group(2) != null) h = Math.max(h, Integer.parseInt(m.group(2)));
            } catch (NumberFormatException ignored) { }
            if (h > 0) return Math.min(480, h * 60);
        }
        return 240; // 认不出的格式：按半天处理
    }

    /** 簇质心 [lng,lat]；空簇返回 null。 */
    private static double[] centroidOf(java.util.List<java.util.Map<String, Object>> cl) {
        double sx = 0, sy = 0;
        int n = 0;
        for (java.util.Map<String, Object> it : cl) {
            double[] p = locOf(it);
            if (p != null) { sx += p[0]; sy += p[1]; n++; }
        }
        return n == 0 ? null : new double[]{sx / n, sy / n};
    }

    /** 高德 regeo 逆地理：坐标反查城镇（township 优先，空则 district）。QPS 限流：调用间隔 350ms。 */
    private String townNear(double lng, double lat) {
        try {
            String url = "https://restapi.amap.com/v3/geocode/regeo?location=" + lng + "," + lat
                    + "&key=" + utils.ExternalServices.amapKey() + "&extensions=base";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");
            com.fasterxml.jackson.databind.JsonNode node = utils.JsonUtils.parse(body, com.fasterxml.jackson.databind.JsonNode.class);
            String tw = node == null ? "" : node.path("regeocode").path("addressComponent").path("township").asText("");
            if (tw != null && !tw.isBlank()) return tw;
            String dt = node == null ? "" : node.path("regeocode").path("addressComponent").path("district").asText("");
            return dt == null ? "" : dt;
        } catch (Exception e) {
            return "";
        } finally {
            try { Thread.sleep(350); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** 单天独立等待：预算 budgetSec 秒（区别于整单 await 的 RemoteTimeout），带取消轮询与事件记录。 */
    private String waitBudget(java.util.concurrent.Future<String> future, String label, long budgetSec) {
        EventStore.add(sessionId, "调用工具", "调用「" + label + "」获取真实数据...");
        long deadline = System.nanoTime() + budgetSec * 1_000_000_000L;
        try {
            while (true) {
                checkCancel();
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainMs <= 0) {
                    future.cancel(true);
                    EventStore.add(sessionId, "Agent结果", label + " ❌ 单天生成超时(" + budgetSec + "s)");
                    return "";
                }
                try {
                    String r = future.get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);
                    return r == null ? "" : r;
                } catch (java.util.concurrent.ExecutionException ex) {
                    if (ex.getCause() instanceof CancelledException) {
                        throw (CancelledException) ex.getCause();
                    }
                    throw ex;
                } catch (java.util.concurrent.TimeoutException e) {
                    // 本轮 500ms 未完成，回循环头检查中断
                }
            }
        } catch (CancelledException e) {
            future.cancel(true);
            EventStore.add(sessionId, "Agent结果", label + " ⏹ 已中断");
            throw e;
        } catch (Exception e) {
            EventStore.add(sessionId, "Agent结果", label + " ❌ 调用失败(" + e.getMessage() + ")");
            return "";
        }
    }

    /** 解析单天回复 → TripPlanData；无块/解析失败留档原文并返回 null（供重试判定）。 */
    private dto.TripPlanData parseDayReply(String reply, int dayNo, String ctx) {
        if (reply == null || reply.isBlank()) return null;
        String block = utils.CandidateUtil.extractPlanBlock(reply);
        if (block.isBlank()) {
            EventStore.add(sessionId, "行程原文", "（第" + dayNo + "天" + ctx + "·无JSON块，回复全文）\n" + reply);
            return null;
        }
        dto.TripPlanData dp = utils.JsonUtils.extractAndParse(block, dto.TripPlanData.class);
        if (dp == null || dp.getSections() == null || dp.getSections().isEmpty()) {
            EventStore.add(sessionId, "行程原文", "（第" + dayNo + "天" + ctx + "·JSON解析失败，块原文）\n" + block);
            return null;
        }
        return dp;
    }

    /** 单天行程一句话概要（拼装整单的可读概要）。 */
    private static String oneLineDay(dto.TripPlanData dp, int day) {
        StringBuilder sb = new StringBuilder("第" + day + "天：");
        int items = 0;
        String first = "", last = "";
        if (dp.getSections() != null) {
            for (dto.Section s : dp.getSections()) {
                if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;
                for (dto.SectionItem it : s.getItems()) {
                    if (it == null) continue;
                    String p = it.getPlace() == null ? "" : it.getPlace().trim();
                    if (p.isBlank()) continue;
                    if (first.isBlank()) first = p;
                    last = p;
                    items++;
                }
            }
        }
        if (first.isBlank()) return sb.append("行程内容生成，详见JSON").toString();
        sb.append(first);
        if (!last.equals(first)) sb.append(" → ").append(last);
        sb.append("（").append(items).append(" 个地点安排）");
        return sb.toString();
    }
}