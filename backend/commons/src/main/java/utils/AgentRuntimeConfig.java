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

/** Nacos-driven runtime switches and resources shared by Java agents. */
@Slf4j  // @Slf4j：注入日志对象
public final class AgentRuntimeConfig {  // 类声明：Nacos 驱动的 Agent 运行时配置（工具开关/服务地址/凭据）

    public static final String DATA_ID = "aitripplan-agent-config.json";  // DATA_ID：配置在 Nacos 上的唯一标识
    public static final String GROUP = Config.get("NACOS_MODEL_CONFIG_GROUP", "DEFAULT_GROUP");  // GROUP：配置分组（可配）

    private static final ObjectMapper JSON = new ObjectMapper();  // JSON：ObjectMapper（解析配置用）
    private static final AtomicBoolean STARTED = new AtomicBoolean();  // STARTED：是否已启动监听（保证一次）
    private static volatile ConfigService configService;  // configService：Nacos 配置客户端
    private static volatile Map<String, Object> latestConfig;  // latestConfig：最新配置快照（volatile 整体替换）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private AgentRuntimeConfig() {}  // 私有构造：禁止 new
    /**
     * 作用：读取 Nacos 服务地址配置
     * 参数：无
     * 返回值：Nacos 地址字符串
     * 异常：无
     */

    public static String serverAddr() {  // serverAddr：读 Nacos 地址
        return AgentEndpoints.nacosAddr(); // ★2026-09-11 端点集中配置（原硬编码 localhost:8848）  // 从端点集中配置取（不再硬编码）
    }  // 方法结束

    /**
     * 作用：等待首份 Agent 运行时配置加载完成（供启动阶段阻塞使用）
     * 参数：timeoutMs 最长等待毫秒数
     * 返回值：true 在超时前成功加载；false 超时或加载失败
     * 异常：无（InterruptedException 内部处理并返回 false）
     */
    public static boolean awaitLoaded(long timeoutMs) {  // awaitLoaded：等首份配置加载完成
        long deadline = System.currentTimeMillis() + timeoutMs;  // deadline：最晚等到什么时候
        while (!loaded() && System.currentTimeMillis() < deadline) {  // 未加载且未超时就继续等
            try {  // try
                Thread.sleep(50L);  // 睡 50 毫秒再查
            } catch (InterruptedException e) {  // 捕获中断
                Thread.currentThread().interrupt();  // 恢复中断标志
                return false;  // 返回 false
            }  // catch 结束
        }  // while 结束
        return loaded();  // 返回最终是否加载成功
    }  // 方法结束
    /**
     * 作用：启动 Agent 运行时配置监听：只执行一次，异步注册监听并拉取首份配置
     * 参数：无
     * 返回值：无
     * 异常：无（异常内部记录日志）
     */

    public static void start() {  // start：启动配置监听（进程内只执行一次）
        if (!STARTED.compareAndSet(false, true)) return;  // CAS 抢锁，已启动过直接返回
        Thread thread = new Thread(AgentRuntimeConfig::connectAndListen,  // 后台线程注册监听
                "nacos-agent-runtime-config-loader");  // 续：线程名 nacos-agent-runtime-config-loader
        thread.setDaemon(true);  // 设为守护线程
        thread.start();  // 启动
    }  // 方法结束
    /**
     * 作用：建立 Nacos 配置客户端、注册监听并主动拉取一次配置
     * 参数：无
     * 返回值：无
     * 异常：无（异常内部捕获）
     */

    private static void connectAndListen() {  // connectAndListen：连接 Nacos 并注册监听（失败重试）
        while (!Thread.currentThread().isInterrupted()) {  // 未中断就一直重试
            try {  // try
                ConfigService service = createService();  // 创建配置客户端
                String content = service.getConfig(DATA_ID, GROUP, 5_000L);  // 主动拉一次当前配置（超时 5 秒）
                if (content != null && !content.isBlank()) {  // 有内容
                    applyConfig(content);  // 应用到内存
                }  // if 结束
                service.addListener(DATA_ID, GROUP, new com.alibaba.nacos.api.config.listener.Listener() {  // 注册变更监听
                    /**
                     * 作用：提供 Nacos 回调线程池（单线程，保证配置应用顺序）
                     * 参数：无
                     * 返回值：Executor
                     * 异常：无
                     */
                    @Override  // @Override 提供回调线程池
                    public Executor getExecutor() {  // getExecutor：
                        return null;  // 返回 null 用 Nacos 默认线程池（单线程保序）
                    }  // 方法结束
                    /**
                     * 作用：Nacos 配置变更回调：解析新的 JSON 并热更工具开关/服务地址/凭据
                     * 参数：configInfo 变更后的配置文本（JSON）
                     * 返回值：无
                     * 异常：无（解析失败只记日志，保留旧配置）
                     */
                    @Override  // @Override 配置变更回调
                    public void receiveConfigInfo(String configInfo) {  // receiveConfigInfo：Nacos 推送新配置时调用
                        try {  // try
                            applyConfig(configInfo);  // 解析并整体替换配置
                            log.info("Nacos agent runtime config updated");  // info 日志：已更新
                        } catch (Exception e) {  // 捕获异常
                            log.warn("Invalid Nacos agent runtime config: {}", e.getMessage());  // warn：配置非法，保留旧配置
                        }  // catch 结束
                    }  // 方法结束
                });  // 监听器注册结束
                configService = service;  // 保存客户端引用
                log.info("Nacos agent runtime config listener started: {}/{}", GROUP, DATA_ID);  // info 日志：监听已启动
                return;  // 成功即返回
            } catch (Exception e) {  // 捕获异常
                configService = null;  // 客户端置空
                log.warn("Nacos agent runtime config unavailable, retrying in 15s: {}", e.getMessage());  // warn：不可用，15 秒后重试
                try {  // try
                    Thread.sleep(15_000L);  // 睡 15 秒
                } catch (InterruptedException ie) {  // 捕获中断
                    Thread.currentThread().interrupt();  // 恢复中断标志
                    return;  // 直接返回
                }  // catch 结束
            }  // catch 结束
        }  // while 结束
    }  // 方法结束
    /**
     * 作用：读取工具开关域配置
     * 参数：无
     * 返回值：该域配置 Map
     * 异常：无
     */

    public static Map<String, Object> toolsConfig() {  // toolsConfig：读工具开关域
        return section("tools");  // 交给 section("tools")
    }  // 方法结束
    /**
     * 作用：读取服务地址域配置（子 Agent / 外部服务 URL）
     * 参数：无
     * 返回值：该域配置 Map
     * 异常：无
     */

    public static Map<String, Object> servicesConfig() {  // servicesConfig：读服务地址域
        return section("services");  // 交给 section("services")
    }  // 方法结束
    /**
     * 作用：读取凭据域配置（第三方服务密钥）
     * 参数：无
     * 返回值：该域配置 Map
     * 异常：无
     */

    public static Map<String, Object> credentialsConfig() {  // credentialsConfig：读凭据域
        return section("credentials");  // 交给 section("credentials")
    }  // 方法结束

    /**
     * 作用：读取当前生效的完整配置快照
     * 参数：无
     * 返回值：配置 Map（可能为空 Map，不会返回 null）
     * 异常：无
     */
    public static Map<String, Object> currentConfig() {  // currentConfig：读当前配置快照
        Map<String, Object> root = latestConfig;  // 取 volatile 快照
        return root == null ? new LinkedHashMap<>() : new LinkedHashMap<>(root);  // null 返回空 Map，否则返回副本
    }  // 方法结束
    /**
     * 作用：判断首份配置是否已加载完成
     * 参数：无
     * 返回值：true=已加载
     * 异常：无
     */

    public static boolean loaded() {  // loaded：首份配置是否已加载
        return latestConfig != null;  // 快照非 null 即已加载
    }  // 方法结束

    /**
     * 作用：查询某个工具的开关状态
     * 参数：name 工具键名；defaultValue 配置缺失时的默认值
     * 返回值：true=允许调用；false=已禁用
     * 异常：无
     */
    public static boolean toolEnabled(String name, boolean defaultValue) {  // toolEnabled：查某工具是否启用
        Map<String, Object> tools = toolsConfig();  // 取工具域配置
        if (!tools.containsKey(name)) return defaultValue;  // 配置里没写就用默认值
        return bool(tools.get(name), defaultValue);  // 写了就按值解析成布尔
    }  // 方法结束
    /**
     * 作用：查询某个服务的地址
     * 参数：name 服务键名；defaultValue 配置缺失时的默认地址
     * 返回值：服务 URL 字符串
     * 异常：无
     */

    public static String serviceUrl(String name, String defaultValue) {  // serviceUrl：查某服务地址
        String value = text(servicesConfig().get(name));  // 取配置值
        return value == null || value.isBlank() ? defaultValue : value.trim();  // 空则用默认值
    }  // 方法结束
    /**
     * 作用：查询某个第三方服务的凭据
     * 参数：name 凭据键名；defaultValue 配置缺失时的默认值
     * 返回值：凭据字符串
     * 异常：无
     */

    public static String credential(String name, String defaultValue) {  // credential：查某凭据
        String value = text(credentialsConfig().get(name));  // 取配置值
        return value == null || value.isBlank() ? defaultValue : value.trim();  // 空则用默认值
    }  // 方法结束

    /**
     * 作用：把完整运行时配置 JSON 发布到 Nacos 供各进程热更
     * 参数：config 待发布的配置 Map
     * 返回值：无
     * 异常：Exception 发布失败时向上抛出
     */
    public static void publish(Map<String, Object> config) throws Exception {  // publish：把配置发布到 Nacos（管理端保存时用）
        ConfigService service = configService;  // 取已有客户端
        if (service == null) service = createService();  // 没有就新建
        if (latestConfig == null) {  // 内存还没有配置
            String remote = service.getConfig(DATA_ID, GROUP, 5_000L);  // 先拉一次远端
            if (remote != null && !remote.isBlank()) applyConfig(remote);  // 有内容就应用
        }  // if 结束
        Map<String, Object> merged = new LinkedHashMap<>(currentConfig());  // merged：当前配置副本
        merged.putAll(config);  // 用新配置覆盖对应键
        String content = JSON.writeValueAsString(merged);  // 序列化成 JSON
        if (!service.publishConfig(DATA_ID, GROUP, content)) {  // 发布失败
            throw new IllegalStateException("Nacos publishConfig returned false");  // 抛异常
        }  // if 结束
        configService = service;  // 保存客户端
        applyConfig(content);  // 本地立即应用（不等回调）
    }  // 方法结束
    /**
     * 作用：把一份 JSON 配置应用到内存（工具开关、服务地址、凭据整体替换）
     * 参数：content 完整配置 JSON 文本
     * 返回值：无
     * 异常：Exception JSON 非法时抛出（调用方已捕获，保留旧配置）
     */

    public static void applyConfig(String content) throws Exception {  // applyConfig：把配置 JSON 应用到内存
        if (content == null || content.isBlank()) return;  // 内容为空直接返回
        Map<String, Object> root = JSON.readValue(content,  // 解析成 Map
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});  // 续：用 TypeReference 保留嵌套结构
        latestConfig = root;  // 整体替换快照（volatile 写，读方立即可见）
    }  // 方法结束
    /**
     * 作用：取出配置中的某个域，不存在或类型不符时返回空 Map
     * 参数：name 域名（tools/services/credentials）
     * 返回值：该域 Map，绝不返回 null
     * 异常：无
     */

    private static Map<String, Object> section(String name) {  // section：取配置中的某个域
        Map<String, Object> root = latestConfig;  // 当前快照
        if (root == null) return new LinkedHashMap<>();  // 没有配置返回空 Map
        if (!(root.get(name) instanceof Map<?, ?> raw)) return new LinkedHashMap<>();  // 该域不是 Map 也返回空 Map
        Map<String, Object> section = new LinkedHashMap<>();  // section：结果
        raw.forEach((key, value) -> {  // 逐键复制
            if (key != null) section.put(String.valueOf(key), value);  // 键非空才放
        });  // 写入（键统一转字符串）
        return section;  // forEach 结束
    }  // 返回该域副本
    /**
     * 作用：把配置值转成布尔，无法识别时用默认值
     * 参数：value 原始配置值；defaultValue 缺省值
     * 返回值：布尔结果
     * 异常：无
     */

    private static boolean bool(Object value, boolean defaultValue) {  // bool：配置值转布尔
        if (value instanceof Boolean b) return b;  // 本身就是布尔直接用
        if (value instanceof Number n) return n.intValue() != 0;  // 数字：非 0 为 true
        String text = text(value);  // 其它按文本解析
        if (text == null || text.isBlank()) return defaultValue;  // 文本为空用默认值
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "on".equalsIgnoreCase(text);  // true/1/on 都算启用
    }  // 方法结束
    /**
     * 作用：把配置值转成字符串，null 时返回空串
     * 参数：value 原始配置值
     * 返回值：去空白后的字符串
     * 异常：无
     */

    private static String text(Object value) {  // text：配置值转字符串
        return value == null ? null : String.valueOf(value);  // null 返回 null，否则 toString
    }  // 方法结束
    /**
     * 作用：创建 Nacos 配置客户端
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
        return NacosFactory.createConfigService(properties);  // 创建并返回客户端
    }  // 方法结束
}  // 类结束
