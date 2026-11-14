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
@Slf4j  // @Slf4j：注入日志对象
public final class NacosModelConfig {  // 类声明：Nacos 驱动的模型配置（Java 侧模型/限流/熔断/超时）

    public static final String DATA_ID = "aitripplan-model-config.json";  // DATA_ID：模型配置在 Nacos 上的唯一标识
    public static final String GROUP = Config.get("NACOS_MODEL_CONFIG_GROUP", "DEFAULT_GROUP");  // GROUP：配置分组（可配）

    private static final ObjectMapper JSON = new ObjectMapper();  // JSON：ObjectMapper
    private static final AtomicBoolean STARTED = new AtomicBoolean();  // STARTED：是否已启动监听
    private static volatile ConfigService configService;  // configService：Nacos 配置客户端
    private static volatile Map<String, Object> latestConfig;  // latestConfig：最新配置快照（volatile 整体替换）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private NacosModelConfig() {}  // 私有构造：禁止 new
    /**
     * 作用：读取 Nacos 服务地址配置
     * 参数：无
     * 返回值：Nacos 地址字符串（含命名空间/默认 localhost:8848）
     * 异常：无
     */

    public static String serverAddr() {  // serverAddr：读 Nacos 地址
        return AgentEndpoints.nacosAddr(); // ★2026-09-11 端点集中配置（原硬编码 localhost:8848）  // 从端点集中配置取
    }  // 方法结束

    /**
     * 作用：启动模型配置监听：只执行一次，异步向 Nacos 注册监听并拉取首份配置
     * 参数：无
     * 返回值：无
     * 异常：无（连接失败由内部线程记录日志，不影响进程启动）
     */
    public static void start() {  // start：启动模型配置监听（进程内只执行一次）
        if (!STARTED.compareAndSet(false, true)) return;  // CAS 抢锁，已启动过返回
        Thread thread = new Thread(NacosModelConfig::connectAndListen, "nacos-model-config-loader");  // 后台线程注册监听（线程名 nacos-model-config-loader）
        thread.setDaemon(true);  // 守护线程
        thread.start();  // 启动
    }  // 方法结束
    /**
     * 作用：建立 Nacos 配置客户端连接、注册监听并主动拉取一次当前配置
     * 参数：无
     * 返回值：无
     * 异常：无（异常内部捕获并打印）
     */

    private static void connectAndListen() {  // connectAndListen：连接 Nacos 并注册监听（失败重试）
        while (!Thread.currentThread().isInterrupted()) {  // 未中断就一直重试
            try {  // try
                ConfigService service = createService();  // 创建配置客户端
                String content = service.getConfig(DATA_ID, GROUP, 5_000L);  // 主动拉一次配置（超时 5 秒）
                if (content != null && !content.isBlank()) {  // 有内容
                    applyConfig(content);  // 应用配置（会立即热更模型/限流/熔断）
                }  // if 结束
                service.addListener(DATA_ID, GROUP, new com.alibaba.nacos.api.config.listener.Listener() {  // 注册变更监听
                    /**
                     * 作用：返回 Nacos 配置变更回调所用的线程池；本实现返回 null，表示交由 Nacos 客户端默认线程池执行回调
                     * 参数：无
                     * 返回值：Executor，恒为 null
                     * 异常：无
                     */
                    @Override  // @Override 提供回调线程池
                    public Executor getExecutor() {  // getExecutor：
                        return null;  // 返回 null 用默认线程池
                    }  // 方法结束
                    /**
                     * 作用：Nacos 配置变更回调：解析新的 JSON 配置并热更模型参数
                     * 参数：configInfo 变更后的配置文本（JSON）
                     * 返回值：无
                     * 异常：无（解析失败只记日志，保留上一份有效配置）
                     */
                    @Override  // @Override 配置变更回调
                    public void receiveConfigInfo(String configInfo) {  // receiveConfigInfo：Nacos 推送新配置
                        try {  // try
                            applyConfig(configInfo);  // 应用新配置
                            log.info("Nacos model config updated");  // info 日志
                        } catch (Exception e) {  // 捕获异常
                            log.warn("Invalid Nacos model config: {}", e.getMessage());  // warn：配置非法，保留旧值
                        }  // catch 结束
                    }  // 方法结束
                });  // 监听注册结束
                configService = service;  // 保存客户端
                log.info("Nacos model config listener started: {}/{}", GROUP, DATA_ID);  // info 日志：监听已启动
                return;  // 成功返回
            } catch (Exception e) {  // 捕获异常
                configService = null;  // 客户端置空
                log.warn("Nacos model config unavailable, retrying in 15s: {}", e.getMessage());  // warn：不可用，15 秒后重试
                try {  // try
                    Thread.sleep(15_000L);  // 睡 15 秒
                } catch (InterruptedException ie) {  // 捕获中断
                    Thread.currentThread().interrupt();  // 恢复中断标志
                    return;  // 返回
                }  // catch 结束
            }  // catch 结束
        }  // while 结束
    }  // 方法结束
    /**
     * 作用：把完整配置 JSON 发布到 Nacos，供各进程监听生效
     * 参数：config 待发布的配置 Map（主/备模型、编排、超时等）
     * 返回值：无
     * 异常：Exception 发布失败（网络/权限）时向上抛出由调用方提示
     */

    public static void publish(Map<String, Object> config) throws Exception {  // publish：把配置发布到 Nacos（管理端保存时用）
        String content = JSON.writeValueAsString(config);  // 序列化成 JSON
        ConfigService service = configService;  // 取现有客户端
        if (service == null) service = createService();  // 没有就新建
        if (!service.publishConfig(DATA_ID, GROUP, content)) {  // 发布失败
            throw new IllegalStateException("Nacos publishConfig returned false");  // 抛异常
        }  // if 结束
        configService = service;  // 保存客户端
    }  // 方法结束
    /**
     * 作用：读取 java 域的模型配置（主/备模型的 URL、Key、名称等）
     * 参数：无
     * 返回值：该域配置 Map，未配置时为空 Map
     * 异常：无
     */

    public static Map<String, Object> javaConfig() {  // javaConfig：导出主模型配置（管理端回显）
        Map<String, Object> java = new LinkedHashMap<>();  // java：结果 Map
        java.put("url", ModelConfigHolder.baseUrl());  // 接口地址
        java.put("key", ModelConfigHolder.apiKey());  // API Key
        java.put("name", ModelConfigHolder.modelName());  // 模型名
        return java;  // 返回
    }  // 方法结束
    /**
     * 作用：读取备用模型（熔断降级用）配置
     * 参数：无
     * 返回值：备用模型配置 Map
     * 异常：无
     */

    public static Map<String, Object> fallbackConfig() {  // fallbackConfig：导出备用模型配置
        Map<String, Object> fallback = new LinkedHashMap<>();  // fallback：结果 Map
        fallback.put("url", ModelConfigHolder.fbBaseUrl());  // 接口地址
        fallback.put("key", ModelConfigHolder.fbApiKey());  // API Key
        fallback.put("name", ModelConfigHolder.fbModelName());  // 模型名
        return fallback;  // 返回
    }  // 方法结束

    /**
     * 作用：读取数据管线（python 侧）使用的模型配置
     * 参数：无
     * 返回值：该域配置 Map
     * 异常：无
     */
    public static Map<String, Object> pythonConfig() {  // pythonConfig：读配置里的 python 域（数据管线用）
        Map<String, Object> root = latestConfig;  // 当前快照
        if (root == null) return null;  // 没有配置返回 null
        if (!(root.get("python") instanceof Map<?, ?> section)) return new LinkedHashMap<>();  // 该域不是 Map 返回空 Map
        Map<String, Object> python = new LinkedHashMap<>();  // python：结果
        section.forEach((key, value) -> {  // 逐键复制
            if (key != null) python.put(String.valueOf(key), value);  // 键非空才放
        });  // 写入（键转字符串）
        return python;  // forEach 结束
    }  // 返回

    /**
     * 作用：读取编排配置（并行开关、A2A 超时等）
     * 参数：无
     * 返回值：编排配置 Map
     * 异常：无
     */
    public static Map<String, Object> orchestrationConfig() {  // orchestrationConfig：读编排域（并发上限等）
        Map<String, Object> root = latestConfig;  // 当前快照
        if (root == null) return null;  // 没有配置返回 null（调用方据此判断是否配了）
        if (!(root.get("orchestration") instanceof Map<?, ?> section)) return new LinkedHashMap<>();  // 该域不是 Map 返回空 Map
        Map<String, Object> orchestration = new LinkedHashMap<>();  // orchestration：结果
        section.forEach((key, value) -> {  // 逐键复制
            if (key != null) orchestration.put(String.valueOf(key), value);  // 键非空才放
        });  // 写入
        return orchestration;  // forEach 结束
    }  // 返回

    /**
     * 作用：读取各场景超时配置（defaultSeconds 等）
     * 参数：无
     * 返回值：超时配置 Map
     * 异常：无
     */
    public static Map<String, Object> timeoutsConfig() {  // timeoutsConfig：读超时域
        Map<String, Object> root = latestConfig;  // 当前快照
        if (root == null) return new LinkedHashMap<>();  // 没有配置返回空 Map
        if (!(root.get("timeouts") instanceof Map<?, ?> section)) return new LinkedHashMap<>();  // 该域不是 Map 返回空 Map
        Map<String, Object> timeouts = new LinkedHashMap<>();  // timeouts：结果
        section.forEach((key, value) -> {  // 逐键复制
            if (key != null) timeouts.put(String.valueOf(key), value);  // 键非空才放
        });  // 写入
        return timeouts;  // forEach 结束
    }  // 返回
    /**
     * 作用：把一份 JSON 配置应用到内存：模型参数写 ModelConfigHolder，限速/重试/熔断参数同步到 SafeModel 与 CircuitBreaker
     * 参数：content 完整配置 JSON 文本
     * 返回值：无
     * 异常：Exception JSON 非法或字段类型不符时抛出（调用方已捕获，保留旧配置）
     */

    static void applyConfig(String content) throws Exception {  // applyConfig：应用配置（整体替换 + 立即热更各组件）
        if (content == null || content.isBlank()) return;  // 内容为空返回
        Map<String, Object> root = JSON.readValue(content,  // 解析成 Map
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});  // 续：用 TypeReference 保留嵌套结构
        latestConfig = root;  // 整体替换快照

        boolean javaChanged = false;  // javaChanged：主/备模型是否变了
        Object java = root.get("java");  // 取 java 域
        if (java instanceof Map<?, ?> section) {  // 是 Map
            ModelConfigHolder.update(text(section.get("url")), text(section.get("key")), text(section.get("name")));  // 热更主模型 URL/Key/名称（空值不覆盖）
            javaChanged = true;  // 标记有变化
        }  // if 结束
        Object fallback = root.get("fallback");  // 取 fallback 域
        if (fallback instanceof Map<?, ?> section) {  // 是 Map
            ModelConfigHolder.updateFallback(text(section.get("url")), text(section.get("key")), text(section.get("name")));  // 热更备用模型配置
            javaChanged = true;  // 标记有变化
        }  // if 结束
        if (javaChanged) {  // 模型配置变过
            LlmClient.reset();  // 丢弃 LlmClient 缓存的模型实例，下次调用重建
        }  // if 结束

        Object limits = root.get("limits");  // 取 limits 域（限流/熔断参数）
        if (limits instanceof Map<?, ?> section) {  // 是 Map
            Double qps = num(section.get("qps"));  // qps：每秒令牌数
            if (qps != null) SafeModel.setMaxQps(qps);  // 有值就热更 SafeModel 限速
            Integer inflight = intVal(section.get("maxInflight"));  // maxInflight：在途并发上限
            if (inflight != null) SafeModel.setMaxInflight(inflight); // 在途并发上限热更  // 有值就热更
            Integer retries = intVal(section.get("maxRetries"));  // maxRetries：最大重试次数
            if (retries != null) SafeModel.setMaxRetries(retries);  // 有值就热更
            Long retryBase = longVal(section.get("retryBaseMs"));  // retryBaseMs：重试基础间隔
            if (retryBase != null) SafeModel.setRetryBaseMs(retryBase);  // 有值就热更
            Integer breakerThreshold = intVal(section.get("breakerFailureThreshold"));  // breakerFailureThreshold：熔断失败阈值
            if (breakerThreshold != null) CircuitBreaker.setFailureThreshold(breakerThreshold);  // 有值就热更熔断器
            Long breakerWindow = longVal(section.get("breakerWindowMs"));  // breakerWindowMs：熔断统计窗口
            if (breakerWindow != null) CircuitBreaker.setWindowMs(breakerWindow);  // 有值就热更
            Long breakerCoolDown = longVal(section.get("breakerCoolDownMs"));  // breakerCoolDownMs：熔断冷却时长
            if (breakerCoolDown != null) CircuitBreaker.setCoolDownMs(breakerCoolDown);  // 有值就热更
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：把配置值转成字符串，null 时返回空串
     * 参数：value 原始配置值
     * 返回值：去空白后的字符串
     * 异常：无
     */

    private static String text(Object value) {  // text：配置值转字符串
        return value == null ? null : String.valueOf(value);  // null 返回 null
    }  // 方法结束
    /**
     * 作用：把配置值转成 Double，无法解析时返回 null
     * 参数：v 原始配置值
     * 返回值：Double 或 null
     * 异常：无
     */

    private static Double num(Object v) {  // num：配置值转 Double
        return v instanceof Number n ? n.doubleValue() : null;  // 是数字才转，否则 null
    }  // 方法结束
    /**
     * 作用：把配置值转成 Integer，无法解析时返回 null
     * 参数：v 原始配置值
     * 返回值：Integer 或 null
     * 异常：无
     */

    private static Integer intVal(Object v) {  // intVal：配置值转 Integer
        return v instanceof Number n ? n.intValue() : null;  // 是数字才转，否则 null
    }  // 方法结束
    /**
     * 作用：把配置值转成 Long，无法解析时返回 null
     * 参数：v 原始配置值
     * 返回值：Long 或 null
     * 异常：无
     */

    private static Long longVal(Object v) {  // longVal：配置值转 Long
        return v instanceof Number n ? n.longValue() : null;  // 是数字才转，否则 null
    }  // 方法结束
    /**
     * 作用：创建 Nacos 配置客户端（含服务地址、命名空间、超时设置）
     * 参数：无
     * 返回值：ConfigService 实例
     * 异常：NacosException 连接或鉴权失败时抛出
     */

    private static ConfigService createService() throws NacosException {  // createService：创建 Nacos 配置客户端
        Properties properties = new Properties();  // properties：连接参数
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr());  // 服务地址
        String namespace = Config.get("NACOS_NAMESPACE", "");  // 命名空间
        if (!namespace.isBlank()) properties.put(PropertyKeyConst.NAMESPACE, namespace);  // 配了才写入
        String username = Config.get("NACOS_USERNAME", "");  // 用户名
        if (!username.isBlank()) properties.put(PropertyKeyConst.USERNAME, username);  // 配了才写入
        String password = Config.get("NACOS_PASSWORD", "");  // 密码
        if (!password.isBlank()) properties.put(PropertyKeyConst.PASSWORD, password);  // 配了才写入
        return NacosFactory.createConfigService(properties);  // 创建并返回
    }  // 方法结束
}  // 类结束
