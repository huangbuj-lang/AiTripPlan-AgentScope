package utils;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 结构化 JSON 提取与解析工具（阶段三主线）。 */
public final class JsonUtils {  // 类声明：JSON 提取与解析工具
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private JsonUtils() {  // 私有构造：禁止 new
    }  // 构造器结束

    /** 复用单例 ObjectMapper（性能：避免反复 new） */
    private static final ObjectMapper MAPPER = new ObjectMapper();  // MAPPER：单例 ObjectMapper（避免反复 new）

    /**
     * 作用：从模型输出中抠出第一个完整的 JSON 对象（容忍 ```json 包裹与前后废话）
     * 参数：text 模型原始输出
     * 返回值：JSON 文本；找不到时返回空串
     * 异常：无
     */
    public static String extractJsonObject(String text) {  // extractJsonObject：从模型输出里抠第一个完整 JSON 对象
        if (text == null) {  // 入参为 null
            return null;  // 返回 null
        }  // if 结束
        int start = text.indexOf('{');  // 从第一个 { 开始
        if (start < 0) {  // 没有 {
            return null;  // 返回 null
        }  // if 结束
        int depth = 0;  // depth：括号深度
        boolean inString = false;  // inString：是否在字符串里（字符串内的括号不算）
        boolean escaped = false;  // escaped：上一个字符是否转义
        for (int i = start; i < text.length(); i++) {  // 从 { 处逐字符扫描
            char c = text.charAt(i);  // 当前字符
            if (inString) {  // 在字符串内
                if (escaped) {  // 上一字符是转义符
                    escaped = false;  // 清掉转义标志
                } else if (c == '\\') {  // 遇到反斜杠
                    escaped = true;  // 标记转义
                } else if (c == '"') {  // 遇到引号
                    inString = false;  // 字符串结束
                }  // if 结束
                continue;  // 字符串内不做括号计数
            }  // if 结束
            if (c == '"') {  // 遇到引号
                inString = true;  // 进入字符串
            } else if (c == '{') {  // 遇到 {
                depth++;  // 深度 +1
            } else if (c == '}') {  // 遇到 }
                depth--;  // 深度 -1
                if (depth == 0) {  // 回到 0 层，说明对象完整
                    return text.substring(start, i + 1);  // 返回这段子串
                }  // if 结束
            }  // if 结束
        }  // for 结束
        return null; // 括号未闭合  // 括号没闭合 → 返回 null
    }  // 方法结束

    /**
     * 作用：把 JSON 文本解析成指定类型
     * 参数：json JSON 文本；clazz 目标类型
     * 返回值：解析结果；失败时返回 null
     * 异常：无（解析异常内部捕获）
     */
    public static <T> T parse(String json, Class<T> clazz) {  // parse：JSON 文本转对象
        if (json == null || json.isBlank()) {  // 文本为空
            return null;  // 返回 null
        }  // if 结束
        try {  // try
            return MAPPER.readValue(json, clazz);  // 反序列化
        } catch (Exception e) {  // 捕获异常
            return null;  // 解析失败返回 null
        }  // catch 结束
    }  // catch 结束

    /**
     * 作用：先抠 JSON 再解析成指定类型（模型输出的标准处理入口）
     * 参数：text 模型原始输出；clazz 目标类型
     * 返回值：解析结果；失败时返回 null
     * 异常：无
     */
    public static <T> T extractAndParse(String text, Class<T> clazz) {  // extractAndParse：先抠 JSON 再解析（模型输出的标准入口）
        return parse(extractJsonObject(text), clazz);  // 两步合并
    }  // 方法结束

    /**
     * 作用：把对象序列化成 JSON 文本
     * 参数：obj 待序列化对象
     * 返回值：JSON 文本；失败时返回空串
     * 异常：无
     */
    public static String toJson(Object obj) {  // toJson：对象转 JSON 文本
        if (obj == null) {  // 对象为空
            return null;  // 返回 null
        }  // if 结束
        try {  // try
            return MAPPER.writeValueAsString(obj);  // 序列化
        } catch (Exception e) {  // 捕获异常
            return null;  // 失败返回 null
        }  // catch 结束
    }  // catch 结束
    /**
     * 作用：把 JSON 数组解析成字符串列表
     * 参数：json JSON 数组文本
     * 返回值：字符串列表；失败时为空列表
     * 异常：无
     */
    public static java.util.List<String> parseStringList(String json) {  // parseStringList：JSON 数组转字符串列表
        if (json == null || json.isBlank()) {  // 文本为空
            return null;  // 返回 null
        }  // if 结束
        try {  // try
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});  // 按 List<String> 类型反序列化
        } catch (Exception e) {  // 捕获异常
            return null;  // 失败返回 null
        }  // catch 结束
    }  // 方法结束
}  // 类结束
