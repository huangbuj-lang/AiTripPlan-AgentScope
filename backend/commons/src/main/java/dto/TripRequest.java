package dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
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

    /** 步行忍耐度（如 5000/10000/20000，单位米；空=不限制） */
    private String walkTolerance = "";

    /** 作息偏好（early=早起 / both=均可 / night=夜猫子；空=不限） */
    private String chronotype = "";

    /** 博物馆占比偏好（low=少 / mid=适中 / high=多；空=不限） */
    private String museumRatio = "";

    /** 原始需求文本（兜底/追溯用） */
    private String raw = "";

    /** 偏好分流：传给行程Agent的偏好（如亲子/美食/小众） */
    private String tripPref = "";

    /** 偏好分流：传给路线Agent的偏好（如自驾/不走高速/骑行） */
    private String routePref = "";

    /** 偏好分流：只用于聚合编排的偏好（如周末/悠闲/性价比） */
    private String aggPref = "";

    /** 预算总额（人民币元；0=未识别出数值预算） */
    private double budgetTotal = 0;

    /** 约束清单（P0~P3 优先级；空=用户未表达明确约束） */
    private List<Constraint> constraints = new ArrayList<>();

    /** 单条约束：内容 + 优先级（P0必须满足 / P1强烈希望 / P2一般偏好 / P3可牺牲） */
    @Data
    @NoArgsConstructor
    public static class Constraint {
        /** 约束描述（如 预算不超过8000元 / 必去西湖 / 不要太多博物馆） */
        private String text = "";
        /** 优先级：P0/P1/P2/P3 */
        private String level = "P2";

        public Constraint(String text, String level) {
            this.text = text == null ? "" : text;
            this.level = level == null || level.isBlank() ? "P2" : level;
        }
    }

    /** 是否存在 P0 硬约束（校验器据此决定预算/天数违规是否判 FAIL） */
    public boolean hasHardConstraints() {
        return constraints != null && constraints.stream().anyMatch(c -> "P0".equalsIgnoreCase(c.getLevel()));
    }

    /** 取指定优先级的约束文本列表（无则空列表） */
    public List<String> constraintTexts(String level) {
        List<String> out = new ArrayList<>();
        if (constraints != null) {
            for (Constraint c : constraints) {
                if (c.getLevel() != null && c.getLevel().equalsIgnoreCase(level) && !c.getText().isBlank()) {
                    out.add(c.getText());
                }
            }
        }
        return out;
    }

    /** 个人中心沉淀偏好（travelStyle/预算/节奏/回避；为空=未设置） */
    private Preferences preferences;
/** 用户沉淀偏好（个人中心设置，生成时注入） */
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
