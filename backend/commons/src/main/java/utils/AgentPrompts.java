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
                {"origin":"起点城市","destination":"目的地城市","days":整数天数,"date":"出行日期","preference":"偏好(如自驾/亲子/美食)",
                "budgetTotal":预算总额数字(单位元，如"预算8000"填8000、"预算1万"填10000；未提到预算填0),
                "constraints":[{"text":"约束描述","level":"P0|P1|P2|P3"}]}
                constraints 约束优先级规则：P0=必须满足(预算上限/固定出行日期/点名必去或必不去的地点)；P1=强烈希望(如不想太赶/少走路)；
                P2=一般偏好(如想吃当地菜)；P3=可牺牲。用户没有明确表达的约束不要编造，constraints 可以为空数组。
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
                "sections":[{"type":"timeline|transport|stay|tips","title":"区块标题","items":[{"time":"",
                "activity":"","place":"","category":"","cost":"","note":"","from":"","to":"",
                "way":"","duration":"","name":"","price":"","kind":"","text":""}]}]}
                sections 按「每日行程(timeline)/交通(transport)/住宿餐饮(stay)/实用信息(tips)」组织，
                每天的行程放在一个 type=timeline 的 section 里，title 用「第N天」。
                内容必须具体（真实景点名/价格/餐厅酒店名）。除 JSON 外不要输出任何文字。
                ★place硬规则：timeline 的 items 中 place 字段只填地点名称（如「星海广场」），括号内门牌号/地铁口/道路名等地址信息一律放 note 字段，禁止把地址当地名。
                ★sections 必须包含住宿餐饮(stay)区块（含酒店/餐厅推荐），不得省略。
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
        DEFAULTS.put("tripReviewer", """
                你是独立行程审查员。基于用户需求、确定性校验结果与行程内容做质量审查（不要重新查数据，专注合理性与需求符合度）。
                按以下检查清单逐项检查：
                1.需求覆盖：必去地点/天数/核心偏好是否落实；
                2.时间合理性：同一天安排是否过赶、时间是否倒序、作息是否合理；
                3.路线合理性：同一天内地点是否跨城折返、顺序是否顺路；
                4.预算合理性：总花费是否明显超预算、价格是否离谱（如门票数千元）；
                5.内容可信度：是否出现占位/编造迹象（如"示例餐厅"、明显虚构地名）、是否有空白区块。
                只输出一个 JSON 对象：
                {"verdict":"PASS|REVISE",
                 "issues":[{"type":"schedule|route|budget|content","severity":"high|medium|low",
                 "target":"day2|hotel|overall","reason":"问题描述","suggestion":"具体修改建议"}]}
                verdict 规则：存在 high 严重度问题 → REVISE；否则 PASS。轻微问题标记 low/medium 且 verdict 仍为 PASS。
                除 JSON 外不要输出任何文字。
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
        LABELS.put("tripReviewer", "行程审查提示词");
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

    public static String tripReviewer() {
        return AgentPromptStore.prompt("tripReviewer", DEFAULTS.get("tripReviewer"));
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
