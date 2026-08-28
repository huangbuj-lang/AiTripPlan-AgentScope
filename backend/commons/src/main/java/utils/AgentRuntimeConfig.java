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

/**
 * Nacos-driven runtime switches and resources shared by Java agents.
 * Nacos is the source of truth after the first snapshot arrives; absent
 * sections fall back to code defaults.
 */
@Slf4j
public final class AgentRuntimeConfig {

    public static final String DATA_ID = "aitripplan-agent-config.json";
    public static final String GROUP = Config.get("NACOS_MODEL_CONFIG_GROUP", "DEFAULT_GROUP");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile ConfigService configService;
    private static volatile Map<String, Object> latestConfig;

    private AgentRuntimeConfig() {}

    public static String serverAddr() {
        return Config.get("NACOS_SERVER_ADDR", "localhost:8848");
    }

    /** Briefly wait for the initial Nacos snapshot so restarted agents do not register with stale fallbacks. */
    public static boolean awaitLoaded(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!loaded() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return loaded();
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(AgentRuntimeConfig::connectAndListen,
                "nacos-agent-runtime-config-loader");
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
                            log.info("Nacos agent runtime config updated");
                        } catch (Exception e) {
                            log.warn("Invalid Nacos agent runtime config: {}", e.getMessage());
                        }
                    }
                });
                configService = service;
                log.info("Nacos agent runtime config listener started: {}/{}", GROUP, DATA_ID);
                return;
            } catch (Exception e) {
                configService = null;
                log.warn("Nacos agent runtime config unavailable, retrying in 15s: {}", e.getMessage());
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static Map<String, Object> toolsConfig() {
        return section("tools");
    }

    public static Map<String, Object> servicesConfig() {
        return section("services");
    }

    public static Map<String, Object> credentialsConfig() {
        return section("credentials");
    }

    /** Returns a shallow copy of the latest Nacos snapshot; empty when still bootstrapping. */
    public static Map<String, Object> currentConfig() {
        Map<String, Object> root = latestConfig;
        return root == null ? new LinkedHashMap<>() : new LinkedHashMap<>(root);
    }

    public static boolean loaded() {
        return latestConfig != null;
    }

    /** Returns the configured switch, or defaultValue when Nacos/section/key is absent. */
    public static boolean toolEnabled(String name, boolean defaultValue) {
        Map<String, Object> tools = toolsConfig();
        if (!tools.containsKey(name)) return defaultValue;
        return bool(tools.get(name), defaultValue);
    }

    public static String serviceUrl(String name, String defaultValue) {
        String value = text(servicesConfig().get(name));
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public static String credential(String name, String defaultValue) {
        String value = text(credentialsConfig().get(name));
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /** Publishes to Nacos and applies the same snapshot locally for immediate reads. */
    public static void publish(Map<String, Object> config) throws Exception {
        ConfigService service = configService;
        if (service == null) service = createService();
        if (latestConfig == null) {
            String remote = service.getConfig(DATA_ID, GROUP, 5_000L);
            if (remote != null && !remote.isBlank()) applyConfig(remote);
        }
        Map<String, Object> merged = new LinkedHashMap<>(currentConfig());
        merged.putAll(config);
        String content = JSON.writeValueAsString(merged);
        if (!service.publishConfig(DATA_ID, GROUP, content)) {
            throw new IllegalStateException("Nacos publishConfig returned false");
        }
        configService = service;
        applyConfig(content);
    }

    public static void applyConfig(String content) throws Exception {
        if (content == null || content.isBlank()) return;
        Map<String, Object> root = JSON.readValue(content,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        latestConfig = root;
    }

    private static Map<String, Object> section(String name) {
        Map<String, Object> root = latestConfig;
        if (root == null) return new LinkedHashMap<>();
        if (!(root.get(name) instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> section = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) section.put(String.valueOf(key), value);
        });
        return section;
    }

    private static boolean bool(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String text = text(value);
        if (text == null || text.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "on".equalsIgnoreCase(text);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
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
