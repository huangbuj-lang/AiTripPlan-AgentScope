package managerAgent.orchestrator;

import dto.PlanSet;
import dto.Section;
import dto.SectionItem;
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

/** 确定性编排器：解析 -> 并行 DAG -> 聚合，流程由 Java 确定，LLM 只负责内容生成。 */
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

    /** 执行编排：解析需求 -> 并行调用专家 -> 聚合为结构化行程。 */
    public TripPlanData run(String userPrompt) {
        return run(userPrompt, null);
    }

    /** 执行编排（带偏好参数）：解析需求 -> 并行调用专家 -> 聚合为结构化行程。 */
    public TripPlanData run(String userPrompt, TripRequest.Preferences prefs) {
        return runCore(userPrompt, prefs);
    }

    /** 执行编排核心（个人中心沉淀偏好可 null）：解析需求 -> 偏好分类 -> 并行调用专家 -> 聚合。 */
    private TripPlanData runCore(String userPrompt, TripRequest.Preferences prefs) {
        ReqAndData rd = resolveAndFetch(userPrompt, prefs);
        //★诊断留证：先落子Agent 原始产出（结构化产出会丢信息，定位必须看原文）
        dumpRawForDiagnosis("route", rd.routeResult);
        dumpRawForDiagnosis("plan", rd.planResult);
        //输入侧缺失亮红灯：哪路专家数据为空，聚合前明确告知（避免静默产出半成品还显示成功）
        String missingInput = missingInputs(rd.routeResult, rd.planResult);
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        //★新增：可行性预检（不中止；取不到里程则不下结论，绝不猜数字）
        utils.RouteFeasibility.Result feas = utils.RouteFeasibility.evaluate(rd.routeResult, rd.req);
        if (!feas.note.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + feas.note);
            //★说"不行"只是半件事：给出可执行的替代方案才是真实业务需要
            if (!feas.advice.isBlank()) {
                EventStore.add(sessionId, "整合", "💡 " + feas.advice);
            }
        }
        //(3) 聚合：结构化 JSON（失败先重试一次，仍失败才退回 markdown 原文兜底）
        EventStore.add(sessionId, "整合", "正在整合路线与行程数据...");
        //★新增（A6 防编造的数据基准）：子Agent 回传的【真实地点清单】→ 校验用的白名单；并把它从正文里剥离
        java.util.Set<String> realPlaces = utils.PlanValidator.parsePlacesBlock(rd.planResult);
        //★治 A6 误报：路线结果本身是真实工具查询产物，其中的地名同样算"真实数据"，一并入白名单
        realPlaces.addAll(utils.PlanValidator.placesFromText(rd.routeResult));
        realPlaces.addAll(utils.PlanValidator.placesFromText(rd.planResult));
        String planText = utils.PlanValidator.stripPlacesBlock(rd.planResult);
        if (!realPlaces.isEmpty()) {
            EventStore.add(sessionId, "校验", "已获得真实地点清单 " + realPlaces.size() + " 条（用于校验行程是否编造地点）");
        }
        TripPlanData plan = aggregate(rd.req, rd.routeResult, planText);
        if (plan == null) {
            //★降级分级：聚合失败【先重试一次】，仍失败才降级（参考 v6：失败先重试、再降级）
            EventStore.add(sessionId, "整合", "结构化聚合失败，先重试一次...");
            plan = aggregate(rd.req, rd.routeResult, planText);
        }
        if (plan == null) {
            plan = fallbackPlan(rd.req, rd.routeResult, rd.planResult); // 兜底已含完整 markdown 原文
            //★降级分级：重试后仍无法结构化 → 【严重降级】，明确标注，不静默当成功交付
            EventStore.add(sessionId, "校验", "⚠️ 降级交付（严重）：重试后行程仍无法结构化为按天区块，已退回 markdown 原文交付");
        } else {
            plan.setMarkdown(renderMarkdown(plan)); // 结构化成功才渲染降级 markdown 字段
            //★可行性复核（行程自身车程）——补实测缺口：路线Agent 有时不给总里程，
            //  预检按铁律不下结论 → 漏掉"天数内排不开"→ 又走一遍重做，白烧时间。
            //  行程各天的车程合计是更硬的依据，两个来源任判其一为不可行即可。
            //★诊断：原始产出 vs 结构化产出的差异（判定"Agent 没写" or "聚合器丢了"）
            reportRawDiagnosis("行程Agent ", rd.planResult, plan);
            feas = refineFeasibility(feas, plan);
            if (feas.infeasible) {
                EventStore.add(sessionId, "校验", "⚠️ " + feas.note);
                if (!feas.advice.isBlank()) {
                    EventStore.add(sessionId, "校验", "💡 " + feas.advice);
                }
            }
            // ★新增：交付前约束校验（硬违反 → 带修正清单打回重做；仍不通过 → 如实标注降级交付）
            plan = validateAndFix(rd.req, rd.routeResult, plan, realPlaces, feas);
            //★交付如实标注（口径已定）：行程过赶时在交付结果顶部明确写出，绝不静默交付坏结果
            if (feas.infeasible) {
                String warn = "⚠️ 本行程过赶：" + feas.note + (feas.advice.isBlank() ? "" : "\n💡 " + feas.advice);
                String md = plan.getMarkdown();
                plan.setMarkdown(warn + "\n\n" + (md == null ? "" : md));
            }
        }
        EventStore.add(sessionId, "完成", "结构化行程生成完成");
        return plan;
    }

    /**
     * 可行性复核：把「路线文本预检」与「行程自身车程」两个来源合并判定。
     *
     * <p>为什么要两个来源：路线Agent 不保证给总里程（实测有几次没给），
     * 此时 {@link utils.RouteFeasibility} 按铁律不下结论 —— 若只看它，就会把
     * "给定天数内物理上排不开"的行程当成"可以靠重做修好"，白烧一轮重做。
     * 行程各天的车程合计（≤6h/天 口径）是更硬的依据。
     */
    private utils.RouteFeasibility.Result refineFeasibility(utils.RouteFeasibility.Result feas, TripPlanData plan) {
        try {
            if (feas != null && feas.infeasible) return feas;
            int days = plan == null ? 0 : plan.getDays();
            if (days <= 0) return feas;
            utils.RouteFeasibility.Result byPlan = utils.RouteFeasibility.fromPlanDrive(
                    utils.PlanValidator.totalDriveMinutes(plan), days, utils.PlanValidator.DAY_DRIVE_MAX_MIN);
            return byPlan.infeasible ? byPlan : feas;
        } catch (Throwable t) {
            return feas; // 复核绝不影响主流程
        }
    }

    /** 交付前校验的最大重做次数 */
    private static final int MAX_VALIDATE_FIX = 2;

    /**
     * ★诊断留证：把<b>子Agent 的原始产出</b>落盘到 logs/raw_&lt;sid&gt;_&lt;tag&gt;.txt（失败不影响交付）。
     *
     * <p>为什么必须留原始文本：结构化产出会丢信息 —— 实测「当日车程合计」在结构化产出里 0 处、
     * duration 25/25 为空，但仅凭结构化产出<b>无法区分</b>是"子Agent 没写"还是"聚合器转写时丢了"。
     * 没有原始文本就只能猜，而猜出来的根因不能作为改代码的依据。
     */
    /** 风格标签（多方案聚合时区分留证文件；无风格返回空串） */
    private static String styleTag(String style) {
        return style == null || style.isBlank() ? "" : "_" + style;
    }

    /**
     * ★诊断留证：聚合**解析失败**时，把「聚合 LLM 吐出的原文」落盘到 logs/aggfail_<sid>_<tag>.txt。
     *
     * <p>为什么必须有它：解析失败有三种成因，**在日志里长得一模一样**（都只说"无法结构化"），
     * 但治本方向完全相反 ——
     * ①输出撞 max_tokens 被截断（文件尾部 JSON 断在半截）→ 提高上限 / 聚合分片输出；
     * ②格式跑偏（带 markdown 代码块/说明文字）→ 改聚合 prompt；
     * ③内容缺失 → 走 gap 补全那条路。
     * 没有原文就只能猜，而本项目铁律是"必须拿出证据，禁止猜"。
     */
    private void dumpAggFailForDiagnosis(String tag, String text, String routeResult, String planResult) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("logs");
            java.nio.file.Files.createDirectories(dir);
            String safe = tag == null ? "" : tag.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5_]", "_");
            java.nio.file.Path f = dir.resolve("aggfail_" + sessionId + "_" + safe + ".txt");
            String body = text == null ? "" : text;
            String header = "# 聚合解析失败留证\n"
                    + "# 时间: " + java.time.LocalDateTime.now() + "\n"
                    + "# 输入长度: 路线=" + (routeResult == null ? 0 : routeResult.length())
                    + " 行程=" + (planResult == null ? 0 : planResult.length()) + "\n"
                    + "# 本次 LLM 输出长度: " + body.length() + "\n"
                    + "# 判读: 尾部JSON断在半截=被max_tokens截断 / 含markdown或说明文字=格式跑偏 / 缺sections=内容缺\n"
                    + "# ---------- 以下为 LLM 原文 ----------\n";
            java.nio.file.Files.write(f, (header + body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
            // 诊断绝不影响主流程
        }
    }

    private void dumpRawForDiagnosis(String tag, String text) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("logs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = dir.resolve("raw_" + sessionId + "_" + tag + ".txt");
            java.nio.file.Files.write(f, (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
            // 诊断留证失败绝不影响交付
        }
    }

    /** 子串出现次数（诊断用） */
    private static int countOccurrences(String hay, String needle) {
        if (hay == null || needle == null || needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * ★诊断事件：把子Agent 原始产出的关键特征摆进事件流，<b>一眼判定</b>
     * "Agent 没写时长/车程" 还是 "写了但被聚合器丢了"——不再靠猜。
     */
    private void reportRawDiagnosis(String who, String raw, TripPlanData plan) {
        try {
            String r = raw == null ? "" : raw;
            int declared = countOccurrences(r, "当日车程合计");
            int hours = countOccurrences(r, "小时");
            int blank = 0, total = 0;
            if (plan != null && plan.getSections() != null) {
                for (dto.Section s : plan.getSections()) {
                    if (s == null || s.getType() == null || !"timeline".equalsIgnoreCase(s.getType())) continue;
                    if (s.getItems() == null) continue;
                    for (dto.SectionItem it : s.getItems()) {
                        if (it == null) continue;
                        total++;
                        String dur = it.getDuration();
                        if (dur == null || dur.isBlank()) blank++;
                    }
                }
            }
            EventStore.add(sessionId, "诊断", who + "原始产出 " + r.length() + " 字符："
                    + "含「当日车程合计」" + declared + " 处、「小时」" + hours + " 处；"
                    + "结构化后 " + blank + "/" + total + " 条时长为空");
        } catch (Throwable ignore) {
            // 诊断绝不影响主流程
        }
    }

    /** ★诊断留证：把每轮送进校验的产出落盘到 logs/validate_&lt;sid&gt;_&lt;tag&gt;.json（失败不影响交付） */
    private void dumpPlanForDiagnosis(String tag, TripPlanData plan) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("logs");
            java.nio.file.Files.createDirectories(dir);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan);
            java.nio.file.Path f = dir.resolve("validate_" + sessionId + "_" + tag + ".json");
            java.nio.file.Files.write(f, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignore) {
            // 诊断留证失败绝不影响交付
        }
    }

    /**
     * ★新增：交付前约束校验与打回重做。
     *
     * <p>定位：编排本身交给 LLM 自由发挥，这里只做"<b>约束能不能过</b>"的客观判定：
     * <ol>
     *   <li>逐条校验（规则见 {@link utils.PlanValidator}），结果写入事件流（用户可见）；</li>
     *   <li>有硬违反（BLOCK）→ 把违反清单附在任务后重发，最多 {@value #MAX_VALIDATE_FIX} 次；</li>
     *   <li>仍不通过 → <b>不阻断交付</b>，但明确标注"降级交付"，绝不静默当成功。</li>
     * </ol>
     *
     * <p>作用域：仅单方案路径；不改动其他既有逻辑。
     */
    private TripPlanData validateAndFix(TripRequest req, String routeResult, TripPlanData plan,
                                       java.util.Set<String> realPlaces, utils.RouteFeasibility.Result feas) {
        try {
            String waypoints = utils.PlanValidator.isCrossCity(req) ? extractWaypoints(routeResult) : "";
            //★可行性前置（口径已定）：行程"物理上跑不完"时的违反（车程过长 → A1/A3）是【重做也修不好】的，
            //  反复打回只会白烧时间与 token（v5 实测：跑 495s，两轮重做全在修不可能修的东西）。
            //  → 此时不打回，只校验一次、如实标注并给出替代建议。
            boolean infeasible = feas != null && feas.infeasible;
            int maxFix = infeasible ? 0 : MAX_VALIDATE_FIX;
            if (infeasible) {
                EventStore.add(sessionId, "校验", "行程过赶（" + feas.note + "）——车程过长导致的违反重做无法解决，"
                        + "本次只校验、如实交付，并给出替代建议");
            }
            dto.ValidationReport last = null;
            int prevBlocking = -1; // 上一轮硬违反条数（用于"无改善即停"）
            for (int attempt = 1; attempt <= maxFix + 1; attempt++) {
                //★留证：把"本轮送进校验的产出"落盘（logs/validate_<sid>_r<N>.json）——
                //  否则像"某轮车程算成0.0h"这类问题无法定位是哪一轮、category 填了什么
                dumpPlanForDiagnosis("r" + attempt, plan);
                dto.ValidationReport vr = utils.PlanValidator.validate(plan, req, realPlaces, routeResult);
                last = vr;
                EventStore.add(sessionId, "校验", vr.summary());
                for (dto.ValidationReport.Violation v : vr.getViolations()) {
                    EventStore.add(sessionId, "校验", "· " + v);
                }
                if (vr.passed()) {
                    return plan;
                }
                if (attempt > maxFix) {
                    break; // 无可重做额度（如"跑不完"的行程）→ 直接走如实标注
                }
                //★无改善即停（实测口径）：重做一轮后硬违反【没有减少】→ 说明是系统性问题
                //  （如车程整体排不开、每天都被车程占满），再重做只是白烧时间与 token。
                //  实测依据：用例① 第2轮与第1轮同类违反；用例② 3 条 → 5 条（越改越差）。
                if (prevBlocking >= 0 && vr.blocking().size() >= prevBlocking) {
                    EventStore.add(sessionId, "校验", "重做后硬违反未减少（" + prevBlocking + " → "
                            + vr.blocking().size() + " 条），判定为系统性问题（重做改不动），停止重做、如实交付");
                    break;
                }
                prevBlocking = vr.blocking().size();
                //★用户口径（增量修补）：先由主管就"违规涉及的那几天"局部改（不重调专家Agent，实测省 200~280s），
                //  改完回到循环顶部做【完整重校验】；不适用/无效（返回 null）才回退到下面的子Agent 重做。
                TripPlanData patched = patchPlanLocally(req, plan, vr);
                if (patched != null) {
                    plan = patched;
                    continue;
                }
                EventStore.add(sessionId, "校验", "发现 " + vr.blocking().size()
                        + " 条硬违反，正在按修正清单重做（第 " + attempt + "/" + maxFix + " 次）...");
                String fixText = planTask(req, waypoints)
                        + "\n\n【必须修正的问题】上一版行程存在以下违反，请逐条改掉后重新输出【完整行程】"
                        + "（不要只输出改动部分）：\n" + vr.toFixInstruction();
                String retry = await(PARALLEL_POOL.submit(() -> remoteTool.callTripPlannerAgent(fixText)), "行程Agent·校验修正");
                if (retry == null || retry.isBlank()) {
                    EventStore.add(sessionId, "校验", "修正重做未返回有效结果，停止重做");
                    break;
                }
                //重做结果同样剥离【真实地点清单】块，并把新清单并入白名单（子Agent 这次可能查到更多地点）
                java.util.Set<String> morePlaces = utils.PlanValidator.parsePlacesBlock(retry);
                if (!morePlaces.isEmpty()) realPlaces.addAll(morePlaces);
                //★诊断留证：重做的原始产出也留证 —— 否则"重做白跑一轮"查不出是 Agent 输出格式变了还是聚合解析失败
                dumpRawForDiagnosis("fix_" + attempt, retry);
                TripPlanData retryPlan = aggregate(req, routeResult, utils.PlanValidator.stripPlacesBlock(retry));
                reportRawDiagnosis("重做产出(第" + attempt + "次) ", retry, retryPlan);
                if (retryPlan == null) {
                    EventStore.add(sessionId, "校验", "修正重做结果无法结构化，停止重做（保留上一版）");
                    break;
                }
                retryPlan.setMarkdown(renderMarkdown(retryPlan));
                plan = retryPlan;
            }
            // 仍不通过：如实标注降级交付（不阻断，避免用户拿不到结果）
            if (last != null && !last.passed()) {
                EventStore.add(sessionId, "校验", infeasible
                        ? "⚠️ 该行程过赶，仍有 " + last.blocking().size() + " 条硬违反（车程过长所致，重做无法解决），"
                                + "本次按【降级交付】处理，未修正项如下："
                        : "⚠️ 重做后仍有 " + last.blocking().size() + " 条硬违反未修正，本次按【降级交付】处理，未修正项如下：");
                for (dto.ValidationReport.Violation v : last.blocking()) {
                    EventStore.add(sessionId, "校验", "· （未修正）" + v);
                }
                if (infeasible && !feas.advice.isBlank()) {
                    EventStore.add(sessionId, "校验", "💡 " + feas.advice);
                }
            }
        } catch (Throwable t) {
            // 校验环节绝不允许影响正常产出：异常只记录，不改变已生成的结果
            EventStore.add(sessionId, "校验", "校验环节异常（不影响交付）：" + t);
        }
        return plan;
    }

    //============ ★新增：主管侧【增量修补】（不重调专家Agent） ============

    /** 增量修补：主管 LLM 只回「被修改的那几天」的替换内容 */
    public static class DayPatch {
        public Integer day;
        public List<String> stops;
        public List<SectionItem> items;
        public DayPatch() {}
    }

    /** 增量修补返回容器（对象包一层，比裸数组稳） */
    public static class DayPatchSet {
        public List<DayPatch> days;
        public DayPatchSet() {}
    }

    /**
     * ★新增：主管侧【增量修补】—— 违规只涉及少数几天时，把【那几天的现有内容 + 逐条违规】交给主管，
     * 由主管自己调一次 LLM 改这几天，其余天【一字不改】。
     *
     * <p>为什么这么做：专家Agent 是 A2A 远程调用、<b>跨调用无记忆</b>（实测两轮重做走向完全不同），
     * 所以"打回重做"实际是<b>从零重写</b>（200~280s，且会换走法、丢已排好的内容）。
     * 主管手里同时有结构化行程、逐条违规、路线、需求，具备局部修补的全部条件。
     *
     * <p>安全边界：① 只接受违规涉及的天，其他天与返回内容一律不采纳；② day=0（全局性问题）或涉及
     * 天太多（&gt;3）时不适用，返回 null 由调用方回退到原有的子Agent 重做；③ 修补后调用方会
     * 【完整重校验】，不放松任何一条约束。
     *
     * @return 修补后的行程（已重渲染 markdown）；不适用或无效时返回 null
     */
    private TripPlanData patchPlanLocally(TripRequest req, TripPlanData plan, dto.ValidationReport vr) {
        try {
            java.util.Set<Integer> days = new java.util.TreeSet<>();
            for (dto.ValidationReport.Violation v : vr.blocking()) {
                if (v.day > 0) days.add(v.day);
            }
            //全局性问题（day=0，如整体绕路/总里程算不出）或涉及天太多 → 不属于"增量"场景
            if (days.isEmpty() || days.size() > 3) {
                return null;
            }
            java.util.List<Section> targets = new java.util.ArrayList<>();
            for (Section sec : plan.getSections()) {
                Integer d = dayNoOf(sec);
                if (d != null && days.contains(d)) targets.add(sec);
            }
            if (targets.isEmpty()) {
                return null;
            }
            StringBuilder fixes = new StringBuilder();
            for (dto.ValidationReport.Violation v : vr.blocking()) {
                if (v.day > 0) {
                    fixes.append("- [").append(v.rule).append("] ").append(v.detail).append("\n");
                }
            }
            String sys = "你是行程修补专家：只修下面列出的违规，其余内容一字不改。\n"
                    + "【只输出被修改的那几天】的 JSON，格式严格为："
                    + "{\"days\":[{\"day\":N,\"stops\":[\"站点名1\",\"站点名2\"],"
                    + "\"items\":[{\"time\":\"09:00\",\"activity\":\"活动\",\"place\":\"地点\","
                    + "\"category\":\"景点\",\"cost\":\"\",\"note\":\"\",\"duration\":\"2h\"}]}]}\n"
                    + "硬性要求：① 只输出违规涉及的天，其他天【不要】出现在 JSON 里；"
                    + "② day 必须与输入的天号一致；③ 每条 timeline 记录必须带 duration（如 2h）；"
                    + "④ 时间线必须按时间递增、不重叠；⑤ 不要写括号说明、不要写工具名或\"数据获取失败\"字样；"
                    + "⑥ stops 只写站点名、同一地点只出现一次。";
            utils.SafeModel.setStage("增量修补");
            String user = "【用户需求】" + clip(req.getRaw(), 600)
                    + "\n\n【要修的问题】\n" + fixes
                    + "\n【这几天的现有内容（只改问题，其余保持原样）】\n"
                    + clip(JsonUtils.toJson(targets), 6000);
            String text = callModelJson(sys, user);
            //★局部宽容解析：JsonUtils 的 ObjectMapper 是默认配置（未知字段会抛异常），
            //  而模型返回的 JSON 常带多余字段。这里用本方法专用的宽容 mapper 解析，
            //  避免"改一处全局配置、影响聚合等既有链路"。
            DayPatchSet set = null;
            try {
                String json = JsonUtils.extractJsonObject(text);
                if (json != null && !json.isBlank()) {
                    com.fasterxml.jackson.databind.ObjectMapper lenient =
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                    .configure(com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_UNKNOWN_PROPERTIES, false);
                    set = lenient.readValue(json, DayPatchSet.class);
                }
            } catch (Exception ignore) {
                //解析失败按"未返回可用结果"处理，下面统一回退专家Agent 重做
            }
            if (set == null || set.days == null || set.days.isEmpty()) {
                EventStore.add(sessionId, "校验", "增量修补未返回可用结果，回退专家Agent 重做");
                return null;
            }
            int replaced = 0;
            for (DayPatch p : set.days) {
                if (p == null || p.day == null || !days.contains(p.day)) continue; // 只接受违规涉及的天
                if (p.items == null || p.items.isEmpty()) continue;
                for (Section sec : plan.getSections()) {
                    Integer d = dayNoOf(sec);
                    if (d != null && d.equals(p.day)) {
                        sec.setItems(p.items);
                        if (p.stops != null && !p.stops.isEmpty()) sec.setStops(p.stops);
                        replaced++;
                        break;
                    }
                }
            }
            if (replaced == 0) {
                EventStore.add(sessionId, "校验", "增量修补没有改到任何一天，回退专家Agent 重做");
                return null;
            }
            EventStore.add(sessionId, "校验", "主管增量修补：已改 " + replaced + " 天" + days
                    + "（不重调专家Agent），重新完整校验");
            plan.setMarkdown(renderMarkdown(plan));
            return plan;
        } catch (Throwable t) {
            //增量修补绝不允许影响正常产出：异常只记录，返回 null 让调用方走原有重做
            EventStore.add(sessionId, "校验", "增量修补异常（已回退原有重做）：" + t);
            return null;
        }
    }

    /** 从区块标题解析天数（"第3天" → 3；非按天区块返回 null） */
    private Integer dayNoOf(Section sec) {
        if (sec == null || sec.getTitle() == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*天").matcher(sec.getTitle());
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** 多方案编排：并行聚合出按【实际路线差异】的 3 套方案（主路线/环线换序/替代路线）供前端对比。 */
    public PlanSet runMultiScheme(String userPrompt) {
        ReqAndData rd = resolveAndFetch(userPrompt, null);
        String missingInput = missingInputs(rd.routeResult, rd.planResult); // 多方案路径同样亮红灯
        if (!missingInput.isBlank()) {
            EventStore.add(sessionId, "整合", "⚠️ " + missingInput + "，本次结果可能不完整，将如实标注缺失部分");
        }
        EventStore.add(sessionId, "整合", "正在生成 3 套差异化方案（三种风格各不相同）...");
        PlanSet set = aggregateMulti(rd.req, rd.routeResult, rd.planResult);
        //每套方案补降级 markdown（前端打印/导出用）
        if (set != null) {
            for (TripPlanData p : set.getPlans()) {
                if (p != null && p.getMarkdown().isBlank()) {
                    p.setMarkdown(renderMarkdown(p));
                }
            }
        }
        EventStore.add(sessionId, "完成", "多方案生成完成（" + (set == null ? 0 : set.getPlans().size()) + " 套）");
        return set;
    }

    /** 解析需求 + 并行 DAG（单方案/多方案共享的前置步骤； */
    private ReqAndData resolveAndFetch(String userPrompt, TripRequest.Preferences prefs) {
        //(1) 需求解析（单次结构化 LLM 调用）
        EventStore.add(sessionId, "解析", "正在解析旅行需求...");
        TripRequest req = parseRequest(userPrompt);
        if (prefs != null && !prefs.isEmpty()) {
            req.setPreferences(prefs);
        }
        EventStore.add(sessionId, "解析", "需求：" + req.getOrigin() + " → " + req.getDestination()
                + "，" + req.getDays() + " 天" + (req.getPreference().isBlank() ? "" : "（" + req.getPreference() + "）")
                + buildPreferenceLabel(req));
        checkCancel();

                /* 结果缓存（进程内）：key=SHA1(目的+|天数|出发|+个人中心沉淀偏好)。 */
        String storedPrefs = (req.getPreferences() != null && !req.getPreferences().isEmpty())
                ? req.getPreferences().toPromptText() : "";
        utils.cache.PlanCache.Entry hit = utils.cache.PlanCache.get(
                req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs, req.getPreference());
        if (hit != null) {
            EventStore.add(sessionId, "解析", "· 命中结果缓存，跳过专家调用 ·");
            //★用户口径（2026-09-13）：缓存命中后两个专家 Agent 不再调用，但前端的阶段卡片是
            //  「只有事件真到达才打勾」✗ → 「行程安排 / 路线规划」会一直停在"待命"，用户看到的是没做完 ✗
            //  按要求：命中缓存也要显示【已完成】✓ —— 本次结果确实来自这两步（只是复用上次产出），
            //  因此按事实补发这两条事件，前端据此把两个阶段标为「已完成」。
            EventStore.add(sessionId, "行程", "命中结果缓存：行程安排复用上次结果（已完成）");
            EventStore.add(sessionId, "路线", "命中结果缓存：路线与车程复用上次结果（已完成）");
            return new ReqAndData(req, hit.routeResult, hit.planResult);
        }

        //(2) 偏好分流：LLM 把文本+个人中心偏好分为行程/路线/聚合三类
        boolean hasTextPref = req.getPreference() != null && !req.getPreference().isBlank();
        boolean hasStoredPref = req.getPreferences() != null && !req.getPreferences().isEmpty();
        if (hasTextPref || hasStoredPref) {
            classifyPreference(req);
        }
        //(3) 编排：统一「先行程后路线」——跨城与单城市不再区分顺序。
        //   业务理由：走向（经桂林还是经重庆）本身是行程决策、直接决定途中玩什么；
        //   先跑路线Agent 会把它锁死在 ≤5 个途经城市上（extractWaypoints 上限），行程可能被带偏。
        //   可行性不因此失守：交付前校验会判「过赶」并如实标注 + 给替代方案（路线文本预检 + 用行程自身车程复核）。
        boolean crossCity = req.getOrigin() != null && !req.getOrigin().isBlank()
                && req.getDestination() != null && !req.getDestination().isBlank()
                && !req.getOrigin().trim().equals(req.getDestination().trim());
        boolean cityTour = !crossCity;  // 单城 / 起点=终点 / 无起点 -> 市内
        EventStore.add(sessionId, "行程", crossCity
                ? "跨城自驾，先规划行程（自定走向+沿途景点），再补算路线..."
                : "市内游，先规划行程提取景点清单...");
        String planFirst = await(PARALLEL_POOL.submit(() ->
                remoteTool.callTripPlannerAgent(planTask(req, ""))), "行程Agent");
        checkCancel();
        //跨城：锚点必须覆盖整条跨省动线 —— 原 extractSights 是市内口径（只抽景点名、上限 5），
        //     跨城时前 5 个匹配很可能全落在第一个省，路线Agent 会拿不到动线。
        String sights = crossCity ? extractCrossCityAnchors(planFirst, req)
                                  : extractSights(planFirst, req.getDestination());
        //★用户口径（2026-09-13）：补上「路线」阶段事件 —— 编排器以前【从不发这个事件】✗
        //  → 前端「路线规划」那张卡永远显示"待命"（其实路线Agent 一直在跑 ✓），进度条也从行程直接跳到整合 ✗
        //  这里只增加事件，不动任何判定与调用逻辑。
        EventStore.add(sessionId, "路线", crossCity
                ? "正在补算跨城路线与里程（停靠点 " + (sights == null || sights.isBlank() ? 0 : sights.split("[，,、\\n]").length) + " 个）..."
                : "正在计算市内各景点之间的路线与车程...");
        String routeResult2 = await(PARALLEL_POOL.submit(() ->
                remoteTool.callRouteMakingAgent(routeTask(req, sights))), "路线Agent");
        EventStore.add(sessionId, "路线", routeResult2 == null || routeResult2.isBlank()
                ? "路线Agent 未返回有效内容（已如实标注，不影响行程交付）"
                : "路线计算完成（" + routeResult2.strip().length() + " 字符），已并入行程");
        checkCancel();
        //★防线：行程Agent 产出为空（超时/被中断/只回了一句开场白）时，绝不能写缓存 ——
        //  空行程一旦入缓存，后续同一请求会命中它、跳过真实调用，校验只能基于残缺数据报一堆错、
        //  再打回重做，等于白跑一轮（实测两轮对照都因此多花 200~280s）。
        if (planFirst != null && planFirst.strip().length() >= 300) {
            utils.cache.PlanCache.put(req.getDestination(), req.getDays(), req.getOrigin(), storedPrefs,
                    req.getPreference(), routeResult2, planFirst);
        } else {
            log.warn("[PlanCache] 行程Agent 产出异常({} 字符)，本轮回退为不使用结果缓存，避免污染后续同请求",
                    planFirst == null ? 0 : planFirst.strip().length());
        }
        return new ReqAndData(req, routeResult2, planFirst);
    }

    /** 解析+DAG 结果载体 */
    private static class ReqAndData {
        final TripRequest req;
        final String routeResult;
        final String planResult;
        ReqAndData(TripRequest req, String routeResult, String planResult) {
            this.req = req; this.routeResult = routeResult; this.planResult = planResult;
        }
    }

    //==================== (1) 需求解析 ====================

    private TripRequest parseRequest(String prompt) {
        utils.SafeModel.setStage("解析需求"); // 调用链：该 LLM span 说明标注"在做什么"
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
        //调用链：把解析结果摘要回填到该 LLM span 说明（"解析需求 - 目的地:xx 天数:x"）
        try {
            StringBuilder sum = new StringBuilder("目的地:")
                    .append(req.getDestination() == null || req.getDestination().isBlank() ? "?" : req.getDestination())
                    .append(" 天数:").append(req.getDays());
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

    /** 偏好标签（事件流展示用，如「步行<=1万米 - 早起 - 博物馆多」） */
    private String buildPreferenceLabel(TripRequest req) {
        return ""; // 前端选项卡已删，偏好仅来自用户输入+个人中心
    }

    /** 偏好约束（聚合 prompt 用，引导模型按偏好安排行程节奏） */
    private String buildPreferencePrompt(TripRequest req) {
        return ""; // 预检约束不再拼入聚合提示词
    }

    //==================== (2) 并行 DAG ====================

    private String routeTask(TripRequest req, String sightList) {
        java.util.List<String> routePrefs = new java.util.ArrayList<>();
        if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) routePrefs.add(req.getRoutePref().trim());
        String routePrefStr = routePrefs.isEmpty() ? "" : String.join(" ", routePrefs);
        //sightList 非空 -> 市内游且已由行程Agent定好景点，路线按这些景点查衔接
        String dest = (req.getDestination() == null || req.getDestination().isBlank()) ? "" : req.getDestination();
        boolean noOrigin = req.getOrigin() == null || req.getOrigin().isBlank();
        if (utils.PlanValidator.isCrossCity(req) && sightList != null && !sightList.isBlank()) {
            // ★跨城 + 行程先行：按行程已定的走向/停靠点补算跨城里程与每日驾驶段。
            //   绝不能落到下面那句「mode=urban / 不要编造跨城里程」的市内分支 ——
            //   那会让跨城路线数据全废，连带可行性预检（读总里程）一起失效。
            return "这是从 " + req.getOrigin() + " 到 " + dest + " 的 " + req.getDays() + "天自驾行程。\n"
                    + "行程Agent已排定走向与停靠点（按先后顺序）：" + sightList + "\n"
                    + "★请调用 query_route_unified(mode=driving" + (routePrefStr.isBlank() ? "" : ", routePref=" + routePrefStr) + ")，"
                    + "按上面停靠点的先后顺序给出：\n"
                    + "  ①【全程总里程】—— 必须给出一个明确的总里程数字（后续可行性判定要用它）\n"
                    + "  ②相邻停靠点之间的里程与预计驾驶时间（小时）\n"
                    + "  ③【不绕路直达里程】—— 起点→终点不绕路的最省路线里程，"
                    + "**必须写成「不绕路直达：约X公里」这个格式**（走向合理性的判定基准）\n"
                    + "★不要改变停靠顺序、不要增删停靠点、不要凭常识编造里程：里程与耗时必须以工具返回为准。\n"
                    + "★严格限制：总行程正好 " + req.getDays() + " 天，不得增加天数。\n"
                    + "最终回复：完整可读的跨城自驾路线，含每日行车段、里程、耗时。";
        }
        if (noOrigin || sightList != null && !sightList.isBlank()) {
            //市内/行程先行：路线Agent针对"行程已定的景点"查衔接，不再自己猜景点
            String base = "这是 " + dest + " 的" + req.getDays() + "天市内/周边游（用户未提供出发地）。\n";
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
        return "请规划从 " + req.getOrigin() + " 到 " + dest + " 的自驾路线，"
                + req.getDays() + " 天，包含每日行车路线、途经景点、驾驶时间与费用。\n"
                + "★调用 query_route_unified，mode 参数driving" + (routePrefStr.isBlank() ? "" : "，routePref=" + routePrefStr)
                + "（自驾路线与距离），查询各段自驾线路。\n"
                + "★严格限制：总行程必须正好 " + req.getDays() + " 天，不得增加天数、不得扩展为更长的环线。\n"
                + "如果 " + req.getDays() + " 天跑不完某条长线，请选择适合 " + req.getDays() + " 天的缩短路线（或减少景点），"
                + "天数只允许少不允许多。";
    }

    private String planTask(TripRequest req, String waypoints) {
        //行程偏好：LLM 分类出的 tripPref
        java.util.List<String> tripPrefs = new java.util.ArrayList<>();
        if (req.getTripPref() != null && !req.getTripPref().isBlank()) tripPrefs.add(req.getTripPref().trim());
        String prefStr = tripPrefs.isEmpty() ? "" : String.join(" ", tripPrefs);
        // ★用户硬性要求（必去/回避/预算）必须传给行程Agent —— 原来这几项根本没进提示词，
        //   导致"必去的地方没安排""超预算"这类问题在生成阶段就无法被遵守。
        {
            StringBuilder hb = new StringBuilder(prefStr);
            if (req.getMustVisit() != null && !req.getMustVisit().isEmpty()) {
                hb.append(hb.length() == 0 ? "" : "；").append("必去：").append(String.join("/", req.getMustVisit()));
            }
            if (req.getPreferences() != null && req.getPreferences().getAvoid() != null
                    && !req.getPreferences().getAvoid().isEmpty()) {
                hb.append(hb.length() == 0 ? "" : "；").append("回避：")
                        .append(String.join("/", req.getPreferences().getAvoid()));
            }
            if (req.getBudgetTotal() > 0) {
                hb.append(hb.length() == 0 ? "" : "；").append("预算上限：")
                        .append((long) req.getBudgetTotal()).append("元");
            }
            prefStr = hb.toString();
        }
        if (waypoints != null && !waypoints.isBlank()) {
            //跨城自驾：查沿途城市景点
            return "请规划从 " + req.getOrigin() + " 到 " + req.getDestination() + " 的" + req.getDays() + "天自驾行程，"
                    + "重点规划【路途沿途】的景点。\n"
                    + "★调用 query_trip 时，把途经城市传 waypoints 参数（逗号分隔）：" + waypoints + "，"
                    + "只查这些途经点的景点/美食，不要查起点或终点城市内部的景点。\n"
                    + "结合路线按 Day 每日给出途经景点行程与安排。"
                    + (prefStr.isBlank() ? "" : "★把行程偏好作为 preference 参数传入：" + prefStr + "。")
                    + "\n★沿途路景必须实查：一并查询沿途值得看的自然风光（草原、雪山、湖泊、垭口、峰林等），"
                    + "按【不用停车就能看】的口径呈现——写进当天车程条目的说明里，不单列条目、不占游玩时长。"
                    + hardConstraints();
        }
        if (utils.PlanValidator.isCrossCity(req)) {
            // ★跨城 + 行程先行（统一「先行程后路线」）：走向由行程Agent 自己定，
            //   不再由路线Agent 预先给定途经城市（那会把行程锁死在 ≤5 个城市上）
            return "请规划从 " + req.getOrigin() + " 到 " + req.getDestination() + " 的" + req.getDays() + "天自驾行程。\n"
                    + "★【你要自己决定走向】：判断走哪条大动脉更合理（例如经桂林/贵州，或经重庆/成都），"
                    + "并逐天写清途经城市与停靠点（第1天…第N天）。\n"
                    + "★【以沿途景点择优】：定走向前，先比较**至少两条候选走廊**各自的沿途景点/路景丰富度"
                    + "（用 query_trip 按候选城市查），选沿途更值得走的那条；\n"
                    + "  但必须【在合理范围内】：最终总里程不得超过「起点→终点不绕路直达里程」的 120%，"
                    + "也不得违反单日车程约束——不能为了多看一个景点绕大圈。\n"
                    + "★调用 query_trip 时，把你拟定要经过的城市传 waypoints 参数（逗号分隔）——"
                    + "【必须写到县级】如「稻城县」「理塘县」「丹巴县」，最多 6 个，按行进顺序挑主要落脚县；"
                    + "只写到州/市级会查不到当地标志性景区（实测：传「甘孜州」查不出稻城亚丁，传「稻城县」才查得到）。\n"
                    + "查这些沿途县/市的景点/美食；**不要查起点或终点城市内部的景点**。\n"
                    + "★沿途路景必须实查：一并查询沿途值得看的自然风光（草原、雪山、湖泊、垭口、峰林等），"
                    + "但按【不用停车就能看】的口径呈现——写进当天车程条目的说明里，不单列条目、不占游玩时长。\n"
                    + (prefStr.isBlank() ? "" : "★把行程偏好作为 preference 参数传入：" + prefStr + "。\n")
                    + "★里程与车程必须来自工具返回的真实数据，不得凭常识估一个数。\n"
                    + hardConstraints();
        }
        return "请规划 " + req.getDestination() + " " + req.getDays() + " 日游的景点与每日行程，"
                + "包含景点、门票、住宿、餐饮美食"
                + (prefStr.isBlank() ? "" : "，偏好：" + prefStr) + "。\n"
                + "★调用 query_trip 时，把上述偏好作为 preference 参数传入，"
                + "让固定链按偏好查询更贴合的数据(如'亲子景点'/'美食餐厅')，不要忽略偏好查通用清单。"
                + hardConstraints();
    }

    /**
     * ★新增：硬约束块（与 {@link utils.PlanValidator} 的校验规则一一对应）。
     *
     * <p>放在此处是为了保证 <b>prompt 与校验单一来源</b>：约束文案改了，校验口径同步改，
     * 不会出现"prompt 说 A、校验按 B"导致一个放行一个打回、来回震荡。
     */
    private String hardConstraints() {
        return "\n\n【硬约束：交付前会逐条校验，违反会被打回重做】\n"
                + "1) 每天「景点游玩 + 车程」合计 ≤ 8 小时；排不下就少安排一个景点，【禁止】压缩游玩时间凑数。\n"
                + "2) 每天纯游玩时长【下限随车程收敛】：下限 = 8 − 当天车程（4 小时封顶）——" + "车程≤4h 时至少玩 4h；车程 5h 至少玩 3h；车程 6h 至少玩 2h（车程越长能玩的时间越少，这是常识）。\n"
                + "3) 单日赶路 ≤ 6 小时；夜间/凌晨（23:00–05:00）【禁止】安排赶路。\n"
                + "4) 单日赶路 > 4 小时时，该天必须安排沿途真实景点，不能只赶路。\n"
                + "5) 每天必须有完整的一天时间线，天数必须正好等于要求的 N 天，一天都不能少。\n"
                + "6) 景点/餐厅/酒店名称必须带城市或行政区前缀（如\"成都市·宽窄巷子\"），"
                + "【禁止】编造未查询到的地点，也不要把自由活动写成景点。\n"
                + "7) 时间线按时间先后递增，地点顺序与车程自洽（相邻点车程要与距离相符，不得出现\"瞬移\"）；"
                + "每条时间线请写清**起止时间或时长**（如 09:00-11:00 或 约2小时），"
                + "车程类条目写明预估车程（如 自驾约3.5小时）。\n"
                + "8) 【禁止重复查询】同一组参数的查询只允许一次；确需补充信息时，请换更具体的参数去查新内容，"
                + "不要反复查同样的数据（重复查询不会带来新信息，只会拖慢交付）。\n"
                + "9) 行程最后必须另起一行输出【真实地点清单】块：只列你本次通过工具**实际查询到**的地点名称"
                + "（景点/餐厅/酒店，带城市前缀），每行一个；没有查到的不要写（这份清单会被用来校验行程里有没有编造地点）。\n"
                + "10) ★每天的行程末尾必须单独一行写明「当日车程合计：约X小时」——这是校验判定车程的【权威依据】："
                + "写了就按它判定；不写则只能按里程粗略估算（可能判定不准）。"
                + "注意：餐饮/休整时间【不要】算进车程，也不要算进游玩时长。\n"
                + "11) ★【沿途路景】（用户要求：**每一条车程都必须带一句沿途可看的风光**）——\n"
                + "   路景 = 路线上【不用停车就能看到】的风景（峰林/河谷/草原/垭口/湖泊/雪山/戈壁等）：\n"
                + "   · ★【必须】给当天每一条车程都写一句沿途风光，写进该交通条目的 note（说明）里；\n"
                + "     正例：「note\":\"沿 G30 穿越天山北麓，途中有果子沟大桥与赛里木湖沿岸可观，垭口可远眺雪山」\"\n"
                + "            「note\":\"翻越哈希勒根达坂（独库公路），沿途草原与雪岭交替，注意落石\"」\n"
                + "     确实没有特殊景观的段落，也要写实际经过的地貌（丘陵/河谷/戈壁/戈壁滩），不许空着。\n"
                + "   · 【只能】写在交通条目的 note 里：【禁止】写进 place/activity（否则概览卡片与轨迹会把路景当成站点显示）\n"
                + "     、【禁止】写进 stops；\n"
                + "   · 【禁止】为路景单列时间线条目、【禁止】把它算进景点游玩时长——它不占行程时间；\n"
                + "   · 【禁止】把路景写成「停车进入的景点」，也不要为它编造门票/开放时间。\n"
                + "12) ★【逐日停靠点】块（跨城自驾必给）：在【真实地点清单】块之后另起一行输出，"
                + "每天一行、格式严格为「第N天：城市名」——只写当天落脚/过夜的城市（带市/县/州后缀，如「第2天：贵阳市」），"
                + "【不要】写景点、不要写街区；必须按实际行进顺序排列。"
                + "（这份块是路线Agent 计算里程的权威依据，写错会导致里程与可行性判定出错。）\n"
                + "14) ★【开放时间只用于排程】：能查到就调用 query_place_detail（传名称+城市）查到开放时间，"
                + "写进该条目的文字里，格式固定为「开放 HH:mm-HH:mm」，并让到访时间落在开放时段内"
                + "（避免交了行程却碰上涨馆/闭园）。【查不到就按全天开放处理】—— 不要因此少排景点，"
                + "也不要在行程里解释原因（不要写「工具未返回」「故不列开放时间」这类话）。\n"
                + "16) ★【交付文案是给游客看的】：行程文本里【禁止】出现任何过程性/内部文字 —— "
                + "「数据获取失败」「未返回」「工具名（query_trip / query_place_detail / query_route_unified 等）」"
                + "「waypoints」「本地知识库」「如实标注」「不列」「未纳入」「备选/未采用方案」等；"
                + "【禁止】写「数据获取情况」「先说清楚数据来源与缺口」这类说明段落；"
                + "某项数据没查到就【直接不写那一条】，也不要解释为什么没查到。\n"
                + "13) ★【走向合理性】沿途景点可以影响选路，但必须【在合理范围内】："
                + "最终总里程不得超过「起点→终点不绕路直达里程」的 120%（交付前会判定）；"
                + "选路时先比较至少两条候选走廊的沿途景点丰富度再定，不要为多看一个景点绕大圈。\n"
                + "15) ★【动线单向、先定住宿锚点】：动笔排每天的行程前，先把【住宿点】定下来（写进当天的住宿条目）；"
                + "当天的游览点要按住宿点【顺路单向串起来】，【禁止】出现「先去了 A → 返回住宿点 → 又去反方向的 B」这种折返；"
                + "同一天同一地点不要安排两次到访（确实需要时，如白天游览 + 晚上夜游，必须在条目里写明理由）；"
                + "若必须中途回住宿点附近用餐，要说明它顺路。\n"
                + "（若为单城市行程：1)~4) 与夜间赶路不做校验，但 5)~15) 仍然校验；"
                + "单城市无沿途概念，11) 自然不适用，12) 可给可不给。）";
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
        //剔除起终点城市名本身
        seen.removeIf(n -> n.contains("起点") || n.contains("终点"));
        return String.join(",", seen);
    }

    /** 从行程结果提取景点名（带景区/园/馆等后缀，最多5个）。 */
    /**
     * ★跨城锚点抽取：给路线Agent 的「行程已定停靠点」清单（<b>保序</b>，动线必须有序）。
     *
     * <p>为什么要单独一套口径：{@link #extractSights} 是市内设计（只抽景点名、上限 5）。
     * 跨城行程横跨几省时，前 5 个匹配很可能全落在第一个省 → 路线Agent 拿不到整条动线锚点。
     * 这里复用 {@code PlanValidator.placesFromText} 已调好的正则与前缀裁剪（如「成都出发前往康定市」→「康定市」），
     * 但<b>保留出现顺序</b>，并把上限按天数放大（约每天 3 个）。
     */
    private String extractCrossCityAnchors(String planResult, TripRequest req) {
        if (planResult == null || planResult.isBlank()) return "";
        int days = (req == null || req.getDays() <= 0) ? 4 : req.getDays();
        int cap = Math.max(6, Math.min(20, days * 2));
        // ★治本：不再正则刮自由文本 —— 实测刮出来是「阿坝州,落点理县,车程为区,尧山景区…」这种垃圾，
        //   还会把顺序搞乱（终点跑到最前），害路线Agent 连出折返式路线（同题 1827km 变 4844km）。
        //   改为读行程Agent 按第12条硬约束输出的【逐日停靠点】块（机器可读、按天有序）。
        java.util.TreeMap<Integer, String> byDay = new java.util.TreeMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*(\\d+)\\s*天\\s*[：:]\\s*([\\u4e00-\\u9fa5]{2,12})")
                .matcher(planResult);
        while (m.find()) {
            try {
                byDay.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).trim());
            } catch (Throwable ignore) { }
        }
        java.util.List<String> stops = new java.util.ArrayList<>(byDay.values());
        // 兜底：块没给（LLM 漏写）时，取每天的「入住/住宿」城市 —— 实测这行稳定、且就是当天落脚点
        if (stops.isEmpty()) {
            java.util.regex.Matcher r = java.util.regex.Pattern
                    .compile("(?:入住|住宿)\\s*[：:]\\s*([\\u4e00-\\u9fa5]{2,10}?)(?:[·（(，,。；;]|月|$)")
                    .matcher(planResult);
            while (r.find() && stops.size() < cap) {
                String s = r.group(1).trim();
                if (!s.isEmpty() && !stops.contains(s)) stops.add(s);
            }
        }
        String origin = (req == null || req.getOrigin() == null) ? "" : req.getOrigin().trim();
        String dest = (req == null || req.getDestination() == null) ? "" : req.getDestination().trim();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : stops) {
            if (out.size() >= cap) break;
            if (s.equals(origin) || s.equals(dest)) continue;
            if (!out.contains(s)) out.add(s);
        }
        return String.join(",", out);
    }

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

    /** 从专家产物提取高德天气原文，格式兼容： 【天气】\n<原文> 或 【目的地天气|xxx】\n<原文> 或 【xxx天气】\n<原文> */
    private String extractWeatherRaw(String combined) {
        if (combined == null || combined.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("【[^】]*天气[^】]*】\\s*\\n([\\s\\S]{2,120}?)\\n")
                .matcher(combined);
        if (m.find()) {
            return m.group(1).trim();
        }
        //兜底：提取 "天气：" 后最近一行
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("天气[\\s:：]([^\\n\\r]{2,80})")
                .matcher(combined);
        return m2.find() ? m2.group(1).trim() : null;
    }

    /** 偏好分类：1次LLM把偏好分为行程(tripPref)/路线(routePref)/聚合(aggPref)三类。 */
    private void classifyPreference(TripRequest req) {
        utils.SafeModel.setStage("偏好分类"); // 调用链：该 LLM span 说明标注阶段
        String sys = AgentPrompts.preferenceClassifier();
        //个人中心偏好并入分类输入
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
                //调用链：分类结果摘要回填
                StringBuilder sum = new StringBuilder("行程偏好:")
                        .append(req.getTripPref() == null || req.getTripPref().isBlank() ? "无" : req.getTripPref());
                if (req.getRoutePref() != null && !req.getRoutePref().isBlank()) {
                    sum.append(" 路线偏好:").append(req.getRoutePref());
                }
                utils.CallTrace.appendLastLlmDetail(sessionId, sum.toString());
                return;
            }
        } catch (Exception ignored) { }
        //解析失败兜底：全部留给聚合
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

    /** 等待 Future 结果，超时/异常返回空串并记录事件； */
    private String await(Future<String> future, String label) {
        RemoteTimeout.configure(utils.NacosModelConfig.orchestrationConfig());
        Duration timeout = RemoteTimeout.timeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                checkCancel(); // 中断检查：阻塞等待期间持续轮询，不再等步骤边界
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
                    //解包：后台任务抛出的 CancelledException（用户中断）要从 Future 里取出来向上传播
                    if (ex.getCause() instanceof CancelledException) {
                        throw (CancelledException) ex.getCause();
                    }
                    throw ex;
                } catch (TimeoutException e) {
                    //本轮 500ms 内未完成，回到循环头重新检查中断
                }
            }
        } catch (CancelledException e) {
            future.cancel(true); // 用户中断：取消后台任务后向上传播
            EventStore.add(sessionId, "Agent结果", label + " ⏹ 已中断");
            throw e;
        } catch (Exception e) {
            log.error("{} 调用失败: {}", label, e.getMessage());
            EventStore.add(sessionId, "Agent结果", label + " ❌ 调用失败(" + e.getMessage() + ")");
            return "";
        }
    }

    //==================== (3) 聚合 ====================

    private TripPlanData aggregate(TripRequest req, String routeResult, String planResult) {
        return aggregateWithStyle(req, routeResult, planResult, null);
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

    /** 单套方案聚合（style=null 表示默认无风格； */
    private TripPlanData aggregateWithStyle(TripRequest req, String routeResult, String planResult, String style) {
        utils.SafeModel.setStage("聚合生成"); // 调用链：聚合 LLM span 标注阶段
        String sysPrompt = AgentPrompts.tripAggregator()
                + buildStyleHint(style)
                + missingInputRule(routeResult, planResult); // 缺失侧如实标注铁律（数据齐全时为空串不加约束）
        String userPrompt = "用户需求：" + req.getRaw()
                + buildPreferencePrompt(req)  // 偏好约束
                + "\n\n【路线数据】\n" + clip(routeResult) + "\n\n【行程数据】\n" + clip(planResult);
        String text = callModelJson(sysPrompt, userPrompt);
        TripPlanData plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        if (plan == null) {
            dumpAggFailForDiagnosis("1次" + styleTag(style), text, routeResult, planResult); // ★留证：第1次吐出的原文
            //首次解析失败：带精简概览+修改指示重试一次（不整段回灌）
            String fix = AgentPrompts.tripAggregatorParseRetry()
                    + "【用户需求】" + req.getRaw() + "\n"
                    + "【路线数据概要】\n" + clip(routeResult, 800) + "\n"
                    + "【行程数据概要】\n" + clip(planResult, 1500);
            text = callModelJson(fix, "请输出合法 JSON 行程。");
            plan = JsonUtils.extractAndParse(text, TripPlanData.class);
        }
        if (plan == null) {
            dumpAggFailForDiagnosis("2次_解析重试后仍失败" + styleTag(style), text, routeResult, planResult); // ★留证
            return null;
        }
        //聚合评分（纯代码）：缺关键内容->带缺失提示重试1次->仍缺返回null外层兜底
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
                //仍缺失 -> 返回null，外层降级markdown兜底
                dumpAggFailForDiagnosis("gap补齐后仍缺" + styleTag(style), retryText, routeResult, planResult); // ★留证
                EventStore.add(sessionId, "整合", "聚合重试后仍缺失，降级为原文兜底");
                return null;
            }
        }
        //天气处理：优先专家数据（高德 query_trip 原文）-> 清洗剔除 LLM 表格残留
        //专家文本无有效天气时直连高德预报接口补真实天气（不依赖 LLM 重述，防编造）
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
        //调用链：聚合结果摘要回填（"聚合生成 - 生成:xx日游"）
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
        if (s.matches("\\|.+\\|")) return ""; // 整行管道符表格结构 -> 无有效天气内容
        return s.replaceAll("^\\s*\\|+\\s*|\\s*\\|+\\s*$", "").trim();
    }

    /** 高德天气直连：按目的地取未来几天真实预报（不依赖 LLM 重述，防编造/表格残留），失败返回 null */
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

    /** 3 套差异化方案并行聚合（按【旅行风格】区分：风格由模型按本次目的地自己定，不固定） */
    private PlanSet aggregateMulti(TripRequest req, String routeResult, String planResult) {
        //★用户口径（最新）：三套方案按【旅行风格】区分，风格由模型按本次目的地自己定（不要固定三件套、不要路线说法）
        String styleNote = "【风格要求】三套方案必须按【旅行风格】区分，风格由你【根据本次目的地与行程特点自己选定】，"
                + "不要套用固定的三件套；三套之间玩法侧重必须真的不同 —— "
                + "每天的景点取舍、餐饮安排、住宿区段、节奏强度，至少有两项有实质差异。"
                + "禁止只改措辞、只换个标题、或把同一套行程换个说法。"
                + "每天仍须满足：游玩+车程≤8h、纯游玩≥4h、单日赶路≤6h、23:00–05:00 不赶路。"
                + "【命名要求】标题用 6~14 个字点明这套的风格并带上目的地，前后两段【用「/」分隔】，"
                + "例如「大连五日 / 海风老街慢游」「川西五日 / 雪山出片线」；"
                + "严禁出现「方案一/方案二/方案三」「方案1/2/3」「主路线」「环线换序」「替代路线」「备用路线」这类生硬字样。";
        String[][] styles = {
                {"s1", "", "本次方案请你先判断本次目的地最适合哪种旅行风格，选【第一个风格】并围绕它编排："
                        + "把该风格在景点取舍、餐饮、节奏、住宿上的特点落到实处。" + styleNote},
                {"s2", "", "本次方案请你换一个【与第一套明显不同、但同样贴合本次目的地的风格】，"
                        + "在景点取舍、餐饮安排、住宿区段、节奏强度上体现差别。" + styleNote},
                {"s3", "", "本次方案请你再换一个【与前两套都不同、同样贴合本次目的地的风格】，"
                        + "三套合起来覆盖不同人群的偏好。" + styleNote},
        };
        //并行提交 3 套聚合（PARALLEL_POOL 已 4 线程）
        @SuppressWarnings("unchecked")
        Future<TripPlanData>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures[i] = PARALLEL_POOL.submit(() ->
                    aggregateWithStyle(req, routeResult, planResult, styles[idx][2]));
        }
        PlanSet set = new PlanSet();
        int successCount = 0;
        for (int i = 0; i < 3; i++) {
            TripPlanData plan = null;
            try {
                //中断感知：轮询等待，点中断立即生效（原 300s 一次性阻塞，中断要等聚合跑完）
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
                while (true) {
                    checkCancel();
                    long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainMs <= 0) { plan = null; break; }
                    try {
                        plan = futures[i].get(Math.min(remainMs, 500L), TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException te) {
                        //回到循环头重新检查中断
                    }
                }
            } catch (CancelledException ce) {
                for (Future<TripPlanData> f : futures) f.cancel(true); // 用户中断：取消全部并行聚合
                throw ce;
            } catch (Exception e) {
                log.error("方案「{}」聚合失败: {}", styles[i][1], e.getMessage());
            }
            if (plan == null) {
                //2026-09-07 单套生成失败自动重试1次：实测美食向套120s LLM超时被掐断->首试失败
                //立即重跑（偶发超时重试多能成功），重试仍失败才退回 markdown 原文兜底，
                //避免用户看到"某方案是原文"而其它两套正常
                EventStore.add(sessionId, "整合", "方案「" + styles[i][1] + "」生成超时/失败，自动重试...");
                log.warn("方案「{}」首次生成失败，自动重试1次", styles[i][1]);
                try {
                    plan = aggregateWithStyle(req, routeResult, planResult, styles[i][2]);
                } catch (Exception e) {
                    log.error("方案「{}」重试异常: {}", styles[i][1], e.getMessage());
                    plan = null;
                }
                if (plan == null) {
                    plan = fallbackPlan(req, routeResult, planResult); // 重试仍失败退回 markdown 兜底
                    plan.setTitle(req.getDestination() + req.getDays() + "日游");
                }
            }
            //★用户口径：方案标题必须用「/」分隔前后两段（模型偶尔用 · 或 ｜，或干脆不分隔）→ 这里统一兜底
            plan.setTitle(normalizeSchemeTitle(plan.getTitle(), req));
            set.getPlans().add(plan);
            set.getStyles().add(styles[i][0]);
            set.getSlogans().add(styles[i][1]);
            if (plan.getSections() != null && !plan.getSections().isEmpty()) {
                successCount++;
            }
        }
        //全部结构化失败则返回 null（调用方兜底为单方案）
        return successCount > 0 ? set : null;
    }

    /** ★方案标题统一成「A / B」形式：模型用了 · ｜ | — ： 等分隔符就换成「/」；完全没分隔的按目的地+天数切开 */
    private String normalizeSchemeTitle(String title, TripRequest req) {
        String t = title == null ? "" : title.trim().replace('\n', ' ').replace("  ", " ");
        if (t.isEmpty()) {
            return (req.getDestination() == null ? "" : req.getDestination()) + req.getDays() + "日游 / 风格方案";
        }
        if (t.contains("/")) {
            return t;                                    // 已经有 / 了，直接用
        }
        for (String sep : new String[]{"·", "｜", "|", "—", " － ", "：", ":"}) {
            int idx = t.indexOf(sep);
            if (idx > 0 && idx + sep.length() < t.length()) {
                return t.substring(0, idx).trim() + " / " + t.substring(idx + sep.length()).trim();
            }
        }
        String dest = req.getDestination() == null ? "" : req.getDestination().trim();
        String head = dest + (req.getDays() > 0 ? req.getDays() + "日" : "");
        if (!dest.isEmpty() && t.startsWith(dest) && t.length() > head.length()) {
            return head + " / " + t.substring(dest.length()).trim();
        }
        int mid = Math.max(4, t.length() / 2);
        return t.substring(0, mid).trim() + " / " + t.substring(mid).trim();
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

    //==================== 工具方法 ====================

    /** 请求 JSON 输出的单次裸 LLM 调用 */
    private String callModelJson(String sysPrompt, String userPrompt) {
        //所有裸 LLM 调用统一走 LlmClient
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

    /** 结构化行程 -> markdown（降级字段 + 旧前端兼容） */
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
