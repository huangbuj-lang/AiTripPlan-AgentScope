package utils;

import dto.TripRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可行性预检（纯函数，无副作用）。
 *
 * <p>用途：路线里程 ÷ 日均里程 → 所需天数；若超过用户要求的天数，说明"物理上跑不完"，
 * 如实标注（**不中止**，交上层决定是否走兜底/替代路线）。
 *
 * <p>铁律：<b>取不到里程就不下结论</b>（返回空串），绝不猜一个数字出来吓人。
 * <p>日均口径（用户已定）：跨城自驾 <b>450 km/日</b>，市内/周边 300 km/日。
 * 450 与"单日车程上限 6h"自洽：450 ÷ 70km/h ≈ 6.4h ≈ 6h，两边对得上，不是孤立拍数。
 */
public final class RouteFeasibility {

    /** 跨城自驾日均里程（km/日）——口径已定 */
    public static final int DAILY_CROSS_CITY_KM = 450;
    /** 市内/周边日均里程（km/日） */
    public static final int DAILY_IN_CITY_KM = 300;
    /** 单日车程估算时速（km/h），与 PlanValidator.EST_KMH 同一口径，兜底用 */
    public static final int EST_KMH = 70;

    /** 显式总里程：总里程/全程/合计 + 数字 + 公里|km */
    private static final Pattern TOTAL_PAT = Pattern.compile(
            "(?:总里程|全程|总计|合计|往返)[^0-9]{0,6}(\\d{2,5}(?:\\.\\d+)?)\\s*(?:公里|千米|km|KM)");
    /** ★直达基准里程：不绕路/直达/最省路线 + 数字 + 公里|km（A11 走向合理性判定的分母） */
    private static final Pattern DIRECT_PAT = Pattern.compile(
            "(?:直达|不绕路|不走回头路|最省|最短|基准)[^0-9]{0,10}(\\d{2,5}(?:\\.\\d+)?)\\s*(?:公里|千米|km|KM)");
    /** 任意里程：数字 + 公里|km */
    private static final Pattern ANY_PAT = Pattern.compile("(\\d{2,5}(?:\\.\\d+)?)\\s*(?:公里|千米|km|KM)");

    private RouteFeasibility() {}

    /**
     * 从路线文本中提取总里程（km）。
     *
     * @return 里程；无法确定时返回 null（上层据此"不下结论"）
     */
    public static Integer totalKm(String routeText) {
        if (routeText == null || routeText.isBlank()) return null;
        Matcher t = TOTAL_PAT.matcher(routeText);
        if (t.find()) {
            Integer v = parse(t.group(1));
            if (v != null && v > 0) return v;
        }
        // 没有显式总里程：取出现的最大值（通常即主干里程），避免把分段累加重复计算
        Matcher a = ANY_PAT.matcher(routeText);
        Integer max = null;
        while (a.find()) {
            Integer v = parse(a.group(1));
            if (v != null && v > 0 && (max == null || v > max)) max = v;
        }
        return max;
    }

    /**
     * 从路线文本中提取「不绕路直达里程」作为走向合理性的基准。
     *
     * @return 基准里程；未给出时返回 null（上层据此报 UNKNOWN，不当通过）
     */
    public static Integer directKm(String routeText) {
        if (routeText == null || routeText.isBlank()) return null;
        Matcher m = DIRECT_PAT.matcher(routeText);
        if (m.find()) {
            Integer v = parse(m.group(1));
            if (v != null && v > 0) return v;
        }
        return null;
    }

    /** 预检结果（结构化）——上层据 {@link #infeasible} 决定"要不要走打回重做" */
    public static final class Result {
        /** 全程里程（km）；null=取不到（不下结论） */
        public final Integer km;
        /** 用户要求天数 */
        public final int days;
        /** 按日均折算所需天数；0=无法判定 */
        public final int need;
        /** 是否"物理上跑不完" */
        public final boolean infeasible;
        /** 用户可读说明（可行/无法判定时为空串） */
        public final String note;
        /** 替代方案建议（不可行时给出，真实可执行） */
        public final String advice;

        Result(Integer km, int days, int need, boolean infeasible, String note, String advice) {
            this.km = km; this.days = days; this.need = need;
            this.infeasible = infeasible; this.note = note; this.advice = advice;
        }
    }

    /**
     * 结构化的可行性预检。
     *
     * <p>铁律：取不到里程 → {@code km=null}、{@code infeasible=false}、{@code note=""}，
     * <b>不下结论</b>（不猜数字）。
     */
    public static Result evaluate(String routeText, TripRequest req) {
        try {
            if (req == null) return new Result(null, 0, 0, false, "", "");
            int days = req.getDays();
            Integer km = totalKm(routeText);
            if (days <= 0 || km == null) return new Result(km, Math.max(days, 0), 0, false, "", "");
            boolean crossCity = PlanValidator.isCrossCity(req);
            int daily = crossCity ? DAILY_CROSS_CITY_KM : DAILY_IN_CITY_KM;
            int need = (int) Math.ceil(km / (double) daily);
            if (need > days) {
                String note = "里程约 " + km + "km，按日均 " + daily + "km 需约 " + need
                        + " 天，超过用户要求的 " + days + " 天（行程过赶，已如实标注）";
                // ★替代方案：说"不行"只是半件事，给可执行的备选才是真实业务需要
                return new Result(km, days, need, true, note, adviceFor(need));
            }
            return new Result(km, days, need, false, "", "");
        } catch (Throwable t) {
            return new Result(null, 0, 0, false, "", ""); // 预检绝不抛异常影响主流程
        }
    }

    /**
     * 可判定性检查（兼容旧调用）。
     *
     * @return 需要提醒的文本；可行或无法判定时返回空串（不打扰用户）
     */
    public static String check(String routeText, TripRequest req) {
        return evaluate(routeText, req).note;
    }

    /** 替代方案建议（真实可执行）——两个判定来源共用一份文案 */
    public static String adviceFor(int needDays) {
        return "建议三选一：① 把行程加到 " + needDays + " 天以上；"
                + "② 改为先飞到最近的枢纽城市再租车自驾（省掉往返长途，实际游玩时间更多）；"
                + "③ 换一个更近的目的地。";
    }

    /**
     * 用「行程自身车程合计」复核可行性 —— 补"路线文本没给总里程"的缺口。
     *
     * @param totalDriveMinutes 全行程车程合计（分钟）
     * @param days              行程天数
     * @param dailyCapMinutes   单日车程上限（分钟）
     */
    public static Result fromPlanDrive(int totalDriveMinutes, int days, int dailyCapMinutes) {
        try {
            if (days <= 0 || dailyCapMinutes <= 0) return new Result(null, Math.max(days, 0), 0, false, "", "");
            if (totalDriveMinutes <= (long) days * dailyCapMinutes) {
                return new Result(null, days, 0, false, "", "");
            }
            int need = (int) Math.ceil(totalDriveMinutes / (double) dailyCapMinutes);
            int perDay = Math.round(totalDriveMinutes / 60f / days);
            String note = "行程自身车程合计约 " + Math.round(totalDriveMinutes / 60f) + " 小时 ÷ " + days
                    + " 天 ≈ 每天 " + perDay + " 小时，超过单日上限 " + (dailyCapMinutes / 60)
                    + " 小时（给定的 " + days + " 天内物理上排不开）";
            return new Result(null, days, need, true, note, adviceFor(need));
        } catch (Throwable t) {
            return new Result(null, 0, 0, false, "", "");
        }
    }

    private static Integer parse(String s) {
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
