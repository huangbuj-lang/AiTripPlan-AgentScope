package utils;

/** 环境变量读取工具：带默认值，配置可配（可选覆盖，未设置时用默认值） */
public final class Config {  // 类声明：配置读取工具（环境变量 + 默认值）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private Config() {}  // 私有构造：禁止 new

    /**
     * 作用：读取字符串配置：优先环境变量，其次 .env/配置项，都没有时返回默认值
     * 参数：key 配置键；def 默认值
     * 返回值：配置值字符串
     * 异常：无
     */
    public static String get(String key, String def) {  // get：读字符串配置
        String v = System.getenv(key);  // 先取同名环境变量
        return (v == null || v.isBlank()) ? def : v.trim();  // null 或空白就用默认值，否则 trim 后返回
    }  // 方法结束

    /**
     * 作用：读取 double 配置，格式不符时返回默认值
     * 参数：key 配置键；def 默认值
     * 返回值：配置数值
     * 异常：无（解析异常内部捕获）
     */
    public static double getDouble(String key, double def) {  // getDouble：读浮点配置
        String v = get(key, null);  // 先取字符串（不给默认值）
        if (v == null) return def;  // 没配就用默认
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }  // 解析成功返回，格式错也用默认
    }  // 方法结束

    /**
     * 作用：读取 int 配置，格式不符时返回默认值
     * 参数：key 配置键；def 默认值
     * 返回值：配置整数
     * 异常：无（解析异常内部捕获）
     */
    public static int getInt(String key, int def) {  // getInt：读整数配置
        String v = get(key, null);  // 先取字符串
        if (v == null) return def;  // 没配就用默认
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }  // 解析成功返回，格式错用默认
    }  // 方法结束

    /**
     * 作用：读取 long 配置，格式不符时返回默认值
     * 参数：key 配置键；def 默认值
     * 返回值：配置长整数
     * 异常：无（解析异常内部捕获）
     */
    public static long getLong(String key, long def) {  // getLong：读长整型配置
        String v = get(key, null);  // 先取字符串
        if (v == null) return def;  // 没配就用默认
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return def; }  // 解析成功返回，格式错用默认
    }  // 方法结束
}  // 类结束
