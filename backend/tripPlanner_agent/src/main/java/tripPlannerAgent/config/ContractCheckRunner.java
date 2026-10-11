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
@Component
public class ContractCheckRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        try {
            String rules = ExternalRules.load("tripPlanner-agent-rules.md", "agent-rules.md");
            ContractChecker.check(rules, "行程JSON", "行程Agent规则",
                    dto.TripPlanData.class, dto.Section.class, dto.SectionItem.class);
        } catch (Exception e) {
            System.err.println("[ContractCheck] 行程Agent规则校验失败（忽略）: " + e.getMessage());
        }
    }
}
