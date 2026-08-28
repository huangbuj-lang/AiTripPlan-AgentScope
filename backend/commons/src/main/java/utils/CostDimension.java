package utils;

import lombok.extern.slf4j.Slf4j;

/**
 * ★成本维度标签工具：为每类业务入口打上 LLM 成本归属标签。
 *
 * 原理：SafeModel.DIMENSION 是 ThreadLocal，每次 LLM 记账取当前线程标签。
 * 入口处 set() → try/finally 里 finally 恢复 "task"，防止池化线程把标签带给下一个不相干请求。
 *
 * 现有维度：task(用户行程任务，默认) / pipeline(管理端数据管线) / forum(论坛AI答主)
 */
@Slf4j
public final class CostDimension {

    public static final String TASK = "task";
    public static final String PIPELINE = "pipeline";
    public static final String FORUM = "forum";

    private CostDimension() {}

    /**
     * 在业务维度内执行一段逻辑（推荐用法：自动恢复默认，防串味）。
     * 例：CostDimension.runAs(CostDimension.FORUM, () -> aiAnswerService.generate(...));
     */
    public static <T> T runAs(String dimension, java.util.function.Supplier<T> body) {
        String prev = SafeModel.getDimension();
        SafeModel.setDimension(dimension);
        try {
            return body.get();
        } finally {
            SafeModel.setDimension(prev); // 恢复进入前的值（池化线程安全）
        }
    }

    /** 无返回值版本 */
    public static void runAs(String dimension, Runnable body) {
        String prev = SafeModel.getDimension();
        SafeModel.setDimension(dimension);
        try {
            body.run();
        } finally {
            SafeModel.setDimension(prev);
        }
    }
}
