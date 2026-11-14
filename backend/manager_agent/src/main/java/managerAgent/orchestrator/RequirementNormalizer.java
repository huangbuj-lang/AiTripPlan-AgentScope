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
public final class RequirementNormalizer {  // 类声明：需求归一化（把 LLM 解析结果与原话再对齐一次）

    /** 预算表达：预算/花费/控制在/不超过…(数字)(万|千|k|K)?(元|块)? */
    private static final Pattern BUDGET = Pattern.compile(  // BUDGET：预算正则（预算/花费/控制在/不超过 + 数字 + 单位）
            "(?:预算|花费|控制在|不超过)[^\\d0-9]{0,8}(\\d+(?:\\.\\d+)?)\\s*(万|千|[kK])?");  // 续行

    /** 必去类硬约束：必去/一定要去/必须去 + 对象名 */
    private static final Pattern MUST_GO = Pattern.compile("(?:必去|一定要去|必须去)([\\u4e00-\\u9fa5A-Za-z0-9]{2,10})");  // MUST_GO：必去地点正则

    /** 回避类约束：不去/不要去/不想去/避开/别去 + 对象名 */
    private static final Pattern AVOID = Pattern.compile("(?:不去|不要去|不想去|避开|别去)([\\u4e00-\\u9fa5A-Za-z0-9]{2,10})");  // AVOID：要避开的正则

    /** 同行人数：数字/中文数字 + 人（如 2人 / 两个人 / 4大1小取4） */
    private static final Pattern TRAVELERS = Pattern.compile(  // TRAVELERS：同行人数正则（阿拉伯或中文数字 + 人）
            "(\\d+|[一二两三四五六七八九十])\\s*(?:个|位)?(?:大?人|成人|大人)");  // 续行

    /** 出行日期：2026年9月10日 / 2026-9-10 / 2026.9.10 / 9月10日（无年份补当年，早于今天则进位一年） */
    private static final Pattern DATE_CN = Pattern.compile(  // DATE_CN：中文日期正则（可带年，月/日）
            "(?:(\\d{4})\\s*[年\\-/.]\\s*)?(\\d{1,2})\\s*[月\\-/.]\\s*(\\d{1,2})\\s*[日号]?");  // 续行

    /** 旅行类型关键词 */
    private static final Pattern LOOP_KW = Pattern.compile("环线|环游|大环线|小环线");  // LOOP_KW：环线关键词
    private static final Pattern DRIVE_KW = Pattern.compile("自驾|开车|包车|租车|房车");  // DRIVE_KW：自驾关键词
    private static final Pattern CROSS_KW = Pattern.compile("[到→经]");  // CROSS_KW：跨城连接词（到/→/经）
    /** ★大区域词（2026-09-06）：目的地为跨县大区域时 LLM 常误判"单城市游"（川西/甘南=区域环线）——
     *  命中即按区域环线处理，保证域专家候选覆盖全区、骨架按"每天1大景区"排。 */
    private static final Pattern AREA_KW = Pattern.compile(  // AREA_KW：大区域关键词（川西/甘南/新疆…）
            "川西|甘南|青甘|甘青|新疆|北疆|南疆|西藏|滇西|滇西北|大西北|呼伦贝尔|坝上|阿坝|甘孜|川藏|青藏|河西走廊|黔东南|恩施|神农架|伊犁");  // 续：这些词出现说明是区域游而非单城市

    /** 天气敏感关键词（户外/摄影类需求，行程需重点关注天气） */
    private static final Pattern WEATHER_KW = Pattern.compile(  // WEATHER_KW：天气敏感活动关键词
            "徒步|登山|露营|户外|滑雪|潜水|漂流|骑行|摄影|拍照|出片|星空|日出|日落|雨季|台风");  // 续行

    /** 同行人类别关键词 */
    private static final Pattern COUPLE_KW = Pattern.compile("情侣|夫妻|蜜月| honeymoon", Pattern.CASE_INSENSITIVE);  // COUPLE_KW：情侣类
    private static final Pattern FAMILY_KW = Pattern.compile("亲子|带娃|带孩子|孩子|小孩|儿童|家庭|娃");  // FAMILY_KW：亲子类
    private static final Pattern ELDER_KW = Pattern.compile("父母|老人|爸妈|爷爷|奶奶|外公|外婆");  // ELDER_KW：带老人
    private static final Pattern FRIENDS_KW = Pattern.compile("朋友|闺蜜|兄弟|同学|同事");  // FRIENDS_KW：朋友同行
    private static final Pattern SOLO_KW = Pattern.compile("一个人|独自|单人");  // SOLO_KW：单人
    private static final Pattern BUSINESS_KW = Pattern.compile("商务|出差|团建");  // BUSINESS_KW：商务/团建

    /** 约束条数上限（防极端长文撑爆提示词） */
    private static final int MAX_CONSTRAINTS = 8;  // MAX_CONSTRAINTS：约束最多保留 8 条（防提示词膨胀）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private RequirementNormalizer() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：需求归一化总入口：依次校正出行方式、人群、日期、天数、目的地与地点写法
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    public static void normalize(TripRequest req) {  // normalize：主入口，就地补全需求字段
        if (req == null) {  // 需求为空
            return;  // 直接返回
        }  // if 结束
        if (req.getConstraints() == null) {  // 约束列表为空
            req.setConstraints(new ArrayList<>());  // 先建空列表
        }  // if 结束
        if (req.getBudgetTotal() <= 0) {  // 预算没解析出来
            double b = parseBudgetYuan(req.getRaw());  // 从原话里正则抠预算
            if (b > 0) {  // 抠到了
                req.setBudgetTotal(b);  // 写回需求
            }  // if 结束
        }  // if 结束
        normalizeTravelType(req);  // 归一出行类型
        normalizeTravelers(req);  // 归一同行人数与人群
        normalizeDate(req);  // 归一日期
        normalizeDays(req);  // 归一天数
        normalizeDestination(req);  // 归一目的地（去掉“3日”这类尾巴）
        normalizePlace(req); // ★2026-09-11 单城市兜底：目的地为空时用出发地补（防"成都3天自驾游"解析成"成都 → "）  // 单城市兜底：目的地为空时用出发地补
        if (!req.isWeatherSensitive() && req.getRaw() != null) {  // 没标天气敏感且有原话
            req.setWeatherSensitive(WEATHER_KW.matcher(req.getRaw()).find());  // 按关键词判断天气敏感
        }  // if 结束
        Map<String, String> uniq = new LinkedHashMap<>();  // uniq：约束去重表（文本 → 优先级）
        for (TripRequest.Constraint c : req.getConstraints()) {  // 遍历已有约束
            if (c == null || c.getText() == null || c.getText().isBlank()) {  // 空约束跳过
                continue;  // continue
            }  // if 结束
            uniq.putIfAbsent(c.getText().trim(), validLevel(c.getLevel()));  // 按文本去重（保留首个优先级）
        }  // for 结束
        String raw = req.getRaw() == null ? "" : req.getRaw();  // raw：用户原话
        if (req.getBudgetTotal() > 0) {  // 有预算
            uniq.putIfAbsent("预算不超过" + (long) req.getBudgetTotal() + "元", "P0");  // 补一条 P0 预算约束
        }  // if 结束
        Matcher m = MUST_GO.matcher(raw);  // 匹配“必去X”
        while (m.find()) {  // 逐个命中
            uniq.putIfAbsent("必去" + m.group(1), "P0");  // 补 P0 必去约束
        }  // while 结束
        m = AVOID.matcher(raw);  // 匹配“不去X”
        while (m.find()) {  // 逐个命中
            uniq.putIfAbsent("避免" + m.group(1), "P1");  // 补 P1 避开约束
        }  // while 结束
        req.setConstraints(new ArrayList<>());  // 清空原约束列表（下面重新装）
        int n = 0;  // n：已装条数
        for (Map.Entry<String, String> e : uniq.entrySet()) {  // 遍历去重后的约束
            if (n++ >= MAX_CONSTRAINTS) {  // 超过上限
                break;  // 跳出
            }  // if 结束
            req.getConstraints().add(new TripRequest.Constraint(e.getKey(), e.getValue()));  // 装入约束对象
        }  // for 结束
    }  // 方法结束

    /**
     * 作用：解析预算文本为元（支持"3000""3千""1.5万"等写法）
     * 参数：raw 预算原文
     * 返回值：预算金额（元）；无法解析返回 0
     * 异常：无
     */
    public static double parseBudgetYuan(String raw) {  // parseBudgetYuan：从原话解析预算金额（元）
        if (raw == null || raw.isBlank()) {  // 原话为空
            return 0;  // 返回 0
        }  // if 结束
        Matcher m = BUDGET.matcher(raw);  // 匹配预算正则
        while (m.find()) {  // 逐个命中
            double v;  // v：金额
            try {  // try
                v = Double.parseDouble(m.group(1));  // 解析数字
            } catch (NumberFormatException e) {  // 捕获异常
                continue;  // 解析失败跳过
            }  // catch 结束
            String unit = m.group(2);  // unit：单位（万/千/k）
            if (unit != null) {  // 有单位
                if (unit.equalsIgnoreCase("k")) {  // 是 k
                    v *= 1000;  // 乘 1000
                } else if (unit.equals("万")) {  // 是“万”
                    v *= 10000;  // 乘 10000
                } else if (unit.equals("千")) {  // 是“千”
                    v *= 1000;  // 乘 1000
                }  // if 结束
            }  // if 结束
            if (v >= 100 && v <= 10_000_000) {  // 只认 100 元 ~ 1000 万这个区间
                return v;  // 返回金额
            }  // if 结束
        }  // while 结束
        return 0;  // 没匹配到返回 0
    }  // 方法结束

    /**
     * 作用：把约束级别规范成合法值（硬性/软性）
     * 参数：level 原始级别文本
     * 返回值：规范级别；无法识别返回原值
     * 异常：无
     */
    private static String validLevel(String level) {  // validLevel：约束优先级归一
        if (level == null) {  // 为空
            return "P2";  // 默认 P2
        }  // if 结束
        return switch (level.trim().toUpperCase()) {  // 按大写判断
            case "P0", "P1", "P2", "P3" -> level.trim().toUpperCase();  // 合法值原样返回
            default -> "P2";  // 其它一律 P2
        };  // switch 结束
    }  // 方法结束

    // ==================== 旅行类型识别（PDF 补充篇：单城市/跨城路线/环线/自驾） ====================

    /**
     * 作用：校正出行方式（自驾/高铁/飞机等别名归一）
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    static void normalizeTravelType(TripRequest req) {  // normalizeTravelType：识别出行类型
        if (req == null) return;  // 需求为空返回
        String raw = req.getRaw() == null ? "" : req.getRaw();  // raw：原话
        String dest = nz(req.getDestination());  // dest：目的地
        boolean area = AREA_KW.matcher(raw).find() || AREA_KW.matcher(dest).find();  // 是否命中大区域关键词（原话或目的地）
        boolean loop = LOOP_KW.matcher(raw).find();  // 是否含环线词
        boolean drive = DRIVE_KW.matcher(raw).find();  // 是否含自驾词
        if (area || loop || drive) {  // 命中任一
            // 大区域/环线/自驾词：LLM 的 SINGLE_CITY 判定作废（川西=区域环线，非单城市）
            if (loop && drive) req.setTravelType("SELF_DRIVE");  // 环线 + 自驾 → 自驾
            else if (drive && !loop) req.setTravelType("SELF_DRIVE");  // 只自驾 → 自驾
            else req.setTravelType("LOOP");  // 否则 → 环线
            return;  // 直接返回（不再看 LLM 判定）
        }  // if 结束
        if (validType(req.getTravelType())) {  // LLM 给的类型合法
            return; // LLM 值合法，直接采信  // 直接采信
        }  // if 结束
        String origin = nz(req.getOrigin());  // origin：出发地
        boolean crossCity = !origin.isBlank() && !dest.isBlank() && !origin.trim().equals(dest.trim());  // 出发地目的地不同 = 跨城
        if (crossCity) {  // 跨城
            req.setTravelType("CROSS_CITY");  // 设为 CROSS_CITY
        } else {  // 否则
            req.setTravelType("SINGLE_CITY");  // 设为 SINGLE_CITY
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：校正出行人群（亲子/老人/情侣等别名归一）
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    static void normalizeTravelers(TripRequest req) {  // normalizeTravelers：归一同行人数与人群
        if (req.getTravelers() > 0 && req.getTravelerType() != null && !req.getTravelerType().isBlank()) {  // 人数与人群都已有值
            return;  // 直接返回
        }  // if 结束
        String raw = req.getRaw() == null ? "" : req.getRaw();  // raw：原话
        if (req.getTravelers() <= 0) {  // 人数还没解析
            Matcher m = TRAVELERS.matcher(raw);  // 匹配人数正则
            if (m.find()) {  // 命中
                req.setTravelers(cnToInt(m.group(1)));  // 中文数字转 int 后写入
            }  // if 结束
        }  // if 结束
        if (req.getTravelerType() == null || req.getTravelerType().isBlank()) {  // 人群类型为空
            if (FAMILY_KW.matcher(raw).find()) req.setTravelerType("family");  // 命中亲子词 → family
            else if (ELDER_KW.matcher(raw).find()) req.setTravelerType("elder");  // 命中老人词 → elder
            else if (COUPLE_KW.matcher(raw).find()) req.setTravelerType("couple");  // 命中情侣词 → couple
            else if (BUSINESS_KW.matcher(raw).find()) req.setTravelerType("business");  // 命中商务词 → business
            else if (SOLO_KW.matcher(raw).find()) req.setTravelerType("solo");  // 命中单人词 → solo
            else if (FRIENDS_KW.matcher(raw).find()) req.setTravelerType("friends");  // 命中朋友词 → friends
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：校正出发日期（相对日期转绝对日期、非法日期清空）
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    static void normalizeDate(TripRequest req) {  // normalizeDate：归一出行日期
        String d = req.getDate() == null ? "" : req.getDate().trim();  // d：当前日期值
        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {  // 已是 yyyy-MM-dd
            return; // 已是标准格式  // 直接返回
        }  // if 结束
        String raw = req.getRaw() == null ? "" : req.getRaw();  // raw：原话
        Matcher m = DATE_CN.matcher(raw);  // 匹配中文日期
        if (m.find()) {  // 命中
            try {  // try
                int year = m.group(1) != null ? Integer.parseInt(m.group(1)) : java.time.LocalDate.now().getYear();  // 年（没写就用今年）
                int month = Integer.parseInt(m.group(2));  // 月
                int day = Integer.parseInt(m.group(3));  // 日
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {  // 月日范围合法
                    java.time.LocalDate date = java.time.LocalDate.of(year, month, day);  // 构造日期对象
                    // 无年份且日期早于今天 → 顺延一年（用户说的"9月10日"大概率指下一个）
                    if (m.group(1) == null && date.isBefore(java.time.LocalDate.now())) {  // 没写年且已过去
                        date = date.plusYears(1);  // 顺延一年（用户说的是下一个 9 月 10 日）
                    }  // if 结束
                    req.setDate(date.toString());  // 写回标准格式
                }  // if 结束
            } catch (Exception ignored) {  // 捕获异常
                // 非法日期保留 LLM 原值
            }  // catch 结束
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：校正行程天数（中文数字转阿拉伯数字、缺失时按日期区间推算、夹紧合法范围）
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    static void normalizeDays(TripRequest req) {  // normalizeDays：归一行程天数
        String raw = req.getRaw();  // raw：原话
        if (raw == null || raw.isBlank()) return;  // 空则返回
        Matcher m = Pattern.compile("([0-9一二两三四五六七八九十]{1,2})\\s*(天|日)\\s*(游|行程|旅行|深度|的旅行)?").matcher(raw);  // 匹配“N天/日 游”
        int best = -1;  // best：最好的天数，-1=未找到
        while (m.find()) {  // 逐个命中
            int v = cnToInt(m.group(1));  // 中文数字转 int
            if (v >= 1 && v <= 30) best = v;  // 1~30 才采纳（取最后一次出现的）
        }  // while 结束
        if (best > 0 && best != req.getDays()) req.setDays(best);  // 有效且与原值不同才写回
    }  // 方法结束

    /**
     * 作用：校正目的地写法（补省市、去括号说明）
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    static void normalizeDestination(TripRequest req) {  // normalizeDestination：去掉目的地尾巴
        String dest = req.getDestination();  // dest：当前目的地
        if (dest == null) return;  // 为空返回
        String t = dest.trim().replaceAll("[0-9一二两三四五六七八九十]+\\s*[日天]$", "").trim();  // 去掉结尾的“3日/三天”这类天数尾巴
        if (t.length() >= 2 && !t.equals(dest)) req.setDestination(t);  // 剩余至少 2 字且确实变了才写回
    }  // 方法结束

    /**
     * 作用：校正地点写法（去掉行程化的修饰词，保留可检索地名）
     * 参数：req 需求对象（原地修改）
     * 返回值：无
     * 异常：无
     */
    static void normalizePlace(TripRequest req) {  // normalizePlace：出发地/目的地互补
        String origin = nz(req.getOrigin());  // origin：出发地
        String dest = nz(req.getDestination());  // dest：目的地
        if (dest.isBlank() && !origin.isBlank()) {  // 目的地空、出发地有
            req.setDestination(origin.trim());  // 把出发地当目的地（单城市游）
        } else if (origin.isBlank() && !dest.isBlank()) {  // 反过来出发地空
            req.setOrigin(dest.trim());  // 把目的地当出发地
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：判断出行方式是否是合法枚举值
     * 参数：t 出行方式文本
     * 返回值：true=合法
     * 异常：无
     */
    private static boolean validType(String t) {  // validType：出行类型是否合法
        if (t == null) return false;  // 为空返回 false
        return switch (t.trim().toUpperCase()) {  // 按大写判断
            case "SINGLE_CITY", "CROSS_CITY", "LOOP", "SELF_DRIVE" -> true;  // 四种合法类型
            default -> false;  // 其它不算
        };  // switch 结束
    }  // 方法结束

    /**
     * 作用：中文数字转整数（一~十，含"两"）
     * 参数：s 中文数字文本
     * 返回值：整数值；无法转换返回 -1
     * 异常：无
     */
    private static int cnToInt(String s) {  // cnToInt：中文/阿拉伯数字转 int
        String t = s.trim();  // t：去空格
        if (t.matches("\\d+")) {  // 纯数字
            try {  // try
                int v = Integer.parseInt(t);  // 解析
                return (v >= 1 && v <= 50) ? v : 0;  // 1~50 才算有效
            } catch (NumberFormatException e) {  // 捕获异常
                return 0;  // 返回 0
            }  // catch 结束
        }  // if 结束
        String cn = "一二三四五六七八九十";  // cn：中文数字表
        if (t.equals("两")) return 2;  // “两”特殊处理为 2
        int idx = cn.indexOf(t);  // 查下标
        return idx >= 0 ? idx + 1 : 0;  // 下标 +1 即数值（找不到返回 0）
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
}  // 类结束
