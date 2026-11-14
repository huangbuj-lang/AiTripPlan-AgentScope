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
@RestController  // @RestController：返回 JSON
public class HealthController {  // 类声明：HealthController

    @GetMapping("/health")  // 映射为 GET /health
    public Map<String, Object> health() {  // 签名：无参数，返回 Map
        return HealthInfo.of("manager_agent", 8091, null);  // 返回 HealthInfo.of("manager_agent", 8091, null)：服务名、端口、附加信息为空——形状固定，监控不用适配
    }  // 方法结束
}  // 类结束

