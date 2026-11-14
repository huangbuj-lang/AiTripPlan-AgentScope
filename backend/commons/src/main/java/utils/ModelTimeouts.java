package utils;

import java.time.Duration;
import java.util.Map;

/** Direct LLM call timeouts controlled by the Nacos model config. */
public final class ModelTimeouts {  // 类声明：直接 LLM 调用的超时配置（由 Nacos 模型配置驱动）
    public static final int MIN_SECONDS = 5;  // MIN_SECONDS：超时下限 5 秒
    public static final int MAX_SECONDS = 600;  // MAX_SECONDS：超时上限 600 秒

    private static final int DEFAULT_SECONDS = 120;  // DEFAULT_SECONDS：默认场景 120 秒
    private static final int MODERATION_SECONDS = 60;  // MODERATION_SECONDS：内容审核 60 秒
    private static final int AI_ANSWER_SECONDS = 120;  // AI_ANSWER_SECONDS：AI 问答 120 秒
    private static final int TRIP_EDIT_SECONDS = 120;  // TRIP_EDIT_SECONDS：改行程 120 秒
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ModelTimeouts() {}  // 私有构造：禁止 new
    /**
     * 作用：取默认场景（需求解析、聚合、提取等裸 LLM 调用）的超时时长
     * 参数：无
     * 返回值：Duration 超时时长，秒数来自 Nacos 模型配置 defaultSeconds（缺省 120s）
     * 异常：无
     */

    public static Duration defaultDuration() {  // defaultDuration：默认场景超时（Duration）
        return Duration.ofMillis(defaultMillis());  // 按毫秒构造
    }  // 方法结束
    /**
     * 作用：取默认场景超时的毫秒数，供阻塞式调用直接使用
     * 参数：无
     * 返回值：超时毫秒数（seconds 夹紧后 ×1000）
     * 异常：无
     */

    public static long defaultMillis() {  // defaultMillis：默认场景超时（毫秒）
        return seconds("defaultSeconds", DEFAULT_SECONDS) * 1000L;  // 秒数×1000
    }  // 方法结束
    /**
     * 作用：取内容审核场景的超时时长
     * 参数：无
     * 返回值：Duration 超时时长，秒数来自 moderationSeconds（缺省 60s）
     * 异常：无
     */

    public static Duration moderationDuration() {  // moderationDuration：审核场景超时
        return Duration.ofMillis(moderationMillis());  // 按毫秒构造
    }  // 方法结束
    /**
     * 作用：取内容审核场景超时的毫秒数
     * 参数：无
     * 返回值：超时毫秒数
     * 异常：无
     */

    public static long moderationMillis() {  // moderationMillis：审核场景超时（毫秒）
        return seconds("moderationSeconds", MODERATION_SECONDS) * 1000L;  // 秒数×1000
    }  // 方法结束
    /**
     * 作用：取 AI 问答场景的超时时长
     * 参数：无
     * 返回值：Duration 超时时长，秒数来自 aiAnswerSeconds（缺省 120s）
     * 异常：无
     */

    public static Duration aiAnswerDuration() {  // aiAnswerDuration：AI 问答超时
        return Duration.ofMillis(aiAnswerMillis());  // 按毫秒构造
    }  // 方法结束
    /**
     * 作用：取 AI 问答场景超时的毫秒数
     * 参数：无
     * 返回值：超时毫秒数
     * 异常：无
     */

    public static long aiAnswerMillis() {  // aiAnswerMillis：AI 问答超时（毫秒）
        return seconds("aiAnswerSeconds", AI_ANSWER_SECONDS) * 1000L;  // 秒数×1000
    }  // 方法结束
    /**
     * 作用：取对话式改行程场景的超时时长
     * 参数：无
     * 返回值：Duration 超时时长，秒数来自 tripEditSeconds（缺省 120s）
     * 异常：无
     */

    public static Duration tripEditDuration() {  // tripEditDuration：改行程超时
        return Duration.ofMillis(tripEditMillis());  // 按毫秒构造
    }  // 方法结束
    /**
     * 作用：取对话式改行程场景超时的毫秒数
     * 参数：无
     * 返回值：超时毫秒数
     * 异常：无
     */

    public static long tripEditMillis() {  // tripEditMillis：改行程超时（毫秒）
        return seconds("tripEditSeconds", TRIP_EDIT_SECONDS) * 1000L;  // 秒数×1000
    }  // 方法结束
    /**
     * 作用：从 Nacos 超时配置读取指定键的秒数，缺失或非数字时用默认值，并夹紧到合法区间
     * 参数：key Nacos 超时配置键名（如 defaultSeconds）；defaultValue 配置缺失时的默认秒数
     * 返回值：夹紧到 [MIN_SECONDS, MAX_SECONDS] 的秒数
     * 异常：无
     */

    private static int seconds(String key, int defaultValue) {  // seconds：读配置秒数并夹紧
        Map<String, Object> timeouts = NacosModelConfig.timeoutsConfig();  // 取 Nacos 超时配置快照
        return normalize(timeouts.get(key) instanceof Number number  // 配置里是数字就用它，
                ? number.intValue()  // 续：否则用默认值
                : defaultValue);  // 续：最后夹到合法区间
    }  // 方法结束
    /**
     * 作用：把任意秒数夹紧到 [MIN_SECONDS, MAX_SECONDS]
     * 参数：value 待校验的秒数
     * 返回值：夹紧后的合法秒数
     * 异常：无
     */

    private static int normalize(int value) {  // normalize：夹到 [5, 600] 秒
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));  // Math.max(MIN, Math.min(MAX, value))
    }  // 方法结束
}  // 类结束
