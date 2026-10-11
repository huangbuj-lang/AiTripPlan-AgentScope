package managerAgent.service;

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
import org.springframework.stereotype.Service;

/** AdminModelConfig 的业务实现（自原控制器等价平移，逻辑未改）。 */
@Service
public class AdminModelConfigService {

    public Map<String, Object> getModelConfig() {
        Map<String, Object> r = new java.util.HashMap<>();
        String jk = utils.ModelConfigHolder.apiKey();
        r.put("java", Map.of(
                "url", utils.ModelConfigHolder.baseUrl(),
                "name", utils.ModelConfigHolder.modelName(),
                "keyMasked", SecretMasker.mask(jk)));
        String fk = utils.ModelConfigHolder.fbApiKey();
        r.put("fallback", Map.of(
                "url", utils.ModelConfigHolder.fbBaseUrl(),
                "name", utils.ModelConfigHolder.fbModelName(),
                "keyMasked", SecretMasker.mask(fk)));
        Map<String, Object> py = readPyLlmConfig();
        py.put("keyMasked", SecretMasker.mask((String) py.get("key")));
        py.remove("key");
        r.put("python", py);
        Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
        RemoteTimeout.configure(orchestration);
        r.put("orchestration", Map.of(
                "maxConcurrentTasks", TripTaskService.maxConcurrent(),
                "remoteTimeoutSeconds", RemoteTimeout.currentSeconds(),
                "source", orchestration != null && orchestration.get("maxConcurrentTasks") != null
                        ? "nacos" : "env"));
        Map<String, Object> timeoutConfig = utils.NacosModelConfig.timeoutsConfig();
        r.put("timeouts", Map.of(
                "defaultSeconds", utils.ModelTimeouts.defaultMillis() / 1000,
                "moderationSeconds", utils.ModelTimeouts.moderationMillis() / 1000,
                "aiAnswerSeconds", utils.ModelTimeouts.aiAnswerMillis() / 1000,
                "tripEditSeconds", utils.ModelTimeouts.tripEditMillis() / 1000,
                "source", timeoutConfig.isEmpty() ? "default" : "nacos"));
        r.put("success", true);
        return r;
    }

    public Map<String, Object> saveModelConfig(String javaUrl, String javaKey, String javaName, String fbUrl, String fbKey, String fbName, String pyUrl, String pyKey, String pyName, Integer maxConcurrentTasks, Integer remoteTimeoutSeconds, Integer defaultTimeoutSeconds, Integer moderationTimeoutSeconds, Integer aiAnswerTimeoutSeconds, Integer tripEditTimeoutSeconds, Double qps, Integer maxInflight, Integer maxRetries, Long retryBaseMs, Integer breakerFailureThreshold, Long breakerWindowMs, Long breakerCoolDownMs) {
        Map<String, Object> r = new java.util.HashMap<>();
        try {
            if (maxConcurrentTasks != null
                    && (maxConcurrentTasks < ConcurrencyGate.MIN_LIMIT
                    || maxConcurrentTasks > ConcurrencyGate.HARD_LIMIT)) {
                r.put("success", false);
                r.put("message", "保存失败：最大并发任务必须在 "
                        + ConcurrencyGate.MIN_LIMIT + "-" + ConcurrencyGate.HARD_LIMIT + " 之间");
                return r;
            }
            if (remoteTimeoutSeconds != null
                    && (remoteTimeoutSeconds < RemoteTimeout.MIN_SECONDS
                    || remoteTimeoutSeconds > RemoteTimeout.MAX_SECONDS)) {
                r.put("success", false);
                r.put("message", "保存失败：远程调用超时必须在 "
                        + RemoteTimeout.MIN_SECONDS + "-" + RemoteTimeout.MAX_SECONDS + " 秒之间");
                return r;
            }
            Integer[] directTimeouts = {defaultTimeoutSeconds, moderationTimeoutSeconds,
                    aiAnswerTimeoutSeconds, tripEditTimeoutSeconds};
            for (Integer timeout : directTimeouts) {
                if (timeout != null && (timeout < utils.ModelTimeouts.MIN_SECONDS
                        || timeout > utils.ModelTimeouts.MAX_SECONDS)) {
                    r.put("success", false);
                    r.put("message", "保存失败：直连模型超时必须在 "
                            + utils.ModelTimeouts.MIN_SECONDS + "-" + utils.ModelTimeouts.MAX_SECONDS + " 秒之间");
                    return r;
                }
            }
            boolean javaChanged = (javaUrl != null && !javaUrl.isBlank())
                    || (javaKey != null && !javaKey.isBlank())
                    || (javaName != null && !javaName.isBlank());
            boolean fbChanged = (fbUrl != null && !fbUrl.isBlank())
                    || (fbKey != null && !fbKey.isBlank())
                    || (fbName != null && !fbName.isBlank());
            boolean pyChanged = pyUrl != null && !pyUrl.isBlank() || pyKey != null && !pyKey.isBlank()
                    || pyName != null && !pyName.isBlank();
            boolean orchestrationChanged = maxConcurrentTasks != null || remoteTimeoutSeconds != null;
            boolean timeoutsChanged = defaultTimeoutSeconds != null || moderationTimeoutSeconds != null
                    || aiAnswerTimeoutSeconds != null || tripEditTimeoutSeconds != null;
            boolean limitsChanged = qps != null || maxInflight != null || maxRetries != null || retryBaseMs != null
                    || breakerFailureThreshold != null || breakerWindowMs != null || breakerCoolDownMs != null;
            if (!javaChanged && !fbChanged && !pyChanged && !orchestrationChanged
                    && !timeoutsChanged && !limitsChanged) {
                r.put("success", true);
                r.put("message", "没有需要保存的修改");
                return r;
            }

            Map<String, Object> java = new java.util.HashMap<>(utils.NacosModelConfig.javaConfig());
            putIfPresent(java, "url", javaUrl);
            putIfPresent(java, "key", javaKey);
            putIfPresent(java, "name", javaName);

            Map<String, Object> fallback = new java.util.HashMap<>(utils.NacosModelConfig.fallbackConfig());
            putIfPresent(fallback, "url", fbUrl);
            putIfPresent(fallback, "key", fbKey);
            putIfPresent(fallback, "name", fbName);

            Map<String, Object> python = new java.util.HashMap<>(readPyLlmConfig());
            putIfPresent(python, "url", pyUrl);
            putIfPresent(python, "key", pyKey);
            putIfPresent(python, "name", pyName);

            Map<String, Object> config = new java.util.LinkedHashMap<>();
            config.put("java", java);
            config.put("fallback", fallback);
            config.put("python", python);
            Map<String, Object> limits = new java.util.LinkedHashMap<>();
            limits.put("qps", qps != null ? qps : utils.SafeModel.getMaxQps());
            limits.put("maxInflight", maxInflight != null ? maxInflight : utils.SafeModel.getMaxInflight()); // 在途并发
            limits.put("maxRetries", maxRetries != null ? maxRetries : utils.SafeModel.getMaxRetries());
            limits.put("retryBaseMs", retryBaseMs != null ? retryBaseMs : utils.SafeModel.getRetryBaseMs());
            limits.put("breakerFailureThreshold", breakerFailureThreshold != null ? breakerFailureThreshold : utils.CircuitBreaker.getFailureThreshold());
            limits.put("breakerWindowMs", breakerWindowMs != null ? breakerWindowMs : utils.CircuitBreaker.getWindowMs());
            limits.put("breakerCoolDownMs", breakerCoolDownMs != null ? breakerCoolDownMs : utils.CircuitBreaker.getCoolDownMs());
            config.put("limits", limits);
            Map<String, Object> orchestration = utils.NacosModelConfig.orchestrationConfig();
            if (orchestration == null) {
                orchestration = new java.util.LinkedHashMap<>();
            }
            if (orchestrationChanged) {
                if (maxConcurrentTasks != null) {
                    orchestration.put("maxConcurrentTasks", maxConcurrentTasks);
                }
                if (remoteTimeoutSeconds != null) {
                    orchestration.put("remoteTimeoutSeconds", remoteTimeoutSeconds);
                }
            }
            if (!orchestration.isEmpty()) {
                config.put("orchestration", orchestration);
            }
            if (timeoutsChanged) {
                Map<String, Object> timeouts = new java.util.LinkedHashMap<>(
                        utils.NacosModelConfig.timeoutsConfig());
                if (defaultTimeoutSeconds != null) timeouts.put("defaultSeconds", defaultTimeoutSeconds);
                if (moderationTimeoutSeconds != null) timeouts.put("moderationSeconds", moderationTimeoutSeconds);
                if (aiAnswerTimeoutSeconds != null) timeouts.put("aiAnswerSeconds", aiAnswerTimeoutSeconds);
                if (tripEditTimeoutSeconds != null) timeouts.put("tripEditSeconds", tripEditTimeoutSeconds);
                config.put("timeouts", timeouts);
            }
            config.put("updatedAt", Instant.now().toString());
            utils.NacosModelConfig.publish(config);

            utils.ModelConfigHolder.update(javaUrl, javaKey, javaName);
            utils.ModelConfigHolder.updateFallback(fbUrl, fbKey, fbName);
            if (javaChanged || fbChanged) managerAgent.tool.LlmClientReset.reset();
            savePyLlmConfig(pyUrl, pyKey, pyName);

            r.put("success", true);
            r.put("message", "配置已发布到Nacos；相关进程监听热更，无需重启");
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", "保存失败：" + e.getMessage());
        }
        return r;
    }

    private void putIfPresent(Map<String, Object> config, String key, String value) {
        if (value != null && !value.isBlank()) config.put(key, value.trim());
    }

    private java.io.File pyConfigFile() {
        return new java.io.File(managerAgent.tool.ProjectPaths.root(), "data-pipeline/llm_config.json");
    }

    private Map<String, Object> readPyLlmConfig() {
        Map<String, Object> m = new java.util.HashMap<>();
        Map<String, Object> remote = utils.NacosModelConfig.pythonConfig();
        if (remote != null) {
            m.putAll(remote);
        } else {
            try {
                java.io.File f = pyConfigFile();
                if (f.exists()) {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    m = om.readValue(f, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                }
            } catch (Exception ignored) { }
        }
        m.putIfAbsent("url", "https://openrouter.ai/api/v1");
        m.putIfAbsent("key", "");
        m.putIfAbsent("name", "stealth/ox-alpha");
        return m;
    }

    private void savePyLlmConfig(String url, String key, String name) throws Exception {
        Map<String, Object> cur = readPyLlmConfig();
        if (url != null && !url.isBlank()) cur.put("url", url.trim());
        if (key != null && !key.isBlank()) cur.put("key", key.trim());
        if (name != null && !name.isBlank()) cur.put("name", name.trim());
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValue(pyConfigFile(), cur);
    }
}
