package managerAgent.hook;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 内存事件库：保存Agent运行事件（用户输入/思考/工具调用），供 /events 查询
 * 事件只用于"实时查看"，无需持久化，用内存（零依赖零配置）
 */
public class EventStore {

    private static final ConcurrentLinkedQueue<Map<String, String>> EVENTS = new ConcurrentLinkedQueue<>(); // 事件队列（线程安全）：保存最近的事件

    private static volatile String RESULT = null; // 最终结果缓存（异步执行后写入，供 /result 查询）

    private static final int MAX_EVENTS = 100; // 最多保留的事件条数（超出丢弃最旧的）

    /**
     * 保存任务最终结果
     */
    public static void setResult(String result) {
        RESULT = result;
    }



    //获取任务最终结果（null=还没完成）
    public static String getResult() {
        return RESULT;
    }

    /**
     * 添加一条事件记录
     */
    public static void add(String type, String content) {
        Map<String, String> event = Map.of(
                "time", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                "type", type,
                "content", content == null ? "" : content
        );
        EVENTS.add(event);
        while (EVENTS.size() > MAX_EVENTS) { // 超出上限，丢弃最旧的一条
            EVENTS.poll();
        }
    }

    /**
     * 获取全部事件（最新的在后面）
     */
    public static List<Map<String, String>> all() {
        return EVENTS.stream().collect(Collectors.toList());
    }

    /**
     * 清空事件（每次新任务开始时调用，避免多任务混流）
     */
    public static void clear() {
        EVENTS.clear();
        RESULT = null;
    }
}
