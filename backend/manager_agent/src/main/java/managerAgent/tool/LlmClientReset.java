package managerAgent.tool;

import utils.LlmClient;
import utils.ModelConfigHolder;

/**
 * ★模型热更辅助：管理端保存配置后调用 reset()，
 * 让 LlmClient 丢弃缓存的旧模型实例，下次调用用新配置重建。
 */
public final class LlmClientReset {

    private LlmClientReset() {}

    public static void reset() {
        LlmClient.reset();
    }
}
