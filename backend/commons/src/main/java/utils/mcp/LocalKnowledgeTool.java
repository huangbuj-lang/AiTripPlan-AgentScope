package utils.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import utils.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** 本地知识库工具：直接查询预处理打标后的特征表（attractions_features/foods_features/hotels_features） 供行程Agent与高德 query_trip 并行调用，融合"本地真实数据(评分/档次/类型)" + 高德实时数据。 */
public class LocalKnowledgeTool {  // 类声明：本地知识库工具（查预处理打标后的特征表）

    private static final String DB = "aitripplan";  // DB：库名 aitripplan

    //==================== 观测埋点：知识库查询成败/次数/耗时（管理端子Agent健康展示） ====================
    public static final java.util.concurrent.atomic.AtomicLong LK_CALLS = new java.util.concurrent.atomic.AtomicLong(0);  // LK_CALLS：知识库调用总次数
    public static final java.util.concurrent.atomic.AtomicLong LK_FAILS = new java.util.concurrent.atomic.AtomicLong(0);  // LK_FAILS：失败次数
    /** 累计耗时（ms），配合 CALLS 算平均 */
    public static final java.util.concurrent.atomic.AtomicLong LK_COST_MS = new java.util.concurrent.atomic.AtomicLong(0);  // LK_COST_MS：累计耗时（配合 CALLS 算平均）

    //知识点/美食/酒店 特征表
    private static final String[][] TABLES = {  // TABLES：意图名 → 特征表名
        {"景点", "attractions_features"},  // 景点 → attractions_features
        {"美食", "foods_features"},  // 美食 → foods_features
        {"酒店", "hotels_features"},  // 酒店 → hotels_features
    };  // 数组结束
    /**
     * 作用：获取本地知识库数据库连接
     * 参数：无
     * 返回值：Connection 实例
     * 异常：Exception 连接失败时抛出
     */

    private Connection getConnection() throws Exception {  // getConnection：取 MySQL 连接
        String host = Config.get("RDS_HOST", "127.0.0.1:3306");  // host 取配置 RDS_HOST
        String user = Config.get("RDS_USER", "root");  // 用户名
        String pass = Config.get("RDS_PASSWORD", "");  // 密码
        Class.forName("com.mysql.cj.jdbc.Driver");  // 加载驱动
        String url = "jdbc:mysql://" + host + "/" + DB + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";  // 拼 JDBC URL（UTF-8 + 上海时区 + 关 SSL）
        return DriverManager.getConnection(url, user, pass);  // 建立连接
    }  // 方法结束

    /** 本地知识查询（按城市+偏好，偏好经同义词归一后下推SQL过滤）。 */
    @Tool(name = "query_local_knowledge",  // @Tool：注册成 Agent 可调用工具
            description = "查询本地沉淀的景点/美食/酒店知识库（含评分、类型、档次、门票、时长、开放时间、游客提示）。与高德综合工具并行调用，" +  // 工具描述：查本地沉淀的景点/美食/酒店知识库，
                    "作为高德实时数据的补充。传 city 城市名；preference 偏好可用自然语言（如 带娃/出片/吃得地道/性价比），会自动匹配标签。")  // 续：作为高德实时数据的补充
    /**
     * 作用：本地知识库查询工具：按城市与偏好查本地攻略（吃住玩），供子 Agent 补充非工具类经验
     * 参数：city 城市名；preference 偏好描述（自然语言，如"带娃亲子"）
     * 返回值：攻略文本；无匹配时返回提示文本
     * 异常：无（内部捕获异常，返回提示文本）
     */
    public String queryLocalKnowledge(  // queryLocalKnowledge：本地知识查询入口
            @ToolParam(name = "city", description = "城市名，如：成都") String city,  // 参数 city：城市名
            @ToolParam(name = "preference", description = "偏好描述，可用自然语言，如：带娃亲子/出片拍照/地道美食/性价比高") String preference) {  // 参数 preference：自然语言偏好（会自动归一到标签）
        if (city == null || city.isBlank()) {  // 城市为空
            LK_CALLS.incrementAndGet(); LK_FAILS.incrementAndGet(); // 参数缺失也算失败调用  // 计入调用与失败（参数缺失也算失败）
            return "【本地知识获取失败】city 必填";  // 返回失败提示
        }  // if 结束
        long lkStart = System.currentTimeMillis();  // lkStart：开始时间
        LK_CALLS.incrementAndGet();  // 调用数 +1
        try {  // try
            String out = doQuery(city, preference);  // 真正查询
            LK_COST_MS.addAndGet(System.currentTimeMillis() - lkStart);  // 累加耗时
            boolean lkOk = !out.contains("获取失败");  // lkOk：返回文本里没有“获取失败”就算成功
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_local_knowledge",  // 记调用链：TOOL 层
                    lkOk ? "知识库调用成功 · " + city + (preference == null || preference.isBlank() ? "" : " · " + preference)  // 续：成功描述（城市 + 偏好）
                         : "知识库调用失败 · " + out.substring(0, Math.min(60, out.length())),  // 续：失败描述（截前 60 字）
                    lkOk, System.currentTimeMillis() - lkStart);  // 续：成败 + 耗时
            return out;  // 返回结果文本
        } catch (RuntimeException re) {  // 捕获运行时异常
            LK_FAILS.incrementAndGet();  // 失败数 +1
            LK_COST_MS.addAndGet(System.currentTimeMillis() - lkStart);  // 累加耗时
            utils.CallTrace.record(utils.SafeModel.lastTraceId(), "TOOL", "query_local_knowledge",  // 记调用链：失败 + 异常信息
                    "异常: " + re.getMessage(), false, System.currentTimeMillis() - lkStart);  // 续：成败 + 耗时
            throw re;  // 原样抛出
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：实际查询：把偏好归一化成标签后按城市与标签检索并渲染成文本
     * 参数：city 城市名；preference 偏好描述
     * 返回值：攻略文本（多行）
     * 异常：无（异常内部捕获）
     */
    private String doQuery(String city, String preference) {  // doQuery：实际查询（标签归一下推 SQL）
        String p = preference == null ? "" : preference.trim();  // p：偏好归一（null 归空串）
        //偏好归一：自由文本 -> 标准标签（同义词表）
        List<String> prefTags = PrefSynonyms.normalize(p);  // prefTags：自由文本 → 标准标签

        //意图（查哪几类）：优先按归一标签映射
        List<String> intents = new ArrayList<>();  // intents：要查哪几类
        String[] allIntentWords = {"美食", "吃", "餐厅", "火锅", "小吃", "地方菜", "异国料理", "海鲜", "素食", "饮品甜点",  // allIntentWords：意图关键词表（供扩展）
                                   "酒店民宿", "住", "住宿", "宾馆", "经济型", "豪华型"};  // 续行
        boolean foodHit = false, hotelHit = false, spotHit = false;  // 三个命中标记
        for (String tag : prefTags) {  // 遍历归一后的标签
            if (tag.equals("美食") || tag.equals("火锅") || tag.equals("地方菜") || tag.equals("小吃快餐")  // 命中美食类标签
                || tag.equals("异国料理") || tag.equals("海鲜") || tag.equals("素食") || tag.equals("饮品甜点")) foodHit = true;  // 续：标记查美食
            if (tag.equals("酒店民宿") || tag.equals("经济型") || tag.equals("豪华型")) hotelHit = true;  // 命中住宿类标签
            if (tag.equals("自然风光") || tag.equals("人文历史") || tag.equals("主题乐园") || tag.equals("特色街区")  // 命中景点类标签
                || tag.equals("城市公园") || tag.equals("小众") || tag.equals("亲子") || tag.equals("拍照")  // 续行
                || tag.equals("悠闲") || tag.equals("徒步") || tag.equals("刺激") || tag.equals("夜生活")  // 续行
                || tag.equals("温泉") || tag.equals("购物") || tag.equals("演艺") || tag.equals("骑行")) spotHit = true;  // 续行
        }  // for 结束
        if (!foodHit && !hotelHit && !spotHit) {  // 一个都没命中
            //归一未命中 -> 用原始关键词判断
            if (p.contains("美食") || p.contains("吃") || p.contains("餐厅") || p.contains("火锅") || p.contains("小吃")) foodHit = true;  // 退回原始关键词判断（美食）
            if (p.contains("酒店") || p.contains("住") || p.contains("住宿") || p.contains("宾馆")) hotelHit = true;  // 退回原始关键词判断（住宿）
            if (p.contains("景点") || p.contains("景区") || p.contains("亲子") || p.contains("自然") || p.contains("人文") || p.contains("儿童")) spotHit = true;  // 退回原始关键词判断（景点）
        }  // if 结束
        if (foodHit) intents.add("美食");  // 要查美食
        if (hotelHit) intents.add("酒店");  // 要查酒店
        if (spotHit) intents.add("景点");  // 要查景点
        if (intents.isEmpty()) {  // 一个意图都没有
            intents.add("景点"); intents.add("美食"); intents.add("酒店");  // 默认三类全查
        }  // if 结束

        StringBuilder sb = new StringBuilder("【本地知识库|").append(city).append("】");  // sb：输出，以“【本地知识库|城市】”开头
        if (!prefTags.isEmpty()) {  // 有归一标签
            sb.append("（偏好:").append(String.join("/", prefTags)).append("，已按偏好筛选；未命中项已回退高分推荐）");  // 附“偏好:xx，已按偏好筛选”说明
        }  // if 结束
        boolean any = false;  // any：是否有任何结果
        try (Connection conn = getConnection()) {  // try-with-resources 取连接
            for (String intent : intents) {  // 逐个意图查询
                String table = null;  // table：对应表名
                for (String[] tb : TABLES) {  // 遍历映射表
                    if (tb[0].equals(intent)) { table = tb[1]; break; }  // 匹配意图就记表名并跳出
                }  // for 结束
                if (table == null) continue;  // 没有对应表就跳过
                boolean isAttraction = "attractions_features".equals(table);  // 是否景点表
                boolean hasSuitableTags = isAttraction || "foods_features".equals(table); // hotels_features 无 suitable_tags 列  // 是否支持 suitable_tags 列（酒店表没有）
                String sel = isAttraction  // sel：查询列（景点多三列）
                        ? "SELECT name, label, rating, level, suggest_duration, tips, best_time FROM " + table  // 景点：含建议时长/提示/最佳时间
                        : "SELECT name, label, rating, level, NULL AS suggest_duration, NULL AS tips, NULL AS best_time FROM " + table;  // 其它：这三列置 NULL 占位

                //两段式：先带偏好标签过滤
                List<String> rows = new ArrayList<>();  // rows：本意图的结果行
                if (!prefTags.isEmpty() && hasSuitableTags) {  // 有偏好标签且表支持
                    StringBuilder cond = new StringBuilder();  // cond：WHERE 条件
                    for (String tag : prefTags) {  // 遍历标签
                        if (cond.length() > 0) cond.append(" OR ");  // 非首项加 OR
                        cond.append("(label LIKE ? OR suitable_tags LIKE ?)");  // 每个标签都做 label/suitable_tags 双匹配
                    }  // for 结束
                    String sqlPref = sel + " WHERE city = ? AND (" + cond + ") ORDER BY rating DESC LIMIT 6";  // sqlPref：带偏好过滤的查询（按评分倒序取 6）
                    try (PreparedStatement ps = conn.prepareStatement(sqlPref)) {  // 预编译
                        ps.setString(1, city);  // 绑定城市
                        int pi = 2;  // pi：参数下标从 2 开始
                        for (String tag : prefTags) {  // 遍历标签
                            ps.setString(pi++, "%" + tag + "%");  // 绑定 label 的 LIKE
                            ps.setString(pi++, "%" + tag + "%");  // 绑定 suitable_tags 的 LIKE
                        }  // for 结束
                        try (ResultSet rs = ps.executeQuery()) {  // 执行查询
                            while (rs.next()) rows.add(renderRow(rs));  // 每行渲染成文本
                        }  // 资源关闭
                    }  // if 结束
                }  // if 结束
                String mode;  // mode：附加说明
                if (!rows.isEmpty()) {  // 偏好命中了
                    mode = "";  // 不需要说明
                } else {  // 否则
                    //回退：偏好无命中（或无偏好）-> 该城评分top
                    String sqlAll = sel + " WHERE city = ? ORDER BY rating DESC LIMIT 6";  // sqlAll：该城评分 top6（无偏好过滤）
                    try (PreparedStatement ps = conn.prepareStatement(sqlAll)) {  // 预编译
                        ps.setString(1, city);  // 绑定城市
                        try (ResultSet rs = ps.executeQuery()) {  // 执行查询
                            while (rs.next()) rows.add(renderRow(rs));  // 渲染每一行
                        }  // 资源关闭
                    }  // if 结束
                    mode = !prefTags.isEmpty() ? "（该偏好暂无精确匹配，以下为高分推荐）" : "";  // 有偏好却没命中时加说明
                }  // if 结束
                if (!rows.isEmpty()) {  // 本意图有结果
                    sb.append("\n【").append(intent).append("】").append(mode);  // 输出【意图】+ 说明
                    for (String r : rows) sb.append("\n- ").append(r);  // 逐行输出 - 名称
                    any = true;  // 标记有结果
                }  // if 结束
            }  // for 结束
            if (!any) {  // 一条都没有
                LK_FAILS.incrementAndGet(); // 无数据也算业务失败（健康页可见）  // 业务失败计数 +1
                return "【本地知识获取失败】该城市暂无沉淀数据，请依赖高德实时查询";  // 返回“该城市暂无沉淀数据”
            }  // if 结束
            return sb.toString();  // 返回汇总文本
        } catch (Exception e) {  // 捕获异常
            LK_FAILS.incrementAndGet(); // 数据库异常计失败  // 数据库异常计失败
            return "【本地知识获取失败】连接数据库异常: " + e.getMessage() + "（不影响高德实时查询，请直接使用高德数据）";  // 返回“连接数据库异常”提示（不影响高德查询）
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：把结果集一行渲染成一行可读文本
     * 参数：rs 已定位到当前行的结果集
     * 返回值：渲染文本
     * 异常：Exception 读取结果集失败时抛出
     */
    private static String renderRow(ResultSet rs) throws Exception {  // renderRow：结果集一行渲染成文本
        StringBuilder b = new StringBuilder();  // b：输出缓冲
        b.append(rs.getString("name"))  // 名称
          .append("（类型:").append(nvl(rs.getString("label")))  // ＋（类型:xx
          .append(" 评分:").append(nvl(rs.getString("rating")))  // ＋ 评分:xx
          .append(" 档次:").append(nvl(rs.getString("level"))).append("）");  // ＋ 档次:xx）
        String dur = rs.getString("suggest_duration");  // 建议游玩时长
        String tips = rs.getString("tips");  // 游客提示
        String best = rs.getString("best_time");  // 最佳时间
        if (dur != null && !dur.isBlank()) b.append(" 建议时长:").append(dur);  // 有时长就加
        if (best != null && !best.isBlank()) b.append(" 开放时间:").append(best);  // 有时间就加
        if (tips != null && !tips.isBlank()) b.append(" 参考提示:").append(tips);  // 有提示就加
        return b.toString();  // 返回这一行
    }  // 方法结束
    /**
     * 作用：null 安全字符串
     * 参数：s 可能为 null 的字符串
     * 返回值：原字符串；null 返回空串
     * 异常：无
     */

    private static String nvl(String s) {  // nvl：null 安全字符串
        return s == null ? "" : s;  // null 返回空串
    }  // 方法结束
}  // 类结束
