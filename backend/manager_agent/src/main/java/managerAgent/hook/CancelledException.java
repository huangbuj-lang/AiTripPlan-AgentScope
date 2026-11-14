package managerAgent.hook;

/** 任务被用户中断异常（类型化取消，替代字符串匹配） */
public class CancelledException extends RuntimeException {  // 类声明：用户中断任务的类型化异常（替代字符串匹配判断）
    /**
     * 作用：构造用户中断异常，固定文案用于前端提示
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    public CancelledException() {  // 构造器：无参，文案固定
        super("任务已被用户中断（/cancel）");  // 固定文案，前端可直接展示
    }  // 构造器结束
}  // 类结束
