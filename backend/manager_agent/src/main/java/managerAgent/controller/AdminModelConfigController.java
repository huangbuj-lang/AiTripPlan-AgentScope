package managerAgent.controller;

import managerAgent.service.TripTaskService;
import managerAgent.tool.ConcurrencyGate;
import managerAgent.tool.SecretMasker;
import managerAgent.tool.RemoteTimeout;
import org.springframework.web.bind.annotation.*;
import utils.CircuitBreaker;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import managerAgent.service.AdminModelConfigService;
import jakarta.annotation.Resource;

@RestController
@RequestMapping("/api/admin")
public class AdminModelConfigController {

    @Resource
    private AdminModelConfigService adminModelConfigService;

    /** 读当前模型配置（key 打码显示； */
    @GetMapping("/model-config")
    public Map<String, Object> getModelConfig() {
        return adminModelConfigService.getModelConfig();
    }

    /** 保存模型配置：先发布 Nacos 配置，成功后更新本进程并写入 Python 本地兜底缓存。 */
    @PostMapping("/model-config")
    public Map<String, Object> saveModelConfig(@RequestParam(required = false) String javaUrl,
                                               @RequestParam(required = false) String javaKey,
                                               @RequestParam(required = false) String javaName,
                                               @RequestParam(required = false) String fbUrl,
                                               @RequestParam(required = false) String fbKey,
                                               @RequestParam(required = false) String fbName,
                                               @RequestParam(required = false) String pyUrl,
                                               @RequestParam(required = false) String pyKey,
                                               @RequestParam(required = false) String pyName,
                                               @RequestParam(required = false) Integer maxConcurrentTasks,
                                               @RequestParam(required = false) Integer remoteTimeoutSeconds,
                                               @RequestParam(required = false) Integer defaultTimeoutSeconds,
                                               @RequestParam(required = false) Integer moderationTimeoutSeconds,
                                               @RequestParam(required = false) Integer aiAnswerTimeoutSeconds,
                                               @RequestParam(required = false) Integer tripEditTimeoutSeconds,
                                               @RequestParam(required = false) Double qps,
                                               @RequestParam(required = false) Integer maxInflight,
                                               @RequestParam(required = false) Integer maxRetries,
                                               @RequestParam(required = false) Long retryBaseMs,
                                               @RequestParam(required = false) Integer breakerFailureThreshold,
                                               @RequestParam(required = false) Long breakerWindowMs,
                                               @RequestParam(required = false) Long breakerCoolDownMs) {
        return adminModelConfigService.saveModelConfig(javaUrl, javaKey, javaName, fbUrl, fbKey, fbName, pyUrl, pyKey, pyName, maxConcurrentTasks, remoteTimeoutSeconds, defaultTimeoutSeconds, moderationTimeoutSeconds, aiAnswerTimeoutSeconds, tripEditTimeoutSeconds, qps, maxInflight, maxRetries, retryBaseMs, breakerFailureThreshold, breakerWindowMs, breakerCoolDownMs);
    }
}
