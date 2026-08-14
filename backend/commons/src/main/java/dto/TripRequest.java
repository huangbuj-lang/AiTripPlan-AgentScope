package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /** 用户沉淀的旅行偏好（来自 user_preferences，P0 注入用，不走 LLM 解析避免幻觉） */
    private Preferences preferences;

    /** 原始需求文本（兜底/追溯用） */
    private String raw = "";

    /**
     * 用户沉淀偏好（结构化，来源 user_preferences 表）。
     */
    @Data
    @NoArgsConstructor
    public static class Preferences {
        /** 风格标签：["穷游","亲子","美食","徒步","奢华"] */
        private List<String> travelStyle;
        /** 预算档：1经济 2舒适 3奢华 */
        private Integer budgetLevel;
        /** 节奏：1紧凑 2适中 3松弛 */
        private Integer pace;
        /** 忌口/回避项：["海鲜","辣"] */
        private List<String> avoid;

        /** 是否为空（未设置任何偏好） */
        public boolean isEmpty() {
            return (travelStyle == null || travelStyle.isEmpty())
                    && budgetLevel == null
                    && pace == null
                    && (avoid == null || avoid.isEmpty());
        }

        /** 渲染为可读文本，供 routeTask/planTask 拼进提示词 */
        public String toPromptText() {
            StringBuilder sb = new StringBuilder();
            if (travelStyle != null && !travelStyle.isEmpty()) {
                sb.append("风格：").append(String.join("/", travelStyle));
            }
            if (budgetLevel != null) {
                String[] names = {"", "经济", "舒适", "奢华"};
                String b = budgetLevel >= 1 && budgetLevel <= 3 ? names[budgetLevel] : ("预算档" + budgetLevel);
                sb.append(sb.length() == 0 ? "" : "，").append("预算：").append(b);
            }
            if (pace != null) {
                String[] names = {"", "紧凑", "适中", "松弛"};
                String p = pace >= 1 && pace <= 3 ? names[pace] : ("节奏" + pace);
                sb.append(sb.length() == 0 ? "" : "，").append("节奏：").append(p);
            }
            if (avoid != null && !avoid.isEmpty()) {
                sb.append(sb.length() == 0 ? "" : "，").append("回避：").append(String.join("/", avoid));
            }
            return sb.toString();
        }
    }
}
