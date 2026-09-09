package utils;

/** 环境变量读取工具：带默认值，配置可配（可选覆盖，未设置时用默认值） */
public final class Config {

    private Config() {}

    /** 读取字符串配置（空/未设置 → 默认值） */
    public static String get(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    /** 读取 double 配置 */
    public static double getDouble(String key, double def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
    }

    /** 读取 int 配置 */
    public static int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    /** 读取 long 配置 */
    public static long getLong(String key, long def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return def; }
    }
}
