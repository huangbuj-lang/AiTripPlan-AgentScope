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
@Component
public class ContractCheckRunner implements ApplicationRunner {

    /** 与 TripOrchestrator.PLAN_STYLES[i][0] 保持一致（private static，跨类无法直接引用，故列在此处） */
    private static final Set<String> BACKEND_STYLE_KEYS = new LinkedHashSet<>(java.util.List.of("classic", "loop", "leisure"));

    @Override
    public void run(ApplicationArguments args) {
        checkPlanJsonContract();
        checkFrontendStyleContract();
    }

    /** ① 【行程JSON】字段 ↔ DTO
     *  manager 自身不含 agent-rules 资源；但部署时与子Agent同机，磁盘上（agent-rules/）有行程Agent的规则，
     *  直接读它做校验——这样主管侧解析端与 tripPlanner 产出端共享同一份字段契约认知。
     *  （若各自独立部署、磁盘读不到，则本项自动跳过，由 tripPlanner 自己的启动校验兜住。） */
    private void checkPlanJsonContract() {
        try {
            String rules = utils.ExternalRules.load("tripPlanner-agent-rules.md", "agent-rules.md");
            if (rules == null || rules.isBlank()) {
                System.out.println("[ContractCheck] 磁盘无行程Agent规则（独立部署场景），跳过主管侧行程JSON校验");
                return;
            }
            ContractChecker.check(rules, "行程JSON", "主管侧读到的行程Agent规则",
                    dto.TripPlanData.class, dto.Section.class, dto.SectionItem.class);
        } catch (Exception e) {
            System.err.println("[ContractCheck] 行程JSON契约校验失败（忽略）: " + e.getMessage());
        }
    }

    /** ② 后端风格 key ↔ 前端 STYLE_LABELS（踩过的真 bug：key 不匹配 → 多方案标签空白） */
    private void checkFrontendStyleContract() {
        try {
            String js = readClasspath("/static/js/app.js");
            if (js == null || js.isBlank()) {
                System.out.println("[ContractCheck] 未找到前端 app.js，跳过风格 key 校验");
                return;
            }
            Set<String> frontKeys = new LinkedHashSet<>();
            Matcher block = Pattern.compile("STYLE_LABELS\\s*=\\s*\\{([^}]*)\\}").matcher(js);
            if (block.find()) {
                Matcher key = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*:").matcher(block.group(1));
                while (key.find()) frontKeys.add(key.group(1));
            }
            if (frontKeys.isEmpty()) {
                System.out.println("[ContractCheck] 前端未解析到 STYLE_LABELS，跳过风格 key 校验");
                return;
            }
            Set<String> missingInFront = new TreeSet<>(BACKEND_STYLE_KEYS);
            missingInFront.removeAll(frontKeys);
            Set<String> extraInFront = new TreeSet<>(frontKeys);
            extraInFront.removeAll(BACKEND_STYLE_KEYS);
            if (missingInFront.isEmpty() && extraInFront.isEmpty()) {
                System.out.println("[ContractCheck] 方案风格 key 与前端 STYLE_LABELS 一致：" + frontKeys);
            } else {
                if (!missingInFront.isEmpty()) {
                    System.err.println("[ContractCheck][WARN] 后端风格 key " + missingInFront
                            + " 在前端 STYLE_LABELS 中缺失 → 该方案标签会渲染成「方案N」");
                }
                if (!extraInFront.isEmpty()) {
                    System.err.println("[ContractCheck][WARN] 前端 STYLE_LABELS 多了后端不产出的 key "
                            + extraInFront + " → 死配置，后端不会输出这些风格");
                }
            }
        } catch (Exception e) {
            System.err.println("[ContractCheck] 风格 key 校验失败（忽略）: " + e.getMessage());
        }
    }

    /** 从 classpath（jar 内 static）读文件为字符串，失败返回 null */
    private static String readClasspath(String path) {
        try (InputStream in = ContractCheckRunner.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
