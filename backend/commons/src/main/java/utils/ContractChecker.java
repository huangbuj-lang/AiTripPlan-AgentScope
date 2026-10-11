package utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★2026-09-11 契约校验（评审点：结构化契约在多处手工同步，历史 bug 全出在这里）。
 *
 * <p>启动时把规则/prompt 文件里<b>声明的 JSON 字段名</b>与对应 <b>DTO 的字段名</b>对一遍，
 * 出现「规则里有、DTO 里没有」立即打 WARN——让「改了 prompt 忘了改 DTO」（或反过来）
 * 在启动阶段就暴露，而不是等跑完任务发现字段丢了、或前端渲染空白。
 *
 * <p>覆盖的同步点是五处：agent-rules.md（两服务各一份）、AgentPrompts 代码默认值、
 * Nacos 覆盖值、DTO、前端渲染映射。本类负责最易出错的一环——规则文件 ↔ DTO。
 */
public final class ContractChecker {

    private ContractChecker() {}

    /** 提取【块名】...【块名结束】之间出现的 JSON 字段名（"field": 形式） */
    public static Set<String> fieldsInBlock(String text, String blockName) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        Matcher block = Pattern.compile("【" + Pattern.quote(blockName) + "】([\\s\\S]*?)【"
                + Pattern.quote(blockName) + "结束】").matcher(text);
        while (block.find()) {
            Matcher field = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:").matcher(block.group(1));
            while (field.find()) out.add(field.group(1));
        }
        return out;
    }

    /** DTO（含父类、非静态字段）的字段名集合 */
    public static Set<String> dtoFields(Class<?> dto) {
        Set<String> out = new LinkedHashSet<>();
        for (Class<?> c = dto; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                out.add(f.getName());
            }
        }
        return out;
    }

    /**
     * 校验（DTO 版）：规则里声明但任一 DTO 都没有的字段 → WARN。
     *
     * @param dtos 该块涉及的全部 DTO（顶层 + 嵌套项，如 TripPlanData / Section / SectionItem）
     */
    public static void check(String rulesText, String blockName, String label, Class<?>... dtos) {
        Set<String> declared = fieldsInBlock(rulesText, blockName);
        if (declared.isEmpty()) {
            System.out.println("[ContractCheck] " + label + "：规则里未找到【" + blockName + "】字段声明，跳过校验");
            return;
        }
        Set<String> known = new LinkedHashSet<>();
        StringBuilder names = new StringBuilder();
        for (Class<?> d : dtos) {
            known.addAll(dtoFields(d));
            names.append(names.length() == 0 ? "" : "/").append(d.getSimpleName());
        }
        Set<String> unknown = new TreeSet<>(declared);
        unknown.removeAll(known);
        if (unknown.isEmpty()) {
            System.out.println("[ContractCheck] " + label + "：【" + blockName + "】的 " + declared.size()
                    + " 个字段与 " + names + " 一致");
        } else {
            System.err.println("[ContractCheck][WARN] " + label + "：【" + blockName + "】声明了 " + names
                    + " 里没有的字段 " + unknown + " —— prompt 与 DTO 很可能已不同步（历史 bug 高发点）");
        }
    }

    /**
     * 校验（字段名列表版）：给跨模块 DTO（如 manager 侧的 RouteData 不在本服务 classpath）或
     * 非 DTO 场景（前端映射表等）使用。
     */
    public static void check(String rulesText, String blockName, String label, List<String> expectedFields) {
        Set<String> declared = fieldsInBlock(rulesText, blockName);
        if (declared.isEmpty()) {
            System.out.println("[ContractCheck] " + label + "：规则里未找到【" + blockName + "】字段声明，跳过校验");
            return;
        }
        Set<String> unknown = new TreeSet<>(declared);
        unknown.removeAll(expectedFields);
        if (unknown.isEmpty()) {
            System.out.println("[ContractCheck] " + label + "：【" + blockName + "】的 " + declared.size()
                    + " 个字段与预期一致");
        } else {
            System.err.println("[ContractCheck][WARN] " + label + "：【" + blockName + "】声明了预期之外的字段 "
                    + unknown + " —— 规则/DTO 可能已不同步");
        }
    }
}
