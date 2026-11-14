package managerAgent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import utils.ContractChecker;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★2026-09-11 启动契约校验（评审点：结构化契约多处手工同步，历史 bug 全出在这里）。
 *
 * <p>manager 侧负责两处：
 * <ol>
 *   <li>【行程JSON】字段 ↔ TripPlanData/Section/SectionItem（主管解析行程Agent终版，字段不同步就直接丢数据）</li>
 *   <li><b>方案风格 key ↔ 前端 STYLE_LABELS</b>——这处踩过真 bug：后端风格 key 由 food/photo/budget
 *       改为 classic/loop/leisure 后前端没跟，多方案标签整块渲染空白。前端文件打进了 jar
 *       （classpath:/static/js/app.js），这里直接读出来比对。</li>
 * </ol>
 * 不一致只 WARN，不阻断启动——诊断信息不该让服务起不来。
 */
@Component  // @Component：注册成 Spring Bean
public class ContractCheckRunner implements ApplicationRunner {  // 类声明：实现 ApplicationRunner，应用启动后自动执行一次

    /** 与 TripOrchestrator.PLAN_STYLES[i][0] 保持一致（private static，跨类无法直接引用，故列在此处） */
    private static final Set<String> BACKEND_STYLE_KEYS = new LinkedHashSet<>(java.util.List.of("classic", "loop", "leisure"));  // 后端方案风格 key（与 TripOrchestrator.PLAN_STYLES 对齐）
    /**
     * 作用：启动契约检查：应用启动后校验提示词声明的输出字段与 DTO/前端是否一致（不一致只告警）
     * 参数：args Spring 应用参数
     * 返回值：无
     * 异常：无（检查异常内部捕获，不影响启动）
     */
    @Override  // @Override 覆写 Runner 入口
    public void run(ApplicationArguments args) {  // run：Spring 启动完成后调用
        checkPlanJsonContract();  // 校验【行程JSON】字段契约
        checkFrontendStyleContract();  // 校验前端 STYLE_LABELS 风格 key 契约
    }  // 方法结束

    /**
     * 作用：校验提示词里约定的行程 JSON 字段与 TripPlanData 字段是否一致
     * 参数：无
     * 返回值：无
     * 异常：无（只打印告警）
     */
    private void checkPlanJsonContract() {  // checkPlanJsonContract：行程 JSON 字段 ↔ DTO 字段
        try {  // try：校验失败不影响启动
            String rules = utils.ExternalRules.load("tripPlanner-agent-rules.md", "agent-rules.md");  // 读规则文件（磁盘优先，回退 classpath 的 agent-rules.md）
            if (rules == null || rules.isBlank()) {  // 规则缺失（独立部署没带规则文件）
                System.out.println("[ContractCheck] 磁盘无行程Agent规则（独立部署场景），跳过主管侧行程JSON校验");  // 打印跳过
                return;  // return
            }  // if 结束
            ContractChecker.check(rules, "行程JSON", "主管侧读到的行程Agent规则",  // 调 ContractChecker 比对：规则里【行程JSON】声明的字段
                    dto.TripPlanData.class, dto.Section.class, dto.SectionItem.class);  // 续：vs TripPlanData/Section/SectionItem 的字段
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ContractCheck] 行程JSON契约校验失败（忽略）: " + e.getMessage());  // 打印“校验失败（忽略）”
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：校验前端样式常量与后端约定是否一致（防止样式口径漂移）
     * 参数：无
     * 返回值：无
     * 异常：无（只打印告警）
     */
    private void checkFrontendStyleContract() {  // checkFrontendStyleContract：后端风格 key ↔ 前端 STYLE_LABELS
        try {  // try：失败不影响启动
            String js = readClasspath("/static/js/app.js");  // 从 classpath 读前端 app.js（被打进 jar）
            if (js == null || js.isBlank()) {  // 文件不存在
                System.out.println("[ContractCheck] 未找到前端 app.js，跳过风格 key 校验");  // 打印跳过
                return;  // return
            }  // if 结束
            Set<String> frontKeys = new LinkedHashSet<>();  // frontKeys：前端声明的风格 key
            Matcher block = Pattern.compile("STYLE_LABELS\\s*=\\s*\\{([^}]*)\\}").matcher(js);  // 正则匹配 STYLE_LABELS = { ... }
            if (block.find()) {  // 匹配到配置块
                Matcher key = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*:").matcher(block.group(1));  // 在块内匹配 key:
                while (key.find()) frontKeys.add(key.group(1));  // 逐个加入集合
            }  // for 结束
            if (frontKeys.isEmpty()) {  // 一个都没解析到
                System.out.println("[ContractCheck] 前端未解析到 STYLE_LABELS，跳过风格 key 校验");  // 打印跳过
                return;  // return
            }  // if 结束
            Set<String> missingInFront = new TreeSet<>(BACKEND_STYLE_KEYS);  // missingInFront：后端有、前端没有的
            missingInFront.removeAll(frontKeys);  // 求差集
            Set<String> extraInFront = new TreeSet<>(frontKeys);  // extraInFront：前端有、后端不产出的
            extraInFront.removeAll(BACKEND_STYLE_KEYS);  // 求差集
            if (missingInFront.isEmpty() && extraInFront.isEmpty()) {  // 两边都为空
                System.out.println("[ContractCheck] 方案风格 key 与前端 STYLE_LABELS 一致：" + frontKeys);  // 打印一致 + 前端 key 集合
            } else {  // 否则
                if (!missingInFront.isEmpty()) {  // 后端 key 前端缺失
                    System.err.println("[ContractCheck][WARN] 后端风格 key " + missingInFront  // WARN：该方案标签会渲染成「方案N」
                            + " 在前端 STYLE_LABELS 中缺失 → 该方案标签会渲染成「方案N」");  // 续行
                }  // if 结束
                if (!extraInFront.isEmpty()) {  // 前端多了后端不产出的
                    System.err.println("[ContractCheck][WARN] 前端 STYLE_LABELS 多了后端不产出的 key "  // WARN：死配置
                            + extraInFront + " → 死配置，后端不会输出这些风格");  // 续行
                }  // if 结束
            }  // if 结束
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ContractCheck] 风格 key 校验失败（忽略）: " + e.getMessage());  // 打印“校验失败（忽略）”
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取 classpath 下的文本资源
     * 参数：path 资源路径
     * 返回值：资源文本；读不到返回空串
     * 异常：无（异常内部捕获）
     */
    private static String readClasspath(String path) {  // readClasspath：读 classpath 文本资源
        try (InputStream in = ContractCheckRunner.class.getResourceAsStream(path)) {  // try-with-resources 打开资源流
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);  // 读不到返回 null，否则解 UTF-8
        } catch (Exception e) {  // 捕获异常
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束
}  // 类结束

