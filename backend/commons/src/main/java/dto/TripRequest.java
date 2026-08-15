package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户旅行需求的解析结果（阶段三结构化数据契约）
 * 由需求解析器单次 LLM 调用产出，字段用宽松默认值兜底。
 */
@Data
@NoArgsConstructor
public class TripRequest {

    /** 起点城市 */
    private String origin = "";

    /** 终点/目的地城市 */
    private String destination = "";

    /** 游玩天数 */
    private int days = 1;

    /** 出行日期（如 2026-08-14） */
    private String date = "";

    /** 偏好（如 自驾 / 亲子 / 美食） */
    private String preference = "";

    /** 步行忍耐度（如 5000/10000/20000，单位米；空=不限制） */
    private String walkTolerance = "";

    /** 作息偏好（early=早起 / both=均可 / night=夜猫子；空=不限） */
    private String chronotype = "";

    /** 博物馆占比偏好（low=少 / mid=适中 / high=多；空=不限） */
    private String museumRatio = "";

    /** 原始需求文本（兜底/追溯用） */
    private String raw = "";
}
