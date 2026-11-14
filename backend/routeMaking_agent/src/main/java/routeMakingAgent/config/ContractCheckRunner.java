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
@Component  // @Component：注册成 Spring Bean
public class ContractCheckRunner implements ApplicationRunner {  // 类声明：实现 ApplicationRunner，启动后自动跑一次

    /** 与 manager 侧 RoutePlanParser.RouteData 的字段保持一致（跨模块，无法直接引用，故列在此处） */
    private static final List<String> ROUTE_FIELDS =  // 路线 JSON 的期望字段（与 manager 侧 RoutePlanParser.RouteData 对齐）
            List.of("totalKm", "oneWayKm", "waypoints", "routeCount");  // 续：totalKm/oneWayKm/waypoints/routeCount
    /**
     * 作用：启动契约检查：校验本进程使用的提示词输出字段与 DTO 是否一致（不一致只告警）
     * 参数：args Spring 应用参数
     * 返回值：无
     * 异常：无（检查异常内部捕获，不影响启动）
     */
    @Override  // @Override 覆写 Runner 入口
    public void run(ApplicationArguments args) {  // run：Spring 启动完成后调用
        try {  // try：校验失败不影响启动
            String rules = ExternalRules.load("routeMaking-agent-rules.md", "agent-rules.md");  // 读规则（磁盘 routeMaking-agent-rules.md 优先，回退 classpath）
            ContractChecker.check(rules, "路线JSON", "路线Agent规则", ROUTE_FIELDS);  // 比对【路线JSON】声明字段 vs 上面的期望字段清单
        } catch (Exception e) {  // 捕获异常
            System.err.println("[ContractCheck] 路线Agent规则校验失败（忽略）: " + e.getMessage());  // 打印“校验失败（忽略）”
        }  // catch 结束
    }  // 方法结束
}  // 类结束

