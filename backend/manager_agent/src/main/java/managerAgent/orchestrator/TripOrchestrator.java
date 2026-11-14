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
@Slf4j  // 注入日志对象 log
public class TripOrchestrator {  // 编排器：一次任务一个实例，状态全在字段与 TravelState 里

    // 任务内部并行池：天骨架分批、3 套方案、逐天修复都提交到这里（见 planOneStyle / planByDaySkeleton）。
    // 大小随并发闸联动：target = min(POOL_MAX_CAP, max(POOL_CORE, 闸 × 6))。
    // 下限 8：单任务真实并行峰值约 3（方案/天并行）+ 主线程 + 余量；上限 16：防失控。
    // 真正压向 8085 的并发由子 Agent 侧信号量把关，本池只决定请求是并行还是排队。
    private static final int POOL_CORE = 8;      // 并行池下限（见上方说明）
    private static final int POOL_MAX_CAP = 16;  // 并行池上限（防失控）
    private static final java.util.concurrent.ThreadPoolExecutor PARALLEL_POOL =  // 并行池实例
            new java.util.concurrent.ThreadPoolExecutor(  // 构造线程池（队列 256、守护线程、池满回退调用线程）
                    POOL_CORE, POOL_CORE, 60L, java.util.concurrent.TimeUnit.SECONDS,  // 核心=最大=8，空闲 60 秒回收
                    new java.util.concurrent.LinkedBlockingQueue<>(256),  // 队列 LinkedBlockingQueue(256)：超出核心线程数就排队
                    r -> { Thread t = new Thread(r, "trip-parallel"); t.setDaemon(true); return t; },  // 线程工厂：命名 trip-parallel，设为守护线程（不阻止 JVM 退出）
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());  // 拒绝策略 CallerRunsPolicy：池满时由调用线程自己跑（降速但不丢任务）

    /** ★2026-09-11 线程池大小与并发闸联动：闸=N → 供 N×6 个并行线程（截断到 POOL_MAX_CAP），让闸真正生效。
     *  单任务并行需求（3套方案 + 天级重编并行）约 6 线程/任务，故取 6 倍。 */
    private static volatile int lastPoolSize = -1; // 首次/变化时打一次日志，便于确认联动生效  // lastPoolSize：上次记录的池大小，首次或变化时打一次日志
    /**
     * 作用：按配置同步内部线程池大小（并行执行专家/逐天修复调用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private static void syncPoolSize() {  // run() 入口先调：按当前并发闸对齐本任务并行池大小
        try {  // 池大小是性能参数，调整失败不影响任务执行
            int gate = managerAgent.service.TripTaskService.maxConcurrent();  // 当前并发闸上限（内部会同步 Nacos 配置）
            int target = Math.min(POOL_MAX_CAP, Math.max(POOL_CORE, gate * 6)); // 目标线程数 = min(16, max(8, 闸×6))：闸=1→8，闸=2→12，闸≥3→16
            if (PARALLEL_POOL.getCorePoolSize() != target) {  // 与当前核心数不同才调整
                PARALLEL_POOL.setMaximumPoolSize(target); // 先放宽 maximum（否则里面 setCore 会因 max<core 抛异常）
                PARALLEL_POOL.setCorePoolSize(target);    // 再对齐 core，线程数立刻生效
            }  // if 结束
            if (lastPoolSize != target) { // 首次或数值变化时记录一次
                log.info("[pool] PARALLEL_POOL = {} 线程（并发闸={}，单任务约需 6 线程）", target, gate);  // 便于运维确认联动已生效
                lastPoolSize = target;  // 记住本次值
            }  // if 结束
        } catch (Exception e) {  // 调整失败只降级告警
            log.warn("[pool] 调整 PARALLEL_POOL 失败（忽略，保持原大小）: {}", e.getMessage());  // 保持原大小：池大小不影响正确性
        }  // catch 结束
    }  // 方法结束

    private final String sessionId;  // 本任务的会话 ID（事件推送 / 调用链 / 取消都按它隔离）
    private final Model model;               // 解析 / 聚合用的裸模型（不走 SafeModel，直连）
    private final RemoteAgentTool remoteTool; // 调两个子 Agent 的 A2A 通道
    /**
     * 作用：构造编排器，绑定本次任务的会话 ID（事件推送、调用链与取消都按会话隔离）
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无
     */

    public TripOrchestrator(String sessionId) {  // 一次任务构造一个编排器
        this.sessionId = sessionId;  // 保存会话 ID：事件推送、调用链、取消都按它隔离
        this.model = AgentUtils.buildModel();  // 构建模型客户端（内部已包限速/重试/熔断）
        this.remoteTool = new RemoteAgentTool();  // 创建 A2A 调用工具
        this.remoteTool.setSessionId(sessionId);  // 注入会话 ID：子 Agent 的结果事件按会话归位
    }  // 构造器结束

    /**
     * 作用：编排入口（无沉淀偏好）：需求归一化 → 三域候选 → 天骨架 → 路线 → 逐天细化 → 校验评分 → 评审回环，产出结构化行程
     * 参数：userPrompt 用户原始需求文本
     * 返回值：结构化行程；彻底失败时返回 markdown 原文兜底的降级结果
     * 异常：CancelledException 用户请求中断时抛出
     */
    public TripPlanData run(String userPrompt) {  // run 单参签名
        return run(userPrompt, null);  // 委托给带偏好版本，prefs 传 null
    }  // 方法结束

    /**
     * 执行编排（带偏好参数）：解析需求 → 并行调用专家 → 聚合为结构化行程。
     */

    /**
     * 作用：编排入口（带个人中心沉淀偏好）：与单参版本相同，额外把偏好并入需求
     * 参数：userPrompt 用户原始需求文本；prefs 个人中心沉淀偏好，可为 null
     * 返回值：结构化行程；失败时返回降级结果
     * 异常：CancelledException 用户请求中断时抛出
     */
    public TripPlanData run(String userPrompt, TripRequest.Preferences prefs) {  // run 双参签名
        syncPoolSize(); // ★2026-09-11 并发对齐：按当前并发闸调整并行池大小，避免闸调大后仍在旧线程数上排队  // syncPoolSize：按当前闸对齐线程池（管理端调大闸后，池要跟着变大）
        return runCore(userPrompt, prefs);  // 进入 runCore
    }  // 方法结束

    /**
     * 作用：编排主流程：候选与骨架 → 路线补算 → 逐天细化 → 组装 → 校验评分 → 评审回环与定点修复 → 如实标注交付
     * 参数：userPrompt 用户原始需求文本；prefs 沉淀偏好，可为 null
     * 返回值：结构化行程（含降级 markdown 字段与证据归档）
     * 异常：CancelledException 用户中断；其余异常上层兜底
     */
    private TripPlanData runCore(String userPrompt, TripRequest.Preferences prefs) {  // runCore 签名（私有）
        TravelState state = resolveAndFetch(userPrompt, prefs, true);  // resolveAndFetch：把解析/缓存/候选/路线/子 Agent 调用全干完，返回状态中心
        // ★输入侧缺失亮红灯：哪路专家数据为空，定稿前明确告知（避免静默产出半成品还显示成功）
        String missingInput = missingInputs(state.getRouteResult(), state.getPlanResult());  // missingInputs：检查路线/行程结果是否为空
        if (!missingInput.isBlank()) {  // 有缺失
            EventStore.add(sessionId, "定稿", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");  // 写事件：⚠️ 缺失内容 + “本次结果可能不完整，将如实标注”
        }  // if 结束
        EventStore.add(sessionId, "思考", "行程Agent 正在基于全量信息编排最终行程...");  // 写事件：行程 Agent 正在编排最终行程
        // ★删聚合：行程Agent 回复中提取结构化终版【行程JSON】块 → TripPlanData；无块/解析失败降级原文兜底
        TripPlanData plan = parseTripPlan(state);  // parseTripPlan：抽块并反序列化
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) {  // 解析失败或没有 sections
            plan = fallbackPlan(state.getRequest(), state.getRouteResult(), state.getPlanResult()); // 兜底已含完整 markdown 原文  // fallbackPlan：用原文兜底（已含完整 markdown）
            EventStore.add(sessionId, "定稿", "行程Agent未返回结构化终版JSON，降级为原文交付");  // 写事件：未返回结构化终版 JSON，降级为原文交付
            // ★诊断留档（2026-09-06）：回复原文入事件流落库，事后可查"为什么没带 JSON 块"（长文本/格式错/截断）
            if (state.getPlanResult() != null && !state.getPlanResult().isBlank()) {  // 有行程原文
                EventStore.add(sessionId, "行程原文", "（定稿降级·行程Agent回复全文）\n" + state.getPlanResult());  // 把行程 Agent 回复全文写进事件流
            }  // if 结束
            // ★观测完整性：降级路径没有可审查的结构化方案，审查闭环客观未执行——必须显式记录而非静默跳过，
            //   否则管理端质量观测缺失审查节点，用户无法区分"审了通过"与"根本没审"
            EventStore.add(sessionId, "审查", "⚠️ 原文降级无结构化方案，审查闭环未执行（仅做确定性校验+评分）");  // 写事件：⚠️ 原文降级无结构化方案，审查闭环未执行（仅做确定性校验+评分）
        } else {  // 否则（结构化成功）
            // ★2026-09-12 区块规整化（实测 bug）：整单路径一次产出 23 个区块，含 3 个逐字重复的
            //   「住宿与餐饮」、7 种命名的 stay 碎片、甚至住宿排在当天行程之前。这里做代码级规整：
            //   ① 去重（type+title+items 完全相同的只留一个）② 按天号排序（同天的 timeline 在前、住宿在后）
            normalizePlanSections(plan, sessionId);  // normalizePlanSections：代码级规整
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段  // renderMarkdown：生成降级用的 markdown 字段（结构化成功才渲染）
            // ④ Reviewer 闭环：每轮审查前先跑确定性校验+六维评分（纯代码，作为审查依据）→ REVISE 定向重编（≤2轮）
            EventStore.add(sessionId, "审查", "独立审查员正在检查行程质量...");  // 写事件：独立审查员正在检查行程质量
            plan = reviewerLoop(state, plan, null);  // reviewerLoop：进入评审闭环（返回修复后的行程）
        }  // else 结束
        // ★不可行兜底：单方案模式下额外输出【替代路线】（原目的地物理上完不成时，给用户二选一）
        if (state.getInfeasibleDays() > 0 && plan != null) {  // 有不可行天数且 plan 非空
            try {  // try
                EventStore.add(sessionId, "路线", "原目的地往返需约 " + state.getInfeasibleDays()  // 写事件：原目的地往返需约 N 天 > 用户 M 天，生成替代路线
                        + " 天 > 用户 " + state.getRequest().getDays() + " 天，生成【替代路线】建议（天数内可行）...");  // 续行
                final TripRequest altReq = state.getRequest();  // altReq：当前需求
                final int altNeed = state.getInfeasibleDays();  // altNeed：需要多少天才够
                String alt = await(PARALLEL_POOL.submit(() ->  // 起后台任务调路线 Agent 生成替代路线（alternativeTask 带“天数内可行”约束），await 等结果
                        remoteTool.callRouteMakingAgent(alternativeTask(altReq, altNeed))), "路线Agent·替代");  // 续行：await 带标签“路线Agent·替代”
                if (alt != null && !alt.isBlank()) {  // 拿到非空替代路线
                    // 从【替代路线】标记处截取（去掉路线Agent 的英文思考前言）
                    int mk = alt.indexOf("【替代路线】");  // 找标记位置
                    String altText = clip(mk >= 0 ? alt.substring(mk) : alt, 1200);  // clip 到 1200 字符
                    plan.setAlternative(altText);  // 挂到 plan.alternative
                    EventStore.add(sessionId, "替代路线", altText);  // 写事件：替代路线
                }  // if 结束
            } catch (Exception e) {  // 失败只 warn，不影响主交付
                log.warn("替代路线生成失败(不阻断交付): {}", e.getMessage());  // 续行：日志
            }  // catch 结束
        }  // if 结束
        // ⑤ 终版确定性校验 + 六维评分 + 证据链存档
        validateAndReport(state, plan, "方案", true);  // validateAndReport：校验评分并写事件
        archiveEvidence(state, plan);  // archiveEvidence：候选证据归档到 plan
        EventStore.add(sessionId, "完成", "结构化行程生成完成");  // 写事件：结构化行程生成完成
        return plan;  // 返回 plan
    }  // 方法结束

    /**
     * 作用：调用约束引擎校验并把结果写入会话事件（可同时产出评分），返回校验报告
     * 参数：state 本次任务状态（需求/候选/会话）；plan 待校验行程；label 校验阶段标签（首次/修复后）；withScore 是否同时计算评分
     * 返回值：校验报告，永不为 null
     * 异常：无（异常内部捕获，返回空报告）
     */
    private dto.ValidationReport validateAndReport(TravelState state, TripPlanData plan, String label, boolean withScore) {  // validateAndReport 签名
        try {  // try：校验自身失败不能阻断交付
            dto.ValidationReport vr = utils.ConstraintEngine.validate(state.getRequest(), plan, state.getCandidatesJson());  // ConstraintEngine.validate：纯代码跑规则检查（天数/节奏/预算/时间列/交通衔接/开放时间…）
            if (!vr.getSkippedChecks().isEmpty()) {  // 有跳过项（缺数据无法判的检查）
                EventStore.add(sessionId, "校验", label + " 跳过检查: " + String.join("、", vr.getSkippedChecks()));  // 写进事件流：哪些检查被跳过（而不是默认通过）
            }  // if 结束
            EventStore.add(sessionId, "校验", label + " 校验结果 " + vr.summary() + vr.checksBrief());  // 写校验结论摘要 + 违反项简报
            if (withScore) {  // 需要评分时
                dto.ScoreResult sr = utils.PlanScorer.score(state.getRequest(), plan, vr,  // PlanScorer.score：六维评分，
                        state.getRouteResult() != null && !state.getRouteResult().isBlank(),  // 依据里带上“路线/行程是否拿到真实数据”，
                        state.getPlanResult() != null && !state.getPlanResult().isBlank());  // 缺数据的方案会被扣分
                EventStore.add(sessionId, "评分", label + " " + sr.getSummary());  // 写评分摘要事件
            }  // if 结束
            return vr;  // 返回校验报告（调用方只做记录，评审闭环内部会自己再调）
        } catch (Exception e) {  // 校验/评分自身异常
            log.warn("确定性校验/评分异常(不阻断交付): {}", e.getMessage());  // 只 warn：校验是保障，不该成为交付阻断点
            return new dto.ValidationReport();  // 返回空报告（调用方按“未校验”处理）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：把候选证据（来源/等级/时间）与最终评分归档，供前端溯源展示
     * 参数：state 任务状态；plan 最终行程
     * 返回值：无
     * 异常：无（归档失败只记日志）
     */
    private void archiveEvidence(TravelState state, TripPlanData plan) {  // archiveEvidence 签名
        try {  // try
            if (plan == null) return;  // plan 为空直接返回
            plan.setCandidatesJson(state.getCandidatesJson());  // 把候选原始 JSON 挂到 plan.candidatesJson
            java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());  // 解析候选
            plan.setEvidence(utils.CandidateUtil.evidenceList(cands));  // 生成 evidence 列表（每个条目的来源等级与时间）挂到 plan.evidence
            String summary = utils.CandidateUtil.evidenceSummary(cands);  // 生成证据摘要
            if (!summary.isBlank()) {  // 摘要非空
                EventStore.add(sessionId, "证据链", summary + "（后端留存，详见行程JSON evidence 字段）");  // 写事件：证据链摘要 + “后端留存，详见行程JSON evidence 字段”
            }  // if 结束
        } catch (Exception e) {  // 异常
            log.warn("证据链存档异常(忽略): {}", e.getMessage());  // 只 warn：证据归档失败不影响行程本身
        }  // catch 结束
    }  // 方法结束

    // ==================== Reviewer 闭环 ====================

    /**
     * 作用：交叉评审：让模型以评审员身份检查行程质量（真实性/节奏/偏好匹配），产出结论与问题清单
     * 参数：req 需求；plan 行程；vr 校验报告；sc 评分结果
     * 返回值：评审结论（结论/评分/问题清单/是否需重做）
     * 异常：无（评审失败按通过处理，不阻塞交付）
     */
    private ReviewVerdict reviewPlan(TripRequest req, TripPlanData plan, dto.ValidationReport vr, dto.ScoreResult sc) {  // reviewPlan 签名
        utils.SafeModel.setStage("行程审查");  // setStage：给这次模型调用打阶段标记（调用链里能看出在干什么）
        String loopLine = needsReturnLoop(req)  // 按是否需要返程闭环生成要求：
                ? "\n\n【闭环要求】本行程【需要返程闭环】：最后一天必须回到出发地 " + req.getOrigin()  // 需要：最后一天必须回到出发地
                  + "。若行程未回到出发地、且无合理说明（如明确单程/异地还车），属 high 问题（type=route，target=overall，verdict=REVISE）。"  // 续：未回到且无合理说明（单程/异地还车）属 high 问题（type=route，verdict=REVISE）
                : "\n\n【闭环要求】本行程【不需要返程闭环】（单城市游/单程/无出发地），不要因“没有返程”判 REVISE。";  // 不需要：明确告诉评审员不要因“没有返程”判 REVISE
        String user = "【用户需求】" + (req == null || req.getRaw() == null ? "" : req.getRaw())  // 拼用户消息：原始需求
                + "\n\n【确定性校验结果】" + (vr == null ? "未执行" : vr.detailedSummary())  // + 确定性校验明细（未执行则写“未执行”）
                + "\n\n【六维评分】" + (sc == null ? "未执行" : sc.getSummary())  // + 六维评分摘要
                + "\n\n【行程JSON】\n" + clip(utils.JsonUtils.toJson(plan), 6000)  // + 行程 JSON（clip 到 6000 字符，防超长被截断）
                + loopLine;  // + 闭环要求
        String out = callModelJson(AgentPrompts.tripReviewer(), user);  // callModelJson：用评审提示词调一次模型，要求输出 JSON
        return ReviewVerdict.parse(out); // null=解析失败，调用方按 PASS 兜底  // ReviewVerdict.parse 解析；null 表示解析失败，调用方按 PASS 兜底
    }  // 方法结束

    /**
     * 作用：评审回环：按评审问题分类定点修复（补路线/补候选/重排某天/整体重折），最多若干轮直到通过或判为改不动
     * 参数：state 任务状态；plan 当前行程；style 方案风格
     * 返回值：修复后的行程（未通过则返回最好的一版并标注）
     * 异常：CancelledException 用户中断时抛出
     */
    private TripPlanData reviewerLoop(TravelState state, TripPlanData plan, String style) {  // reviewerLoop 签名
        TripRequest req = state.getRequest();  // 取需求
        TripPlanData current = plan;  // current：当前行程（每轮可能被修复替换）
        boolean routeOk = state.getRouteResult() != null && !state.getRouteResult().isBlank();  // routeOk：路线结果是否有真实数据（评分用）
        boolean planOk = state.getPlanResult() != null && !state.getPlanResult().isBlank();  // planOk：行程结果是否非空
        for (int round = 1; round <= MAX_REVIEW_ROUNDS; round++) {  // 最多 MAX_REVIEW_ROUNDS(=2) 轮
            checkCancel();  // checkCancel：用户中断在这里生效
            dto.ValidationReport vr = null;  // vr 先置 null
            try {  // try
                vr = utils.ConstraintEngine.validate(req, current, state.getCandidatesJson());  // 跑一次确定性校验（纯代码，零成本）
            } catch (Exception ignored) {  // 校验异常
            }  // 忽略（本轮无校验依据也能审）
            if (vr != null) { // ★审查轮内校验也入事件流：看每轮重跑前后质量变化，不用翻日志  // 校验成功
                EventStore.add(sessionId, "校验", "第" + round + "轮审查前校验 " + vr.summary() + vr.checksBrief());  // 写事件：第 N 轮审查前校验结果
            }  // if 结束
            // ★⑦⑧ 审查依据（纯代码，零成本）：确定性校验 + 六维评分 → 喂审查员
            dto.ScoreResult sc = null;  // sc 先置 null
            try {  // try
                sc = utils.PlanScorer.score(req, current, vr, routeOk, planOk);  // 算六维评分
                EventStore.add(sessionId, "评分", "第" + round + "轮审查前 " + sc.getSummary());  // 写评分事件
            } catch (Exception e) {  // 评分异常
                log.warn("第{}轮审查前评分异常(不阻断): {}", round, e.getMessage());  // 只 warn，不阻断
            }  // catch 结束
            ReviewVerdict v = reviewPlan(req, current, vr, sc);  // reviewPlan：让模型审查
            if (v == null) {  // 解析失败（v 为 null）
                EventStore.add(sessionId, "审查", "第" + round + "轮审查输出异常，按通过处理");  // 写事件：本轮审查输出异常，按通过处理
                ReviseSummary.recordRound(sessionId, round, "PARSE_ERROR", -1, null, "accepted"); // 解析失败按通过收尾  // 记一轮 PARSE_ERROR
                ReviseSummary.finish(sessionId, "parse_error");  // 收尾标记 parse_error
                break;  // 跳出循环
            }  // if 结束
            if (!v.needsRevise()) {  // 不需要修改（审查通过）
                EventStore.add(sessionId, "审查", "第" + round + "轮审查通过"  // 写事件：第 N 轮审查通过
                        + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : "")  // 续：有质量分就带上
                        + (v.getIssues().isEmpty() ? "" : "（" + v.getIssues().size() + " 条轻微建议已记录）"));  // 续：有轻微建议就说明已记录
                ReviseSummary.recordRound(sessionId, round, "PASS", v.getScore(), null, "accepted");  // 记一轮 PASS
                ReviseSummary.finish(sessionId, round == 1 ? "pass_first" : "pass_after_rework");  // 收尾：首轮通过 / 返工后通过
                break;  // 跳出循环
            }  // if 结束
            EventStore.add(sessionId, "审查", "第" + round + "轮审查 REVISE"  // 写事件：第 N 轮审查 REVISE，
                    + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : "") + "：" + v.feedbackText());  // 续：质量分与问题清单
            if (round == MAX_REVIEW_ROUNDS) {  // 已到最后一轮
                EventStore.add(sessionId, "审查", "已达修正上限(" + MAX_REVIEW_ROUNDS + " 轮)，交付当前版本");  // 写事件：已达修正上限(2 轮)，交付当前版本
                ReviseSummary.recordRound(sessionId, round, "REVISE", v.getScore(), snapshotIssues(v), "maxed_rework");  // 记一轮 REVISE（maxed）
                ReviseSummary.finish(sessionId, "maxed");  // 收尾标记 maxed
                break;  // 跳出循环（不再重做）
            }  // if 结束
            ReviseSummary.recordRound(sessionId, round, "REVISE", v.getScore(), snapshotIssues(v), "rework");  // 记一轮 REVISE（rework）
            EventStore.add(sessionId, "审查", "带审查反馈重编行程（第 " + round + " 次修正）...");  // 写事件：带审查反馈重编（第 N 次修正）
            // ★审查补查：问题涉及交通/衔接(domain=transport 或 type=route)时，先按当前方案景点补查真实路线，
            //   回填 routeResult 再重编——行程Agent 改完行程后衔接数据不过期、不靠模型脑补
            if (needRouteRefetch(v)) {  // 需要补路线
                refetchRouteForIssues(state, current);  // refetchRouteForIssues：补查并回填
            }  // if 结束
            // ★分路段候选补查：校验报"第N天内容过少/游玩时长不足"→ 定向补查该天路段候选，合并后重编
            refetchCandidatesForThinDay(state, current, vr);  // refetchCandidatesForThinDay：补查某天候选，合并后重编
            TripPlanData fixed = null; // 显式初始化：下面各分支若全部修复失败，fixed 保持 null（调用方会保留上一版）  // fixed 显式置 null：所有修复分支都失败时保持 null（调用方据此判断“没改出更好的”）
            try {  // try：修复过程可能抛异常
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
                java.util.Set<Integer> days = skeletonDayNos(state, v.feedbackText());  // skeletonDayNos：从评审反馈里抽出“提到哪几天”
                TripRequest reqR = state == null ? null : state.getRequest();  // reqR：需求
                int totalDays = reqR == null ? 0 : reqR.getDays();  // totalDays：总天数
                java.util.List<String> djR = state == null ? null : state.getDayPlanJson();  // djR：按天 JSON 列表
                boolean skeletonMode = djR != null && !djR.isEmpty() && djR.size() == totalDays;  // skeletonMode：有按天 JSON 且数量等于天数
                // ★2026-09-11 全局性意见（如"自驾/交通方式/住宿区域统一"）必须贯彻到每一天：
                //   实测踩过——审查说"把全部衔接改写为自驾口径"，但正文只提到某天，于是只修了那天，
                //   第 2 天整段仍是"打车"。所以命中全局关键词时，清空"提到哪天才修哪天"的限定，改为全量。
                boolean global = isGlobalFeedback(v.feedbackText());  // isGlobalFeedback：判断是否全局性意见
                if (global && skeletonMode) {  // 全局且骨架模式
                    days.clear();  // 清空天号集合
                }  // if 结束
                if (!days.isEmpty()) {  // 点名了具体天
                    EventStore.add(sessionId, "审查", "按天修复（第 "  // 写事件：按天修复（第 … 天）
                            + days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"))  // 续：把天号拼成顿号分隔
                            + " 天）...");  // 续
                    if (days.size() == 1) {  // 只有 1 天
                        fixed = repairDayByAgent(state, style, v.feedbackText(), days.iterator().next());  // repairDayByAgent 串行修（它会改写 dayPlanJson，不能并发）
                    } else {  // 否则
                        fixed = repairDaysInParallel(state, style, days, v.feedbackText()); // ★并行：原来逐天串行 3 天=282s  // repairDaysInParallel 并行修
                    }  // if 结束
                } else {  // 没点名具体天
                    if (skeletonMode) {  // 骨架模式
                        EventStore.add(sessionId, "审查", "审查意见未限定具体天，逐天全量修复（避免整单重编超时）...");  // 写事件：审查意见未限定具体天，逐天全量修复
                        java.util.Set<Integer> allDays = new java.util.TreeSet<>();  // 组装全部天号
                        for (int d = 1; d <= totalDays; d++) allDays.add(d);  // 1..totalDays
                        fixed = repairDaysInParallel(state, style, allDays, v.feedbackText()); // ★并行  // 并行修复所有天
                    } else {  // 否则（非骨架模式）
                        // 非骨架模式：本就是整单生成的，保持原路径
                        fixed = repairPlanByAgent(state, style, v.feedbackText(), current);  // repairPlanByAgent 整单重编
                    }  // if 结束
                }  // if 结束
            } catch (Exception e) {  // 修复过程异常
                log.warn("Reviewer 修正重编异常: {}", e.getMessage());  // 只 warn
                EventStore.add(sessionId, "审查", "修正重编异常，交付当前版本");  // 写事件：修正重编异常，交付当前版本
                ReviseSummary.finish(sessionId, "rework_failed");  // 收尾 rework_failed
                break;  // 跳出循环
            }  // catch 结束
            if (fixed == null) {  // 没修出结果（fixed 为 null）
                // ★2026-09-11 致命问题不静默（实测依据）：如果"修复失败 + 当前版本结构本就不达标"，
                //   这不是"小瑕疵保留上一版"，而是**交付一份错行程**（实测：3天请求交付2天、且全程打车无自驾），
                //   之前这种情况只写"保留上一版"，最终报"完成/无降级"，错误无人察觉。
                //   现在只要结构校验不通过就显式标"降级交付"，让 E2E 与用户都能看见。
                TripRequest reqC = state == null ? null : state.getRequest();  // reqC：需求
                int wantC = reqC == null ? 0 : reqC.getDays();  // wantC：期望天数
                String curText = state == null ? null : state.getPlanResult();  // curText：当前行程原文
                if (!planStructOk(curText, wantC)) {  // 结构校验不通过
                    EventStore.add(sessionId, "审查", "⚠️ 降级交付：修复未成功，且当前版本结构校验不通过（应 "  // 写事件：⚠️ 降级交付（应 N 天，实际区块不符）——此结果需重新生成，不可直接采用
                            + wantC + " 天，实际区块不符）—— 此结果需重新生成，不可直接采用");  // 续
                    log.warn("[review] 修复失败且结构校验不通过（应 {} 天），已标降级交付", wantC);  // warn 日志
                }  // if 结束
                EventStore.add(sessionId, "审查", "修正重编未产出更优结果，保留上一版");  // 写事件：修正重编未产出更优结果，保留上一版
                ReviseSummary.finish(sessionId, "rework_no_improvement");  // 收尾 rework_no_improvement
                break;  // 跳出循环
            }  // if 结束
            // ★区块回填（2026-09-06）：整单/天级重编只修审查指出问题，transport/stay/tips 与问题无关、
            //   上一版有效——模型重编若把这类区块写空/漏写（如 tips 空 → 前端"实用贴士"空卡），
            //   用上一版对应区块回填，防交付外观降级
            backfillSideSections(fixed, current);  // backfillSideSections：回填附属区块
            fixed.setMarkdown(renderMarkdown(fixed));  // 重渲染 markdown
            current = fixed;  // current 换成修复后的版本
        }  // for 结束
        return current;  // 返回当前最好的一版
    }  // 方法结束

    /**
     * 作用：回填附加区块（预算/提示/交通等）——修复只改了每日行程时，把原行程的附属区块补回来
     * 参数：fresh 修复后的行程；prev 原行程
     * 返回值：无
     * 异常：无
     */
    private void backfillSideSections(TripPlanData fresh, TripPlanData prev) {  // backfillSideSections 签名
        if (fresh == null || prev == null) return;  // 任一为空就不处理
        for (String typ : new String[]{"transport", "stay", "tips"}) {  // 只处理这三类附属区块
            Section fs = findSectionByType(fresh, typ);  // 取新版里的该区块
            Section ps = findSectionByType(prev, typ);  // 取旧版里的该区块
            if (fs == null || ps == null) continue;  // 任一侧没有就跳过
            boolean freshEmpty = fs.getItems() == null || fs.getItems().isEmpty();  // 新版是否为空（没条目）
            boolean prevFull = ps.getItems() != null && !ps.getItems().isEmpty();  // 旧版是否有内容
            if (freshEmpty && prevFull) {  // 新版空、旧版有
                fs.setItems(ps.getItems());  // 直接沿用旧版条目
                log.info("重编区块回填 {}：{} 条（新版空→沿用上一版）", typ, ps.getItems().size());  // 日志：回填了哪类、多少条
            }  // if 结束
        }  // for 结束
    }  // 方法结束
    /**
     * 作用：按区块类型查找第一个匹配区块
     * 参数：plan 行程；type 区块类型（timeline/stay/tip/budget 等）
     * 返回值：匹配的区块；不存在返回 null
     * 异常：无
     */

    private static Section findSectionByType(TripPlanData plan, String type) {  // findSectionByType 签名
        if (plan == null || plan.getSections() == null) return null;  // plan 或 sections 为空返回 null
        for (Section s : plan.getSections()) {  // 遍历区块
            if (s != null && type.equals(s.getType())) return s;  // type 相等就返回
        }  // for 结束
        return null;  // 没找到返回 null
    }  // 方法结束

    /**
     * 作用：判断评审问题是否属于"路线数据不足"，需要重新取路线
     * 参数：v 评审结论
     * 返回值：true=需要补路线
     * 异常：无
     */
    private boolean needRouteRefetch(ReviewVerdict v) {  // needRouteRefetch 签名
        if (v == null || v.getIssues() == null || v.getIssues().isEmpty()) return false;  // 没有问题清单直接 false
        for (ReviewVerdict.Issue is : v.getIssues()) {  // 遍历每个问题
            if (is == null) continue;  // 跳过 null
            String dom = is.domain == null ? "" : is.domain.trim().toLowerCase();  // domain 归一（空串/去空格/小写）
            String typ = is.type == null ? "" : is.type.trim().toLowerCase();  // type 归一
            String reason = is.reason == null ? "" : is.reason;  // reason 原文
            if ("transport".equals(dom) || "route".equals(typ) || "route".equals(dom)) return true;  // domain=transport 或 type=route 或 domain=route → 需要补路线
            // reason 明确提及衔接/交通/绕路时也触发（审查器未标 domain 的兜底）
            if (reason.contains("衔接") || reason.contains("交通") || reason.contains("绕路")  // reason 含“衔接/交通/绕路”
                    || reason.contains("步行") || reason.contains("公交")) return true;  // 或含“步行/公交” → 需要补
        }  // for 结束
        return false;  // 都不命中返回 false
    }  // 方法结束

    /**
     * 作用：按评审问题中的地点对重新调用路线子 Agent，补算缺失里程并合并进候选数据
     * 参数：state 任务状态；plan 当前行程
     * 返回值：无
     * 异常：无（补取失败不阻断，仅记录事件）
     */
    private void refetchRouteForIssues(TravelState state, TripPlanData plan) {  // refetchRouteForIssues 签名
        try {  // try
            TripRequest req = state.getRequest();  // 取需求
            if (req == null || plan == null) return;  // 任一为空直接返回
            boolean crossCity = req.getOrigin() != null && !req.getOrigin().isBlank()  // 判断是否跨城（出发地≠目的地）
                    && req.getDestination() != null && !req.getDestination().isBlank()  // 续行
                    && !req.getOrigin().trim().equals(req.getDestination().trim());  // 续行
            if (crossCity) return; // 跨城：路线决定途经城市，聚合若改城市属结构性变化，走缓存键失效重建，不在此补查  // 跨城直接返回——跨城路线决定途经城市，改城市属结构性变化，应该走缓存键失效重建，不在这里补查
            String sights = sightsFromPlan(plan);  // 从结构化行程里提取景点清单
            if (sights.isBlank()) return; // 结构化行程无景点 → 无清单可查  // 没景点 → 无清单可查，直接返回
            EventStore.add(sessionId, "路线", "审查发现交通/衔接问题，按当前方案景点补查真实路线...");  // 写事件：审查发现交通/衔接问题，按当前方案景点补查真实路线
            String freshRoute = await(PARALLEL_POOL.submit(() ->  // 起后台任务调路线 Agent（routeTask 带景点清单），
                    remoteTool.callRouteMakingAgent(routeTask(req, sights))), "路线Agent·审查补查");  // 续：await 等待，标签“路线Agent·审查补查”
            if (freshRoute != null && !freshRoute.isBlank()) {  // 拿到非空新路线
                state.setRouteResult(freshRoute); // 回填：后续行程Agent重编用新衔接数据  // 回填 state.routeResult：后续行程 Agent 重编用新衔接数据
                state.addTrace("路线", "审查补查: 已按当前方案重查衔接");  // 记轨迹：审查补查已完成
                EventStore.add(sessionId, "路线", "路线补查完成(衔接数据已更新)");  // 写事件：路线补查完成（衔接数据已更新）
            } else {  // 没拿到
                EventStore.add(sessionId, "路线", "路线补查失败(沿用原衔接数据)");  // 写事件：路线补查失败（沿用原衔接数据）
            }  // if 结束
        } catch (Exception e) {  // 异常
            log.warn("审查补查路线异常(忽略，沿用原衔接): {}", e.getMessage());  // 只 warn：沿用原衔接数据，不影响交付
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：某天条目过少时，按该天所在区域重新取三域候选并合并
     * 参数：state 任务状态；plan 当前行程；vr 校验报告
     * 返回值：无
     * 异常：无（补取失败不阻断）
     */
    private void refetchCandidatesForThinDay(TravelState state, TripPlanData plan, dto.ValidationReport vr) {  // refetchCandidatesForThinDay 签名
        try {  // try
            if (state == null || plan == null || vr == null || vr.getIssues() == null) return;  // 任一为空直接返回
            int thinDay = -1;  // thinDay：要补查的那一天，-1 表示还没有
            for (dto.ValidationReport.Issue is : vr.getIssues()) {  // 遍历校验问题
                String r = is == null || is.getReason() == null ? "" : is.getReason();  // 取 reason（null 归一空串）
                if (r.contains("内容过少") || r.contains("游玩时长")) {  // 是“内容过少”或“游玩时长不足”
                    int d = parseDayNo(is.getTarget());  // 从 target 文本解析天号
                    if (d > 0) { thinDay = d; break; }  // 解析到有效天号就记下并跳出
                }  // if 结束
            }  // for 结束
            if (thinDay < 1) return;  // 没定位到天直接返回
            String region = regionOfDay(plan, thinDay);  // 取该天所在区域名
            if (region.isBlank()) return;  // 区域为空（拿不到地名）就返回
            TripRequest req = state.getRequest();  // 取需求
            EventStore.add(sessionId, "候选", "第" + thinDay + "天内容不足，定向补查「" + region + "」景点候选...");  // 写事件：第 N 天内容不足，定向补查「区域」景点候选
            java.util.Map<String, Object> extra = queryCandidatesOnce(req, region,  // 只查该区域的三域候选（attraction 域）
                    req == null ? "" : (req.getTripPref() == null ? "" : req.getTripPref()), "attraction");  // 参数：偏好（tripPref，null 归一空串）、域=attraction
            if (extra == null || extra.isEmpty() || utils.CandidateUtil.items(extra, "attraction").isEmpty()) {  // 没查到新候选
                EventStore.add(sessionId, "候选", "第" + thinDay + "天补查无新景点候选（该路段确实稀疏）");  // 写事件：该路段确实稀疏，补不到
                return;  // 直接返回
            }  // if 结束
            java.util.Map<String, Object> base = utils.CandidateUtil.parse(state.getCandidatesJson());  // 解析现有候选
            int before = utils.CandidateUtil.items(base, "attraction").size();  // 补查前景点数
            java.util.Map<String, Object> merged = mergeCandidates(state.getCandidatesJson(), extra);  // 把补查结果合并进现有候选
            state.setCandidatesJson(utils.JsonUtils.toJson(merged));  // 写回 state
            int after = utils.CandidateUtil.items(merged, "attraction").size();  // 合并后景点数
            EventStore.add(sessionId, "候选", "第" + thinDay + "天路段候选补查完成（景点 " + before + "→" + after + " 个）");  // 写事件：补查完成（景点 before→after 个）
        } catch (Exception e) {  // 异常
            log.warn("分路段候选补查异常(忽略): {}", e.getMessage());  // 只 warn：补查失败不影响交付
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：从问题目标文本（如"第2天"）中解析天号
     * 参数：target 目标文本
     * 返回值：天号；解析不到返回 0
     * 异常：无
     */
    private int parseDayNo(String target) {  // parseDayNo 签名
        if (target == null) return -1;  // null 返回 -1
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(target);  // 用正则找第一个数字
        return m.find() ? Integer.parseInt(m.group(1)) : -1;  // 找到就 parseInt，否则 -1
    }  // 方法结束

    /**
     * 作用：取某天行程所在区域（用于有针对性地补候选）
     * 参数：plan 行程；day 天号
     * 返回值：区域名；取不到返回空串
     * 异常：无
     */
    private String regionOfDay(TripPlanData plan, int day) {  // regionOfDay 签名
        if (plan == null || plan.getSections() == null) return "";  // plan 或 sections 为空返回空串
        java.util.LinkedHashSet<String> places = new java.util.LinkedHashSet<>();  // 用一个有序 Set 收集该天的地点（去重且保序）
        for (dto.Section sec : plan.getSections()) {  // 遍历区块
            if (sec == null || sec.getItems() == null) continue;  // 跳过无效区块
            String title = sec.getTitle() == null ? "" : sec.getTitle();  // 取标题
            if (!title.contains("第" + day + "天") && !title.contains("Day" + day) && !title.contains("day" + day)) continue;  // 标题不含“第N天/DayN/dayN”就跳过（不是这天的区块）
            for (dto.SectionItem it : sec.getItems()) {  // 遍历该区块的条目
                if (it == null || it.getPlace() == null || it.getPlace().isBlank()) continue;  // 跳过无效条目（null/地点为空）
                places.add(it.getPlace().trim());  // 把地点加进集合（自动去重）
                if (places.size() >= 4) break;  // 最多取 4 个地点（够定区域即可）
            }  // for 结束
            if (!places.isEmpty()) break;  // 这一天已经取到地点，后面的区块不用再看
        }  // for 结束
        return String.join(",", places);  // 拼成逗号分隔的区域描述
    }  // 方法结束

    /**
     * 作用：合并新旧候选 JSON（按域与名称去重，新数据优先）
     * 参数：existingJson 已有候选 JSON；extra 新增候选数据
     * 返回值：合并后的候选 Map
     * 异常：无
     */
    @SuppressWarnings("unchecked")  // 抑制泛型转换警告
    private java.util.Map<String, Object> mergeCandidates(String existingJson, java.util.Map<String, Object> extra) {  // mergeCandidates 签名
        java.util.Map<String, Object> base = utils.CandidateUtil.parse(existingJson);  // 先解析已有候选
        if (base == null) base = new java.util.LinkedHashMap<>();  // 解析不出来就新建空 Map
        for (String d : utils.CandidateUtil.DOMAINS) {  // 遍历三个域（景点/酒店/餐饮）
            java.util.List<java.util.Map<String, Object>> extraItems = utils.CandidateUtil.items(extra, d);  // 取新增数据里该域的条目
            if (extraItems.isEmpty()) continue;  // 该域没有新条目就跳过
            java.util.Map<String, Object> node = utils.CandidateUtil.domain(base, d);  // 取已有候选里该域的节点
            if (node == null) {  // 节点不存在
                node = new java.util.LinkedHashMap<>();  // 新建节点
                base.put(d, node);  // 挂到 base 上
            }  // if 结束
            java.util.List<Object> list;  // 准备 list 变量
            if (node.get("candidates") instanceof java.util.List) {  // 节点里已有 candidates 列表
                list = (java.util.List<Object>) node.get("candidates");  // 直接用现有列表
            } else {  // 否则
                list = new java.util.ArrayList<>();  // 新建列表
                node.put("candidates", list);  // 挂到节点上
            }  // if 结束
            java.util.Set<String> existing = new java.util.LinkedHashSet<>();  // 收集已有条目名（去重用）
            for (Object o : list) {  // 遍历现有条目
                if (o instanceof java.util.Map) {  // 是 Map
                    Object n = ((java.util.Map<String, Object>) o).get("name");  // 取 name
                    if (n != null) existing.add(n.toString());  // 非空就记下
                }  // if 结束
            }  // for 结束
            for (java.util.Map<String, Object> it : extraItems) {  // 遍历新增条目
                Object n = it.get("name");  // 取 name
                if (n != null && existing.add(n.toString())) list.add(it);  // 名字是新的（add 返回 true）才加入列表
            }  // for 结束
        }  // 域循环结束
        return base;  // 返回合并后的 Map
    }  // 方法结束

    /**
     * 作用：从当前行程中抽出景点清单（供补算路线使用）
     * 参数：plan 行程
     * 返回值：景点清单文本
     * 异常：无
     */
    private String sightsFromPlan(TripPlanData plan) {  // sightsFromPlan 签名
        if (plan == null || plan.getSections() == null) return "";  // plan 为空返回空串
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();  // seen：去重保序集合
        for (dto.Section sec : plan.getSections()) {  // 遍历区块
            if (sec == null || sec.getItems() == null) continue;  // 跳过无效区块
            for (dto.SectionItem it : sec.getItems()) {  // 遍历条目
                if (it == null || it.getPlace() == null || it.getPlace().isBlank()) continue;  // 跳过无效条目
                seen.add(it.getPlace().trim());  // 加入地点
                if (seen.size() >= 5) break;  // 最多 5 个（路线查询不宜太长）
            }  // for 结束
            if (seen.size() >= 5) break;  // 已够 5 个就跳出外层
        }  // for 结束
        return String.join(",", seen);  // 拼成逗号分隔清单
    }  // 方法结束

    /**
     * 作用：多方案编排：同一需求产出多套风格差异化行程，逐套评分后可择优
     * 参数：userPrompt 用户原始需求文本
     * 返回值：PlanSet 多方案集合
     * 异常：CancelledException 用户中断时抛出
     */
    public PlanSet runMultiScheme(String userPrompt) {  // runMultiScheme 签名：/app 的 multiScheme=true 走这里
        syncPoolSize(); // ★2026-09-11 并发对齐：多方案入口同样按闸调整并行池（3套方案并行需要更多线程）  // syncPoolSize：多方案入口同样按闸调整并行池（3 套并行需要更多线程）
        // ★多方案：跳过默认定稿（withPlanAgent=false），3套风格在 aggregateMulti 内并行定稿，避免白跑一次
        TravelState state = resolveAndFetch(userPrompt, null, false);  // resolveAndFetch：只取候选与路线，不生成行程
        String missingInput = missingInputs(state.getRouteResult(), ""); // 多方案仅路线前置必须齐全  // missingInputs：多方案只要求路线前置齐全
        if (!missingInput.isBlank()) {  // 有缺失
            EventStore.add(sessionId, "定稿", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");  // 写事件：⚠️ 缺失内容，结果可能不完整
        }  // if 结束
        EventStore.add(sessionId, "思考", "正在基于候选与衔接数据规划多套差异化方案...");  // 写事件：正在规划多套差异化方案
        EventStore.add(sessionId, "定稿", "正在生成 3 套差异化方案（经典主线/深度环线/轻松向）...");  // 写事件：正在生成 3 套（经典主线/深度环线/轻松向）
        PlanSet set = aggregateMulti(state);  // aggregateMulti：并行定稿 3 套，返回 PlanSet
        // 每套方案补降级 markdown（前端打印/导出用）+ 逐套确定性校验与评分，择优 recommendedIndex
        if (set != null) {  // set 非空
            for (int i = 0; i < set.getPlans().size(); i++) {  // 逐套遍历
                TripPlanData p = set.getPlans().get(i);  // 取一套
                if (p != null && p.getMarkdown().isBlank()) {  // markdown 为空
                    p.setMarkdown(renderMarkdown(p));  // 渲染 markdown（前端打印/导出用）
                }  // if 结束
                String slogan = i < set.getSlogans().size() ? set.getSlogans().get(i) : ("方案" + (i + 1));  // 取该套的口号（越界用“方案N”兜底）
                dto.ValidationReport vr = validateAndReport(state, p, "「" + slogan + "」", false);  // 对该套跑校验（不评分，下面单独评分）
                try {  // try
                    dto.ScoreResult sr = utils.PlanScorer.score(state.getRequest(), p, vr,  // 算六维评分
                            state.getRouteResult() != null && !state.getRouteResult().isBlank(),  // 参数：路线是否有真实数据
                            state.getPlanResult() != null && !state.getPlanResult().isBlank());  // 参数：行程是否有真实数据
                    set.getScores().add(sr.getTotal());  // 把总分放进 set.scores（下标与 plans 对齐）
                    EventStore.add(sessionId, "评分", "「" + slogan + "」 " + sr.getSummary());  // 写评分事件
                } catch (Exception e) {  // 评分异常
                    set.getScores().add(null); // 评分异常不阻断，占位保持下标对齐  // 占位 null，保持下标对齐（不阻断）
                    log.warn("方案「{}」评分异常: {}", slogan, e.getMessage());  // warn 日志
                }  // catch 结束
            }  // for 结束
            int best = utils.PlanScorer.best(set.getScores());  // 找出最高分下标
            if (best >= 0) {  // 有结果
                set.setRecommendedIndex(best);  // 设置推荐下标
                String bestSlogan = best < set.getSlogans().size() ? set.getSlogans().get(best) : ("方案" + (best + 1));  // 取推荐方案口号
                EventStore.add(sessionId, "评分", "★ 推荐方案：" + bestSlogan);  // 写事件：★ 推荐方案
                // ★Reviewer 闭环只审推荐方案（省 2/3 审查开销）：REVISE 按域定向重跑（保持风格），修正后重算评分
                TripPlanData rec = set.getPlans().get(best);  // rec：推荐方案
                if (rec != null && rec.getSections() != null && !rec.getSections().isEmpty()) {  // 方案有效（有 sections）
                    EventStore.add(sessionId, "审查", "对推荐方案「" + bestSlogan + "」执行独立审查...");  // 写事件：对推荐方案执行独立审查
                    TripPlanData reviewed = reviewerLoop(state, rec, PLAN_STYLES[best][2]);  // reviewerLoop 审推荐方案，style 传该套风格
                    if (reviewed != rec) {  // 审查改动了行程
                        set.getPlans().set(best, reviewed);  // 替换 set 里的这一套
                        try {  // try
                            dto.ValidationReport vrf = utils.ConstraintEngine.validate(  // 重新校验
                                    state.getRequest(), reviewed, state.getCandidatesJson());  // 参数
                            dto.ScoreResult sr = utils.PlanScorer.score(state.getRequest(), reviewed, vrf,  // 重新评分
                                    state.getRouteResult() != null && !state.getRouteResult().isBlank(),  // 参数
                                    state.getPlanResult() != null && !state.getPlanResult().isBlank());  // 参数
                            set.getScores().set(best, sr.getTotal());  // 更新分数
                            EventStore.add(sessionId, "评分", "「" + bestSlogan + "」审查修正后 " + sr.getSummary());  // 写事件：审查修正后的评分
                        } catch (Exception ignored) {  // 异常忽略（分数保持原值）
                        }  // catch 结束
                    }  // if 结束
                }  // if 结束
            }  // if(best>=0) 结束
            // 每套方案存档候选与证据链（前端不渲染，增量修正/审计用）
            for (TripPlanData p : set.getPlans()) {  // 遍历方案
                archiveEvidence(state, p);  // 归档证据
            }  // for 结束
        }  // if(set!=null) 结束
        EventStore.add(sessionId, "完成", "多方案生成完成（" + (set == null ? 0 : set.getPlans().size()) + " 套）");  // 写事件：多方案生成完成（N 套）
        return set;  // 返回 PlanSet
    }  // 方法结束

    /**
     * 作用：解析需求并准备数据：需求归一化 → 缓存检查 → 三域候选（可选专家 Agent）→ 天气与路线骨架
     * 参数：userPrompt 用户原始需求文本；prefs 沉淀偏好；withPlanAgent 是否走行程专家 Agent 取天骨架
     * 返回值：TravelState（需求 + 候选 + 路线 + 骨架 + 缓存标记）
     * 异常：CancelledException 用户中断时抛出
     */
    private TravelState resolveAndFetch(String userPrompt, TripRequest.Preferences prefs, boolean withPlanAgent) {  // resolveAndFetch 签名：withPlanAgent 决定要不要调行程 Agent 定稿
        TravelState state = new TravelState();  // 新建 TravelState（状态中心）
        // ① 需求解析（单次结构化 LLM 调用）+ 纯代码规整（预算数值化/约束兜底）
        EventStore.add(sessionId, "解析", "正在解析旅行需求...");  // 写事件：正在解析旅行需求
        TripRequest req = parseRequest(userPrompt);  // parseRequest：自然语言 → TripRequest
        if (prefs != null && !prefs.isEmpty()) {  // 有个人中心偏好
            req.setPreferences(prefs);  // 并入需求
        }  // if 结束
        RequirementNormalizer.normalize(req); // ★LLM 漏解析预算/约束时正则兜底，保证状态完整  // RequirementNormalizer.normalize：LLM 漏解析的预算/约束用正则补齐
        state.setRequest(req);  // 需求放进状态中心
        String constraintSummary = req.getConstraints().isEmpty() ? ""  // constraintSummary：没有约束就是空串，
                : "，约束 " + req.getConstraints().size() + " 条" + (req.hasHardConstraints() ? "（含P0硬约束）" : "");  // 有则“，约束 N 条（含P0硬约束）”
        EventStore.add(sessionId, "解析", "需求：" + req.getOrigin() + " → " + req.getDestination()  // 写事件：需求摘要（出发地→目的地，
                + "，" + req.getDays() + " 天"  // 续：天数
                + (req.getBudgetTotal() > 0 ? "，预算 " + (long) req.getBudgetTotal() + " 元" : "")  // 续：有预算就带预算
                + constraintSummary  // 续：约束摘要
                + (req.getPreference().isBlank() ? "" : "（" + req.getPreference() + "）")  // 续：有偏好就带偏好
                + (req.travelTypeLabel().isBlank() ? "" : "，类型「" + req.travelTypeLabel() + "」")  // 续：有出行类型带类型
                + (req.getTravelers() > 0 ? "，" + req.getTravelers() + " 人"  // 续：有同行人数带人数
                        + (req.travelerTypeLabel().isBlank() ? "" : "（" + req.travelerTypeLabel() + "）") : "")  // 续：有人群类型带人群标签
                + buildPreferenceLabel(req));  // 续：拼上偏好标签
        state.addTrace("解析", req.getOrigin() + "→" + req.getDestination() + " " + req.getDays() + "天"  // 记轨迹：解析结果一行
                + (req.getBudgetTotal() > 0 ? " 预算" + (long) req.getBudgetTotal() : "")  // 续行：有预算加预算
                + (req.travelTypeLabel().isBlank() ? "" : " " + req.travelTypeLabel()));  // 续行：有出行类型加类型
        checkCancel();  // checkCancel：已请求中断就直接抛

        /* ★ 结果缓存（进程内）：key=目的地|天数|出发|沉淀偏好|本次偏好|需求指纹(预算/人数/类型/约束)。
           仅缓存专家产物，LLM 聚合仍每次执行——防串 + 偏好变化即时生效。 */  // 只缓存专家产物，LLM 聚合仍每次执行（防串 + 偏好变化即时生效）
        String storedPrefs = (req.getPreferences() != null && !req.getPreferences().isEmpty())  // storedPrefs：沉淀偏好转提示词文本（没有就是空串）
                ? req.getPreferences().toPromptText() : "";  // 续行
        String cacheFingerprint = req.cacheFingerprint();  // cacheFingerprint：需求指纹（预算/人数/类型/约束的哈希）
        utils.cache.PlanCache.Entry hit = utils.cache.PlanCache.get(  // 查缓存（六个维度组成键）
                req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs, req.getPreference(), cacheFingerprint);  // 续行
        if (hit != null && !cacheEntryUsable(hit, req)) {  // 命中但结构校验不通过
            // ★2026-09-11 缓存结构校验（实测依据）：缓存里存过"标称 3 天、实际只有 2 天区块"的旧结果。
            //   命中后审查员正确指出「每日行程区块为 2 天，与请求 3 天不符」「完全没有自驾安排」，
            //   但修复走整单重编失败 → "保留上一版" → **把错行程静默交付出去，还报完成/无降级**。
            //   所以采用前先校验结构与天数，不通过就当作未命中，直接走真实生成。
            EventStore.add(sessionId, "解析", "· 结果缓存结构校验不通过（天数/区块不符），改为重新生成 ·");  // 写事件：缓存结构校验不通过，改为重新生成
            log.warn("[cache] 命中但校验不通过（期望 {} 天，缓存文本 {} 字符），按未命中处理",  // warn 日志：期望天数与缓存文本长度
                    req.getDays(), hit.planResult == null ? 0 : hit.planResult.length());  // 续行
            hit = null;  // hit 置 null（按未命中处理）
        }  // if 结束
        if (hit != null) {  // 真正命中缓存
            EventStore.add(sessionId, "解析", "· 命中结果缓存，跳过专家调用 ·");  // 写事件：命中结果缓存，跳过专家调用
            EventStore.add(sessionId, "审查", "结果缓存命中，正在执行校验/审查闭环（专家生成已跳过）...");  // 写事件：缓存命中，正在执行校验/审查闭环
            state.setFromCache(true);  // 标记来自缓存
            state.setRouteResult(hit.routeResult);  // 复用路线结果
            state.setPlanResult(hit.planResult);  // 复用行程结果
            state.setCandidatesJson(hit.candidatesJson); // 候选随缓存复用（旧条目为空串则聚合走原文兜底）  // 复用候选 JSON（旧条目为空则后续走原文兜底）
            // ★2026-09-11 关键补充：还原"按天结构"。否则 dayPlanJson 为空 → skeletonMode=false
            //   → 审查发现问题后只能走"整单重编"（本项目判定必然失败的那条路）→ 修复失败 → 交付带缺陷结果。
            restoreDayPlanJsonFromText(state, req);  // restoreDayPlanJsonFromText：从行程文本还原按天 JSON
            return state;  // 直接返回状态（跳过所有专家调用）
        }  // if 结束

        // ② 偏好分流：LLM 把文本+个人中心偏好分为行程/路线/聚合三类
        boolean hasTextPref = req.getPreference() != null && !req.getPreference().isBlank();  // 有文本偏好
        boolean hasStoredPref = req.getPreferences() != null && !req.getPreferences().isEmpty();  // 有沉淀偏好
        if (hasTextPref || hasStoredPref) {  // 任一有
            classifyPreference(req);  // classifyPreference：做偏好分类
        }  // if 结束
        // ③ 编排分支：跨城=先路线(途经点)再行程；市内=先行程(景点)再路线
        //    跨 Agent 提取结果（途经点/景点清单）统一写入 state，禁止散装传参
        boolean crossCity = req.getOrigin() != null && !req.getOrigin().isBlank()  // crossCity：出发地和目的地都有
                && req.getDestination() != null && !req.getDestination().isBlank()  // 续：
                && !req.getOrigin().trim().equals(req.getDestination().trim());  // 续：且不是同一个地方
        boolean cityTour = !crossCity;  // 单城 / 起点=终点 / 无起点 → 市内（环线游无起点也走此分支）  // cityTour：其余都算市内（单城/首尾同地/无出发地）
        if (crossCity) {  // 跨城分支
            EventStore.add(sessionId, "路线", "跨城"  // 写事件：跨城 + 出行类型，
                    + (req.travelTypeLabel().isBlank() ? "" : "「" + req.travelTypeLabel() + "」")  // 续：
                    + "，候选先行：先沿途查候选定核心区域，再按核心区域规划路线...");  // 续：候选先行，先沿途查候选定核心区域，再按核心区域规划路线
            // ① ★候选先行：沿途查候选（出发地→目的地，域专家 query_trip 沿途），不再依赖路线Agent先定途经城市
            String originDest = req.getOrigin().trim() + "," + req.getDestination().trim();  // originDest：出发地,目的地
            fetchCandidatesPreflight(state, originDest, req);  // fetchCandidatesPreflight：三域并行查沿途候选
            checkCancel();  // checkCancel
            // ② 候选聚类定核心景点区域（沿途哪值得停由候选数据决定，不是LLM拍脑袋）
            java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());  // 解析候选
            // ★2026-09-12 不再按"景点个数"限死上限（原写死 8 → 长线只能得 8 簇、切不出 14 天）。
            //   代表景点清单纯粹是"给路线Agent查衔接用的骨架"，天数分配由 cutIntoDays 按【游玩时长】决定，
            //   所以这里放开到候选全量（上限取候选个数，不低于 8 保证短行程行为不变）。
            int repCap = cands == null ? 8 : Math.max(8, cands.size() * 4);  // repCap = 候选数×4，最少 8
            String clusterSights = cands == null ? "" : utils.CandidateUtil.clusterRepSights(cands, repCap);  // clusterRepSights：取每簇代表景点
            state.setSights(clusterSights);  // 写入 state.sights
            state.addTrace("路线", "候选核心区域: " + (clusterSights.isBlank() ? "未提取到（候选缺失/无坐标）" : clusterSights));  // 记轨迹：候选核心区域
            // ③ 路线Agent 基于候选核心区域规划路线（途经城市由景点分布决定）
            String routeResult = await(PARALLEL_POOL.submit(() ->  // 起后台任务调路线 Agent（routeTask 带簇代表景点），
                    remoteTool.callRouteMakingAgent(routeTask(req, clusterSights))), "路线Agent");  // 续：await 等待
            state.setRouteResult(routeResult);  // 写入 state.routeResult
            checkCancel();  // checkCancel
            // ★强制校验前移：往返里程÷300 > 天数 → 尝试重调缩短（最多2轮）
            //  ★效果检测：重调后里程没降（降幅<5%）说明物理上缩不动（目的地往返里程由主干路线决定，
            //   砍停靠点省不了），立即停止——避免"越缩越多"（实测路线Agent被逼会排更多途经点，里程反升）。
            int routeKm = extractTotalDistance(routeResult);  // extractTotalDistance：从路线文本提总里程
            String bestResult = routeResult;  // bestResult：目前最好的一版
            int bestKm = routeKm;  // bestKm：对应里程
            for (int shrink = 0; shrink < 2 && routeKm > 0 && (int) Math.ceil(routeKm / 300.0) > req.getDays(); shrink++) {  // 最多 2 轮，且仅当里程/300 > 天数时进入
                final int needDays = (int) Math.ceil(routeKm / 300.0);  // needDays：按 300km/天算需要几天
                final int curKm = routeKm;  // curKm：当前里程
                final int userDays = req.getDays();  // userDays：用户天数
                EventStore.add(sessionId, "路线", "往返里程 " + curKm + "km 需约 " + needDays  // 写事件：往返里程需 N 天 > 用户 M 天，尝试缩短（第 k 次）
                        + " 天 > 用户 " + userDays + " 天，尝试缩短路线（第 " + (shrink + 1) + " 次）...");  // 续行
                String shorter = await(PARALLEL_POOL.submit(() ->  // 再调路线 Agent，任务里明确要求：
                        remoteTool.callRouteMakingAgent(routeTask(req, clusterSights,  // 续：能砍就砍非核心中转点/改走紧的小环线，
                                "往返里程 " + curKm + "km 需 " + needDays + " 天，用户只要 " + userDays  // 续：若已是最近路线就保持不动、不要增加途经点，并说明缩不动的原因，
                                        + " 天。①若物理上可行，砍非核心中转停靠点/改走更紧的小环线使往返里程下降；"  // 续：
                                        + "②若这已是最近路线（目的地往返里程由主干路线决定、缩不动），"  // 续：用户明确要去的景点必须保留
                                        + "保持路线不变、绝对不要增加途经点，并如实说明缩不动的原因。"  // 续行
                                        + "用户明确要去的景点（如稻城亚丁）必须保留"))), "路线Agent·缩短");  // 续：await 标签“路线Agent·缩短”
                if (shorter == null || shorter.isBlank()) break;  // 没拿到结果就停（不再浪费下一轮）
                int newKm = extractTotalDistance(shorter);  // 算新里程
                // 效果检测：里程没降（降幅 < 5%）→ 判定物理上缩不动，停止重调（不再浪费下一轮）
                if (newKm <= 0 || newKm >= (int) (curKm * 0.95)) {  // 新里程无效或降幅不足 5%
                    EventStore.add(sessionId, "路线", "重调后里程 " + (newKm <= 0 ? "未知" : newKm + "km")  // 写事件：重调后里程未下降，判定缩不动，停止重调
                            + " 未下降（降幅 <5%）——判定缩不动，停止重调");  // 续行
                    break;  // 跳出
                }  // if 结束
                if (newKm < bestKm) { bestKm = newKm; bestResult = shorter; }  // 新里程更小
                routeResult = shorter;  // 用这次的路线
                state.setRouteResult(routeResult);  // 写回 state
                routeKm = newKm;  // 更新当前里程
            }  // if 结束
            // 保留里程最小的一版（防止最后一版反而更大）
            if (bestKm > 0 && bestResult != routeResult && bestKm < extractTotalDistance(routeResult)) {  // 最好那版不是当前版且更小
                routeResult = bestResult;  // 回退到最好那版
                state.setRouteResult(routeResult);  // 写回 state
                routeKm = bestKm;  // 更新里程
                EventStore.add(sessionId, "路线", "保留里程最小版本 " + bestKm + "km（放弃更大的重调版）");  // 写事件：保留里程最小版本
            }  // if 结束
            checkCancel();  // checkCancel
            String waypoints = extractWaypoints(routeResult);  // extractWaypoints：提途经城市
            state.setWaypoints(waypoints);  // 写入 state
            suggestDaysByDistance(routeResult, req);  // suggestDaysByDistance：按里程给天数建议（只提示，不改用户天数）
            // ★可行性预检（不中止）：往返里程÷日均 > 用户天数 → 标记 infeasibleDays，供兜底/替代路线用
            int feasKm = extractTotalDistance(routeResult);  // 再取一次里程
            if (feasKm > 0) {  // 里程有效
                String ftype = req.getTravelType() == null ? "" : req.getTravelType().trim().toUpperCase();  // 出行类型大写
                int fDaily = "CROSS_CITY".equals(ftype) ? 500 : 300;  // 日均里程：跨城 500km/天，其它 300km/天
                int needDays = (int) Math.ceil(feasKm / (double) fDaily);  // 需要几天
                if (needDays > req.getDays()) state.setInfeasibleDays(needDays);  // 超过用户天数就记下 needDays
            }  // if 结束
            state.addTrace("路线", "途经城市: " + (waypoints.isBlank() ? "未提取到" : waypoints));  // 记轨迹：途经城市
            String routeCandidates = extractRouteCandidates(routeResult); // 多条候选路线概要（供行程Agent择优）  // extractRouteCandidates：抽多条候选路线概要（供行程 Agent 择优）
            if (withPlanAgent) {  // 需要行程 Agent 定稿
                // ★本轮改动：跨城分支也接上天骨架（原来这里只有"整单一次定稿"）。
                //   实测依据（川西 7 天）：整单任务长度 6079 字符，子 Agent 单次要跑 250 秒以上，
                //   主管 45 秒阻塞窗口必然拿不到 → 轮询回捞在窗口内也捞不到 → 重发 → 总耗时 439 秒且降级交付。
                //   骨架把任务拆成"一天一个短任务"（实测单天约 1400 字符、100~200 秒），既有并行也更容易成功。
                //   骨架不可用时（候选不足/切天失败/天数不符）自动回退整单，行为与改动前完全一致。
                EventStore.add(sessionId, "行程", "跨城行程Agent 基于候选路线与候选定稿（优先天骨架）...");  // 写事件：跨城定稿开始（骨架优先）
                String planResult = DAY_SKELETON ? planByDaySkeleton(state, req) : null;  // 先试天骨架：候选成簇 → 地理链 → 按游玩时长切天 → 逐日并行（DAY_SKELETON=false 时跳过，直接整单）
                if (planResult == null || planResult.isBlank()) {  // 骨架不可用
                    EventStore.add(sessionId, "定稿", "跨城整单生成（天骨架不可用或回退，行程Agent一次定稿）...");  // 写事件：告知回退整单
                    planResult = await(PARALLEL_POOL.submit(() ->  // 回退：整单一次定稿（与改动前一致）
                            remoteTool.callTripPlannerAgent(finalPlanTask(state, null, null, null))), "行程Agent");  // 续：await 等待
                }  // if 结束
                state.setPlanResult(planResult);  // 写入 state.planResult
                // ④ 收割兜底：行程Agent若因候选不足补查过（回复带新候选块）→ 并入状态；正常情况候选已前置注入
                harvestCandidates(state);  // harvestCandidates：收割候选块
                checkCancel();  // checkCancel
                // ★失败不缓存：行程Agent未返回(空)时写缓存会污染后续命中(拿到空planResult直接降级)——宁可下次重跑
                if (state.getPlanResult() != null && !state.getPlanResult().isBlank()) {  // 结果非空才
                    utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,  // 写缓存（路线 + 行程 + 候选 + 指纹）
                            req.getPreference(), cacheFingerprint, state.getRouteResult(), state.getPlanResult(), state.getCandidatesJson());  // 续行
                }  // if 结束
            }  // if withPlanAgent 结束
            return state;  // 返回 state（跨城分支）
        }  // 跨城分支结束
        EventStore.add(sessionId, "行程", "市内"  // 写事件：市内游（带出行类型），
                + (req.travelTypeLabel().isBlank() ? "游" : "「" + req.travelTypeLabel() + "」")  // 续：
                + "，先并行查询三域候选...");  // 续：先并行查三域候选
        // ① ★候选前置（删聚合配套）：域专家三线程并行查候选（无途经城市），成簇后路线与行程都有全量依据
        fetchCandidatesPreflight(state, "", req);  // fetchCandidatesPreflight：查三域候选（不带城市对）
        checkCancel();  // checkCancel
        // ② 路线按簇查衔接：从候选提取"每簇代表景点"清单（坐标5km成簇，代码），路线Agent只查簇间真实衔接
        java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());  // 解析候选
        String clusterSights = cands == null ? ""  // 取簇代表景点（上限同跨城）
                : utils.CandidateUtil.clusterRepSights(cands, Math.max(8, cands.size() * 4));  // 续行
        state.setSights(clusterSights);  // 写入 state.sights
        state.addTrace("行程", "簇代表景点: " + (clusterSights.isBlank() ? "未提取到（候选缺失/无坐标）" : clusterSights));  // 记轨迹：簇代表景点
        EventStore.add(sessionId, "候选", clusterSights.isBlank()  // 写事件：候选情况——
                ? "候选缺失或无坐标景点，路线按无清单模式自拟"  // 没有簇代表景点就说“候选缺失或无坐标，路线按无清单模式自拟”
                : "路线按簇代表景点查衔接：" + clusterSights);  // 否则列出“路线按簇代表景点查衔接：……”
        String routeResult2 = await(PARALLEL_POOL.submit(() ->  // 起后台任务调路线 Agent，
                remoteTool.callRouteMakingAgent(routeTask(req, clusterSights))), "路线Agent");  // 续：await 等待
        state.setRouteResult(routeResult2);  // 写入 state.routeResult
        checkCancel();  // checkCancel
        if (withPlanAgent) {  // 需要行程 Agent 定稿
            // ③ ★天骨架并行定稿（2026-09-06）：候选簇→地理链→按天切段→逐日短任务并行→拼装整单；
            //    失败（骨架不可用/某天失败）自动回退原整单 finalPlanTask
            String planText = DAY_SKELETON ? planByDaySkeleton(state, req) : null;  // planByDaySkeleton：走天骨架（DAY_SKELETON=false 时跳过，整单生成）
            if (planText == null || planText.isBlank()) {  // 骨架返回空
                EventStore.add(sessionId, "定稿", "整单生成（天骨架不可用或回退，行程Agent一次定稿）...");  // 写事件：整单生成（天骨架不可用或回退）
                planText = await(PARALLEL_POOL.submit(() ->  // 回退：调行程 Agent 整单定稿，
                        remoteTool.callTripPlannerAgent(finalPlanTask(state, null, null, null))), "行程Agent");  // 续：await 等待
            }  // if 结束
            state.setPlanResult(planText);  // 写入 state.planResult
            harvestCandidates(state);  // harvestCandidates：收割候选
            checkCancel();  // checkCancel
            // ★失败不缓存：行程Agent未返回(空)时写缓存会污染后续命中(拿到空planResult直接降级)——宁可下次重跑
            if (state.getPlanResult() != null && !state.getPlanResult().isBlank()) {  // 结果非空才
                utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,  // 写缓存
                        req.getPreference(), cacheFingerprint, state.getRouteResult(), state.getPlanResult(), state.getCandidatesJson());  // 续行
            }  // if 结束
        }  // if withPlanAgent 结束
        return state;  // 返回 state（市内分支）
    }  // 方法结束

    /**
     * 作用：收集并规范化三域候选：优先用候选工具/域 Agent 的产出，缺失域补取并标注降级
     * 参数：state 任务状态
     * 返回值：无
     * 异常：无（单域失败只降级该域）
     */
    private void harvestCandidates(TravelState state) {  // harvestCandidates 签名
        try {  // try
            String block = utils.CandidateUtil.extractBlock(state.getPlanResult());  // 从行程回复里抽【三域候选】块
            boolean fetched = false;  // fetched：是否由代码回取
            if (block.isBlank()) {  // 回复里没有块
                block = fetchTripCandidateBlock(); // ★观测代码化：回复无块 → 代码回取子Agent存档块  // 直接打子 Agent 的 /candidate-block 取存档块
                fetched = !block.isBlank();  // 取到就标记“代码回取”
            }  // if 结束
            if (!block.isBlank()) {  // 块非空
                java.util.Map<String, Object> cands = utils.CandidateUtil.parse(block);  // 解析候选 JSON
                if (cands != null && !cands.isEmpty()) {  // 解析成功且非空
                    state.setCandidatesJson(utils.JsonUtils.toJson(cands));  // 写入 state.candidatesJson
                    state.setPlanResult(utils.CandidateUtil.stripBlock(state.getPlanResult()));  // 从行程回复里剥掉这个块（防重复喂模型）
                    String summary = utils.CandidateUtil.evidenceSummary(cands);  // 生成证据摘要
                    String cluster = utils.CandidateUtil.clusterHintSummary(cands); // ★聚类生效可观测（管理端事件流）  // 生成聚类提示摘要（聚类生效可观测）
                    EventStore.add(sessionId, "候选", "三域候选已入状态中心"  // 写事件：三域候选已入状态中心
                            + (fetched ? "（候选块由代码回取，行程Agent回复未透传）" : "")  // 续：注明是否代码回取
                            + (summary.isBlank() ? "" : "：" + summary)  // 续：有摘要就带摘要
                            + (cluster.isBlank() ? "" : "｜" + cluster));  // 续：有聚类就带聚类
                    EventStore.putExtra(sessionId, "clusterStats", utils.CandidateUtil.clusterStats(cands)); // ★任务3：聚类效果结构化统计  // 聚类结构化统计塞进事件附加信息（管理端看聚类效果）
                    state.addTrace("候选", "三域候选(结构化,每域随天数)"  // 记轨迹：三域候选（结构化，每域随天数）
                            + (fetched ? "·代码回取" : ""));  // 续：代码回取时标注
                }  // if 结束
            }  // if 结束
        } catch (Exception e) {  // 异常
            log.warn("候选收割异常(忽略，走原文聚合): {}", e.getMessage());  // 只 warn：候选收割失败走原文聚合老路
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：调三域候选工具一次性取回候选数据块（失败返回空串）
     * 参数：无
     * 返回值：候选 JSON 文本；失败返回空串
     * 异常：无
     */
    private String fetchTripCandidateBlock() {  // fetchTripCandidateBlock 签名
        try {  // try
            String qs = "sessionId=" + java.net.URLEncoder.encode(sessionId, "UTF-8");  // 拼查询串：sessionId 编码
            java.net.URL u = new java.net.URL(utils.AgentEndpoints.tripAgent() + "/candidate-block?" + qs);  // 拼 URL：tripAgent 地址 + /candidate-block
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();  // 打开连接
            c.setConnectTimeout(3000);  // 连接超时 3 秒（本地进程间调用）
            c.setReadTimeout(3000);  // 读超时 3 秒
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应体
            java.util.Map<String, Object> root = utils.JsonUtils.parse(b, java.util.Map.class);  // 解析成 Map
            if (root == null) return "";  // 解析失败返回空串
            Object blk = root.get("block");  // 取 block 字段
            return blk instanceof String s ? s : "";  // 是字符串就返回，否则空串
        } catch (Exception e) {  // 异常
            log.debug("候选块回取失败(忽略，走原文聚合): {}", e.getMessage());  // debug 日志：候选块回取失败（忽略，走原文聚合）
            return "";  // 返回空串
        }  // catch 结束
    }  // 方法结束

    // ==================== ① 需求解析 ====================
    /**
     * 作用：用一次结构化 LLM 调用把自然语言需求解析成 TripRequest，再做归一化校正
     * 参数：prompt 用户原始需求文本
     * 返回值：TripRequest 需求对象
     * 异常：无（解析失败走 fallbackParse）
     */

    private TripRequest parseRequest(String prompt) {  // parseRequest 签名
        utils.SafeModel.setStage("解析需求"); // ★调用链：该 LLM span 说明标注"在做什么"  // setStage：给这次模型调用打“解析需求”标记（调用链里能看出在做什么）
        String sysPrompt = AgentPrompts.requestParser();  // 取需求解析的系统提示词
        String text = callModelJson(sysPrompt, prompt);  // callModelJson：调模型，要求输出 JSON
        TripRequest req = JsonUtils.extractAndParse(text, TripRequest.class);  // extractAndParse：从回复里抽 JSON 并反序列化（能剥 ``` 包裹）
        if (req == null) { // 解析失败：从原文粗提取兜底  // 解析失败
            req = fallbackParse(prompt);  // fallbackParse：用正则从原文粗提取（保证有基本需求）
        }  // if 结束
        if (req.getDays() < 1) {  // 天数小于 1（模型漏给了）
            req.setDays(1);  // 兜底 1 天（不能是 0 天行程）
        }  // if 结束
        req.setRaw(prompt);  // setRaw：保留用户原话（评审、兜底都要用）
        // ★调用链：把解析结果摘要回填到该 LLM span 说明（"解析需求 · 目的地:xx 天数:x"）
        try {  // try
            StringBuilder sum = new StringBuilder("目的地:")  // 拼摘要：目的地:
                    .append(req.getDestination() == null || req.getDestination().isBlank() ? "?" : req.getDestination())  // 目的地为空就写“?”
                    .append(" 天数:").append(req.getDays());  // 续：天数
            if (req.getBudgetTotal() > 0) {  // 有预算
                sum.append(" 预算:").append((long) req.getBudgetTotal());  // 续：预算
            }  // if 结束
            if (!req.getConstraints().isEmpty()) {  // 有约束
                sum.append(" 约束:").append(req.getConstraints().size()).append("条");  // 续：约束条数
            }  // if 结束
            if (req.getOrigin() != null && !req.getOrigin().isBlank()) {  // 有起点
                sum.append(" 起点:").append(req.getOrigin());  // 续：起点
            }  // if 结束
            if (req.getPreference() != null && !req.getPreference().isBlank()) {  // 有偏好
                sum.append(" 偏好:").append(req.getPreference());  // 续：偏好
            }  // if 结束
            utils.CallTrace.appendLastLlmDetail(sessionId, sum.toString());  // 把摘要写回最近一条 LLM span
        } catch (Exception ignored) { }  // 摘要拼装失败不影响解析
        return req;  // 返回需求
    }  // 方法结束

    /**
     * 作用：规则兜底解析：模型解析失败时用正则抠出出发地/目的地/天数
     * 参数：prompt 用户原始需求文本
     * 返回值：TripRequest 需求对象（至少保证天数与目的地可用）
     * 异常：无
     */
    private TripRequest fallbackParse(String prompt) {  // fallbackParse 签名
        TripRequest req = new TripRequest();  // 新建空需求
        String[] citys = prompt.replaceAll("帮我|制定|计划|规划|旅游|旅行|游|自驾|路线", " ").trim().split("\\s+");  // 把停用词（帮我/制定/计划/游/自驾…）替换成空格，再按空白切词
        int cityIdx = 0;  // cityIdx：已识别到第几个城市
        for (String token : citys) {  // 遍历切出的词
            if (token.isBlank() || token.length() > 6 || token.matches(".*\\d.*")) {  // 跳过空白、超过 6 字的、含数字的（基本是噪音）
                continue;  // 跳过
            }  // if 结束
            if (cityIdx == 0) {  // 第一个城市当出发地
                req.setOrigin(token);  // setOrigin
            } else if (cityIdx == 1) {  // 第二个当目的地
                req.setDestination(token);  // setDestination
            } else {  // 再多就不处理
                break;  // 跳出
            }  // else 结束
            cityIdx++;  // 计数 +1
        }  // for 结束
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*[日天]").matcher(prompt);  // 正则找“3日/5天”里的数字
        if (m.find()) {  // 找到
            req.setDays(Integer.parseInt(m.group(1)));  // 设为天数
        }  // if 结束
        return req;  // 返回需求（可能仍缺字段，但不会 NPE）
    }  // 方法结束

    /**
     * 作用：拼装偏好简短标签，用于过程事件展示
     * 参数：req 需求对象
     * 返回值：形如（偏好：亲子）的标签；无偏好返回空串
     * 异常：无
     */
    private String buildPreferenceLabel(TripRequest req) {  // buildPreferenceLabel 签名
        return ""; // 前端选项卡已删，偏好仅来自用户输入+个人中心  // 直接返回空串——前端偏好选项卡已删，偏好只来自用户输入与个人中心
    }  // 方法结束

    /**
     * 作用：拼装注入提示词的偏好约束文本
     * 参数：req 需求对象
     * 返回值：偏好约束文本；无偏好返回空串
     * 异常：无
     */
    private String buildPreferencePrompt(TripRequest req) {  // buildPreferencePrompt 签名
        return ""; // 预检约束不再拼入聚合提示词  // 返回空串——预检约束不再拼进聚合提示词
    }  // 方法结束

    // ==================== ② 并行 DAG ====================

    /**
     * 作用：给景点名补上城市前缀，避免"西湖"这类同名地点被解析到别的城市
     * 参数：city 目的地城市；sightList 景点清单文本
     * 返回值：带城市限定的景点清单
     * 异常：无
     */
    static String qualifySights(String city, String sightList) {  // qualifySights 签名：static，测试可直接调
        if (sightList == null || sightList.isBlank()) return sightList == null ? "" : sightList;  // 清单为空直接返回（null 归一空串）
        if (city == null || city.isBlank()) return sightList;  // 城市为空原样返回（无法限定）
        String c = city.trim();  // c：去掉首尾空格的城市名
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        for (String raw : sightList.split("[、,，;；/]")) {  // 按中英文顿号/逗号/分号/斜杠切分
            String s = raw.trim();  // 去空格
            if (s.isEmpty()) continue;  // 空项跳过
            if (sb.length() > 0) sb.append("、");  // 不是第一项就加顿号
            // 已含城市限定 → 原样保留，避免出现"成都·成都·环球中心"
            if (s.startsWith(c) || s.contains(c + "·") || s.contains(c + "-") || s.contains(c + "市")) {  // 以城市开头，或含“城市·”“城市-”“城市市”
                sb.append(s);  // 原样追加
            } else {  // 否则
                sb.append(c).append("·").append(s);  // 拼成“城市·景点”
            }  // if 结束
        }  // for 结束
        return sb.toString();  // 返回拼好的清单
    }  // 方法结束
    /**
     * 作用：构造发给路线子 Agent 的任务描述（首次）：按骨架停靠点要求给出总里程与分段里程
     * 参数：req 需求；sightList 停靠点清单
     * 返回值：任务描述文本
     * 异常：无
     */

    private String routeTask(TripRequest req, String sightList) {  // routeTask 两参签名
        return routeTask(req, sightList, "");  // 委托给三参版本，收缩反馈传空串
    }  // 方法结束
    /**
     * 作用：构造发给路线子 Agent 的任务描述（补算/收缩）：带收缩反馈，要求按修正意见重算路线
     * 参数：req 需求；sightList 停靠点清单；shrinkFeedback 上一轮的问题反馈
     * 返回值：任务描述文本
     * 异常：无
     */

    private String routeTask(TripRequest req, String sightList, String shrinkFeedback) {  // routeTask 三参签名：这是路线 Agent 的完整任务模板
        java.util.List<String> routePrefs = new java.util.ArrayList<>();  // routePrefs：路线偏好列表
        if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) routePrefs.add(req.getRoutePref().trim());  // 有路线偏好就加入
        String routePrefStr = routePrefs.isEmpty() ? "" : String.join(" ", routePrefs);  // routePrefStr：拼成字符串（空则空串）
        // sightList 非空 → 市内游：候选簇代表景点清单（路线按簇查衔接，供行程Agent编排时使用）
        String dest = (req.getDestination() == null || req.getDestination().isBlank()) ? "" : req.getDestination();  // dest：目的地（null 归一空串）
        boolean noOrigin = req.getOrigin() == null || req.getOrigin().isBlank();  // noOrigin：用户没给出发地
        // ★统一状态下发（PDF §三）：子 Agent 围绕 TravelState 决策，不各自解读原始用户文本
        String brief = req.toStateBrief() + "\n";  // brief：状态摘要 + 换行
        if (noOrigin || sightList != null && !sightList.isBlank()) {  // 没出发地，或给了景点清单（市内/周边游）
            // 市内：路线Agent针对"候选景点簇代表清单"查簇间衔接（候选已前置查好，代码成簇），不再自己猜景点
            String base = brief + "这是 " + dest + " 的" + req.getDays() + "天市内/周边游（用户未提供出发地）。\n";  // base：先说明这是几天市内/周边游、用户未提供出发地
            if (sightList != null && !sightList.isBlank()) {  // 有景点清单
                base += "需查询衔接的景点清单（已由代码按候选成簇/终版行程确定）：" + qualifySights(dest, sightList) + "。\n"  // 告诉它要查衔接的清单（已按候选成簇/终版行程确定）
                        + "★地名歧义防护：清单里每个地名都已带城市限定（如「成都·环球中心」），调用工具时必须**连同城市一起传**，"  // 地名歧义防护：清单里每个地名都带城市限定，调工具必须连城市一起传
                        + "不得只传裸名——实测裸名「环球中心」被高德解析到新疆阿克苏，会让整轮查询白跑并触发重查（多花 40s+）。\n"  // 实测裸名“环球中心”被高德解析到新疆阿克苏，整轮白跑并触发重查（多花 40s+）
                        + "★请用 query_route_unified(mode=urban" + (routePrefStr.isBlank() ? "" : ", routePref=" + routePrefStr)  // 要求用 query_route_unified(mode=urban[, routePref=…])
                        + ") 查询这些景点【彼此之间/所在区域之间】的衔接方式"  // 查这些景点彼此之间/所在区域之间的衔接方式
                        + "（短距步行/长距公交地铁，给出大致耗时），供行程Agent按真实衔接串联每日；"  // （短距步行/长距公交地铁，给出大致耗时），供行程 Agent 按真实衔接串联每日
                        + "不要额外添加不在清单里的景点，也不要编造跨城里程。\n";  // 不加清单外的景点，也不编造跨城里程
            } else {  // 没有清单
                base += "请规划在市内及周边的行程：\n"  // 改为：规划市内及周边行程
                        + "★调用 query_route_unified，mode 固定传 urban" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)  // 仍用 query_route_unified，mode 固定 urban[, routePref=…]
                        + "（市内衔接：步行/公交），查询市内相邻景点/地点间的衔接方式，不要编造跨城里程。\n";  // （市内衔接：步行/公交），查询相邻景点衔接，不编造跨城里程
            }  // if 结束
            base += "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数。\n"  // 追加：总行程必须正好 N 天，不得增加
                    + "最终回复：完整可读的市内衔接方案，含代表景点间的衔接方式与大致耗时（供行程Agent编排引用）。";  // 最终回复要求：完整市内衔接方案（含衔接方式与耗时）
            return base;  // 返回市内任务文本
        }  // if 结束（市内分支返回）
        String driveHint = switch (req.getTravelType() == null ? "" : req.getTravelType()) {  // 跨城：按出行类型给驾驶提示（switch）
            case "LOOP", "SELF_DRIVE" -> "（环线游：每日路线尽量不走回头路，环形串联）";  // 环线/自驾 → 每日路线尽量不走回头路，环形串联
            default -> "";  // 其他类型无额外提示
        };  // switch 结束
        return brief + "请规划从 " + req.getOrigin() + " 到 " + dest + " 的自驾方案，"  // 返回跨城任务：请规划从出发地到目的地的自驾方案，
                + req.getDays() + " 天" + driveHint + "。\n"  // 续：天数 + 驾驶提示
                + (sightList != null && !sightList.isBlank()  // 有候选景点区域时
                    ? "★候选核心景点区域（已由候选聚类确定，途经城市/停靠点应优先覆盖这些区域）：" + qualifySights(dest, sightList) + "\\n"  // 告诉它途经城市/停靠点应优先覆盖这些区域（已按候选聚类确定）
                      + "★地名歧义防护：以上地名均已带城市限定（如「成都·环球中心」），调用 query_trip/query_route_unified 时必须**连同城市一起传**，"  // 地名歧义防护（同上）
                      + "不得只传裸名——实测裸名「环球中心」被高德解析到新疆阿克苏，导致整轮查询白跑并触发重查（多花 40s+）。\\n"  // 续
                    : "")  // 没清单则这段为空
                + "★请给出【2~3 条候选路线方案】（单条路线可能错过好景点，须供行程Agent择优选择）：\n"  // 要求给出 2~3 条候选路线方案（供行程 Agent 择优）
                + "每条方案用以下固定格式分条标注：\n"  // 固定格式说明
                + "★候选路线一：途经城市：城市A,城市B,城市C\n"  // 候选路线一：途经城市 A,B,C
                + "每日概要：第1天…；第2天…（逐日简述行车段与停留点）\n"  // 每日概要：第1天…；第2天…
                + "★去程里程：约 NNNkm；★往返总里程：约 NNNkm（自驾/环线必须按【闭环】规划——出发地→各途经→目的地→返回出发地；"  // 去程里程与往返总里程（自驾/环线必须按闭环规划）
                + "往返总里程 = 含回程的全程真实里程，用 query_route_unified 各段累加）。★这条「往返总里程」标注行是里程数据的唯一权威来源（代码只读这一行），禁止省略或改用表格替代。\n"  // 往返总里程 = 含回程的全程真实里程，用 query_route_unified 各段累加；这行是里程唯一权威来源（代码只读这一行），禁止省略或改表格
                + "（候选路线二/三同格式；各方案走不同途经城市组合与节奏，不要雷同）\n"  // 候选二/三同格式，走不同途经组合与节奏，不要雷同
                + "★先调用 query_trip 查询出发地→目的地【沿途】的景点/观景点分布，据此确定途经城市与停靠点（以沿途真实景点为准，不要凭记忆拍脑袋定途经城市）；"  // 先 query_trip 查出发地→目的地沿途景点分布，据此定途经城市与停靠点（不要凭记忆）
                + "再调用 query_route_unified，mode 参数driving" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)  // 再 query_route_unified(mode=driving[, routePref=…])
                + "（自驾路线与距离），查询每条候选方案各段自驾线路的真实里程/时长。\n"  // 查每条候选各段真实里程/时长
                + "★严格限制：每条候选路线的总天数必须正好 " + req.getDays() + " 天，不得增加；"  // 严格限制：每条候选总天数必须正好 N 天，不得增加
                + "★里程天数门禁：往返总里程 ÷ 日均 300km（高原/山路）若超过 " + req.getDays()  // 里程天数门禁：往返总里程÷日均 300km 若超过 N 天，必须主动缩短路线
                + " 天，该方案必须【主动缩短路线】——砍掉最远节点/改走小环线/减少停靠点，保证单日车程不超 6 小时，严禁硬排超长赶路日。\n"  // （砍最远节点/改小环线/减停靠点），保证单日车程不超 6 小时，严禁硬排超长赶路日
                + (shrinkFeedback == null || shrinkFeedback.isBlank()  // 有收缩反馈时
                    ? "" : "★强制缩短（上一版往返里程超载，本次必须执行）：" + shrinkFeedback  // 强制缩短（上一版超载，本次必须执行）：拼上反馈
                        + "。★缩短的是【路线长度】（砍非核心中转停靠点/改走更紧的小环线），"  // 缩短的是路线长度（砍非核心中转点/改更紧的小环线）
                        + "绝不可砍掉返程——路线必须保持闭环（出发地→…→目的地→返回出发地），"  // 绝不可砍掉返程——必须保持闭环，totalKm 仍是含返程的往返总里程
                        + "totalKm 仍是含返程的往返闭环总里程。\n")  // 续行（结束该段）
                + "最终回复：完整列出各候选路线方案（含途经城市、逐日概要、总里程），不要只给单一定稿路线。\n"  // 最终回复：完整列出各候选方案，不要只给单一定稿
                + "★回复末尾必须附加一个【路线JSON】块（代码据此读往返里程/途经城市，字段齐全、禁止省略）：\n"  // 要求末尾附【路线JSON】块（代码据此读里程/途经城市，字段齐全）
                + "【路线JSON】\n"  // 【路线JSON】标记
                + "{\"totalKm\":往返闭环总里程整数(含返程,出发地→…→目的地→返回出发地全程),\"oneWayKm\":单程去程里程整数,\"waypoints\":\"途经城市逗号分隔\",\"routeCount\":候选路线条数}\n"  // 字段：totalKm（往返闭环总里程）、oneWayKm、waypoints、routeCount
                + "【路线JSON结束】\n"  // 【路线JSON结束】
                + "★口径铁律：totalKm 必须是【往返闭环】总里程（=去程+返程，绝非单程）；即使只规划了单程，totalKm 也必须按往返口径给（单程×2）。";  // 口径铁律：totalKm 必须是往返闭环总里程（=去程+返程），即使只规划单程也要按往返口径给
    }  // 方法结束

    /**
     * 作用：构造发给行程子 Agent 的最终细化任务（不含路线行）
     * 参数：state 任务状态；style 方案风格；feedback 上一轮反馈；prevPlanJson 上一版行程 JSON
     * 返回值：任务描述文本
     * 异常：无
     */
    private String finalPlanTask(TravelState state, String style, String feedback, String prevPlanJson) {  // finalPlanTask 四参签名
        return finalPlanTask(state, style, feedback, prevPlanJson, "");  // 委托给五参版本，routeLine 传空串
    }  // 方法结束
    /**
     * 作用：构造发给行程子 Agent 的最终细化任务（带路线行，要求逐天落实里程与时段）
     * 参数：state 任务状态；style 方案风格；feedback 上一轮反馈；prevPlanJson 上一版行程 JSON；routeLine 路线要点行
     * 返回值：任务描述文本
     * 异常：无
     */

    private String finalPlanTask(TravelState state, String style, String feedback, String prevPlanJson, String routeLine) {  // finalPlanTask 五参签名：这是行程 Agent 的完整任务模板
        TripRequest req = state.getRequest();  // 取需求
        String brief = req.toStateBrief() + "\n";  // brief：状态摘要
        String waypoints = state.getWaypoints();  // waypoints：途经城市
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("【候选预置】三域候选已由主管查好（见下方【三域候选】区）。禁止调用 query_trip_candidates 重复查询（重复=浪费与等待）；仅当某域候选明显不足（degraded 或数量<3）时才允许补查一次。\n\n");  // 开头就声明：三域候选已由主管查好，禁止重复调 query_trip_candidates，仅当某域不足（degraded 或 <3）才允许补查一次
        sb.append(brief);  // 追加状态摘要
        sb.append("用户需求：").append(req.getRaw()).append("\n");  // 追加用户原话（模型要看到原始需求）
        if (style != null && !style.isBlank()) {  // 有风格约束
            sb.append("\n【风格约束】").append(style).append("\n");  // 追加【风格约束】（多方案时区分三套）
        }  // if 结束
        if (waypoints != null && !waypoints.isBlank()) {  // 跨城（有途经城市）
            // 跨城：候选路线择一为主线 + 按主线途经城市安排（前段途经、后段终点）
            String routeCandidates = extractRouteCandidates(state.getRouteResult());  // 抽多条候选路线概要
            sb.append("请规划从 ").append(req.getOrigin()).append(" 到 ").append(req.getDestination())  // 请规划从出发地到目的地
                    .append(" 的").append(req.getDays()).append("天行程，重点规划【终点城市】与【途经城市】的景点，不要规划起点城市 ")  // 续：N 天行程，重点规划终点与途经城市景点，不要规划起点城市内部景点（起点只是出发地）
                    .append(req.getOrigin()).append(" 内部的景点（起点只是出发地）");  // 续行：起点名
            String prefCross = req.getTripPref();  // 取出行偏好
            if (prefCross != null && !prefCross.isBlank()) {  // 有偏好
                sb.append("，偏好：").append(prefCross);  // 续：偏好
            }  // if 结束
            sb.append("。\n");  // 句号收尾
            if (!routeCandidates.isBlank()) {  // 有候选路线
                sb.append("路线Agent给出多条候选路线方案，择一作为主线：\n").append(routeCandidates).append("\n");  // 告诉它“路线 Agent 给了多条，择一作为主线”，并附上候选
            }  // if 结束
            sb.append("【你选定主线的途经城市】").append(waypoints)  // 明确所选主线的途经城市
                    .append("。每日安排与所选路线的途经城市/行车段一致，跨城衔接以路线Agent返回为准，禁止编造跨城里程。\n");  // 续：每日安排与所选路线一致，跨城衔接以路线 Agent 为准，禁止编造跨城里程
            if (routeLine != null && !routeLine.isBlank()) {  // 有多方案指定的骨架行
                sb.append("★本方案指定骨架：").append(routeLine)  // 指定骨架：
                        .append("——请【严格以这条候选路线为主线】安排每日行程（这是与其它方案区分的关键，不要自行改走别的路线）。\n");  // 续：要求严格以这条候选路线为主线安排每日（这是与其它方案区分的关键）
            }  // if 结束
        } else {  // 市内分支
            sb.append("请规划 ").append(req.getDestination()).append(" ").append(req.getDays())  // 请规划目的地 N 日游的景点与每日行程，
                    .append(" 日游的景点与每日行程，包含景点、门票、住宿、餐饮美食");  // 续：包含景点、门票、住宿、餐饮美食
            String pref = req.getTripPref();  // 取出行偏好
            if (pref != null && !pref.isBlank()) {  // 有偏好
                sb.append("，偏好：").append(pref);  // 续：偏好
            }  // if 结束
            sb.append("。\n");  // 句号
            // 市内：路线Agent已按簇代表查过真实衔接 → 行程Agent编排必须依据它（不再事后补查）
            String route = state.getRouteResult();  // 取路线结果
            if (route != null && !route.isBlank()) {  // 有路线结果
                sb.append("【簇间衔接（路线Agent按候选景点簇查询的真实交通：步行/公交/时长）】\n")  // 【簇间衔接】标题：路线 Agent 按候选景点簇查询的真实交通
                        .append(clip(route, 3000)).append("\n");  // 附上路线文本（clip 到 3000 字符）
            }  // if 结束
        }  // 跨城/市内分支结束
        // 三域候选可读清单 + 区域分组（复用原聚合拼装；候选JSON原文已由代码存入状态中心，此处只注入可读摘要供选点）
        String candSection = buildCandidatesSection(state);  // buildCandidatesSection：生成候选可读清单
        if (!candSection.isBlank()) {  // 有清单
            sb.append(candSection).append("\n");  // 追加
        }  // if 结束
        if (feedback != null && !feedback.isBlank()) {  // 有审查反馈
            sb.append("\n【审查反馈】上一版行程经独立审查发现问题：").append(feedback)  // 【审查反馈】上一版经独立审查发现问题：
                    .append("\n本次只修复上述问题；未涉及的天与区块保持原样（增量修正铁律，禁止无中生有全盘重排）。\n");  // 续：本次只修复这些问题；未涉及的天与区块保持原样（增量修正铁律，禁止全盘重排）
            if (req != null && req.getDays() > 0) {  // 需求有天数
                sb.append("★总天数铁律：行程总天数必须保持用户要求的 ")  // 总天数铁律：必须保持用户要求的
                        .append(req.getDays())  // 续：天数
                        .append(" 天（timeline 区块数 = ").append(req.getDays())  // 续：天（timeline 区块数 = 天数）
                        .append("），禁止增减天数——返程/晚间/收尾一律写进最后一天内部，不得为此新开一天。\n");  // 续：禁止增减；返程/晚间/收尾一律写进最后一天内部，不得为此新开一天
            }  // if 结束
        }  // if 结束
        // ★交通工具铁律：自驾/环线全程必须自驾（含返程），禁止擅自改成飞机/高铁规避超长返程
        if ("SELF_DRIVE".equals(req.getTravelType() == null ? "" : req.getTravelType())  // 是自驾
                || "LOOP".equals(req.getTravelType() == null ? "" : req.getTravelType())) {  // 或环线
            sb.append("★交通工具铁律：本行程为【自驾/环线】，去程与返程全程必须自驾，"  // 交通工具铁律：去程与返程全程必须自驾，
                    + "禁止擅自把返程改成飞机/高铁/火车来规避超长返程——即使返程里程长、超出单日车程上限，"  // 续：禁止把返程改成飞机/高铁/火车规避超长返程，
                    + "也如实标注该日车程并给出拆分建议（如中途住宿），不得换交通工具。\n");  // 续：即使返程里程长也如实标注该日车程并给拆分建议（如中途住宿），不得换交通工具
        }  // if 结束
        // ★闭环铁律（仅需返程时）：行程是一个圈——最后一天必须回到出发地；返程按正常行程安排
        if (needsReturnLoop(req)) {  // 需要返程闭环
            sb.append("★闭环铁律：本行程是一个【闭环】——从 " + req.getOrigin() + " 出发，最后一天【必须回到 ")  // 闭环铁律：从出发地出发，最后一天必须回到
                    .append(req.getOrigin())  // 续：出发地
                    .append("】。返程是行程的一部分（不是可省略项），必须按正常行程安排（在途≤6h、在途>4h有景点停靠、游玩4~8h）；")  // 续：返程是行程一部分，必须按正常行程安排（在途≤6h、>4h 有景点停靠、游玩 4~8h）
                    .append("若一天排不完返程，就【分多天边走边玩返回】，严禁终止于中途城镇或省略返程；")  // 续：一天排不完就分多天边走边玩返回，严禁终止于中途城镇或省略返程
                    .append("返程若确实超时，如实标注车程即可，但不得因此不排返程。\n");  // 续：返程超时就如实标注车程，但不得因此不排返程
        }  // if 结束
        if (prevPlanJson != null && !prevPlanJson.isBlank()) {  // 有上一版行程 JSON
            sb.append("上一版行程JSON（修改基准，仅修订反馈涉及部分）：\n")  // 告诉它这是修改基准，只修订反馈涉及部分
                    .append("【行程JSON】\n").append(prevPlanJson).append("\n【行程JSON结束】\n");  // 附上一版 JSON 块
        }  // if 结束
        sb.append("\n最终回复：简短概要（每天一句话，总长不超过300字——完整可读文本由主管从你的JSON生成，概要只作兜底），末尾【必须】原样附完整【行程JSON】结构化块（格式见系统规则）。"  // 最终回复要求：简短概要（每天一句，≤300 字，完整文本由主管从 JSON 生成），
                + "输出预算优先保证 JSON 块完整，宁可概要更短也不可截断 JSON。"  // 续：末尾必须原样附完整【行程JSON】块；输出预算优先保证 JSON 完整，宁可概要更短
                + "JSON 的区块必须齐全：每天 timeline、stay、transport、tips 等区块一个都不能少——内容精简时压缩区块内每条 note/描述文字（宁短勿长），禁止整块删除或留空区块；tips（实用贴士）必须保留 5-8 条真实可用的提示。");  // 续：区块必须齐全（每天 timeline/stay/transport/tips 一个不少），内容精简时压缩单条文字，禁止整块删除或留空；tips 保留 5-8 条
        return sb.toString().trim();  // 返回 trim 后的任务文本
    }  // 方法结束

    /**
     * 作用：预取三域候选（在正式细化前先把真实性数据准备好）
     * 参数：state 任务状态；waypoints 途经城市；req 需求
     * 返回值：无
     * 异常：无（预取失败不阻断，后续会再补）
     */
    private void fetchCandidatesPreflight(TravelState state, String waypoints, TripRequest req) {  // fetchCandidatesPreflight 签名
        String pref = req.getTripPref() == null ? "" : req.getTripPref();  // pref：出行偏好（null 归一空串）
        String n = String.valueOf(utils.CandidateUtil.candidateCountPerDomain(String.valueOf(req.getDays())));  // n：每域候选个数（按天数算）
        EventStore.add(sessionId, "候选", "候选前置查询(域专家三线程并行，每域" + n + "个)"  // 写事件：候选前置查询（域专家三线程并行，每域 n 个）
                + (waypoints == null || waypoints.isBlank() ? "" : " · 途经:" + waypoints) + " ...");  // 续：有途经城市就带上
        try {  // try
            // ① 全域主查
            java.util.Map<String, Object> cands = queryCandidatesOnce(req, waypoints, pref, null);  // queryCandidatesOnce：调一次候选查询（全域网）
            if (cands == null || cands.isEmpty()) {  // 整包失败（null 或空）
                // 整包失败：等 3s 后整包再查一次（极端情况，如 8085 瞬时不可用）
                EventStore.add(sessionId, "候选", "候选前置查询失败/无结构化结果，3 秒后整包重查一次...");  // 写事件：候选前置查询失败，3 秒后整包重查一次
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }  // sleep 3 秒（被中断就恢复标志）
                cands = queryCandidatesOnce(req, waypoints, pref, null);  // 重查
            }  // if 结束
            if (cands != null && !cands.isEmpty()) {  // 查到了
                // ② 分域补查：缺失域只补缺失（domains=xxx）
                java.util.List<String> missing = missingDomains(cands);  // 算出哪些域缺失
                if (!missing.isEmpty()) {  // 有缺失
                    EventStore.add(sessionId, "候选", "候选域缺失（" + String.join("、", missing)  // 写事件：候选域缺失（xxx），3 秒后分域补查一次
                            + "），3 秒后分域补查一次...");  // 续行
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }  // sleep 3 秒
                    java.util.Map<String, Object> cands2 = queryCandidatesOnce(req, waypoints, pref,  // 按缺失域再查一次
                            String.join(",", missing));  // 续：domains=缺失域名
                    if (cands2 != null) {  // 补查有结果
                        for (String d : missing) {  // 遍历缺失域
                            if (!candDomainList(cands2, d).isEmpty()) {  // 该域补到了数据
                                cands.put(d, cands2.get(d)); // 只合并补到的域  // 只合并补到的域
                            }  // if 结束
                        }  // for 结束
                    }  // if 结束
                }  // if 结束
                // ③ 坐标补齐（域专家 location 覆盖率不稳 → 高德按名补查，保证骨架成簇可用）
                int fixed = enrichSightLocations(cands, req.getDestination());  // enrichSightLocations：按名补坐标
                if (fixed > 0) {  // 补到了一些
                    EventStore.add(sessionId, "候选", "景点坐标补齐：" + fixed + " 个（高德按名补查）");  // 写事件：景点坐标补齐 N 个
                }  // if 结束
                boolean hasAttr = !candDomainList(cands, "attraction").isEmpty();  // hasAttr：景点域有数据
                if (hasAttr) {  // 有景点才能做骨架
                    state.setCandidatesJson(utils.JsonUtils.toJson(cands));  // 把候选 JSON 写入状态中心
                    String summary = utils.CandidateUtil.evidenceSummary(cands);  // 证据摘要
                    String cluster = utils.CandidateUtil.clusterHintSummary(cands);  // 聚类摘要
                    EventStore.add(sessionId, "候选", "三域候选已前置入状态"  // 写事件：三域候选已前置入状态
                            + (summary.isBlank() ? "" : "：" + summary)  // 续：有摘要带摘要
                            + (cluster.isBlank() ? "" : "｜" + cluster));  // 续：有聚类带聚类
                    EventStore.putExtra(sessionId, "clusterStats", utils.CandidateUtil.clusterStats(cands));  // 聚类统计塞进附加信息
                    state.addTrace("候选", "三域候选(结构化,前置查询,每域" + n + ")");  // 记轨迹：三域候选（结构化，前置查询，每域 n）
                    return;  // 直接返回（成功）
                }  // if 结束
            }  // if 结束
            EventStore.add(sessionId, "候选", "候选前置查询重查后仍失败/缺景点域，行程Agent将自行补查兜底");  // 写事件：重查后仍失败/缺景点域，行程 Agent 将自行补查兜底
        } catch (Exception e) {  // 异常
            log.warn("候选前置查询异常(行程Agent自行补查兜底): {}", e.getMessage());  // warn 日志
            EventStore.add(sessionId, "候选", "候选前置查询异常(" + e.getMessage() + ")，行程Agent将自行补查兜底");  // 写事件：候选前置查询异常（原因），行程 Agent 自行补查兜底
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：调用一次三域候选工具（可限定域名），返回解析后的候选 Map
     * 参数：req 需求；waypoints 途经城市；pref 偏好关键词；domains 限定域名（null 表示全部）
     * 返回值：候选数据 Map；失败返回空 Map
     * 异常：无（异常内部捕获）
     */
    private java.util.Map<String, Object> queryCandidatesOnce(TripRequest req, String waypoints,  // queryCandidatesOnce 签名第 1 行
                                                              String pref, String domains) {  // 签名第 2 行：pref、domains
        try {  // try
            StringBuilder qs = new StringBuilder("destination=")  // 拼查询串：destination（URL 编码）
                    .append(java.net.URLEncoder.encode(req.getDestination() == null ? "" : req.getDestination(), "UTF-8"))  // 续：目的地
                    .append("&days=").append(req.getDays())  // ＋ days
                    .append("&preference=").append(java.net.URLEncoder.encode(pref, "UTF-8"))  // ＋ preference（编码）
                    .append("&waypoints=").append(java.net.URLEncoder.encode(waypoints == null ? "" : waypoints, "UTF-8"))  // ＋ waypoints（编码）
                    .append("&brief=").append(java.net.URLEncoder.encode(req.toStateBrief(), "UTF-8"));  // ＋ brief（状态摘要，编码）
            if (domains != null && !domains.isBlank()) {  // 限定了域
                qs.append("&domains=").append(java.net.URLEncoder.encode(domains, "UTF-8"));  // ＋ domains
            }  // if 结束
            qs.append("&sessionId=").append(java.net.URLEncoder.encode(sessionId, "UTF-8"));  // ＋ sessionId（让域专家 span 归位）
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(  // 打开连接（tripAgent 地址 + /trip/candidates）
                    utils.AgentEndpoints.tripAgent() + "/trip/candidates?" + qs).openConnection();  // 续行
            c.setConnectTimeout(10000);  // 连接超时 10 秒
            c.setReadTimeout(300000); // 域专家并行最长120s + 置信度重试  // 读超时 300 秒（域专家并行最长 120s + 置信度重试）
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应体
            java.util.Map<String, Object> root = utils.JsonUtils.parse(body, java.util.Map.class);  // 解析 Map
            String reply = root == null ? null : str(root.get("reply"));  // 取 reply 字段
            String block = reply == null ? "" : utils.CandidateUtil.extractBlock(reply);  // 从 reply 里抽【域候选JSON】块
            return block.isBlank() ? null : utils.CandidateUtil.parse(block);  // 块为空返回 null，否则解析成 Map 返回
        } catch (Exception e) {  // 异常
            log.warn("候选查询请求异常: {}", e.getMessage());  // warn 日志
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：检查候选数据里哪些域缺失或为空
     * 参数：cands 候选数据
     * 返回值：缺失域名列表
     * 异常：无
     */
    private static java.util.List<String> missingDomains(java.util.Map<String, Object> cands) {  // missingDomains 签名
        java.util.List<String> missing = new java.util.ArrayList<>();  // 准备结果
        if (cands == null) {  // cands 为 null
            missing.add("attraction");  // 缺景点
            missing.add("hotel");  // 缺酒店
            missing.add("dining");  // 缺餐饮
            return missing;  // 返回三个都缺
        }  // if 结束
        for (String d : utils.CandidateUtil.DOMAINS) {  // 遍历三个域
            if (candDomainList(cands, d).isEmpty()) missing.add(d);  // 该域列表为空就记缺失
        }  // for 结束
        return missing;  // 返回缺失列表
    }  // 方法结束

    /**
     * 作用：把子 Agent 的最终输出解析成结构化行程（含块剥离与字段兜底）
     * 参数：state 任务状态
     * 返回值：结构化行程；解析失败返回 null
     * 异常：无（异常内部捕获）
     */
    private TripPlanData parseTripPlan(TravelState state) {  // parseTripPlan 签名
        String reply = state.getPlanResult();  // 取行程 Agent 的回复
        if (reply == null || reply.isBlank()) return null;  // 空回复返回 null
        String block = utils.CandidateUtil.extractPlanBlock(reply);  // 抽【行程JSON】块
        if (block.isBlank()) return null;  // 没有块返回 null
        TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);  // 反序列化成 TripPlanData
        if (plan == null) return null;  // 解析失败返回 null
        // 完整性缺口（原 aggregateGap 语义：sections空/timeline缺/stay缺 → 视为不可交付）
        String gap = aggregateGap(plan);  // aggregateGap：算出缺口描述
        if (!gap.isBlank()) {  // 有缺口
            EventStore.add(sessionId, "定稿", "行程JSON缺关键内容(" + gap + ")，降级为原文兜底");  // 写事件：行程JSON缺关键内容(缺口)，降级为原文兜底
            return null;  // 返回 null（上层走 fallbackPlan）
        }  // if 结束
        normalizePlan(state, plan);  // normalizePlan：归一化（标题/天数/天气等）
        return plan;  // 返回行程
    }  // 方法结束

    /**
     * 作用：行程归一化：区块排序、缺失字段兜底、天数校正、markdown 渲染
     * 参数：state 任务状态；plan 行程（原地修改）
     * 返回值：无
     * 异常：无
     */
    private void normalizePlan(TravelState state, TripPlanData plan) {  // normalizePlan 签名
        TripRequest req = state.getRequest();  // 取需求
        if (plan.getTitle() == null || plan.getTitle().isBlank()) {  // 标题为空
            plan.setTitle(req.getDestination() + req.getDays() + "日游");  // 用“目的地+天数+日游”兜底
        }  // if 结束
        if (plan.getDestination() == null || plan.getDestination().isBlank()) {  // 目的地为空
            plan.setDestination(req.getDestination());  // 用需求里的目的地
        }  // if 结束
        if (plan.getDays() < 1) {  // 天数小于 1
            plan.setDays(req.getDays());  // 用需求天数
        }  // if 结束
        String routeResult = state.getRouteResult();  // 取路线结果
        String planResult = state.getPlanResult();  // 取行程结果
        // ★天气提取前必须剥离【行程JSON】块：否则正则会把块内 JSON 字段("weather":"...","place":...)当天气抓到 weather 字段
        String planText = planResult == null ? "" : utils.CandidateUtil.stripPlanBlock(planResult);  // planText：去掉 JSON 块后的正文
        String weatherRaw = cleanWeather(extractWeatherRaw((routeResult == null ? "" : routeResult)  // weatherRaw：从路线文本 + 行程正文里提天气，再清洗
                + "\n" + planText));  // 续行
        if (weatherRaw == null || weatherRaw.isBlank()) {  // 没提到天气
            String direct = fetchAmapWeather(req.getDestination(), Math.min(req.getDays(), 7));  // 直连高德预报（最多 7 天）
            if (direct != null && !direct.isBlank()) {  // 拿到预报
                weatherRaw = direct;  // 用直连结果
                EventStore.add(sessionId, "定稿", "天气直连高德预报：" + direct);  // 写事件：天气直连高德预报
            }  // if 结束
        }  // if 结束
        if (weatherRaw != null && !weatherRaw.isBlank()) {  // 有天气（专家原文或直连）
            plan.setWeather(weatherRaw); // 覆盖行程Agent值（已清洗）  // 覆盖行程里的天气（已清洗）
            EventStore.add(sessionId, "定稿", "天气透传：" + weatherRaw);  // 写事件：天气透传
        } else if (plan.getWeather() != null && !plan.getWeather().isBlank()) {  // 否则行程本身带了天气
            plan.setWeather(cleanWeather(plan.getWeather()));  // 清洗后写回（去掉模型的多余描述）
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：从反馈文本中解析要重排的天号（单个）
     * 参数：state 任务状态；feedback 反馈文本
     * 返回值：天号；解析不到返回 0
     * 异常：无
     */
    private int skeletonDayNo(TravelState state, String feedback) {  // skeletonDayNo 签名
        java.util.List<String> dj = state.getDayPlanJson();  // 取按天 JSON 列表
        if (dj == null || dj.isEmpty() || feedback == null) return 0;  // 为空或反馈为空返回 0
        TripRequest req = state.getRequest();  // 取需求
        int days = req == null ? 0 : req.getDays();  // 天数
        if (days < 1 || dj.size() != days) return 0; // 非骨架模式/段数不完整  // 天数无效或按天段数不完整（非骨架模式）返回 0
        java.util.Set<Integer> hit = new java.util.TreeSet<>();  // hit：命中的天号集合（有序去重）
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则：第N天 或 dayN
                .compile("第\\s*(\\d+)\\s*天|day\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)  // 续：
                .matcher(feedback);  // 编译（忽略大小写）
        while (m.find()) {  // 逐处匹配
            String g = m.group(1) != null ? m.group(1) : m.group(2);  // 取第 1 组或第 2 组
            if (g == null) continue;  // 都没有就跳过
            try {  // try
                int n = Integer.parseInt(g);  // 转数字
                if (n >= 1 && n <= days) hit.add(n);  // 在 1..天数 范围内就记下
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
        }  // while 结束
        return hit.size() == 1 ? hit.iterator().next() : 0;  // 只命中一天才返回那一天，否则 0（多天走另一个方法）
    }  // 方法结束

    /**
     * 作用：从反馈文本中解析所有涉及的天号（多个）
     * 参数：state 任务状态；feedback 反馈文本
     * 返回值：天号集合
     * 异常：无
     */
    private java.util.Set<Integer> skeletonDayNos(TravelState state, String feedback) {  // skeletonDayNos 签名
        java.util.Set<Integer> hit = new java.util.TreeSet<>();  // hit：结果集合
        java.util.List<String> dj = state == null ? null : state.getDayPlanJson();  // 取按天 JSON 列表
        if (dj == null || dj.isEmpty() || feedback == null) return hit;  // 为空返回空集合
        TripRequest req = state.getRequest();  // 取需求
        int days = req == null ? 0 : req.getDays();  // 天数
        if (days < 1 || dj.size() != days) return hit; // 非骨架模式/段数不完整  // 非骨架模式返回空集合
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则：第N天 / dayN
                .compile("第\\s*(\\d+)\\s*天|day\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)  // 续
                .matcher(feedback);  // 编译
        while (m.find()) {  // 逐处匹配
            String g = m.group(1) != null ? m.group(1) : m.group(2);  // 取组
            if (g == null) continue;  // 跳过
            try {  // try
                int n = Integer.parseInt(g);  // 转数字
                if (n >= 1 && n <= days) hit.add(n);  // 范围有效就加入
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
        }  // while 结束
        return hit;  // 返回集合
    }  // 方法结束

    /**
     * 作用：从模型文本回复中恢复按天保存的行程 JSON（防止结构化解析失败丢天）
     * 参数：state 任务状态；req 需求
     * 返回值：无
     * 异常：无
     */
    private void restoreDayPlanJsonFromText(TravelState state, TripRequest req) {  // restoreDayPlanJsonFromText 签名
        try {  // try
            if (state == null || req == null) return;  // 参数无效返回
            int days = req.getDays();  // 天数
            if (days <= 0) return;  // 天数<=0 返回
            String text = state.getPlanResult();  // 取行程原文
            if (text == null || text.isBlank()) return;  // 空则返回
            String block = utils.CandidateUtil.extractPlanBlock(text);  // 抽【行程JSON】块
            if (block == null || block.isBlank()) return;  // 没有块返回
            dto.TripPlanData whole = utils.JsonUtils.extractAndParse(block, dto.TripPlanData.class);  // 整份解析成 TripPlanData
            if (whole == null || whole.getSections() == null || whole.getSections().isEmpty()) return;  // 解析失败或没有区块返回

            java.util.Map<Integer, java.util.List<dto.Section>> byDay = new java.util.TreeMap<>();  // byDay：天号 → 该天区块列表
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*天");  // 正则：第N天
            for (dto.Section s : whole.getSections()) {  // 遍历所有区块
                String t = s.getTitle() == null ? "" : s.getTitle();  // 取标题
                java.util.regex.Matcher m = p.matcher(t);  // 匹配
                if (!m.find()) continue;  // 标题里没有天号就跳过（如“住宿与餐饮”）
                try {  // try
                    int d = Integer.parseInt(m.group(1));  // 取天号
                    if (d >= 1 && d <= days) byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(s);  // 范围有效就按天分组
                } catch (NumberFormatException ignored) { }  // 解析失败忽略
            }  // for 结束
            if (byDay.isEmpty()) return; // 拆分不出天 → 保持原样（不要造出半个结构）  // 一个天都拆不出来就返回（不要造半个结构）

            java.util.List<String> dj = state.getDayPlanJson();  // 取按天 JSON 列表
            if (dj == null) return;  // 为 null 返回
            dj.clear();  // 清空旧内容
            for (int d = 1; d <= days; d++) {  // 逐天重建
                java.util.List<dto.Section> ss = byDay.get(d);  // 取该天区块
                if (ss == null || ss.isEmpty()) {  // 该天没有区块
                    state.addDayPlanJson(d, ""); // 缺天留空位（与天骨架部分成功的行为一致）  // 留空位（与天骨架部分成功的行为一致）
                    continue;  // 继续下一天
                }  // if 结束
                dto.TripPlanData one = new dto.TripPlanData();  // 新建单天 TripPlanData
                one.setTitle(whole.getTitle());  // 复制标题
                one.setDestination(whole.getDestination());  // 复制目的地
                one.setDays(whole.getDays());  // 复制天数
                one.setDate(whole.getDate());  // 复制日期
                one.setSections(ss);  // 挂该天区块
                state.addDayPlanJson(d, utils.JsonUtils.toJson(one));  // 序列化后写入按天列表
            }  // for 结束
            EventStore.add(sessionId, "解析", "· 缓存结果已还原按天结构（"  // 写事件：缓存结果已还原按天结构（x/y 天），审查修复可按天进行
                    + byDay.size() + "/" + days + " 天），审查修复可按天进行 ·");  // 续行
            log.info("[cache] 已从缓存文本还原按天结构: {}/{} 天", byDay.size(), days);  // info 日志
        } catch (Throwable t) {  // 捕获任何 Throwable
            // 还原失败不致命：最多退回原来的整单修复路径
            log.warn("[cache] 还原按天结构失败（忽略，退回整单路径）: {}", t.toString());  // warn 日志
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：区块规范化：按天排序、合并重复类型区块、补齐缺失的每日区块
     * 参数：plan 行程（原地修改）；sessionId 会话 ID（用于事件）
     * 返回值：无
     * 异常：无
     */
    private void normalizePlanSections(dto.TripPlanData plan, String sessionId) {  // normalizePlanSections 签名
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) return;  // plan 或 sections 为空直接返回
        java.util.List<dto.Section> src = new java.util.ArrayList<>(plan.getSections());  // src：原区块列表的副本

        // ① 去重（保序）
        java.util.LinkedHashMap<String, dto.Section> uniq = new java.util.LinkedHashMap<>();  // uniq：type|title|items 组合键 → 区块
        for (dto.Section s : src) {  // 遍历原区块
            if (s == null) continue;  // null 跳过
            String key = str(s.getType()) + "|" + str(s.getTitle()) + "|"  // key = 类型|标题|
                    + utils.JsonUtils.toJson(s.getItems());  // 续：条目 JSON（三者全同才算重复）
            uniq.putIfAbsent(key, s);  // putIfAbsent：已存在就不覆盖（保留第一个）
        }  // for 结束

        // ② 分组：能识别天号的按天号，其余入 rest
        java.util.TreeMap<Integer, java.util.List<dto.Section>> byDay = new java.util.TreeMap<>();  // byDay：天号 → 区块列表（TreeMap 保证按天排序）
        java.util.List<dto.Section> rest = new java.util.ArrayList<>();  // rest：识别不出天号的区块
        for (dto.Section s : uniq.values()) {  // 遍历去重后的区块
            Integer d = dayNoInText(s.getTitle());  // 从标题解析天号
            if (d == null) {  // 标题里没有天号
                // 区块标题没写天号时，退回看它的 items 文本（住宿条目常带"第N天"）
                StringBuilder sb = new StringBuilder();  // 拼 items 文本
                if (s.getItems() != null) {  // 有 items
                    for (dto.SectionItem it : s.getItems()) {  // 遍历条目
                        if (it != null) sb.append(str(it.getName())).append(' ').append(str(it.getActivity())).append(' ');  // 拼 name + activity
                    }  // if 结束
                }  // if 结束
                d = dayNoInText(sb.toString());  // 再从 items 文本解析天号
            }  // if 结束
            if (d != null && d > 0) byDay.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(s);  // 有有效天号 → 按天分组
            else rest.add(s);  // 否则进 rest
        }  // for 结束

        java.util.List<dto.Section> out = new java.util.ArrayList<>();  // out：输出列表
        for (java.util.Map.Entry<Integer, java.util.List<dto.Section>> e : byDay.entrySet()) {  // 按天号升序遍历
            e.getValue().sort(java.util.Comparator.comparingInt(this::sectionRank));  // 同一天内按 sectionRank 排序（timeline 在前、stay 次之、其它后）
            out.addAll(e.getValue());  // 整组加入
        }  // for 结束
        out.addAll(rest); // 识别不出天号的原样保留到最后，绝不丢弃  // 识别不出天号的原样追加到最后，绝不丢弃

        if (out.size() != src.size()) {  // 去重后数量减少
            plan.setSections(out);  // 写回区块列表
            EventStore.add(sessionId, "定稿", "区块规整化：去掉 " + (src.size() - out.size())  // 写事件：区块规整化，去掉 N 个重复区块（x → y）
                    + " 个重复区块（" + src.size() + " → " + out.size() + "）");  // 续行
        } else {  // 没去重
            plan.setSections(out);  // 仍然写回（顺序已被按天规整）
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：给区块类型定排序权重（timeline 在前、tips 在后）
     * 参数：s 区块
     * 返回值：排序权重值
     * 异常：无
     */
    private int sectionRank(dto.Section s) {  // sectionRank 签名
        String t = str(s.getType()).toLowerCase();  // 类型转小写
        if ("timeline".equals(t)) return 0;  // timeline → 0（最前）
        if ("stay".equals(t)) return 1;  // stay → 1
        return 2;  // 其它 → 2（最后）
    }  // 方法结束

    /**
     * 作用：从文本中解析天号（支持"第2天""D2"等写法）
     * 参数：text 文本
     * 返回值：天号；解析不到返回 null
     * 异常：无
     */
    private Integer dayNoInText(String text) {  // dayNoInText 签名
        if (text == null || text.isBlank()) return null;  // 空文本返回 null
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*天").matcher(text);  // 正则：第N天
        if (m.find()) {  // 找到
            try {  // try
                return Integer.parseInt(m.group(1));  // 转数字返回
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
        }  // if 结束
        return null;  // 没找到返回 null
    }  // 方法结束

    /**
     * 作用：判断缓存命中是否可用（天数与结构必须与本次需求匹配，否则视为污染）
     * 参数：hit 缓存条目；req 需求
     * 返回值：true=可以复用
     * 异常：无
     */
    private static boolean cacheEntryUsable(utils.cache.PlanCache.Entry hit, TripRequest req) {  // cacheEntryUsable 签名
        if (hit == null || hit.planResult == null || hit.planResult.isBlank()) return false;  // 命中为空或行程为空 → 不可用
        int want = req == null ? 0 : req.getDays();  // want：期望天数
        return planStructOk(hit.planResult, want);  // 结构校验通过才算可用
    }  // 方法结束

    /**
     * 作用：判断行程文本的结构是否完整（天区块数达到要求）
     * 参数：planText 行程文本；wantDays 期望天数
     * 返回值：true=结构完整
     * 异常：无
     */
    private static boolean planStructOk(String planText, int wantDays) {  // planStructOk 签名
        if (planText == null || planText.isBlank()) return false;  // 空文本 → false
        if (wantDays <= 0) return true;  // 没要求天数 → true
        java.util.TreeSet<Integer> got = new java.util.TreeSet<>();  // got：识别到的天号集合
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则第N天
                .compile("第\\s*(\\d+)\\s*天")  // 续
                .matcher(planText);  // 续
        while (m.find()) {  // while 遍历
            try {  // try
                got.add(Integer.parseInt(m.group(1)));  // 加入集合
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
        }  // while 结束
        if (got.isEmpty()) return false;                      // 连一天都识别不出 → 不可用  // 一天都识别不出 → 不可用
        // 要求覆盖 1..wantDays 且不超出（超天数也是历史踩过的坑：3 日游被扩成 5 天）
        return got.first() == 1 && got.last() == wantDays && got.size() >= wantDays;  // 首=1、尾=wantDays、数量够 → 完整
    }  // 方法结束

    /**
     * 作用：判断反馈是否属于整体性问题（而非某一天）
     * 参数：feedback 反馈文本
     * 返回值：true=整体性问题
     * 异常：无
     */
    private static boolean isGlobalFeedback(String feedback) {  // isGlobalFeedback 签名
        if (feedback == null || feedback.isBlank()) return false;  // 空反馈 → false
        String[] globalKeys = {  // globalKeys：全局关键词表
                "自驾", "交通方式", "公共交通", "包车", "租车",   // 出行方式：天然覆盖全程  // 出行方式类（天然覆盖全程）
                "全程", "每一天", "每天", "所有天", "各天", "全天数", // 显式全量表述  // 显式全量表述
                "住宿区域", "统一", "衔接一致"                      // 跨天一致性要求  // 跨天一致性要求
        };  // 数组结束
        for (String k : globalKeys) {  // 逐个检查
            if (feedback.contains(k)) return true;  // 命中任一 → true
        }  // for 结束
        return false;  // 都不命中 → false
    }  // 方法结束

    /**
     * 作用：构造单天修复任务：只重排指定那天，并给出问题反馈与上下文锚点
     * 参数：state 任务状态；dayNo 天号；feedback 反馈文本
     * 返回值：任务描述文本
     * 异常：无
     */
    private String buildDayRepairTask(TravelState state, int dayNo, String feedback) {  // buildDayRepairTask 签名
        TripRequest req = state.getRequest();  // 取需求
        java.util.List<String> dj = state.getDayPlanJson();  // 取按天 JSON
        String oldJson = (dj != null && dayNo >= 1 && dayNo <= dj.size()) ? dj.get(dayNo - 1) : "";  // oldJson：该天的上一版 JSON（越界则空串）
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("你是行程定稿Agent。上一版第 ").append(dayNo).append(" 天行程经独立审查发现问题，本次【只修正第 ")  // 开头声明：只修正第 N 天
                .append(dayNo).append(" 天】（共 ").append(req == null ? "" : req.getDays()).append(" 天），禁止动其它天。\n\n");  // 续：共 M 天，禁止动其它天
        sb.append("【审查反馈】").append(feedback).append("\n\n");  // 附审查反馈
        sb.append("【上一版第").append(dayNo).append("天JSON（修改基准：只修订反馈指出的问题，未涉及的安排保持原样，")  // 附上一版该天 JSON（修改基准：只修反馈指出的问题）
                .append("禁止无中生有全盘重排）】\n【行程JSON】\n").append(oldJson).append("\n【行程JSON结束】\n");  // 续：未涉及的安排保持原样，禁止全盘重排
        // ★路线衔接数据（交通类 REVISE 补查后更新；整段注入不按天匹配——路线数据格式不稳定，模型自行找相关行）
        String route = state.getRouteResult();  // 取路线结果
        if (route != null && !route.isBlank()) {  // 有路线
            sb.append("\n【真实路线衔接数据（主管已查/补查，供参考移动方式/车程/途经；只采用与当天相关的行，")  // 说明这是主管查/补查的真实衔接，只采用与当天相关的行
                    .append("其余天数据忽略）】\n").append(clip(route, 3000)).append("\n");  // 附路线文本（clip 3000）
        }  // if 结束
        sb.append("\n【跨天连续铁律】今天的出发地与住宿地以上一版为准（上版开头的出发地与末尾住宿 = 真实链条）：\n")  // 跨天连续铁律：今天的出发地与住宿地以上一版为准
                .append("① 不得把今天出发点改到上版之外的城镇（防\"昨天在城北、今天起点在城南\"）；\n")  // ① 不得把今天出发点改到上版之外的城镇（防“昨天在城北、今天起点在城南”）
                .append("② 住宿地尽量保持上版不变；确因反馈必须更换住宿区域时，只能换到当天景点所在城镇内，")  // ② 住宿地尽量不变；必须换时只能换到当天景点所在城镇内
                .append("并在当天末尾注明\"次日将从 <新住宿地> 出发\"；\n")  // 续：并在当天末尾注明“次日将从新住宿地出发”
                .append("③ 长途移动（车程超 3 小时）必须在时间线中写明出发时间与车程，不得与景点时间矛盾。\n");  // ③ 长途移动（>3h）必须写明出发时间与车程，不得与景点时间矛盾
        sb.append("\n输出要求：按系统规则输出【行程JSON】块，sections 只含第 " + dayNo  // 输出要求：按系统规则输出【行程JSON】块，sections 只含第 N 天
                + " 天的 timeline 与 stay 区块（title 用「第" + dayNo + "天」），禁止输出其它天；"  // 续：title 用「第N天」，禁止输出其它天
                + "当天所有条目 place 文本不得重复，cat=景点的条目不超过5个，概要不超过60字，JSON 完整优先、宁短勿长。");  // 续：当天 place 不重复、景点条目 ≤5 个、概要 ≤60 字，JSON 完整优先
        return sb.toString().trim();  // 返回 trim 后的任务
    }  // 方法结束

    /**
     * 作用：判断单天修复的返回里是否混入了其他天（污染检测）
     * 参数：dp 解析后的返回；dayNo 目标天号
     * 返回值：true=混入其他天
     * 异常：无
     */
    private boolean dayReplyHasOtherDay(dto.TripPlanData dp, int dayNo) {  // dayReplyHasOtherDay 签名
        if (dp == null || dp.getSections() == null) return false;  // 空 → false
        for (dto.Section s : dp.getSections()) {  // 遍历区块
            String type = s.getType() == null ? "" : s.getType();  // 取类型
            String title = s.getTitle() == null ? "" : s.getTitle();  // 取标题
            if ("timeline".equals(type) && !title.contains("第" + dayNo + "天")) return true;  // 是 timeline 且标题不含“第N天” → 判定混入
        }  // for 结束
        return false;  // 没有混入
    }  // 方法结束

    /**
     * 作用：并行修复多天：每天各起一个子 Agent 任务，全部返回后合并
     * 参数：state 任务状态；style 方案风格；days 需要修复的天集合；feedback 反馈文本
     * 返回值：合并修复后的行程；全部失败返回 null
     * 异常：无（单天失败只保留原天）
     */
    private dto.TripPlanData repairDaysInParallel(TravelState state, String style,  // repairDaysInParallel 签名第 1 行
                                                  java.util.Set<Integer> days, String feedback) {  // 签名第 2 行：days、feedback
        java.util.List<String> dj = state.getDayPlanJson();  // 取按天 JSON
        TripRequest req = state.getRequest();  // 取需求
        if (dj == null || days == null || days.isEmpty()) return null;  // 参数无效返回 null
        final java.util.Map<Integer, String> newJsons = new java.util.concurrent.ConcurrentHashMap<>();  // newJsons：天号 → 新 JSON（并发安全）
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();  // futures：待等待的任务句柄
        EventStore.add(sessionId, "审查", "并行修复第 "  // 写事件：并行修复第 …
                + days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"))  // 续：天号拼成顿号分隔
                + " 天（同时发起，缩短总耗时）...");  // 续：同时发起，缩短总耗时
        for (int d : days) {  // 遍历要修的天
            final int dd = d;  // dd：当前的天的副本（lambda 要用 final）
            futures.add(PARALLEL_POOL.submit(() -> {  // 往并行池提交一个修复任务
                try {  // try
                    // ★不再嵌套 submit：本任务已在线程池里，再向内层提交会在池满时自锁
                    //（外层占线程 + 内层等线程）。直接调用即可。
                    String reply = remoteTool.callTripPlannerAgent(buildDayRepairTask(state, dd, feedback));  // 调行程 Agent，任务由 buildDayRepairTask 生成（只改这一天）
                    dto.TripPlanData dp = parseDayReply(reply, dd, "天级重编");  // parseDayReply：解析单天回复
                    if (dp == null) return;  // 解析失败就返回（该天保留原样）
                    if (dayReplyHasOtherDay(dp, dd)) {  // 越权检查：回复里混入了别的天
                        EventStore.add(sessionId, "审查", "第" + dd + "天重编越权输出其它天，丢弃该天结果");  // 写事件：第 N 天重编越权输出其它天，丢弃
                        return;  // 返回（不采纳）
                    }  // if 结束
                    newJsons.put(dd, utils.JsonUtils.toJson(dp));  // 把新 JSON 放进结果表（并发 Map）
                } catch (CancelledException ce) {  // 捕获用户中断
                    throw ce; // 用户中断要向上传播  // 向上抛（中断必须传播）
                } catch (Throwable t) {  // 捕获其他异常
                    log.warn("第{}天并行修复异常: {}", dd, t.toString());  // warn 日志：第 N 天并行修复异常
                }  // catch 结束
            }));  // 任务 lambda 结束
        }  // for 结束
        // 统一等待（全部完成或各自 240s 预算耗尽）
        for (java.util.concurrent.Future<?> f : futures) {  // 遍历任务句柄
            try {  // try
                f.get(300, java.util.concurrent.TimeUnit.SECONDS);  // 最多等 300 秒
            } catch (Exception ignored) {  // 单天失败/超时不影响其它天
                // 单天失败不影响其它天；越权/异常已在任务内记录
            }  // catch 结束
        }  // for 结束
        if (newJsons.isEmpty()) return null;  // 一个都没成功 → 返回 null（调用方保留上一版）
        // 合并（串行写 state，避免并发改写）
        for (java.util.Map.Entry<Integer, String> e : newJsons.entrySet()) {  // 遍历成功的天
            if (e.getKey() >= 1 && e.getKey() <= dj.size()) dj.set(e.getKey() - 1, e.getValue());  // 写回对应的按天槽位（下标 = 天号-1）
        }  // for 结束
        String text = assembleDayPlanText(state, req);  // assembleDayPlanText：把按天 JSON 拼成整份行程文本
        if (text == null || text.isBlank()) return null;  // 拼装失败返回 null
        state.setPlanResult(text);  // 写回 state.planResult
        dto.TripPlanData whole = utils.JsonUtils.extractAndParse(  // 整份解析回 TripPlanData
                utils.CandidateUtil.extractPlanBlock(text), dto.TripPlanData.class);  // 续行
        EventStore.add(sessionId, "审查", "并行修复完成（成功 "  // 写事件：并行修复完成（成功 x/y 天），整单已重拼
                + newJsons.size() + "/" + days.size() + " 天），整单已重拼");  // 续行
        return whole;  // 返回整份行程
    }  // 方法结束

    /**
     * 作用：让行程子 Agent 只重排某一天，并把结果替换回整份行程
     * 参数：state 任务状态；style 方案风格；feedback 反馈文本；dayNo 目标天号
     * 返回值：修复后的整份行程；失败返回 null
     * 异常：无
     */
    private dto.TripPlanData repairDayByAgent(TravelState state, String style, String feedback, int dayNo) {  // repairDayByAgent 签名
        java.util.List<String> dj = state.getDayPlanJson();  // 取按天 JSON
        if (dj == null || dayNo < 1 || dayNo > dj.size()) return null;  // 越界返回 null
        TripRequest req = state.getRequest();  // 取需求
        EventStore.add(sessionId, "审查", "第" + dayNo + "天按天重编（单天短任务，防整单长输出截断）...");  // 写事件：第 N 天按天重编（单天短任务，防整单长输出截断）
        String reply = waitBudget(PARALLEL_POOL.submit(() ->  // waitBudget：带预算等待的单天重编任务，
                remoteTool.callTripPlannerAgent(buildDayRepairTask(state, dayNo, feedback))),  // 续：任务由 buildDayRepairTask 生成
                "行程Agent·第" + dayNo + "天(天级重编)", 240);  // 续：标签“行程Agent·第N天”，预算 240 秒
        dto.TripPlanData dp = parseDayReply(reply, dayNo, "天级重编");  // 解析单天回复
        if (dp == null) return null;  // 解析失败返回 null
        // 校验该天只含第 dayNo 天 timeline（防越权）
        if (dayReplyHasOtherDay(dp, dayNo)) {  // 混入其它天
            EventStore.add(sessionId, "审查", "天级重编越权输出其它天，丢弃重编结果");  // 写事件：越权输出，丢弃重编结果
            return null;  // 返回 null
        }  // if 结束
        dj.set(dayNo - 1, utils.JsonUtils.toJson(dp));  // 写回按天槽位
        String text = assembleDayPlanText(state, req);  // 重新拼整份文本
        if (text == null || text.isBlank()) return null;  // 拼装失败返回 null
        state.setPlanResult(text);  // 写回 state
        dto.TripPlanData whole = utils.JsonUtils.extractAndParse(  // 整份解析回 TripPlanData
                utils.CandidateUtil.extractPlanBlock(text), dto.TripPlanData.class);  // 续行
        EventStore.add(sessionId, "审查", "第" + dayNo + "天天级重编完成，整单已重拼");  // 写事件：第 N 天天级重编完成，整单已重拼
        return whole;  // 返回整份行程
    }  // 方法结束

    /**
     * 作用：让行程子 Agent 整体重排（不限定某天），并把结果替换回行程
     * 参数：state 任务状态；style 方案风格；feedback 反馈文本；prev 上一版行程
     * 返回值：重排后的行程；失败返回 null
     * 异常：无
     */
    private TripPlanData repairPlanByAgent(TravelState state, String style, String feedback, TripPlanData prev) {  // repairPlanByAgent 签名
        EventStore.add(sessionId, "审查", "带审查反馈重调行程Agent重编（候选已就绪，局部修正）...");  // 写事件：带审查反馈重调行程 Agent 重编（候选已就绪，局部修正）
        String task = finalPlanTask(state, style, feedback,  // 构造任务：带 style 与 feedback，
                prev == null ? null : trimReviseBaseline(prev, feedback));  // 续：修改基准用 trimReviseBaseline 裁剪过（减少上下文噪声）
        String reply = await(PARALLEL_POOL.submit(() ->  // 调行程 Agent，
                remoteTool.callTripPlannerAgent(task)), "行程Agent(审查重编)");  // 续：await 等待，标签“行程Agent(审查重编)”
        if (reply == null || reply.isBlank()) return null;  // 空回复返回 null
        String block = utils.CandidateUtil.extractPlanBlock(reply);  // 抽【行程JSON】块
        if (block.isBlank()) {  // 没有块
            EventStore.add(sessionId, "行程原文", "（REVISE重编·无JSON块，行程Agent回复全文）\n" + reply);  // 把回复原文存档（便于排查为什么没带块）
            return null;  // 返回 null
        }  // if 结束
        TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);  // 解析成 TripPlanData
        if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) {  // 解析失败或没有区块
            EventStore.add(sessionId, "行程原文", "（REVISE重编·JSON块解析失败，块原文）\n" + block);  // 存档块原文
            EventStore.add(sessionId, "审查", "重编未产出结构化终版JSON，保留上一版");  // 写事件：重编未产出结构化终版 JSON，保留上一版
            return null;  // 返回 null
        }  // if 结束
        // ★天数硬校验（2026-09-06）：整单重编模型可能把"补最后一天返程/收尾"误解成加天数（实测 3 日游被扩成 5 天）——
        //   天数必须与用户要求一致，不符即作废保留上一版，绝不交付错误天数的行程
        TripRequest req0 = state == null ? null : state.getRequest();  // 取需求
        int expectDays = req0 == null ? 0 : req0.getDays();  // 期望天数
        if (expectDays > 0) {  // 有期望天数
            int got = 0;  // got：实际 timeline 区块数
            for (Section s : plan.getSections()) {  // 遍历区块
                if (s != null && "timeline".equals(s.getType())) got++;  // 是 timeline 就计数
            }  // for 结束
            if (got != expectDays) {  // 数量不符
                EventStore.add(sessionId, "审查", "整单重编天数不符（应为 " + expectDays  // 写事件：整单重编天数不符（应 N 天，模型输出 M 天），作废保留上一版
                        + " 天，模型输出 " + got + " 天），作废保留上一版");  // 续行
                return null;  // 返回 null
            }  // if 结束
        }  // if 结束
        normalizePlan(state, plan);  // normalizePlan：归一化
        return plan;  // 返回重排后的行程
    }  // 方法结束

    /**
     * 作用：为候选景点补充坐标（缺坐标的按地名反查），返回补全成功的条数
     * 参数：cands 候选数据（原地修改）；dest 目的地城市
     * 返回值：补全成功的条数
     * 异常：无（查不到坐标的条目保留原样）
     */
    private int enrichSightLocations(java.util.Map<String, Object> cands, String dest) {  // enrichSightLocations 签名
        int fixed = 0;  // fixed：补全计数
        try {  // try
            java.util.List<java.util.Map<String, Object>> spots = candDomainList(cands, "attraction");  // 取景点域列表
            for (java.util.Map<String, Object> it : spots) {  // 遍历景点
                Object loc = it.get("location");  // 取 location
                if (loc != null && !String.valueOf(loc).trim().isBlank()) continue;  // 已有坐标就跳过
                String name = str(it.get("name")).trim();  // 取名称（去空格）
                if (name.isBlank()) continue;  // 名字为空跳过
                String city = dest == null ? "" : dest.trim();  // city：目的地城市
                try {  // try：单个补查失败不影响整体
                    String qs = "keywords=" + java.net.URLEncoder.encode(name, "UTF-8")  // 拼查询串：keywords=景点名
                            + "&city=" + java.net.URLEncoder.encode(city, "UTF-8")  // ＋ city
                            + "&output=json&key=" + utils.ExternalServices.amapKey() + "&offset=1&extensions=base";  // ＋ output/key/offset=1
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(  // 打开高德 place/text 连接
                            utils.ExternalServices.amapPlaceSearchUrl(qs)).openConnection();  // 续行
                    c.setConnectTimeout(8000);  // 连接超时 8 秒
                    c.setReadTimeout(8000);  // 读超时 8 秒
                    String body = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应
                    com.fasterxml.jackson.databind.JsonNode node = utils.JsonUtils.parse(body, com.fasterxml.jackson.databind.JsonNode.class);  // 解析 JSON
                    com.fasterxml.jackson.databind.JsonNode pois = node == null ? null : node.path("pois");  // 取 pois
                    if (pois != null && pois.isArray() && pois.size() > 0) {  // 有结果
                        String l = pois.get(0).path("location").asText("");  // 取第一个 POI 的 location
                        if (l != null && !l.isBlank()) {  // 非空
                            it.put("location", l.trim());  // 写回 location
                            fixed++;  // 计数 +1
                        }  // if 结束
                    }  // if 结束
                } catch (Exception ignored) {  // 捕获异常
                    // 单点失败静默（不阻塞整体）
                }  // catch 结束
                try { Thread.sleep(350); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }  // 限速：每个景点后 sleep 350ms（高德 QPS 3）
            }  // for 结束
        } catch (Exception e) {  // 外层异常
            log.warn("景点坐标补齐异常(忽略): {}", e.getMessage());  // warn 日志
        }  // catch 结束
        return fixed;  // 返回补全条数
    }  // 方法结束

    /**
     * 作用：裁剪修改基线：只保留反馈涉及的天与跨天锚点，减少重排时的上下文噪声
     * 参数：prev 当前行程；feedback 反馈文本
     * 返回值：裁剪后的行程 JSON 文本
     * 异常：无
     */
    private String trimReviseBaseline(TripPlanData prev, String feedback) {  // trimReviseBaseline 签名
        String full = utils.JsonUtils.toJson(prev);  // 序列化整份行程
        if (full == null) return null;  // 序列化失败返回 null
        try {  // try
            com.fasterxml.jackson.databind.JsonNode root = utils.JsonUtils.parse(full, com.fasterxml.jackson.databind.JsonNode.class);  // 解析成 JsonNode
            if (root == null || !root.isObject()) return full;  // 不是对象返回原 JSON
            java.util.Set<String> affected = new java.util.HashSet<>();  // affected：反馈涉及的天号（字符串集合）
            java.util.regex.Matcher m = java.util.regex.Pattern  // 正则：第N天 / dayN
                    .compile("第\\s*(\\d+)\\s*天|day\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)  // 续
                    .matcher(feedback == null ? "" : feedback);  // 编译（忽略大小写）
            while (m.find()) {  // 逐处匹配
                String g = m.group(1) != null ? m.group(1) : m.group(2);  // 取组
                if (g != null) affected.add(g);  // 加入集合
            }  // while 结束
            if (affected.isEmpty()) return full;  // 没提到具体天 → 返回完整基线（整体性问题要全量上下文）
            com.fasterxml.jackson.databind.JsonNode secs = root.get("sections");  // 取 sections
            if (secs == null || !secs.isArray()) return full;  // 不是数组返回完整
            java.util.regex.Pattern dayPat = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*天");  // dayPat：第N天
            for (com.fasterxml.jackson.databind.JsonNode sec : secs) {  // 遍历区块
                if (!"timeline".equals(sec.path("type").asText(""))) continue;  // 非 timeline 跳过
                java.util.regex.Matcher dm = dayPat.matcher(sec.path("title").asText(""));  // 从标题匹配天号
                if (!dm.find()) continue;  // 没有天号跳过
                if (affected.contains(dm.group(1))) continue; // 涉及天：全字段原样  // 是涉及的天 → 全字段保留（continue）
                com.fasterxml.jackson.databind.JsonNode items = sec.get("items");  // 取 items
                if (items == null || !items.isArray()) continue;  // 不是数组跳过
                for (com.fasterxml.jackson.databind.JsonNode it : items) {  // 遍历条目
                    if (it.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) it).remove("note");  // 删除 note 字段（非涉及天的备注是噪声）
                }  // for 结束
            }  // for 结束
            return utils.JsonUtils.toJson(root);  // 返回裁剪后的 JSON
        } catch (Exception e) {  // 异常
            return full;  // 返回完整 JSON
        }  // catch 结束
    }  // 方法结束

    /** 从路线Agent回复提取【路线JSON】块解析出 totalKm/waypoints/routeCount（失败返回 null，退回旧正则提取）。
     *  ★用 Map 接收（非 DTO），避免 Jackson 默认 FAIL_ON_UNKNOWN_PROPERTIES 因路线Agent多写字段而整体失败。 */
    @SuppressWarnings("unchecked")  // 抑制泛型警告
    /**
     * 作用：按总里程给出天数建议（天数明显排不开时提示并给替代方案）
     * 参数：routeResult 路线原文；req 需求对象
     * 返回值：无
     * 异常：无
     */
    private void suggestDaysByDistance(String routeResult, TripRequest req) {  // suggestDaysByDistance 签名
        if (req == null || req.getDays() < 1) return;  // 需求无效返回
        int totalKm = extractTotalDistance(routeResult);  // 提往返总里程
        if (totalKm <= 0) return;  // 里程无效返回
        String type = req.getTravelType() == null ? "" : req.getTravelType().trim().toUpperCase();  // 出行类型大写
        int dailyKm = "CROSS_CITY".equals(type) ? 500 : 300; // 跨城高速日均500km，自驾/环线复杂路况日均300km  // 日均里程：跨城高速 500km，自驾/环线 300km
        // totalKm 现为【往返闭环】口径（routeTask 已要求含返程，见【路线JSON】口径铁律），不再 ×2
        int suggestDays = (int) Math.ceil((double) totalKm / dailyKm);  // 建议天数 = 里程 ÷ 日均（向上取整）
        if (suggestDays > req.getDays()) {  // 建议天数 > 用户天数（排不开）
            EventStore.add(sessionId, "解析", "⚠ 天数偏紧：往返约 " + totalKm + " km（含返程），按日均 " + dailyKm  // 写事件：⚠ 天数偏紧——往返约 N km，按日均 M km 需约 K 天，当前仅 D 天
                    + " km 需约 " + suggestDays + " 天，当前仅 " + req.getDays() + " 天。"  // 续
                    + "该目的地往返里程由主干路线决定、缩短空间有限（重调缩不动即停）。"  // 续：该目的地往返里程由主干路线决定、缩短空间有限（重调缩不动即停）
                    + "建议：加到 " + suggestDays + " 天，或改走更近的替代路线（见【替代路线】方案）；已如实标注，行程按现有天数编排。");  // 续：建议加到 K 天或改走更近的替代路线；已如实标注，行程按现有天数编排
        }  // if 结束
    }  // 方法结束

    /** 从路线结果提取多条候选路线概要（格式见 routeTask："★候选路线一：途经城市：A市,B市..."），
     *  供行程Agent择优选择主线；提不到时返回空串（行程Agent按默认 waypoints 编排）。 */
    /**
     * 作用：把路线原文拆成按段的路线候选行（供逐天落地时引用）
     * 参数：routeResult 路线原文
     * 返回值：路线行列表
     * 异常：无
     */
    private java.util.List<String> routeCandidateLines(String routeResult) {  // routeCandidateLines 签名
        java.util.List<String> out = new java.util.ArrayList<>();  // 结果列表
        String block = extractRouteCandidates(routeResult);  // 先抽候选路线要点
        for (String line : block.split("\n")) {  // 按行拆分
            String t = line.trim();  // 去空格
            if (!t.isBlank()) out.add(t);  // 非空才加入
        }  // for 结束
        return out;  // 返回
    }  // 方法结束
    /**
     * 作用：从路线原文中抽取候选路线要点（里程/耗时/停靠）
     * 参数：routeResult 路线原文
     * 返回值：路线要点文本
     * 异常：无
     */

    private String extractRouteCandidates(String routeResult) {  // extractRouteCandidates 签名
        if (routeResult == null || routeResult.isBlank()) return "";  // 空返回空串
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(  // 正则：候选路线N：…途经城市：xxx
                "★?\\s*候选路线[一二三四1234]+[：:].*?途经城市[：:]\\s*([^\\n\\r]+)");  // 续
        java.util.regex.Matcher m = p.matcher(routeResult);  // 匹配器
        int idx = 1;  // 序号从 1 开始
        while (m.find() && idx <= 3) {  // 最多 3 条
            String cities = m.group(1).trim();  // 取途经城市
            if (!cities.isBlank()) {  // 非空
                sb.append("候选路线").append(idx).append("：途经城市 ").append(cities).append("\n");  // 按“候选路线N：途经城市 xxx”输出
                idx++;  // 序号 +1
            }  // if 结束
        }  // while 结束
        return sb.toString();  // 返回要点
    }  // 方法结束

    /**
     * 作用：从行程原文中抽取景点清单（限数量）
     * 参数：planResult 行程原文；dest 目的地
     * 返回值：景点清单文本；提取不到返回空串
     * 异常：无
     */
    private String extractSights(String planResult, String dest) {  // extractSights 签名
        if (planResult == null || planResult.isBlank()) return "";  // 空返回空串
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();  // seen：去重保序集合
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则：2-8 个汉字 + 景区/公园/古镇/山/湖等后缀
                .compile("[\u4e00-\u9fa5]{2,8}?(?:景区|公园|古镇|山庄|博物馆|街|塔|山|湾|洞|寺|宫|观景台|馆|广场|坊|陵|岛|湖|院|园|桥|河)")  // 续
                .matcher(planResult);  // 续
        while (m.find() && seen.size() < 5) {  // 最多 5 个
            String name = m.group();  // 取命中的名字
            if (name.equals(dest)) continue; // 排除纯城市名  // 排除纯城市名
            seen.add(name);  // 加入集合
        }  // while 结束
        return String.join(",", seen);  // 拼成逗号分隔
    }  // 方法结束

    /**
     * 作用：从两路专家原文中抠出天气段落原文
     * 参数：combined 路线与行程原文拼接文本
     * 返回值：天气原文；无天气返回空串
     * 异常：无
     */
    private String extractWeatherRaw(String combined) {  // extractWeatherRaw 签名
        if (combined == null || combined.isBlank()) return null;  // 空返回 null
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则：【…天气…】标题后的 2-120 字符
                .compile("【[^】]*天气[^】]*】\\s*\\n([\\s\\S]{2,120}?)\\n")  // 续
                .matcher(combined);  // 续
        if (m.find()) {  // 匹配到
            return m.group(1).trim();  // 返回该段（trim）
        }  // if 结束
        // 兜底：提取 "天气：" 后最近一行
        java.util.regex.Matcher m2 = java.util.regex.Pattern  // 正则：天气后跟 2-80 字符
                .compile("天气[\\s:：]([^\\n\\r]{2,80})")  // 续
                .matcher(combined);  // 匹配器
        return m2.find() ? m2.group(1).trim() : null;  // 匹配到返回，否则 null
    }  // 方法结束

    /**
     * 作用：偏好分流：让模型把用户文本偏好与沉淀偏好分成行程/路线/聚合三类并写回需求对象
     * 参数：req 需求对象（原地补全 tripPref/routePref）
     * 返回值：无
     * 异常：无（模型失败保留原值）
     */
    private void classifyPreference(TripRequest req) {  // classifyPreference 签名
        utils.SafeModel.setStage("偏好分类"); // ★调用链：该 LLM span 说明标注阶段  // setStage：标记“偏好分类”
        String sys = AgentPrompts.preferenceClassifier();  // 取分类提示词
        // 个人中心偏好并入分类输入
        String prefInput = req.getPreference();  // prefInput：用户文本偏好
        if (req.getPreferences() != null && !req.getPreferences().isEmpty()) {  // 有沉淀偏好
            prefInput = (prefInput == null || prefInput.isBlank() ? "" : prefInput + "；")  // 拼成“文本偏好；个人中心设置：…”
                    + "个人中心设置：" + req.getPreferences().toPromptText();  // 续行
        }  // if 结束
        String out = callModelJson(sys, "用户偏好：" + prefInput);  // 调模型做分类（要求 JSON）
        try {  // try
            var node = JsonUtils.extractAndParse(out, java.util.Map.class);  // 解析成 Map
            if (node != null) {  // 解析成功
                req.setTripPref(str(node.get("tripPref")));  // 写回行程偏好
                req.setRoutePref(str(node.get("routePref")));  // 写回路线偏好
                req.setAggPref(str(node.get("aggPref")));  // 写回聚合偏好
                // ★调用链：分类结果摘要回填
                StringBuilder sum = new StringBuilder("行程偏好:")  // 拼“行程偏好:…”，
                        .append(req.getTripPref() == null || req.getTripPref().isBlank() ? "无" : req.getTripPref());  // 续：空则写“无”
                if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) {  // 有路线偏好
                    sum.append(" 路线偏好:").append(req.getRoutePref());  // 续：路线偏好
                }  // if 结束
                utils.CallTrace.appendLastLlmDetail(sessionId, sum.toString());  // 写回最近一条 LLM span
                return;  // 返回
            }  // if 结束
        } catch (Exception ignored) { }  // 解析异常忽略
        // 解析失败兜底：全部留给聚合
        req.setAggPref(req.getPreference());  // 把原始偏好当聚合偏好
    }  // 方法结束

    /**
     * 作用：安全转字符串
     * 参数：o 任意对象
     * 返回值：对象字符串；null 返回空串
     * 异常：无
     */
    private String str(Object o) {  // str 签名
        return o == null ? "" : o.toString();  // null 归一空串
    }  // 方法结束

    /**
     * 作用：检查用户是否已请求中断，是则抛出类型化取消异常
     * 参数：无
     * 返回值：无
     * 异常：CancelledException 用户已请求中断
     */
    private void checkCancel() {  // checkCancel 签名
        if (EventStore.isCancelRequested(sessionId)) {  // 取消标志已置位
            throw new CancelledException();  // 抛 CancelledException（上层记 CANCELLED）
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：等待子 Agent 结果：按超时预算轮询（持续检查取消），超时取消后台任务并返回空串
     * 参数：future 子 Agent 调用的 Future；label 中文标签（事件与日志）
     * 返回值：子 Agent 返回文本；超时或失败返回空串
     * 异常：CancelledException 用户中断（同时取消后台任务）
     */
    private String await(Future<String> future, String label) {  // await 签名
        EventStore.add(sessionId, "调用工具", "调用「" + label + "」获取真实数据...");  // 写事件：调用「label」获取真实数据
        RemoteTimeout.configure(utils.NacosModelConfig.orchestrationConfig());  // 刷新 A2A 超时预算（可热更）
        Duration timeout = RemoteTimeout.timeout();  // 取当前预算
        long deadline = System.nanoTime() + timeout.toNanos();  // deadline：现在 + 预算
        try {  // try
            while (true) {  // 循环等待
                checkCancel(); // ★中断检查：阻塞等待期间持续轮询，不再等步骤边界  // checkCancel：阻塞等待期间持续轮询，不等步骤边界
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;  // 剩余毫秒
                if (remainMs <= 0) {  // 已到/超期
                    future.cancel(true);  // 取消后台任务（打断子 Agent 调用）
                    log.warn("{} 调用超时({}s)，已取消后台任务", label, RemoteTimeout.currentSeconds());  // warn 日志：调用超时，已取消后台任务
                    EventStore.add(sessionId, "Agent结果",  // 写事件：label ❌ 调用超时(Ns)
                            label + " ❌ 调用超时(" + RemoteTimeout.currentSeconds() + "s)");  // 续行
                    return "";  // 返回空串（上层按“该路数据缺失”继续）
                }  // if 结束
                try {  // try
                    String r = future.get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);  // 最多等 500ms 就返回，便于及时检查取消
                    return r == null ? "" : r;  // 拿到就返回（null 归一空串）
                } catch (ExecutionException ex) {  // 捕获包装异常
                    // ★解包：后台任务抛出的 CancelledException（用户中断）要从 Future 里取出来向上传播
                    if (ex.getCause() instanceof CancelledException) {  // 原因是用户中断
                        throw (CancelledException) ex.getCause();  // 取出并抛出 CancelledException
                    }  // 其他异常原样抛
                    throw ex;  // catch 结束
                } catch (TimeoutException e) {  // 捕获超时
                    // 本轮 500ms 内未完成，回到循环头重新检查中断
                }  // catch 结束
            }  // while 结束
        } catch (CancelledException e) {  // 捕获用户中断
            future.cancel(true); // ★用户中断：取消后台任务后向上传播  // 取消后台任务后向上传播
            EventStore.add(sessionId, "Agent结果", label + " ⏹ 已中断");  // 写事件：label ⏹ 已中断
            throw e;  // 抛出
        } catch (Exception e) {  // 捕获其他异常
            log.error("{} 调用失败: {}", label, e.getMessage());  // error 日志
            EventStore.add(sessionId, "Agent结果", label + " ❌ 调用失败(" + e.getMessage() + ")");  // 写事件：label ❌ 调用失败(原因)
            return "";  // 返回空串
        }  // catch 结束
    }  // 方法结束

    // ==================== ③ 专家产物与终版解析（★删聚合：无LLM二次整合环节） ====================

    /**
     * 作用：判断输入侧哪一路数据缺失，返回可读描述
     * 参数：routeResult 路线原文；planResult 行程原文
     * 返回值：缺失描述文本；齐全返回空串
     * 异常：无
     */
    private String missingInputs(String routeResult, String planResult) {  // missingInputs 签名
        boolean noRoute = routeResult == null || routeResult.isBlank();  // 路线是否为空
        boolean noPlan = planResult == null || planResult.isBlank();  // 行程是否为空
        if (noRoute && noPlan) return "路线与行程数据均缺失";  // 都缺
        if (noRoute) return "路线数据缺失";  // 缺路线
        if (noPlan) return "行程数据缺失";  // 缺行程
        return "";  // 齐全
    }  // 方法结束

    /**
     * 作用：清洗天气文本：剔除 LLM 输出里的 markdown 表格残留
     * 参数：w 天气原文，可为 null
     * 返回值：清洗后的天气文本
     * 异常：无
     */
    private String cleanWeather(String w) {  // cleanWeather 签名
        if (w == null) return null;  // null 直接返回
        String s = w.trim();  // trim
        if (s.matches("\\|.+\\|")) return ""; // 整行管道符表格结构 → 无有效天气内容  // 整行都是管道符表格结构 → 视为无有效天气
        return s.replaceAll("^\\s*\\|+\\s*|\\s*\\|+\\s*$", "").trim();  // 去掉首尾的管道符与空白
    }  // 方法结束

    /**
     * 作用：直连高德天气预报接口补真实天气（不依赖 LLM 重述，防编造）
     * 参数：city 目的城市；days 需要的天数（上限 7 天）
     * 返回值：天气文本；接口无数据返回空串
     * 异常：无（异常内部捕获）
     */
    private String fetchAmapWeather(String city, int days) {  // fetchAmapWeather 签名
        try {  // try
            String key = utils.ExternalServices.amapKey();  // 取高德 Key
            if (key == null || key.isBlank() || city == null || city.isBlank()) return null;  // Key/城市无效返回 null
            String clean = city.trim().replaceAll("\\d+\\s*[日天]|[一二三四五六七八九十]+\\s*[日天]|游$", "").trim();  // 清洗城市名（去掉“3日游”这类后缀）
            if (clean.isBlank()) return null;  // 清洗后为空返回 null
            Thread.sleep(350); // QPS 3 限速  // 限速：高德 QPS 3，先 sleep 350ms
            String qs = "city=" + java.net.URLEncoder.encode(clean, "UTF-8")  // 拼查询串：city + output=json + key
                    + "&output=json&key=" + key + "&extensions=all";  // 续：extensions=all（要预报）
            java.net.URL u = new java.net.URL("https://restapi.amap.com/v3/weather/weatherInfo?" + qs);  // 拼 weatherInfo 接口地址
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();  // 打开连接
            c.setConnectTimeout(5000);  // 连接超时 5 秒
            c.setReadTimeout(8000);  // 读超时 8 秒
            String b = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应
            java.util.Map<String, Object> root = utils.JsonUtils.parse(b, java.util.Map.class);  // 解析 JSON
            if (root == null) return null;  // 解析失败返回 null
            Object fc = root.get("forecasts");  // 取 forecasts
            if (!(fc instanceof java.util.List) || ((java.util.List<?>) fc).isEmpty()) return null;  // 不是非空列表返回 null
            Object castsObj = ((java.util.Map<?, ?>) ((java.util.List<?>) fc).get(0)).get("casts");  // 取 forecasts[0].casts（每天的预报）
            if (!(castsObj instanceof java.util.List)) return null;  // 不是列表返回 null
            StringBuilder sb = new StringBuilder();  // 输出缓冲
            int n = 0;  // 已输出天数
            for (Object co : (java.util.List<?>) castsObj) {  // 遍历每天的预报
                if (n >= days) break;  // 够天数就停
                java.util.Map<?, ?> cast = (java.util.Map<?, ?>) co;  // 单天预报节点
                Object dv = cast.get("date");  // 日期
                Object dy = cast.get("dayweather");  // 白天天气
                Object nt = cast.get("nightweather");  // 夜间天气
                Object dt = cast.get("daytemp");  // 白天温度
                Object ntt = cast.get("nighttemp");  // 夜间温度
                if (dv == null || dy == null) continue;  // 缺关键字段跳过
                String date = String.valueOf(dv);  // 日期字符串（yyyy-MM-dd）
                String md = date.length() >= 10  // 把日期转成“M月D日”
                        ? (Integer.parseInt(date.substring(5, 7)) + "月" + Integer.parseInt(date.substring(8, 10)) + "日")  // 续：取月与日部分转 int（去掉前导 0）
                        : date;  // 日期格式异常时原样用
                String w = String.valueOf(dy);  // 白天天气
                if (nt != null && !String.valueOf(nt).equals(w)) w = w + "转" + nt;  // 夜天不同就写成“白天转夜间”
                String temps = (ntt == null ? "" : String.valueOf(ntt) + "℃~") + (dt == null ? "" : String.valueOf(dt) + "℃");  // 温度区间：夜间~白天
                if (sb.length() > 0) sb.append("；");  // 不是第一天就加分隔符
                sb.append(md).append(" ").append(w).append(" ").append(temps);  // 拼一天：日期 天气 温度
                n++;  // 计数 +1
            }  // for 结束
            return sb.length() > 0 ? sb.toString() : null;  // 有内容返回，否则 null
        } catch (Exception e) {  // 异常
            return null;  // 返回 null（天气缺失不阻断）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：行程完整性检查：缺每日区块/住宿区块/标题/天数时返回缺项清单
     * 参数：plan 行程
     * 返回值：缺项描述；完整时返回空串
     * 异常：无
     */
    private String aggregateGap(TripPlanData plan) {  // aggregateGap 签名
        if (plan == null) return "整体为空";  // 整体为空
        java.util.List<String> gaps = new java.util.ArrayList<>();  // gaps：缺项列表
        if (plan.getSections() == null || plan.getSections().isEmpty()) {  // sections 为空
            gaps.add("sections(行程区块)为空");  // 记为缺 sections
        } else {  // 否则
            boolean hasTimeline = plan.getSections().stream()  // 是否有非空 timeline
                    .anyMatch(s -> "timeline".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty());  // 续：类型 timeline 且有条目
            if (!hasTimeline) gaps.add("缺少每日行程timeline区块");  // 没有就记缺 timeline
            boolean hasStay = plan.getSections().stream()  // 是否有非空 stay
                    .anyMatch(s -> "stay".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty());  // 续：类型 stay 且有条目
            if (!hasStay) gaps.add("缺少住宿餐饮(stay)区块");  // 没有就记缺 stay
        }  // if 结束
        if (plan.getTitle() == null || plan.getTitle().isBlank()) gaps.add("标题");  // 标题为空记缺
        if (plan.getDestination() == null || plan.getDestination().isBlank()) gaps.add("目的地");  // 目的地为空记缺
        if (plan.getDays() < 1) gaps.add("天数");  // 天数<1 记缺
        return String.join("、", gaps);  // 拼成顿号分隔
    }  // 方法结束

    /**
     * 作用：把候选数据渲染成注入提示词的区块（每域限条数，附证据与置信度）
     * 参数：state 任务状态
     * 返回值：候选区块文本
     * 异常：无
     */
    private String buildCandidatesSection(TravelState state) {  // buildCandidatesSection 签名
        if (state == null || state.getCandidatesJson().isBlank()) return "";  // 没有候选返回空串
        Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());  // 解析候选
        int perDomain = utils.CandidateUtil.candidateCountPerDomain(  // 每域条数（按天数算）
                state.getRequest() == null ? null : String.valueOf(state.getRequest().getDays()));  // 续
        String text = utils.CandidateUtil.toPromptText(cands, perDomain);  // 候选转提示词文本
        if (text.isBlank()) return "";  // 空返回
        String groupHint = utils.CandidateUtil.coordinateGroupHint(cands); // ★坐标聚类（P1b：真实坐标邻近分组）  // 坐标聚类分组提示（真实坐标邻近分组）
        if (groupHint.isBlank()) groupHint = utils.CandidateUtil.areaGroupHint(cands); // 坐标缺失回退文本分组  // 坐标缺失时回退文本分组
        return "\n\n【三域候选（结构化，已按需求筛选，含置信度）】\n" + text  // 拼【三域候选】区块标题
                + (groupHint.isBlank() ? "" : "\n\n" + groupHint)  // 续：有分组提示就附上
                + "\n（候选来自真实查询，行程安排优先从中选取；⚠标记项在 note 注明\"建议出发前再次确认\"）";  // 续：说明候选来自真实查询，优先从中选取；⚠ 标记项需再次确认
    }  // 方法结束

    /**
     * 作用：把评审问题转成可序列化快照（落库降级与展示用）
     * 参数：v 评审结论
     * 返回值：问题快照列表
     * 异常：无
     */
    private static java.util.List<Map<String, Object>> snapshotIssues(ReviewVerdict v) {  // snapshotIssues 签名
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();  // 结果列表
        if (v == null || v.getIssues() == null) return out;  // 空返回空列表
        for (int i = 0; i < v.getIssues().size() && i < 6; i++) {  // 最多取 6 条
            out.add(ReviseSummary.issueSnapshot(v.getIssues().get(i)));  // 转成快照
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /** 多方案风格定义（style key / 展示名 / 风格约束文案）：多方案生成与 Reviewer 重编共用，保证重跑不丢风格 */
    private static final String[][] PLAN_STYLES = {  // PLAN_STYLES 声明
            {"classic", "经典主线", "本次方案为「经典主线」：走最直接的主干路线（最短国道/高速主干），途经点取主干沿线，优先控制总里程与在途时间——里程最优、节奏紧凑。"},  // classic 经典主线：走主干路线，里程最优、节奏紧凑
            {"loop", "深度环线", "本次方案为「深度环线」：去程与返程走不同路线（不走回头路，环形串联），覆盖更多沿途观景点——里程略长但体验更丰富，适合时间充裕。"},  // loop 深度环线：去程返程不走回头路，覆盖更多观景点
            {"leisure", "轻松向", "本次方案为「轻松向」：以每日车程短为第一优先（尽量≤4h），减少远距离节点、增加停留时长，必要时舍弃最远打卡点——适合不想赶路的用户。"},  // leisure 轻松向：每日车程尽量 ≤4h，减少远距离节点
    };  // 数组结束

    /**
     * 作用：按里程缺口给出替代风格的方案标识（行程排不开时换玩法重心）
     * 参数：req 需求；needDays 按里程推算的所需天数
     * 返回值：风格标识数组（风格键 + 风格说明）
     * 异常：无
     */
    private String[] alternativeStyle(TripRequest req, int needDays) {  // alternativeStyle 签名
        return new String[]{"alternative", "替代路线",  // 返回 [alternative, 替代路线,
                "本次方案为「替代路线」：原目的地（" + req.getDestination() + "）往返里程需约 " + needDays  // 续：说明原目的地往返需约 N 天、超出用户 M 天，物理上无法舒适完成
                        + " 天、超出用户 " + req.getDays() + " 天，物理上无法舒适完成。"  // 续
                        + "请规划一条 " + req.getDays() + " 天内【真正可行】的替代路线（如改走更近的环线/缩短最远节点），"  // 续：请规划 M 天内真正可行的替代路线（改走更近的环线/缩短最远节点），
                        + "硬约束：每天在途≤6小时、在途>4小时必须有景点停靠、每天游玩4~8小时；"  // 续：硬约束：每天在途≤6h、>4h 必有景点停靠、每天游玩 4~8h，
                        + "并在方案开头用一句话说明：与原目的地的取舍（放弃了什么、换来了什么）。"};  // 续：开头一句话说明与原目的地的取舍]
    }  // 方法结束

    /**
     * 作用：判断是否需要走"收缩返工"回环（里程超天数要求）
     * 参数：req 需求
     * 返回值：true=需要收缩返工
     * 异常：无
     */
    private boolean needsReturnLoop(TripRequest req) {  // needsReturnLoop 签名
        if (req == null) return false;  // 需求为空 → false
        String origin = req.getOrigin();  // 取出发地
        if (origin == null || origin.isBlank()) return false;  // 没出发地 → false
        String t = req.getTravelType() == null ? "" : req.getTravelType().trim().toUpperCase();  // 出行类型大写
        if ("SELF_DRIVE".equals(t) || "LOOP".equals(t)) return true;  // 自驾/环线 → true
        String raw = req.getRaw() == null ? "" : req.getRaw();  // 取用户原话
        return raw.contains("往返") || raw.contains("返回") || raw.contains("来回") || raw.contains("回程");  // 含“往返/返回/来回/回程” → true
    }  // 方法结束

    /**
     * 作用：构造收缩返工任务：要求按天数裁剪停靠点而非延长天数
     * 参数：req 需求；needDays 按里程推算的所需天数
     * 返回值：任务描述文本
     * 异常：无
     */
    private String alternativeTask(TripRequest req, int needDays) {  // alternativeTask 签名
        return req.toStateBrief() + "\n"  // 返回：状态摘要
                + "用户想去 " + req.getDestination() + "，从 " + req.getOrigin() + " 出发，行程 " + req.getDays() + " 天。"  // ＋ 用户想去 X，从 Y 出发，行程 N 天
                + "但该目的地往返里程需约 " + needDays + " 天，物理上无法在 " + req.getDays() + " 天内舒适完成。\n"  // ＋ 但该目的地往返需约 K 天，物理上无法在 N 天内舒适完成
                + "请给出一条 " + req.getDays() + " 天内【真正可行】的替代路线"  // ＋ 请给出一条 N 天内真正可行的替代路线
                + "（如改走更近的环线 / 缩短最远节点 / 替换为同方向更近的目的地），例如川西可把小环线作为稻城亚丁的替代。\n"  // ＋（改走更近的环线/缩短最远节点/替换同方向更近目的地），例如川西小环线替代稻城亚丁
                + "硬要求：①每天在途≤6小时、在途>4小时必须有景点停靠、每天游玩4~8小时；"  // ＋ 硬要求①每天在途≤6h、>4h 必有景点停靠、每天游玩 4~8h
                + "②用 query_route_unified（mode=driving）查真实里程，不凭记忆估算；"  // ＋ ②用 query_route_unified(mode=driving) 查真实里程，不凭记忆
                + "③说明与原目的地的取舍（放弃了什么、换来了什么）。\n"  // ＋ ③说明与原目的地的取舍
                + "最终回复：开头一行用【替代路线】标注 路线名 + 往返总里程，随后给逐日概要（简洁，不需完整行程）。";  // ＋ 最终回复：开头一行【替代路线】标注路线名 + 往返总里程，随后给逐日概要
    }  // 方法结束

    /** Reviewer 修正重跑上限（轮） */
    private static final int MAX_REVIEW_ROUNDS = 2;  // MAX_REVIEW_ROUNDS=2：评审最多 2 轮（改不动就停）

    /**
     * 作用：是否启用天骨架（切天并行）。
     * 取值：读配置 DAY_SKELETON，默认 true（启用）；设成 false 则跨城与市内都退回"整单一次生成"。
     * 为什么留这个开关：用于 A/B 对比"切天 vs 不切天"的耗时——其余变量（候选、审查、回捞、模型）完全一致，
     *   只有这一处不同，测出来的差值才是骨架本身的代价/收益。生产默认保持 true。
     */
    private static final boolean DAY_SKELETON = !"false".equalsIgnoreCase(utils.Config.get("DAY_SKELETON", "true"));

    /**
     * 作用：多方案编排主体：多套风格各跑一次完整细化链，失败的方案剔除
     * 参数：state 任务状态
     * 返回值：PlanSet 多方案集合
     * 异常：无
     */
    private PlanSet aggregateMulti(TravelState state) {  // aggregateMulti 签名
        TripRequest req = state.getRequest();  // 取需求
        String routeResult = state.getRouteResult();  // 取路线结果
        String planResult = state.getPlanResult();  // 取行程结果
        java.util.List<String> candLines = routeCandidateLines(state.getRouteResult());  // candLines：候选路线要点行（每套方案各用一条）
        String[][] styles = PLAN_STYLES;  // styles：三套风格定义
        // ★不可行兜底：原目的地物理上完不成时，第3套（轻松向）换成【替代路线】——给用户一个天数内真正可行的选项（占多方案一席）
        if (state.getInfeasibleDays() > 0) {  // 有不可行天数
            styles = new String[][]{PLAN_STYLES[0], PLAN_STYLES[1], alternativeStyle(req, state.getInfeasibleDays())};  // 第 3 套换成替代路线风格
            EventStore.add(sessionId, "定稿", "⚠ 原目的地（" + req.getDestination() + "）往返需约 " + state.getInfeasibleDays()  // 写事件：⚠ 原目的地往返需约 K 天 > 用户 N 天——第 3 套改为替代路线
                    + " 天 > 用户 " + req.getDays() + " 天——第3套改为【替代路线】（天数内可行），供对比选择");  // 续行
        }  // if 结束
        // 并行提交 3 套行程Agent 定稿（PARALLEL_POOL 已 4 线程；候选前置已共享，不重复查询）
        @SuppressWarnings("unchecked")  // 抑制泛型警告
        final String[][] useStyles = styles; // lambda 捕获需 effectively final（styles 可能被替代路线覆盖）  // useStyles：lambda 捕获需要 effectively final（styles 可能被替换）
        Future<TripPlanData>[] futures = new Future[3];  // futures：3 个任务句柄
        for (int i = 0; i < 3; i++) {  // 提交 3 套
            final int idx = i;  // idx：下标副本
            final String routeLine = (idx < candLines.size()) ? candLines.get(idx) : "";  // routeLine：第 i 条候选路线（不够则空串，代表方案走不同主线）
            futures[i] = PARALLEL_POOL.submit(() -> planOneStyle(state, useStyles[idx][2], routeLine));  // 提交 planOneStyle（风格约束 + 指定路线行）
        }  // for 结束
        PlanSet set = new PlanSet();  // 新建 PlanSet
        int successCount = 0;  // successCount：结构化成功的套数
        for (int i = 0; i < 3; i++) {  // 逐套收集结果
            TripPlanData plan = null;  // plan 先置 null
            try {  // try
                // ★中断感知：轮询等待，点中断立即生效（原 300s 一次性阻塞，中断要等跑完）
                // ★2026-09-05 上限=2次A2A尝试×单次预算（默认600s）：与 RemoteAgentTool 每attempt独立300s一致，否则future先超时把重试中的套放弃
                long deadline = System.nanoTime() + managerAgent.tool.RemoteTimeout.timeout().toNanos() * 2;  // deadline：现在 + 预算×2
                while (true) {  // 轮询
                    checkCancel();  // checkCancel：中断立即生效
                    long remainMs = (deadline - System.nanoTime()) / 1_000_000L;  // 剩余毫秒
                    if (remainMs <= 0) { plan = null; break; }  // 超期就放弃这一套（plan 保持 null）
                    try {  // try
                        plan = futures[i].get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);  // 最多等 500ms 就回来检查中断
                        break;  // 拿到就跳出
                    } catch (TimeoutException te) {  // 超时
                        // 回到循环头重新检查中断
                    }  // catch 结束
                }  // while 结束
            } catch (CancelledException ce) {  // 捕获用户中断
                for (Future<TripPlanData> f : futures) f.cancel(true); // ★用户中断：取消全部并行方案  // 取消全部并行方案
                throw ce;  // 向上抛
            } catch (Exception e) {  // 捕获其他异常
                log.error("方案「{}」生成失败: {}", styles[i][1], e.getMessage());  // error 日志：方案「名字」生成失败
            }  // catch 结束
            if (plan == null) {  // 这一套没产出
                plan = fallbackPlan(req, routeResult, planResult); // 该套失败退回 markdown 兜底  // 退回 markdown 兜底（保证三套都有内容可比）
                plan.setTitle(req.getDestination() + req.getDays() + "日游（" + styles[i][1] + "）");  // 标题带上风格名
            }  // if 结束
            set.getPlans().add(plan);  // 加入方案列表
            set.getStyles().add(styles[i][0]);  // 记录风格 key
            set.getSlogans().add(styles[i][1]);  // 记录展示名（口号）
            if (plan.getSections() != null && !plan.getSections().isEmpty()) {  // 有结构化区块
                successCount++;  // 成功套数 +1
            }  // if 结束
        }  // for 结束
        // 全部结构化失败则返回 null（调用方兜底为单方案）
        return successCount > 0 ? set : null;  // 有成功就返回集合
    }  // 方法结束

    /**
     * 作用：单套方案生成（不含指定路线行）
     * 参数：state 任务状态；style 风格标识
     * 返回值：该风格的结构化行程；失败返回 null
     * 异常：无
     */
    private TripPlanData planOneStyle(TravelState state, String style) {  // planOneStyle 两参签名
        return planOneStyle(state, style, "");  // 委托三参版本，routeLine 空串
    }  // 方法结束
    /**
     * 作用：单套方案生成（带路线行，要求逐天落实里程与时段）
     * 参数：state 任务状态；style 风格标识；routeLine 路线要点行
     * 返回值：该风格的结构化行程；失败返回 null
     * 异常：无
     */

    private TripPlanData planOneStyle(TravelState state, String style, String routeLine) {  // planOneStyle 三参签名
        try {  // try
            String task = finalPlanTask(state, style, null, null, routeLine);  // 用 finalPlanTask 生成任务（带风格与指定路线）
            String reply = remoteTool.callTripPlannerAgent(task); // 同步阻塞（A2A 自身超时控制）  // 同步调用行程 Agent（A2A 自身超时控制）
            if (reply == null || reply.isBlank()) return null;  // 空回复返回 null
            String block = utils.CandidateUtil.extractPlanBlock(reply);  // 抽【行程JSON】块
            if (block.isBlank()) {  // 没有块
                EventStore.add(sessionId, "行程原文", "（多方案单套·无JSON块，行程Agent回复全文）\n" + reply);  // 把回复原文存档（排查为什么没带块）
                return null;  // 返回 null
            }  // if 结束
            TripPlanData plan = JsonUtils.extractAndParse(block, TripPlanData.class);  // 解析成 TripPlanData
            if (plan == null || plan.getSections() == null || plan.getSections().isEmpty()) return null;  // 解析失败或无区块返回 null
            normalizePlan(state, plan);  // normalizePlan：归一化
            return plan;  // 返回该套方案
        } catch (Exception e) {  // 异常
            log.warn("方案「{}」行程Agent调用异常: {}", style, e.getMessage());  // warn 日志：方案「风格」调用异常
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：最终兜底：结构化失败时用路线与行程原文拼成 markdown 降级交付（明确标注降级）
     * 参数：req 需求；routeResult 路线原文；planResult 行程原文
     * 返回值：只带 markdown 字段的行程对象
     * 异常：无
     */
    private TripPlanData fallbackPlan(TripRequest req, String routeResult, String planResult) {  // fallbackPlan 签名
        TripPlanData plan = new TripPlanData();  // 新建行程对象
        plan.setTitle(req.getDestination() + req.getDays() + "日游计划");  // 标题：目的地+天数+日游计划
        plan.setDestination(req.getDestination());  // 目的地
        plan.setDays(req.getDays());  // 天数
        plan.setMarkdown(joinRaw(req, routeResult, planResult));  // markdown = 路线与行程原文拼接
        log.warn("行程终版缺失，退回 markdown 原文兜底");  // warn 日志：终版缺失，退回原文兜底
        return plan;  // 返回
    }  // 方法结束

    /**
     * 作用：把路线与行程原文拼成一份 markdown 文档（兜底交付用）
     * 参数：req 需求；routeResult 路线原文；planResult 行程原文
     * 返回值：markdown 文本
     * 异常：无
     */
    private String joinRaw(TripRequest req, String routeResult, String planResult) {  // joinRaw 签名
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("# ").append(req.getDestination()).append(req.getDays()).append("日游计划\n\n");  // 一级标题：目的地+天数+日游计划
        if (!routeResult.isBlank()) {  // 有路线原文
            sb.append("## 路线\n\n").append(routeResult).append("\n\n");  // ## 路线 + 原文
        }  // if 结束
        String readable = planResult == null ? "" : utils.CandidateUtil.stripPlanBlock(planResult);  // readable：行程原文去掉 JSON 块
        if (!readable.isBlank()) {  // 有正文
            sb.append("## 行程\n\n").append(readable).append("\n");  // ## 行程 + 正文
        }  // if 结束
        return sb.toString().trim();  // 返回 trim 后的 markdown
    }  // 方法结束

    // ==================== 工具方法 ====================

    /**
     * 作用：裸 LLM 调用（要求 JSON 输出），统一走 LlmClient 并带上当前会话用于调用链归位
     * 参数：sysPrompt 系统提示词；userPrompt 用户提示词
     * 返回值：模型返回文本（可能为空串）
     * 异常：无（异常已在 LlmClient 内部捕获）
     */
    private String callModelJson(String sysPrompt, String userPrompt) {  // callModelJson 签名
        // 所有裸 LLM 调用统一走 LlmClient；先绑定会话上下文，让 LLM 调用链 span 归到本任务
        utils.SafeModel.setSessionId(sessionId);  // setSessionId
        return utils.LlmClient.chatJson(sysPrompt, userPrompt);  // 交给 LlmClient.chatJson（内部走 SafeModel：限流/熔断/重试）
    }  // 方法结束

    /**
     * 作用：从 ChatResponse 中拼接全部文本块
     * 参数：resp 模型响应
     * 返回值：文本；响应为空返回空串
     * 异常：无
     */
    private String textOf(ChatResponse resp) {  // textOf 签名
        if (resp == null) {  // 响应为空
            return "";  // 返回空串
        }  // if 结束
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        for (ContentBlock block : resp.getContent()) {  // 遍历内容块
            if (block instanceof TextBlock tb) {  // 是文本块
                sb.append(tb.getText());  // 追加文本
            }  // if 结束
        }  // for 结束
        return sb.toString();  // 返回
    }  // 方法结束

    /**
     * 作用：截断过长文本，保留指定上限
     * 参数：s 原文本；maxLen 最大长度
     * 返回值：截断后的文本；null 返回空串
     * 异常：无
     */
    private String clip(String s, int maxLen) {  // clip 签名
        if (s == null) return "";  // null 返回空串
        if (s.length() <= maxLen) return s;  // 没超长原样返回
        return s.substring(0, maxLen) + "\n…(数据过长已截断)…";  // 截断并加“…(数据过长已截断)…”标记
    }  // 方法结束

    /**
     * 作用：按默认上限截断文本
     * 参数：s 原文本
     * 返回值：截断后的文本；null 返回空串
     * 异常：无
     */
    private String clip(String s) {  // clip 单参签名
        return clip(s, 6000);  // 委托两参版本，上限 6000
    }  // 方法结束

    /**
     * 作用：把结构化行程渲染成 markdown（降级字段/详情页展示用）
     * 参数：plan 行程
     * 返回值：markdown 文本
     * 异常：无
     */
    private String renderMarkdown(TripPlanData plan) {  // renderMarkdown 签名
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("# ").append(plan.getTitle()).append("\n\n");  // 一级标题：行程标题
        if (!plan.getWeather().isBlank()) {  // 有天气
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");  // 加一行“**天气**：…”
        }  // if 结束
        for (Section sec : plan.getSections()) {  // 遍历区块
            sb.append("## ").append(sec.getTitle()).append("\n");  // 二级标题：区块标题
            for (SectionItem it : sec.getItems()) {  // 遍历区块内条目
                sb.append("- ");  // 每条以“- ”开头
                switch (sec.getType()) {  // 按区块类型渲染
                    case "timeline" -> sb.append("**").append(it.getTime()).append("** ")  // timeline：**时间** 地点 活动
                            .append(it.getPlace()).append(" ").append(it.getActivity());  // 续行
                    case "transport" -> sb.append(it.getFrom()).append(" → ").append(it.getTo())  // transport：从 → 到（方式，时长）
                            .append("（").append(it.getWay()).append("，").append(it.getDuration()).append("）");  // 续行
                    case "stay" -> sb.append(it.getName()).append("（").append(it.getCategory())  // stay：名称（类型 价格）
                            .append(it.getPrice().isBlank() ? "" : " " + it.getPrice()).append("）");  // 续：价格为空则不加
                    case "tips" -> sb.append(it.getKind()).append("：").append(it.getText());  // tips：类型：正文
                    default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());  // default：活动+地点+文本兜底
                }  // switch 结束
                sb.append("\n");  // 换行
            }  // for 结束
            sb.append("\n");  // 区块间空一行
        }  // for 结束
        return sb.toString().trim();  // 返回 trim 后的 markdown
    }  // 方法结束

    // ==================== ★天骨架并行定稿（2026-09-06 架构） ====================
    // 动机：行程Agent 单次生成整单（7天终版JSON≈9千字）受模型长输出速度影响，单轮常超300s被掐断重试。
    // 改法：主管按候选坐标贪心成簇(≤5km)→最近邻地理链→按天切连续段→每天独立短任务(只输出当天，30-90s)
    //      并行生成→拼装成标准整单JSON文本(【行程JSON】块)，下游 parse/审查/缓存/降级链路完全不变。
    // 跨天连续：每天出发地=昨天末簇住宿地，由高德 regeo 城镇链代码锁定；簇不跨天=景点天然不重复、不回头。
    // 失败/不可用（候选无坐标、簇<2、天数<2、任一天失败）→ 返回 null，调用方回退原整单生成。
    /** 每天硬上限分钟数（8h）：分天/合并时的容量约束，与 ConstraintEngine 的"过于赶场"口径一致 */
    static final int PER_DAY_MAX_MIN = 480;  // PER_DAY_MAX_MIN=480

    static class DaySkel {  // 内部类 DaySkel：一天骨架的数据结构
        int day;  // day：天号
        String fromTown = "";  // fromTown：当天出发城镇
        String endTown = "";  // endTown：当天结束（住宿）城镇
        double[] center = null;   // 当天候选质心 [lng,lat]  // center：当天候选质心 [lng,lat]
        double moveKm = -1;       // 昨天末簇质心 → 今天质心的直线距离（-1=无前序/不可算）  // moveKm：昨天质心→今天质心的直线距离（-1=无前序/不可算）
        final java.util.List<java.util.Map<String, Object>> members = new java.util.ArrayList<>();  // members：该天的候选条目
        final java.util.List<double[]> locs = new java.util.ArrayList<>();  // locs：该天候选坐标列表
    }  // 类结束

    /**
     * 作用：生成按天的行程骨架：把候选景点按地理聚类切成每天一组，输出骨架文本
     * 参数：state 任务状态；req 需求
     * 返回值：天骨架文本
     * 异常：无
     */
    private String planByDaySkeleton(TravelState state, TripRequest req) {  // planByDaySkeleton 签名
        if (req == null || req.getDays() < 2) return null;  // 天数<2 不走骨架（单天没必要）
        java.util.Map<String, Object> cands = utils.CandidateUtil.parse(state.getCandidatesJson());  // 解析候选
        java.util.List<java.util.Map<String, Object>> spots = candDomainList(cands, "attraction");  // 取景点域
        if (spots.size() < 3) return null;  // 景点少于 3 个无法成簇
        java.util.List<java.util.List<java.util.Map<String, Object>>> clusters = clusterKm(spots);  // clusterKm：按 5km 贪心成簇
        if (clusters == null || clusters.size() < 2) return null;  // 簇少于 2 个不切天
        java.util.List<java.util.List<java.util.Map<String, Object>>> chain = nearestChain(clusters);  // nearestChain：把簇串成最近邻地理链
        if (chain == null) return null;  // 链构造失败返回 null
        java.util.List<DaySkel> days = cutIntoDays(chain, req.getDays(), req);  // cutIntoDays：按天切连续段
        if (days == null || days.size() != req.getDays()) return null;  // 切出的天数与需求不符就放弃
        EventStore.add(sessionId, "定稿", "天骨架并行定稿：候选按坐标成" + clusters.size() + "簇→地理链切"  // 写事件：天骨架并行定稿——候选按坐标成 N 簇→地理链切 M 天
                + days.size() + "天，逐日短任务并行生成后拼装（防单次长输出超时）");  // 续：逐日短任务并行生成后拼装（防单次长输出超时）
        // regeo 城镇链：每天质心反查住宿城镇；第1天出发=origin，其后出发=昨天住宿城镇
        for (int i = 0; i < days.size(); i++) {  // 逐天补城镇信息
            DaySkel sk = days.get(i);  // 取该天骨架
            sk.day = i + 1;  // 天号 = 下标+1
            if (!sk.locs.isEmpty()) {  // 有坐标
                // 住宿城镇取当天全部候选的质心反查（比"最后成员"更代表当日活动区域）
                double sx = 0, sy = 0;  // sx/sy：坐标累加
                for (double[] p : sk.locs) { sx += p[0]; sy += p[1]; }  // 累加经纬度
                sk.center = new double[]{sx / sk.locs.size(), sy / sk.locs.size()};  // center：质心
                sk.endTown = townNear(sk.center[0], sk.center[1]);  // endTown：质心附近城镇
                if (sk.endTown == null) sk.endTown = "";  // 取不到归空串
            }  // if 结束
            sk.fromTown = i == 0  // fromTown：第一天用出发地，
                    ? (req.getOrigin() == null || req.getOrigin().isBlank() ? "" : req.getOrigin())  // 续：出发地为空则空串
                    : days.get(i - 1).endTown;  // 之后用前一天的 endTown
        }  // if 结束
        // ★长途移动显式化（设计③）：昨天质心 → 今天质心的直线距离（山区实际车程≈直线×1.8-2.5），
        //   超阈值时在当天任务注入"长途移动日"模板——模型按真实移动写早出发/途中休整/到后轻量，不假装瞬移
        for (int i = 1; i < days.size(); i++) {  // 逐天算移动距离
            DaySkel prev = days.get(i - 1);  // 前一天
            DaySkel cur = days.get(i);  // 当天
            if (prev.center != null && cur.center != null) {  // 两天的质心都有
                cur.moveKm = havKm(prev.center[1], prev.center[0], cur.center[1], cur.center[0]);  // havKm：球面距离
            }  // if 结束
        }  // for 结束
        // 分批并行执行每天任务（8085 并发能力有限：实测 4+ 并发 A2A 时后提交任务失败——每批最多 3 个，
        // 批内并行、批间串行）；单天失败自动重试 1 次（每段独立 240s 预算，短输出必按时返回）
        int batchSize = 3;  // batchSize=3（批大小）
        // ★2026-09-11 改为按【天号】索引：补生成/缺天交付时能精确定位是哪一天，拼装也不会错位
        java.util.Map<Integer, dto.TripPlanData> dayPlans = new java.util.TreeMap<>();  // dayPlans：天号 → 单天行程（TreeMap 有序）
        java.util.List<String> failed = new java.util.ArrayList<>();  // failed：失败的天号
        int processed = 0;  // processed：已处理的偏移
        while (processed < days.size()) {  // 分批循环
            int batchEnd = Math.min(processed + batchSize, days.size());  // batchEnd：本批结束位置
            java.util.List<java.util.concurrent.Future<String>> futs = new java.util.ArrayList<>();  // futs：本批任务句柄
            for (int i = processed; i < batchEnd; i++) {  // 提交本批每天
                final DaySkel f = days.get(i);  // f：该天骨架
                futs.add(PARALLEL_POOL.submit(() -> remoteTool.callTripPlannerAgent(daySkeletonTask(state, req, f))));  // 调行程 Agent（daySkeletonTask 生成当天任务）
            }  // for 结束
            java.util.Map<Integer, dto.TripPlanData> batchPlans = new java.util.TreeMap<>();  // batchPlans：本批成功的天
            for (int i = 0; i < futs.size(); i++) {  // 逐批等待结果
                int dayNo = processed + i + 1;  // dayNo：真实天号
                checkCancel();  // checkCancel
                String label = "行程Agent·第" + dayNo + "天";  // label：日志/事件标签
                String reply = waitBudget(futs.get(i), label, 240);  // waitBudget：带 240s 预算等待
                dto.TripPlanData dp = parseDayReply(reply, dayNo, "骨架");  // parseDayReply：解析单天回复
                if (dp == null) {  // 解析失败
                    // 单天重试一次
                    EventStore.add(sessionId, "定稿", "第" + dayNo + "天首次生成失败，重试...");  // 写事件：第 N 天首次生成失败，重试
                    final DaySkel f = days.get(processed + i);  // 取该天骨架
                    String reply2 = waitBudget(PARALLEL_POOL.submit(() ->  // 重试任务，
                            remoteTool.callTripPlannerAgent(daySkeletonTask(state, req, f))), label + "(重试)", 240);  // 续：标签“(重试)”，同样 240s
                    dp = parseDayReply(reply2, dayNo, "骨架重试");  // 再解析
                }  // if 结束
                if (dp == null) {  // 重试也失败
                    failed.add(String.valueOf(dayNo));  // 记入失败列表
                    continue;  // continue（该天留空位）
                }  // if 结束
                batchPlans.put(dayNo, dp);  // 本批成功的天入表
            }  // for 结束
            if (!batchPlans.isEmpty()) {  // 本批有成功
                for (java.util.Map.Entry<Integer, dto.TripPlanData> e : batchPlans.entrySet()) {  // 逐天入总表
                    dayPlans.put(e.getKey(), e.getValue()); // 按天号入表（TreeMap 天然有序）  // 按天号入表（TreeMap 天然有序）
                    EventStore.add(sessionId, "定稿", "第" + e.getKey() + "天生成完成("  // 写事件：第 N 天生成完成
                            + e.getValue().getSections().size() + "区块)");  // 续：区块数
                }  // for 结束
            }  // if 结束
            processed = batchEnd;  // 推进偏移
        }  // while 结束
        if (!failed.isEmpty()) {  // 有失败的天
            // ★2026-09-11 天级兜底（精准重试失败的部分）：
            //   并发是失败主因（8085 实测 4+ 并发 A2A 时后提交的任务会失败），所以补生成改为【串行】——
            //   只针对失败的那几天重试，已成功的天原样保留，不再把整批结果清空重来。
            EventStore.add(sessionId, "定稿", "天骨架有 " + failed.size() + " 天未成功（第"  // 写事件：天骨架有 N 天未成功（第…天），正在串行补生成
                    + String.join("、", failed) + "天），正在串行补生成…");  // 续行
            java.util.List<String> stillFailed = new java.util.ArrayList<>();  // stillFailed：串行补后仍失败的
            for (String f : new java.util.ArrayList<>(failed)) {  // 遍历失败天
                checkCancel();  // checkCancel
                int dayNo = Integer.parseInt(f);  // 天号
                DaySkel sk = days.get(dayNo - 1);  // 该天骨架
                String reply = waitBudget(PARALLEL_POOL.submit(() ->  // 串行补生成（单任务，不并发）
                        remoteTool.callTripPlannerAgent(daySkeletonTask(state, req, sk))), "行程Agent·第" + dayNo + "天(串行补)", 240);  // 续：标签“(串行补)”，240s
                dto.TripPlanData dp = parseDayReply(reply, dayNo, "串行补");  // 解析
                if (dp == null) {  // 仍失败
                    stillFailed.add(f);  // 记入仍失败
                } else {  // 补成功
                    dayPlans.put(dayNo, dp); // 补成功：放回对应天号位置  // 放回对应天号位置
                    EventStore.add(sessionId, "定稿", "第" + dayNo + "天串行补生成成功");  // 写事件：第 N 天串行补生成成功
                }  // if 结束
            }  // for 结束
            failed.clear();  // 清空原失败列表
            failed.addAll(stillFailed);  // 填入仍失败的
        }  // if 结束

        if (failed.isEmpty()) {  // 全部天都成功
            // 全部天成功：天级 JSON 存档（★REVISE 只重编涉及天）→ 统一拼装
            for (java.util.Map.Entry<Integer, dto.TripPlanData> e : dayPlans.entrySet()) {  // 逐天入 state
                state.addDayPlanJson(utils.JsonUtils.toJson(e.getValue()));  // addDayPlanJson：按天号存 JSON
            }  // for 结束
            state.addTrace("定稿", "天骨架并行生成完成(" + days.size() + "天，簇链切分)");  // 记轨迹：天骨架并行生成完成（N 天，簇链切分）
            return assembleDayPlanText(state, req, new java.util.ArrayList<>(dayPlans.keySet()));  // assembleDayPlanText：拼成整份行程文本
        }  // if 结束

        // ★2026-09-11 降级分级：补生成后仍有天失败 → 保留已成功的天按天交付并如实标注，
        //   而不是像原来那样 clear() 后回退"整单生成"（整单更容易失败，一失败就退成纯文本）。
        if (dayPlans.isEmpty()) {  // 一天都没成功
            EventStore.add(sessionId, "定稿", "天骨架全部天均失败（第" + String.join("、", failed) + "天），回退整单生成");  // 写事件：天骨架全部失败（第…天），回退整单生成
            state.getDayPlanJson().clear();  // 清空按天 JSON（回退整单路径）
            return null;  // 返回 null（调用方回退整单）
        }  // if 结束
        EventStore.add(sessionId, "定稿", "⚠️ 第" + String.join("、", failed) + "天最终未生成成功，"  // 写事件：⚠️ 第…天最终未生成成功，
                + "其余 " + dayPlans.size() + " 天正常交付（缺失天已在行程内标注）");  // 续：其余 N 天正常交付（缺失天已在行程内标注）
        for (java.util.Map.Entry<Integer, dto.TripPlanData> e : dayPlans.entrySet()) {  // 逐天入 state
            dto.TripPlanData dp = e.getValue();  // 取单天行程
            state.addDayPlanJson(e.getKey(), utils.JsonUtils.toJson(dp)); // 按天号对齐，缺天留空位  // 按天号对齐，缺天留空位
        }  // for 结束
        state.addTrace("定稿", "天骨架部分生成(" + dayPlans.size() + "/" + days.size() + "天)");  // 记轨迹：天骨架部分生成（x/y 天）
        return assembleDayPlanText(state, req, new java.util.ArrayList<>(dayPlans.keySet()));  // assemble：拼成整份并返回
    }  // 方法结束

    /**
     * 作用：把所有天的骨架拼成完整行程文本
     * 参数：state 任务状态；req 需求
     * 返回值：行程文本
     * 异常：无
     */
    private String assembleDayPlanText(TravelState state, TripRequest req) {  // 两参签名
        java.util.List<Integer> all = new java.util.ArrayList<>();  // all：全部天号
        int n = state.getDayPlanJson() == null ? 0 : state.getDayPlanJson().size();  // n：按天 JSON 数量
        for (int i = 1; i <= n; i++) all.add(i);  // 1..n 全部加入
        return assembleDayPlanText(state, req, all);  // 委托三参版本
    }  // 方法结束

    /**
     * 作用：把指定天的骨架拼成行程文本（定点重排时用）
     * 参数：state 任务状态；req 需求；dayNos 要拼的天号列表
     * 返回值：行程文本
     * 异常：无
     */
    private String assembleDayPlanText(TravelState state, TripRequest req, java.util.List<Integer> dayNos) {  // 三参签名
        java.util.List<String> dj = state.getDayPlanJson();  // 取按天 JSON
        if (dj == null || dj.isEmpty() || req == null || dayNos == null || dayNos.isEmpty()) return null;  // 参数无效返回 null
        java.util.List<Object> allSecs = new java.util.ArrayList<>();  // allSecs：汇总所有区块
        java.util.List<String> summaries = new java.util.ArrayList<>();  // summaries：每天一句话概要
        java.util.List<String> missing = new java.util.ArrayList<>();  // missing：缺失的天
        for (int i = 0; i < dj.size(); i++) {  // 逐天处理
            int dayNo = i + 1;  // 真实天号
            String raw = dj.get(i);  // 该天 JSON
            if (raw == null || raw.isBlank()) { missing.add("第" + dayNo + "天"); continue; } // 缺天：留空位  // 空 → 记缺失并留空位
            dto.TripPlanData dp = utils.JsonUtils.extractAndParse(raw, dto.TripPlanData.class);  // 解析单天行程
            if (dp == null || dp.getSections() == null || dp.getSections().isEmpty()) {  // 解析失败或无区块
                missing.add("第" + dayNo + "天");  // 记缺失
                continue;  // 继续下一天
            }  // if 结束
            String dayTag = "第" + dayNo + "天";  // dayTag：第N天
            for (dto.Section s : dp.getSections()) {  // 遍历该天区块
                String type = s.getType() == null ? "" : s.getType();  // 取类型
                String title = s.getTitle() == null ? "" : s.getTitle();  // 取标题
                if ("timeline".equals(type) && !title.contains(dayTag)) continue; // 防模型越权输出其它天  // timeline 但标题不是这一天 → 跳过（防模型越权输出其它天）
                allSecs.add(s);  // 加入汇总
            }  // for 结束
            summaries.add(oneLineDay(dp, dayNo));  // 生成一句话概要
        }  // for 结束
        if (allSecs.isEmpty()) return null; // 没有任何有效天 → 交给上游决定是否回退整单  // 一个有效天都没有 → 返回 null（交给上游决定是否回退整单）
        if (!missing.isEmpty()) { // ★缺天如实标注（进度/前端可见，不隐瞒）  // 有缺失的天
            summaries.add("⚠️ " + String.join("、", missing) + "未能生成成功（已自动重试仍未成功），"  // 追加一条 ⚠️ 缺天说明：未能生成成功（已自动重试仍未成功），
                    + "其余天正常交付；如需补齐，可对缺失的天重新提交生成。");  // 续：其余天正常交付；如需补齐可对缺失的天重新提交
        }  // if 结束
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();  // root：整份行程 Map
        root.put("title", (req.getDestination() == null ? "" : req.getDestination()) + req.getDays() + "日游");  // title：目的地+天数+日游
        root.put("destination", req.getDestination());  // destination
        root.put("days", req.getDays());  // days
        root.put("weather", "");  // weather 先留空（后面 normalizePlan 填）
        root.put("sections", allSecs);  // sections：全部区块
        String planJson = utils.JsonUtils.toJson(root);  // 序列化成 JSON
        if (planJson == null || planJson.isBlank()) return null;  // 序列化失败返回 null
        return String.join("\n", summaries) + "\n\n【行程JSON】\n" + planJson + "\n【行程JSON结束】";  // 返回：概要 + 【行程JSON】块 + 【行程JSON结束】
    }  // 方法结束

    /**
     * 作用：构造单天细化任务：给定该天骨架景点与路线要点，要求排出行程条目
     * 参数：state 任务状态；req 需求；sk 该天骨架
     * 返回值：任务描述文本
     * 异常：无
     */
    private String daySkeletonTask(TravelState state, TripRequest req, DaySkel sk) {  // daySkeletonTask 签名
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("你是行程定稿Agent。本次任务：只编排整个行程的【第 ").append(sk.day).append(" 天】（共 ")  // 开头：只编排第 N 天（共 M 天），
                .append(req.getDays()).append(" 天），【禁止】安排或提及其它天，也禁止编造候选清单之外的景点。\n\n");  // 续：禁止安排或提及其它天，也禁止编造候选清单外的景点
        // ★2026-09-11 明确"候选预置"：三域候选已由主管查好并注入本任务（景点在下方候选区，酒店/餐饮在文末），
        //   禁止再调用 query_trip_candidates / query_trip 重复查询——这是 Agent 发起补查、补查完以叙述收尾的直接诱因。
        sb.append("【候选预置】本任务的候选（景点/酒店/餐饮）已由主管查好并附在文末与候选区，"  // 候选预置说明：候选已附在文末与候选区，禁止重复查询；
                + "【禁止】再调用 query_trip_candidates 或 query_trip 重复查询；确需补查也必须在本轮内完成并接着输出【行程JSON】。\n\n");  // 续：确需补查也必须在本轮内完成并接着输出【行程JSON】
        sb.append("【整体需求】").append(req.getDestination()).append(" ").append(req.getDays()).append(" 日游");  // 整体需求：目的地 N 日游
        if (req.getPreference() != null && !req.getPreference().isBlank()) {  // 有文本偏好
            sb.append("，偏好：").append(req.getPreference());  // 续：偏好
        }  // if 结束
        String tripPref = req.getTripPref();  // 取行程节奏偏好
        if (tripPref != null && !tripPref.isBlank()) {  // 有
            sb.append("；行程节奏偏好：").append(tripPref);  // 续：行程节奏偏好
        }  // if 结束
        if (req.getBudgetTotal() > 0) {  // 有预算
            sb.append("；预算：").append((long) req.getBudgetTotal()).append(" 元");  // 续：预算 N 元
        }  // if 结束
        sb.append("\n\n【今日出发地】")  // 今日出发地：
                .append(sk.fromTown.isBlank() ? "行程起点（从出发城市启程）" : sk.fromTown)  // 续：出发城镇（空则“行程起点”）
                .append(sk.day == 1 ? "（第1天从行程起点出发）" : "（昨天住宿地，今天从这里前往今日景点；如与今日首景点相距较远请先写清移动方式与车程）")  // 续：第 1 天/其余天说明（昨天住宿地，相距远要先写移动方式与车程）
                .append("\n");  // 换行
        sb.append("【今日住宿区域】").append(sk.endTown.isBlank() ? "今日游玩区域就近" : sk.endTown)  // 今日住宿区域：
                .append("（选择该区域真实酒店）\n");  // 续：（选择该区域真实酒店）
        // ★长途移动日模板（设计③）：昨天末点→今天首点的直线距离超阈值时显式点名赶路，防"瞬移式"行程
        if (sk.moveKm > 150) {  // 距离 >150km（长途）
            int est = (int) Math.round(sk.moveKm * (sk.moveKm > 300 ? 2.2 : 1.8)); // 山区系数粗估车程  // est：按山区系数粗估车程（>300km 用 2.2 倍，否则 1.8 倍）
            sb.append("【长途移动日】今天需从昨天行程末点（直线约 ").append((int) Math.round(sk.moveKm))  // 长途移动日：今天需从昨天末点（直线约 N km，山区车程约 M 分钟）赶赴今日主景区
                    .append(" km，山区实际车程约 ").append(est).append(" 分钟）赶赴今日主景区。时间线必须按真实移动编排：")  // 续：时间线必须按真实移动编排：
                    .append("① 早出发（6:00-8:00，视车程定）；② 途中给 1-2 个真实途经休整/观景短停（写明地点，宁少勿编）；")  // 续：① 早出发（6:00-8:00）；② 途中给 1-2 个真实途经休整/观景短停（写明地点，宁少勿编）；
                    .append("③ 主景区游玩安排在到达后并给足时间；④ 若到达已过 15:00，今日只安排抵达+入住+轻量活动（如街区漫步/晚餐），")  // 续：③ 主景区游玩安排在到达后并给足时间；④ 若到达已过 15:00，今日只安排抵达+入住+轻量活动，
                    .append("主景区改次日——禁止把长途车程写成一笔带过或让景点时间与车程矛盾。\n");  // 续：主景区改次日——禁止把长途车程一笔带过或让景点时间与车程矛盾
        } else if (sk.moveKm > 80) {  // 距离 80-150km（较长但不长途）
            sb.append("【车程较长】今天从昨天行程末点赴今日景区直线约 ").append((int) Math.round(sk.moveKm))  // 车程较长：直线约 N km，请给合理出发时间与车程，
                    .append(" km，请给出合理出发时间与车程，避免景点时间与移动时长矛盾。\n");  // 续：避免景点时间与移动时长矛盾
        }  // if 结束
        sb.append("【今日时间预算】景点游玩 + 车程 合计 ≤ 8 小时（用餐/休整已另计）。"  // 今日时间预算：景点游玩 + 车程 ≤8 小时（用餐休整另计）；
                + "下方候选里标注的「建议游玩」是净游玩时长，请据此安排时间线；"  // 续：候选里的“建议游玩”是净游玩时长，据此安排时间线；
                + "若排不下就少安排一个景点，【禁止】压缩各景点的游玩时间凑数，"  // 续：排不下就少安排一个景点，禁止压缩游玩时间凑数，
                + "【禁止】为填满时间而编造候选之外的地点或把自由活动写成景点。\n");  // 续：禁止编造候选外的地点或把自由活动写成景点
        sb.append("【今日可安排景点候选（物理邻近组，可全选或选部分，最多5个）】\n");  // 今日可安排景点候选（物理邻近组，可全选或选部分，最多 5 个）
        for (java.util.Map<String, Object> it : sk.members) {  // 遍历该天候选
            String nm = str(it.get("name"));  // 名称
            String loc = str(it.get("location"));  // 坐标
            String pr = str(it.get("price"));  // 票价
            String ot = str(it.get("openTime"));  // 开放时间
            String du = str(it.get("duration"));  // 建议游玩时长
            sb.append("- ").append(nm.isBlank() ? "(未命名)" : nm)  // 输出“- 名称”，
                    .append(du.isBlank() ? "" : "（建议游玩 " + du + "）")  // 续：有建议游玩就带上
                    .append(loc.isBlank() ? "" : "（坐标 " + loc + "）")  // 续：有坐标就带上
                    .append(pr.isBlank() ? "" : " 票价:" + pr)  // 续：有票价就带上
                    .append(ot.isBlank() ? "" : " 开放:" + ot).append("\n");  // 续：有开放时间就带上，换行
        }  // for 结束
        sb.append("\n【输出要求】按系统规则输出【行程JSON】块：sections 只含今天的 type=timeline（title 用「第")  // 输出要求：sections 只含今天的 timeline（title「第N天」）与 stay；
                .append(sk.day).append("天」）与 type=stay；timeline 覆盖全天（到达/景点游玩/午餐晚餐/晚间），")  // 续：timeline 覆盖全天（到达/游玩/午餐晚餐/晚间）；
                .append("一个景区内部多站点合并成一条并写进 activity；当天所有条目 place 文本不得重复；cat=景点的条目不超过5个；")  // 续：一景区多站点合并成一条写进 activity；place 不重复；景点条目 ≤5 个；
                .append("概要每天一句话不超过60字。JSON 完整优先，宁短勿长，禁止截断 JSON 块。");  // 续：概要每天一句 ≤60 字；JSON 完整优先，禁止截断
        // ★2026-09-11 修「Agent 只拿到景点候选 → 自己发起补查 → 补查完以叙述收尾、不输出 JSON」：
        //   原来只注入景点候选，Agent 判断酒店/餐饮缺失就去补查，补查后整轮以"我将补查…"这类计划性叙述结束，
        //   这一轮就作废（manager 只能判失败→重试，白等 2~5 分钟）。把主管已查好的酒店/餐饮候选一并注入。
        try {  // try
            java.util.Map<String, Object> cands = utils.JsonUtils.extractAndParse(  // 解析候选 JSON
                    state.getCandidatesJson(), java.util.Map.class);  // 续行
            String[][] extra = {{"hotel", "今日住宿候选"}, {"dining", "今日餐饮候选"}};  // extra：要补注入的两个域（酒店、餐饮）
            for (String[] de : extra) {  // 逐个域
                java.util.List<java.util.Map<String, Object>> list = candDomainList(cands, de[0]);  // 取该域条目
                if (list.isEmpty()) continue;  // 空就跳过
                sb.append("\n【").append(de[1]).append("（主管已查好，直接选用，禁止再查）】\n");  // 标题：今日住宿/餐饮候选（主管已查好，直接选用，禁止再查）
                int n = 0;  // 已注入条数
                for (java.util.Map<String, Object> it : list) {  // 遍历条目
                    if (n >= 6) break;  // 最多 6 条
                    String nm = str(it.get("name"));  // 名称
                    if (nm.isBlank()) continue;  // 空名跳过
                    n++;  // 计数 +1
                    sb.append("- ").append(nm);  // 输出“- 名称”
                    String loc = str(it.get("location"));  // 坐标
                    if (!loc.isBlank()) sb.append("（坐标 ").append(loc).append("）");  // 有就带上
                    String pr = str(it.get("price"));  // 价格
                    if (!pr.isBlank()) sb.append(" 人均:").append(pr);  // 有就带人均价
                    String ad = str(it.get("address"));  // 地址
                    if (!ad.isBlank()) sb.append(" 地址:").append(ad);  // 有就带地址
                    sb.append("\n");  // 换行
                }  // for 结束
            }  // for 域结束
        } catch (Exception ignored) {  // 捕获异常
            // 候选注入失败不影响主流程（Agent 仍可退回自行补查）
        }  // catch 结束
        sb.append("\n★【本轮铁律】不要以「我先获取…」「我将补查…」等计划性叙述作为回复结束——"  // 本轮铁律：不要以“我先获取…”“我将补查…”作回复结束——那等于本轮没产出
                + "那等于本轮没有产出。候选数据已全部给在任务里：直接编排并【立即输出完整【行程JSON】块】。"  // 续：候选已全给在任务里，直接编排并立即输出完整【行程JSON】块
                + "确需补查时必须在本轮内先调用工具拿到数据，再在同一轮输出【行程JSON】。");  // 续：确需补查时必须在同一轮内先调工具拿到数据再输出 JSON
        return sb.toString().trim();  // 返回 trim 后的任务
    }  // 方法结束

    /**
     * 作用：取候选数据中某个域的条目列表
     * 参数：cands 候选数据；domain 域名
     * 返回值：条目 Map 列表；不存在返回空列表
     * 异常：无
     */
    private static java.util.List<java.util.Map<String, Object>> candDomainList(  // candDomainList 签名第 1 行
            java.util.Map<String, Object> cands, String domain) {  // 签名第 2 行
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();  // 结果列表
        if (cands == null) return out;  // 候选为空返回空列表
        Object dom = cands.get(domain);  // 取该域节点
        if (!(dom instanceof java.util.Map<?, ?> dm)) return out;  // 不是 Map 返回空列表
        Object cs = dm.get("candidates");  // 取 candidates
        if (cs instanceof java.util.List<?> list) {  // 是列表
            for (Object o : list) {  // 遍历条目
                if (o instanceof java.util.Map<?, ?> m) {  // 是 Map
                    java.util.Map<String, Object> mm = new java.util.LinkedHashMap<>();  // 复制成键为字符串的 LinkedHashMap
                    for (java.util.Map.Entry<?, ?> e : m.entrySet()) mm.put(String.valueOf(e.getKey()), e.getValue());  // 逐键复制（键统一转字符串）
                    out.add(mm);  // 加入结果
                }  // if 结束
            }  // for 结束
        }  // if 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：读取候选条目的坐标
     * 参数：it 候选条目
     * 返回值：[纬度, 经度]；缺失返回 null
     * 异常：无
     */
    private static double[] locOf(java.util.Map<String, Object> it) {  // locOf 签名
        if (it == null) return null;  // 条目为空返回 null
        Object v = it.get("location");  // 取 location
        if (v == null) return null;  // 没有返回 null
        String s = String.valueOf(v).trim();  // 转字符串并 trim
        int c = s.indexOf(',');  // 找逗号
        if (c <= 0) return null;  // 没逗号/位置非法返回 null
        try {  // try
            double lng = Double.parseDouble(s.substring(0, c).trim());  // 经度
            double lat = Double.parseDouble(s.substring(c + 1).trim());  // 纬度
            return new double[]{lng, lat};  // 返回 [经度, 纬度]
        } catch (NumberFormatException e) {  // 解析失败
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束
    /**
     * 作用：计算两点球面距离（Haversine）
     * 参数：la1 起点纬度；lo1 起点经度；la2 终点纬度；lo2 终点经度
     * 返回值：距离公里数
     * 异常：无
     */

    private static double havKm(double la1, double lo1, double la2, double lo2) {  // havKm 签名
        double R = 6371.0;  // 地球半径 6371km
        double dLat = Math.toRadians(la2 - la1);  // 纬度差转弧度
        double dLng = Math.toRadians(lo2 - lo1);  // 经度差转弧度
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)  // Haversine 前半
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))  // 续：cos(la1)·cos(la2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);  // ＋ sin²(dLng/2)，Haversine 公式后半
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));  // 标准球面距离公式
    }  // 方法结束

    /**
     * 作用：按给定距离阈值对景点做聚类
     * 参数：spots 景点列表（含坐标）
     * 返回值：按距离聚成的若干组
     * 异常：无
     */
    private static java.util.List<java.util.List<java.util.Map<String, Object>>> clusterKm(  // clusterKm 签名第 1 行
            java.util.List<java.util.Map<String, Object>> spots) {  // 签名第 2 行
        java.util.List<java.util.Map<String, Object>> coord = new java.util.ArrayList<>();  // coord：有坐标的点
        for (java.util.Map<String, Object> it : spots) if (locOf(it) != null) coord.add(it);  // 只保留有坐标的
        if (coord.size() < 2) return null;  // 少于 2 个无法成簇
        java.util.List<java.util.List<java.util.Map<String, Object>>> groups = new java.util.ArrayList<>();  // groups：簇列表
        java.util.List<double[]> cents = new java.util.ArrayList<>();  // cents：每簇质心
        for (java.util.Map<String, Object> it : coord) {  // 逐点归簇
            double[] p = locOf(it);  // 取坐标
            int best = -1;  // best：最合适的簇下标，-1 表示新簇
            double bestD = Double.MAX_VALUE;  // bestD：最近距离
            for (int g = 0; g < groups.size(); g++) {  // 遍历已有簇
                double[] c = cents.get(g);  // 取质心
                double d = havKm(c[1], c[0], p[1], p[0]);  // 算距离
                if (d <= 5.0 && d < bestD) { best = g; bestD = d; }  // ≤5km 且比当前最近更近 → 记下
            }  // for 结束
            if (best >= 0) {  // 找到可并入的簇
                groups.get(best).add(it);  // 加入该簇
                double[] c = cents.get(best);  // 取质心
                int n = groups.get(best).size();  // 簇内点数
                c[0] = (c[0] * (n - 1) + p[0]) / n;  // 增量更新质心经度
                c[1] = (c[1] * (n - 1) + p[1]) / n;  // 增量更新质心纬度
            } else {  // 否则
                java.util.List<java.util.Map<String, Object>> g = new java.util.ArrayList<>();  // 新建一簇
                g.add(it);  // 放入该点
                groups.add(g);  // 加入簇列表
                cents.add(new double[]{p[0], p[1]});  // 质心就是该点
            }  // if 结束
        }  // for 结束
        return groups;  // 返回簇列表
    }  // 方法结束

    /**
     * 作用：把景点按最近邻次序串成一条动线（避免来回折返），并对多日景点做分组
     * 参数：clusters 已按距离聚类的景点组
     * 返回值：串好的动线分组
     * 异常：无
     */
    private static java.util.List<java.util.List<java.util.Map<String, Object>>> nearestChain(  // nearestChain 签名第 1 行
            java.util.List<java.util.List<java.util.Map<String, Object>>> clusters) {  // 签名第 2 行
        if (clusters == null || clusters.isEmpty()) return null;  // 没有簇返回 null
        double[][] cents = new double[clusters.size()][2];  // cents：每簇质心
        for (int i = 0; i < clusters.size(); i++) {  // 逐簇算质心
            double sx = 0, sy = 0;  // 累加经纬度
            java.util.List<double[]> ls = new java.util.ArrayList<>();  // 收集有效坐标
            for (java.util.Map<String, Object> it : clusters.get(i)) {  // 遍历簇内点
                double[] p = locOf(it);  // 取坐标
                if (p != null) { sx += p[0]; sy += p[1]; ls.add(p); }  // 累加并收集
            }  // for 结束
            if (ls.isEmpty()) return null;  // 一个有效坐标都没有 → null
            cents[i] = new double[]{sx / ls.size(), sy / ls.size()};  // 质心
        }  // for 结束
        java.util.List<java.util.List<java.util.Map<String, Object>>> chain = new java.util.ArrayList<>();  // chain：动线
        boolean[] used = new boolean[clusters.size()];  // used：是否已入链
        int cur = 0;  // cur：当前簇下标
        chain.add(clusters.get(0));  // 从第 0 簇开始
        used[0] = true;  // 标记已用
        for (int step = 1; step < clusters.size(); step++) {  // 逐步扩展
            int next = -1;  // next：下一个簇
            double best = Double.MAX_VALUE;  // best：最近距离
            for (int i = 0; i < clusters.size(); i++) {  // 遍历未用的簇
                if (used[i]) continue;  // 跳过已用
                double d = havKm(cents[cur][1], cents[cur][0], cents[i][1], cents[i][0]);  // 算到当前簇的距离
                if (d < best) { best = d; next = i; }  // 更近就记下
            }  // for 结束
            if (next < 0) break;  // 找不到就结束
            chain.add(clusters.get(next));  // 加入动线
            used[next] = true;  // 标记已用
            cur = next;  // 移动当前簇
        }  // for 结束
        return chain;  // 返回动线
    }  // 方法结束

    /**
     * 作用：把动线按天数切分：结合每日时长上限与景点优先级，决定每天玩哪些
     * 参数：chain 动线分组；days 天数；req 需求
     * 返回值：每天的骨架列表
     * 异常：无
     */
    static java.util.List<DaySkel> cutIntoDays(  // cutIntoDays 签名第 1 行
            java.util.List<java.util.List<java.util.Map<String, Object>>> chain, int days, TripRequest req) {  // 签名第 2 行
        if (chain == null || days < 1 || chain.size() < 1) return null;  // 参数无效返回 null
        boolean loop = req != null && req.getTravelType() != null  // loop：是否环线/自驾（
                && ("LOOP".equals(req.getTravelType().trim().toUpperCase())  // 续：类型是 LOOP
                || "SELF_DRIVE".equals(req.getTravelType().trim().toUpperCase()));  // 续：或 SELF_DRIVE）

        // ★2026-09-12 重写为【按景点游玩时长分天】（原逻辑"一簇一天"与设计不符）：
        //   候选景点自带 duration（"建议半天/建议1天/建议1-2天/建议2-3小时"），这才是分天依据——
        //   大景区（稻城亚丁"建议1-2天"）本就该占 1~2 天，半天景区可在同区域合并；
        //   每天控制在 4~8 小时（与 ConstraintEngine 的节奏口径一致）。
        //   天数(days)是硬约束：分出的组数不足则拆分最大的组，超出则并入相邻组。
        java.util.List<java.util.Map<String, Object>> flat = new java.util.ArrayList<>();  // flat：把各簇摊平成一个列表
        for (java.util.List<java.util.Map<String, Object>> cl : chain) {  // 遍历簇
            // ★2026-09-12 簇内排序（决定"同区域里先玩谁"）：priority 升序（1=必去优先）→ rating 降序。
            //   priority 由景点候选专家输出（1 必去 / 2 强烈推荐 / 3 可选补充），缺失按 2 处理（兼容旧数据）。
            //   跨簇顺序仍由地理链决定（不绕路）。
            java.util.List<java.util.Map<String, Object>> cl2 = new java.util.ArrayList<>(cl);  // cl2：簇的副本（可排序）
            cl2.sort((a, b) -> {  // 排序
                int pa = priorityOf(a), pb = priorityOf(b);  // 取两点的 priority
                if (pa != pb) return Integer.compare(pa, pb);  // priority 小的在前
                return Double.compare(ratingOf(b), ratingOf(a));  // 同级按评分从高到低
            });  // 排序结束
            flat.addAll(cl2);  // 摊入 flat
        }  // for 结束
        if (flat.isEmpty()) return null;  // flat 为空返回 null

        java.util.List<Integer> mins = new java.util.ArrayList<>();  // mins：每个景点的游玩分钟数
        int total = 0;  // total：总游玩分钟
        for (java.util.Map<String, Object> it : flat) {  // 逐个算
            int m = durationMinutes(it.get("duration"));  // 分钟数（从 duration 文本解析）
            mins.add(m);  // 记录
            total += m;  // 累加
        }  // for 结束
        // 每天目标游玩时长：总时长均摊，夹在 240~480 分钟（4~8h）
        int perDay = Math.max(240, Math.min(480, (int) Math.ceil(total / (double) days)));  // perDay：均摊值夹紧

        java.util.List<java.util.List<java.util.Map<String, Object>>> groups = new java.util.ArrayList<>();  // groups：分好的天组
        java.util.List<java.util.Map<String, Object>> cur = new java.util.ArrayList<>();  // cur：当前正在累积的组
        int acc = 0;  // acc：当前组累计分钟
        for (int i = 0; i < flat.size(); i++) {  // 逐个景点分配
            int m = mins.get(i);  // 该景点分钟
            // ★2026-09-12 大景区独占连续多天（张家界/稻城亚丁这类"建议2-3天"的景区，
            //   在行程里就是连续玩好几天，不能塞进某一天、也不能被当成半天）。
            //   判定用【单天硬上限 480】而不是【每天均摊目标 per】——
            //   实测踩过：per=ceil(6540/14)=468 时，ceil(480/468)=2，把"正好1天"的景区
            //   （九寨沟/四姑娘山/海螺沟）全判成占 2 天，一天变两天，天数直接爆掉。
            int need = Math.max(1, (int) Math.ceil(m / (double) PER_DAY_MAX_MIN));  // need：需要几天（向上取整，至少 1）
            if (need > 1) {  // 需要多天
                if (!cur.isEmpty()) { groups.add(cur); cur = new java.util.ArrayList<>(); acc = 0; }  // 先把已累积的组收尾
                for (int d = 0; d < need && groups.size() < days; d++) {  // 为这个大景区占 need 天
                    java.util.List<java.util.Map<String, Object>> g = new java.util.ArrayList<>();  // 新建单成员组
                    g.add(flat.get(i)); // 同一个景区出现在连续多天：每天写它的不同片区/线路  // 放同一个景区（每天写它的不同片区/线路）
                    groups.add(g);  // 加入天组
                }  // for 结束
                continue;  // continue（大景区已处理完）
            }  // if 结束
            if (!cur.isEmpty() && acc + m > perDay) {  // 加入后会超过每天目标
                groups.add(cur);  // 当前组收尾
                cur = new java.util.ArrayList<>();  // 新建组
                acc = 0;  // 累计清零
            }  // if 结束
            cur.add(flat.get(i));  // 加入当前组
            acc += m;  // 累计分钟
        }  // for 结束
        if (!cur.isEmpty()) groups.add(cur);  // 最后一组收尾

        // 天数对齐：不足则拆分最大的组（避免出现空天）；超出则并入相邻组（保地理连续）
        int guard = 0;  // guard：防死循环
        while (groups.size() < days && guard++ < 200) {  // 组数不足
            int best = -1, bestN = 1;  // best：最大组下标
            for (int i = 0; i < groups.size(); i++) {  // 找最大组
                if (groups.get(i).size() > bestN) { bestN = groups.get(i).size(); best = i; }  // 比当前最大还大就更新
            }  // for 结束
            if (best < 0) {  // 没有可拆的组
                groups.add(new java.util.ArrayList<>()); // 确实无组可拆：留一个机动/休整日（不硬塞景点）  // 加一个空组（留机动/休整日，不硬塞景点）
            } else {  // 否则
                java.util.List<java.util.Map<String, Object>> g = groups.get(best);  // 取最大组
                int half = Math.max(1, g.size() / 2);  // half：一半大小
                groups.set(best, new java.util.ArrayList<>(g.subList(0, half)));  // 前半留原位置
                groups.add(best + 1, new java.util.ArrayList<>(g.subList(half, g.size())));  // 后半插入下一位置
            }  // if 结束
        }  // while 结束
        // ★2026-09-12 天数超额的处理顺序（"值最优"取舍，不是单纯删景点）：
        //   ① 先尝试把"景点数最少的组"并入相邻组（不丢景点，只是当天排得紧一点）；
        //   ② 仍超额才舍弃 priority=3（可选补充）的景点；③ 最后才动 priority=2。
        //   priority=1（必去）永不因为"天数不够"被丢弃。
        //   ★注意：不能合并"大景区独占多天"产生的单成员组（否则大景区又被压回1天）——
        //   判定见 sameSpotAllDays()：这些组虽然只有1个成员，但整个行程里该景点占了多天。
        int guard2 = 0;  // guard2：防死循环
        while (groups.size() > days && guard2++ < 200) {  // 组数超出
            // ① 优先舍 priority=3（可选补充）——这是"值最优"的取舍，比硬合并更符合业务
            int bi = -1, bj = -1;  // bi/bj：要删的组下标与成员下标
            for (int i = 0; i < groups.size(); i++) {  // 遍历组
                for (int j = 0; j < groups.get(i).size(); j++) {  // 遍历成员
                    if (priorityOf(groups.get(i).get(j)) >= 3) { bi = i; bj = j; break; }  // priority≥3 → 记下并跳出
                }  // for 结束
                if (bi >= 0) break;  // 找到了就跳出外层
            }  // for 结束
            if (bi >= 0) {  // 找到可舍的景点
                groups.get(bi).remove(bj);  // 从该组删除该成员
                if (groups.get(bi).isEmpty()) groups.remove(bi);  // 删完组空了就把组也删掉
                continue;  // continue（重新判组数）
            }  // if 结束
            // ② 合并"单成员且非大景区"的组到相邻组——★必须检查容量，否则会出现
            //   "稻城亚丁960+九寨沟480=24小时"这种物理上排不完的一天（实测复现过）
            int mi = -1, mn = Integer.MAX_VALUE;  // mi：要合并的组，mn：最小规模
            for (int i = 0; i < groups.size(); i++) {  // 遍历组
                if (groups.get(i).size() > 1) continue;   // 只考虑"单成员组"  // 只考虑单成员组
                if (isMultiDaySpot(groups, i)) continue;  // 大景区独占的多天：不许合并  // 大景区独占的多天：不许合并
                int tgt = i > 0 ? i - 1 : (i + 1 < groups.size() ? i + 1 : -1);  // tgt：相邻组（优先前一组）
                if (tgt < 0) continue;  // 没有相邻组就跳过
                int load = spotMinutes(groups.get(tgt)) + spotMinutes(groups.get(i));  // load：合并后的分钟数
                if (load > PER_DAY_MAX_MIN) continue;     // ★装不下就不合并  // 装不下（>8h）就不合并
                if (groups.get(i).size() < mn) { mn = groups.get(i).size(); mi = i; }  // 规模更小的作为候选
            }  // for 结束
            if (mi >= 0) {  // 找到可合并的组
                java.util.List<java.util.Map<String, Object>> g = groups.get(mi);  // 取该组
                if (!g.isEmpty()) {  // 非空
                    java.util.Map<String, Object> spot = g.get(0);  // 取它唯一成员
                    int tgt = mi > 0 ? mi - 1 : (mi + 1 < groups.size() ? mi + 1 : -1);  // 相邻组
                    if (tgt >= 0) groups.get(tgt).add(spot);  // 并入相邻组
                }  // if 结束
                groups.remove(mi);  // 删除该组
                continue;  // continue
            }  // if 结束
            // ③ 兜底：候选总量本身就超过 days 天容量（例：14 天行程、候选合计≈15 天量），
            //   容量约束已无解 → 把最后一组并入"全局负荷最小"的那一组，避免全堆在末尾。
            java.util.List<java.util.Map<String, Object>> g = groups.remove(groups.size() - 1);  // 取走最后一组
            int tgt = 0, minLoad = Integer.MAX_VALUE;  // tgt/minLoad：负荷最小的组
            for (int i = 0; i < groups.size(); i++) {  // 遍历组
                int load = spotMinutes(groups.get(i));  // 算负荷
                if (load < minLoad) { minLoad = load; tgt = i; }  // 更小就记下
            }  // for 结束
            groups.get(tgt).addAll(g);  // 并入
        }  // while 结束
        if (groups.size() != days) return null;  // 组数与天数不符 → 放弃骨架（回退整单）

        java.util.List<DaySkel> out = new java.util.ArrayList<>();  // out：每天骨架
        for (java.util.List<java.util.Map<String, Object>> g : groups) {  // 逐组构造
            DaySkel sk = new DaySkel();  // 新建 DaySkel
            for (java.util.Map<String, Object> it : g) {  // 把该组成员放入
                sk.members.add(it);  // 记录成员
                double[] p = locOf(it);  // 取坐标
                if (p != null) sk.locs.add(p);  // 有坐标就记
            }  // for 结束
            out.add(sk);  // 加入结果
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：估算一组景点的总停留分钟数
     * 参数：group 一组景点
     * 返回值：总分钟数
     * 异常：无
     */
    static int spotMinutes(java.util.List<java.util.Map<String, Object>> group) {  // spotMinutes 签名
        if (group == null) return 0;  // null 返回 0
        int s = 0;  // 累加器
        for (java.util.Map<String, Object> it : group) {  // 遍历成员
            int m = durationMinutes(it.get("duration"));  // 该景点分钟数
            int need = Math.max(1, (int) Math.ceil(m / (double) PER_DAY_MAX_MIN));  // 需要几天
            s += (need > 1) ? (int) Math.ceil(m / (double) need) : m; // 跨天均摊  // 跨天均摊（大景区按天分摊）
        }  // for 结束
        return s;  // 返回总分钟
    }  // 方法结束

    /**
     * 作用：判断某组是否适合拆到多天（体量大的景区）
     * 参数：groups 动线分组；i 组下标
     * 返回值：true=适合拆到多天
     * 异常：无
     */
    static boolean isMultiDaySpot(  // isMultiDaySpot 签名第 1 行
            java.util.List<java.util.List<java.util.Map<String, Object>>> groups, int i) {  // 签名第 2 行
        if (groups == null || i < 0 || i >= groups.size()) return false;  // 越界返回 false
        java.util.List<java.util.Map<String, Object>> g = groups.get(i);  // 取该组
        if (g.size() != 1) return false;  // 不是单成员组 → false
        Object name = g.get(0).get("name");  // 取景点名
        if (name == null) return false;  // 没有名字 → false
        for (int j = 0; j < groups.size(); j++) {  // 遍历其它组
            if (j == i) continue;  // 跳过自己
            for (java.util.Map<String, Object> m : groups.get(j)) {  // 遍历成员
                if (name.equals(m.get("name"))) return true;  // 同名景点出现在别的组 → 是跨天景点
            }  // for 结束
        }  // for 结束
        return false;  // 不在多组 → false
    }  // 方法结束

    /**
     * 作用：读取景点优先级（用户偏好/热度决定排程先后）
     * 参数：it 候选条目
     * 返回值：优先级数值，越大越优先
     * 异常：无
     */
    static int priorityOf(java.util.Map<String, Object> it) {  // priorityOf 签名
        if (it == null) return 2;  // 空条目 → 2
        Object v = it.get("priority");  // 取 priority
        if (v == null) return 2;  // 没有 → 2
        try {  // try
            int p = (int) Math.round(Double.parseDouble(String.valueOf(v).replaceAll("[^0-9.]", "")));  // 取数字部分四舍五入
            return (p >= 1 && p <= 3) ? p : 2;  // 1..3 有效，否则 2
        } catch (NumberFormatException e) {  // 解析失败
            return 2;  // 按 2
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取景点评分
     * 参数：it 候选条目
     * 返回值：评分；缺失返回 0
     * 异常：无
     */
    static double ratingOf(java.util.Map<String, Object> it) {  // ratingOf 签名
        if (it == null) return 0;  // 空条目 → 0
        Object v = it.get("rating");  // 取 rating
        if (v == null) return 0;  // 没有 → 0
        if (v instanceof Number) return ((Number) v).doubleValue();  // 是数字直接取值
        try {  // try
            return Double.parseDouble(String.valueOf(v).replaceAll("[^0-9.]", ""));  // 从字符串里抠数字
        } catch (NumberFormatException e) {  // 解析失败
            return 0;  // 按 0
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：把建议游玩时长转成分钟
     * 参数：dur 时长对象（数字或文本）
     * 返回值：分钟数；无法识别返回默认值
     * 异常：无
     */
    static int durationMinutes(Object dur) {  // durationMinutes 签名
        String s = dur == null ? "" : String.valueOf(dur);  // 转成字符串（null 空串）
        if (s.isBlank()) return 240;  // 空 → 240（半天）
        // ★"半天-1天/半天~1天"必须先判：否则下面的"X天"正则会先吃掉里面的"1天"（实测踩过）
        if (s.contains("半天-1天") || s.contains("半天~1天")) return 360;  // 半天-1天 → 360 分钟
        // ★"X天/X-Y天"通用解析（张家界、稻城亚丁这类大景区会写"建议2-3天"）——
        //   取上限（宁可按最长算，也不要把大景区压成半天导致分天排不下）
        java.util.regex.Matcher dm = java.util.regex.Pattern  // 正则：N-M 天
                .compile("(\\d+)\\s*\\s*(?:-|~|—|–)\\s*(\\d+)\\s*天")  // 续
                .matcher(s);  // 续
        if (dm.find()) {  // 匹配到
            try {  // try
                int hi = Integer.parseInt(dm.group(2));  // 取上限天数
                if (hi > 0) return Math.min(30, hi) * 480;  // 天数×480（最多 30 天）
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
        }  // if 结束
        java.util.regex.Matcher dm2 = java.util.regex.Pattern.compile("(\\d+)\\s*天").matcher(s);  // 正则：N 天
        if (dm2.find()) {  // 匹配到
            try {  // try
                int n = Integer.parseInt(dm2.group(1));  // 天数
                if (n > 0) return Math.min(30, n) * 480;  // 天数×480
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
        }  // if 结束
        if (s.contains("半天-1天") || s.contains("半天~1天")) return 360;  // 半天-1天 → 360（兜底再判一次）
        if (s.contains("半天")) return 240;  // 半天 → 240
        if (s.contains("2-3小时") || s.contains("2~3小时")) return 150;  // 2-3 小时 → 150
        // 其它："X小时" / "X-Y小时" → 取最大值的小时数
        java.util.regex.Matcher m = java.util.regex.Pattern  // 正则：X 或 X-Y 小时
                .compile("(\\d+)\\s*(?:\\.\\d+)?\\s*(?:-\\s*(\\d+)\\s*)?(小时|h|H)")  // 续
                .matcher(s);  // 续
        if (m.find()) {  // 匹配到
            int h = 0;  // 小时数
            try {  // try
                h = Integer.parseInt(m.group(1));  // 取下限
                if (m.group(2) != null) h = Math.max(h, Integer.parseInt(m.group(2)));  // 有上限就取较大值
            } catch (NumberFormatException ignored) { }  // 解析失败忽略
            if (h > 0) return Math.min(480, h * 60);  // 小时转分钟（最多 480）
        }  // if 结束
        return 240; // 认不出的格式：按半天处理  // 认不出的格式按半天（240）
    }  // 方法结束

    /**
     * 作用：计算一组景点的几何中心坐标
     * 参数：cl 一组景点
     * 返回值：[纬度, 经度]；无坐标返回 null
     * 异常：无
     */
    private static double[] centroidOf(java.util.List<java.util.Map<String, Object>> cl) {  // centroidOf 签名
        double sx = 0, sy = 0;  // 累加经纬度
        int n = 0;  // 有效点数
        for (java.util.Map<String, Object> it : cl) {  // 遍历成员
            double[] p = locOf(it);  // 取坐标
            if (p != null) { sx += p[0]; sy += p[1]; n++; }  // 有坐标就累加
        }  // for 结束
        return n == 0 ? null : new double[]{sx / n, sy / n};  // 没有有效点返回 null，否则返回均值
    }  // 方法结束

    /**
     * 作用：查给定坐标附近的城镇名（用于给某天命名区域）
     * 参数：lng 经度；lat 纬度
     * 返回值：城镇名；查不到返回空串
     * 异常：无
     */
    private String townNear(double lng, double lat) {  // townNear 签名
        try {  // try
            String url = "https://restapi.amap.com/v3/geocode/regeo?location=" + lng + "," + lat  // 拼 regeo 地址：location + key
                    + "&key=" + utils.ExternalServices.amapKey() + "&extensions=base";  // 续：extensions=base
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();  // 打开连接
            c.setConnectTimeout(8000);  // 连接超时 8 秒
            c.setReadTimeout(8000);  // 读超时 8 秒
            String body = new String(c.getInputStream().readAllBytes(), "UTF-8");  // 读响应
            com.fasterxml.jackson.databind.JsonNode node = utils.JsonUtils.parse(body, com.fasterxml.jackson.databind.JsonNode.class);  // 解析 JSON
            String tw = node == null ? "" : node.path("regeocode").path("addressComponent").path("township").asText("");  // 取 addressComponent.township（乡镇/街道）
            if (tw != null && !tw.isBlank()) return tw;  // 有 township 就返回
            String dt = node == null ? "" : node.path("regeocode").path("addressComponent").path("district").asText("");  // 否则取 district（区县）
            return dt == null ? "" : dt;  // 返回区县（可能空串）
        } catch (Exception e) {  // 异常
            return "";  // 返回空串
        } finally {  // finally
            try { Thread.sleep(350); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }  // 无论成败都限速 sleep 350ms（高德 QPS 3）
        }  // finally 结束
    }  // 方法结束

    /**
     * 作用：按预算秒数等待子 Agent 结果，超时取消并返回空串（定点修复用）
     * 参数：future 子 Agent 调用的 Future；label 中文标签；budgetSec 预算秒数
     * 返回值：子 Agent 返回文本；超时返回空串
     * 异常：CancelledException 用户中断
     */
    private String waitBudget(java.util.concurrent.Future<String> future, String label, long budgetSec) {  // waitBudget 签名
        EventStore.add(sessionId, "调用工具", "调用「" + label + "」获取真实数据...");  // 写事件：调用「label」获取真实数据
        long deadline = System.nanoTime() + budgetSec * 1_000_000_000L;  // deadline：现在 + 预算
        try {  // try
            while (true) {  // 循环等待
                checkCancel();  // checkCancel
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;  // 剩余毫秒
                if (remainMs <= 0) {  // 超期
                    future.cancel(true);  // 取消后台任务
                    EventStore.add(sessionId, "Agent结果", label + " ❌ 单天生成超时(" + budgetSec + "s)");  // 写事件：label ❌ 单天生成超时(Ns)
                    return "";  // 返回空串
                }  // if 结束
                try {  // try
                    String r = future.get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);  // 最多等 500ms 就回来检查中断
                    return r == null ? "" : r;  // 拿到就返回（null 归一空串）
                } catch (java.util.concurrent.ExecutionException ex) {  // 捕获包装异常
                    if (ex.getCause() instanceof CancelledException) {  // 原因是用户中断
                        throw (CancelledException) ex.getCause();  // 取出并抛出
                    }  // 其他异常原样抛
                    throw ex;  // catch 结束
                } catch (java.util.concurrent.TimeoutException e) {  // 捕获超时
                    // 本轮 500ms 未完成，回循环头检查中断
                }  // catch 结束
            }  // while 结束
        } catch (CancelledException e) {  // 捕获用户中断
            future.cancel(true);  // 取消后台任务
            EventStore.add(sessionId, "Agent结果", label + " ⏹ 已中断");  // 写事件：label ⏹ 已中断
            throw e;  // 向上抛
        } catch (Exception e) {  // 捕获其他异常
            EventStore.add(sessionId, "Agent结果", label + " ❌ 调用失败(" + e.getMessage() + ")");  // 写事件：label ❌ 调用失败(原因)
            return "";  // 返回空串
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：把单天修复的文本回复解析成只含该天的行程对象
     * 参数：reply 模型返回文本；dayNo 目标天号；ctx 上下文标签（用于日志）
     * 返回值：单天行程对象；解析失败返回 null
     * 异常：无
     */
    private dto.TripPlanData parseDayReply(String reply, int dayNo, String ctx) {  // parseDayReply 签名
        if (reply == null || reply.isBlank()) return null;  // 空回复返回 null
        String block = utils.CandidateUtil.extractPlanBlock(reply);  // 抽【行程JSON】块
        if (block.isBlank()) {  // 没有块
            EventStore.add(sessionId, "行程原文", "（第" + dayNo + "天" + ctx + "·无JSON块，回复全文）\n" + reply);  // 把回复原文存档（标注第几天 + 上下文）
            return null;  // 返回 null
        }  // if 结束
        dto.TripPlanData dp = utils.JsonUtils.extractAndParse(block, dto.TripPlanData.class);  // 解析成 TripPlanData
        if (dp == null || dp.getSections() == null || dp.getSections().isEmpty()) {  // 解析失败或无区块
            EventStore.add(sessionId, "行程原文", "（第" + dayNo + "天" + ctx + "·JSON解析失败，块原文）\n" + block);  // 把块原文存档
            return null;  // 返回 null
        }  // if 结束
        return dp;  // 返回单天行程
    }  // 方法结束

    /**
     * 作用：把某天的条目压成一行文本（给提示词做上下文摘要）
     * 参数：dp 单天行程；day 天号
     * 返回值：一行摘要文本
     * 异常：无
     */
    private static String oneLineDay(dto.TripPlanData dp, int day) {  // oneLineDay 签名
        StringBuilder sb = new StringBuilder("第" + day + "天：");  // sb：以“第N天：”开头
        int items = 0;  // items：地点计数
        String first = "", last = "";  // first/last：首个与最后一个地点
        if (dp.getSections() != null) {  // 有区块
            for (dto.Section s : dp.getSections()) {  // 遍历区块
                if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;  // 只看 timeline 且有条目
                for (dto.SectionItem it : s.getItems()) {  // 遍历条目
                    if (it == null) continue;  // 跳过 null
                    String p = it.getPlace() == null ? "" : it.getPlace().trim();  // 取地点（null 归一空串）
                    if (p.isBlank()) continue;  // 空地点跳过
                    if (first.isBlank()) first = p;  // 第一个地点记为 first
                    last = p;  // last 每轮更新
                    items++;  // 计数 +1
                }  // for 结束
            }  // for 结束
        }  // if 结束
        if (first.isBlank()) return sb.append("行程内容生成，详见JSON").toString();  // 一个地点都没有 → 兜底文案“行程内容生成，详见JSON”
        sb.append(first);  // 输出首个地点
        if (!last.equals(first)) sb.append(" → ").append(last);  // 首尾不同则加“ → 末个”
        sb.append("（").append(items).append(" 个地点安排）");  // 输出地点数量
        return sb.toString();  // 返回这一行
    }  // 方法结束
}  // 类结束