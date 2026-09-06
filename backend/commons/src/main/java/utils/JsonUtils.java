package utils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化 JSON 提取与解析工具（阶段三主线）。
 * LLM 输出的 JSON 常裹在 markdown 代码围栏或前后缀文本里，这里做鲁棒提取。
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /** 复用单例 ObjectMapper（性能：避免反复 new）；★容忍 LLM 输出的未知字段——
     *  实证教训（stage-test-001）：行程Agent 在 section 里多写了 "weather" 字段，
     *  Jackson 默认 FAIL_ON_UNKNOWN_PROPERTIES=true 使整个终版解析抛 UnrecognizedPropertyException → 降级原文兜底。
     *  契约外字段应该被忽略而不是让整份行程作废。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 从自由文本中提取首个完整 JSON 对象（容忍 ``` 围栏与前后缀）。
     * 用括号配对 + 字符串内花括号跳过，找到与首个 '{' 配对的 '}' 为止。
     */
    public static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // 括号未闭合
    }

    /**
     * 解析 JSON 到指定类型（失败返回 null，不抛异常）。
     */
    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取并解析：自由文本 → 类型化对象（失败返回 null）。
     */
    public static <T> T extractAndParse(String text, Class<T> clazz) {
        return parse(extractJsonObject(text), clazz);
    }

    /**
     * 序列化对象为 JSON 字符串（失败返回 null）。
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }
/**
     * 解析 JSON 数组字符串为字符串列表（失败返回 null）。
     * 用于 user_preferences 的 travel_style / avoid_list 等 JSON 列。
     */
    public static java.util.List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
