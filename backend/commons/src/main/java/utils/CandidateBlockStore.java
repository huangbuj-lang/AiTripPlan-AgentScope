package utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★候选块进程级存档（观测代码化原则：结构化观测数据必须由代码确定性采集/传递，
 * 不依赖子 Agent LLM 把工具返回的【域候选JSON】块"自觉"抄进最终回复——LLM 不可靠，
 * 模型只摘摘要丢块会导致主管收割不到候选、聚类统计缺席）。
 *
 * 用法：DomainCandidateService.run 产出候选 JSON 后 put(sessionId, json) 存档；
 * 主管 harvestCandidates 在回复文本中提取不到块时，经子 Agent /candidate-block 端点回取。
 * key = sessionId（子 Agent 进程内由 A2A 任务文本【trace:xxx】归位，与 CallTrace 同键）。
 * 容量上限 300 会话，超限丢最旧；进程重启即清（与 CallTrace span 同级的内存态观测，
 * 随"观测落库"整体方案一并持久化）。
 */
public class CandidateBlockStore {  // 类声明：候选块进程级存档（静态工具类）

    private static final int MAX_SESSIONS = 300;  // 容量上限：最多存 300 个会话

    /** sessionId → 最近一次产出的候选 JSON（LinkedHashMap 保插入序，超限丢最旧） */
    private static final Map<String, String> BLOCKS = Collections.synchronizedMap(  // synchronizedMap 包一层保证线程安全；LinkedHashMap 保插入序
            new LinkedHashMap<>(64, 0.75f, false) {  // accessOrder=false：按插入序淘汰（不是按访问序）
                /**
                 * 作用：LRU 淘汰钩子：超过容量上限时移除最久未使用的会话候选
                 * 参数：eldest 最早写入的条目
                 * 返回值：true=需要淘汰
                 * 异常：无
                 */
                @Override  // @Override 覆写淘汰钩子
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {  // removeEldestEntry：每次 put 后被调用
                    return size() > MAX_SESSIONS;  // 超过 300 就淘汰最旧的一条
                }  // 钩子方法结束
            });  // 匿名子类结束
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private CandidateBlockStore() {  // 私有构造：禁止 new
    }  // 构造器结束

    /**
     * 作用：按会话缓存三域候选 JSON（容量上限内 LRU 保留）
     * 参数：sessionId 会话 ID；candidatesJson 三域候选 JSON
     * 返回值：无
     * 异常：无
     */
    public static void put(String sessionId, String candidatesJson) {  // put 签名：存档某会话的候选 JSON
        if (sessionId == null || sessionId.isBlank()  // sessionId 或 JSON 为空
                || candidatesJson == null || candidatesJson.isBlank()) {  // 续：直接返回不存
            return;  // return
        }  // if 结束
        BLOCKS.put(sessionId, candidatesJson);  // 写入（超限自动淘汰最旧）
    }  // 方法结束

    /**
     * 作用：按会话读取三域候选 JSON
     * 参数：sessionId 会话 ID
     * 返回值：候选 JSON；无缓存返回 null
     * 异常：无
     */
    public static String get(String sessionId) {  // get 签名：读某会话的候选 JSON
        if (sessionId == null || sessionId.isBlank()) {  // sessionId 为空
            return "";  // 返回空串
        }  // if 结束
        String v = BLOCKS.get(sessionId);  // 取缓存值
        return v == null ? "" : v;  // null 归一空串返回
    }  // 方法结束
}  // 类结束

