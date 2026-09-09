package utils;

import lombok.extern.slf4j.Slf4j;

/** 成本维度标签工具：为每类业务入口打上 LLM 成本归属标签。 */
@Slf4j
public final class CostDimension {

    public static final String TASK = "task";
    public static final String PIPELINE = "pipeline";
    public static final String FORUM = "forum";

    private CostDimension() {}

        /** 在业务维度内执行一段逻辑（推荐用法：自动恢复默认，防串味）。 */
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
