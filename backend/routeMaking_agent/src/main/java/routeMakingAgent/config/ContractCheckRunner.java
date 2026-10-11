package routeMakingAgent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import utils.ContractChecker;
import utils.ExternalRules;

import java.util.List;

/**
 * ★2026-09-11 启动契约校验（评审点）：【路线JSON】的字段声明写在 routeMaking-agent-rules.md 里，
 * 而真正消费它的是<b>另一个模块</b>（manager 侧 RoutePlanParser.RouteData / TripOrchestrator），
 * 跨模块手工同步最容易漏。启动时对一遍字段名，不一致立刻 WARN。
 */
@Component
public class ContractCheckRunner implements ApplicationRunner {

    /** 与 manager 侧 RoutePlanParser.RouteData 的字段保持一致（跨模块，无法直接引用，故列在此处） */
    private static final List<String> ROUTE_FIELDS =
            List.of("totalKm", "oneWayKm", "waypoints", "routeCount");

    @Override
    public void run(ApplicationArguments args) {
        try {
            String rules = ExternalRules.load("routeMaking-agent-rules.md", "agent-rules.md");
            ContractChecker.check(rules, "路线JSON", "路线Agent规则", ROUTE_FIELDS);
        } catch (Exception e) {
            System.err.println("[ContractCheck] 路线Agent规则校验失败（忽略）: " + e.getMessage());
        }
    }
}
