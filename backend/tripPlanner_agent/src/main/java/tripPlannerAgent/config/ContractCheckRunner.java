package tripPlannerAgent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import utils.ContractChecker;
import utils.ExternalRules;

/**
 * ★2026-09-11 启动契约校验（评审点）：【行程JSON】的字段声明写在 tripPlanner-agent-rules.md 里，
 * 解析用的 DTO 在 commons。两处手工同步，历史 bug 全出在这（改了 prompt 没改 DTO → 字段静默丢失）。
 * 启动时对一遍，不一致立刻 WARN，不必等任务跑完才发现。
 */
@Component  // @Component：注册成 Spring Bean
public class ContractCheckRunner implements ApplicationRunner {  // 类声明：实现 ApplicationRunner，启动后自动跑一次
    /**
     * 作用：启动契约检查：校验本进程使用的提示词输出字段与 DTO 是否一致（不一致只告警）
     * 参数：args Spring 应用参数
     * 返回值：无
     * 异常：无（检查异常内部捕获，不影响启动）
     */
    @Override  // @Override 覆写 Runner 入口
    public void run(ApplicationArguments args) {  // run：Spring 启动完成后调用
        try {  // try：校验失败不影响启动
            String rules = ExternalRules.load("tripPlanner-agent-rules.md", "agent-rules.md");  // 读规则（磁盘 tripPlanner-agent-rules.md 优先，回退 classpath 的 agent-rules.md）
            ContractChecker.check(rules, "行程JSON", "行程Agent规则",  // 比对【行程JSON】声明字段 vs
                    dto.TripPlanData.class, dto.Section.class, dto.SectionItem.class);  // 续：TripPlanData/Section/SectionItem 的字段
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ContractCheck] 行程Agent规则校验失败（忽略）: " + e.getMessage());  // 打印“校验失败（忽略）”
        }  // catch 结束
    }  // 方法结束
}  // 类结束

