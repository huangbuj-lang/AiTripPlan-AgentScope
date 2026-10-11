package utils.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 偏好同义词归一：把用户/LLM 的自由文本偏好映射到标准标签集， 供 LocalKnowledgeTool 下推 SQL（label / suitable_tags 匹配）。 */
public final class PrefSynonyms {

    private PrefSynonyms() {}

    /** 标准 -> 同义词组（含标准词本身）。 */
    private static final Map<String, List<String>> MAP = new LinkedHashMap<>();

    static {
        //---- 人群/玩法类（匹配 suitable_tags）----
        MAP.put("亲子", arr("亲子", "带娃", "小孩", "孩子", "儿童", "娃娃", "家庭", "遛娃", "萌娃"));
        MAP.put("老人", arr("老人", "老年", "长辈", "父母", "爸妈", "敬老", "慢节奏"));
        MAP.put("情侣", arr("情侣", "恋爱", "约会", "蜜月", "浪漫", "夫妻", "爱人"));
        MAP.put("拍照", arr("拍照", "摄影", "出片", "打卡", "网红", "机位", "好看", "颜值", "美照"));
        MAP.put("朋友", arr("朋友", "好友", "闺蜜", "兄弟", "结伴", "团建", "多人"));
        MAP.put("独行", arr("独行", "一个人", "独自", "单人"));
        //---- 景点类型类（匹配 label）----
        MAP.put("自然风光", arr("自然风光", "自然", "山水", "风景", "森林", "湖泊", "山", "海", "江", "瀑布", "峡谷", "湿地", "田园", "生态"));
        MAP.put("人文历史", arr("人文历史", "人文", "历史", "文化", "古迹", "文物", "博物馆", "纪念馆", "古建", "遗产", "寺庙", "佛", "红色"));
        MAP.put("主题乐园", arr("主题乐园", "乐园", "游乐园", "游乐场", "动物园", "海洋馆", "水世界", "主题公园", "迪士尼"));
        MAP.put("特色街区", arr("特色街区", "街区", "步行街", "商业街", "古街", "老街", "巷子", "夜市", "集市"));
        MAP.put("城市公园", arr("城市公园", "公园", "园林", "植物园", "绿化", "休闲广场"));
        MAP.put("小众", arr("小众", "冷门", "人少", "清净", "原生态", "秘境", "小众景点"));
        MAP.put("刺激", arr("刺激", "冒险", "极限", "挑战", "蹦极", "漂流", "滑雪"));
        //---- 美食类（匹配 foods_features.label）----
        MAP.put("美食", arr("美食", "吃", "好吃", "餐饮", "餐厅", "小吃", "美味", "特产", "觅食", "干饭"));
        MAP.put("火锅", arr("火锅", "麻辣", "辣", "川味"));
        MAP.put("地方菜", arr("地方菜", "菜系", "本帮菜", "地道", "正宗", "特色菜"));
        MAP.put("小吃快餐", arr("小吃快餐", "快餐", "面", "粉", "饺子", "简餐"));
        MAP.put("异国料理", arr("异国料理", "西餐", "日本料理", "日料", "韩国", "泰国菜", "东南亚", "自助"));
        MAP.put("饮品甜点", arr("饮品甜点", "咖啡", "奶茶", "甜品", "下午茶", "茶馆", "酒吧", "烘焙"));
        MAP.put("海鲜", arr("海鲜", "河鲜", "水产", "刺身"));
        MAP.put("素食", arr("素食", "素菜", "斋饭", "轻食"));
        //---- 住宿类 ----
        MAP.put("酒店民宿", arr("酒店民宿", "住宿", "住", "宾馆", "民宿", "客栈", "度假村"));
        MAP.put("经济型", arr("经济型", "便宜", "省钱", "性价比", "穷游", "实惠", " budget", "经济"));
        MAP.put("豪华型", arr("豪华型", "豪华", "五星", "高端", "奢侈", "品质", "舒适度"));
        //---- 节奏/方式类 ----
        MAP.put("悠闲", arr("悠闲", "慢生活", "放松", "休闲", "躺平", "发呆", "惬意", "度假"));
        MAP.put("徒步", arr("徒步", "登山", "爬山", "远足", "步道", "健走"));
        MAP.put("骑行", arr("骑行", "自行车", "单车", "骑车"));
        MAP.put("自驾", arr("自驾", "开车", "租车", "公路"));
        MAP.put("温泉", arr("温泉", "泡汤", "汤池"));
        MAP.put("夜生活", arr("夜生活", "夜景", "晚上", "夜晚", "夜游", "灯光", "酒吧街"));
        MAP.put("购物", arr("购物", "买", "商场", "免税", "血拼", "伴手礼"));
        MAP.put("演艺", arr("演艺", "演出", "表演", " show", "剧场", "实景剧", "晚会"));
    }

    private static List<String> arr(String... a) {
        return Arrays.asList(a);
    }

    /** 归一：从自由文本提取全部命中的标准标签。 */
    public static List<String> normalize(String prefText) {
        List<String> out = new ArrayList<>();
        if (prefText == null || prefText.isBlank()) {
            return out;
        }
        String text = prefText.toLowerCase();
        for (Map.Entry<String, List<String>> e : MAP.entrySet()) {
            for (String syn : e.getValue()) {
                if (text.contains(syn.toLowerCase())) {
                    if (!out.contains(e.getKey())) {
                        out.add(e.getKey());
                    }
                    break;
                }
            }
        }
        return out;
    }

    /** 是否含人群/兴趣类标签（用于决定是否下推过滤） */
    public static boolean hasFilterable(List<String> tags) {
        return tags != null && !tags.isEmpty();
    }
}
