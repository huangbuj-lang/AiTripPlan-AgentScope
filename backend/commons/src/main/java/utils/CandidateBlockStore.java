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
public class CandidateBlockStore {

    private static final int MAX_SESSIONS = 300;

    /** sessionId → 最近一次产出的候选 JSON（LinkedHashMap 保插入序，超限丢最旧） */
    private static final Map<String, String> BLOCKS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    private CandidateBlockStore() {
    }

    /** 存档某会话最近一次产出的三域候选 JSON（无会话上下文/空块忽略，防串号与空写） */
    public static void put(String sessionId, String candidatesJson) {
        if (sessionId == null || sessionId.isBlank()
                || candidatesJson == null || candidatesJson.isBlank()) {
            return;
        }
        BLOCKS.put(sessionId, candidatesJson);
    }

    /** 读取某会话最近一次候选 JSON；无则返回空串 */
    public static String get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        String v = BLOCKS.get(sessionId);
        return v == null ? "" : v;
    }
}
