package utils;

import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Subscribes named prompts from Nacos Prompt management. */
@Slf4j  // @Slf4j：注入日志对象
public final class AgentPromptStore {  // 类声明：从 Nacos Prompt 管理订阅命名提示词

    private static final Map<String, String> PROMPTS = new ConcurrentHashMap<>();  // PROMPTS：Nacos 定制值缓存（键含前缀）
    private static final Map<String, AbstractNacosPromptListener> LISTENERS = new ConcurrentHashMap<>();  // LISTENERS：已注册的监听器（避免重复订阅）
    private static final AtomicBoolean STARTED = new AtomicBoolean();  // STARTED：是否已启动（保证只启动一次）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private AgentPromptStore() {}  // 私有构造：禁止 new
    /**
     * 作用：启动提示词热更：从 Nacos 拉取全部提示词并订阅变更（只执行一次）
     * 参数：无
     * 返回值：无
     * 异常：无（失败只记日志，回退内置默认提示词）
     */

    public static void start() {  // start：启动提示词热更（进程内只执行一次）
        if (!STARTED.compareAndSet(false, true)) return;  // CAS 抢锁，已启动过直接返回
        Set<String> names = Set.copyOf(AgentPrompts.defaults().keySet());  // names：全部内置提示词键
        Thread thread = new Thread(() -> connectAndSubscribe(names), "nacos-agent-prompt-loader");  // 后台线程加载，不阻塞启动
        thread.setDaemon(true);  // 设为守护线程
        thread.start();  // 启动
    }  // 方法结束
    /**
     * 作用：判断是否已从 Nacos 加载过提示词
     * 参数：无
     * 返回值：true=已加载
     * 异常：无
     */

    public static boolean loaded() {  // loaded：是否已从 Nacos 加载过
        return !PROMPTS.isEmpty();  // 缓存非空即已加载
    }  // 方法结束
    /**
     * 作用：判断某个提示词是否被 Nacos 定制过（与内置默认不同）
     * 参数：name 提示词键名
     * 返回值：true=已定制
     * 异常：无
     */

    public static boolean customized(String name) {  // customized：该提示词是否被 Nacos 定制过
        String value = PROMPTS.get(promptKey(name));  // 取缓存值（用带前缀的键）
        return value != null && !value.isBlank();  // 非空即已定制
    }  // 方法结束
    /**
     * 作用：返回当前提示词来源描述（内置/Nacos）
     * 参数：无
     * 返回值：来源标识字符串
     * 异常：无
     */

    public static String source() {  // source：当前提示词来源
        return PROMPTS.isEmpty() ? "default" : "nacos-prompt";  // 空=内置默认，否则 nacos-prompt
    }  // 方法结束
    /**
     * 作用：把提示词键名转成 Nacos 配置键
     * 参数：name 提示词键名
     * 返回值：Nacos 配置键字符串
     * 异常：无
     */

    public static String promptKey(String name) {  // promptKey：提示词键加 Nacos 前缀
        return Config.get("NACOS_PROMPT_PREFIX", "aitripplan.") + name;  // 前缀取配置 NACOS_PROMPT_PREFIX（默认 aitripplan.）
    }  // 方法结束
    /**
     * 作用：按优先级取提示词：Nacos 定制值 > 内置默认值 > 调用方给的兜底值
     * 参数：name 提示词键名；defaultValue 兜底文本
     * 返回值：最终使用的提示词文本
     * 异常：无
     */

    public static String prompt(String name, String defaultValue) {  // prompt：按优先级取提示词
        String value = PROMPTS.get(promptKey(name));  // 先查 Nacos 定制值
        return value == null || value.isBlank() ? defaultValue : value;  // 空则用调用方给的默认值
    }  // 方法结束
    /**
     * 作用：应用一份提示词（热更/测试用），空值表示清空定制回到默认
     * 参数：name 提示词键名；template 提示词模板文本，null 或空表示移除定制
     * 返回值：无
     * 异常：无
     */

    static void applyPrompt(String name, String template) {  // applyPrompt：写入/移除定制值
        if (name == null) return;  // 键为空返回
        if (template == null || template.isBlank()) {  // 值为空
            PROMPTS.remove(promptKey(name));  // 移除定制（回到默认）
        } else {  // 否则
            PROMPTS.put(promptKey(name), template);  // 写入定制值
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：清空内存中的提示词定制，回到内置默认（测试用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    static void resetForTest() {  // resetForTest：清空定制（测试用）
        PROMPTS.clear();  // 清空缓存
    }  // 方法结束
    /**
     * 作用：建立 Nacos 提示词客户端并为全部提示词注册监听
     * 参数：names 需要订阅的提示词键集合
     * 返回值：无
     * 异常：无（异常内部捕获）
     */

    private static void connectAndSubscribe(Set<String> names) {  // connectAndSubscribe：连接 Nacos 并订阅（失败重试）
        while (!Thread.currentThread().isInterrupted()) {  // 未中断就一直重试
            try {  // try
                subscribeAll(names);  // 执行订阅
                return;  // 成功就返回
            } catch (InterruptedException e) {  // 捕获中断
                Thread.currentThread().interrupt();  // 恢复中断标志
                return;  // 直接返回
            } catch (Exception e) {  // 捕获其他异常
                log.warn("Nacos prompt service unavailable, retrying in 15s: {}", e.getMessage());  // warn：Nacos 不可用，15 秒后重试
                sleepQuietly();  // 静默睡 15 秒
            }  // catch 结束
        }  // while 结束
    }  // 方法结束
    /**
     * 作用：逐个订阅提示词变更并拉取一次当前值
     * 参数：names 需要订阅的提示词键集合
     * 返回值：无
     * 异常：Exception 订阅或拉取失败时抛出（上层捕获后降级为默认提示词）
     */

    private static void subscribeAll(Set<String> names) throws Exception {  // subscribeAll：逐个订阅提示词
        var service = utils.NacosUtil.getNacosClient();  // 取 Nacos 客户端
        boolean failed = false;  // failed：是否有订阅失败

        for (String name : names) {  // 遍历提示词键
            if (LISTENERS.containsKey(name)) continue;  // 已订阅过就跳过
            try {  // try
                AbstractNacosPromptListener listener = new PromptListener(name);  // 为该键建监听器
                Prompt prompt = service.subscribePrompt(promptKey(name), null, null, listener);  // 订阅（返回当前值）
                apply(name, prompt);  // 把当前值写进缓存
                LISTENERS.put(name, listener);  // 记下监听器
                log.info("Subscribed Nacos prompt: {}", promptKey(name));  // info 日志：已订阅
            } catch (Exception e) {  // 捕获异常
                failed = true;  // 标记有失败
                log.warn("Unable to subscribe Nacos prompt {}: {}", promptKey(name), e.getMessage());  // warn：订阅失败
            }  // catch 结束
        }  // for 结束

        if (failed && LISTENERS.size() < names.size()) {  // 有失败且未全部订阅
            sleepQuietly();  // 睡 15 秒
            throw new IllegalStateException("Some Nacos prompts are still unsubscribed");  // 抛异常让上层重试
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：把 Nacos 返回的提示词对象写入内存缓存
     * 参数：name 提示词键名；prompt Nacos 提示词对象
     * 返回值：无
     * 异常：无
     */

    private static void apply(String name, Prompt prompt) {  // apply：把 Nacos 提示词对象写入缓存
        applyPrompt(name, prompt == null ? null : prompt.getTemplate());  // 取模板文本（对象为空则清空定制）
    }  // 方法结束
    /**
     * 作用：静默睡眠（重试等待），中断时恢复中断标志
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private static void sleepQuietly() {  // sleepQuietly：静默睡眠（重试等待）
        try {  // try
            Thread.sleep(15_000L);  // 睡 15 秒
        } catch (InterruptedException e) {  // 捕获中断
            Thread.currentThread().interrupt();  // 恢复中断标志
        }  // catch 结束
    }  // 方法结束

private static class PromptListener extends AbstractNacosPromptListener {  // 内部类 PromptListener：单条提示词的变更监听器
        private final String name;  // name：提示词键
        /**
         * 作用：构造单条提示词的 Nacos 监听器
         * 参数：name 提示词键名
         * 返回值：无
         * 异常：无
         */

        private PromptListener(String name) {  // 构造器
            this.name = name;  // 保存键
        }  // 构造器结束
        /**
         * 作用：提示词变更事件回调：把新版本写入内存缓存（失败只记日志）
         * 参数：event Nacos 提示词事件
         * 返回值：无
         * 异常：无
         */
        @Override  // @Override 覆写变更回调
        public void onEvent(NacosPromptEvent event) {  // onEvent：Nacos 推送新版本时触发
            try {  // try
                applyPrompt(name, event.getPrompt() == null ? null : event.getPrompt().getTemplate());  // 把新模板写进缓存（成功即热更生效）
            } catch (Exception e) {  // 捕获异常
                log.warn("Invalid Nacos prompt {}: {}", promptKey(name), e.getMessage());  // warn：模板非法
            }  // catch 结束
        }  // 方法结束
    }  // 内部类结束
}  // 类结束
