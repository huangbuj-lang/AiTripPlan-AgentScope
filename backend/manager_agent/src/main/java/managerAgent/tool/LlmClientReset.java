package managerAgent.tool;

import utils.LlmClient;
import utils.ModelConfigHolder;

/** 模型热更辅助：管理端保存配置后调用 reset()， 让 LlmClient 丢弃缓存的旧模型实例，下次调用用新配置重建。 */
public final class LlmClientReset {  // 类声明：模型热更辅助（工具类，不允许实例化）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private LlmClientReset() {}  // 私有构造：禁止 new
    /**
     * 作用：丢弃 LlmClient 缓存的模型实例，使下次调用按最新配置重建（管理端保存配置后调用）
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    public static void reset() {  // reset 签名：重置模型客户端
        LlmClient.reset();  // 调 LlmClient.reset() 丢弃缓存的旧模型实例，下次调用按新配置重建
    }  // 方法结束
}  // 类结束

