package utils;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性约束校验引擎（普通代码，非 Agent）。
 * 对结构化行程做程序化规则校验：天数一致 / 预算超支 / P0 必去与回避约束 / 景点重复 /
 * 每日时间顺序 / 交通时长异常。
 * 分级设计：数据缺失（无预算数、无时间字段、无结构化区块）时跳过该项并记录 skippedChecks，
 * 绝不因数据覆盖率低而误报 FAIL。
 */
public final class ConstraintEngine {  // 类声明：确定性约束校验引擎（纯代码规则，不用 LLM）

    /** 单段交通时长上限（分钟），超过视为异常 */
    private static final int MAX_LEG_MINUTES = 360;  // MAX_LEG_MINUTES：单段交通上限 360 分钟（6 小时）

    /** 每天纯游玩时长下限（分钟）：低于 4h 视为"内容过少"（大景区 1 个玩一整天也满足，靠时长而非个数） */
    private static final int MIN_PLAY_MINUTES = 240;  // MIN_PLAY_MINUTES：每日最少游玩 240 分钟（4 小时）

    /** 每天纯游玩时长上限（分钟）：超过 8h 视为"过于赶场"（旅行优先） */
    private static final int MAX_PLAY_MINUTES = 480;  // MAX_PLAY_MINUTES：每日最多游玩 480 分钟（8 小时）

    /** 单日赶路阈值（分钟）：在途超过 4h 必须沿途安排景点（纯赶路不可接受） */
    private static final int TRAVEL_MINUTES = 240;  // TRAVEL_MINUTES：赶路日阈值 240 分钟（4 小时）

    /** 单日赶路上限（分钟）：在途累计超过 6h 视为路线过载，报 WARN（旅行优先） */
    private static final int MAX_TRAVEL_MINUTES = 360;  // MAX_TRAVEL_MINUTES：单日累计在途上限 360 分钟（6 小时）

    private static final Pattern MONEY = Pattern.compile("(\\d+(?:\\.\\d+)?)(\\s*万)?");  // MONEY：金额正则（数字 + 可选万）
    private static final Pattern HHMM = Pattern.compile("^(\\d{1,2}):(\\d{2})");  // HHMM：时:分 正则
    /** "08:00-13:00" / "8:00～13:30" 起止时间范围（兼容全角波浪线、至） */
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[-–~～至]\\s*(\\d{1,2}):(\\d{2})");  // TIME_RANGE：时间段正则（各种连接符）
    /** transport 条目 time 字段的"第N天"天归属标记 */
    private static final Pattern DAY_OF = Pattern.compile("第(\\d+)天");  // DAY_OF：第N天 正则
    private static final Pattern DUR_HM = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?小时(?:\\s*(\\d+)\\s*分?)?");  // DUR_HM：X小时Y分 正则
    private static final Pattern DUR_MIN = Pattern.compile("(\\d+)\\s*分(?:钟)?");  // DUR_MIN：X分钟 正则
    private static final Pattern DUR_HOUR_ONLY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:h|小时)");  // DUR_HOUR_ONLY：X小时/Xh 正则

    /** 必去/回避约束文本中的对象名提取前缀（RequirementNormalizer 与 LLM 常见表达） */
    private static final String[] MUST_PREFIXES = {"必去", "一定要去", "必须去", "必到"};  // MUST_PREFIXES：必去类约束前缀
    private static final String[] AVOID_PREFIXES = {"避免", "不去", "不要去", "不想去", "避开", "别去"};  // AVOID_PREFIXES：回避类约束前缀
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ConstraintEngine() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：约束校验入口（不含候选数据）：天数、单日节奏、预算、用户硬性要求、时间列、交通衔接逐项检查，汇总成校验报告
     * 参数：req 需求（含天数/预算/硬性要求）；plan 待校验行程
     * 返回值：校验报告（违规明细、是否通过、修正指令）
     * 异常：无（单项检查异常不影响其余检查）
     */
    public static ValidationReport validate(TripRequest req, TripPlanData plan) {  // validate（两参）：不带候选数据的校验
        return validate(req, plan, null);  // 委托三参版本，candidatesJson=null
    }  // 方法结束

    /**
     * 作用：约束校验入口（含三域候选数据）：在基础校验上追加开放时间冲突与酒店位置合理性检查
     * 参数：req 需求；plan 待校验行程；candidatesJson 三域候选 JSON（景点/酒店/餐饮的真实营业时间与坐标）
     * 返回值：校验报告（违规明细、是否通过、修正指令）
     * 异常：无（候选 JSON 非法时降级为不含候选的校验）
     */
    public static ValidationReport validate(TripRequest req, TripPlanData plan, String candidatesJson) {  // validate（三参）：主校验流程
        ValidationReport report = new ValidationReport();  // report：校验报告
        if (plan == null) {  // 行程为空
            report.add(new ValidationReport.Issue("schedule", "high", "overall", "行程整体为空，无法校验"));  // 记一条 high 问题
            report.resolveStatus();  // 算最终结论
            return report;  // 返回报告
        }  // if 结束
        checkDays(req, plan, report);  // ① 天数一致性
        checkDailyPace(req, plan, report);  // ①b 每天节奏
        checkBudget(req, plan, report);  // ② 预算
        checkConstraints(req, plan, report);  // ③ 必去/回避约束
        checkTimeline(plan, report);  // ④ 时间顺序与重复景点
        checkTransport(plan, report);  // ④b 单段交通时长
        java.util.Map<String, Object> candidates = CandidateUtil.parse(candidatesJson);  // 解析三域候选（营业时间/酒店位置要用）
        checkOpeningHours(plan, candidates, report);  // ⑤ 营业时间
        checkHotelLocation(req, plan, candidates, report);  // ⑥ 酒店位置
        report.resolveStatus();  // 算最终结论（PASS/WARN/FAIL）
        return report;  // 返回报告
    }  // 方法结束

    // ==================== ① 天数一致性 ====================

    /**
     * 作用：校验行程天区块数量与需求天数是否一致
     * 参数：req 需求；plan 行程；report 待写入的校验报告
     * 返回值：无
     * 异常：无
     */
    private static void checkDays(TripRequest req, TripPlanData plan, ValidationReport report) {  // checkDays：天数一致性
        if (req == null || req.getDays() < 1) {  // 需求为空或天数未知
            report.getSkippedChecks().add("天数一致性(请求天数未知)");  // 记为跳过（数据不足不判错）
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("天数一致性");  // 标记已执行
        if (plan.getDays() > 0 && plan.getDays() != req.getDays()) {  // 方案天数与需求不符
            report.add(new ValidationReport.Issue("schedule", "high", "overall",  // 记 high 问题：天数不符
                    "天数不符：请求 " + req.getDays() + " 天，方案 " + plan.getDays() + " 天"));  // 续行
        }  // if 结束
        int timelineDays = countTimelineDays(plan);  // timelineDays：实际有内容的天数
        if (timelineDays > 0 && timelineDays != req.getDays()) {  // 与需求不符
            report.add(new ValidationReport.Issue("schedule", "high", "overall",  // 记 high 问题：区块天数不符
                    "每日行程区块为 " + timelineDays + " 天，与请求 " + req.getDays() + " 天不符"));  // 续行
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：统计行程中真正有内容的天区块数量
     * 参数：plan 行程
     * 返回值：天区块数
     * 异常：无
     */

    private static int countTimelineDays(TripPlanData plan) {  // countTimelineDays：数有内容的 timeline 区块
        int n = 0;  // n：计数
        if (plan.getSections() != null) {  // 有区块
            for (Section s : plan.getSections()) {  // 遍历
                if ("timeline".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty()) {  // timeline 且条目非空
                    n++;  // 计数 +1
                }  // if 结束
            }  // for 结束
        }  // if 结束
        return n;  // 返回天数
    }  // 方法结束

    // ==================== ①b 每天节奏（按游玩时长 + 赶路约束） ====================

    /**
     * 作用：判断用户是否明确要求高强度紧凑节奏（是则放宽单日节奏规则）
     * 参数：req 需求
     * 返回值：true=用户主动要求赶节奏
     * 异常：无
     */
    private static boolean userOverridesPace(TripRequest req) {  // userOverridesPace：用户是否显式要求了节奏
        if (req == null || req.getRaw() == null) return false;  // 需求为空返回 false
        String raw = req.getRaw();  // raw：用户原话
        return raw.contains("特种兵") || raw.contains("多排") || raw.contains("少排")  // 含节奏相关词 → 由用户自己定节奏（
                || raw.contains("慢节奏") || raw.contains("慢游") || raw.contains("悠闲")  // 续：特种兵/多排/少排/慢节奏
                || raw.contains("不赶") || raw.contains("轻松") || raw.contains("多去几个")  // 续：悠闲/不赶/轻松
                || raw.contains("多安排") || raw.contains("少安排");  // 续：多去几个/多安排/少安排）
    }  // 方法结束

    /**
     * 作用：校验单日节奏：游玩+车程总时长、纯游玩时长、单日赶路上限
     * 参数：req 需求；plan 行程；report 校验报告
     * 返回值：无
     * 异常：无
     */
    private static void checkDailyPace(TripRequest req, TripPlanData plan, ValidationReport report) {  // checkDailyPace：每天节奏校验
        if (userOverridesPace(req)) {  // 用户显式要求节奏
            report.getSkippedChecks().add("每天节奏(用户显式要求节奏)");  // 记跳过（不按默认 4~8h 判）
            return;  // 返回
        }  // if 结束
        if (plan.getSections() == null) {  // 没有区块
            report.getSkippedChecks().add("每天节奏(无行程区块)");  // 记跳过
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("每天节奏");  // 标记已执行
        // 先解析 transport 段，建"天→跨城赶路分钟"映射（transport 条目 time 写「第N天」，duration 写「约X小时」）
        Map<Integer, Integer> transportByDay = new HashMap<>();  // transportByDay：天号 → 跨城赶路分钟
        for (Section s : plan.getSections()) {  // 遍历区块
            if (!"transport".equals(s.getType()) || s.getItems() == null) continue;  // 只看 transport
            for (SectionItem it : s.getItems()) {  // 遍历条目
                int d = dayOf(it.getTime());  // d：天号（从 time 的 第N天 解析）
                int dur = parseDurationMinutes(it.getDuration());  // dur：时长分钟
                if (d > 0 && dur > 0) transportByDay.merge(d, dur, Integer::sum);  // 同天累加
            }  // for 结束
        }  // if 结束
        int day = 0;  // day：当前第几天
        for (Section s : plan.getSections()) {  // 遍历区块
            if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;  // 只看 timeline
            day++;  // 天数递增
            if (s.getItems().isEmpty()) {  // 该天没有任何条目
                report.add(new ValidationReport.Issue("schedule", "medium", "第" + day + "天",  // 记 medium 问题：当天没排内容
                        "第" + day + "天未安排任何内容（请补充沿途观景点/景点或交通说明）"));  // 续行
                continue;  // 跳过该天
            }  // if 结束
            int playMinutes = 0;    // 当天景点游玩时长总和  // playMinutes：当天游玩时长
            int transitMinutes = transportByDay.getOrDefault(day, 0); // 当天在途 = transport 跨城段 + timeline 交通条目  // transitMinutes：当天在途（跨城段 + timeline 交通条目）
            for (SectionItem it : s.getItems()) {  // 遍历条目
                String cat = it.getCategory() == null ? "" : it.getCategory().trim();  // cat：类别
                boolean attraction = cat.isBlank() || "景点".equals(cat) || "attraction".equalsIgnoreCase(cat);  // attraction：空/景点/attraction 都算景点
                boolean transit = "交通".equals(cat) || "transport".equalsIgnoreCase(cat); // ★长途赶路；"衔接"=市内通勤不计入  // transit：交通类（衔接=市内通勤，不计入）
                String place = it.getPlace() == null ? "" : it.getPlace().trim();  // place：地点
                int span = spanMinutes(it.getTime());  // span：该条目的时间跨度分钟
                if (transit && span > 0) {  // 是交通且有跨度
                    transitMinutes += span;  // 计入在途
                }  // if 结束
                if (transit && isNightTime(it.getTime())) {  // 是交通且在夜间
                    report.add(new ValidationReport.Issue("transport", "medium", "第" + day + "天",  // 记 medium 问题：夜间赶路不安全
                            "第" + day + "天存在夜间/凌晨赶路（" + it.getTime() + "），夜间行车不安全，请调整出发或到达时间"));  // 续行
                }  // if 结束
                if (attraction && !place.isBlank() && !isGenericPlace(place) && span > 0) {  // 是景点且地点有效且有时长
                    playMinutes += span;  // 计入游玩时长
                }  // if 结束
            }  // for 结束
            // ① 单日累计赶路上限：>6h 报 WARN（旅行优先，不整天赶路）
            if (transitMinutes > MAX_TRAVEL_MINUTES) {  // 单日累计在途 > 6 小时
                report.add(new ValidationReport.Issue("transport", "medium", "第" + day + "天",  // 记 medium 问题：建议拆分或换交通方式
                        "第" + day + "天累计在途约 " + (transitMinutes / 60) + " 小时，超过 6 小时上限，建议拆分行程或改乘高铁/飞机"));  // 续行
            }  // if 结束
            // ② 赶路日（在途 4~6h）：必须有景点，纯赶路不可接受
            if (transitMinutes > TRAVEL_MINUTES && playMinutes == 0) {  // 在途 4~6 小时但完全没景点
                report.add(new ValidationReport.Issue("schedule", "medium", "第" + day + "天",  // 记 medium 问题：纯赶路不可接受
                        "第" + day + "天纯赶路约 " + (transitMinutes / 60) + " 小时且未安排景点，需缩短车程或沿途安排观景点"));  // 续行
            }  // if 结束
            // ③ 正常游玩日（在途 ≤4h）：游玩时长 4~8h
            if (transitMinutes <= TRAVEL_MINUTES) {  // 正常游玩日（在途 ≤4h）
                if (playMinutes < MIN_PLAY_MINUTES) {  // 游玩不足 4 小时
                    report.add(new ValidationReport.Issue("schedule", "medium", "第" + day + "天",  // 记 medium 问题：内容过少
                            "第" + day + "天游玩时长仅约 " + (playMinutes / 60) + " 小时，内容过少（默认每天 4~8 小时）"));  // 续行
                } else if (playMinutes > MAX_PLAY_MINUTES) {  // 游玩超过 8 小时
                    report.add(new ValidationReport.Issue("schedule", "medium", "第" + day + "天",  // 记 medium 问题：过于赶场
                            "第" + day + "天游玩时长约 " + (playMinutes / 60) + " 小时，过于赶场（默认每天 4~8 小时）"));  // 续行
                }  // if 结束
            }  // if 结束
        }  // for 结束
    }  // 方法结束

    // ==================== ② 预算校验 ====================

    /**
     * 作用：校验行程花费是否超出用户预算
     * 参数：req 需求；plan 行程；report 校验报告
     * 返回值：无
     * 异常：无
     */
    private static void checkBudget(TripRequest req, TripPlanData plan, ValidationReport report) {  // checkBudget：预算校验
        double budget = req == null ? 0 : req.getBudgetTotal();  // budget：用户预算
        if (budget <= 0) {  // 没识别出预算
            report.getSkippedChecks().add("预算校验(未识别数值预算)");  // 记跳过
            return;  // 返回
        }  // if 结束
        double sum = 0;  // sum：行程金额累加
        int parsed = 0;  // parsed：成功解析的条数
        if (plan.getSections() != null) {  // 有区块
            for (Section s : plan.getSections()) {  // 遍历区块
                if (s.getItems() == null) continue;  // 无条目跳过
                for (SectionItem it : s.getItems()) {  // 遍历条目
                    Double c = parseMoney("timeline".equals(s.getType()) ? it.getCost() : it.getPrice());  // timeline 取 cost，其它取 price
                    if (c != null) {  // 解析成功
                        sum += c;  // 累加
                        parsed++;  // 计数
                    }  // if 结束
                }  // for 结束
            }  // for 结束
        }  // if 结束
        if (parsed == 0) {  // 一条金额都没解析出来
            report.getSkippedChecks().add("预算校验(行程中无可解析金额)");  // 记跳过（数据不足）
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("预算校验(按行程标注费用累加比对)");  // 标记已执行
        if (sum > budget) {  // 超预算
            report.add(new ValidationReport.Issue("budget", "high", "overall",  // 记 high 问题：预算超支
                    "预算超支：明细估算 " + (long) sum + " 元 > 预算 " + (long) budget + " 元（按行程标注费用累加）"));  // 续行
        } else if (sum > budget * 0.9) {  // 超过预算 90%
            report.add(new ValidationReport.Issue("budget", "medium", "overall",  // 记 medium 问题：接近上限
                    "预算接近上限：明细估算 " + (long) sum + " 元 / 预算 " + (long) budget + " 元"));  // 续行
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：解析金额文本（元/¥/万等写法）
     * 参数：text 金额文本
     * 返回值：金额数值；无法解析返回 null
     * 异常：无
     */
    static Double parseMoney(String text) {  // parseMoney：从文本解析金额
        if (text == null || text.isBlank()) {  // 文本为空
            return null;  // 返回 null
        }  // if 结束
        String s = text.trim();  // s：去空格
        if (s.contains("免费") || s.contains("无需") || s.contains("不含")) {  // 免费类表述
            return 0.0;  // 按 0 元算
        }  // if 结束
        Matcher m = MONEY.matcher(s.replace("，", "").replace(",", ""));  // 去掉千分位后匹配数字
        if (m.find()) {  // 匹配到
            try {  // try
                double v = Double.parseDouble(m.group(1));  // 取值
                if (m.group(2) != null && !m.group(2).isBlank()) {  // 带 万 单位
                    v *= 10000;  // ×10000
                }  // if 结束
                if (v >= 0 && v <= 10_000_000) {  // 合理区间校验
                    return v;  // 返回金额
                }  // if 结束
            } catch (NumberFormatException ignored) {  // 捕获异常
            }  // catch 结束
        }  // if 结束
        return null;  // 解析不出返回 null
    }  // 方法结束

    // ==================== ③ P0 必去 / 回避约束 ====================

    /**
     * 作用：校验用户硬性要求是否被满足（必去地点有没有、回避地点有没有出现）
     * 参数：req 需求；plan 行程；report 校验报告
     * 返回值：无
     * 异常：无
     */
    private static void checkConstraints(TripRequest req, TripPlanData plan, ValidationReport report) {  // checkConstraints：必去/回避约束校验
        if (req == null || req.getConstraints() == null || req.getConstraints().isEmpty()) {  // 没有约束
            report.getSkippedChecks().add("约束校验(无明确约束)");  // 记跳过
            return;  // 返回
        }  // if 结束
        String text = planText(plan);  // text：整份行程的文本
        if (text.isBlank()) {  // 行程没文本
            report.getSkippedChecks().add("约束校验(行程无文本)");  // 记跳过
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("必去/回避约束校验");  // 标记已执行
        for (TripRequest.Constraint c : req.getConstraints()) {  // 逐条约束检查
            String t = c.getText() == null ? "" : c.getText().trim();  // t：约束文本
            String must = stripPrefix(t, MUST_PREFIXES);  // 去掉 必去 前缀
            if (must != null && !must.isBlank()) {  // 是必去类约束
                if (!text.contains(must)) {  // 行程里找不到该地点
                    report.add(new ValidationReport.Issue("constraint",  // 记问题：必去未落实（
                            "P0".equalsIgnoreCase(c.getLevel()) ? "high" : "medium", "overall",  // 续：P0 判 high，其它判 medium
                            "必去「" + must + "」未出现在行程中（约束: " + t + "）"));  // 续行
                }  // if 结束
                continue;  // 继续下一条
            }  // if 结束
            String avoid = stripPrefix(t, AVOID_PREFIXES);  // 去掉 回避 前缀
            if (avoid != null && !avoid.isBlank() && text.contains(avoid)) {  // 是回避类且行程里出现了
                report.add(new ValidationReport.Issue("constraint",  // 记问题：出现应回避的内容（
                        "P0".equalsIgnoreCase(c.getLevel()) ? "high" : "medium", "overall",  // 续：P0 判 high，其它 medium
                        "行程包含应回避的「" + avoid + "」（约束: " + t + "）"));  // 续行
            }  // if 结束
        }  // for 结束
    }  // 方法结束

    /**
     * 作用：去掉文本开头的指定前缀（如"必去："）
     * 参数：text 原文本；prefixes 需要剥离的前缀数组
     * 返回值：剥离后的文本
     * 异常：无
     */
    static String stripPrefix(String text, String[] prefixes) {  // stripPrefix：去掉约束前缀
        for (String p : prefixes) {  // 遍历前缀
            if (text.startsWith(p) && text.length() > p.length()) {  // 以该前缀开头且后面还有内容
                return text.substring(p.length());  // 返回去掉前缀后的部分
            }  // if 结束
        }  // for 结束
        return null;  // 不是该类前缀返回 null
    }  // 方法结束

    // ==================== ④ 时间顺序 / 重复景点 / 交通时长 ====================
    /**
     * 作用：校验每日时间列的单调性、重叠与夜间（23:00–05:00）赶路
     * 参数：plan 行程；report 校验报告
     * 返回值：无
     * 异常：无
     */

    private static void checkTimeline(TripPlanData plan, ValidationReport report) {  // checkTimeline：时间顺序与重复景点
        if (plan.getSections() == null) {  // 没有区块
            report.getSkippedChecks().add("时间顺序/重复景点(无区块)");  // 记跳过
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("同天景点重复检查");  // 标记已执行（同天景点重复检查）
        Set<String> seenAcrossDays = new HashSet<>();  // seenAcrossDays：跨天出现过的景点主名
        for (Section s : plan.getSections()) {  // 遍历区块
            if (!"timeline".equals(s.getType()) || s.getItems() == null || s.getItems().isEmpty()) {  // 只看非空 timeline
                continue;  // 非 timeline 或空区块 → 跳过
            }  // if 结束
            String day = s.getTitle() == null || s.getTitle().isBlank() ? "timeline" : s.getTitle();  // day：区块标题（定位用）
            // 每日内时间顺序：解析成功的时间应非递减
            int prev = -1;  // prev：上一项时间分钟
            boolean prevValid = false;  // prevValid：prev 是否可用
            Set<String> seenInDay = new HashSet<>();  // seenInDay：当天已出现的景点
            Set<String> seenNonAttr = new HashSet<>();  // seenNonAttr：当天已出现的非景点
            boolean anyTime = false;  // anyTime：该天是否有时刻
            for (SectionItem it : s.getItems()) {  // 遍历条目
                int cur = parseMinutesOfDay(it.getTime());  // cur：本条目的时间分钟
                if (cur >= 0) {  // 解析成功
                    anyTime = true;  // 标记有时间
                    if (prevValid && cur < prev) {  // 比上一项早 = 倒序
                        report.add(new ValidationReport.Issue("schedule", "medium", day,  // 记 medium 问题：时间倒序
                                day + " 时间倒序：" + it.getTime() + " 早于上一项"));  // 续行
                    }  // if 结束
                    prev = cur;  // 更新 prev
                    prevValid = true;  // prevValid=true
                }  // if 结束
                String place = it.getPlace() == null ? "" : it.getPlace().trim();  // place：地点名
            // 重复判定（2026-09-06 收紧）：
            // ① 景点/未分类类：按「主名」判（切掉 ·子地点后缀），同景区拆行（双桥沟·红杉林/·四姑娜措）视为重复
            // ② 非景点类（餐饮/住宿等）：仅精确同名算重复（古镇内多家真实店名不同名，不误伤）
            boolean attraction = it.getCategory() == null || it.getCategory().isBlank()  // attraction：是否景点类
                    || "景点".equals(it.getCategory()) || "attraction".equalsIgnoreCase(it.getCategory());  // 续：category 为 attraction 也算景点
            String key = attraction ? placeMain(place) : place;  // key：景点按主名比（切掉·后缀）  // 续：category 为 attraction 也算景点
            boolean generic = isGenericPlace(place);  // generic：是否泛称地点
            if (!place.isBlank() && !generic) {  // 地点有效且非泛称
                if (attraction) {  // 景点类
                    if (!seenInDay.add(key)) {  // 当天已出现过
                        report.add(new ValidationReport.Issue("duplicate", "medium", day,  // 记 medium 问题：重复安排（
                                day + " 重复安排「" + place + "」" + (key.equals(place) ? "" : "（与同景区「" + key + "」拆行重复）")  // 续：同景区拆行也算重复
                                        + "，同景区多条应合并为一条并在 activity 写全路线"));  // 续：建议合并成一条
                    } else if (!seenAcrossDays.add(key)) {  // 跨天出现过
                        report.add(new ValidationReport.Issue("duplicate", "low", day,  // 记 low 问题：多天重复
                                "「" + key + "」在多个天重复出现"));  // 续行
                    }  // if 结束
                } else if (!seenNonAttr.add(key)) {  // 非景点类只看精确同名
                    report.add(new ValidationReport.Issue("duplicate", "low", day,  // 记 low 问题：同名多次
                            day + " 「" + place + "」同名出现多次，建议合并进同一条描述（餐饮/入住/休息并入所在活动或写真实店名/酒店名）"));  // 续：建议合并
                }  // if 结束
            }  // if 结束
        }  // for 结束
            if (!anyTime) {  // 该天没有可解析时间
                report.getSkippedChecks().add("时间顺序(" + day + " 无时间字段)");  // 记跳过（时间顺序无法校验）
            }  // if 结束
        }  // for 结束
    }  // 方法结束

    /**
     * 作用：判断地点是否为泛化表述（如"酒店""景区""市区"，不能当作具体地点）
     * 参数：place 地点文本
     * 返回值：true=泛化表述
     * 异常：无
     */
    private static boolean isGenericPlace(String place) {  // isGenericPlace：是否泛称地点（自由活动/午餐等）
        return place.contains("自由") || place.contains("午餐") || place.contains("晚餐")  // 含泛称词（自由/
                || place.contains("早餐") || place.contains("休息") || place.contains("返程")  // 续：午餐晚餐/
                || place.contains("出发") || place.contains("到达");  // 续：早餐休息/返程出发到达）
    }  // 方法结束

    /**
     * 作用：取地点主名（去掉括号补充说明）
     * 参数：place 原始地点文本
     * 返回值：主名文本
     * 异常：无
     */
    static String placeMain(String place) {  // placeMain：取景点主名（切掉 · 后的子地点）
        int i = place.indexOf('·');  // 找中点符号
        if (i <= 0) return place.trim();  // 没有或位置非法就原样返回
        String main = place.substring(0, i).trim();  // main：中点前的部分
        return main.length() > 4 ? main : place.trim();  // 主名超过 4 字才采用（防误切）
    }  // 方法结束
    /**
     * 作用：校验跨天/跨地点的交通衔接是否缺失（有位移却无交通条目）
     * 参数：plan 行程；report 校验报告
     * 返回值：无
     * 异常：无
     */

    private static void checkTransport(TripPlanData plan, ValidationReport report) {  // checkTransport：单段交通时长校验
        if (plan.getSections() == null) {  // 没有区块
            report.getSkippedChecks().add("交通时长(无区块)");  // 记跳过
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("交通时长检查(单段超限判定)");  // 标记已执行
        boolean any = false;  // any：是否有可解析时长
        for (Section s : plan.getSections()) {  // 遍历区块
            if (!"transport".equals(s.getType()) || s.getItems() == null) continue;  // 只看 transport
            for (SectionItem it : s.getItems()) {  // 遍历条目
                int min = parseDurationMinutes(it.getDuration());  // min：时长分钟
                if (min >= 0) {  // 解析成功
                    any = true;  // 标记有数据
                    if (min > MAX_LEG_MINUTES) {  // 超过单段上限 6 小时
                        report.add(new ValidationReport.Issue("transport", "medium",  // 记 medium 问题（
                                (it.getFrom() == null ? "" : it.getFrom()) + "→" + (it.getTo() == null ? "" : it.getTo()),  // 续：定位为 起点→终点
                                "单段交通 " + it.getDuration() + " 超过 " + (MAX_LEG_MINUTES / 60) + " 小时，建议拆分或复核"));  // 续：建议拆分或复核）
                    }  // if 结束
                }  // if 结束
            }  // for 结束
        }  // if 结束
        if (!any) {  // 一条都没解析出来
            report.getSkippedChecks().add("交通时长(无可解析时长)");  // 记跳过
        }  // if 结束
    }  // 方法结束

    // ==================== ⑤ 营业时间（候选数据驱动） ====================

    /** 开放时间段形如 "08:30-18:00" / "8:00～17:30"（兼容全角波浪线与"至"，括号备注忽略） */
    private static final Pattern OPEN_RANGE = Pattern.compile(  // OPEN_RANGE：营业时间段正则
            "(\\d{1,2}):(\\d{2})\\s*[-–~～至]\\s*(\\d{1,2}):(\\d{2})");  // 续：时间段正则（时:分 - 时:分）
  // 续：时间段正则（时:分 - 时:分）
    /**
     * 作用：校验行程时间是否与候选数据的真实营业时间冲突
     * 参数：plan 行程；candidates 三域候选数据；report 校验报告
     * 返回值：无
     * 异常：无
     */
    private static void checkOpeningHours(TripPlanData plan, java.util.Map<String, Object> candidates,  // checkOpeningHours：营业时间校验（候选驱动）
                                          ValidationReport report) {  // 续：candidates 三域候选、report 校验报告
        if (plan.getSections() == null) {  // 没有区块  // 续：candidates 三域候选、report 校验报告
            report.getSkippedChecks().add("营业时间(无区块)");  // 记跳过
            return;  // 返回
        }  // if 结束
        if (candidates == null || candidates.isEmpty()) {  // 没有候选数据
            report.getSkippedChecks().add("营业时间(无结构化候选)");  // 记跳过
            return;  // 返回
        }  // if 结束
        // 名称 → 开放时间段（景点 + 餐饮两个域）
        java.util.Map<String, String> openMap = new java.util.HashMap<>();  // openMap：地点名 → 营业时段
        for (String domain : new String[]{"attraction", "dining"}) {  // 遍历景点与餐饮两个域
            for (java.util.Map<String, Object> it : CandidateUtil.items(candidates, domain)) {  // 遍历该域条目
                String name = str(it.get("name"));  // name：名称
                String open = str(it.get("openTime"));  // open：营业时间
                if (!name.isBlank() && OPEN_RANGE.matcher(open).find()) {  // 有名称且能解析出时段
                    openMap.put(name, open);  // 记入映射
                }  // if 结束
            }  // for 结束
        }  // for 结束
        if (openMap.isEmpty()) {  // 一个营业时间都没有
            report.getSkippedChecks().add("营业时间(候选无营业时间数据)");  // 记跳过
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("营业时间检查(候选开放时段比对)");  // 标记已执行
        int checked = 0;  // checked：实际比对条数
        for (Section s : plan.getSections()) {  // 遍历区块
            if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;  // 只看 timeline
            for (SectionItem it : s.getItems()) {  // 遍历条目
                String place = it.getPlace() == null ? "" : it.getPlace().trim();  // place：地点
                String open = lookupOpen(openMap, place);  // open：该地点营业时间
                if (open == null) continue;  // 查不到就跳过
                int minutes = parseMinutesOfDay(it.getTime());  // minutes：安排的时刻
                if (minutes < 0) continue;  // 解析不出就跳过
                java.util.regex.Matcher m = OPEN_RANGE.matcher(open);  // m：营业时段匹配器
                if (!m.find()) continue;  // 解析失败跳过
                int from = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));  // from：开门分钟
                int to = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));  // to：关门分钟
                checked++;  // 比对条数 +1
                // 结束时间越过 24:00（如酒吧 10:00-26:00）按次日处理不判违规
                if (to <= from) continue;  // 结束≤开始说明跨零点（如酒吧），跳过
                if (minutes < from - 15 || minutes > to + 15) { // 15分钟缓冲防边界误报  // 超出营业时间 15 分钟以上
                    report.add(new ValidationReport.Issue("opening", "medium", s.getTitle(),  // 记 medium 问题：疑似超营业时间（
                            s.getTitle() + " " + it.getTime() + " 安排「" + place  // 续：附地点与营业时段
                                    + "」，疑似超出营业时间（" + open + "），建议复核"));  // 续：建议复核
                }  // if 结束
            }  // for 结束
        }  // for 结束
        if (checked == 0) {  // 一条都没比对成
            report.getSkippedChecks().add("营业时间(行程条目无可匹配候选)");  // 记跳过（无可匹配候选）
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：在候选开放时间表中查找某地点的营业时间
     * 参数：openMap 地点到营业时间的映射；place 地点名
     * 返回值：营业时间文本；查不到返回空串
     * 异常：无
     */
    private static String lookupOpen(java.util.Map<String, String> openMap, String place) {  // lookupOpen：按地点名查营业时间
        if (place.isBlank()) return null;  // 地点为空返回 null
        String hit = openMap.get(place);  // 先精确匹配
        if (hit != null) return hit;  // 命中就返回
        for (java.util.Map.Entry<String, String> en : openMap.entrySet()) {  // 否则模糊匹配
            if (place.contains(en.getKey()) || en.getKey().contains(place)) {  // 双向包含即算命中
                return en.getValue();  // 返回营业时间
            }  // if 结束
        }  // for 结束
        return null;  // 查不到返回 null
    }  // 方法结束

    // ==================== ⑥ 酒店位置（候选数据驱动） ====================

    /**
     * 作用：校验酒店位置是否在当天行程动线附近（防止住宿离行程过远）
     * 参数：req 需求；plan 行程；candidates 三域候选数据；report 校验报告
     * 返回值：无
     * 异常：无
     */
    private static void checkHotelLocation(TripRequest req, TripPlanData plan,  // checkHotelLocation：酒店位置校验
                                           java.util.Map<String, Object> candidates, ValidationReport report) {  // 续：plan 行程、candidates 候选
        if (candidates == null || candidates.isEmpty()) {  // 没有候选  // 续：plan 行程、candidates 候选
            report.getSkippedChecks().add("酒店位置(无结构化候选)");  // 记跳过
            return;  // 返回
        }  // if 结束
        java.util.List<java.util.Map<String, Object>> hotels = CandidateUtil.items(candidates, "hotel");  // hotels：酒店候选
        if (hotels.isEmpty()) {  // 没有酒店候选
            report.getSkippedChecks().add("酒店位置(无酒店候选)");  // 记跳过
            return;  // 返回
        }  // if 结束
        report.getExecutedChecks().add("酒店位置检查(城市一致性+区域重合度)");  // 标记已执行
        String dest = req == null || req.getDestination() == null ? "" : req.getDestination().trim();  // dest：目的地
        // ① 城市一致性：候选 city 与目的地互不包含 → 数据可能错配
        int cityMismatch = 0;  // cityMismatch：城市不一致数
        int withCity = 0;  // withCity：带城市信息的候选数
        for (java.util.Map<String, Object> h : hotels) {  // 遍历酒店
            String city = str(h.get("city")).trim();  // city：候选城市
            if (city.isBlank()) continue;  // 空跳过
            withCity++;  // 计数 +1
            if (!dest.isBlank() && !city.contains(dest) && !dest.contains(city)) {  // 与目的地互不包含
                cityMismatch++;  // 不一致计数 +1
            }  // if 结束
        }  // for 结束
        if (withCity > 0 && cityMismatch == withCity && !dest.isBlank()) {  // 全部都不一致
            report.add(new ValidationReport.Issue("hotel", "medium", "hotel",  // 记 medium 问题：数据疑似错配
                    "酒店候选城市与目的地「" + dest + "」不一致，数据疑似错配，建议复核"));  // 续行
        }  // if 结束
        // ② 区域重合度：酒店区域与每日活动地点存在文本重合（双向包含）才算"位置顺"；全无重合 → low 提示
        java.util.Set<String> hotelAreas = new java.util.HashSet<>();  // hotelAreas：酒店所在区域集合
        for (java.util.Map<String, Object> h : hotels) {  // 遍历酒店
            String area = str(h.get("area")).trim();  // area：区域
            if (!area.isBlank()) hotelAreas.add(area);  // 非空就收集
        }  // for 结束
        if (hotelAreas.isEmpty()) {  // 没有区域数据
            report.getSkippedChecks().add("酒店位置(候选无区域数据)");  // 记跳过
            return;  // 返回
        }  // if 结束
        java.util.List<String> places = activityPlaces(plan);  // places：行程里的活动地点
        if (places.isEmpty()) {  // 没有活动地点
            report.getSkippedChecks().add("酒店位置(行程无活动地点)");  // 记跳过
            return;  // 返回
        }  // if 结束
        for (String area : hotelAreas) {  // 遍历酒店区域
            for (String pl : places) {  // 遍历活动地点
                if (pl.contains(area) || area.contains(pl)) {  // 双向包含即认为位置合理
                    return; // 任一酒店区域与活动地点重合即认为位置合理  // 直接返回（不报问题）
                }  // if 结束
            }  // for 结束
        }  // for 结束
        report.add(new ValidationReport.Issue("hotel", "low", "hotel",  // 完全没有重合
                "酒店区域（" + String.join("、", hotelAreas) + "）与每日活动地点缺乏重合，通勤时间可能偏长，建议关注"));  // 记 low 问题：通勤可能偏长
    }  // 方法结束

    /**
     * 作用：抽取行程中的活动地点（排除交通与住宿）
     * 参数：plan 行程
     * 返回值：地点列表
     * 异常：无
     */
    private static java.util.List<String> activityPlaces(TripPlanData plan) {  // activityPlaces：取行程里的活动地点
        java.util.List<String> out = new java.util.ArrayList<>();  // out：结果
        if (plan.getSections() != null) {  // 有区块
            for (Section s : plan.getSections()) {  // 遍历
                if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;  // 只看 timeline
                for (SectionItem it : s.getItems()) {  // 遍历条目
                    String p = nz(it.getPlace()).trim();  // p：地点（去空格）
                    if (!p.isBlank()) out.add(p);  // 非空就加入
                }  // for 结束
            }  // for 结束
        }  // if 结束
        return out;  // 返回地点列表
    }  // 方法结束

    // ==================== 解析工具 ====================

    /**
     * 作用：把时间点（HH:mm）转成当天分钟数
     * 参数：time 时间文本
     * 返回值：当天分钟数；无法解析返回 -1
     * 异常：无
     */
    static int parseMinutesOfDay(String time) {  // parseMinutesOfDay：时刻转当天分钟数
        if (time == null) return -1;  // 为空返回 -1
        Matcher m = HHMM.matcher(time.trim());  // 匹配 时:分
        if (m.find()) {  // 匹配到
            int h = Integer.parseInt(m.group(1));  // 小时
            int min = Integer.parseInt(m.group(2));  // 分钟
            if (h >= 0 && h <= 23 && min >= 0 && min <= 59) {  // 范围合法
                return h * 60 + min;  // 返回 h*60+min
            }  // if 结束
        }  // if 结束
        return -1;  // 解析不出返回 -1
    }  // 方法结束

    /**
     * 作用：计算时间段（09:00-11:00）的跨度分钟数
     * 参数：time 时间段文本
     * 返回值：跨度分钟数；无法解析返回 0
     * 异常：无
     */
    static int spanMinutes(String time) {  // spanMinutes：时间段转分钟跨度
        if (time == null) return -1;  // 为空返回 -1
        String s = time.trim();  // s：去空格
        Matcher m = TIME_RANGE.matcher(s);  // 匹配时间段
        if (m.find()) {  // 匹配到
            try {  // try
                int a = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));  // a：开始分钟
                int b = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));  // b：结束分钟
                if (b < a) b += 24 * 60;  // 跨零点（结束早于开始）
                return b - a;  // 返回跨度
            } catch (NumberFormatException ignored) {  // 捕获异常
                return -1;  // 返回 -1
            }  // catch 结束
        }  // if 结束
        // 单时间（只写了开始时刻）：按默认 2 小时估计，避免因格式缺失把游玩时长算成 0 而误报
        return HHMM.matcher(s).find() ? 120 : -1;  // 只有单个时刻：按默认 2 小时估（防算成 0 误报）
    }  // 方法结束

    /**
     * 作用：从带天标记的文本中解析天号
     * 参数：time 文本（如"第2天 09:00"）
     * 返回值：天号；无标记返回 0
     * 异常：无
     */
    static int dayOf(String time) {  // dayOf：从 第N天 解析天号
        if (time == null) return -1;  // 为空返回 -1
        Matcher m = DAY_OF.matcher(time);  // 匹配
        return m.find() ? Integer.parseInt(m.group(1)) : -1;  // 命中返回天号，否则 -1
    }  // 方法结束

    /**
     * 作用：判断时间点是否落在夜间（23:00–05:00）
     * 参数：time 时间文本
     * 返回值：true=夜间
     * 异常：无
     */
    static boolean isNightTime(String time) {  // isNightTime：是否夜间时段
        if (time == null) return false;  // 为空返回 false
        Matcher m = TIME_RANGE.matcher(time.trim());  // 匹配时间段
        if (m.find()) {  // 匹配到
            int a = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));  // a：开始分钟
            int b = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));  // b：结束分钟
            if (b < a) return true; // 跨天恒为夜间  // 跨天算夜间
            return a < 360 || b > 1200;  // 早于 6:00 或晚于 20:00 算夜间
        }  // if 结束
        int single = parseMinutesOfDay(time);  // 单个时刻同样判断
        return single >= 0 && (single < 360 || single > 1200);  // 返回判断结果
    }  // 方法结束

    /**
     * 作用：把时长文本（如"1.5小时""90分钟"）解析成分钟
     * 参数：duration 时长文本
     * 返回值：分钟数；无法解析返回 0
     * 异常：无
     */
    static int parseDurationMinutes(String duration) {  // parseDurationMinutes：时长文本转分钟
        if (duration == null || duration.isBlank()) return -1;  // 为空返回 -1
        String s = duration.trim();  // s：去空格
        Matcher m = DUR_HM.matcher(s);  // 先试 X小时Y分
        if (m.find()) {  // 匹配到
            try {  // try
                double h = Double.parseDouble(m.group(1));  // h：小时数
                int extra = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;  // extra：附加分钟
                return (int) Math.round(h * 60) + extra;  // 返回 h*60+extra
            } catch (NumberFormatException ignored) {  // 捕获异常
            }  // catch 结束
        }  // if 结束
        m = DUR_MIN.matcher(s);  // 再试 X分钟
        if (m.find()) {  // 匹配到
            try {  // try
                return Integer.parseInt(m.group(1));  // 直接返回分钟数
            } catch (NumberFormatException ignored) {  // 捕获异常
            }  // catch 结束
        }  // if 结束
        m = DUR_HOUR_ONLY.matcher(s);  // 再试 X小时 / Xh
        if (m.find()) {  // 匹配到
            try {  // try
                return (int) Math.round(Double.parseDouble(m.group(1)) * 60);  // 小时×60
            } catch (NumberFormatException ignored) {  // 捕获异常
            }  // catch 结束
        }  // if 结束
        return -1;  // 都认不出返回 -1
    }  // 方法结束

    /**
     * 作用：把整份行程拼成一段纯文本（用于关键词检查）
     * 参数：plan 行程
     * 返回值：拼接文本
     * 异常：无
     */
    private static String planText(TripPlanData plan) {  // planText：整份行程拼成文本（供关键词查找）
        StringBuilder sb = new StringBuilder();  // sb：输出
        sb.append(plan.getTitle() == null ? "" : plan.getTitle()).append(' ');  // 先放标题
        if (plan.getSections() != null) {  // 有区块
            for (Section s : plan.getSections()) {  // 遍历区块
                if (s.getItems() == null) continue;  // 无条目跳过
                for (SectionItem it : s.getItems()) {  // 遍历条目
                    sb.append(nz(it.getPlace())).append(' ')  // 地点
                            .append(nz(it.getName())).append(' ')  // 名称
                            .append(nz(it.getActivity())).append(' ')  // 活动
                            .append(nz(it.getNote())).append(' ');  // 备注
                }  // for 结束
            }  // for 结束
        }  // if 结束
        return sb.toString();  // 返回拼接文本
    }  // 方法结束
    /**
     * 作用：null/空白安全字符串
     * 参数：s 可能为 null 的字符串
     * 返回值：原字符串；null 或空白返回空串
     * 异常：无
     */

    private static String nz(String s) {  // nz：null 安全字符串
        return s == null ? "" : s;  // null 归空串
    }  // 方法结束
    /**
     * 作用：安全转字符串
     * 参数：o 任意对象
     * 返回值：对象字符串；null 返回空串
     * 异常：无
     */

    private static String str(Object o) {  // str：对象转字符串
        return o == null ? "" : String.valueOf(o);  // null 归空串
    }  // 方法结束
}  // 类结束
