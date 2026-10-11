package utils;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;

import java.util.Properties;

/** Nacos 客户端工具类 */
public class NacosUtil {

    private static volatile AiService aiService; // 单例（避免反复创建连接泄漏）

    public static AiService getNacosClient() throws NacosException {
        if (aiService == null) {
            synchronized (NacosUtil.class) { // 双重检查锁：只初始化一次
                if (aiService == null) {
                    Properties properties = new Properties(); // 设置 Nacos 地址
                    properties.put(PropertyKeyConst.SERVER_ADDR,
                            utils.AgentEndpoints.nacosAddr()); // ★2026-09-11 端点集中配置
                    String namespace = utils.Config.get("NACOS_NAMESPACE", "");
                    if (!namespace.isBlank()) {
                        properties.put(PropertyKeyConst.NAMESPACE, namespace);
                    }
                    String username = utils.Config.get("NACOS_USERNAME", "");
                    if (!username.isBlank()) properties.put(PropertyKeyConst.USERNAME, username);
                    String password = utils.Config.get("NACOS_PASSWORD", "");
                    if (!password.isBlank()) properties.put(PropertyKeyConst.PASSWORD, password);
                    aiService = AiFactory.createAiService(properties); // 创建 Nacos Client
                }
            }
        }
        return aiService;
    }
}
