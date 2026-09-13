package utils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Named prompts and tool descriptions with code defaults and Nacos Prompt overrides. */
public final class AgentPrompts {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("requestParser", """
                你是旅行需求解析器。从用户输入提取结构化信息，只输出一个 JSON 对象，字段名固定如下：
                {"origin":"起点城市","destination":"目的地城市","days":整数天数,"date":"出行日期","preference":"偏好(如自驾/亲子/美食)","mustVisit":["用户明确要求必须去的对象，如'一定要去宽窄巷子'→['宽窄巷子']；没说就给空数组"],"budgetTotal":数值金额元(用户说'预算5000'→5000；没说就给0)"}
                缺失字段给空字符串或默认值。除 JSON 外不要输出任何文字。
                """);
        DEFAULTS.put("preferenceClassifier", """
                你是旅行偏好分类器。把用户偏好分类到三个字段，只输出一个 JSON 对象，字段名固定：
                {"tripPref":"适合行程Agent的偏好(与景点美食住宿相关，如亲子/美食/小众/博物馆)；无则空",
                "routePref":"适合路线Agent的偏好(与出行方式道路相关，如公交/骑行/步行/不走高速)；无则空",
                "aggPref":"不适合二者、只影响整体编排的偏好(如放松休闲/性价比紧凑/节假日)；无则空"}
                只输出 JSON 不要多余文字。
                """);
        DEFAULTS.put("tripAggregator", """
                你是行程聚合器。根据需求与专家数据，输出一个结构化 JSON 对象，字段名固定如下：
                {"title":"行程标题","destination":"目的地","days":天数,"date":"日期","weather":"天气",
                "sections":[{"type":"timeline|transport|stay|tips","title":"区块标题","stops":["当天站点1","站点2"],"items":[{"time":"",
                "activity":"","place":"","category":"","cost":"","note":"","from":"","to":"",
                "way":"","duration":"","name":"","price":"","kind":"","text":""}]}]}
                sections 按「每日行程(timeline)/交通(transport)/住宿餐饮(stay)/实用信息(tips)」组织，
                每天的行程放在一个 type=timeline 的 section 里，title 用「第N天」。
                内容必须具体（真实景点名/价格/餐厅酒店名）。除 JSON 外不要输出任何文字。
                ★place硬规则：timeline 的 items 中 place 字段只填地点名称（如「星海广场」），括号内门牌号/地铁口/道路名等地址信息一律放 note 字段，禁止把地址当地名。
                ★sections 必须包含住宿餐饮(stay)区块（含酒店/餐厅推荐），不得省略。
                ★stops硬规则（前端卡片会按原有样式逐行显示它）：每个 timeline 区块必须填 stops —— 当天按顺序的站点名数组，
                例：["宽窄巷子","锦里","武侯祠博物馆","九眼桥"]。
                · 只写地点名，【禁止】带括号或补充说明（错误示例：「宽窄巷子(夜游)」「四姑娘山（日隆镇）」）；
                · 【同一地点只出现一次】，不要因为「到这里吃饭」「从这里出发」就重复写地名；
                · 交通/餐饮/住宿条目不要出现在 stops 里（它们的细节写在 items 里）。
                ★交付内容硬规则：行程文本里【禁止】出现任何过程性/内部字样 —— 例如「数据获取失败」「未采用」
                「衔接失败」「校验」「硬违反」「打回」等；某项数据没查到就【不要写那一条】，
                也不要解释为什么没查到、更不要写备选/未采用的方案。
                ★区块精简硬规则：住宿餐饮(stay)区块只推荐 2~3 家酒店 + 2~3 家餐厅；
                实用信息(tips)区块只写 3~5 条最关键的行前提示；不要罗列长清单、不要写过程说明。
                                ★实用信息内容硬规则（自驾/高原/草原线路必写，用户明确要求）：
                                实用信息(tips)区块必须覆盖这三类内容（行程里确实涉及就写，不涉及就不写，不要硬凑）——
                                ① 自驾装备：随车/随行装备（如备胎与拖车绳、防滑链、胎压与冷却液检查、车载充电与离线地图、常用药、防晒防风与保暖衣物）；
                                ② 路线难度：本行程的路况与强度提示（如盘山/搓板路、单日车程偏长、加油点与休息点间隔、区间限速、避免夜间行车）；
                                ③ 高原反应：进入海拔 2500 米以上地区时的适应与应急（如提前一天适应、避免剧烈运动与饮酒、备高原安/葡萄糖/氧气、出现头痛呕吐立即下撤）。
                                每条写清"怎么做 + 为什么"，一句话搞定；查不到或不确定的不要编造，宁缺勿造。
                                ★时长硬规则（实测踩坑）：每条 item 的 duration 必须填原文里的实际时长，
                                形如「2小时」「约30分钟」；交通/自驾类条目必须写成「车程4.5小时」这种形式。
                                原文里有时长/车程信息就【必须】照填，【禁止】留空 —— 留空会让后续的节奏校验无法判定。
                                ★开放时间硬规则：原文中若写明某地点的开放时间（形如「开放 09:00-17:00」），
                必须原样保留到该条目的 note 字段（例：note="开放 09:00-17:00"），不得丢弃或改写数字
                —— 交付前要用它比对"到访时间是否在开放时段内"。
                ★当日车程汇总硬规则：原文中若出现「当日车程合计：约X小时」这类汇总，
                                必须原样保留：放进当天 timeline 中某个交通条目的 note 字段
                                （例：note="当日车程合计：约6.5小时"），不得丢弃、不得改写数字。
                ★沿途风光硬规则（用户要求）：原文里每条车程/交通段提到的沿途风光（垭口/雪山/草原/湖泊/
                河谷/峰林/大桥等）【必须】原样写进【该交通条目的 note 字段】，
                例：note="沿 G30 穿越天山北麓，途中有果子沟大桥与赛里木湖沿岸可观，垭口可远眺雪山"。
                【只能写 note】：禁止写进 place/activity/stops —— 否则概览卡片与轨迹时间轴会把沿途风景显示成站点。
                原文某条车程确实没写沿途风光的，按该路段实际地貌补一句（丘陵/河谷/戈壁），禁止编造具体景点名。
                                """);
        DEFAULTS.put("tripAggregatorParseRetry", """
                你是行程聚合器。上次结构化 JSON 解析失败。请基于【用户需求】与【数据概要】只输出一份合法 JSON 行程（字段同前），重点是 sections 每天 timeline。数据较大已截断，可省次要 detail，保留每天真实安排。
                """);
        DEFAULTS.put("tripAggregatorGapRetry", """
                你是行程聚合器。上次聚合结果缺失：{missing}。请重新整合【用户需求】与【数据】，补齐缺失内容，输出完整合法 JSON 行程（字段同前）。
                """);
        DEFAULTS.put("moderation", """
                你是内容审核员。判断文本是否违规，只输出一个 JSON 对象：
                {"verdict":"pass|review|block","reason":"简短理由"}
                pass=正常；review=存疑(广告/软色情/低质/边界模糊，交人工)；block=明确违规(色情/暴力/违法/严重广告/人身攻击)。
                除 JSON 外不要输出任何文字。
                """);
        DEFAULTS.put("tripEditor", """
                你是旅行行程编辑助手。根据行程背景重新规划其中一天，只输出一个 JSON 对象：
                {"title":"区块标题","items":[{"time":"09:00","place":"景点/地点","activity":"活动描述","cost":"费用","note":"备注"}]}
                items 为该天按时间排序的具体安排，内容必须具体真实。除 JSON 外不要输出任何文字。
                """);
        DEFAULTS.put("routeAgentSystem", """
                你是RouteMakingAgent，一个专业的路线规划Agent。请始终使用中文，按用户要求查询真实路线数据并输出完整可读行程。
                """);
        DEFAULTS.put("tripAgentSystem", """
                你是TripPlannerAgent，一个专业的行程规划Agent。请始终使用中文，查询真实景点/住宿/美食数据并输出完整可读行程。
                """);
        DEFAULTS.put("genericReActSystem", """
                你是{agentName}，一个专业的中文AI助手。请始终使用中文进行思考、推理和回答，除非用户明确要求使用其他语言。
                你的目标是高质量完成用户交给你的任务。
                ★最终输出铁律：你的最终回复【必须】是完整、详细、可直接阅读的完整计划/完整答案，
                包含每一天的具体安排、具体景点名、具体价格、具体酒店餐厅名。
                【绝对禁止】只输出"已完成""总结""总览""核心成果"等收尾性概述——
                那些是总结不是计划，用户看不到你的子任务结果，只看到你的最终回复。
                输出前自查：如果最终回复里没有具体的日程细节，必须展开为完整计划再输出。
                ★开头禁止引导句：最终回复【直接】从计划正文开始（如"路线：...""第一天：..."），
                【绝对禁止】开头写"我已加载...""我已核对...""以下是为您整理..."
                "计划已制定完毕/已完成""最终完整方案"等任何过程叙述或引导语。
                """);
        DEFAULTS.put("toolQueryTrip", "一站式获取规划行程所需的全部真实数据。单城游传 destination 查城市景点/天气/酒店/美食；跨城自驾传 waypoints(途经城市,逗号分隔)查沿途途经景点,不查两端城市内部景点。一次调用返回完整数据。");
        DEFAULTS.put("toolRouteUnified", "路线统一查询工具：查两点间路线。mode=driving查自驾路线与距离；mode=urban查市内衔接(短距步行/长距公交)。可选routePref偏好(如公交/骑行/步行/不走高速)影响出行方式。跨城自驾用driving，市内景区串联用urban。");
        DEFAULTS.put("toolLocalKnowledge", "查询本地沉淀的景点/美食/酒店知识库（含评分、类型、档次、门票、时长、开放时间、游客提示）。与高德综合工具并行调用，作为高德实时数据的补充。传 city 城市名；preference 偏好可用自然语言（如 带娃/出片/吃得地道/性价比），会自动匹配标签。");

        LABELS.put("requestParser", "需求解析提示词");
        LABELS.put("preferenceClassifier", "偏好分类提示词");
        LABELS.put("tripAggregator", "行程聚合/输出格式");
        LABELS.put("tripAggregatorParseRetry", "聚合解析重试");
        LABELS.put("tripAggregatorGapRetry", "聚合补齐重试");
        LABELS.put("moderation", "内容审核提示词");
        LABELS.put("tripEditor", "行程编辑提示词");
        LABELS.put("routeAgentSystem", "路线Agent系统提示词");
        LABELS.put("tripAgentSystem", "行程Agent系统提示词");
        LABELS.put("genericReActSystem", "通用ReAct提示词模板");
        LABELS.put("toolQueryTrip", "工具描述：query_trip");
        LABELS.put("toolRouteUnified", "工具描述：query_route_unified");
        LABELS.put("toolLocalKnowledge", "工具描述：query_local_knowledge");
    }

    private AgentPrompts() {}

    public static String requestParser() {
        return AgentPromptStore.prompt("requestParser", DEFAULTS.get("requestParser"));
    }

    public static String preferenceClassifier() {
        return AgentPromptStore.prompt("preferenceClassifier", DEFAULTS.get("preferenceClassifier"));
    }

    public static String tripAggregator() {
        return AgentPromptStore.prompt("tripAggregator", DEFAULTS.get("tripAggregator"));
    }

    public static String tripAggregatorParseRetry() {
        return AgentPromptStore.prompt("tripAggregatorParseRetry", DEFAULTS.get("tripAggregatorParseRetry"));
    }

    public static String tripAggregatorGapRetry() {
        return AgentPromptStore.prompt("tripAggregatorGapRetry", DEFAULTS.get("tripAggregatorGapRetry"));
    }

    public static String moderation() {
        return AgentPromptStore.prompt("moderation", DEFAULTS.get("moderation"));
    }

    public static String tripEditor() {
        return AgentPromptStore.prompt("tripEditor", DEFAULTS.get("tripEditor"));
    }

    public static String routeAgentSystem(String fileFallback) {
        return AgentPromptStore.prompt("routeAgentSystem", fileFallback);
    }

    public static String tripAgentSystem(String fileFallback) {
        return AgentPromptStore.prompt("tripAgentSystem", fileFallback);
    }

    public static String genericReActSystem(String agentName) {
        return AgentPromptStore.prompt("genericReActSystem", DEFAULTS.get("genericReActSystem"))
                .replace("{agentName}", agentName);
    }

    public static String toolQueryTrip() {
        return AgentPromptStore.prompt("toolQueryTrip", DEFAULTS.get("toolQueryTrip"));
    }

    public static String toolRouteUnified() {
        return AgentPromptStore.prompt("toolRouteUnified", DEFAULTS.get("toolRouteUnified"));
    }

    public static String toolLocalKnowledge() {
        return AgentPromptStore.prompt("toolLocalKnowledge", DEFAULTS.get("toolLocalKnowledge"));
    }

    public static Map<String, String> defaults() {
        return new LinkedHashMap<>(DEFAULTS);
    }

    public static Map<String, String> labels() {
        return new LinkedHashMap<>(LABELS);
    }
}
