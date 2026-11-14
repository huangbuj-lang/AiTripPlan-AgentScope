package utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三域结构化候选（景点/酒店/餐饮 Top-3）JSON 工具。
 * 契约（行程子 Agent 产出，主管消费）：
 * {"attraction":{"city":"..","candidates":[{"name":"","reason":"","price":"","rating":"","openTime":"","duration":"","area":"","location":"经度,纬度(高德原样,可空)","confidence":0.9,"source":"amap+local_db","needsConfirm":false}],"degraded":false}, "hotel":{...}, "dining":{...}}
 * 来源分级（PDF §十五）：S=官方站点（暂无）/ A=高德API与本地知识库 / B=专业平台 / C=LLM常识补充。
 * 置信度门槛（PDF §十八）：域均值 < 0.70 触发一次重查，仍低则逐候选标注 needsConfirm=true（"建议出发前确认"）。
 */
public final class CandidateUtil {  // 类声明：三域候选工具（解析/取数/置信度/证据/聚类）

    /** 三个域的 key（域专家Agent与聚合/校验/证据链统一用这套命名） */
    public static final String[] DOMAINS = {"attraction", "hotel", "dining"};  // DOMAINS：三个域的 key

    private static final String BLOCK_BEGIN = "【域候选JSON】";  // BLOCK_BEGIN：域候选 JSON 块起始标记
    private static final String BLOCK_END = "【域候选JSON结束】";  // BLOCK_END：域候选 JSON 块结束标记
    /** ★删聚合后行程Agent直接输出结构化终版：块标记与域候选块同机制（正文= TripPlanData JSON，供主管解析） */
    private static final String PLAN_BLOCK_BEGIN = "【行程JSON】";  // PLAN_BLOCK_BEGIN：行程 JSON 块起始标记
    private static final String PLAN_BLOCK_END = "【行程JSON结束】";  // PLAN_BLOCK_END：行程 JSON 块结束标记
    private static final Pattern CONF = Pattern.compile("\"confidence\"\\s*:\\s*(0?(?:\\.\\d+)|1(?:\\.0+)?)");  // CONF：置信度字段正则（0~1）

    /**
     * 作用：域名转中文标签
     * 参数：domain 域名（attraction/hotel/dining）
     * 返回值：中文标签；未知域名返回原值
     * 异常：无
     */
    public static String domainLabel(String domain) {  // domainLabel：域 key 转中文名
        return switch (domain == null ? "" : domain) {  // 按 key 判断
            case "attraction" -> "景点";  // attraction → 景点
            case "hotel" -> "酒店";  // hotel → 酒店
            case "dining" -> "餐饮";  // dining → 餐饮
            default -> domain == null ? "" : domain;  // 其它原样返回
        };  // switch 结束
    }  // 方法结束

    /**
     * 作用：从子 Agent 回复中抽出【三域候选】数据块
     * 参数：reply 子 Agent 原文
     * 返回值：候选 JSON 文本；没有该块返回空串
     * 异常：无
     */
    public static String extractBlock(String reply) {  // extractBlock：从回复里抠【域候选JSON】块内容
        if (reply == null) return "";  // 回复为空返回空串
        int b = reply.indexOf(BLOCK_BEGIN);  // 找起始标记
        if (b < 0) return "";  // 没有标记返回空串
        int s = b + BLOCK_BEGIN.length();  // 内容起点
        int e = reply.indexOf(BLOCK_END, s);  // 找结束标记
        if (e < 0) e = reply.length();  // 没有结束标记就取到末尾
        return reply.substring(s, e).trim();  // 返回去空格后的内容
    }  // 方法结束

    /**
     * 作用：从回复中剥离【三域候选】块，避免污染其他环节
     * 参数：reply 子 Agent 原文
     * 返回值：剥离后的文本
     * 异常：无
     */
    public static String stripBlock(String reply) {  // stripBlock：把【域候选JSON】块从回复里剥掉
        if (reply == null) return "";  // 回复为空返回空串
        int b = reply.indexOf(BLOCK_BEGIN);  // 找起始标记
        if (b < 0) return reply;  // 没有就原样返回
        int e = reply.indexOf(BLOCK_END, b);  // 找结束标记
        if (e < 0) return reply.substring(0, b).trim();  // 没有结束标记：截到起始处
        return (reply.substring(0, b) + reply.substring(e + BLOCK_END.length())).trim();  // 否则把块两侧拼起来
    }  // 方法结束
    /**
     * 作用：从回复中抽出【行程】数据块
     * 参数：reply 子 Agent 原文
     * 返回值：行程 JSON 文本；没有该块返回空串
     * 异常：无
     */

    public static String extractPlanBlock(String reply) {  // extractPlanBlock：抠【行程JSON】块（兼容 markdown 围栏）
        if (reply == null) return "";  // 回复为空返回空串
        int b = reply.indexOf(PLAN_BLOCK_BEGIN);  // 找起始标记
        if (b >= 0) {  // 有标记
            int s = b + PLAN_BLOCK_BEGIN.length();  // 内容起点
            int e = reply.indexOf(PLAN_BLOCK_END, s);  // 找结束标记
            if (e < 0) e = reply.length();  // 没有就取到末尾
            return reply.substring(s, e).trim();  // 返回内容
        }  // if 结束
        // ★兜底（2026-09-06）：模型可能用 markdown ```json 代码块围栏包 JSON 而不带【行程JSON】标记
        int cb = reply.indexOf("```json");  // 兜底：找 ```json 围栏
        if (cb < 0) cb = reply.indexOf("```JSON");  // 找大写变体
        if (cb >= 0) {  // 找到围栏
            int s = cb + 7; // 跳过 ```json  // 跳过围栏标记
            int e = reply.indexOf("```", s);  // 找结尾围栏
            return (e < 0 ? reply.substring(s) : reply.substring(s, e)).trim();  // 返回围栏内内容
        }  // if 结束
        return "";  // 都没有返回空串
    }  // 方法结束

    /**
     * 作用：从回复中剥离【行程】块
     * 参数：reply 子 Agent 原文
     * 返回值：剥离后的文本
     * 异常：无
     */
    public static String stripPlanBlock(String reply) {  // stripPlanBlock：把行程 JSON 块从回复里剥掉
        if (reply == null) return "";  // 回复为空返回空串
        int b = reply.indexOf(PLAN_BLOCK_BEGIN);  // 找起始标记
        int e = -1;  // e：结束标记位置
        if (b >= 0) e = reply.indexOf(PLAN_BLOCK_END, b);  // 有起始就找结束
        if (b >= 0 && e >= 0) {  // 两个标记都在
            return (reply.substring(0, b) + reply.substring(e + PLAN_BLOCK_END.length())).trim();  // 去掉中间内容
        }  // if 结束
        // ★兜底：markdown ```json 围栏同样剥离
        int cb = reply.indexOf("```json");  // 兜底找 markdown 围栏
        if (cb < 0) cb = reply.indexOf("```JSON");  // 找大写变体
        if (cb >= 0) {  // 找到围栏
            int ce = reply.indexOf("```", cb + 7);  // 找结尾围栏
            if (ce < 0) return reply.substring(0, cb).trim();  // 没有结尾：截到围栏前
            return (reply.substring(0, cb) + reply.substring(ce + 3)).trim();  // 否则拼接围栏两侧
        }  // if 结束
        if (b >= 0) return reply.substring(0, b).trim();  // 只有起始标记：截到起始处
        return reply;  // 都没有原样返回
    }  // 方法结束

    /**
     * 作用：把候选 JSON 解析成 Map 结构
     * 参数：json JSON 文本
     * 返回值：候选 Map；解析失败返回空 Map
     * 异常：无（异常内部捕获）
     */
    public static Map<String, Object> parse(String json) {  // parse：JSON 文本解析成 Map
        if (json == null || json.isBlank()) return null;  // 文本为空返回 null
        Map<?, ?> node = JsonUtils.extractAndParse(json, Map.class);  // 抠 JSON 并解析
        if (node == null) return null;  // 解析失败返回 null
        Map<String, Object> out = new LinkedHashMap<>();  // out：键转字符串的 Map
        for (Map.Entry<?, ?> en : node.entrySet()) {  // 遍历原 Map
            out.put(String.valueOf(en.getKey()), en.getValue());  // 键统一转字符串
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：解析单个域候选 Agent 的输出为统一域节点
     * 参数：out 该域 Agent 的原始输出；domain 域名
     * 返回值：统一结构的域节点 Map；解析失败返回带降级标记的空节点
     * 异常：无（异常内部捕获）
     */
    public static Map<String, Object> parseDomainNode(String out, String domain) {  // parseDomainNode：从域专家输出解析出域节点
        if (out == null || out.isBlank()) return null;  // 输出为空返回 null
        Map<String, Object> inner = unwrapDomain(parse(out), domain);  // 先整体解析再取该域
        if (inner != null) return wrap(inner, domain);  // 拿到了就包成 {domain: node}
        // 抢救：扫描文本中的 {...} 平衡片段，找含 candidates 数组的域对象
        for (String frag : balancedJsonFragments(out)) {  // 逐个片段尝试
            inner = unwrapDomain(parse(frag), domain);  // 同样取该域
            if (inner != null) return wrap(inner, domain);  // 拿到就返回
        }  // for 结束
        return null;  // 都失败返回 null
    }  // 方法结束

    /**
     * 作用：把域内容包成统一域节点结构（含 domain 标识与元信息）
     * 参数：inner 域内容；domain 域名
     * 返回值：包装后的域节点
     * 异常：无
     */
    private static Map<String, Object> wrap(Map<String, Object> inner, String domain) {  // wrap：把域节点包成 {domain: node} 形式
        Map<String, Object> wrapped = new LinkedHashMap<>();  // wrapped：结果
        wrapped.put(domain, inner);  // 放入域名对应的节点
        return wrapped;  // 返回
    }  // 方法结束

    /**
     * 作用：解包不同形态的域节点，取出真正的域内容（兼容数组/对象两种写法）
     * 参数：node 原始节点；domain 域名
     * 返回值：域内容 Map
     * 异常：无
     */
    @SuppressWarnings("unchecked")  // @SuppressWarnings：下面的强转未检查
    private static Map<String, Object> unwrapDomain(Map<String, Object> node, String domain) {  // unwrapDomain：从解析结果里取出该域的节点
        if (node == null) return null;  // 为空返回 null
        Object direct = node.get(domain);  // 直接取该域
        if (direct instanceof Map) {  // 是 Map（包裹形）
            // 包裹形：{"attraction":{"candidates":[...]}}
            Map<String, Object> m = (Map<String, Object>) direct;  // 强转
            return m.containsKey("candidates") || Boolean.TRUE.equals(m.get("degraded")) ? m : null;  // 有 candidates 或标了 degraded 才算(否则返回 null)
        }  // if 结束
        if (domain.equalsIgnoreCase(str(node.get("domain")))) {  // 扁平形：domain 字段等于该域
            // 扁平形：{"domain":"attraction","candidates":[...]}
            return node.containsKey("candidates") || Boolean.TRUE.equals(node.get("degraded")) ? node : null;  // 有 candidates 或 degraded 才算
        }  // if 结束
        return null;  // 都不匹配返回 null
    }  // 方法结束

    /**
     * 作用：从文本中依次切出括号平衡的完整 JSON 片段（跳过被截断的残片）
     * 参数：text 原始文本
     * 返回值：JSON 片段列表
     * 异常：无
     */
    private static List<String> balancedJsonFragments(String text) {  // balancedJsonFragments：扫描出文本里所有括号平衡的 JSON 片段
        List<String> out = new ArrayList<>();  // out：结果列表
        int depth = 0;  // depth：括号深度
        int start = -1;  // start：片段起点
        boolean inStr = false;  // inStr：是否在字符串里
        char prev = 0;  // prev：上一个字符（判转义）
        for (int i = 0; i < text.length() && out.size() < 20; i++) {  // 逐字符扫描，最多 20 个片段
            char c = text.charAt(i);  // 当前字符
            if (inStr) {  // 在字符串里
                if (c == '"' && prev != '\\') inStr = false;  // 遇到未转义的引号 → 出字符串
            } else if (c == '"') {  // 遇到引号
                inStr = true;  // 进入字符串
            } else if (c == '{') {  // 遇到 {
                if (depth == 0) start = i;  // 深度 0 时记起点
                depth++;  // 深度 +1
            } else if (c == '}') {  // 遇到 }
                if (depth > 0) {  // 深度大于 0
                    depth--;  // 深度 -1
                    if (depth == 0 && start >= 0) {  // 回到 0 层说明一个完整片段
                        out.add(text.substring(start, i + 1));  // 加入结果
                        start = -1;  // 重置起点
                    }  // if 结束
                }  // if 结束
            }  // if 结束
            prev = c;  // 记录上一个字符
        }  // for 结束
        return out;  // 返回片段列表
    }  // 方法结束

    /**
     * 作用：取候选数据中某个域的内容
     * 参数：candidates 候选数据；domain 域名
     * 返回值：域内容 Map；不存在返回空 Map
     * 异常：无
     */
    @SuppressWarnings("unchecked")  // @SuppressWarnings：强转未检查
    public static Map<String, Object> domain(Map<String, Object> candidates, String domain) {  // domain：取某域的节点
        if (candidates == null) return null;  // 候选为空返回 null
        Object d = candidates.get(domain);  // 取该域
        return d instanceof Map ? (Map<String, Object>) d : null;  // 是 Map 就返回，否则 null
    }  // 方法结束

    /**
     * 作用：取某个域的条目列表
     * 参数：candidates 候选数据；domain 域名
     * 返回值：条目 Map 列表；不存在返回空列表
     * 异常：无
     */
    @SuppressWarnings("unchecked")  // @SuppressWarnings：强转未检查
    public static List<Map<String, Object>> items(Map<String, Object> candidates, String domain) {  // items：取某域的候选条目列表
        Map<String, Object> d = domain(candidates, domain);  // 先取域节点
        if (d == null || !(d.get("candidates") instanceof List<?> list)) return new ArrayList<>();  // 域不存在或没有 candidates 列表 → 空列表
        List<Map<String, Object>> out = new ArrayList<>();  // out：结果
        for (Object o : list) {  // 遍历候选
            if (o instanceof Map) {  // 是 Map
                out.add((Map<String, Object>) o);  // 强转加入
            }  // if 结束
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：取某个域前 N 个候选名称
     * 参数：candidates 候选数据；domain 域名；max 最多取几个
     * 返回值：名称列表
     * 异常：无
     */
    public static List<String> names(Map<String, Object> candidates, String domain, int max) {  // names：取某域前 max 个候选名
        List<String> out = new ArrayList<>();  // out：结果
        for (Map<String, Object> it : items(candidates, domain)) {  // 遍历候选
            String n = str(it.get("name"));  // 取名称
            if (!n.isBlank()) out.add(n);  // 非空加入
            if (out.size() >= max) break;  // 够数就停
        }  // for 结束
        return out;  // 返回名单
    }  // 方法结束

    /**
     * 作用：计算一组候选的平均置信度
     * 参数：items 候选条目列表
     * 返回值：平均置信度 0~1；空列表返回 0
     * 异常：无
     */
    public static double avgConfidence(List<Map<String, Object>> items) {  // avgConfidence：算候选平均置信度
        double sum = 0;  // sum：累计
        int n = 0;  // n：有效条数
        for (Map<String, Object> it : items) {  // 遍历候选
            double c = confidenceOf(it);  // 取置信度
            if (c >= 0) {  // 有效值
                sum += c;  // 累加
                n++;  // 计数
            }  // if 结束
        }  // for 结束
        return n == 0 ? -1 : sum / n;  // 没有有效值返回 -1，否则返回均值
    }  // 方法结束

    /**
     * 作用：读取单个候选的置信度
     * 参数：item 候选条目
     * 返回值：置信度 0~1；缺失返回 0
     * 异常：无
     */
    public static double confidenceOf(Map<String, Object> item) {  // confidenceOf：取单条候选的置信度
        Object c = item.get("confidence");  // 取 confidence 字段
        if (c instanceof Number num) {  // 是数字
            double v = num.doubleValue();  // 转 double
            return (v >= 0 && v <= 1) ? v : -1;  // 0~1 才算有效，否则 -1
        }  // if 结束
        if (c != null) {  // 是其它非空值
            try {  // try
                double v = Double.parseDouble(String.valueOf(c));  // 按文本解析
                return (v >= 0 && v <= 1) ? v : -1;  // 0~1 才算有效
            } catch (NumberFormatException ignored) {  // 捕获异常
            }  // catch 结束
        }  // if 结束
        return -1;  // 取不到返回 -1
    }  // 方法结束

    /**
     * 作用：判断某个域是否处于降级状态（数据不全或全部来自兜底）
     * 参数：candidates 候选数据；domain 域名
     * 返回值：true=该域降级
     * 异常：无
     */
    public static boolean degraded(Map<String, Object> candidates, String domain) {  // degraded：某域是否降级
        Map<String, Object> d = domain(candidates, domain);  // 取域节点
        if (d == null) return true;  // 节点不存在 → 降级
        if (Boolean.TRUE.equals(d.get("degraded"))) return true;  // 自报 degraded
        return items(candidates, domain).isEmpty();  // 没有候选也算降级
    }  // 方法结束

    /**
     * 作用：判断某个域是否需要人工确认（置信度偏低）
     * 参数：candidates 候选数据；domain 域名
     * 返回值：true=需要确认
     * 异常：无
     */
    public static boolean markNeedsConfirm(Map<String, Object> candidates, String domain) {  // markNeedsConfirm：给低置信候选打“需确认”标记
        boolean marked = false;  // marked：是否有标记
        for (Map<String, Object> it : items(candidates, domain)) {  // 遍历候选
            double c = confidenceOf(it);  // 取置信度
            if (c >= 0 && c < 0.70) {  // 低于 0.70
                it.put("needsConfirm", true);  // 打标记
                marked = true;  // 记录有标记
            }  // if 结束
        }  // for 结束
        return marked;  // 返回是否有标记
    }  // 方法结束

    /**
     * 作用：给候选数据附加证据元信息（来源、等级、获取时间）
     * 参数：candidates 候选数据；source 数据来源；grade 证据等级；retrievedAt 获取时间
     * 返回值：无
     * 异常：无
     */
    public static void attachEvidence(Map<String, Object> candidates, String source, String grade, String retrievedAt) {  // attachEvidence：给候选附证据字段（来源/等级/时间）
        if (candidates == null) return;  // 候选为空返回
        for (String d : DOMAINS) {  // 遍历三个域
            for (Map<String, Object> it : items(candidates, d)) {  // 遍历该域候选
                it.putIfAbsent("source", source);  // 来源（已有不覆盖）
                it.putIfAbsent("grade", grade);  // 证据等级
                it.putIfAbsent("retrievedAt", retrievedAt);  // 抓取时间
            }  // for 结束
        }  // for 结束
    }  // 方法结束

    /**
     * 作用：读取候选数据的证据列表
     * 参数：candidates 候选数据
     * 返回值：证据 Map 列表；无证据返回空列表
     * 异常：无
     */
    public static List<Map<String, Object>> evidenceList(Map<String, Object> candidates) {  // evidenceList：生成每域的证据记录列表
        List<Map<String, Object>> out = new ArrayList<>();  // out：结果
        if (candidates == null) return out;  // 候选为空返回空列表
        for (String d : DOMAINS) {  // 遍历三个域
            List<Map<String, Object>> items = items(candidates, d);  // 取该域候选
            if (items.isEmpty()) continue;  // 没有候选跳过
            Map<String, Object> rec = new LinkedHashMap<>();  // rec：一条证据记录
            rec.put("domain", d);  // 域名
            rec.put("label", domainLabel(d));  // 域中文名
            rec.put("count", items.size());  // 候选条数
            String source = str(items.get(0).get("source"));  // 来源（取首条）
            rec.put("source", source.isBlank() ? "amap+local_db" : source);  // 来源为空用默认
            String grade = str(items.get(0).get("grade"));  // 等级（取首条）
            rec.put("grade", grade.isBlank() ? "A" : grade);  // 等级为空默认 A
            String at = str(items.get(0).get("retrievedAt"));  // 抓取时间
            rec.put("retrievedAt", at);  // 放入
            double avg = avgConfidence(items);  // 平均置信度
            rec.put("avgConfidence", avg < 0 ? 0.0 : Math.round(avg * 100) / 100.0);  // 保留两位小数
            boolean low = false;  // low：是否有低置信候选
            for (Map<String, Object> it : items) {  // 遍历候选
                double c = confidenceOf(it);  // 取置信度
                if (c >= 0 && c < 0.70) low = true;  // 低于 0.70 记低置信
            }  // if 结束
            rec.put("needsConfirm", low);  // 是否需要确认
            out.add(rec);  // 加入结果
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：生成证据摘要文本（来源与等级概览）
     * 参数：candidates 候选数据
     * 返回值：摘要文本；无证据返回空串
     * 异常：无
     */
    public static String evidenceSummary(Map<String, Object> candidates) {  // evidenceSummary：证据一句话摘要
        StringBuilder sb = new StringBuilder();  // sb：输出
        for (Map<String, Object> rec : evidenceList(candidates)) {  // 遍历证据记录
            if (sb.length() > 0) sb.append(" ");  // 非首项加空格
            sb.append(rec.get("label")).append("(").append(rec.get("grade")).append("级·均")  // 拼“域(等级·均分
                    .append(rec.get("avgConfidence"));  // 续：置信度)
            if (Boolean.TRUE.equals(rec.get("needsConfirm"))) sb.append("·低置信已标注");  // 需要确认就加说明
            sb.append(")");  // 补右括号
        }  // for 结束
        return sb.toString();  // 返回摘要
    }  // 方法结束

    /**
     * 作用：区域名归一化（去掉"市""区"等后缀，统一写法）
     * 参数：area 原始区域名
     * 返回值：归一化区域名
     * 异常：无
     */
    private static String normalizeArea(String area) {  // normalizeArea：区域名归一（切掉·后缀）
        if (area == null) return "";  // 为空返回空串
        String a = area.trim();  // a：去空格
        int sep = a.indexOf('·');  // 找中点符号
        if (sep > 0) a = a.substring(0, sep).trim();  // 有就取前半段
        return a;  // 返回
    }  // 方法结束

    /**
     * 作用：按区域给候选分组，生成"同区可一天玩"的分组提示
     * 参数：candidates 候选数据
     * 返回值：分组提示文本
     * 异常：无
     */
    public static String areaGroupHint(Map<String, Object> candidates) {  // areaGroupHint：按区域文本给景点分组提示
        List<Map<String, Object>> spots = items(candidates, "attraction");  // 取景点候选
        if (spots.size() < 2) return "";  // 少于 2 个无意义
        // 归一化 area → 景点名（保序；area 空白的归"未标注"）
        Map<String, List<String>> groups = new LinkedHashMap<>();  // groups：区域 → 景点名列表
        for (Map<String, Object> it : spots) {  // 遍历景点
            String area = normalizeArea(str(it.get("area")));  // area：归一后的区域
            String name = str(it.get("name")).trim();  // name：景点名
            if (name.isBlank()) continue;  // 空名跳过
            groups.computeIfAbsent(area.isBlank() ? "未标注区域" : area, k -> new ArrayList<>()).add(name);  // 按区域归组（空区域归“未标注区域”）
        }  // for 结束
        if (groups.size() < 2) return ""; // 全在同区域/全未标注 → 分组无意义  // 只有一组说明全在同区域，无分组价值
        StringBuilder sb = new StringBuilder("【景点区域分组（按地理邻近预分组，代码计算）】\n");  // sb：分组标题
        int gi = 1;  // gi：组号
        for (Map.Entry<String, List<String>> en : groups.entrySet()) {  // 遍历分组
            sb.append("组").append(gi++).append("（").append(en.getKey()).append("）：")  // 组N（区域）：
              .append(String.join("、", en.getValue())).append("\n");  // 续：顿号连接的景点名
        }  // for 结束
        return sb.toString().trim();  // 返回去空格的分组文本
    }  // 方法结束

    /**
     * 作用：按坐标距离对景点聚类，每簇挑一个代表景点用于排程
     * 参数：candidates 候选数据；maxSights 最多输出几个代表景点
     * 返回值：代表景点清单文本
     * 异常：无
     */
    public static String clusterRepSights(Map<String, Object> candidates, int maxSights) {  // clusterRepSights：按坐标簇取代表景点（逗号分隔）
        List<Map<String, Object>> spots = items(candidates, "attraction");  // 取景点候选
        if (spots.isEmpty()) return "";  // 没有返回空串
        // ★2026-09-12 去掉硬上限 8（实测根因）：14 天长线候选 18 个，但原来无论传多少都被压到 8，
        //   导致"代表景点≤8 → 簇≤8 → cutIntoDays 最多切 8 天 → 14 天切不出 → 天骨架 return null
        //   → 回退整单生成 → 结构乱(缺天/重复/碎片)"。上限改由调用方按天数决定，这里只保下限 3。
        int cap = Math.max(3, maxSights <= 0 ? 8 : maxSights);  // cap：代表景点上限（至少 3，未指定按 8）
        List<String> reps = new ArrayList<>();  // reps：代表景点名
        List<Map<String, Object>> coord = new ArrayList<>();  // coord：带坐标的候选
        for (Map<String, Object> it : spots) {  // 遍历景点
            if (parseLocation(str(it.get("location"))) != null) coord.add(it);  // 有有效坐标就加入
        }  // for 结束
        if (coord.size() < 2) { // 坐标不足：直接按候选顺序取前 cap 个  // 坐标不足 2 个
            for (Map<String, Object> it : spots) {  // 直接按顺序取前 cap 个
                String nm = str(it.get("name")).trim();  // 取名称
                if (nm.isBlank()) continue;  // 空名跳过
                reps.add(nm);  // 加入代表
                if (reps.size() >= cap) break;  // 够数就停
            }  // if 结束
            return String.join(",", reps);  // 逗号拼接返回
        }  // if 结束
        // 贪心成簇，每簇取首个成员为代表
        List<double[]> centroids = new ArrayList<>();  // centroids：各簇质心
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();  // seen：已用过的景点名
        for (Map<String, Object> it : coord) {  // 遍历带坐标的候选
            double[] p = parseLocation(str(it.get("location")));  // p：坐标
            String nm = str(it.get("name")).trim();  // 名称
            if (nm.isBlank()) continue;  // 空名跳过
            boolean joined = false;  // joined：是否并入已有簇
            for (int g = 0; g < centroids.size(); g++) {  // 遍历已有簇
                double[] c = centroids.get(g);  // c：簇质心
                double d = haversineKm(c[1], c[0], p[1], p[0]);  // 算到质心的距离
                if (d <= CLUSTER_KM) { // 并入既有簇：簇代表点(质心)不变，仅计数影响后续距离判定  // 5 公里内就并入
                    joined = true;  // 标记已并入
                    break;  // 跳出
                }  // if 结束
            }  // for 结束
            if (!joined) {  // 没有可并入的簇
                if (seen.add(nm)) { // 新簇：该候选即为簇代表  // 新簇：该景点即代表
                    reps.add(nm);  // 加入代表
                    centroids.add(new double[]{p[0], p[1]});  // 记下质心
                    if (reps.size() >= cap) break;  // 够数就停
                }  // if 结束
            }  // if 结束
        }  // for 结束
        // ★边界：全部候选挤在一簇（≤5km 全并入）时只有 1 个代表 → 路线Agent 无点对可查衔接。
        // 补足同簇内候选（按原始顺序）到至少 2 个点（≤cap），让市内衔接查询仍有对象。
        if (reps.size() < 2) {  // 代表不足 2 个（全挤在一簇）
            for (Map<String, Object> it : spots) {  // 从同簇里补足
                String nm = str(it.get("name")).trim();  // 取名称
                if (nm.isBlank() || seen.contains(nm)) continue;  // 空名或已用过就跳过
                reps.add(nm);  // 加入代表
                if (reps.size() >= Math.max(2, Math.min(cap, 3))) break;  // 最多补到 3 个
            }  // if 结束
        }  // if 结束
        return String.join(",", reps);  // 逗号拼接返回
    }  // 方法结束

    /**
     * 作用：按坐标给候选分组，生成地理聚类提示
     * 参数：candidates 候选数据
     * 返回值：聚类提示文本
     * 异常：无
     */
    public static String coordinateGroupHint(Map<String, Object> candidates) {  // coordinateGroupHint：按真实坐标给景点分组提示
        List<Map<String, Object>> spots = items(candidates, "attraction");  // 取景点候选
        if (spots.size() < 2) return "";  // 少于 2 个无意义
        // 只保留带有效坐标的候选（保序）
        List<Map<String, Object>> coord = new ArrayList<>();  // coord：带坐标的候选
        for (Map<String, Object> it : spots) {  // 遍历景点
            if (parseLocation(str(it.get("location"))) != null) coord.add(it);  // 有坐标就加入
        }  // for 结束
        if (coord.size() < 2) return ""; // 坐标不足 → 交给文本分组  // 坐标不足返回空串（交给文本分组）
        // 贪心聚类：按原始顺序遍历，与已有组质心距离 ≤ 阈值(默认5km)则入组，否则开新组
        List<List<Map<String, Object>>> groups = new ArrayList<>();  // groups：分组结果
        List<double[]> centroids = new ArrayList<>(); // 每组质心 [lng,lat]  // centroids：每组质心 [lng,lat]
        for (Map<String, Object> it : coord) {  // 遍历带坐标候选
            double[] p = parseLocation(str(it.get("location")));  // p：坐标
            int best = -1;  // best：最合适的组，-1 表示新组
            double bestDist = Double.MAX_VALUE;  // bestDist：最近距离
            for (int g = 0; g < groups.size(); g++) {  // 遍历已有组
                double[] c = centroids.get(g);  // c：组质心
                double d = haversineKm(c[1], c[0], p[1], p[0]); // (lat1,lng1,lat2,lng2)  // 算距离（lat1,lng1,lat2,lng2）
                if (d <= CLUSTER_KM && d < bestDist) { best = g; bestDist = d; }  // 5 公里内且更近就记下
            }  // for 结束
            if (best >= 0) {  // 找到可并入的组
                groups.get(best).add(it);  // 加入该组
                double[] c = centroids.get(best);  // c：该组质心
                c[0] = (c[0] * (groups.get(best).size() - 1) + p[0]) / groups.get(best).size();  // 增量更新质心经度
                c[1] = (c[1] * (groups.get(best).size() - 1) + p[1]) / groups.get(best).size();  // 增量更新质心纬度
            } else {  // 否则
                List<Map<String, Object>> g = new ArrayList<>();  // g：新组
                g.add(it);  // 放入该点
                groups.add(g);  // 加入组列表
                centroids.add(new double[]{p[0], p[1]});  // 记下质心
            }  // if 结束
        }  // for 结束
        if (groups.size() < 2) return ""; // 全部近邻成一簇 → 无分组价值  // 全部近邻成一簇 → 无分组价值
        StringBuilder sb = new StringBuilder("【景点区域分组（按真实坐标邻近预分组，代码计算）】\n");  // sb：分组标题
        for (int gi = 0; gi < groups.size(); gi++) {  // 遍历分组
            List<Map<String, Object>> g = groups.get(gi);  // g：该组
            sb.append("组").append(gi + 1).append("（").append(groupLabel(g)).append("）：")  // 组N（标签）：
              .append(String.join("、", namesOf(g))).append("\n");  // 续：顿号连接的景点名
        }  // for 结束
        return sb.toString().trim();  // 返回去空格的分组文本
    }  // 方法结束

    /**
     * 作用：生成聚类摘要（几组、每组几个）
     * 参数：candidates 候选数据
     * 返回值：摘要文本
     * 异常：无
     */
    public static String clusterHintSummary(Map<String, Object> candidates) {  // clusterHintSummary：给一行聚类摘要（决策依据用）
        List<Map<String, Object>> spots = items(candidates, "attraction");  // 取景点候选
        if (spots.size() < 2) return "";  // 少于 2 个返回空串
        int withCoord = 0;  // withCoord：带坐标的候选数
        for (Map<String, Object> it : spots) {  // 遍历景点
            if (parseLocation(str(it.get("location"))) != null) withCoord++;  // 有坐标就计数
        }  // for 结束
        String coordHint = coordinateGroupHint(candidates);  // 先试坐标分组
        if (!coordHint.isBlank()) {  // 坐标分组成功
            return "坐标聚类分组已注入：" + countGroups(coordHint) + " 组（景点候选 " + withCoord + "/" + spots.size()  // 返回“坐标聚类分组已注入：N 组（x/y 带坐标，≤5km 成簇）”
                    + " 个带坐标，≤5km 成簇）";  // 续行
        }  // if 结束
        String areaHint = areaGroupHint(candidates);  // 回退文本分组
        if (!areaHint.isBlank()) {  // 文本分组成功
            // 回退原因按带坐标数区分：坐标齐但相距>5km 未成簇 / 坐标太少根本无从成簇
            String why = withCoord >= 2  // why：回退原因（按带坐标数区分）
                    ? "坐标成簇不足(" + withCoord + "/" + spots.size() + " 个带坐标但相距>5km 未成簇)"  // 坐标够但相距太远
                    : "坐标不足(" + withCoord + "/" + spots.size() + " 个带坐标)";  // 坐标太少无从成簇
            return why + "，回退区域文本分组已注入：" + countGroups(areaHint) + " 组";  // 返回原因 + 文本分组组数
        }  // if 结束
        return "无分组提示（景点候选 " + spots.size() + " 个，坐标仅 " + withCoord + " 个，无法成簇）";  // 都没有分组返回说明
    }  // 方法结束

    /**
     * 作用：统计分组提示中的组数
     * 参数：hint 分组提示文本
     * 返回值：组数
     * 异常：无
     */
    private static int countGroups(String hint) {  // countGroups：数提示文本里有几组
        int n = 0;  // n：计数
        for (String line : hint.split("\n")) {  // 逐行看
            String t = line.trim();  // t：去空格
            if (t.startsWith("组") && t.contains("（")) n++;  // 以“组”开头且带括号就是一组
        }  // for 结束
        return n;  // 返回组数
    }  // 方法结束

    /**
     * 作用：按行程天数推算每个域应取的候选数量
     * 参数：days 行程天数文本
     * 返回值：该天数对应的每域候选数
     * 异常：无
     */
    public static int candidateCountPerDomain(String days) {  // candidateCountPerDomain：按天数算每域候选数
        int n = 3;  // n：默认 3
        if (days != null) {  // 有天数
            try {  // try
                int d = Integer.parseInt(days.replaceAll("[^0-9]", ""));  // 只留数字
                if (d > 0) {  // 有效天数
                    n = (int) Math.round(d * 2.5);  // 天数 × 2.5 四舍五入
                }  // if 结束
            } catch (Exception ignored) { }  // 捕获异常忽略
        }  // for 结束
        return Math.max(3, Math.min(n, 18));  // 夹在 3~18 之间返回
    }  // 方法结束

    /**
     * 作用：输出聚类统计（组数/每组成员/平均半径）
     * 参数：candidates 候选数据
     * 返回值：统计 Map
     * 异常：无
     */
    public static Map<String, Object> clusterStats(Map<String, Object> candidates) {  // clusterStats：聚类统计（观测/前端展示）
        Map<String, Object> m = new java.util.LinkedHashMap<>();  // m：结果 Map
        List<Map<String, Object>> spots = items(candidates, "attraction");  // 取景点候选
        m.put("attractionCount", spots.size());  // 景点总数
        int withCoord = 0;  // withCoord：带坐标数
        for (Map<String, Object> it : spots) {  // 遍历景点
            if (parseLocation(str(it.get("location"))) != null) withCoord++;  // 有坐标就计数
        }  // for 结束
        m.put("withLocation", withCoord);  // 带坐标数
        m.put("locationCoverage", spots.isEmpty() ? 0 : Math.round(withCoord * 1000.0 / spots.size()) / 10.0);  // 坐标覆盖率（百分比，一位小数）
        String coordHint = coordinateGroupHint(candidates);  // 坐标分组提示
        int cg = coordHint.isBlank() ? 0 : countGroups(coordHint);  // 坐标组数
        String areaHint = areaGroupHint(candidates);  // 文本分组提示
        int ag = areaHint.isBlank() ? 0 : countGroups(areaHint);  // 文本组数
        m.put("coordGroups", cg);  // 坐标组数
        m.put("areaGroups", ag);  // 文本组数
        m.put("mode", cg > 0 ? "coordinate" : (ag > 0 ? "area" : "none"));  // mode：坐标分组 / 文本分组 / 无
        List<Integer> sizes = new ArrayList<>();  // sizes：每组景点数
        String hint = cg > 0 ? coordHint : areaHint;  // hint：实际生效的分组提示
        if (!hint.isBlank()) {  // 有提示
            for (String line : hint.split("\n")) {  // 逐行看
                String t = line.trim();  // t：去空格
                int colon = t.indexOf('：');  // 找冒号
                if (t.startsWith("组") && t.contains("（") && colon > 0) {  // 是组行
                    int n = 0;  // n：该组成员数
                    for (String s : t.substring(colon + 1).split("、")) {  // 按顿号拆
                        if (!s.isBlank()) n++;  // 非空就计数
                    }  // for 结束
                    sizes.add(n);  // 加入组大小
                }  // if 结束
            }  // for 结束
        }  // if 结束
        m.put("groupSizes", sizes);  // 组大小列表
        return m;  // 返回统计
    }  // 方法结束

    /**
     * 作用：生成一组的标签（区域名或代表点）
     * 参数：g 组内候选列表
     * 返回值：组标签文本
     * 异常：无
     */
    private static String groupLabel(List<Map<String, Object>> g) {  // groupLabel：给一组景点起标签（区域众数或跨度）
        Map<String, Integer> freq = new LinkedHashMap<>();  // freq：区域出现次数
        for (Map<String, Object> it : g) {  // 遍历该组
            String area = normalizeArea(str(it.get("area")));  // area：归一后的区域
            if (!area.isBlank()) freq.merge(area, 1, Integer::sum);  // 非空就计数
        }  // for 结束
        if (!freq.isEmpty()) {  // 有区域数据
            String top = null; int max = 0;  // top：出现最多的区域，max：次数
            for (Map.Entry<String, Integer> en : freq.entrySet()) {  // 遍历统计
                if (en.getValue() > max) { max = en.getValue(); top = en.getKey(); }  // 次数更多就更新
            }  // for 结束
            return top;  // 返回众数区域
        }  // if 结束
        // 无 area：算组内最大跨度
        double span = 0;  // span：最大跨度公里
        for (int i = 0; i < g.size(); i++) {  // 遍历组内点
            double[] a = parseLocation(str(g.get(i).get("location")));  // a：坐标
            if (a == null) continue;  // 无坐标跳过
            for (int j = i + 1; j < g.size(); j++) {  // 与其后的点比
                double[] b = parseLocation(str(g.get(j).get("location")));  // b：另一个点坐标
                if (b == null) continue;  // 无坐标跳过
                double d = haversineKm(a[1], a[0], b[1], b[0]);  // 算距离
                if (d > span) span = d;  // 更大就更新
            }  // for 结束
        }  // for 结束
        return "近邻·跨度约" + Math.max(1, Math.round(span)) + "km";  // 返回“近邻·跨度约Nkm”
    }  // 方法结束

    /**
     * 作用：取一组候选的名称列表
     * 参数：g 组内候选列表
     * 返回值：名称列表
     * 异常：无
     */
    private static List<String> namesOf(List<Map<String, Object>> g) {  // namesOf：取一组的景点名列表
        List<String> out = new ArrayList<>();  // out：结果
        for (Map<String, Object> it : g) {  // 遍历该组
            String n = str(it.get("name")).trim();  // n：名称
            if (!n.isBlank()) out.add(n);  // 非空加入
        }  // for 结束
        return out;  // 返回
    }  // 方法结束

    /**
     * 作用：解析 "lat,lng" 形式的坐标文本
     * 参数：loc 坐标文本
     * 返回值：[纬度, 经度]；解析失败返回 null
     * 异常：无
     */
    private static double[] parseLocation(String loc) {  // parseLocation：解析“经度,纬度”文本
        if (loc == null || loc.isBlank()) return null;  // 为空返回 null
        int comma = loc.indexOf(',');  // 找英文逗号
        if (comma < 0) comma = loc.indexOf('，');  // 没有就找中文逗号
        if (comma < 0) return null;  // 都没有返回 null
        try {  // try
            double lng = Double.parseDouble(loc.substring(0, comma).trim());  // 经度
            double lat = Double.parseDouble(loc.substring(comma + 1).trim());  // 纬度
            if (lng < -180 || lng > 180 || lat < -90 || lat > 90) return null;  // 经纬度范围校验
            return new double[]{lng, lat};  // 返回 [经度, 纬度]
        } catch (NumberFormatException e) {  // 捕获异常
            return null;  // 返回 null
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：计算两点球面距离（Haversine）
     * 参数：lat1 起点纬度；lng1 起点经度；lat2 终点纬度；lng2 终点经度
     * 返回值：距离公里数
     * 异常：无
     */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {  // haversineKm：球面距离（公里）
        double r = 6371.0;  // r：地球半径
        double dLat = Math.toRadians(lat2 - lat1);  // 纬度差转弧度
        double dLng = Math.toRadians(lng2 - lng1);  // 经度差转弧度
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)  // Haversine 前半：sin²(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))  // ＋ cos(lat1)·cos(lat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);  // ＋ sin²(dLng/2)，构成 Haversine 后半
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));  // 标准球面距离公式
    }  // 方法结束

    /** 坐标聚类半径阈值（km）：市内"同片区"邻近距离 */
    private static final double CLUSTER_KM = 5.0;  // CLUSTER_KM：聚类阈值 5 公里

    /**
     * 作用：把候选数据转成注入提示词的紧凑文本（每域限条数）
     * 参数：candidates 候选数据；maxPerDomain 每域最多输出条数
     * 返回值：提示词文本
     * 异常：无
     */
    public static String toPromptText(Map<String, Object> candidates, int maxPerDomain) {  // toPromptText：把候选渲染成提示词文本
        if (candidates == null || candidates.isEmpty()) return "";  // 候选为空返回空串
        StringBuilder sb = new StringBuilder();  // sb：输出
        for (String d : DOMAINS) {  // 逐个域
            List<Map<String, Object>> items = items(candidates, d);  // 该域候选
            if (items.isEmpty()) continue;  // 没有跳过
            sb.append("◆").append(domainLabel(d)).append("候选：\n");  // ◆域候选：
            int i = 0;  // i：已输出条数
            for (Map<String, Object> it : items) {  // 遍历候选
                if (i++ >= maxPerDomain) break;  // 够数就停
                sb.append("- ").append(str(it.get("name")));  // - 名称
                String price = str(it.get("price"));  // 价格
                if (!price.isBlank()) sb.append("（").append(price).append("）");  // 有价格就带括号
                String rating = str(it.get("rating"));  // 评分
                if (!rating.isBlank()) sb.append(" 评分").append(rating);  // 有评分就带
                String open = str(it.get("openTime"));  // 开放时间
                if (!open.isBlank()) sb.append(" 开放").append(open);  // 有就带
                String area = str(it.get("area"));  // 区域
                if (!area.isBlank()) sb.append(" 区域").append(area);  // 有就带
                String reason = str(it.get("reason"));  // 推荐理由
                if (!reason.isBlank()) sb.append(" — ").append(reason);  // 有就用 — 引出
                if (Boolean.TRUE.equals(it.get("needsConfirm"))) sb.append(" ⚠信息待确认");  // 需确认的加 ⚠ 标记
                sb.append("\n");  // 换行
            }  // for 结束
        }  // for 结束
        return sb.toString().trim();  // 返回去空格的结果
    }  // 方法结束

    /**
     * 作用：从候选 JSON 文本直接算平均置信度（无需先解析结构）
     * 参数：json 候选 JSON 文本
     * 返回值：平均置信度 0~1；无法解析返回 0
     * 异常：无
     */
    public static double avgConfidenceFromText(String json) {  // avgConfidenceFromText：从文本估算平均置信度
        Map<String, Object> node = parse(json);  // 先按 JSON 解析
        if (node != null) {  // 解析成功
            double sum = 0;  // sum：累计
            int n = 0;  // n：有效域数
            for (String d : DOMAINS) {  // 逐个域
                double v = avgConfidence(items(node, d));  // 该域平均置信度
                if (v >= 0) {  // 有效值
                    sum += v;  // 累加
                    n++;  // 计数
                }  // if 结束
            }  // for 结束
            return n == 0 ? -1 : sum / n;  // 没有有效值返回 -1
        }  // if 结束
        if (json == null || !json.contains("confidence")) return -1;  // 没有 confidence 字样返回 -1
        Matcher m = CONF.matcher(json);  // 正则匹配所有置信度
        double sum = 0;  // sum：累计
        int n = 0;  // n：条数
        while (m.find()) {  // 逐个匹配
            try {  // try
                double v = Double.parseDouble(m.group(1));  // 取值
                if (v >= 0 && v <= 1) {  // 0~1 才有效
                    sum += v;  // 累加
                    n++;  // 计数
                }  // if 结束
            } catch (NumberFormatException ignored) {  // 捕获异常
            }  // catch 结束
        }  // for 结束
        return n == 0 ? -1 : sum / n;  // 返回均值或 -1
    }  // 方法结束
    /**
     * 作用：安全转字符串
     * 参数：o 任意对象
     * 返回值：对象字符串；null 返回空串
     * 异常：无
     */

    private static String str(Object o) {  // str：null 安全字符串
        return o == null ? "" : String.valueOf(o);  // null 归空串
    }  // 方法结束
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private CandidateUtil() {  // 私有构造：禁止 new
    }  // 构造器结束
}  // 类结束
