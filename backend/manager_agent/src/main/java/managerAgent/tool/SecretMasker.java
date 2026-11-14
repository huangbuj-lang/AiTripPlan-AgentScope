package managerAgent.tool;

/**
 * 密钥脱敏：配置回显时只保留头尾字符。
 */
public final class SecretMasker {  // 类声明：密钥脱敏工具
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private SecretMasker() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：对密钥做脱敏展示（保留前 6 位）
     * 参数：k 原始密钥
     * 返回值：脱敏字符串；过短或为空返回空串
     * 异常：无
     */
    public static String mask(String k) {        return (k == null || k.length() < 10) ? "" : k.substring(0, 6) + "****" + k.substring(k.length() - 4);  // mask：null 或短于 10 位返回空串（太短无法脱敏，直接不展示）；否则保留前 6 位 + **** + 后 4 位
    
    }  // 方法结束
}  // 类结束

