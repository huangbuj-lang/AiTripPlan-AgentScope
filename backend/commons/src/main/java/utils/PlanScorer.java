package utils;

import dto.ScoreResult;
import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import dto.TripRequest;
import dto.ValidationReport;

import java.util.List;

/**
 * 方案评分模型（纯代码确定性打分，非 Agent）。
 * 六维加权：用户匹配 0.30 + 可行性 0.25 + 时间效率 0.15 + 预算匹配 0.15 + 数据可靠性 0.10 + 体验质量 0.05。
 * 数据不可得的维度给中性分（60~75），不因数据缺失而直接打 0，保证跨方案可比性。
 */
public final class PlanScorer {  // 类声明：方案评分模型（纯代码确定性打分，不用 LLM）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private PlanScorer() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：综合评分：按用户匹配/可行性/时间效率/预算匹配/数据可靠性/体验六个维度加权给出总分，供多方案择优
     * 参数：req 需求；plan 行程；vr 校验报告；routeOk 路线数据是否可用；planOk 行程数据是否可用
     * 返回值：评分结果（总分 + 各维度分）
     * 异常：无（纯计算，不抛异常）
     */
    public static ScoreResult score(TripRequest req, TripPlanData plan, ValidationReport vr,  // score：综合评分入口
                                    boolean routeOk, boolean planOk) {  // 续行：routeOk 路线是否可用、planOk 行程是否可用
        ScoreResult r = new ScoreResult();  // r：评分结果对象
        if (plan == null) {  // 行程为空
            r.getBreakdown().put("match", 0);  // 匹配维度 0 分
            r.getBreakdown().put("feasible", 0);  // 可行性 0 分
            r.getBreakdown().put("efficiency", 0);  // 时效 0 分
            r.getBreakdown().put("budget", 0);  // 预算 0 分
            r.getBreakdown().put("reliability", 0);  // 可靠性 0 分
            r.getBreakdown().put("experience", 0);  // 体验 0 分
            r.setTotal(0);  // 总分 0
            r.buildSummary();  // 生成摘要
            return r;  // 返回
        }  // if 结束
        int match = userMatch(req, plan, vr);  // ① 用户匹配（权重 0.30）
        int feasible = feasibility(vr);  // ② 可行性（0.25）
        int efficiency = timeEfficiency(plan);  // ③ 时间效率（0.15）
        int budget = budgetMatch(req, plan);  // ④ 预算匹配（0.15）
        int reliability = dataReliability(vr, routeOk, planOk);  // ⑤ 数据可靠性（0.10）
        int experience = experience(plan);  // ⑥ 体验质量（0.05）
        r.getBreakdown().put("match", match);  // 写入匹配分
        r.getBreakdown().put("feasible", feasible);  // 写入可行性分
        r.getBreakdown().put("efficiency", efficiency);  // 写入时效分
        r.getBreakdown().put("budget", budget);  // 写入预算分
        r.getBreakdown().put("reliability", reliability);  // 写入可靠性分
        r.getBreakdown().put("experience", experience);  // 写入体验分
        double total = match * 0.30 + feasible * 0.25 + efficiency * 0.15  // 加权求和
                + budget * 0.15 + reliability * 0.10 + experience * 0.05;  // 续行
        r.setTotal((int) Math.round(Math.max(0, Math.min(100, total))));  // 四舍五入并夹到 0~100
        r.buildSummary();  // 生成一行摘要
        return r;  // 返回评分结果
    }  // 方法结束

    // ==================== ① 用户匹配度 0.30 ====================
    /**
     * 作用：用户匹配维度评分：偏好与硬性要求被满足的程度
     * 参数：req 需求；plan 行程；vr 校验报告
     * 返回值：0~100 的分数
     * 异常：无
     */

    private static int userMatch(TripRequest req, TripPlanData plan, ValidationReport vr) {  // userMatch：① 用户匹配度
        int s = 70;  // 基础分 70
        if (vr != null) {  // 有校验报告
            long hard = vr.getIssues().stream().filter(i -> "constraint".equals(i.getType()) && "high".equals(i.getSeverity())).count();  // hard：constraint 类且 high 严重的问题数
            long mid = vr.getIssues().stream().filter(i -> "constraint".equals(i.getType()) && !"high".equals(i.getSeverity())).count();  // mid：constraint 类但非 high 的问题数
            s -= (int) hard * 25 + (int) mid * 10;  // 扣分：硬违反每个 -25，中等问题每个 -10
            if (hard == 0 && mid == 0 && req != null && req.getConstraints() != null && !req.getConstraints().isEmpty()) {  // 没有任何约束问题、且需求里确实有明确约束
                s += 15; // 明确约束且全部满足  // +15（约束全满足）
            }  // if 结束
        }  // if 结束
        if (plan.getSections() != null) {  // 有区块
            boolean hasStay = plan.getSections().stream()  // 是否存在非空 stay 区块
                    .anyMatch(x -> "stay".equals(x.getType()) && x.getItems() != null && !x.getItems().isEmpty());  // 续行
            if (hasStay) s += 10;  // 有住宿安排 +10
        }  // if 结束
        if (req != null && req.getDays() > 0 && countTimelineDays(plan) == req.getDays()) {  // 行程天数与需求天数一致
            s += 10;  // +10
        }  // if 结束
        String pref = req == null ? "" : (req.getPreference() == null ? "" : req.getPreference());  // pref：用户偏好文本
        if (pref.length() >= 2 && planText(plan).contains(pref)) {  // 偏好文本能在行程里找到
            s += 10;  // +10（说明偏好真的落到行程里）
        }  // if 结束
        return clamp(s);  // 夹到 0~100 返回
    }  // 方法结束

    // ==================== ② 可行性 0.25 ====================
    /**
     * 作用：可行性维度评分：按校验报告中的硬违反与警告数量扣分
     * 参数：vr 校验报告
     * 返回值：0~100 的分数
     * 异常：无
     */

    private static int feasibility(ValidationReport vr) {  // feasibility：② 可行性
        if (vr == null) return 75;  // 没有校验报告 → 75（中性分）
        int s = switch (vr.getStatus()) {  // 按校验状态给基础分
            case "PASS" -> 95;  // PASS → 95
            case "WARN" -> 70;  // WARN → 70
            default -> 40;  // FAIL → 40
        };  // switch 结束
        for (ValidationReport.Issue i : vr.getIssues()) {  // 遍历问题清单
            if ("medium".equals(i.getSeverity())) s -= 2;  // medium 每个 -2
            else if ("low".equals(i.getSeverity())) s -= 1;  // low 每个 -1
        }  // for 结束
        return clamp(s);  // 夹紧返回
    }  // 方法结束

    // ==================== ③ 时间效率 0.15 ====================
    /**
     * 作用：时间效率维度评分：单日有效游玩时长占比
     * 参数：plan 行程
     * 返回值：0~100 的分数
     * 异常：无
     */

    private static int timeEfficiency(TripPlanData plan) {  // timeEfficiency：③ 时间效率
        long total = 0;  // total：交通时长总和
        int n = 0;  // n：交通条目数
        if (plan.getSections() != null) {  // 有区块
            for (Section sec : plan.getSections()) {  // 遍历区块
                if (!"transport".equals(sec.getType()) || sec.getItems() == null) continue;  // 只看 transport 且有条目
                for (SectionItem it : sec.getItems()) {  // 遍历条目
                    int m = ConstraintEngine.parseDurationMinutes(it.getDuration());  // 解析时长（如“1.5小时” → 90 分钟）
                    if (m >= 0) {  // 解析成功
                        total += m;  // 累加
                        n++;  // 计数
                    }  // if 结束
                }  // for 结束
            }  // for 结束
        }  // if 结束
        if (n == 0) return 70; // 无交通数据 → 中性  // 没有交通数据 → 70（中性）
        long avg = total / n;  // 平均单段时长
        if (avg <= 60) return 95;  // ≤60 分钟 → 95
        if (avg <= 120) return 85;  // ≤120 分钟 → 85
        if (avg <= 240) return 70;  // ≤240 分钟 → 70
        return 50;  // 更长 → 50
    }  // 方法结束

    // ==================== ④ 预算匹配 0.15 ====================
    /**
     * 作用：预算匹配维度评分：总花费贴近预算区间的程度
     * 参数：req 需求；plan 行程
     * 返回值：0~100 的分数
     * 异常：无
     */

    private static int budgetMatch(TripRequest req, TripPlanData plan) {  // budgetMatch：④ 预算匹配
        double budget = req == null ? 0 : req.getBudgetTotal();  // budget：需求里的总预算（无需求按 0）
        if (budget <= 0) return 70; // 未识别预算 → 中性  // 没识别出预算 → 70（中性）
        double sum = 0;  // sum：行程累计花费
        int parsed = 0;  // parsed：成功解析出金额的条目数
        if (plan.getSections() != null) {  // 有区块
            for (Section sec : plan.getSections()) {  // 遍历区块
                if (sec.getItems() == null) continue;  // 无条目跳过
                for (SectionItem it : sec.getItems()) {  // 遍历条目
                    Double c = ConstraintEngine.parseMoney("timeline".equals(sec.getType()) ? it.getCost() : it.getPrice());  // timeline 取 cost、其它区块取 price，解析成金额（如“¥120” → 120）
                    if (c != null) {  // 解析成功
                        sum += c;  // 累加
                        parsed++;  // 计数
                    }  // if 结束
                }  // for 结束
            }  // for 结束
        }  // if 结束
        if (parsed == 0) return 60; // 有预算但行程无金额 → 略低于中性  // 有预算但一个金额都没解析出来 → 60（略低于中性）
        if (sum <= budget) return 95;  // 不超预算 → 95
        double overrun = (sum - budget) / budget;  // overrun：超支比例
        return clamp((int) (95 - overrun * 100));  // 95 减去超支百分比（超 100% 直接 0），夹紧
    }  // 方法结束

    // ==================== ⑤ 数据可靠性 0.10 ====================
    /**
     * 作用：数据可靠性维度评分：路线与行程数据是否缺失、是否有占位/编造痕迹
     * 参数：vr 校验报告；routeOk 路线是否可用；planOk 行程是否可用
     * 返回值：0~100 的分数
     * 异常：无
     */

    private static int dataReliability(ValidationReport vr, boolean routeOk, boolean planOk) {  // dataReliability：⑤ 数据可靠性
        int s;  // s：基础分
        if (routeOk && planOk) s = 95;  // 路线与行程都可用 → 95
        else if (routeOk || planOk) s = 60;  // 只有一边可用 → 60
        else s = 30;  // 都不可用 → 30
        if (vr != null) {  // 有校验报告
            s -= vr.getSkippedChecks().size() * 2; // 跳过检查越多 → 数据可得性越差  // 每跳过一项检查扣 2 分（数据越缺分越低）
        }  // if 结束
        return clamp(s);  // 夹紧返回
    }  // 方法结束

    // ==================== ⑥ 体验质量 0.05 ====================
    /**
     * 作用：体验维度评分：景点密度、住宿质量、餐饮安排的丰富度
     * 参数：plan 行程
     * 返回值：0~100 的分数
     * 异常：无
     */

    private static int experience(TripPlanData plan) {  // experience：⑥ 体验质量
        int days = countTimelineDays(plan);  // days：有内容的天数
        int items = 0;  // items：timeline 条目总数
        boolean hasTips = false;  // hasTips：是否有贴士区块
        if (plan.getSections() != null) {  // 有区块
            for (Section sec : plan.getSections()) {  // 遍历
                if ("timeline".equals(sec.getType()) && sec.getItems() != null) {  // timeline 区块累加条目数
                    items += sec.getItems().size();  // 条目总数累加该区块的条目数
                }  // tips 区块非空
                if ("tips".equals(sec.getType()) && sec.getItems() != null && !sec.getItems().isEmpty()) {  // 续：标记有贴士
                    hasTips = true;  // hasTips=true
                }  // if 结束
            }  // for 结束
        }  // if 结束
        int perDay = days > 0 ? items / days : items;  // perDay：每天平均条目数（没算出天数就用总数）
        int s = switch (perDay) {  // 按每天条目数给分
            case 0 -> 30;  // 0 条 → 30
            case 1 -> 55;  // 1 条 → 55
            case 2 -> 70;  // 2 条 → 70
            case 3 -> 85;  // 3 条 → 85
            default -> 95;  // 4 条以上 → 95
        };  // switch 结束
        if (hasTips) s += 5;  // 有贴士 +5
        return clamp(s);  // 夹紧返回
    }  // 方法结束

    // ==================== 工具 ====================
    /**
     * 作用：把分数夹紧到 0~100
     * 参数：v 原始分数
     * 返回值：夹紧后的分数
     * 异常：无
     */

    private static int clamp(int v) {  // clamp：把分数夹到 [0,100]
        return Math.max(0, Math.min(100, v));  // Math.max(0, Math.min(100, v))
    }  // 方法结束
    /**
     * 作用：统计有内容的天区块数量
     * 参数：plan 行程
     * 返回值：天区块数
     * 异常：无
     */

    private static int countTimelineDays(TripPlanData plan) {  // countTimelineDays：统计有内容的天区块数
        int n = 0;  // n：计数
        if (plan.getSections() != null) {  // 有区块
            for (Section s : plan.getSections()) {  // 遍历区块
                if ("timeline".equals(s.getType()) && s.getItems() != null && !s.getItems().isEmpty()) {  // 是 timeline 且条目非空
                    n++;  // 计数 +1
                }  // if 结束
            }  // for 结束
        }  // if 结束
        return n;  // 返回天数
    }  // 方法结束
    /**
     * 作用：把整份行程拼成纯文本（供关键词评分）
     * 参数：plan 行程
     * 返回值：拼接文本
     * 异常：无
     */

    private static String planText(TripPlanData plan) {  // planText：把行程拼成纯文本（供关键词包含判断）
        StringBuilder sb = new StringBuilder();  // sb：输出缓冲
        if (plan.getSections() != null) {  // 有区块
            for (Section s : plan.getSections()) {  // 遍历区块
                if (s.getItems() == null) continue;  // 无条目跳过
                for (SectionItem it : s.getItems()) {  // 遍历条目
                    sb.append(it.getPlace() == null ? "" : it.getPlace()).append(' ')  // 拼地点（null 归空）
                            .append(it.getName() == null ? "" : it.getName()).append(' ')  // 续：拼名称
                            .append(it.getActivity() == null ? "" : it.getActivity()).append(' ');  // 续：拼活动
                }  // for 结束
            }  // for 结束
        }  // if 结束
        return sb.toString();  // 返回拼接文本
    }  // 方法结束

    /**
     * 作用：取一组分数中的最高分
     * 参数：scores 分数列表
     * 返回值：最高分；列表为空返回 0
     * 异常：无
     */
    public static int best(List<Integer> scores) {  // best：取分数最好的方案（注意：返回的是下标，不是分数）
        int best = -1;  // best：最优下标，-1 表示没有
        int bestVal = Integer.MIN_VALUE;  // bestVal：当前最高分
        for (int i = 0; i < scores.size(); i++) {  // 遍历全部分数
            Integer s = scores.get(i);  // 取第 i 个
            if (s != null && s > bestVal) {  // 非空且比当前最高还高
                bestVal = s;  // 更新最高分
                best = i;  // 更新最优下标
            }  // if 结束
        }  // for 结束
        return best;  // 返回最优下标（分数列表为空时返回 -1）
    }  // 方法结束
}  // 类结束

