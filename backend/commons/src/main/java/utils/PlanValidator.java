package utils;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;
import dto.ValidationReport.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交付前约束校验器（纯函数，无副作用、无外部依赖）。
 *
 * <p><b>定位</b>：行程编排本身交给 LLM 自由发挥，本类只做"**约束能不能过**"的客观判定，
 * 判定结果用于：① 交付前拦截（BLOCK 打回重做）② 如实标注（WARN/UNKNOWN 不影响交付）。
 *
 * <p><b>铁律</b>：<b>判不出来就报 UNKNOWN，绝不当作通过</b>——否则"约束"等于没写。
 *
 * <p>规则清单：
 * <pre>
 * A1 每天游玩+车程 ≤ 8h     （仅跨城）
 * A2 每天纯游玩 ≥ 4h        （仅跨城）
 * A3 单日赶路 ≤ 6h          （仅跨城）
 * A4 赶路 > 4h 必须有真景点  （仅跨城）
 * A5 天数 = 用户要求且每天都有安排
 * A6 景点地点须来自真实数据（防编造）
 * A7 每天时间线单调递增
 * A8 夜间/凌晨(23:00-05:00)不赶路（仅跨城）
 * A10 区块不得重复
 * </pre>
 *
 * <p>用户口径："单城市不需要这些改动"——A1~A4、A8 仅在跨城时判定。
 */
public final class PlanValidator {

    /** 单日游玩+车程上限（分钟）：8h */
    private static final int DAY_TOTAL_MAX_MIN = 8 * 60;
    /** 单日纯游玩下限（分钟）：4h */
    private static final int DAY_PLAY_MIN_MIN = 4 * 60;
    /** 单日赶路上限（分钟）：6h（对外暴露，供编排层做可行性复核） */
    public static final int DAY_DRIVE_MAX_MIN = 6 * 60;
    /** "赶路日"判定阈值（分钟）：4h */
    private static final int LONG_DRIVE_MIN = 4 * 60;
    /**
     * 单日车程估算时速（km/h）——兜底用。
     * ★口径（用户已定）：Agent 给的车程小时最优先；没给才退回里程估算。
     *   70km/h 与"450km/日"口径自洽：450 ÷ 70 ≈ 6.4h ≈ 单日车程上限 6h，两边对得上，不是孤立拍数。
     */
    private static final float EST_KMH = 70f;
    /** 可行性判定用的日均里程（km/日）——往返总里程 ÷ 它 = 所需天数 */
    public static final float KM_PER_DAY = 450f;

    private PlanValidator() {}

    /** 天号识别：兼容「第3天 / Day 3 / D3 / 第三天」等写法 */
    private static final Pattern DAY_PAT =
            Pattern.compile("(?:第\\s*(\\d+)\\s*天)|(?:Day\\s*(\\d+))|(?:\\bD(\\d+)\\b)", Pattern.CASE_INSENSITIVE);
    /** 时间点：09:00 / 9:00 / 09：00 */
    private static final Pattern CLOCK_PAT = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})");
    /** 当天显式声明的车程小时：如「当日车程合计约4.5小时」「车程4小时」 */
    private static final Pattern DAY_DRIVE_PAT = Pattern.compile(
            "(?:当日|今日|当天|全天|本日)?[ ]*车程[约合计共总]{0,4}[:：]?[ ]*[约合计共总]{0,2}[ ]*(\\d+(?:\\.\\d+)?)[ ]*(?:小时|个小时|h|H)");
    /** 里程：420公里 / 420km / 420 千米 */
    private static final Pattern KM_PAT = Pattern.compile("(\\d{2,4})[ ]*(?:公里|千米|km|KM)");

    /** 时长：2小时 / 1.5小时 / 90分钟 / 半天 / 2h */
    private static final Pattern DUR_HOUR_PAT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:小时|个小时|h|H)");
    private static final Pattern DUR_MIN_PAT = Pattern.compile("(\\d+)\\s*(?:分钟|分)");

    /** 交通类关键词（用于类别缺失时的兜底判定；实测持续扩充，避免 A3 漏判） */
    private static final String[] TRAVEL_WORDS = {
            "自驾", "驾车", "车程", "驱车", "行驶", "驶入", "驶出", "途经", "路过", "赶路", "返程", "前往", "抵达", "出发",
            "高铁", "动车", "火车", "列车", "飞机", "航班", "机场", "大巴", "包车", "打车", "出租", "租车", "自驾车",
            "地铁", "公交", "步行", "骑行", "摆渡", "索道",
            "高速", "国道", "省道", "公路", "路段", "里程", "服务区", "加油站", "收费站", "环线", "转乘"
    };
    /** category 里的交通词（实测 LLM 会填「自驾」而非「交通」，必须覆盖） */
    private static final String[] TRAVEL_CAT_WORDS = {"交通", "自驾", "驾车", "车", "行", "驾", "航", "铁", "路", "驶"};
    /** category 里明确的非交通类别（命中则判否，避免"行"等单字误伤） */
    private static final String[] NON_TRAVEL_CAT_WORDS = {"景点", "游览", "餐饮", "美食", "住宿", "酒店", "购物", "提示", "结束", "娱乐", "活动"};
    /** 「非地点表述」特征：路段/地理描述/自造词 —— A6 真正该抓的就是这类 */
    private static final String[] PSEUDO_PLACE_WORDS = {"段", "沿线", "交界", "地带", "流域", "河谷", "丘陵", "山麓", "走廊", "区间",
            "神秘", "未开发", "秘境", "未知", "传说中的", "自造"};
    /** 开放时段：「开放 09:00-17:00」（兼容全角冒号、波浪线、至） */
    private static final Pattern OPEN_PAT = Pattern.compile(
            "开放[^0-9]{0,4}(\\d{1,2})[:：](\\d{2})\\s*[-—~～至]\\s*(\\d{1,2})[:：](\\d{2})");

    /** 行政区/地理通名后缀：这类名字是"行政地名"而非"编造景点"，A6 不判定 */
    private static final String[] ADMIN_SUFFIX = {"市", "州", "县", "区", "镇", "乡", "盟", "旗", "省", "自治区", "街道"};
    /** 住宿类关键词 */
    private static final String[] STAY_WORDS = {"入住", "住宿", "酒店", "民宿", "客栈", "check in", "checkin"};

    /**
     * 执行校验。
     *
     * @param plan          聚合后的结构化行程（可为 null）
     * @param req           用户需求
     * @param allowedPlaces 真实地点白名单（来自工具/候选数据）；为空表示无白名单→跳过 A6
     */
    /** 走向合理性上限：实际总里程 / 不绕路直达里程 的比值上限（用户口径："在合理范围内"） */
    public static final double DETOUR_MAX_RATIO = 1.2;

    /** 兼容旧签名（不判走向合理性，A11 会报 UNKNOWN） */
    public static ValidationReport validate(TripPlanData plan, TripRequest req, Set<String> allowedPlaces) {
        return validate(plan, req, allowedPlaces, null);
    }

    public static ValidationReport validate(TripPlanData plan, TripRequest req, Set<String> allowedPlaces, String routeText) {
        ValidationReport r = new ValidationReport();
        try {
            if (plan == null) {
                r.add(0, "A5", "行程结构", Severity.BLOCK, "聚合结果为空，未生成任何结构化行程");
                return r;
            }
            boolean crossCity = isCrossCity(req);
            int wantDays = req == null ? 0 : Math.max(0, req.getDays());

            // ---- A14 必去对象：用户明确"必须去"的必须安排进行程 ----
            if (req != null && req.getMustVisit() != null) {
                for (String mv : req.getMustVisit()) {
                    if (mv == null || mv.isBlank()) continue;
                    if (findAvoidHit(plan, mv.trim()) == null) {
                        r.add(0, "A14", "用户必去的对象必须安排进行程", Severity.BLOCK,
                                "行程里没有用户明确要求必去的「" + mv.trim() + "」");
                    }
                }
            }

            // ---- A15 预算：总花费不得超过用户给的预算（仅当用户给了预算才判）----
            if (req != null && req.getBudgetTotal() > 0) {
                double total = 0;
                int parsed = 0;
                for (Section s : safe(plan.getSections())) {
                    for (SectionItem it : safe(s.getItems())) {
                        Double c = parseMoney(it.getCost());
                        if (c == null) c = parseMoney(it.getPrice());
                        if (c != null) { total += c; parsed++; }
                    }
                }
                if (parsed == 0) {
                    r.add(0, "A15", "总花费不得超过预算", Severity.UNKNOWN,
                            "行程里没有可解析的费用数据，无法与预算 " + (long) req.getBudgetTotal() + " 元比较");
                } else if (total > req.getBudgetTotal()) {
                    r.add(0, "A15", "总花费不得超过预算", Severity.WARN,
                            "行程合计约 " + (long) total + " 元，超出预算 " + (long) req.getBudgetTotal()
                                    + " 元共 " + (long) (total - req.getBudgetTotal()) + " 元");
                }
            }

            // ---- A13 回避对象：用户/画像明确"回避"的东西不得出现在行程里 ----
            //   数据来源：TripRequest.preferences.avoid（个人中心画像，已透传进 prompt）；
            //   只看 place/activity 两个字段（不看 note —— note 里常有"不含爬山"这类解释性文字，会误伤）。
            if (req != null && req.getPreferences() != null && req.getPreferences().getAvoid() != null) {
                for (String av : req.getPreferences().getAvoid()) {
                    if (av == null || av.isBlank()) continue;
                    String hit = findAvoidHit(plan, av.trim());
                    if (hit != null) {
                        r.add(0, "A13", "回避对象不得出现在行程里", Severity.BLOCK,
                                "行程里出现你要求回避的「" + av.trim() + "」：" + hit);
                    }
                }
            }

            // ---- A11 走向合理性：为沿途景点绕路必须"在合理范围内" ----
            //   用户口径（2026-09-12）：沿途景点可以影响选路，但不能为了景点乱绕。
            //   可客观判定式：实际总里程 ÷ 「不绕路直达里程」→ 超过 DETOUR_MAX_RATIO 即报。
            //   注意：分母取不到时【报 UNKNOWN，绝不当通过】（判不出来不下结论）。
            if (crossCity) {
                Integer actualKm = utils.RouteFeasibility.totalKm(routeText);
                Integer directKm = utils.RouteFeasibility.directKm(routeText);
                if (actualKm != null && directKm != null && directKm > 0) {
                    double ratio = actualKm * 1.0 / directKm;
                    if (ratio > DETOUR_MAX_RATIO) {
                        r.add(0, "A11", "走向须合理（为沿途景点绕路不得过多）", Severity.WARN,
                                "实际总里程 " + actualKm + "km 是不绕路直达 " + directKm + "km 的 "
                                        + String.format("%.0f", (ratio - 1) * 100) + "% 超出（上限 "
                                        + String.format("%.0f", (DETOUR_MAX_RATIO - 1) * 100) + "%）");
                    }
                } else {
                    r.add(0, "A11", "走向须合理（为沿途景点绕路不得过多）", Severity.UNKNOWN,
                            "路线文本未同时给出总里程与不绕路直达里程，无法判定绕路幅度");
                }
            }

            // ---- A10 区块重复 ----
            Map<String, Integer> seen = new HashMap<>();
            for (Section s : safe(plan.getSections())) {
                String key = str(s.getType()) + "|" + str(s.getTitle());
                seen.merge(key, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : seen.entrySet()) {
                if (e.getValue() > 1) {
                    r.add(0, "A10", "区块不得重复", Severity.WARN,
                            "区块「" + e.getKey() + "」出现 " + e.getValue() + " 次（重复）");
                }
            }

            // ---- 按天收集 timeline 条目 ----
            Map<Integer, List<SectionItem>> dayItems = new LinkedHashMap<>();
            for (Section s : safe(plan.getSections())) {
                if (!"timeline".equalsIgnoreCase(str(s.getType()))) continue;
                int d = dayOf(s.getTitle());
                if (d <= 0) continue;
                dayItems.computeIfAbsent(d, k -> new ArrayList<>()).addAll(safe(s.getItems()));

            }

            // ---- A16 营业时间：只用于【排程】—— 有开放时间就校验到访时间是否落在时段内，
            //   避免「18:30 参观 17:00 闭馆的博物馆」这类硬伤（数据由行程Agent 调工具查得，写进条目「开放 HH:mm-HH:mm」）。
            //   用户口径：查不到就【按全天开放处理】—— 不报无法判定，也不因为缺开放时间就少排景点。
            {
                int openSeen = 0;
                for (Map.Entry<Integer, List<SectionItem>> de : dayItems.entrySet()) {
                    int d = de.getKey();
                    for (SectionItem it : safe(de.getValue())) {
                        String blob = str(it.getNote()) + " " + str(it.getActivity()) + " " + str(it.getText());
                        Matcher om = OPEN_PAT.matcher(blob);
                        if (!om.find()) continue;
                        openSeen++;
                        Integer st = clockMinutes(it.getTime());
                        if (st == null) continue;
                        Integer os = clockMinutes(om.group(1) + ":" + om.group(2));
                        Integer oe = clockMinutes(om.group(3) + ":" + om.group(4));
                        if (os == null || oe == null) continue;
                        if (st < os || st >= oe) {
                            r.add(d, "A16", "行程时间须在该地点开放时段内", Severity.WARN,
                                    "第" + d + "天 " + str(it.getTime()) + " 安排「" + clip(it) + "」，"
                                            + "而该地开放时段为 " + om.group(1) + ":" + om.group(2) + "-"
                                            + om.group(3) + ":" + om.group(4));
                        }
                    }
                }
                //openSeen == 0：查不到开放时间 → 视为全天开放，不报（开放时间只用于编排，不作为交付门槛）
            }


            // ---- A5 天数与覆盖 ----
            if (wantDays > 0 && plan.getDays() > 0 && plan.getDays() != wantDays) {
                r.add(0, "A5", "天数须等于用户要求", Severity.BLOCK,
                        "行程天数为 " + plan.getDays() + "，用户要求 " + wantDays + " 天");
            }
            if (wantDays > 0) {
                List<Integer> missing = new ArrayList<>();
                for (int d = 1; d <= wantDays; d++) {
                    if (!dayItems.containsKey(d) || dayItems.get(d).isEmpty()) missing.add(d);
                }
                if (!missing.isEmpty()) {
                    r.add(0, "A5", "每天都要有安排", Severity.BLOCK,
                            "第 " + join(missing) + " 天没有任何时间线安排（缺 " + missing.size() + " 天）");
                }
            }

            // ---- 逐天判定时间/车程类约束 ----
            int checked = 0, unknown = 0;
            for (Map.Entry<Integer, List<SectionItem>> e : dayItems.entrySet()) {
                int d = e.getKey();
                List<SectionItem> items = e.getValue();
                // ★时长来源（不依赖 LLM 填字段）：
                //   ① 优先 SectionItem.duration 字段；
                //   ② 缺失时用"下一条的 time − 本条 time"按**纯算术**推算（如 06:00→12:30 即 6.5h）。
                //   实测依据：gitee 聚合产出里 duration 恒为空串，若不推算，A1~A4 只能全部报"无法判定"=约束落空。
                java.util.List<Integer> mins = new java.util.ArrayList<>();
                for (int k = 0; k < items.size(); k++) {
                    Integer m = parseDuration(str(items.get(k).getDuration()));
                    if (m == null) {
                        Integer a = clockMinutes(items.get(k).getTime());
                        Integer b = (k + 1 < items.size()) ? clockMinutes(items.get(k + 1).getTime()) : null;
                        if (a != null && b != null && b > a) m = b - a; // 时段推算
                    }
                    mins.add(m);
                }
                int play = 0, driveFromItems = 0, unknownMin = 0;
                boolean hasSight = false, playUnknown = false;
                for (int k = 0; k < items.size(); k++) {
                    SectionItem it = items.get(k);
                    Integer min = mins.get(k);
                    boolean travel = isTravel(it);
                    boolean sight = isSight(it);
                    // ★沿途路景不算"正常景点"（用户口径 2026-09-12）：路段/地理描述式表述
                    //   （「粤北丘陵」「喀斯特峰林」「西江河谷段」）既不计入游玩时长，
                    //   也【不能】用来满足 A4「赶路>4h 须有真景点」——否则纯赶路日会被路景"洗白"。
                    //   这类表述本身已由 A6 报出，此处不重复报，只是不计入。
                    if (sight && !isPseudoPlace(placeOf(it))) hasSight = true;
                    if (min == null) {
                        // 只统计"会计入判定"的条目（车程/景点）——餐饮住宿时长缺失不算数据缺口，
                        // 否则几乎每天都报"数据不足"，把真实问题淹没。
                        if (travel || sight) unknownMin++;
                        if (sight) playUnknown = true;
                    } else if (travel) {
                        driveFromItems += min;
                    } else if (sight && !isPseudoPlace(placeOf(it))) {
                        play += min;   // ★路景（路段/地理描述式表述）不计入游玩时长
                    }
                    // ★餐饮/住宿/购物/提示等一律【不计入】（用户口径：用餐、休整已另计）
                    //   实测教训：原先 else 分支把餐饮1h也算进"游玩"，导致合规的 8.0h 被算成 9.0h，
                    //   每天误报"超出1h"，还触发无谓的打回重做。
                    // A8 夜间赶路（仅跨城）
                    if (crossCity && travel) {
                        Integer hh = hourOf(it.getTime());
                        if (hh != null && (hh >= 23 || hh < 5)) {
                            r.add(d, "A8", "夜间/凌晨不赶路", Severity.BLOCK,
                                    "第" + d + "天 " + str(it.getTime()) + " 安排赶路（" + clip(it) + "）");
                        }
                    }
                }

                // ---- A7 时间线单调递增 ----
                Integer prev = null;
                for (SectionItem it : items) {
                    Integer minOfDay = clockMinutes(it.getTime());
                    if (minOfDay == null) continue;
                    if (prev != null && minOfDay < prev) {
                        r.add(d, "A7", "时间线须递增", Severity.WARN,
                                "第" + d + "天 " + str(it.getTime()) + " 早于前一条（" + clip(it) + "），时间线可能乱序");
                        break;
                    }
                    prev = minOfDay;
                }

                // ---- A12 同一天不得把同一景区拆成多行（虚增内容）----
                //   实测场景：「成都市·宽窄巷子」「宽窄巷子·井巷子」看着是两处，其实是同一个地方拆成两行。
                //   v6 的踩坑经验：`·` 前段 >4 字才是"景区主体"（如「四姑娘山景区双桥沟·红杉林」→ 取前段）；
                //   前段 ≤4 字多是城市/古镇前缀（「丹巴·中路乡」「康定·情歌广场」）→ 必须整串比，否则同城全判重复。
                Map<String, Integer> dayMain = new LinkedHashMap<>();
                for (SectionItem it : items) {
                    if (!isSight(it) || isPseudoPlace(placeOf(it))) continue;
                    String main = placeMain(placeOf(it));
                    if (main.length() < 2) continue;
                    dayMain.merge(main, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e2 : dayMain.entrySet()) {
                    if (e2.getValue() > 1) {
                        r.add(d, "A12", "同一天不得拆分同一景区凑数", Severity.WARN,
                                "第" + d + "天「" + e2.getKey() + "」被拆成 " + e2.getValue() + " 行（同一景区应写成一条）");
                    }
                }

                // ---- A6 地点来源（防编造）----
                if (allowedPlaces != null && !allowedPlaces.isEmpty()) {
                    for (SectionItem it : items) {
                        if (!isSight(it)) continue;
                        String place = placeOf(it);
                        if (place.isBlank() || place.length() < 2) continue;
                        if (isAdminName(place)) continue;                 // 行政地名（梧州/桂林/康定…）不算编造景点
                        if (placeIsReal(place, allowedPlaces)) continue;  // 命中真实数据 → 通过
                        // ★只报"明显不是地点"的表述（路段/地理描述/自造词）。
                        //   不再"凡不在白名单就报"——实测白名单依赖子Agent 回传的清单，**不可能完整**；
                        //   硬报会把「龙脊梯田/甲秀楼/宽窄巷子/甲居藏寨」等**真实景点**误判为编造，
                        //   进而污染修正清单、让 LLM 白改甚至改坏。无法验证的不下结论（与 UNKNOWN 同一原则）。
                        if (isPseudoPlace(place)) {
                            r.add(d, "A6", "地点须是真实景点而非路段描述", Severity.WARN,
                                    "第" + d + "天「" + place + "」是路段/地理描述式表述，不是可游览景点（不能当景点写）");
                        }
                    }
                }

                // ---- ★当天车程小时：三级优先（口径已定）----
                //   ① Agent/行程里显式给的「车程 X 小时」——最真实（路线Agent 本就在查高德耗时）
                //   ② 交通条目时长累加
                //   ③ 都没有、但有里程 → 里程 ÷ 70km/h 估算
                //   都拿不到 → 车程无数据（不硬凑，不假装判定过）
                Integer declaredDrive = declaredDriveMinutes(items);
                Integer dayKm = dayMileage(items);
                int drive;
                String driveSrc;
                if (declaredDrive != null) {
                    drive = declaredDrive;
                    driveSrc = "";
                } else if (driveFromItems > 0) {
                    drive = driveFromItems;
                    driveSrc = "";
                } else if (dayKm != null) {
                    drive = Math.round(dayKm / EST_KMH * 60f);
                    driveSrc = "（车程按里程" + dayKm + "km÷" + (int) EST_KMH + "km/h 估算）";
                } else {
                    drive = 0;
                    driveSrc = "（无车程数据）";
                }

                // 注意：只有当"既没车程小时、也没里程"时才算真·无数据；否则车程仍可用估算判定（并标注来源）
                if (unknownMin == items.size() && !items.isEmpty() && declaredDrive == null && dayKm == null) {
                    unknown++;
                    if (crossCity) {
                        r.add(d, "A1", "每天游玩+车程≤8h", Severity.UNKNOWN,
                                "第" + d + "天条目均无可用时长信息，无法判定时间预算");
                    }
                    continue;
                }
                checked++;

                if (!crossCity) continue; // 用户明令：单城市不做这些约束

                if (play + drive > DAY_TOTAL_MAX_MIN) {
                    r.add(d, "A1", "每天游玩+车程≤8h", Severity.BLOCK,
                            "第" + d + "天 游玩" + hm(play) + "+车程" + hm(drive) + "=" + hm(play + drive)
                                    + "，超出 8h " + hm(play + drive - DAY_TOTAL_MAX_MIN) + driveSrc);
                }
                // ★A2 下限随当天车程动态收敛（口径已定）：游玩下限 = min(4h, 8h − 当天车程)
                //   车程越长，能玩的时间越少——这是常识，不该"一刀切 4h"。
                //   实测依据：v5 中第2/5/6天纯游玩 3.0/3.3/3.5h 被一刀切判违规，触发了两轮无谓重做。
                int playMin = Math.min(DAY_PLAY_MIN_MIN, DAY_TOTAL_MAX_MIN - drive);
                if (playMin < 0) playMin = 0;
                if (play < playMin) {
                    if (playUnknown) {
                        r.add(d, "A2", "每天纯游玩须达下限", Severity.UNKNOWN,
                                "第" + d + "天有 " + unknownMin + " 条时长未识别，纯游玩下限无法判定（已识别部分 " + hm(play) + "）");
                    } else {
                        r.add(d, "A2", "每天纯游玩须达下限", Severity.BLOCK,
                                "第" + d + "天纯游玩仅 " + hm(play) + "（当天车程 " + hm(drive) + " → 下限 "
                                        + hm(playMin) + "）" + driveSrc);
                    }
                }
                if (drive > DAY_DRIVE_MAX_MIN) {
                    r.add(d, "A3", "单日赶路≤6h", Severity.BLOCK,
                            "第" + d + "天赶路 " + hm(drive) + "，超出 6h " + hm(drive - DAY_DRIVE_MAX_MIN) + driveSrc);
                }
                if (drive > LONG_DRIVE_MIN && !hasSight) {
                    r.add(d, "A4", "赶路>4h须沿途有真景点", Severity.BLOCK,
                            "第" + d + "天赶路 " + hm(drive) + " 却没有任何景点安排（不能只赶路）");
                }
            }
            r.setCheckedDays(checked);
            r.setUnknownDays(unknown);
        } catch (Throwable t) {
            // 校验器自身绝不允许影响主流程：异常一律降级为"无法判定"，如实上报
            r.add(0, "A0", "校验器执行", Severity.UNKNOWN, "校验过程异常：" + t);
        }
        return r;
    }

    // ==================== 判定辅助 ====================

    /** 是否跨城：出发地与目的地都非空且不同（与编排层判定口径一致） */
    public static boolean isCrossCity(TripRequest req) {
        if (req == null) return false;
        String o = str(req.getOrigin()).trim(), d = str(req.getDestination()).trim();
        return !o.isEmpty() && !d.isEmpty() && !o.equals(d);
    }

    /**
     * 单日车程（分钟）——与 {@link #validate} 同口径的三级解析：
     * ①Agent 声明的「车程 X 小时」②交通条目时长（缺失时按下一条时间推算）③里程 ÷ 70km/h。
     * 拿不到返回 0。
     */
    static int dayDriveMinutes(List<SectionItem> items) {
        List<SectionItem> its = safe(items);
        Integer declared = declaredDriveMinutes(its);
        if (declared != null) return declared;
        int sum = 0;
        for (int k = 0; k < its.size(); k++) {
            SectionItem it = its.get(k);
            if (!isTravel(it)) continue;
            Integer m = parseDuration(str(it.getDuration()));
            if (m == null) {
                Integer a = clockMinutes(it.getTime());
                Integer b = (k + 1 < its.size()) ? clockMinutes(its.get(k + 1).getTime()) : null;
                if (a != null && b != null && b > a) m = b - a;
            }
            if (m != null) sum += m;
        }
        if (sum > 0) return sum;
        Integer km = dayMileage(its);
        return km == null ? 0 : Math.round(km / EST_KMH * 60f);
    }

    /**
     * 全行程车程合计（分钟）——供编排层做"可行性复核"。
     *
     * <p>存在意义：<b>路线Agent 有时不给总里程</b>，此时 {@link RouteFeasibility} 按铁律不下结论，
     * 就会漏掉"物理上排不开"的情形、又走一遍重做（白烧时间）。行程自身的车程数据是更硬的依据。
     */
    public static int totalDriveMinutes(TripPlanData plan) {
        if (plan == null) return 0;
        Map<Integer, List<SectionItem>> dayItems = new LinkedHashMap<>();
        for (Section s : safe(plan.getSections())) {
            if (!"timeline".equalsIgnoreCase(str(s.getType()))) continue;
            int d = dayOf(s.getTitle());
            if (d <= 0) continue;
            dayItems.computeIfAbsent(d, k -> new ArrayList<>()).addAll(safe(s.getItems()));
        }
        int total = 0;
        for (List<SectionItem> its : dayItems.values()) {
            total += dayDriveMinutes(its);
        }
        return total;
    }

    /** 把条目的所有文本字段拼成一段，供关键词/数值解析 */
    private static String blobOf(SectionItem it) {
        if (it == null) return "";
        return str(it.getTime()) + " " + str(it.getActivity()) + " " + str(it.getPlace()) + " "
                + str(it.getNote()) + " " + str(it.getWay()) + " " + str(it.getFrom()) + " "
                + str(it.getTo()) + " " + str(it.getName()) + " " + str(it.getText());
    }

    /**
     * 当天显式声明的车程（分钟）——形如「当日车程合计约4.5小时」「车程 4 小时」。
     * 取不到返回 null（绝不用别的东西凑数）。
     */
    static Integer declaredDriveMinutes(List<SectionItem> items) {
        for (SectionItem it : safe(items)) {
            Matcher m = DAY_DRIVE_PAT.matcher(blobOf(it));
            while (m.find()) {
                try {
                    return Math.round(Float.parseFloat(m.group(1)) * 60f);
                } catch (NumberFormatException ignore) { }
            }
        }
        return null;
    }

    /** 当天里程（km）：取条目里出现的最大值（通常某条写"当日合计420公里"）；取不到返回 null */
    static Integer dayMileage(List<SectionItem> items) {
        int max = -1;
        for (SectionItem it : safe(items)) {
            Matcher m = KM_PAT.matcher(blobOf(it));
            while (m.find()) {
                try { max = Math.max(max, Integer.parseInt(m.group(1))); } catch (NumberFormatException ignore) { }
            }
        }
        return max < 0 ? null : max;
    }

    /** 从区块标题解析天号；解析不到返回 0 */
    static int dayOf(String title) {
        if (title == null) return 0;
        Matcher m = DAY_PAT.matcher(title);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) {
                    try { return Integer.parseInt(m.group(i)); } catch (NumberFormatException ignore) { }
                }
            }
        }
        return 0;
    }

    /** 条目时长（分钟）；判不出返回 null（→ 计入 UNKNOWN，绝不按 0 处理） */
    static Integer minutesOf(SectionItem it) {
        if (it == null) return null;
        Integer fromDur = parseDuration(str(it.getDuration()));
        if (fromDur != null) return fromDur;
        return timeRangeMinutes(str(it.getTime()));
    }

    /** 解析时长文本 */
    static Integer parseDuration(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.contains("半天")) return 240;
        if (t.contains("全天") || t.contains("一整天")) return 480;
        Matcher h = DUR_HOUR_PAT.matcher(t);
        if (h.find()) {
            try { return (int) Math.round(Double.parseDouble(h.group(1)) * 60); } catch (NumberFormatException ignore) { }
        }
        Matcher m = DUR_MIN_PAT.matcher(t);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) { }
        }
        return null;
    }

    /** 解析时间段 "09:00-11:00" / "09:00~11:00" 的分钟数 */
    static Integer timeRangeMinutes(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = CLOCK_PAT.matcher(s);
        List<Integer> mins = new ArrayList<>();
        while (m.find()) {
            int hh = Integer.parseInt(m.group(1)), mm = Integer.parseInt(m.group(2));
            if (hh <= 23 && mm <= 59) mins.add(hh * 60 + mm);
        }
        if (mins.size() >= 2) {
            int span = mins.get(1) - mins.get(0);
            if (span < 0) span += 24 * 60; // 跨零点
            return span > 0 && span <= 24 * 60 ? span : null;
        }
        return null;
    }

    /** 取时间点的"当天分钟数"（用于单调性判定） */
    static Integer clockMinutes(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = CLOCK_PAT.matcher(s);
        if (m.find()) {
            int hh = Integer.parseInt(m.group(1)), mm = Integer.parseInt(m.group(2));
            if (hh <= 23 && mm <= 59) return hh * 60 + mm;
        }
        return null;
    }

    /** 取时间点的小时（用于夜间判定） */
    static Integer hourOf(String s) {
        Integer m = clockMinutes(s);
        return m == null ? null : m / 60;
    }

    /**
     * 是否交通条目。
     *
     * <p>★实测教训（留证定位）：LLM 会把交通类条目的 category 填成「自驾」而非「交通」，
     * 原规则只认 交通/车/行 →「自驾」被判成非交通 → 车程恒为 0.0h、A3 漏判。
     * 且 category 非空时原实现直接 return，连关键词兜底都不会执行。
     * 现改为三段：①命中交通词→是 ②命中明确的非交通类别→否 ③未识别→走关键词兜底。
     */
    static boolean isTravel(SectionItem it) {
        if (it == null) return false;
        String cat = str(it.getCategory()).trim();
        if (!cat.isEmpty()) {
            for (String w : TRAVEL_CAT_WORDS) {
                if (cat.contains(w)) return true;
            }
            for (String w : NON_TRAVEL_CAT_WORDS) {
                if (cat.contains(w)) return false;
            }
            // 未识别的类别：继续走下面的关键词兜底，不要直接判否
        }
        String hay = str(it.getWay()) + " " + str(it.getActivity()) + " " + str(it.getNote())
                + " " + str(it.getFrom()) + " " + str(it.getTo());
        for (String w : TRAVEL_WORDS) {
            if (hay.contains(w)) return true;
        }
        return false;
    }

    /** 是否景点条目 */
    static boolean isSight(SectionItem it) {
        if (it == null) return false;
        String cat = str(it.getCategory());
        if (!cat.isBlank()) return cat.contains("景") || cat.contains("玩") || cat.contains("游");
        if (isTravel(it) || isStay(it)) return false;
        return !placeOf(it).isBlank();
    }

    /** 是否住宿条目 */
    static boolean isStay(SectionItem it) {
        if (it == null) return false;
        String cat = str(it.getCategory());
        if (!cat.isBlank()) return cat.contains("住") || cat.contains("酒店");
        String hay = str(it.getActivity()) + " " + str(it.getName());
        for (String w : STAY_WORDS) {
            if (hay.contains(w)) return true;
        }
        return false;
    }

    /** 取地点文本 */
    static String placeOf(SectionItem it) {
        if (it == null) return "";
        String p = str(it.getPlace());
        if (!p.isBlank()) return p.trim();
        return str(it.getName()).trim();
    }

    /** 地点是否命中真实白名单（包含关系双向匹配，容忍"锦里古街"vs"锦里"） */
    static boolean placeIsReal(String place, Set<String> allowed) {
        String p = normalize(place);
        if (p.length() < 2) return true; // 太短难以判定，不误报
        String pc = core(place);         // 去掉括号补充说明后的主名
        for (String a : allowed) {
            String n = normalize(a);
            if (n.length() < 2) continue;
            if (p.contains(n) || n.contains(p)) return true;
            // ★实测误报修复：产出写「漓江（兴坪段）」、白名单写「阳朔漓江」时，
            //   括号里的"（兴坪段）"只是补充说明、不改变地点身份 → 用主名再比一次，否则会被误判成"非地点表述"。
            String nc = core(a);
            if (pc.length() >= 2 && nc.length() >= 2 && (pc.contains(nc) || nc.contains(pc))) return true;
        }
        return false;
    }

    /** 去掉括号补充说明后的主名（「漓江（兴坪段）」→「漓江」） */
    private static String core(String s) {
        return normalize(str(s).replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", ""));
    }

    private static String normalize(String s) {
        return str(s).replaceAll("[\\s（）()【】\\[\\]、,，。.·-]", "");
    }

    private static <T> List<T> safe(List<T> l) {
        return l == null ? new ArrayList<>() : l;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String clip(SectionItem it) {
        String s = str(it.getActivity());
        if (s.isBlank()) s = str(it.getName());
        if (s.isBlank()) s = str(it.getNote());
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /** 分钟 → "1.5h" 展示 */
    static String hm(int minutes) {
        return String.format("%.1fh", minutes / 60.0);
    }

    private static String join(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    /** 按天归并的只读视图（供上层按天打回重做使用） */
    public static Map<Integer, List<SectionItem>> groupByDay(TripPlanData plan) {
        Map<Integer, List<SectionItem>> out = new LinkedHashMap<>();
        for (Section s : safe(plan == null ? null : plan.getSections())) {
            if (!"timeline".equalsIgnoreCase(str(s.getType()))) continue;
            int d = dayOf(s.getTitle());
            if (d <= 0) continue;
            out.computeIfAbsent(d, k -> new ArrayList<>()).addAll(safe(s.getItems()));
        }
        return out;
    }

    /** 行程末尾附带的真实地点清单块标记（由子Agent 按任务要求输出） */
    public static final String PLACES_BLOCK = "【真实地点清单】";

    /**
     * 解析子Agent 回传的【真实地点清单】：只保留它**实际查到的**地点名。
     *
     * <p>这是 A6「防编造」的数据基准——没有清单就不判定（返回空集 → 上层跳过 A6），绝不瞎猜。
     */
    public static Set<String> parsePlacesBlock(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;
        int i = text.indexOf(PLACES_BLOCK);
        if (i < 0) return out;
        String tail = text.substring(i + PLACES_BLOCK.length());
        for (String raw : tail.split("[\r\n]+")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // 遇到下一个块标题则停止（清单块结束）
            if (line.startsWith("【") && !line.startsWith(PLACES_BLOCK)) break;
            line = line.replaceFirst("^[-*•·\\d.、)\\s]+", "").trim();
            if (line.length() >= 2 && line.length() <= 40) out.add(line);
        }
        return out;
    }

    /** 剥离【真实地点清单】块（避免它作为正文进入结构化聚合） */
    public static String stripPlacesBlock(String text) {
        if (text == null) return "";
        int i = text.indexOf(PLACES_BLOCK);
        return i < 0 ? text : text.substring(0, i).trim();
    }

    /**
     * 是否**行政区地名**（如「广西梧州」「四川泸州」）——是则 A6 不判定，避免把真实地名误报成编造。
     *
     * <p>收紧口径（实测教训）：必须同时满足「长度 ≤ 4」+「行政后缀」。
     * 否则「神秘未知古镇」这类**编造的景点**会因以"镇"结尾而被误放过，A6 形同虚设。
     */
    /** 是否「非地点表述」：路段/地理描述/自造词（A6 真正该抓的） */
    /**
     * 地点主名：用于"同景区拆行"的重复判定（口径取自 v6 `ConstraintEngine.placeMain`）。
     * 「·」前段 &gt;4 字视为景区主体（「四姑娘山景区双桥沟·红杉林」→「四姑娘山景区双桥沟」）；
     * 前段 ≤4 字多为城市/古镇前缀（「丹巴·中路乡」「康定·情歌广场」）→ 整串保留，
     * 否则同一城市的所有「丹巴·X」会互相误判重复。
     */
    /** 解析金额文本为元（"免费"=0；"80元/人"=80；"1.5万"=15000）；无法解析返回 null */
    static Double parseMoney(String s) {
        String t = str(s).trim();
        if (t.isEmpty()) return null;
        if (t.contains("免费")) return 0.0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万)?").matcher(t);
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group(1));
            if (m.group(2) != null) v *= 10000;
            return v;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 在行程的地点/活动字段里找一个回避词（命中返回该条目摘要） */
    private static String findAvoidHit(TripPlanData plan, String term) {
        for (Section s : safe(plan.getSections())) {
            if (!"timeline".equalsIgnoreCase(str(s.getType()))) continue;
            for (SectionItem it : safe(s.getItems())) {
                String hay = str(it.getPlace()) + " " + str(it.getActivity());
                if (hay.contains(term)) return clip(it);
            }
        }
        return null;
    }

    static String placeMain(String place) {
        String p = str(place).trim();
        int i = p.indexOf('·');
        if (i <= 0) return p;
        String main = p.substring(0, i).trim();
        return main.length() > 4 ? main : p;
    }

    public static boolean isPseudoPlace(String name) {
        String n = str(name).trim();
        if (n.isEmpty()) return false;
        for (String w : PSEUDO_PLACE_WORDS) {
            if (n.contains(w)) return true;
        }
        return false;
    }

    public static boolean isAdminName(String name) {
        String n = str(name).trim();
        if (n.isEmpty() || n.length() > 4) return false;
        for (String suf : ADMIN_SUFFIX) {
            if (n.endsWith(suf)) return true;
        }
        return false;
    }

    /**
     * 从**路线结果**里抽取真实地名，并入 A6 白名单。
     *
     * <p>实测依据：只拿子Agent 回传的清单当白名单时，「广西梧州/贵州三都/康定」等**真实地名**被误报为编造；
     * 而路线结果本身就是真实工具查询的产物，其中的地名同样是"真实数据"。
     */
    public static Set<String> placesFromText(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;
        Matcher m = Pattern.compile(
                "[\\u4e00-\\u9fa5]{2,10}?(?:市|县|区|镇|州|乡|景区|古镇|雪山|草原|沟|湖|海|桥|寺|庙|宫|公园|垭口|观景台|步道|老街)")
                .matcher(text);
        while (m.find()) {
            // 剥离前缀：整段中文常连写（如「成都出发前往康定市」），按最后一个动词/分隔符切掉前面
            String p = m.group().trim();
            for (String sep : new String[]{"前往", "抵达", "到达", "来自", "出发", "途经", "路过", "经过", "穿过",
                    "沿着", "经", "从", "到", "去", "在", "、", "，", ",", " ", "—", "－", "-", "→"}) {
                int idx = p.lastIndexOf(sep);
                if (idx >= 0) p = p.substring(idx + sep.length()).trim();
            }
            if (p.length() >= 2 && p.length() <= 12) out.add(p);
        }
        return out;
    }

    /** 白名单收集工具：把工具返回文本里的地点名收敛为集合（供上层调用） */
    public static Set<String> collectPlaces(Iterable<String> rawTexts) {
        Set<String> out = new HashSet<>();
        if (rawTexts == null) return out;
        for (String t : rawTexts) {
            if (t == null || t.isBlank()) continue;
            Matcher m = Pattern.compile("[\"'](?:name|place|title)[\"']\\s*[:：]\\s*[\"']([^\"']{2,40})[\"']").matcher(t);
            while (m.find()) out.add(m.group(1).trim());
        }
        return out;
    }
}
