package managerAgent.tool;

/**
 * 密钥脱敏：配置回显时只保留头尾字符。
 */
public final class SecretMasker {

    private SecretMasker() {
    }

    /** 脱敏后用于回显 */
    public static String mask(String k) {        return (k == null || k.length() < 10) ? "" : k.substring(0, 6) + "****" + k.substring(k.length() - 4);
    
    }
}
