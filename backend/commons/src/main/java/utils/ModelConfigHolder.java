package utils;

/** 模型配置热更中心（Java 侧唯一模型配置来源）： - 管理端保存后调用 update() 原子更新，LlmClient/AgentUtils 下次构建模型即用新配置 - 初始值从 .env（MODEL_BASE_URL/MODEL_API_KEY/MODEL_NAME）加载 */
public final class ModelConfigHolder {  // 类声明：模型配置热更中心（Java 侧唯一模型配置来源）

    private static volatile String baseUrl = Config.get("MODEL_BASE_URL", "https://api.deepseek.com");  // baseUrl：主模型接口地址（默认 DeepSeek）
    private static volatile String apiKey  = Config.get("MODEL_API_KEY", Config.get("DEEPSEEK_API_KEY", ""));  // apiKey：主模型 Key（回退读 DEEPSEEK_API_KEY）
    private static volatile String modelName = Config.get("MODEL_NAME", "deepseek-chat");  // modelName：主模型名（默认 deepseek-chat）

    //备用模型配置（熔断降级用）：OpenAI 兼容接口，URL/KEY/NAME 全部可热更
    //默认 URL=阿里 DashScope 兼容模式（与原 DashScope qwen 行为等价），KEY 沿用 DASHSCOPE_API_KEY
    private static volatile String fbBaseUrl = Config.get("FALLBACK_MODEL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1");  // fbBaseUrl：备用模型地址（默认阿里 DashScope 兼容模式）
    private static volatile String fbApiKey  = Config.get("FALLBACK_MODEL_API_KEY", Config.get("DASHSCOPE_API_KEY", ""));  // fbApiKey：备用模型 Key（回退读 DASHSCOPE_API_KEY）
    private static volatile String fbModelName = Config.get("FALLBACK_MODEL_NAME", "qwen3.7-max");  // fbModelName：备用模型名（默认 qwen3.7-max）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ModelConfigHolder() {}  // 私有构造：禁止 new
    /**
     * 作用：读取主模型的 OpenAI 兼容接口地址（可被管理端热更）
     * 参数：无
     * 返回值：主模型 baseUrl 字符串
     * 异常：无
     */

    public static String baseUrl()   { return baseUrl; }  // baseUrl()：读主模型地址
    /**
     * 作用：读取主模型 API Key（可被管理端热更）
     * 参数：无
     * 返回值：主模型 API Key 字符串，未配置为空串
     * 异常：无
     */
    public static String apiKey()    { return apiKey; }  // apiKey()：读主模型 Key
    /**
     * 作用：读取主模型名称（可被管理端热更）
     * 参数：无
     * 返回值：主模型名称字符串
     * 异常：无
     */
    public static String modelName() { return modelName; }  // modelName()：读主模型名
    /**
     * 作用：读取备用模型的 OpenAI 兼容接口地址（熔断降级时使用，可热更）
     * 参数：无
     * 返回值：备用模型 baseUrl 字符串
     * 异常：无
     */

    public static String fbBaseUrl()   { return fbBaseUrl; }  // fbBaseUrl()：读备用模型地址
    /**
     * 作用：读取备用模型 API Key（熔断降级时使用，可热更）
     * 参数：无
     * 返回值：备用模型 API Key 字符串，未配置为空串
     * 异常：无
     */
    public static String fbApiKey()    { return fbApiKey; }  // fbApiKey()：读备用模型 Key
    /**
     * 作用：读取备用模型名称（熔断降级时使用，可热更）
     * 参数：无
     * 返回值：备用模型名称字符串
     * 异常：无
     */
    public static String fbModelName() { return fbModelName; }  // fbModelName()：读备用模型名

    /**
     * 作用：原子热更主模型配置：非空参数才覆盖，空值保持原配置不变
     * 参数：url 新的接口地址；key 新的 API Key；name 新的模型名称；三者允许为 null 或空串表示不修改该项
     * 返回值：无
     * 异常：无
     */
    public static void update(String url, String key, String name) {  // update：热更主模型配置
        if (url != null && !url.isBlank())  baseUrl = url.trim();  // 地址非空才覆盖
        if (key != null && !key.isBlank())  apiKey = key.trim();  // Key 非空才覆盖
        if (name != null && !name.isBlank()) modelName = name.trim();  // 模型名非空才覆盖
    }  // 方法结束

    /**
     * 作用：原子热更备用模型配置：非空参数才覆盖，空值保持原配置不变
     * 参数：url 备用模型接口地址；key 备用模型 API Key；name 备用模型名称；均允许为 null 或空串表示不修改该项
     * 返回值：无
     * 异常：无
     */
    public static void updateFallback(String url, String key, String name) {  // updateFallback：热更备用模型配置
        if (url != null && !url.isBlank())  fbBaseUrl = url.trim();  // 地址非空才覆盖
        if (key != null && !key.isBlank())  fbApiKey = key.trim();  // Key 非空才覆盖
        if (name != null && !name.isBlank()) fbModelName = name.trim();  // 模型名非空才覆盖
    }  // 方法结束
}  // 类结束
