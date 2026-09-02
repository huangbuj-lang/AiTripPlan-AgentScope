package utils;

import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;

import java.util.HashSet;
import java.util.List;
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
public final class ConstraintEngine {

    /** 单段交通时长上限（分钟），超过视为异常 */
    private static final int MAX_LEG_MINUTES = 360;

    private static final Pattern MONEY = Pattern.compile("(\\d+(?:\\.\\d+)?)(\\s*万)?");
    private static final Pattern HHMM = Pattern.compile("^(\\d{1,2}):(\\d{2})");
    private static final Pattern DUR_HM = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?小时(?:\\s*(\\d+)\\s*分?)?");
    private static final Pattern DUR_MIN = Pattern.compile("(\\d+)\\s*分(?:钟)?");
    private static final Pattern DUR_HOUR_ONLY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:h|小时)");

    /** 必去/回避约束文本中的对象名提取前缀（RequirementNormalizer 与 LLM 常见表达） */
    private static final String[] MUST_PREFIXES = {"必去", "一定要去", "必须去", "必到"};
    private static final String[] AVOID_PREFIXES = {"避免", "不去", "不要去", "不想去", "避开", "别去"};

    private ConstraintEngine() {
    }

    /** 主入口：校验结构化行程，产出 ValidationReport（status/score/issues 已汇总）。 */
    public static ValidationReport validate(TripRequest req, TripPlanData plan) {
        return validate(req, plan, null);
    }

    /** 主入口（带三域候选 JSON）：候选里含营业时间/酒店区域数据时可执行更多确定性检查，无候选自动降级。 */
    public static ValidationReport validate(TripRequest req, TripPlanData plan, String candidatesJson) {
        ValidationReport report = new ValidationReport();
        if (plan == null) {
            report.add(new ValidationReport.Issue("schedule", "high", "overall", "行程整体为空，无法校验"));
            report.resolveStatus();
            return report;
        }
        checkDays(req, plan, report);
        checkBudget(req, plan, report);
        checkConstraints(req, plan, report);
        checkTimeline(plan, report);
        checkTransport(plan, report);
        java.util.Map<String, Object> candidates = CandidateUtil.parse(candidatesJson);
        checkOpeningHours(plan, candidates, report);
        checkHotelLocation(req, plan, candidates, report);
        report.resolveStatus();
        return report;
    }

    // ==================== ① 天数一致性 ====================

    /** 请求天数与行程天数/timeline 天数不一致 → high（天数是 P0 级契约）。 */
    private static void checkDays(TripRequest req, TripPlanData plan, ValidationReport report) {
        if (req == null || req.getDays() < 1) {
            report.getSkippedChecks().add("天数一致性(请求天数未知)");
            return;
        }
        if (plan.getDays() > 0 && plan.getDays() != req.getDays()) {
            report.add(new ValidationReport.Issue("schedule", "high", "overall",
                    "天数不符：请求 " + req.getDays() + " 天，方案 " + plan.getDays() + " 天"));
        }
        int timelineDays = countTimelineDays(plan);
        if (timelineDays > 0 && timelineDays != req.getDays()) {
            report.add(new ValidationReport.Issue("schedule", "high", "overall",
                    "每日行程区块为 " + timelineDays + " 天，与请求 " + req.getDays() + " 天不符"));
        }
    }

    private static int countTimelineDays(TripPlanData plan) {
        int n = 0;
        if (plan.getSections() != null) {
            for (Section s : plan.getSections()) {
                if ("timeline".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    // ==================== ② 预算校验 ====================

    /** 汇总 timeline.cost 与 stay.price 的可解析金额，与 budgetTotal 比较。 */
    private static void checkBudget(TripRequest req, TripPlanData plan, ValidationReport report) {
        double budget = req == null ? 0 : req.getBudgetTotal();
        if (budget <= 0) {
            report.getSkippedChecks().add("预算校验(未识别数值预算)");
            return;
        }
        double sum = 0;
        int parsed = 0;
        if (plan.getSections() != null) {
            for (Section s : plan.getSections()) {
                if (s.getItems() == null) continue;
                for (SectionItem it : s.getItems()) {
                    Double c = parseMoney("timeline".equals(s.getType()) ? it.getCost() : it.getPrice());
                    if (c != null) {
                        sum += c;
                        parsed++;
                    }
                }
            }
        }
        if (parsed == 0) {
            report.getSkippedChecks().add("预算校验(行程中无可解析金额)");
            return;
        }
        if (sum > budget) {
            report.add(new ValidationReport.Issue("budget", "high", "overall",
                    "预算超支：明细估算 " + (long) sum + " 元 > 预算 " + (long) budget + " 元（按行程标注费用累加）"));
        } else if (sum > budget * 0.9) {
            report.add(new ValidationReport.Issue("budget", "medium", "overall",
                    "预算接近上限：明细估算 " + (long) sum + " 元 / 预算 " + (long) budget + " 元"));
        }
    }

    /** 解析金额文本为元（"免费"=0；"80元/人"=80；"1.5万"=15000）；无法解析返回 null。 */
    static Double parseMoney(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        if (s.contains("免费") || s.contains("无需") || s.contains("不含")) {
            return 0.0;
        }
        Matcher m = MONEY.matcher(s.replace("，", "").replace(",", ""));
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (m.group(2) != null && !m.group(2).isBlank()) {
                    v *= 10000;
                }
                if (v >= 0 && v <= 10_000_000) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    // ==================== ③ P0 必去 / 回避约束 ====================

    /** 必去对象未出现在行程 → high；P0/P1 回避对象出现 → high/medium。 */
    private static void checkConstraints(TripRequest req, TripPlanData plan, ValidationReport report) {
        if (req == null || req.getConstraints() == null || req.getConstraints().isEmpty()) {
            report.getSkippedChecks().add("约束校验(无明确约束)");
            return;
        }
        String text = planText(plan);
        if (text.isBlank()) {
            report.getSkippedChecks().add("约束校验(行程无文本)");
            return;
        }
        for (TripRequest.Constraint c : req.getConstraints()) {
            String t = c.getText() == null ? "" : c.getText().trim();
            String must = stripPrefix(t, MUST_PREFIXES);
            if (must != null && !must.isBlank()) {
                if (!text.contains(must)) {
                    report.add(new ValidationReport.Issue("constraint",
                            "P0".equalsIgnoreCase(c.getLevel()) ? "high" : "medium", "overall",
                            "必去「" + must + "」未出现在行程中（约束: " + t + "）"));
                }
                continue;
            }
            String avoid = stripPrefix(t, AVOID_PREFIXES);
            if (avoid != null && !avoid.isBlank() && text.contains(avoid)) {
                report.add(new ValidationReport.Issue("constraint",
                        "P0".equalsIgnoreCase(c.getLevel()) ? "high" : "medium", "overall",
                        "行程包含应回避的「" + avoid + "」（约束: " + t + "）"));
            }
        }
    }

    /** 去掉前缀取对象名（仅当前缀在开头时），无匹配返回 null。 */
    static String stripPrefix(String text, String[] prefixes) {
        for (String p : prefixes) {
            if (text.startsWith(p) && text.length() > p.length()) {
                return text.substring(p.length());
            }
        }
        return null;
    }

    // ==================== ④ 时间顺序 / 重复景点 / 交通时长 ====================

    private static void checkTimeline(TripPlanData plan, ValidationReport report) {
        if (plan.getSections() == null) {
            report.getSkippedChecks().add("时间顺序/重复景点(无区块)");
            return;
        }
        Set<String> seenAcrossDays = new HashSet<>();
        for (Section s : plan.getSections()) {
            if (!"timeline".equals(s.getType()) || s.getItems() == null || s.getItems().isEmpty()) {
                continue;
            }
            String day = s.getTitle() == null || s.getTitle().isBlank() ? "timeline" : s.getTitle();
            // 每日内时间顺序：解析成功的时间应非递减
            int prev = -1;
            boolean prevValid = false;
            Set<String> seenInDay = new HashSet<>();
            boolean anyTime = false;
            for (SectionItem it : s.getItems()) {
                int cur = parseMinutesOfDay(it.getTime());
                if (cur >= 0) {
                    anyTime = true;
                    if (prevValid && cur < prev) {
                        report.add(new ValidationReport.Issue("schedule", "medium", day,
                                day + " 时间倒序：" + it.getTime() + " 早于上一项"));
                    }
                    prev = cur;
                    prevValid = true;
                }
                String place = it.getPlace() == null ? "" : it.getPlace().trim();
                // 仅"景点"类条目计重复：交通(到达地)/餐饮(餐厅在古镇内)等条目的 place 是位置不是游览安排，不算重复
                boolean attraction = it.getCategory() == null || it.getCategory().isBlank()
                        || "景点".equals(it.getCategory()) || "attraction".equalsIgnoreCase(it.getCategory());
                if (!place.isBlank() && attraction && !isGenericPlace(place)) {
                    if (!seenInDay.add(place)) {
                        report.add(new ValidationReport.Issue("duplicate", "medium", day,
                                day + " 重复安排「" + place + "」"));
                    } else if (!seenAcrossDays.add(place)) {
                        report.add(new ValidationReport.Issue("duplicate", "low", day,
                                "「" + place + "」在多个天重复出现"));
                    }
                }
            }
            if (!anyTime) {
                report.getSkippedChecks().add("时间顺序(" + day + " 无时间字段)");
            }
        }
    }

    /** 泛化安排词不计重复（自由活动/用餐/休息），仅按关键词判断以免误伤真实短地名 */
    private static boolean isGenericPlace(String place) {
        return place.contains("自由") || place.contains("午餐") || place.contains("晚餐")
                || place.contains("早餐") || place.contains("休息") || place.contains("返程")
                || place.contains("出发") || place.contains("到达");
    }

    private static void checkTransport(TripPlanData plan, ValidationReport report) {
        if (plan.getSections() == null) {
            report.getSkippedChecks().add("交通时长(无区块)");
            return;
        }
        boolean any = false;
        for (Section s : plan.getSections()) {
            if (!"transport".equals(s.getType()) || s.getItems() == null) continue;
            for (SectionItem it : s.getItems()) {
                int min = parseDurationMinutes(it.getDuration());
                if (min >= 0) {
                    any = true;
                    if (min > MAX_LEG_MINUTES) {
                        report.add(new ValidationReport.Issue("transport", "medium",
                                (it.getFrom() == null ? "" : it.getFrom()) + "→" + (it.getTo() == null ? "" : it.getTo()),
                                "单段交通 " + it.getDuration() + " 超过 " + (MAX_LEG_MINUTES / 60) + " 小时，建议拆分或复核"));
                    }
                }
            }
        }
        if (!any) {
            report.getSkippedChecks().add("交通时长(无可解析时长)");
        }
    }

    // ==================== ⑤ 营业时间（候选数据驱动） ====================

    /** 开放时间段形如 "08:30-18:00" / "8:00～17:30"（兼容全角波浪线与"至"，括号备注忽略） */
    private static final Pattern OPEN_RANGE = Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*[-–~～至]\\s*(\\d{1,2}):(\\d{2})");

    /** 营业时间检查：timeline 条目时间落在候选景点/餐厅开放时间之外 → medium。
     *  数据缺失（无候选/无 openTime/无时间字段）逐项跳过。 */
    private static void checkOpeningHours(TripPlanData plan, java.util.Map<String, Object> candidates,
                                          ValidationReport report) {
        if (plan.getSections() == null) {
            report.getSkippedChecks().add("营业时间(无区块)");
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            report.getSkippedChecks().add("营业时间(无结构化候选)");
            return;
        }
        // 名称 → 开放时间段（景点 + 餐饮两个域）
        java.util.Map<String, String> openMap = new java.util.HashMap<>();
        for (String domain : new String[]{"attraction", "dining"}) {
            for (java.util.Map<String, Object> it : CandidateUtil.items(candidates, domain)) {
                String name = str(it.get("name"));
                String open = str(it.get("openTime"));
                if (!name.isBlank() && OPEN_RANGE.matcher(open).find()) {
                    openMap.put(name, open);
                }
            }
        }
        if (openMap.isEmpty()) {
            report.getSkippedChecks().add("营业时间(候选无营业时间数据)");
            return;
        }
        int checked = 0;
        for (Section s : plan.getSections()) {
            if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;
            for (SectionItem it : s.getItems()) {
                String place = it.getPlace() == null ? "" : it.getPlace().trim();
                String open = lookupOpen(openMap, place);
                if (open == null) continue;
                int minutes = parseMinutesOfDay(it.getTime());
                if (minutes < 0) continue;
                java.util.regex.Matcher m = OPEN_RANGE.matcher(open);
                if (!m.find()) continue;
                int from = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                int to = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
                checked++;
                // 结束时间越过 24:00（如酒吧 10:00-26:00）按次日处理不判违规
                if (to <= from) continue;
                if (minutes < from - 15 || minutes > to + 15) { // 15分钟缓冲防边界误报
                    report.add(new ValidationReport.Issue("opening", "medium", s.getTitle(),
                            s.getTitle() + " " + it.getTime() + " 安排「" + place
                                    + "」，疑似超出营业时间（" + open + "），建议复核"));
                }
            }
        }
        if (checked == 0) {
            report.getSkippedChecks().add("营业时间(行程条目无可匹配候选)");
        }
    }

    /** 地点匹配营业时间：全等优先，其次包含（"束河古镇·四方街咖啡馆" 匹配 "四方街咖啡馆"） */
    private static String lookupOpen(java.util.Map<String, String> openMap, String place) {
        if (place.isBlank()) return null;
        String hit = openMap.get(place);
        if (hit != null) return hit;
        for (java.util.Map.Entry<String, String> en : openMap.entrySet()) {
            if (place.contains(en.getKey()) || en.getKey().contains(place)) {
                return en.getValue();
            }
        }
        return null;
    }

    // ==================== ⑥ 酒店位置（候选数据驱动） ====================

    /** 酒店位置检查：酒店候选城市与目的地不符 → medium；酒店区域与全部每日活动地点零重合 → low。
     *  说明：无地理编码距离数据，位置合理性以城市一致性与区域文本重合度近似（数据缺失分级跳过）。 */
    private static void checkHotelLocation(TripRequest req, TripPlanData plan,
                                           java.util.Map<String, Object> candidates, ValidationReport report) {
        if (candidates == null || candidates.isEmpty()) {
            report.getSkippedChecks().add("酒店位置(无结构化候选)");
            return;
        }
        java.util.List<java.util.Map<String, Object>> hotels = CandidateUtil.items(candidates, "hotel");
        if (hotels.isEmpty()) {
            report.getSkippedChecks().add("酒店位置(无酒店候选)");
            return;
        }
        String dest = req == null || req.getDestination() == null ? "" : req.getDestination().trim();
        // ① 城市一致性：候选 city 与目的地互不包含 → 数据可能错配
        int cityMismatch = 0;
        int withCity = 0;
        for (java.util.Map<String, Object> h : hotels) {
            String city = str(h.get("city")).trim();
            if (city.isBlank()) continue;
            withCity++;
            if (!dest.isBlank() && !city.contains(dest) && !dest.contains(city)) {
                cityMismatch++;
            }
        }
        if (withCity > 0 && cityMismatch == withCity && !dest.isBlank()) {
            report.add(new ValidationReport.Issue("hotel", "medium", "hotel",
                    "酒店候选城市与目的地「" + dest + "」不一致，数据疑似错配，建议复核"));
        }
        // ② 区域重合度：酒店区域与每日活动地点存在文本重合（双向包含）才算"位置顺"；全无重合 → low 提示
        java.util.Set<String> hotelAreas = new java.util.HashSet<>();
        for (java.util.Map<String, Object> h : hotels) {
            String area = str(h.get("area")).trim();
            if (!area.isBlank()) hotelAreas.add(area);
        }
        if (hotelAreas.isEmpty()) {
            report.getSkippedChecks().add("酒店位置(候选无区域数据)");
            return;
        }
        java.util.List<String> places = activityPlaces(plan);
        if (places.isEmpty()) {
            report.getSkippedChecks().add("酒店位置(行程无活动地点)");
            return;
        }
        for (String area : hotelAreas) {
            for (String pl : places) {
                if (pl.contains(area) || area.contains(pl)) {
                    return; // 任一酒店区域与活动地点重合即认为位置合理
                }
            }
        }
        report.add(new ValidationReport.Issue("hotel", "low", "hotel",
                "酒店区域（" + String.join("、", hotelAreas) + "）与每日活动地点缺乏重合，通勤时间可能偏长，建议关注"));
    }

    /** 每日 timeline 地点列表（酒店位置重合度检查用） */
    private static java.util.List<String> activityPlaces(TripPlanData plan) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (plan.getSections() != null) {
            for (Section s : plan.getSections()) {
                if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;
                for (SectionItem it : s.getItems()) {
                    String p = nz(it.getPlace()).trim();
                    if (!p.isBlank()) out.add(p);
                }
            }
        }
        return out;
    }

    // ==================== 解析工具 ====================

    /** "09:00" → 540；无法解析返回 -1 */
    static int parseMinutesOfDay(String time) {
        if (time == null) return -1;
        Matcher m = HHMM.matcher(time.trim());
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h >= 0 && h <= 23 && min >= 0 && min <= 59) {
                return h * 60 + min;
            }
        }
        return -1;
    }

    /** "2小时30分"→150；"2.5小时"→150；"90分钟"→90；"3h"→180；无法解析返回 -1 */
    static int parseDurationMinutes(String duration) {
        if (duration == null || duration.isBlank()) return -1;
        String s = duration.trim();
        Matcher m = DUR_HM.matcher(s);
        if (m.find()) {
            try {
                double h = Double.parseDouble(m.group(1));
                int extra = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                return (int) Math.round(h * 60) + extra;
            } catch (NumberFormatException ignored) {
            }
        }
        m = DUR_MIN.matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        m = DUR_HOUR_ONLY.matcher(s);
        if (m.find()) {
            try {
                return (int) Math.round(Double.parseDouble(m.group(1)) * 60);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** 行程可检索文本：标题+目的地+全部 items 关键字段（不含 markdown 冗余） */
    private static String planText(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.getTitle() == null ? "" : plan.getTitle()).append(' ');
        if (plan.getSections() != null) {
            for (Section s : plan.getSections()) {
                if (s.getItems() == null) continue;
                for (SectionItem it : s.getItems()) {
                    sb.append(nz(it.getPlace())).append(' ')
                            .append(nz(it.getName())).append(' ')
                            .append(nz(it.getActivity())).append(' ')
                            .append(nz(it.getNote())).append(' ');
                }
            }
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
