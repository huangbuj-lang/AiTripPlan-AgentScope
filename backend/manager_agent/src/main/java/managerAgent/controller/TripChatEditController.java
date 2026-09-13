package managerAgent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;
import dto.ValidationReport.Severity;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import utils.AgentPrompts;
import utils.ExternalServices;
import utils.JsonUtils;
import utils.LlmClient;
import utils.ModelTimeouts;
import utils.PlanValidator;

/**
 * 对话式改行程（新增接口，不改动任何既有类/接口）。
 *
 * <p>入口：{@code POST /api/trip-chat-edit?planId=<行程id>&instruction=<用户原话>}
 *
 * <p>与旧的 {@code /api/trips/{id}/regenerate-day} 的区别（用户口径）：
 * <ul>
 *   <li><b>上下文喂足</b>：目标天全部条目 + 前一天最后 2 条 + 后一天前 2 条 + 全程背景 + 硬约束文本 + 用户原话；</li>
 *   <li><b>真实数据预取</b>：对目标天出现的地点逐个查高德（带 city=目的地城市），把评分/地址/电话作为
 *       "真实信息，优先使用"塞进 prompt；查不到的字段标"暂无"，<b>绝不编造</b>；</li>
 *   <li><b>交付前校验</b>：新的一天过 {@link PlanValidator}，目标天有 BLOCK 级违规就回喂重试一次，
 *       仍 BLOCK 则<b>不写库</b>、如实返回原因；</li>
 *   <li><b>可撤销</b>：返回改动前的整天 Section（prevSection）供前端一键还原；并给出 diff（去掉/新增/调整/车程变化）。</li>
 * </ul>
 *
 * <p><b>本轮加固（用户实测反馈的三件事）</b>：
 * <ol>
 *   <li><b>显式可量化要求必须是硬判定</b>：如"少排一个点"→ 当天【非交通条目数】必须真的减少，
 *       不满足就回喂重试，仍不满足则<b>不写库</b>并返回 {@code success:false} + "没做到你的要求：…"
 *       （绝不假装成功）。判定结果放在返回 JSON 的 {@code hardChecks} 里；</li>
 *   <li><b>跨天连续性硬判定</b>：新的一天与前一天/后一天比对——重复景点、同类型重复（草原/湖/古城…）、
 *       方向衔接倒挂（前一天已在 A 落脚、这天却从 B 开始）→ 回喂重试一次，仍冲突则<b>不写库</b>并返回
 *       "和相邻两天冲突：…"；判定结果放在 {@code continuity} 里；</li>
 *   <li><b>变更小结数据重做</b>：diff 的 removed/added 只放【地点】（带 time），全部按地点去重；
 *       changed 放地点 + timeBefore/timeAfter；pointsBefore/After 口径 = 【非交通条目数】（与①一致）。</li>
 * </ol>
 *
 * <p>落库沿用现有 {@code trip_plans} 表（plan_json + plan_content），不新增表/列。
 * 成功后<b>顺带返回整份更新后的行程</b>（{@code plan} + {@code markdown}，数据取自刚写库的
 * plan_json / plan_content），供前端把 app.js 的模块内状态（lastResultMarkdown/currentPlanData）写回，
 * 否则"保存到我的行程"会读到空文本报"暂无结果可保存"。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class TripChatEditController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();

    /** 高德查询间隔（QPS 3 → 350ms），与 TripExtrasController 同一口径 */
    private static final long AMAP_INTERVAL_MS = 350L;
    /** 单次请求最多预取的地点条数（防高德配额被打爆 / 接口被拖长） */
    private static final int MAX_FETCH_PLACES = 8;

    /** 硬约束文本（与 PlanValidator 的判定口径一致；单城不做 A1~A4/A8 判定，此处作为通用要求给出） */
    private static final String HARD_RULES = String.join("\n",
            "1. 每天纯游玩 + 车程合计不超过 8 小时；",
            "2. 每天纯游玩不低于 4 小时（当天车程长时，下限按 8 小时减车程收敛）；",
            "3. 单日赶路不超过 6 小时；赶路超过 4 小时的当天必须安排真景点（不能只赶路）；",
            "4. 节奏合理，不要排得过赶；当天时间线必须从早到晚递增；",
            "5. 不要出现同类型重复景点（同一天内不得重复，也不要与前一天/后一天重复同一景区）；",
            "6. 跨城顺序不能倒（保持原有城市/县城的先后顺序，不走回头路）；",
            "7. 县级途经点要保留（原行程里作为途经点的县/镇不要删掉）；",
            "8. 保持整体风格一致（写法与信息密度和原来这一天一致）；",
            "9. 23:00-05:00 之间不要安排赶路。");

    /** 天号写法：第3天 / 第 3 天 / Day 3 / D3 / 第三天 */
    private static final Pattern DAY_CN_PAT =
            Pattern.compile("第\\s*([0-9]+|[一二三四五六七八九十两]+)\\s*[天日]");
    private static final Pattern DAY_EN_PAT = Pattern.compile("(?i)\\bday\\s*([0-9]{1,2})");
    private static final Pattern DAY_D_PAT = Pattern.compile("(?i)(?:^|[^a-z])d\\s*([0-9]{1,2})(?![0-9])");
    /** 光写中文数字（如「三太赶了」）也认：只认开头的那个数字，且后面不能是量词（免得「少排一个点」被当成第 1 天） */
    private static final Pattern DAY_BARE_PAT =
            Pattern.compile("^\\s*([一二三四五六七八九十两])(?![个天日小时分次人公])");
    /** 整体性（改天数/改全行程）关键词——这类要求本接口不支持，如实回绝 */
    private static final String[] WHOLE_TRIP_WORDS = {
            "整体", "全程", "整条", "全部", "所有天", "每一天", "改成", "换一个目的地", "重新生成"};
    private static final String[] DAY_COUNT_WORDS = {"加一天", "多一天", "少一天", "减一天", "加两天", "减两天", "增加一天", "减少一天"};


    /** 标题里的天号（与 PlanValidator 的 DAY_PAT 同口径：第N天 / Day N / DN）；取不到返回 0 */
    private static final Pattern TITLE_DAY_PAT =
            Pattern.compile("(?:第\\s*(\\d+)\\s*天)|(?:Day\\s*(\\d+))|(?:\\bD(\\d+)\\b)", Pattern.CASE_INSENSITIVE);

    /**
     * 对话式改某一天。
     *
     * @param planId      行程 id（trip_plans.id），必填
     * @param instruction 用户原话，必填
     */
    @PostMapping("/trip-chat-edit")
    public Map<String, Object> chatEdit(@RequestParam(value = "planId", required = false) Long planId,
                                        @RequestParam(value = "instruction", required = false) String instruction,
                                        HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        //① 入参校验：一律返回 JSON（绝不 500 / HTML 错误页）
        if (planId == null) {
            result.put("message", "缺少行程ID（请在 URL 里带 planId）");
            return result;
        }
        if (instruction == null || instruction.isBlank()) {
            result.put("message", "请说明要改什么，例如：第三天太赶了");
            return result;
        }
        //② 登录校验（本路径不在 LoginInterceptor 的拦截清单里，故在此显式判空，避免 NPE 变 500）
        Object uidObj = session == null ? null : session.getAttribute("userId");
        if (uidObj == null) {
            result.put("message", "请先登录");
            return result;
        }
        long userId = ((Number) uidObj).longValue();

        try {
            //③ 取行程（仅本人）
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT city, days, plan_json, plan_content FROM trip_plans WHERE id = ? AND user_id = ?",
                    planId, userId);
            if (rows.isEmpty()) {
                result.put("message", "行程不存在或无权访问");
                return result;
            }
            Map<String, Object> row = rows.get(0);
            TripPlanData plan = row.get("plan_json") == null ? null
                    : JsonUtils.parse(String.valueOf(row.get("plan_json")), TripPlanData.class);
            if (plan == null) {
                //兜底：旧数据 plan_json 为空时，从 plan_content 尾部的 JSON 标记解析（与编辑页同口径）
                plan = parsePlanFromContent(row.get("plan_content"));
            }
            if (plan == null) {
                result.put("message", "这条行程没有结构化数据，无法对话式修改（请重新生成行程）");
                return result;
            }
            if (plan.getSections() == null || plan.getSections().isEmpty()) {
                result.put("message", "这条行程没有任何日程区块，无法对话式修改");
                return result;
            }

            //④ 识别目标天
            int targetDay = parseTargetDay(instruction);
            if (containsDayCountChange(instruction)) {
                result.put("message", "增减天数属于整体调整，请重新生成行程");
                return result;
            }
            if (targetDay <= 0) {
                result.put("message", looksWholeTrip(instruction)
                        ? "整体调整（改天数/改全行程）请重新生成行程"
                        : "请说明要改哪一天，例如：第三天太赶了");
                return result;
            }

            //⑤ 定位第 targetDay 天的 timeline 区块（与 TripEditController.regenerateDay 同一口径）
            int timelineIdx = 0, timelineCount = 0, targetSectionIdx = -1;
            Section target = null;
            for (int i = 0; i < plan.getSections().size(); i++) {
                Section sec = plan.getSections().get(i);
                if (!"timeline".equals(sec.getType())) continue;
                timelineCount++;
                if (timelineIdx == targetDay - 1) {
                    target = sec;
                    targetSectionIdx = i;
                    break;
                }
                timelineIdx++;
            }
            if (target == null) {
                result.put("message", "行程里没有第 " + targetDay + " 天（这份行程只有 " + timelineCount + " 天日程）");
                return result;
            }

            String dayTitle = target.getTitle() == null || target.getTitle().isBlank()
                    ? ("第" + targetDay + "天") : target.getTitle();
            //改动前的整天快照（供前端一键撤销）：深拷贝，避免后续被就地改掉
            Section prevSection = JsonUtils.parse(JsonUtils.toJson(target), Section.class);

            //⑥ 真实数据预取（高德，带 city=目的地城市；查不到写"暂无"，绝不编造）
            String city = plan.getDestination() == null || plan.getDestination().isBlank()
                    ? (row.get("city") == null ? "" : String.valueOf(row.get("city")))
                    : plan.getDestination();
            List<String> facts = new ArrayList<>();
            int factsUsed = 0;
            if (target.getItems() != null) {
                Set<String> places = new LinkedHashSet<>();
                for (SectionItem it : target.getItems()) {
                    if (it == null) continue;
                    String p = it.getPlace() == null ? "" : it.getPlace().trim();
                    if (!p.isEmpty()) places.add(p);
                    if (places.size() >= MAX_FETCH_PLACES) break;
                }
                for (String p : places) {
                    String fact = fetchFact(p, city);
                    if (fact != null) {
                        facts.add(fact);
                        if (!fact.contains("暂无")) factsUsed++;
                    }
                    sleepQuiet(AMAP_INTERVAL_MS); //高德 QPS 3
                }
            }

            //⑦ 组装上下文 → 调 LLM → 解析成 Section
            //   三重硬判定（任一不过 → 把冲突项写进提示词回喂一次；仍不过 → 不写库、如实报失败）：
            //   ① 用户的显式量化要求（少排一个点 / 车程不增加 …）
            //   ② 与前一天、后一天的连续性（重复景点 / 同类型重复 / 方向衔接倒挂）
            //   ③ PlanValidator 目标天的 BLOCK 级违规
            HardIntent hard = detectHardIntent(instruction);
            String intentHint = intentHintOf(hard, dayDriveMinutes(prevSection));
            String retryHint = intentHint;
            Set<String> townNames = collectTownNames(plan, city);

            Section newSection = null;
            ValidationReport report = new ValidationReport();
            List<Map<String, Object>> hardChecks = new ArrayList<>();
            List<Map<String, Object>> continuity = new ArrayList<>();
            int targetDayNum = targetDay;
            String failText = "";
            for (int attempt = 0; attempt < 2; attempt++) {
                Section candidate = parseSection(callModelJson(AgentPrompts.tripEditor(),
                        buildPrompt(plan, targetDay, dayTitle, target, prevSection, instruction, facts, retryHint)));
                if (candidate == null || candidate.getItems() == null || candidate.getItems().isEmpty()) {
                    failText = "重排失败（模型没有返回可用的日程），请稍后重试";
                    break;
                }
                candidate.setType("timeline");
                if (candidate.getTitle() == null || candidate.getTitle().isBlank()) {
                    candidate.setTitle(dayTitle);
                }
                candidate.setStops(stopsOf(candidate));
                targetDayNum = titleDayOf(candidate.getTitle()) > 0 ? titleDayOf(candidate.getTitle()) : targetDay;

                ValidationReport r = validateWithDay(plan, targetSectionIdx, candidate);
                String blockText = blockingText(r, targetDayNum);
                hardChecks = evalHardChecks(hard, prevSection, candidate);
                continuity = evalContinuity(plan, targetDay, candidate, townNames);

                List<String> hardMiss = missTexts(hardChecks, "note");
                List<String> contMiss = missTexts(continuity, "detail");
                boolean clean = hardMiss.isEmpty() && contMiss.isEmpty() && blockText.isEmpty();
                if (clean) {
                    newSection = candidate;
                    report = r;
                    break;
                }
                if (attempt == 0) {
                    //回喂一次：把「没做到的显式要求 / 跨天冲突项 / BLOCK 违规」逐条写进提示词
                    retryHint = intentHint + buildRetryHint(hardMiss, contMiss, blockText);
                    log.info("对话式改行程回喂重试: planId={} day={} 硬要求未过={} 跨天冲突={} block={}",
                            planId, targetDay, hardMiss.size(), contMiss.size(), blockText.isEmpty() ? 0 : 1);
                    continue;
                }
                StringBuilder f = new StringBuilder();
                if (!hardMiss.isEmpty()) {
                    f.append("没做到你的要求：").append(String.join("；", hardMiss));
                }
                if (!contMiss.isEmpty()) {
                    if (f.length() > 0) f.append("；");
                    f.append("和相邻两天冲突：").append(String.join("；", contMiss));
                }
                if (!blockText.isEmpty()) {
                    if (f.length() > 0) f.append("；");
                    f.append("这次重排会违反约束：").append(blockText);
                }
                failText = f.length() > 0 ? f.toString() : "重排结果不满足硬性要求，已放弃改动";
                break;
            }

            if (newSection == null) {
                //★不写库：把判定明细一并返回，前端用【警告样式】显示「没做到/冲突」
                result.put("message", failText.isBlank() ? "重排失败，请稍后重试" : failText);
                result.put("dayIndex", targetDay - 1);
                result.put("hardChecks", hardChecks);
                result.put("continuity", continuity);
                return result;
            }

            //★用户口径（铁律）：重排【只改画面、不落库】——
            //  数据库只在用户点【保存到我的行程】时才写；这里仅把结果返回给前端（内存态）。
            //  （原来这里直接 UPDATE trip_plans，等于"没保存就把原行程覆盖了"✗ 已按用户要求去掉）
            plan.getSections().set(targetSectionIdx, newSection);
            String savedJson = JsonUtils.toJson(plan);   //仅用于返回给前端预览，不写库
            String savedMd = renderMarkdown(plan);       //同上

            //⑨ 返回：新的一天 + 改动前那天（撤销用）+ diff + 硬判定明细 + 整份行程（供保存行程写回前端状态）
            List<String> warnings = new ArrayList<>();
            for (ValidationReport.Violation v : report.getViolations()) {
                if (v.severity == Severity.WARN && (v.day == targetDayNum || v.day == 0)) warnings.add(v.detail);
            }
            result.put("success", true);
            result.put("message", dayTitle + " 已按你的要求重排");
            result.put("dayIndex", targetDay - 1);
            result.put("dayTitle", newSection.getTitle());
            result.put("section", newSection);
            result.put("prevSection", prevSection);
            result.put("diff", diffOf(prevSection, newSection));
            result.put("hardChecks", hardChecks);
            result.put("continuity", continuity);
            result.put("factsUsed", factsUsed);
            result.put("warnings", warnings);
            //★后端权威数据（本次重排结果，未写库）：前端要把它写回 app.js 的模块内状态，
            //  否则「保存到我的行程」读到空的 lastResultMarkdown 会报"暂无结果可保存"
            result.put("plan", plan);
            result.put("markdown", savedMd);
            return result;
        } catch (Throwable t) {
            //接口任何异常都降级成 JSON 失败，绝不抛 500
            log.error("对话式改行程失败: planId={}, err={}", planId, t.toString());
            result.put("message", "改行程失败：" + t.getMessage());
            return result;
        }
    }

    // ==================== 上下文组装 ====================

    /** 组装喂给 LLM 的完整上下文（信息喂足：目标天全文 + 前后天衔接 + 全程背景 + 硬约束 + 真实信息 + 原话） */
    private String buildPrompt(TripPlanData plan, int targetDay, String dayTitle, Section target,
                               Section prevSection, String instruction, List<String> facts, String retryHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("【全程背景】标题：").append(safe(plan.getTitle()))
                .append("；目的地：").append(safe(plan.getDestination()))
                .append("；总天数：").append(plan.getDays()).append(" 天。\n");
        sb.append("【本次只重排这一天】第 ").append(targetDay).append(" 天（原标题：").append(dayTitle).append("）\n");
        sb.append("那一天现有的全部安排（时间 | 地点 | 活动 | 费用 | 备注）：\n");
        if (target.getItems() == null || target.getItems().isEmpty()) {
            sb.append("- （这一天目前没有任何条目）\n");
        } else {
            for (SectionItem it : target.getItems()) {
                sb.append("- ").append(safe(it.getTime())).append(" | ").append(safe(it.getPlace()))
                        .append(" | ").append(safe(it.getActivity())).append(" | ").append(safe(it.getCost()))
                        .append(" | ").append(safe(it.getNote())).append("\n");
            }
        }
        if (facts != null && !facts.isEmpty()) {
            sb.append("【真实信息（高德实查结果，优先使用；标“暂无”的表示查不到，绝不能编造）】\n");
            for (String f : facts) sb.append("- ").append(f).append("\n");
        } else {
            sb.append("【真实信息】本次没有取到高德真实信息（地点不存在于高德或抓取失败），"
                    + "评分/地址/电话一律写“暂无”，不要编造。\n");
        }
        //前一天最后 2 条 / 后一天前 2 条（衔接用，避免重复景点与回头路）
        Section prevDay = neighborDay(plan, targetDay - 1, false);
        Section nextDay = neighborDay(plan, targetDay + 1, true);
        if (prevDay != null) {
            sb.append("【前一天（第 ").append(targetDay - 1).append(" 天）的最后 2 条安排（不要重复这些景点、不要走回头路）】\n");
            List<SectionItem> its = safeItems(prevDay);
            for (int i = Math.max(0, its.size() - 2); i < its.size(); i++) {
                sb.append("- ").append(safe(its.get(i).getTime())).append(" | ")
                        .append(safe(its.get(i).getPlace())).append(" | ").append(safe(its.get(i).getActivity())).append("\n");
            }
            String anchor = anchorOf(prevDay);
            if (anchor != null) {
                sb.append("（前一天落脚点：").append(anchor)
                        .append("；这一天的【起点必须从它接上】，不要退回已经走过的城市/县城）\n");
            }
        }
        if (nextDay != null) {
            sb.append("【后一天（第 ").append(targetDay + 1).append(" 天）的前 2 条安排（不要重复这些景点、不要走回头路）】\n");
            List<SectionItem> its = safeItems(nextDay);
            for (int i = 0; i < Math.min(2, its.size()); i++) {
                sb.append("- ").append(safe(its.get(i).getTime())).append(" | ")
                        .append(safe(its.get(i).getPlace())).append(" | ").append(safe(its.get(i).getActivity())).append("\n");
            }
            String origin = originOf(nextDay);
            if (origin != null) {
                sb.append("（后一天出发点：").append(origin)
                        .append("；这一天的【终点必须能接上它】，不要把它甩到走回头路的方向）\n");
            }
        }
        sb.append("【硬约束（必须全部满足）】\n").append(HARD_RULES).append("\n");
        if (retryHint != null && !retryHint.isBlank()) {
            sb.append(retryHint).append("\n");
        }
        sb.append("【用户要求（原话）】").append(instruction).append("\n");
        sb.append("请只输出这一天的 JSON（不要输出任何解释文字）：");
        sb.append("{\"title\":\"").append(targetDay).append(" 天标题（保持“第").append(targetDay)
                .append("天 …”的写法）\",\"items\":[{\"time\":\"09:00\",\"place\":\"地点\",\"activity\":\"活动\",\"cost\":\"费用\",\"note\":\"备注\"}]}");
        return sb.toString();
    }

    /** 取相邻某天的 timeline 区块（targetDay 为 1 起的第几天）；不存在返回 null */
    private static Section neighborDay(TripPlanData plan, int targetDay, boolean forward) {
        if (targetDay < 1) return null;
        int idx = 0;
        for (Section sec : safeSections(plan)) {
            if (!"timeline".equals(sec.getType())) continue;
            idx++;
            if (idx == targetDay) return sec;
        }
        return null;
    }

    // ==================== 高德真实数据预取 ====================

    /**
     * 查一个地点的真实信息（评分/地址/电话）。
     * 失败返回 null（调用方不显示这一条）；查到的字段为空则写「暂无」。
     */
    private String fetchFact(String name, String city) {
        try {
            String key = ExternalServices.amapKey();
            if (key == null || key.isBlank()) return name + "：评分 暂无、地址 暂无、电话 暂无（高德Key未配置）";
            //★不要把城市拼进关键词：city 已经用参数传了，前缀反而让高德搜不到（与 TripExtrasController 同一口径）
            String qs = "keywords=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&output=json&key=" + key + "&offset=5&extensions=all";
            if (city != null && !city.isBlank()) qs += "&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8);
            String body = httpGet(ExternalServices.amapPlaceSearchUrl(qs));
            JsonNode pois = om.readTree(body).path("pois");
            if (!pois.isArray() || pois.isEmpty()) return name + "：评分 暂无、地址 暂无、电话 暂无";
            JsonNode poi = pois.get(0);
            JsonNode biz = poi.path("biz_ext");
            String rating = biz.isArray() ? "" : biz.path("rating").asText("");
            String address = poi.path("address").asText("");
            String tel = poi.path("tel").asText("");
            if ("[]".equals(rating.trim())) rating = "";
            return name + "：评分 " + orNone(rating) + "、地址 " + orNone(address) + "、电话 " + orNone(tel)
                    + "（高德命中名：" + orNone(poi.path("name").asText("")) + "）";
        } catch (Throwable t) {
            log.warn("高德查地点失败 place={} err={}", name, t.toString());
            return name + "：评分 暂无、地址 暂无、电话 暂无";
        }
    }

    private static String orNone(String s) {
        return (s == null || s.isBlank()) ? "暂无" : s.trim();
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        return new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== ① 显式量化要求：识别 + 硬判定 ====================

    /** 一个「数量类」表达：p = 正则，g = 数字所在的分组号（0 = 无分组，按 1 计） */
    private static final class NumPat {
        final Pattern p;
        final int g;

        NumPat(String re, int g) {
            this.p = Pattern.compile(re);
            this.g = g;
        }
    }

    /** 「少排点」类表达（识别【非交通条目】要减少） */
    private static final NumPat[] DEC_PATS = {
            new NumPat("少\\s*排\\s*([0-9]+|[一二两三四五六七八九十]+)?\\s*个?\\s*(点|景点|地方|安排|项目|条|站)?", 1),
            new NumPat("少\\s*(?:玩|去|安排)?\\s*([0-9]+|[一二两三四五六七八九十]+)?\\s*个\\s*(?:点|景点|地方|安排|项目|条|站)", 1),
            new NumPat("(精简|削减|压缩)", 0),
            new NumPat("(?:去掉|去除|删掉|删除|砍掉|减掉|省掉|减去)\\s*([0-9]+|[一二两三四五六七八九十]+)\\s*个", 1),
            new NumPat("(?:去掉|去除|删掉|删除|砍掉|减掉|省掉|减去)\\s*([0-9]+|[一二两三四五六七八九十]+)?\\s*个\\s*(?:点|景点|地方|安排|项目|条|站)", 1)
    };

    /** 「加点」类表达（识别【非交通条目】要增加） */
    private static final NumPat[] INC_PATS = {
            new NumPat("(?:加|增加|多加|再加|多排|添加|增添)\\s*([0-9]+|[一二两三四五六七八九十]+)?\\s*个?\\s*(?:点|景点|地方|安排|项目|一站|行程点)", 1),
            new NumPat("(?:多玩|多去|多逛|多安排|多来)\\s*([0-9]+|[一二两三四五六七八九十]+)?\\s*个?", 1),
            new NumPat("(?:加|多)\\s*([0-9]+|[一二两三四五六七八九十]+)\\s*个", 1)
    };

    /**
     * 「太赶/轻松点/不那么累/车程少点」类表达（要求当天车程不增加）。
     * 只收明确、不歧义的词，宁可少认也不要乱认。
     */
    private static final Pattern DRIVE_RE = Pattern.compile(
            "太赶|很赶|有点赶|赶得|赶人|太累|很累|特别累|别太累|不那么累|轻松|悠闲|舒缓|慢一点|节奏慢"
                    + "|少开车|少赶路|车程(?:少|短|减|别那么|不要太)|减少车程|路程(?:少|短)|不赶");

    /** 用户显式量化要求的载体（识别不到就不判定） */
    private static final class HardIntent {
        /** <0：要求非交通条目减少 |n| 个；>0：要求增加 n 个；0：不涉及数量 */
        int deltaCount = 0;
        /** 是否要求「当天车程不增加」 */
        boolean driveNoIncrease = false;

        boolean any() {
            return deltaCount != 0 || driveNoIncrease;
        }
    }

    /**
     * 从用户原话识别【可量化、可判定、不歧义】的硬要求。
     * 同时命中「加」和「少」这类互相矛盾的说法 → 视为歧义，不判定数量。
     */
    private static HardIntent detectHardIntent(String instruction) {
        HardIntent h = new HardIntent();
        if (instruction == null || instruction.isBlank()) return h;
        String s = instruction.trim();
        int inc = hitNumber(INC_PATS, s);
        int dec = hitNumber(DEC_PATS, s);
        if (inc > 0 && dec <= 0) h.deltaCount = inc;
        else if (dec > 0 && inc <= 0) h.deltaCount = -dec;
        //两个都命中 → 歧义，数量不判定
        if (DRIVE_RE.matcher(s).find()) h.driveNoIncrease = true;
        return h;
    }

    /** 命中任一表达 → 返回张数（默认 1）；都没命中返回 0 */
    private static int hitNumber(NumPat[] pats, String s) {
        for (NumPat np : pats) {
            Matcher m = np.p.matcher(s);
            if (!m.find()) continue;
            String g = (np.g > 0 && m.groupCount() >= np.g) ? m.group(np.g) : null;
            int v = cn2Int(g);
            return v <= 0 ? 1 : v;
        }
        return 0;
    }

    /** 把识别到的量化要求写成提示词（回喂时也带上，明确写清楚要减/加几个） */
    private static String intentHintOf(HardIntent h, int driveBeforeMinutes) {
        if (h == null || !h.any()) return "";
        StringBuilder sb = new StringBuilder("【本次的量化要求（硬性，必须做到，做不到会被判定为失败）】\n");
        if (h.deltaCount < 0) {
            sb.append("- 必须把当天【非交通条目】（景点/活动这类真正的安排）数量至少减少 ")
                    .append(-h.deltaCount).append(" 个：删掉一个可去可不去的点，不要靠删交通条目凑数。\n");
        } else if (h.deltaCount > 0) {
            sb.append("- 必须把当天【非交通条目】数量至少增加 ").append(h.deltaCount).append(" 个。\n");
        }
        if (h.driveNoIncrease) {
            sb.append("- 当天车程【不得比原来更长】（原车程约 ").append(hmDrive(driveBeforeMinutes))
                    .append("），要更轻松：能减就减，不能减也不能加。\n");
        }
        return sb.toString();
    }

    /** 对重排结果硬检查用户显式量化要求；返回 [{rule, ok, before, after, note}] */
    private static List<Map<String, Object>> evalHardChecks(HardIntent h, Section before, Section after) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (h == null || !h.any()) return out;
        if (h.deltaCount != 0) {
            int b = countPoints(before);
            int a = countPoints(after);
            int need = Math.abs(h.deltaCount);
            boolean ok = h.deltaCount < 0 ? (a <= b - need) : (a >= b + need);
            String rule = h.deltaCount < 0 ? "显式数量要求：少排点" : "显式数量要求：加点";
            String note = h.deltaCount < 0
                    ? "要求少排 " + need + " 个点（非交通条目），" + (ok ? "已做到：" : "没做到：") + b + " → " + a
                    : "要求多排 " + need + " 个点（非交通条目），" + (ok ? "已做到：" : "没做到：") + b + " → " + a;
            out.add(check(rule, ok, b, a, note));
        }
        if (h.driveNoIncrease) {
            int db = dayDriveMinutes(before);
            int da = dayDriveMinutes(after);
            boolean ok = da <= db;
            out.add(check("显式车程要求：不增加", ok, hmDrive(db), hmDrive(da),
                    "要求当天车程不增加，" + (ok ? "已做到：" : "没做到：") + hmDrive(db) + " → " + hmDrive(da)));
        }
        return out;
    }

    // ==================== ② 跨天连续性：硬判定 ====================

    /** 同类型的关键词表（按长度从长到短判断「以它结尾」） */
    private static final String[] SAME_TYPE_WORDS = {
            "草原", "古城", "古镇", "峡谷", "温泉", "沙漠", "湿地", "冰川", "森林", "雪山", "天池",
            "丹霞", "故城", "遗址", "溶洞", "石林", "峰林", "水库", "沙漠", "湖", "寺", "庙", "山"};

    /** 地名里出现这些词就不是「能当落脚点/景点」的干净地名（酒店/中转/餐饮…） */
    private static final String[] ANCHOR_STOP_WORDS = {
            "酒店", "民宿", "客栈", "住处", "住宿", "入住", "休息", "自由活动", "出发", "启程", "前往",
            "抵达", "到达", "返程", "返抵", "返回", "晚上", "早上", "上午", "下午", "傍晚", "夜里", "夜间",
            "晚餐", "午餐", "早餐", "夜市", "购物", "结束", "市区", "城区", "附近", "路上", "途中",
            "服务区", "机场", "车站", "火车站", "待定", "可去可不去"};

    /** 地点字段里出现这些词 → 不是景点（中转/餐饮/住宿/路段） */
    private static final String[] NON_SIGHT_PLACE_WORDS = {
            "餐", "美食", "夜市", "购物", "住宿", "酒店", "民宿", "客栈", "出发", "前往", "返回", "返程",
            "途经", "路过", "路上", "高速", "国道", "服务区", "车站", "机场", "→", "—", "-", "合计"};

    /** 活动字段里出现这些词 → 不是景点（在吃饭/休息/入住/赶路） */
    private static final String[] NON_SIGHT_ACT_WORDS = {
            "早餐", "午餐", "晚餐", "用餐", "就餐", "美食", "自由活动", "休息", "入住", "退房", "购物",
            "赶路", "返程", "抵达酒店"};

    /** 出发点：「从X出发 / X出发 / X启程」 */
    private static final Pattern ORIGIN_FROM_PAT =
            Pattern.compile("从\\s*([^，,。；;、\\s]{2,10}?)\\s*(?:出发|启程|前往|驶向|返回|返程|动身)");
    private static final Pattern ORIGIN_BARE_PAT =
            Pattern.compile("([^，,。；;、\\s]{2,10}?)\\s*(?:出发|启程|动身)");
    /** 首条本身就写着「集合/退房/早餐」等，且 place 是地名 → 把它当出发点 */
    private static final Pattern FIRST_ITEM_HINT_PAT = Pattern.compile("集合|退房|出发|启程|早餐|前往|动身|从");

    /**
     * 新的一天与【前一天/后一天】的连续性硬判定。返回 [{rule, ok, detail}]（ok=false 即冲突）。
     *
     * <p>三条：①同名景点重复 ②同类型景点重复（草原/湖/古城…）③方向衔接倒挂
     * （前一天已在 A 落脚、这天却从 B 开始；或这天的落脚点接不上后一天的出发点）。
     * 判不出来的一律标 skipped（宁可少报，也不乱报）。
     */
    private List<Map<String, Object>> evalContinuity(TripPlanData plan, int targetDay, Section newDay,
                                                    Set<String> townNames) {
        List<Map<String, Object>> out = new ArrayList<>();
        Section prev = neighborDay(plan, targetDay - 1, false);
        Section next = neighborDay(plan, targetDay + 1, true);

        List<String> mine = sightPlacesOf(newDay, townNames);
        Set<String> reported = new LinkedHashSet<>();
        int[] nbDays = {targetDay - 1, targetDay + 1};
        Section[] nbSecs = {prev, next};
        for (int k = 0; k < 2; k++) {
            int nd = nbDays[k];
            Section nb = nbSecs[k];
            if (nb == null) continue;
            List<String> theirs = sightPlacesOf(nb, townNames);
            for (String op : theirs) {
                String on = normalizeName(op);
                if (on.length() < 2) continue;
                for (String mp : mine) {
                    String mn = normalizeName(mp);
                    if (mn.length() < 2) continue;
                    if (!samePlace(mn, on)) continue;
                    if (reported.add("dup|" + mn + "|" + nd)) {
                        out.add(cont("跨天重复景点", false, "这一天有「" + mp + "」，与第" + nd + "天的「" + op
                                + "」是同一处（相邻两天不能重复同一景点）"));
                    }
                }
            }
        }
        //同类型重复
        for (int k = 0; k < 2; k++) {
            int nd = nbDays[k];
            Section nb = nbSecs[k];
            if (nb == null) continue;
            for (String op : sightPlacesOf(nb, townNames)) {
                String ot = typeOf(op);
                if (ot == null) continue;
                for (String mp : mine) {
                    String mt = typeOf(mp);
                    if (mt == null || !mt.equals(ot)) continue;
                    if (samePlace(normalizeName(mp), normalizeName(op))) continue; //同一处已由上一段报过
                    if (reported.add("type|" + mt + "|" + normalizeName(mp) + "|" + nd)) {
                        out.add(cont("跨天同类型重复", false, "这一天有「" + mp + "」（" + mt + "），与第" + nd
                                + "天的「" + op + "」同类型（" + ot + "）重复，换一处别的类型"));
                    }
                }
            }
        }
        //方向/衔接倒挂：起点 vs 前一天落脚点
        String prevAnchor = anchorOf(prev);
        String myOrigin = originOf(newDay);
        if (prev == null) {
            out.add(cont("衔接：起点与前一日落脚点", true, "这一天是行程第一天，没有前一天（未判定）"));
        } else if (prevAnchor == null || myOrigin == null) {
            out.add(contSkip("衔接：起点与前一日落脚点", "前一天没写出落脚点、或这一天没写明出发地，未作判定（宁可少报）"));
        } else if (samePlace(normalizeName(prevAnchor), normalizeName(myOrigin))) {
            out.add(cont("衔接：起点与前一日落脚点", true, "起点「" + myOrigin + "」与前一天落脚点「" + prevAnchor + "」衔接一致"));
        } else {
            out.add(cont("衔接：起点与前一日落脚点", false, "前一天（第" + (targetDay - 1) + "天）已经在「" + prevAnchor
                    + "」落脚，这一天却从「" + myOrigin + "」开始（方向倒挂/接不上）"));
        }
        //终点 vs 后一天出发点
        String myAnchor = anchorOf(newDay);
        String nextOrigin = originOf(next);
        if (next == null) {
            out.add(cont("衔接：终点与后一日出发点", true, "这一天是行程最后一天，没有后一天（未判定）"));
        } else if (myAnchor == null || nextOrigin == null) {
            out.add(contSkip("衔接：终点与后一日出发点", "这一天没写出落脚点、或后一天没写明出发地，未作判定（宁可少报）"));
        } else if (samePlace(normalizeName(myAnchor), normalizeName(nextOrigin))) {
            out.add(cont("衔接：终点与后一日出发点", true, "落脚点「" + myAnchor + "」与后一天出发点「" + nextOrigin + "」衔接一致"));
        } else {
            out.add(cont("衔接：终点与后一日出发点", false, "这一天的落脚点是「" + myAnchor + "」，但第" + (targetDay + 1)
                    + "天却从「" + nextOrigin + "」出发（方向倒挂/接不上）"));
        }
        return out;
    }

    /** 一天里的【景点类】地点（排除城市/中转/住宿/餐饮/路段） */
    private static List<String> sightPlacesOf(Section sec, Set<String> townNames) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SectionItem it : safeItems(sec)) {
            if (it == null) continue;
            String p = safe(it.getPlace()).trim();
            if (p.isBlank()) continue;
            if (isTraffic(it) || isStay(it)) continue;
            boolean bad = false;
            for (String w : NON_SIGHT_PLACE_WORDS) {
                if (p.contains(w)) { bad = true; break; }
            }
            if (bad) continue;
            String act = safe(it.getActivity());
            for (String w : NON_SIGHT_ACT_WORDS) {
                if (act.contains(w)) { bad = true; break; }
            }
            if (bad) continue;
            if (PlanValidator.isAdminName(p)) continue;        //城市/县/镇不算景点
            if (PlanValidator.isPseudoPlace(p)) continue;      //路段/地理描述不算景点
            if (townNames != null && matchesAny(p, townNames)) continue; //行程里的城市/县城名不算景点
            if (!seen.add(normalizeName(p))) continue;
            out.add(p);
        }
        return out;
    }

    /** 同类型关键词：按「以该词结尾」判定（短词如"山"只认结尾，避免"天山天池"这类误判） */
    private static String typeOf(String place) {
        String n = normalizeName(place);
        if (n.length() < 2) return null;
        for (String w : SAME_TYPE_WORDS) {
            if (n.endsWith(w) && n.length() > w.length()) return w;
        }
        return null;
    }

    /** 一天的「落脚点」：从最后一条往前找第一个能当落脚点的地名 */
    private static String anchorOf(Section sec) {
        List<SectionItem> its = safeItems(sec);
        for (int i = its.size() - 1; i >= 0; i--) {
            SectionItem it = its.get(i);
            String c = stripAnchorWords(safe(it.getPlace()));
            if (isPlaceToken(c)) return c;
            c = stripAnchorWords(safe(it.getName()));
            if (isPlaceToken(c)) return c;
            c = stripAnchorWords(safe(it.getTo()));
            if (isPlaceToken(c)) return c;
        }
        return null;
    }

    /** 一天的「出发点」：从第一条开始找「从X出发 / X出发 / 首条地名」 */
    private static String originOf(Section sec) {
        List<SectionItem> its = safeItems(sec);
        for (int i = 0; i < Math.min(its.size(), 2); i++) {
            SectionItem it = its.get(i);
            String f = stripAnchorWords(safe(it.getFrom()));
            if (isPlaceToken(f)) return f;
            String blob = safe(it.getPlace()) + " " + safe(it.getActivity());
            Matcher m = ORIGIN_FROM_PAT.matcher(blob);
            if (m.find()) {
                String t = stripAnchorWords(m.group(1));
                if (isPlaceToken(t)) return t;
            }
            m = ORIGIN_BARE_PAT.matcher(blob);
            if (m.find()) {
                String t = stripAnchorWords(m.group(1));
                if (isPlaceToken(t)) return t;
            }
            if (i == 0) {
                String p = stripAnchorWords(safe(it.getPlace()));
                boolean hint = FIRST_ITEM_HINT_PAT.matcher(safe(it.getActivity())).find();
                if (isPlaceToken(p) && (isAdminLike(p) || hint)) return p;
            }
        }
        return null;
    }

    /** 去掉「酒店/出发/抵达…」这类非地名成分，留下干净的地名 */
    private static String stripAnchorWords(String s) {
        String v = safe(s).trim();
        if (v.isEmpty()) return "";
        for (String w : ANCHOR_STOP_WORDS) v = v.replace(w, "");
        v = v.replaceAll("^[（(【\\[]|[）)】\\]]$", "").trim();
        return v.trim();
    }

    /** 能不能当地名用（太短/含非地名成分/路段描述 → 不算） */
    private static boolean isPlaceToken(String t) {
        if (t == null) return false;
        String v = t.trim();
        if (v.length() < 2 || v.length() > 10) return false;
        for (String w : ANCHOR_STOP_WORDS) {
            if (v.contains(w)) return false;
        }
        if (PlanValidator.isPseudoPlace(v)) return false;
        return true;
    }

    /** 是否是行政区地名写法（市/县/区/镇/州/乡/村/旗/盟） */
    private static boolean isAdminLike(String s) {
        String v = safe(s).trim();
        if (v.isEmpty() || v.length() > 6) return false;
        for (String suf : new String[]{"市", "县", "区", "镇", "州", "乡", "村", "旗", "盟"}) {
            if (v.endsWith(suf)) return true;
        }
        return false;
    }

    /** 地名比较（宽松：相等或互相包含，且都至少有 2 个字） */
    private static boolean samePlace(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() < 2 || b.length() < 2) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /** 地名的规范化写法（去空格/括号/标点） */
    private static String normalizeName(String s) {
        return safe(s).replaceAll("[\\s（）()【】\\[\\]、,，。.·\\-—→>／/\\\\\"'“”]", "");
    }

    /** name 是否命中集合里的任一地名（宽松包含匹配） */
    private static boolean matchesAny(String name, Set<String> set) {
        String n = normalizeName(name);
        if (n.length() < 2) return false;
        for (String s : set) {
            String k = normalizeName(s);
            if (k.length() < 2) continue;
            if (k.equals(n) || k.contains(n) || n.contains(k)) return true;
        }
        return false;
    }

    /**
     * 行程里的「城市/县城」名集合（用于把城市从中转地名里排除）：
     * 目的地 + 行程城市 + 交通条目的 from/to + 非 timeline 区块（住宿/交通/贴士）的名与地点。
     */
    private static Set<String> collectTownNames(TripPlanData plan, String city) {
        Set<String> out = new LinkedHashSet<>();
        if (city != null && !city.isBlank()) out.add(city.trim());
        if (plan != null && plan.getDestination() != null && !plan.getDestination().isBlank()) {
            out.add(plan.getDestination().trim());
        }
        for (Section sec : safeSections(plan)) {
            boolean tl = "timeline".equals(sec.getType());
            for (SectionItem it : safeItems(sec)) {
                if (it == null) continue;
                addShort(out, it.getFrom());
                addShort(out, it.getTo());
                if (!tl) {
                    addShort(out, it.getName());
                    addShort(out, it.getPlace());
                }
            }
        }
        return out;
    }

    private static void addShort(Set<String> out, String s) {
        String v = safe(s).trim();
        if (v.isEmpty() || v.length() > 12) return;
        out.add(v);
    }

    /** 一条硬条件判定结果 */
    private static Map<String, Object> check(String rule, boolean ok, Object before, Object after, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("ok", ok);
        if (before != null) m.put("before", before);
        if (after != null) m.put("after", after);
        m.put("note", note);
        return m;
    }

    /** 一条连续性判定结果 */
    private static Map<String, Object> cont(String rule, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("ok", ok);
        m.put("skipped", false);
        m.put("detail", detail);
        return m;
    }

    /** 判不出来（不判定）的连续性结果：ok=true 但标 skipped，前端不当失败 */
    private static Map<String, Object> contSkip(String rule, String detail) {
        Map<String, Object> m = cont(rule, true, detail);
        m.put("skipped", true);
        return m;
    }

    /** 取出 ok=false 的那些条目的说明文本（field = note 或 detail） */
    private static List<String> missTexts(List<Map<String, Object>> checks, String field) {
        List<String> out = new ArrayList<>();
        if (checks == null) return out;
        for (Map<String, Object> c : checks) {
            if (c == null) continue;
            if (!Boolean.FALSE.equals(c.get("ok"))) continue;
            Object v = c.get(field);
            if (v != null && !String.valueOf(v).isBlank()) out.add(String.valueOf(v));
        }
        return out;
    }

    /** 回喂提示词：把没做到的硬要求 / 跨天冲突 / BLOCK 违规逐条写清楚 */
    private static String buildRetryHint(List<String> hardMiss, List<String> contMiss, String blockText) {
        StringBuilder sb = new StringBuilder();
        sb.append("【上一次重排【没有】满足下面的硬性要求，必须全部修正（这是硬性要求，不要解释、不要偷懒）】\n");
        for (String s : hardMiss) sb.append("- 没做到：").append(s).append("\n");
        for (String s : contMiss) sb.append("- 和相邻两天冲突：").append(s).append("\n");
        if (blockText != null && !blockText.isBlank()) sb.append("- 违反约束：").append(blockText).append("\n");
        return sb.toString();
    }

    // ==================== 校验与 diff ====================

    /** 用「把新一天放进去」的行程跑 PlanValidator（req 取最小可用口径：目的地 + 天数） */
    private ValidationReport validateWithDay(TripPlanData plan, int sectionIdx, Section newSection) {
        TripPlanData copy = JsonUtils.parse(JsonUtils.toJson(plan), TripPlanData.class);
        if (copy == null || copy.getSections() == null) {
            ValidationReport r = new ValidationReport();
            r.add(0, "A0", "校验器执行", Severity.UNKNOWN, "行程副本解析失败，无法判定");
            return r;
        }
        copy.getSections().set(sectionIdx, newSection);
        copy.setDays(plan.getDays() < 1 ? 1 : plan.getDays());
        TripRequest req = new TripRequest();
        req.setDestination(plan.getDestination());
        req.setDays(copy.getDays());
        //allowedPlaces 传 null：编排层的白名单来自子Agent 回传清单（此处拿不到），
        //PlanValidator 对 null 白名单直接跳过 A6，不会误报编造
        return PlanValidator.validate(copy, req, null, null);
    }

    /** 目标天自己的 BLOCK 级违规描述（多条用「；」连接）；没有则返回空串 */
    private static String blockingText(ValidationReport report, int targetDayNum) {
        StringBuilder sb = new StringBuilder();
        for (ValidationReport.Violation v : report.getViolations()) {
            if (v.severity != Severity.BLOCK) continue;
            if (v.day != targetDayNum) continue;   //只认这一天自己的违规
            if (sb.length() > 0) sb.append("；");
            sb.append("[").append(v.rule).append("] ").append(v.detail);
        }
        return sb.toString();
    }

    /**
     * 前后两天的变更小结数据。
     *
     * <p>口径（用户反馈：时间与地点混在一起、地点重复出现）：
     * <ul>
     *   <li>removed / added：<b>只放地点</b>（带 time），按【规范化地名】去重，同一地点只出现一次；</li>
     *   <li>changed：同一地点但时间变了 → {place, timeBefore, timeAfter}；</li>
     *   <li>pointsBefore/After：【非交通条目数】（与①的硬判定同一口径）；</li>
     *   <li>driveBefore/After：沿用 PlanValidator 的车程口径（hmDrive 格式）。</li>
     * </ul>
     */
    private static Map<String, Object> diffOf(Section before, Section after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        Map<String, SectionItem> bm = placeMap(before);
        Map<String, SectionItem> am = placeMap(after);

        List<Map<String, Object>> removed = new ArrayList<>();
        for (Map.Entry<String, SectionItem> e : bm.entrySet()) {
            if (am.containsKey(e.getKey())) continue;
            removed.add(placeEntry(e.getValue()));
        }
        List<Map<String, Object>> added = new ArrayList<>();
        for (Map.Entry<String, SectionItem> e : am.entrySet()) {
            if (bm.containsKey(e.getKey())) continue;
            added.add(placeEntry(e.getValue()));
        }
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map.Entry<String, SectionItem> e : am.entrySet()) {
            SectionItem old = bm.get(e.getKey());
            if (old == null) continue;
            String tb = safe(old.getTime()).trim();
            String ta = safe(e.getValue().getTime()).trim();
            if (tb.equals(ta)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("place", safe(e.getValue().getPlace()).trim());
            m.put("timeBefore", tb);
            m.put("timeAfter", ta);
            changed.add(m);
        }
        diff.put("removed", removed);
        diff.put("added", added);
        diff.put("changed", changed);
        diff.put("pointsBefore", countPoints(before));
        diff.put("pointsAfter", countPoints(after));
        diff.put("driveBefore", hmDrive(dayDriveMinutes(before)));
        diff.put("driveAfter", hmDrive(dayDriveMinutes(after)));
        return diff;
    }

    /** 地点 → 该地点第一次出现的条目（规范化地名去重：同一天里同一个地点只留一条） */
    private static Map<String, SectionItem> placeMap(Section sec) {
        Map<String, SectionItem> m = new LinkedHashMap<>();
        for (SectionItem it : safeItems(sec)) {
            if (it == null) continue;
            String p = safe(it.getPlace()).trim();
            if (p.isEmpty()) continue;                 //只比【地点】：没有地点的条目（纯时间行）不进小结
            String k = normalizeName(p);
            if (k.isEmpty() || m.containsKey(k)) continue;
            m.put(k, it);
        }
        return m;
    }

    private static Map<String, Object> placeEntry(SectionItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("place", safe(it.getPlace()).trim());
        m.put("time", safe(it.getTime()).trim());
        return m;
    }

    /**
     * 点位 = 【非交通条目】数（有地点或活动的条目；交通条目不算"点"）。
     * 与①的硬判定同一口径，避免"点位 10→10 却报成功"这种假落实。
     */
    private static int countPoints(Section sec) {
        int n = 0;
        for (SectionItem it : safeItems(sec)) {
            if (it == null) continue;
            if (isTraffic(it)) continue;
            if (safe(it.getPlace()).isBlank() && safe(it.getActivity()).isBlank()) continue;
            n++;
        }
        return n;
    }

    /** 新一天的概览站点列表（与数据模型口径一致：去括号说明、同一地点只出现一次） */
    private static List<String> stopsOf(Section sec) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SectionItem it : safeItems(sec)) {
            if (it == null) continue;
            String p = safe(it.getPlace()).replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", "").trim();
            if (p.isEmpty()) continue;
            if (seen.add(p)) out.add(p);
        }
        return out;
    }

    // ---- 条目的交通/住宿/景点分类（PlanValidator 的同口径复制：那些方法是包级私有，这里不能直接调） ----

    private static final String[] TRAVEL_WORDS = {
            "自驾", "驾车", "车程", "驱车", "行驶", "驶入", "驶出", "途经", "路过", "赶路", "返程", "前往", "抵达", "出发",
            "高铁", "动车", "火车", "列车", "飞机", "航班", "机场", "大巴", "包车", "打车", "出租", "租车", "自驾车",
            "地铁", "公交", "步行", "骑行", "摆渡", "索道",
            "高速", "国道", "省道", "公路", "路段", "里程", "服务区", "加油站", "收费站", "环线", "转乘"
    };
    private static final String[] TRAVEL_CAT_WORDS = {"交通", "自驾", "驾车", "车", "行", "驾", "航", "铁", "路", "驶"};
    private static final String[] NON_TRAVEL_CAT_WORDS = {"景点", "游览", "餐饮", "美食", "住宿", "酒店", "购物", "提示", "结束", "娱乐", "活动"};
    private static final String[] STAY_WORDS = {"入住", "住宿", "酒店", "民宿", "客栈", "check in", "checkin"};

    private static boolean isTraffic(SectionItem it) {
        if (it == null) return false;
        String cat = safe(it.getCategory()).trim();
        if (!cat.isEmpty()) {
            for (String w : TRAVEL_CAT_WORDS) {
                if (cat.contains(w)) return true;
            }
            for (String w : NON_TRAVEL_CAT_WORDS) {
                if (cat.contains(w)) return false;
            }
        }
        String hay = safe(it.getWay()) + " " + safe(it.getActivity()) + " " + safe(it.getNote())
                + " " + safe(it.getFrom()) + " " + safe(it.getTo());
        for (String w : TRAVEL_WORDS) {
            if (hay.contains(w)) return true;
        }
        return false;
    }

    private static boolean isStay(SectionItem it) {
        if (it == null) return false;
        String cat = safe(it.getCategory());
        if (!cat.isBlank()) return cat.contains("住") || cat.contains("酒店");
        String hay = safe(it.getActivity()) + " " + safe(it.getName());
        for (String w : STAY_WORDS) {
            if (hay.contains(w)) return true;
        }
        return false;
    }

    /**
     * 某一天的车程分钟数。
     * 直接复用 PlanValidator 的公开口径（{@link PlanValidator#totalDriveMinutes}）：
     * 声明车程 → 交通条目时长累加 → 里程÷70km/h 估算。
     */
    private static int dayDriveMinutes(Section sec) {
        if (sec == null) return 0;
        Section copy = JsonUtils.parse(JsonUtils.toJson(sec), Section.class);
        if (copy == null) return 0;
        copy.setType("timeline");
        copy.setTitle("第1天"); //借 PlanValidator 的"按天归并"（标题必须能解析出天号）
        TripPlanData tmp = new TripPlanData();
        List<Section> one = new ArrayList<>();
        one.add(copy);
        tmp.setSections(one);
        tmp.setDays(1);
        return PlanValidator.totalDriveMinutes(tmp);
    }

    /** 分钟 → 「3h20m」 */
    private static String hmDrive(int minutes) {
        int m = Math.max(0, minutes);
        return (m / 60) + "h" + String.format("%02d", m % 60) + "m";
    }

    // ==================== 天号识别 ====================

    /** 从用户原话识别目标天（1 起）；识别不到返回 0 */
    static int parseTargetDay(String instruction) {
        if (instruction == null) return 0;
        String s = instruction.trim();
        Matcher m = DAY_CN_PAT.matcher(s);
        if (m.find()) {
            int v = cn2Int(m.group(1));
            if (v > 0) return v;
        }
        m = DAY_EN_PAT.matcher(s);
        if (m.find()) return toInt(m.group(1));
        m = DAY_D_PAT.matcher(s);
        if (m.find()) return toInt(m.group(1));
        m = DAY_BARE_PAT.matcher(s);
        if (m.find()) {
            int v = cn2Int(m.group(1));
            if (v > 0) return v;
        }
        return 0;
    }

    /** 中文数字/阿拉伯数字 → int（支持 一~二十）；解析不了返回 0 */
    private static int cn2Int(String t) {
        if (t == null || t.isBlank()) return 0;
        String v = t.trim();
        if (v.matches("[0-9]+")) return toInt(v);
        int ten = v.indexOf('十');
        java.util.Map<Character, Integer> one = new java.util.HashMap<>();
        one.put('一', 1); one.put('二', 2); one.put('两', 2); one.put('三', 3); one.put('四', 4);
        one.put('五', 5); one.put('六', 6); one.put('七', 7); one.put('八', 8); one.put('九', 9);
        if (ten >= 0) {
            int left = ten == 0 ? 1 : one.getOrDefault(v.charAt(ten - 1), 0);
            int right = ten + 1 < v.length() ? one.getOrDefault(v.charAt(ten + 1), 0) : 0;
            return left * 10 + right;
        }
        return v.length() == 1 ? one.getOrDefault(v.charAt(0), 0) : one.getOrDefault(v.charAt(0), 0);
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 是否"加减天数"这类整体性要求（本接口不做，如实回绝） */
    private static boolean containsDayCountChange(String s) {
        if (s == null) return false;
        for (String w : DAY_COUNT_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    private static boolean looksWholeTrip(String s) {
        if (s == null) return false;
        for (String w : WHOLE_TRIP_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /** 区块标题里的天号（与 PlanValidator 同口径）；取不到返回 0 */
    private static int titleDayOf(String title) {
        if (title == null) return 0;
        Matcher m = TITLE_DAY_PAT.matcher(title);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) return toInt(m.group(i));
            }
        }
        return 0;
    }

    // ==================== LLM / 解析 / 落库（与 TripEditController 同口径的复制版） ====================

    /** 调 LLM 生成某天的新行程区块（返回 JSON 字符串） */
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
            log.error("对话式改行程 LLM 调用失败: {}", e.getMessage());
            return "";
        }
    }

    private Section parseSection(String json) {
        if (json == null || json.isBlank()) return null;
        return JsonUtils.extractAndParse(json, Section.class);
    }

    /** 旧数据兜底：从 plan_content 的 JSON 标记块里解析结构化行程（与编辑页同一标记口径） */
    private TripPlanData parsePlanFromContent(Object contentObj) {
        if (contentObj == null) return null;
        String content = String.valueOf(contentObj);
        final String mark = "<!--ATPLAN_JSON_START-->";
        final String end = "<!--ATPLAN_JSON_END-->";
        int i = content.indexOf(mark);
        if (i < 0) return null;
        int j = content.indexOf(end, i);
        if (j <= i) return null;
        String raw = content.substring(i + mark.length(), j).trim();
        Object parsed = JsonUtils.parse(raw, Map.class);
        if (parsed instanceof Map<?, ?> m && m.containsKey("__planSet")
                && m.get("set") instanceof Map<?, ?> setMap
                && setMap.get("plans") instanceof List<?> plans && !plans.isEmpty()) {
            return JsonUtils.parse(JsonUtils.toJson(plans.get(0)), TripPlanData.class);
        }
        return JsonUtils.parse(raw, TripPlanData.class);
    }

    /** 结构化行程 -> markdown（与 TripEditController.renderMarkdown 同一逻辑的复制版，保持 plan_content 一致） */
    private String renderMarkdown(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.getTitle()).append("\n\n");
        if (plan.getWeather() != null && !plan.getWeather().isBlank()) {
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");
        }
        for (Section sec : safeSections(plan)) {
            sb.append("## ").append(sec.getTitle()).append("\n");
            for (SectionItem it : safeItems(sec)) {
                sb.append("- ");
                switch (sec.getType() == null ? "" : sec.getType()) {
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

    private static List<Section> safeSections(TripPlanData plan) {
        return (plan == null || plan.getSections() == null) ? new ArrayList<>() : plan.getSections();
    }

    private static List<SectionItem> safeItems(Section sec) {
        return (sec == null || sec.getItems() == null) ? new ArrayList<>() : sec.getItems();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
