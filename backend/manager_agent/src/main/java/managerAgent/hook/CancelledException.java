package managerAgent.hook;

/**
 * 任务被用户中断异常（类型化取消，替代字符串匹配）
 */
public class CancelledException extends RuntimeException {

    public CancelledException() {
        super("任务已被用户中断（/cancel）");
    }
}
