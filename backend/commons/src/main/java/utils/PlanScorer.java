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
public final class PlanScorer {

    private PlanScorer() {
    }

    /**
     * @param req      解析产物（预算/约束/偏好）
     * @param plan     结构化行程
     * @param vr       确定性校验报告（可 null）
     * @param routeOk  路线专家产物是否齐全
     * @param planOk   行程专家产物是否齐全
     */
    public static ScoreResult score(TripRequest req, TripPlanData plan, ValidationReport vr,
                                    boolean routeOk, boolean planOk) {
        ScoreResult r = new ScoreResult();
        if (plan == null) {
            r.getBreakdown().put("match", 0);
            r.getBreakdown().put("feasible", 0);
            r.getBreakdown().put("efficiency", 0);
            r.getBreakdown().put("budget", 0);
            r.getBreakdown().put("reliability", 0);
            r.getBreakdown().put("experience", 0);
            r.setTotal(0);
            r.buildSummary();
            return r;
        }
        int match = userMatch(req, plan, vr);
        int feasible = feasibility(vr);
        int efficiency = timeEfficiency(plan);
        int budget = budgetMatch(req, plan);
        int reliability = dataReliability(vr, routeOk, planOk);
        int experience = experience(plan);
        r.getBreakdown().put("match", match);
        r.getBreakdown().put("feasible", feasible);
        r.getBreakdown().put("efficiency", efficiency);
        r.getBreakdown().put("budget", budget);
        r.getBreakdown().put("reliability", reliability);
        r.getBreakdown().put("experience", experience);
        double total = match * 0.30 + feasible * 0.25 + efficiency * 0.15
                + budget * 0.15 + reliability * 0.10 + experience * 0.05;
        r.setTotal((int) Math.round(Math.max(0, Math.min(100, total))));
        r.buildSummary();
        return r;
    }

    // ==================== ① 用户匹配度 0.30 ====================

    private static int userMatch(TripRequest req, TripPlanData plan, ValidationReport vr) {
        int s = 70;
        if (vr != null) {
            long hard = vr.getIssues().stream().filter(i -> "constraint".equals(i.getType()) && "high".equals(i.getSeverity())).count();
            long mid = vr.getIssues().stream().filter(i -> "constraint".equals(i.getType()) && !"high".equals(i.getSeverity())).count();
            s -= (int) hard * 25 + (int) mid * 10;
            if (hard == 0 && mid == 0 && req != null && req.getConstraints() != null && !req.getConstraints().isEmpty()) {
                s += 15; // 明确约束且全部满足
            }
        }
        if (plan.getSections() != null) {
            boolean hasStay = plan.getSections().stream()
                    .anyMatch(x -> "stay".equals(x.getType()) && x.getItems() != null && !x.getItems().isEmpty());
            if (hasStay) s += 10;
        }
        if (req != null && req.getDays() > 0 && countTimelineDays(plan) == req.getDays()) {
            s += 10;
        }
        String pref = req == null ? "" : (req.getPreference() == null ? "" : req.getPreference());
        if (pref.length() >= 2 && planText(plan).contains(pref)) {
            s += 10;
        }
        return clamp(s);
    }

    // ==================== ② 可行性 0.25 ====================

    private static int feasibility(ValidationReport vr) {
        if (vr == null) return 75;
        int s = switch (vr.getStatus()) {
            case "PASS" -> 95;
            case "WARN" -> 70;
            default -> 40;
        };
        for (ValidationReport.Issue i : vr.getIssues()) {
            if ("medium".equals(i.getSeverity())) s -= 2;
            else if ("low".equals(i.getSeverity())) s -= 1;
        }
        return clamp(s);
    }

    // ==================== ③ 时间效率 0.15 ====================

    private static int timeEfficiency(TripPlanData plan) {
        long total = 0;
        int n = 0;
        if (plan.getSections() != null) {
            for (Section sec : plan.getSections()) {
                if (!"transport".equals(sec.getType()) || sec.getItems() == null) continue;
                for (SectionItem it : sec.getItems()) {
                    int m = ConstraintEngine.parseDurationMinutes(it.getDuration());
                    if (m >= 0) {
                        total += m;
                        n++;
                    }
                }
            }
        }
        if (n == 0) return 70; // 无交通数据 → 中性
        long avg = total / n;
        if (avg <= 60) return 95;
        if (avg <= 120) return 85;
        if (avg <= 240) return 70;
        return 50;
    }

    // ==================== ④ 预算匹配 0.15 ====================

    private static int budgetMatch(TripRequest req, TripPlanData plan) {
        double budget = req == null ? 0 : req.getBudgetTotal();
        if (budget <= 0) return 70; // 未识别预算 → 中性
        double sum = 0;
        int parsed = 0;
        if (plan.getSections() != null) {
            for (Section sec : plan.getSections()) {
                if (sec.getItems() == null) continue;
                for (SectionItem it : sec.getItems()) {
                    Double c = ConstraintEngine.parseMoney("timeline".equals(sec.getType()) ? it.getCost() : it.getPrice());
                    if (c != null) {
                        sum += c;
                        parsed++;
                    }
                }
            }
        }
        if (parsed == 0) return 60; // 有预算但行程无金额 → 略低于中性
        if (sum <= budget) return 95;
        double overrun = (sum - budget) / budget;
        return clamp((int) (95 - overrun * 100));
    }

    // ==================== ⑤ 数据可靠性 0.10 ====================

    private static int dataReliability(ValidationReport vr, boolean routeOk, boolean planOk) {
        int s;
        if (routeOk && planOk) s = 95;
        else if (routeOk || planOk) s = 60;
        else s = 30;
        if (vr != null) {
            s -= vr.getSkippedChecks().size() * 2; // 跳过检查越多 → 数据可得性越差
        }
        return clamp(s);
    }

    // ==================== ⑥ 体验质量 0.05 ====================

    private static int experience(TripPlanData plan) {
        int days = countTimelineDays(plan);
        int items = 0;
        boolean hasTips = false;
        if (plan.getSections() != null) {
            for (Section sec : plan.getSections()) {
                if ("timeline".equals(sec.getType()) && sec.getItems() != null) {
                    items += sec.getItems().size();
                }
                if ("tips".equals(sec.getType()) && sec.getItems() != null && !sec.getItems().isEmpty()) {
                    hasTips = true;
                }
            }
        }
        int perDay = days > 0 ? items / days : items;
        int s = switch (perDay) {
            case 0 -> 30;
            case 1 -> 55;
            case 2 -> 70;
            case 3 -> 85;
            default -> 95;
        };
        if (hasTips) s += 5;
        return clamp(s);
    }

    // ==================== 工具 ====================

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
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

    private static String planText(TripPlanData plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.getSections() != null) {
            for (Section s : plan.getSections()) {
                if (s.getItems() == null) continue;
                for (SectionItem it : s.getItems()) {
                    sb.append(it.getPlace() == null ? "" : it.getPlace()).append(' ')
                            .append(it.getName() == null ? "" : it.getName()).append(' ')
                            .append(it.getActivity() == null ? "" : it.getActivity()).append(' ');
                }
            }
        }
        return sb.toString();
    }

    /** 多方案择优：返回总分最高的下标（并列取先者；空列表返回 -1） */
    public static int best(List<Integer> scores) {
        int best = -1;
        int bestVal = Integer.MIN_VALUE;
        for (int i = 0; i < scores.size(); i++) {
            Integer s = scores.get(i);
            if (s != null && s > bestVal) {
                bestVal = s;
                best = i;
            }
        }
        return best;
    }
}
