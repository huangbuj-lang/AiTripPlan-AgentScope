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

    /** 旅行类型：SINGLE_CITY单城市游 / CROSS_CITY跨城路线游 / LOOP区域环线 / SELF_DRIVE自驾路线游（空=未识别） */
    private String travelType = "";

    /** 同行人数（0=未识别） */
    private int travelers = 0;

    /** 同行人类别：couple情侣夫妻 / family亲子家庭 / friends朋友结伴 / solo独自出行 / elder携老人 / business商务（空=未识别） */
    private String travelerType = "";

    /** 天气敏感：含户外/徒步/摄影等需求，行程安排需重点关注天气（false=不敏感） */
    private boolean weatherSensitive = false;

    /** 酒店偏好（如 近地铁/舒适/经济型；空=未表达） */
    private String hotelPref = "";

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

    /** 旅行类型中文标签（事件流/状态简报展示用；空类型按跨城布尔推导） */
    public String travelTypeLabel() {
        String t = travelType == null ? "" : travelType.trim().toUpperCase();
        return switch (t) {
            case "SINGLE_CITY" -> "单城市游";
            case "CROSS_CITY" -> "跨城路线游";
            case "LOOP" -> "区域环线游";
            case "SELF_DRIVE" -> "自驾路线游";
            default -> "";
        };
    }

    /** 同行人类别中文标签（空=未识别） */
    public String travelerTypeLabel() {
        String t = travelerType == null ? "" : travelerType.trim().toLowerCase();
        return switch (t) {
            case "couple" -> "情侣/夫妻";
            case "family" -> "亲子家庭";
            case "friends" -> "朋友结伴";
            case "solo" -> "独自出行";
            case "elder" -> "携老人";
            case "business" -> "商务出行";
            default -> "";
        };
    }

    /** 渲染结构化状态简报（PDF §三：Agent 不直接解读原始文本，围绕统一 TravelState 决策）。
     *  随 A2A 任务文本下发子 Agent，作为权威需求快照。 */
    public String toStateBrief() {
        StringBuilder sb = new StringBuilder("【TravelState 统一状态·权威快照】\n");
        String label = travelTypeLabel();
        if (!label.isBlank()) sb.append("旅行类型: ").append(label).append("\n");
        if (origin != null && !origin.isBlank()) sb.append("出发地: ").append(origin).append("\n");
        sb.append("目的地: ").append(destination == null ? "" : destination).append("\n");
        sb.append("天数: ").append(days).append(" 天\n");
        if (date != null && !date.isBlank()) sb.append("出行日期: ").append(date).append("\n");
        if (travelers > 0) {
            sb.append("同行: ").append(travelers).append(" 人");
            String tt = travelerTypeLabel();
            if (!tt.isBlank()) sb.append("（").append(tt).append("）");
            sb.append("\n");
        }
        if (budgetTotal > 0) sb.append("预算总额: ").append((long) budgetTotal).append(" 元\n");
        if (constraints != null && !constraints.isEmpty()) {
            sb.append("硬约束(P0): ").append(String.join("；", constraintTexts("P0"))).append("\n");
            List<String> soft = new ArrayList<>();
            for (String lv : new String[]{"P1", "P2", "P3"}) {
                soft.addAll(constraintTexts(lv));
            }
            if (!soft.isEmpty()) sb.append("软偏好(P1~P3): ").append(String.join("；", soft)).append("\n");
        }
        if (preference != null && !preference.isBlank()) sb.append("用户偏好: ").append(preference).append("\n");
        if (tripPref != null && !tripPref.isBlank()) sb.append("行程偏好: ").append(tripPref).append("\n");
        if (routePref != null && !routePref.isBlank()) sb.append("路线偏好: ").append(routePref).append("\n");
        if (hotelPref != null && !hotelPref.isBlank()) sb.append("酒店偏好: ").append(hotelPref).append("\n");
        if (weatherSensitive) sb.append("天气敏感: 是（户外/摄影类安排需关注天气）\n");
        if (preferences != null && !preferences.isEmpty()) {
            sb.append("沉淀偏好: ").append(preferences.toPromptText()).append("\n");
        }
        return sb.toString();
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

    /** 结果缓存需求指纹：toStateBrief 会整体进专家 prompt，其中未单独参与 PlanCache key 的字段
     *  （出行类型/日期/人数/预算/约束/酒店偏好/天气敏感）在此规范化拼接，防止不同预算或约束的请求复用同一份专家产物。
     *  约束与解析顺序无关，按 级别:文本 排序保证同语义请求指纹一致。 */
    public String cacheFingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(travelType == null ? "" : travelType.trim().toUpperCase()).append('|')
          .append(date == null ? "" : date.trim()).append('|')
          .append(travelers).append('|')
          .append(travelerType == null ? "" : travelerType.trim().toLowerCase()).append('|')
          .append(budgetTotal > 0 ? (long) budgetTotal : 0).append('|')
          .append(weatherSensitive).append('|')
          .append(hotelPref == null ? "" : hotelPref.trim());
        if (constraints != null) {
            constraints.stream()
                    .filter(c -> c != null && c.getText() != null && !c.getText().isBlank())
                    .map(c -> (c.getLevel() == null ? "P2" : c.getLevel().trim().toUpperCase())
                            + ":" + c.getText().trim())
                    .sorted()
                    .forEach(s -> sb.append('|').append(s));
        }
        return sb.toString();
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
