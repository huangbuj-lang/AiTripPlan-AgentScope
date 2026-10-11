package utils;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Nacos model configuration. */
@Slf4j
public final class NacosModelConfig {

    public static final String DATA_ID = "aitripplan-model-config.json";
    public static final String GROUP = Config.get("NACOS_MODEL_CONFIG_GROUP", "DEFAULT_GROUP");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile ConfigService configService;
    private static volatile Map<String, Object> latestConfig;

    private NacosModelConfig() {}

    public static String serverAddr() {
        return AgentEndpoints.nacosAddr(); // ★2026-09-11 端点集中配置（原硬编码 localhost:8848）
    }

    /** Starts asynchronous loading so a temporarily unavailable Nacos cannot block application startup. */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(NacosModelConfig::connectAndListen, "nacos-model-config-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private static void connectAndListen() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ConfigService service = createService();
                String content = service.getConfig(DATA_ID, GROUP, 5_000L);
                if (content != null && !content.isBlank()) {
                    applyConfig(content);
                }
                service.addListener(DATA_ID, GROUP, new com.alibaba.nacos.api.config.listener.Listener() {
                    @Override
                    public Executor getExecutor() {
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        try {
                            applyConfig(configInfo);
                            log.info("Nacos model config updated");
                        } catch (Exception e) {
                            log.warn("Invalid Nacos model config: {}", e.getMessage());
                        }
                    }
                });
                configService = service;
                log.info("Nacos model config listener started: {}/{}", GROUP, DATA_ID);
                return;
            } catch (Exception e) {
                configService = null;
                log.warn("Nacos model config unavailable, retrying in 15s: {}", e.getMessage());
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static void publish(Map<String, Object> config) throws Exception {
        String content = JSON.writeValueAsString(config);
        ConfigService service = configService;
        if (service == null) service = createService();
        if (!service.publishConfig(DATA_ID, GROUP, content)) {
            throw new IllegalStateException("Nacos publishConfig returned false");
        }
        configService = service;
    }

    public static Map<String, Object> javaConfig() {
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("url", ModelConfigHolder.baseUrl());
        java.put("key", ModelConfigHolder.apiKey());
        java.put("name", ModelConfigHolder.modelName());
        return java;
    }

    public static Map<String, Object> fallbackConfig() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("url", ModelConfigHolder.fbBaseUrl());
        fallback.put("key", ModelConfigHolder.fbApiKey());
        fallback.put("name", ModelConfigHolder.fbModelName());
        return fallback;
    }

    /** Returns the python section from the latest Nacos snapshot. */
    public static Map<String, Object> pythonConfig() {
        Map<String, Object> root = latestConfig;
        if (root == null) return null;
        if (!(root.get("python") instanceof Map<?, ?> section)) return new LinkedHashMap<>();
        Map<String, Object> python = new LinkedHashMap<>();
        section.forEach((key, value) -> {
            if (key != null) python.put(String.valueOf(key), value);
        });
        return python;
    }

    /** Returns the orchestration section from the latest Nacos snapshot. */
    public static Map<String, Object> orchestrationConfig() {
        Map<String, Object> root = latestConfig;
        if (root == null) return null;
        if (!(root.get("orchestration") instanceof Map<?, ?> section)) return new LinkedHashMap<>();
        Map<String, Object> orchestration = new LinkedHashMap<>();
        section.forEach((key, value) -> {
            if (key != null) orchestration.put(String.valueOf(key), value);
        });
        return orchestration;
    }

    /** Returns direct LLM call timeouts. */
    public static Map<String, Object> timeoutsConfig() {
        Map<String, Object> root = latestConfig;
        if (root == null) return new LinkedHashMap<>();
        if (!(root.get("timeouts") instanceof Map<?, ?> section)) return new LinkedHashMap<>();
        Map<String, Object> timeouts = new LinkedHashMap<>();
        section.forEach((key, value) -> {
            if (key != null) timeouts.put(String.valueOf(key), value);
        });
        return timeouts;
    }

    static void applyConfig(String content) throws Exception {
        if (content == null || content.isBlank()) return;
        Map<String, Object> root = JSON.readValue(content,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        latestConfig = root;

        boolean javaChanged = false;
        Object java = root.get("java");
        if (java instanceof Map<?, ?> section) {
            ModelConfigHolder.update(text(section.get("url")), text(section.get("key")), text(section.get("name")));
            javaChanged = true;
        }
        Object fallback = root.get("fallback");
        if (fallback instanceof Map<?, ?> section) {
            ModelConfigHolder.updateFallback(text(section.get("url")), text(section.get("key")), text(section.get("name")));
            javaChanged = true;
        }
        if (javaChanged) {
            LlmClient.reset();
        }

        Object limits = root.get("limits");
        if (limits instanceof Map<?, ?> section) {
            Double qps = num(section.get("qps"));
            if (qps != null) SafeModel.setMaxQps(qps);
            Integer inflight = intVal(section.get("maxInflight"));
            if (inflight != null) SafeModel.setMaxInflight(inflight); // 在途并发上限热更
            Integer retries = intVal(section.get("maxRetries"));
            if (retries != null) SafeModel.setMaxRetries(retries);
            Long retryBase = longVal(section.get("retryBaseMs"));
            if (retryBase != null) SafeModel.setRetryBaseMs(retryBase);
            Integer breakerThreshold = intVal(section.get("breakerFailureThreshold"));
            if (breakerThreshold != null) CircuitBreaker.setFailureThreshold(breakerThreshold);
            Long breakerWindow = longVal(section.get("breakerWindowMs"));
            if (breakerWindow != null) CircuitBreaker.setWindowMs(breakerWindow);
            Long breakerCoolDown = longVal(section.get("breakerCoolDownMs"));
            if (breakerCoolDown != null) CircuitBreaker.setCoolDownMs(breakerCoolDown);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer intVal(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Long longVal(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static ConfigService createService() throws NacosException {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr());
        String namespace = Config.get("NACOS_NAMESPACE", "");
        if (!namespace.isBlank()) properties.put(PropertyKeyConst.NAMESPACE, namespace);
        String username = Config.get("NACOS_USERNAME", "");
        if (!username.isBlank()) properties.put(PropertyKeyConst.USERNAME, username);
        String password = Config.get("NACOS_PASSWORD", "");
        if (!password.isBlank()) properties.put(PropertyKeyConst.PASSWORD, password);
        return NacosFactory.createConfigService(properties);
    }
}
