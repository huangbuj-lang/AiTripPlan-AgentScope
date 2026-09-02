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

    /** 约束条数上限（防极端长文撑爆提示词） */
    private static final int MAX_CONSTRAINTS = 8;

    private RequirementNormalizer() {
    }

    /** 就地规整：预算缺失时从原文提取；约束去重/层级校准；补登 P0 预算、P0 必去、P1 回避。 */
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
}
