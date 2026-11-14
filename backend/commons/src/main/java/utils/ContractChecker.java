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
public final class ContractChecker {  // 类声明：契约校验工具（规则文件字段 ↔ DTO 字段）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ContractChecker() {}  // 私有构造：禁止 new

    /**
     * 作用：从提示词文本的指定区块中抽取被声明的字段名（形如 "字段名:" 或反引号包裹）
     * 参数：text 提示词/规则文本；blockName 区块名（如 JSON 输出块）
     * 返回值：字段名集合
     * 异常：无
     */
    public static Set<String> fieldsInBlock(String text, String blockName) {  // fieldsInBlock：从【区块】里抽字段名
        Set<String> out = new LinkedHashSet<>();  // out：保序字段集合
        if (text == null || text.isBlank()) return out;  // 文本为空返回空集
        Matcher block = Pattern.compile("【" + Pattern.quote(blockName) + "】([\\s\\S]*?)【"  // 正则匹配【区块名】…【区块名结束】之间的内容（[\s\S]*? 含换行、非贪婪）
                + Pattern.quote(blockName) + "结束】").matcher(text);  // 续行
        while (block.find()) {  // 逐段匹配
            Matcher field = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:").matcher(block.group(1));  // 在区块内匹配 "字段名" : 形式
            while (field.find()) out.add(field.group(1));  // 命中就加入集合
        }  // for 结束
        return out;  // 返回字段集合
    }  // 方法结束

    /**
     * 作用：用反射取出 DTO 的全部字段名（含继承字段）
     * 参数：dto DTO 类
     * 返回值：字段名集合
     * 异常：无
     */
    public static Set<String> dtoFields(Class<?> dto) {  // dtoFields：反射取 DTO 全部字段名
        Set<String> out = new LinkedHashSet<>();  // out：保序集合
        for (Class<?> c = dto; c != null && c != Object.class; c = c.getSuperclass()) {  // 逐层向上（含父类，到 Object 停）
            for (Field f : c.getDeclaredFields()) {  // 取本类声明的字段
                if (Modifier.isStatic(f.getModifiers())) continue;  // 跳过 static 常量
                out.add(f.getName());  // 加入字段名
            }  // for 结束
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：契约检查（按 DTO 类）：比对提示词声明的字段与 DTO 实际字段，缺失/多余时打印告警
     * 参数：rulesText 规则文本；blockName 区块名；label 检查项标签；dtos 参与比对的 DTO 类
     * 返回值：无
     * 异常：无（只告警，不打断启动）
     */
    public static void check(String rulesText, String blockName, String label, Class<?>... dtos) {  // check（按 DTO 类）：比对声明字段与 DTO 字段
        Set<String> declared = fieldsInBlock(rulesText, blockName);  // declared：规则里声明的字段
        if (declared.isEmpty()) {  // 一个都没找到
            System.out.println("[ContractCheck] " + label + "：规则里未找到【" + blockName + "】字段声明，跳过校验");  // 打印“跳过校验”（说明规则文件里没有该区块）
            return;  // return
        }  // if 结束
        Set<String> known = new LinkedHashSet<>();  // known：所有 DTO 字段的并集
        StringBuilder names = new StringBuilder();  // names：类名拼接（日志用）
        for (Class<?> d : dtos) {  // 遍历 DTO
            known.addAll(dtoFields(d));  // 合并字段
            names.append(names.length() == 0 ? "" : "/").append(d.getSimpleName());  // 类名用 / 连接
        }  // for 结束
        Set<String> unknown = new TreeSet<>(declared);  // unknown：声明了但 DTO 里没有的
        unknown.removeAll(known);  // 求差集
        if (unknown.isEmpty()) {  // 没有差异
            System.out.println("[ContractCheck] " + label + "：【" + blockName + "】的 " + declared.size()  // 打印“一致”+ 字段数
                    + " 个字段与 " + names + " 一致");  // 续行
        } else {  // 否则
            System.err.println("[ContractCheck][WARN] " + label + "：【" + blockName + "】声明了 " + names  // WARN：声明了 DTO 没有的字段
                    + " 里没有的字段 " + unknown + " —— prompt 与 DTO 很可能已不同步（历史 bug 高发点）");  // 续：提示 prompt 与 DTO 可能不同步（历史 bug 高发点）
        }  // if 结束
    }  // 方法结束

    /**
     * 作用：契约检查（按字段名清单）：比对提示词声明字段与给定字段清单
     * 参数：rulesText 规则文本；blockName 区块名；label 检查项标签；expectedFields 期望字段清单
     * 返回值：无
     * 异常：无（只告警，不打断启动）
     */
    public static void check(String rulesText, String blockName, String label, List<String> expectedFields) {  // check（按字段清单）：比对声明字段与给定清单
        Set<String> declared = fieldsInBlock(rulesText, blockName);  // declared：声明字段
        if (declared.isEmpty()) {  // 没找到
            System.out.println("[ContractCheck] " + label + "：规则里未找到【" + blockName + "】字段声明，跳过校验");  // 打印跳过
            return;  // return
        }  // if 结束
        Set<String> unknown = new TreeSet<>(declared);  // unknown：声明了但清单里没有的
        unknown.removeAll(expectedFields);  // 求差集
        if (unknown.isEmpty()) {  // 无差异
            System.out.println("[ContractCheck] " + label + "：【" + blockName + "】的 " + declared.size()  // 打印一致
                    + " 个字段与预期一致");  // 续行
        } else {  // 否则
            System.err.println("[ContractCheck][WARN] " + label + "：【" + blockName + "】声明了预期之外的字段 "  // WARN：声明了预期之外的字段
                    + unknown + " —— 规则/DTO 可能已不同步");  // 续行
        }  // if 结束
    }  // 方法结束
}  // 类结束

