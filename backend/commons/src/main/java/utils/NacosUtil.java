package utils;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;

import java.util.Properties;

/**
 * Nacos 客户端工具类
 */
public class NacosUtil {

    public static AiService getNacosClient() throws NacosException {

        Properties properties = new Properties(); // 设置 Nacos 地址
        properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
        return AiFactory.createAiService(properties); // 创建 Nacos Client

    }
}
