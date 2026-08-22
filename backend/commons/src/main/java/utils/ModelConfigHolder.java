package utils;

/**
 * ★模型配置热更中心（Java 侧唯一模型配置来源）：
 * - 管理端保存后调用 update() 原子更新，LlmClient/AgentUtils 下次构建模型即用新配置
 * - 初始值从 .env（MODEL_BASE_URL/MODEL_API_KEY/MODEL_NAME）加载
 */
public final class ModelConfigHolder {

    private static volatile String baseUrl = Config.get("MODEL_BASE_URL", "https://api.deepseek.com");
    private static volatile String apiKey  = Config.get("MODEL_API_KEY", Config.get("DEEPSEEK_API_KEY", ""));
    private static volatile String modelName = Config.get("MODEL_NAME", "deepseek-chat");

    private ModelConfigHolder() {}

    public static String baseUrl()   { return baseUrl; }
    public static String apiKey()    { return apiKey; }
    public static String modelName() { return modelName; }

    /** 原子热更（管理端保存时调用）；任一参数为空则忽略该项 */
    public static void update(String url, String key, String name) {
        if (url != null && !url.isBlank())  baseUrl = url.trim();
        if (key != null && !key.isBlank())  apiKey = key.trim();
        if (name != null && !name.isBlank()) modelName = name.trim();
    }
}
