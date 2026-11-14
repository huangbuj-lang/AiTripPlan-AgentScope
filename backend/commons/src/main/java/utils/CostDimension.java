package utils;

import lombok.extern.slf4j.Slf4j;

/** 成本维度标签工具：为每类业务入口打上 LLM 成本归属标签。 */
@Slf4j  // @Slf4j：注入日志对象
public final class CostDimension {  // 类声明：LLM 成本归属标签工具（ThreadLocal 维度切换）

    public static final String TASK = "task";  // 常量 task：行程任务链路的成本归属
    public static final String PIPELINE = "pipeline";  // 常量 pipeline：数据管线的成本归属
    public static final String FORUM = "forum";  // 常量 forum：论坛 AI 回答的成本归属
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private CostDimension() {}  // 私有构造：禁止 new

    /**
     * 作用：在指定成本维度内执行一段逻辑，结束后自动恢复进入前的维度（池化线程安全）
     * 参数：dimension 成本维度（task/pipeline/forum 等）；body 要执行的逻辑，返回泛型结果
     * 返回值：body 的返回值
     * 异常：无（body 抛出的异常原样向上抛出，维度已在 finally 中恢复）
     */
    public static <T> T runAs(String dimension, java.util.function.Supplier<T> body) {  // runAs 签名：Supplier 版，返回 T
        String prev = SafeModel.getDimension();  // prev：记下进入前的维度（线程池复用线程时必须还原）
        SafeModel.setDimension(dimension);  // 切到目标维度
        try {  // try
            return body.get();  // 执行逻辑并返回结果
        } finally {  // finally：无论成败都还原
            SafeModel.setDimension(prev); // 恢复进入前的值（池化线程安全）  // 恢复进入前的维度
        }  // finally 结束
    }  // 方法结束

    /**
     * 作用：在指定成本维度内执行一段无返回值的逻辑，结束后自动恢复维度
     * 参数：dimension 成本维度；body 要执行的逻辑
     * 返回值：无
     * 异常：无（异常原样向上抛出，维度已恢复）
     */
    public static void runAs(String dimension, Runnable body) {  // runAs 签名：Runnable 版
        String prev = SafeModel.getDimension();  // prev：进入前的维度
        SafeModel.setDimension(dimension);  // 切到目标维度
        try {  // try
            body.run();  // 执行逻辑
        } finally {  // finally
            SafeModel.setDimension(prev);  // 恢复进入前的维度
        }  // finally 结束
    }  // 方法结束
}  // 类结束

