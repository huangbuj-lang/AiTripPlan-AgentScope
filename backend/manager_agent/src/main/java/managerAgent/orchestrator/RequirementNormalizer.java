package managerAgent.orchestrator;

import dto.TripRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求规整器：LLM 解析结果的纯代码兜底补全（预算数值化 + P0~P3 约束提取），
 * LLM 漏解析时从原文正则补救，保证下游校验/评分拿到的状态字段完整。
 */
public final class RequirementNormalizer {

    /** 预算表达：预算/花费/控制在/不超过…(数字)(万|千|k|K)?(元|块)? */
    private static final Pattern BUDGET = Pattern.compile(
            "(?:预算|花费|控制在|不超过)[^\\d0-9]{0,8}(\\d+(?:\\.\\d+)?)\\s*(万|千|[kK])?");

    /** 必去类硬约束：必去/一定要去/必须去 + 对象名 */
    private static final Pattern MUST_GO = Pattern.compile("(?:必去|一定要去|必须去)([\\u4e00-\\u9fa5A-Za-z0-9]{2,10})");

    /** 回避类约束：不去/不要去/不想去/避开/别去 + 对象名 */
    private static final Pattern AVOID = Pattern.compile("(?:不去|不要去|不想去|避开|别去)([\\u4e00-\\u9fa5A-Za-z0-9]{2,10})");

    /** 同行人数：数字/中文数字 + 人（如 2人 / 两个人 / 4大1小取4） */
    private static final Pattern TRAVELERS = Pattern.compile(
            "(\\d+|[一二两三四五六七八九十])\\s*(?:个|位)?(?:大?人|成人|大人)");

    /** 出行日期：2026年9月10日 / 2026-9-10 / 2026.9.10 / 9月10日（无年份补当年，早于今天则进位一年） */
    private static final Pattern DATE_CN = Pattern.compile(
            "(?:(\\d{4})\\s*[年\\-/.]\\s*)?(\\d{1,2})\\s*[月\\-/.]\\s*(\\d{1,2})\\s*[日号]?");

    /** 旅行类型关键词 */
    private static final Pattern LOOP_KW = Pattern.compile("环线|环游|大环线|小环线");
    private static final Pattern DRIVE_KW = Pattern.compile("自驾|开车|包车|租车|房车");
    private static final Pattern CROSS_KW = Pattern.compile("[到→经]");
    /** ★大区域词（2026-09-06）：目的地为跨县大区域时 LLM 常误判"单城市游"（川西/甘南=区域环线）——
     *  命中即按区域环线处理，保证域专家候选覆盖全区、骨架按"每天1大景区"排。 */
    private static final Pattern AREA_KW = Pattern.compile(
            "川西|甘南|青甘|甘青|新疆|北疆|南疆|西藏|滇西|滇西北|大西北|呼伦贝尔|坝上|阿坝|甘孜|川藏|青藏|河西走廊|黔东南|恩施|神农架|伊犁");

    /** 天气敏感关键词（户外/摄影类需求，行程需重点关注天气） */
    private static final Pattern WEATHER_KW = Pattern.compile(
            "徒步|登山|露营|户外|滑雪|潜水|漂流|骑行|摄影|拍照|出片|星空|日出|日落|雨季|台风");

    /** 同行人类别关键词 */
    private static final Pattern COUPLE_KW = Pattern.compile("情侣|夫妻|蜜月| honeymoon", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAMILY_KW = Pattern.compile("亲子|带娃|带孩子|孩子|小孩|儿童|家庭|娃");
    private static final Pattern ELDER_KW = Pattern.compile("父母|老人|爸妈|爷爷|奶奶|外公|外婆");
    private static final Pattern FRIENDS_KW = Pattern.compile("朋友|闺蜜|兄弟|同学|同事");
    private static final Pattern SOLO_KW = Pattern.compile("一个人|独自|单人");
    private static final Pattern BUSINESS_KW = Pattern.compile("商务|出差|团建");

    /** 约束条数上限（防极端长文撑爆提示词） */
    private static final int MAX_CONSTRAINTS = 8;

    private RequirementNormalizer() {
    }

    /** 就地规整：预算数值化 + 旅行类型/同行人/日期/天气敏感补全 + 约束去重/层级校准。 */
    public static void normalize(TripRequest req) {
        if (req == null) {
            return;
        }
        if (req.getConstraints() == null) {
            req.setConstraints(new ArrayList<>());
        }
        if (req.getBudgetTotal() <= 0) {
            double b = parseBudgetYuan(req.getRaw());
            if (b > 0) {
                req.setBudgetTotal(b);
            }
        }
        normalizeTravelType(req);
        normalizeTravelers(req);
        normalizeDate(req);
        normalizeDays(req);
        normalizeDestination(req);
        if (!req.isWeatherSensitive() && req.getRaw() != null) {
            req.setWeatherSensitive(WEATHER_KW.matcher(req.getRaw()).find());
        }
        Map<String, String> uniq = new LinkedHashMap<>();
        for (TripRequest.Constraint c : req.getConstraints()) {
            if (c == null || c.getText() == null || c.getText().isBlank()) {
                continue;
            }
            uniq.putIfAbsent(c.getText().trim(), validLevel(c.getLevel()));
        }
        String raw = req.getRaw() == null ? "" : req.getRaw();
        if (req.getBudgetTotal() > 0) {
            uniq.putIfAbsent("预算不超过" + (long) req.getBudgetTotal() + "元", "P0");
        }
        Matcher m = MUST_GO.matcher(raw);
        while (m.find()) {
            uniq.putIfAbsent("必去" + m.group(1), "P0");
        }
        m = AVOID.matcher(raw);
        while (m.find()) {
            uniq.putIfAbsent("避免" + m.group(1), "P1");
        }
        req.setConstraints(new ArrayList<>());
        int n = 0;
        for (Map.Entry<String, String> e : uniq.entrySet()) {
            if (n++ >= MAX_CONSTRAINTS) {
                break;
            }
            req.getConstraints().add(new TripRequest.Constraint(e.getKey(), e.getValue()));
        }
    }

    /** 从原文提取预算总额（元）：8000 / 1万 / 8千 / 10k / 1.5万；不合理（<100 或 >1000万）返回 0。 */
    public static double parseBudgetYuan(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        Matcher m = BUDGET.matcher(raw);
        while (m.find()) {
            double v;
            try {
                v = Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String unit = m.group(2);
            if (unit != null) {
                if (unit.equalsIgnoreCase("k")) {
                    v *= 1000;
                } else if (unit.equals("万")) {
                    v *= 10000;
                } else if (unit.equals("千")) {
                    v *= 1000;
                }
            }
            if (v >= 100 && v <= 10_000_000) {
                return v;
            }
        }
        return 0;
    }

    /** 校准优先级：非法值归为 P2 */
    private static String validLevel(String level) {
        if (level == null) {
            return "P2";
        }
        return switch (level.trim().toUpperCase()) {
            case "P0", "P1", "P2", "P3" -> level.trim().toUpperCase();
            default -> "P2";
        };
    }

    // ==================== 旅行类型识别（PDF 补充篇：单城市/跨城路线/环线/自驾） ====================

    /** 旅行类型：★大区域词命中时直接区域环线/自驾（LLM 对"川西七日"误判单城市游）；否则 LLM 值合法保留；
     *  非法则按原文规则推导（环线/自驾 > 跨城 > 单城）。 */
    static void normalizeTravelType(TripRequest req) {
        if (req == null) return;
        String raw = req.getRaw() == null ? "" : req.getRaw();
        String dest = nz(req.getDestination());
        boolean area = AREA_KW.matcher(raw).find() || AREA_KW.matcher(dest).find();
        boolean loop = LOOP_KW.matcher(raw).find();
        boolean drive = DRIVE_KW.matcher(raw).find();
        if (area || loop || drive) {
            // 大区域/环线/自驾词：LLM 的 SINGLE_CITY 判定作废（川西=区域环线，非单城市）
            if (loop && drive) req.setTravelType("SELF_DRIVE");
            else if (drive && !loop) req.setTravelType("SELF_DRIVE");
            else req.setTravelType("LOOP");
            return;
        }
        if (validType(req.getTravelType())) {
            return; // LLM 值合法，直接采信
        }
        String origin = nz(req.getOrigin());
        boolean crossCity = !origin.isBlank() && !dest.isBlank() && !origin.trim().equals(dest.trim());
        if (crossCity) {
            req.setTravelType("CROSS_CITY");
        } else {
            req.setTravelType("SINGLE_CITY");
        }
    }

    /** 同行人：LLM 漏解析时正则补人数与类别（情侣/亲子/老人/朋友/独自/商务）。 */
    static void normalizeTravelers(TripRequest req) {
        if (req.getTravelers() > 0 && req.getTravelerType() != null && !req.getTravelerType().isBlank()) {
            return;
        }
        String raw = req.getRaw() == null ? "" : req.getRaw();
        if (req.getTravelers() <= 0) {
            Matcher m = TRAVELERS.matcher(raw);
            if (m.find()) {
                req.setTravelers(cnToInt(m.group(1)));
            }
        }
        if (req.getTravelerType() == null || req.getTravelerType().isBlank()) {
            if (FAMILY_KW.matcher(raw).find()) req.setTravelerType("family");
            else if (ELDER_KW.matcher(raw).find()) req.setTravelerType("elder");
            else if (COUPLE_KW.matcher(raw).find()) req.setTravelerType("couple");
            else if (BUSINESS_KW.matcher(raw).find()) req.setTravelerType("business");
            else if (SOLO_KW.matcher(raw).find()) req.setTravelerType("solo");
            else if (FRIENDS_KW.matcher(raw).find()) req.setTravelerType("friends");
        }
    }

    /** 出行日期规范化：LLM 输出统一为 ISO（YYYY-MM-DD）；中文/缺年格式由正则兜底补全。 */
    static void normalizeDate(TripRequest req) {
        String d = req.getDate() == null ? "" : req.getDate().trim();
        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return; // 已是标准格式
        }
        String raw = req.getRaw() == null ? "" : req.getRaw();
        Matcher m = DATE_CN.matcher(raw);
        if (m.find()) {
            try {
                int year = m.group(1) != null ? Integer.parseInt(m.group(1)) : java.time.LocalDate.now().getYear();
                int month = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    java.time.LocalDate date = java.time.LocalDate.of(year, month, day);
                    // 无年份且日期早于今天 → 顺延一年（用户说的"9月10日"大概率指下一个）
                    if (m.group(1) == null && date.isBefore(java.time.LocalDate.now())) {
                        date = date.plusYears(1);
                    }
                    req.setDate(date.toString());
                }
            } catch (Exception ignored) {
                // 非法日期保留 LLM 原值
            }
        }
    }

    /** 天数兜底（2026-09-06）：需求解析 LLM 偶发把"N日游"解析错（如川西七日游→1天）——
     *  raw 含明确的 N天/N日（含中文数字）时以原文为准覆盖。取最后一个合法匹配（"3天2晚"取3）。 */
    static void normalizeDays(TripRequest req) {
        String raw = req.getRaw();
        if (raw == null || raw.isBlank()) return;
        Matcher m = Pattern.compile("([0-9一二两三四五六七八九十]{1,2})\\s*(天|日)\\s*(游|行程|旅行|深度|的旅行)?").matcher(raw);
        int best = -1;
        while (m.find()) {
            int v = cnToInt(m.group(1));
            if (v >= 1 && v <= 30) best = v;
        }
        if (best > 0 && best != req.getDays()) req.setDays(best);
    }

    /** 目的地纠偏（2026-09-06）：LLM 偶发把"N日/天"并入目的地（"川西七日"→目的地="川西七日"）——截掉尾部数量日/天。 */
    static void normalizeDestination(TripRequest req) {
        String dest = req.getDestination();
        if (dest == null) return;
        String t = dest.trim().replaceAll("[0-9一二两三四五六七八九十]+\\s*[日天]$", "").trim();
        if (t.length() >= 2 && !t.equals(dest)) req.setDestination(t);
    }

    /** 旅行类型值合法性 */
    private static boolean validType(String t) {
        if (t == null) return false;
        return switch (t.trim().toUpperCase()) {
            case "SINGLE_CITY", "CROSS_CITY", "LOOP", "SELF_DRIVE" -> true;
            default -> false;
        };
    }

    /** 中文数字/阿拉伯数字 → int（一~十；超出范围返回 0） */
    private static int cnToInt(String s) {
        String t = s.trim();
        if (t.matches("\\d+")) {
            try {
                int v = Integer.parseInt(t);
                return (v >= 1 && v <= 50) ? v : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        String cn = "一二三四五六七八九十";
        if (t.equals("两")) return 2;
        int idx = cn.indexOf(t);
        return idx >= 0 ? idx + 1 : 0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
