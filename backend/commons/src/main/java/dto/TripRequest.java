package dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户旅行需求的解析结果（阶段三结构化数据契约）
 * 由需求解析器单次 LLM 调用产出，字段用宽松默认值兜底。
 */
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造（Jackson 反序列化需要）
public class TripRequest {  // 类声明：旅行需求（需求解析产物 + 归一后的字段）

    /** 起点城市 */
    private String origin = "";  // origin：出发地

    /** 终点/目的地城市 */
    private String destination = "";  // destination：目的地

    /** 游玩天数 */
    private int days = 1;  // days：天数（默认 1）

    /** 出行日期（如 2026-08-14） */
    private String date = "";  // date：出行日期（yyyy-MM-dd）

    /** 偏好（如 自驾 / 亲子 / 美食） */
    private String preference = "";  // preference：用户原话里的偏好

    /** 步行忍耐度（如 5000/10000/20000，单位米；空=不限制） */
    private String walkTolerance = "";  // walkTolerance：步行容忍度（画像注入）

    /** 作息偏好（early=早起 / both=均可 / night=夜猫子；空=不限） */
    private String chronotype = "";  // chronotype：作息类型（画像注入）

    /** 博物馆占比偏好（low=少 / mid=适中 / high=多；空=不限） */
    private String museumRatio = "";  // museumRatio：博物馆占比偏好（画像注入）

    /** 原始需求文本（兜底/追溯用） */
    private String raw = "";  // raw：用户原始输入（兜底解析用）

    /** 偏好分流：传给行程Agent的偏好（如亲子/美食/小众） */
    private String tripPref = "";  // tripPref：适合行程 Agent 的偏好

    /** 偏好分流：传给路线Agent的偏好（如自驾/不走高速/骑行） */
    private String routePref = "";  // routePref：适合路线 Agent 的偏好

    /** 偏好分流：只用于聚合编排的偏好（如周末/悠闲/性价比） */
    private String aggPref = "";  // aggPref：只影响整体编排的偏好

    /** 预算总额（人民币元；0=未识别出数值预算） */
    private double budgetTotal = 0;  // budgetTotal：预算总额（元，0=未识别）

    /** 旅行类型：SINGLE_CITY单城市游 / CROSS_CITY跨城路线游 / LOOP区域环线 / SELF_DRIVE自驾路线游（空=未识别） */
    private String travelType = "";  // travelType：SINGLE_CITY/CROSS_CITY/LOOP/SELF_DRIVE

    /** 同行人数（0=未识别） */
    private int travelers = 0;  // travelers：同行人数（0=未识别）

    /** 同行人类别：couple情侣夫妻 / family亲子家庭 / friends朋友结伴 / solo独自出行 / elder携老人 / business商务（空=未识别） */
    private String travelerType = "";  // travelerType：couple/family/friends/solo/elder/business

    /** 天气敏感：含户外/徒步/摄影等需求，行程安排需重点关注天气（false=不敏感） */
    private boolean weatherSensitive = false;  // weatherSensitive：是否天气敏感（户外/摄影）

    /** 酒店偏好（如 近地铁/舒适/经济型；空=未表达） */
    private String hotelPref = "";  // hotelPref：酒店偏好

    /** 约束清单（P0~P3 优先级；空=用户未表达明确约束） */
    private List<Constraint> constraints = new ArrayList<>();  // constraints：约束列表

    /** 单条约束：内容 + 优先级（P0必须满足 / P1强烈希望 / P2一般偏好 / P3可牺牲） */
    @Data  // @Data：Constraint 也用 Lombok
    @NoArgsConstructor  // @NoArgsConstructor：无参构造
    public static class Constraint {  // 内部类 Constraint：一条约束
        /** 约束描述（如 预算不超过8000元 / 必去西湖 / 不要太多博物馆） */
        private String text = "";  // text：约束内容
        /** 优先级：P0/P1/P2/P3 */
        private String level = "P2";  // level：优先级 P0~P3（默认 P2）
        /**
         * 作用：构造一条用户约束
         * 参数：text 约束文本；level 约束级别（硬性/软性）
         * 返回值：无
         * 异常：无
         */

        public Constraint(String text, String level) {  // 构造器：带空值兜底
            this.text = text == null ? "" : text;  // text 为 null 归空串
            this.level = level == null || level.isBlank() ? "P2" : level;  // level 为空归 P2
        }  // 构造器结束
    }  // 内部类结束

    /**
     * 作用：判断是否存在硬性约束（必去/回避/预算上限等）
     * 参数：无
     * 返回值：true=有硬性约束
     * 异常：无
     */
    public boolean hasHardConstraints() {  // hasHardConstraints：是否有 P0 硬约束
        return constraints != null && constraints.stream().anyMatch(c -> "P0".equalsIgnoreCase(c.getLevel()));  // 任一约束是 P0
    }  // 方法结束

    /**
     * 作用：生成出行方式的中文标签
     * 参数：无
     * 返回值：标签文本，未指定时返回"未指定"
     * 异常：无
     */
    public String travelTypeLabel() {  // travelTypeLabel：出行类型中文名
        String t = travelType == null ? "" : travelType.trim().toUpperCase();  // t：大写后的类型
        return switch (t) {  // 按类型返回
            case "SINGLE_CITY" -> "单城市游";  // 单城市游
            case "CROSS_CITY" -> "跨城路线游";  // 跨城路线游
            case "LOOP" -> "区域环线游";  // 区域环线游
            case "SELF_DRIVE" -> "自驾路线游";  // 自驾路线游
            default -> "";  // 未知返回空串
        };  // switch 结束
    }  // 方法结束

    /**
     * 作用：生成出行人群的中文标签（亲子/情侣/老人等）
     * 参数：无
     * 返回值：标签文本，未指定时返回"未指定"
     * 异常：无
     */
    public String travelerTypeLabel() {  // travelerTypeLabel：同行人群中文名
        String t = travelerType == null ? "" : travelerType.trim().toLowerCase();  // t：小写后的人群类型
        return switch (t) {  // 按类型返回
            case "couple" -> "情侣/夫妻";  // 情侣/夫妻
            case "family" -> "亲子家庭";  // 亲子家庭
            case "friends" -> "朋友结伴";  // 朋友结伴
            case "solo" -> "独自出行";  // 独自出行
            case "elder" -> "携老人";  // 携老人
            case "business" -> "商务出行";  // 商务出行
            default -> "";  // 未知返回空串
        };  // switch 结束
    }  // 方法结束

    /**
     * 作用：生成给行程状态机的需求简述（一行文字）
     * 参数：无
     * 返回值：需求简述文本
     * 异常：无
     */
    public String toStateBrief() {  // toStateBrief：生成【TravelState 统一状态】权威快照文本
        StringBuilder sb = new StringBuilder("【TravelState 统一状态·权威快照】\n");  // sb：以快照标题开头
        String label = travelTypeLabel();  // label：出行类型中文名
        if (!label.isBlank()) sb.append("旅行类型: ").append(label).append("\n");  // 有类型就写
        if (origin != null && !origin.isBlank()) sb.append("出发地: ").append(origin).append("\n");  // 有出发地就写
        sb.append("目的地: ").append(destination == null ? "" : destination).append("\n");  // 目的地（必写）
        sb.append("天数: ").append(days).append(" 天\n");  // 天数
        if (date != null && !date.isBlank()) sb.append("出行日期: ").append(date).append("\n");  // 有日期就写
        if (travelers > 0) {  // 有同行人数
            sb.append("同行: ").append(travelers).append(" 人");  // 同行人数
            String tt = travelerTypeLabel();  // tt：人群中文名
            if (!tt.isBlank()) sb.append("（").append(tt).append("）");  // 有就追加
            sb.append("\n");  // 换行
        }  // if 结束
        if (budgetTotal > 0) sb.append("预算总额: ").append((long) budgetTotal).append(" 元\n");  // 有预算就写
        if (constraints != null && !constraints.isEmpty()) {  // 有约束
            sb.append("硬约束(P0): ").append(String.join("；", constraintTexts("P0"))).append("\n");  // P0 硬约束
            List<String> soft = new ArrayList<>();  // soft：软偏好收集
            for (String lv : new String[]{"P1", "P2", "P3"}) {  // 按 P1/P2/P3 顺序
                soft.addAll(constraintTexts(lv));  // 逐个等级收集
            }  // for 结束
            if (!soft.isEmpty()) sb.append("软偏好(P1~P3): ").append(String.join("；", soft)).append("\n");  // 有软偏好就写
        }  // if 结束
        if (preference != null && !preference.isBlank()) sb.append("用户偏好: ").append(preference).append("\n");  // 用户偏好
        if (tripPref != null && !tripPref.isBlank()) sb.append("行程偏好: ").append(tripPref).append("\n");  // 行程偏好
        if (routePref != null && !routePref.isBlank()) sb.append("路线偏好: ").append(routePref).append("\n");  // 路线偏好
        if (hotelPref != null && !hotelPref.isBlank()) sb.append("酒店偏好: ").append(hotelPref).append("\n");  // 酒店偏好
        if (weatherSensitive) sb.append("天气敏感: 是（户外/摄影类安排需关注天气）\n");  // 天气敏感标记
        if (preferences != null && !preferences.isEmpty()) {  // 有沉淀偏好（个人画像）
            sb.append("沉淀偏好: ").append(preferences.toPromptText()).append("\n");  // 画像偏好文本
        }  // if 结束
        return sb.toString();  // 返回快照文本
    }  // 方法结束

    /**
     * 作用：按级别筛出约束文本列表
     * 参数：level 约束级别（硬性/软性）
     * 返回值：该级别的约束文本列表
     * 异常：无
     */
    public List<String> constraintTexts(String level) {  // constraintTexts：取指定优先级的约束文本
        List<String> out = new ArrayList<>();  // out：结果
        if (constraints != null) {  // 有约束
            for (Constraint c : constraints) {  // 遍历
                if (c.getLevel() != null && c.getLevel().equalsIgnoreCase(level) && !c.getText().isBlank()) {  // 等级匹配且内容非空
                    out.add(c.getText());  // 加入
                }  // if 结束
            }  // for 结束
        }  // if 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：生成需求指纹（目的地/天数/出发地/偏好/约束的哈希），用于缓存去重
     * 参数：无
     * 返回值：指纹十六进制字符串
     * 异常：无
     */
    public String cacheFingerprint() {  // cacheFingerprint：需求指纹（防缓存串味）
        StringBuilder sb = new StringBuilder();  // sb：指纹缓冲
        sb.append(travelType == null ? "" : travelType.trim().toUpperCase()).append('|')  // 出行类型
          .append(date == null ? "" : date.trim()).append('|')  // 日期
          .append(travelers).append('|')  // 人数
          .append(travelerType == null ? "" : travelerType.trim().toLowerCase()).append('|')  // 人群
          .append(budgetTotal > 0 ? (long) budgetTotal : 0).append('|')  // 预算
          .append(weatherSensitive).append('|')  // 是否天气敏感
          .append(hotelPref == null ? "" : hotelPref.trim());  // 酒店偏好
        if (constraints != null) {  // 有约束
            constraints.stream()  // 流式处理约束
                    .filter(c -> c != null && c.getText() != null && !c.getText().isBlank())  // 过滤空约束
                    .map(c -> (c.getLevel() == null ? "P2" : c.getLevel().trim().toUpperCase())  // 拼“优先级:内容”（
                            + ":" + c.getText().trim())  // 续：级别为空按 P2）
                    .sorted()  // 排序（保证同样约束顺序稳定）
                    .forEach(s -> sb.append('|').append(s));  // 逐个追加
        }  // if 结束
        return sb.toString();  // 返回指纹
    }  // 方法结束

    /** 个人中心沉淀偏好（travelStyle/预算/节奏/回避；为空=未设置） */
    private Preferences preferences;  // preferences：个人中心沉淀的画像偏好
/** 用户沉淀偏好（个人中心设置，生成时注入） */
    @Data  // @Data：Preferences 也用 Lombok
    @NoArgsConstructor  // @NoArgsConstructor：无参构造
    public static class Preferences {  // 内部类 Preferences：画像偏好
        /** 风格标签：["穷游","亲子","美食","徒步","奢华"] */
        private List<String> travelStyle;  // travelStyle：风格标签列表
        /** 预算档：1经济 2舒适 3奢华 */
        private Integer budgetLevel;  // budgetLevel：预算档 1~3
        /** 节奏：1紧凑 2适中 3松弛 */
        private Integer pace;  // pace：节奏档 1~3
        /** 忌口/回避项：["海鲜","辣"] */
        private List<String> avoid;  // avoid：要回避的清单

        /**
         * 作用：判断偏好对象是否为空
         * 参数：无
         * 返回值：true=无任何偏好
         * 异常：无
         */
        public boolean isEmpty() {  // isEmpty：是否全空
            return (travelStyle == null || travelStyle.isEmpty())  // 风格为空
                    && budgetLevel == null  // 且预算档为空
                    && pace == null  // 且节奏为空
                    && (avoid == null || avoid.isEmpty());  // 且回避为空
        }  // 方法结束

        /**
         * 作用：把沉淀偏好拼成可注入提示词的文本
         * 参数：无
         * 返回值：偏好文本；无偏好返回空串
         * 异常：无
         */
        public String toPromptText() {  // toPromptText：画像偏好转提示词文本
            StringBuilder sb = new StringBuilder();  // sb：输出
            if (travelStyle != null && !travelStyle.isEmpty()) {  // 有风格
                sb.append("风格：").append(String.join("/", travelStyle));  // 拼“风格：x/y”
            }  // if 结束
            if (budgetLevel != null) {  // 有预算档
                String[] names = {"", "经济", "舒适", "奢华"};  // names：预算档中文名表
                String b = budgetLevel >= 1 && budgetLevel <= 3 ? names[budgetLevel] : ("预算档" + budgetLevel);  // b：档位名（越界显示“预算档N”）
                sb.append(sb.length() == 0 ? "" : "，").append("预算：").append(b);  // 拼“预算：x”（非首项加逗号）
            }  // if 结束
            if (pace != null) {  // 有节奏档
                String[] names = {"", "紧凑", "适中", "松弛"};  // names：节奏档中文名表
                String p = pace >= 1 && pace <= 3 ? names[pace] : ("节奏" + pace);  // p：档位名
                sb.append(sb.length() == 0 ? "" : "，").append("节奏：").append(p);  // 拼“节奏：x”
            }  // if 结束
            if (avoid != null && !avoid.isEmpty()) {  // 有回避清单
                sb.append(sb.length() == 0 ? "" : "，").append("回避：").append(String.join("/", avoid));  // 拼“回避：x/y”
            }  // if 结束
            return sb.toString();  // 返回拼接结果
        }  // 方法结束
    }  // 内部类结束
}  // 类结束
