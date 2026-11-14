package utils;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;

import java.util.Properties;

/** Nacos 客户端工具类 */
public class NacosUtil {  // 类声明：Nacos 客户端工具

    private static volatile AiService aiService; // 单例（避免反复创建连接泄漏）  // aiService：Nacos AI 服务单例（避免反复建连泄漏）
    /**
     * 作用：获取 Nacos AI/注册中心客户端（进程内单例）
     * 参数：无
     * 返回值：AiService 客户端
     * 异常：NacosException 连接或鉴权失败时抛出
     */

    public static AiService getNacosClient() throws NacosException {  // getNacosClient：取 Nacos 客户端单例
        if (aiService == null) {  // 还没创建
            synchronized (NacosUtil.class) { // 双重检查锁：只初始化一次  // 类级锁，双重检查
                if (aiService == null) {  // 锁内再判一次
                    Properties properties = new Properties(); // 设置 Nacos 地址  // properties：Nacos 连接参数
                    properties.put(PropertyKeyConst.SERVER_ADDR,  // 设置服务地址（
                            utils.AgentEndpoints.nacosAddr()); // ★2026-09-11 端点集中配置  // 续：从 AgentEndpoints 集中配置取）
                    String namespace = utils.Config.get("NACOS_NAMESPACE", "");  // namespace：命名空间
                    if (!namespace.isBlank()) {  // 配了命名空间
                        properties.put(PropertyKeyConst.NAMESPACE, namespace);  // 写入命名空间
                    }  // if 结束
                    String username = utils.Config.get("NACOS_USERNAME", "");  // username：鉴权用户名
                    if (!username.isBlank()) properties.put(PropertyKeyConst.USERNAME, username);  // 配了才写入
                    String password = utils.Config.get("NACOS_PASSWORD", "");  // password：鉴权密码
                    if (!password.isBlank()) properties.put(PropertyKeyConst.PASSWORD, password);  // 配了才写入
                    aiService = AiFactory.createAiService(properties); // 创建 Nacos Client  // 创建 Nacos AI 客户端
                }  // if 结束
            }  // synchronized 结束
        }  // if 结束
        return aiService;  // 返回单例
    }  // 方法结束
}  // 类结束
