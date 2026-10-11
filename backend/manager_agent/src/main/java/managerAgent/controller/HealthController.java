package managerAgent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import utils.HealthInfo;

import java.util.Map;

/**
 * ★2026-09-11 服务级健康探针（评审点：原来只有 AdminSystemController 的 /agents/health，
 * 那是“子Agent 调用统计”，不是“本进程活着吗”）。
 *
 * <p>本端点刻意<b>免登录、极轻、返回稳定形状</b>，供守护脚本/负载均衡/监控轮询：
 * {@code curl -s http://localhost:8091/health}。WebConfig 用的是白名单式拦截，故此处天然放行。
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return HealthInfo.of("manager_agent", 8091, null);
    }
}
