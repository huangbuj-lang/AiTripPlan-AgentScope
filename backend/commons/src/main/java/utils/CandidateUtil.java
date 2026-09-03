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
 * {"attraction":{"city":"..","candidates":[{"name":"","reason":"","price":"","rating":"","openTime":"","duration":"","area":"","confidence":0.9,"source":"amap+local_db","needsConfirm":false}],"degraded":false}, "hotel":{...}, "dining":{...}}
 * 来源分级（PDF §十五）：S=官方站点（暂无）/ A=高德API与本地知识库 / B=专业平台 / C=LLM常识补充。
 * 置信度门槛（PDF §十八）：域均值 < 0.70 触发一次重查，仍低则逐候选标注 needsConfirm=true（"建议出发前确认"）。
 */
public final class CandidateUtil {

    /** 三个域的 key（域专家Agent与聚合/校验/证据链统一用这套命名） */
    public static final String[] DOMAINS = {"attraction", "hotel", "dining"};

    private static final String BLOCK_BEGIN = "【域候选JSON】";
    private static final String BLOCK_END = "【域候选JSON结束】";
    private static final Pattern CONF = Pattern.compile("\"confidence\"\\s*:\\s*(0?(?:\\.\\d+)|1(?:\\.0+)?)");

    /** 域中文名 */
    public static String domainLabel(String domain) {
        return switch (domain == null ? "" : domain) {
            case "attraction" -> "景点";
            case "hotel" -> "酒店";
            case "dining" -> "餐饮";
            default -> domain == null ? "" : domain;
        };
    }

    /** 从子 Agent 完整回复中提取 【域候选JSON】...【域候选JSON结束】 块正文（无块返回空串） */
    public static String extractBlock(String reply) {
        if (reply == null) return "";
        int b = reply.indexOf(BLOCK_BEGIN);
        if (b < 0) return "";
        int s = b + BLOCK_BEGIN.length();
        int e = reply.indexOf(BLOCK_END, s);
        if (e < 0) e = reply.length();
        return reply.substring(s, e).trim();
    }

    /** 从回复正文中剥离候选 JSON 块（候选已单独入状态中心，正文进聚合/缓存/渲染时不再夹带大段 JSON） */
    public static String stripBlock(String reply) {
        if (reply == null) return "";
        int b = reply.indexOf(BLOCK_BEGIN);
        if (b < 0) return reply;
        int e = reply.indexOf(BLOCK_END, b);
        if (e < 0) return reply.substring(0, b).trim();
        return (reply.substring(0, b) + reply.substring(e + BLOCK_END.length())).trim();
    }

    /** 解析候选 JSON（容忍围栏/前后缀）；解析失败或非对象返回 null。 */
    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return null;
        Map<?, ?> node = JsonUtils.extractAndParse(json, Map.class);
        if (node == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : node.entrySet()) {
            out.put(String.valueOf(en.getKey()), en.getValue());
        }
        return out;
    }

    /**
     * 解析域专家回复为【包裹形】候选 {"attraction":{...}}（与 items/degraded 期望一致）。
     * 兼容模型输出：包裹形 {"attraction":{...}} 与扁平形 {"domain":"attraction",...}（提示词约定为后者）。
     * 整体解析失败时扫描回复中的平衡 JSON 片段抢救（防模型夹带思考文字）。无可用候选返回 null。
     */
    public static Map<String, Object> parseDomainNode(String out, String domain) {
        if (out == null || out.isBlank()) return null;
        Map<String, Object> inner = unwrapDomain(parse(out), domain);
        if (inner != null) return wrap(inner, domain);
        // 抢救：扫描文本中的 {...} 平衡片段，找含 candidates 数组的域对象
        for (String frag : balancedJsonFragments(out)) {
            inner = unwrapDomain(parse(frag), domain);
            if (inner != null) return wrap(inner, domain);
        }
        return null;
    }

    /** 包裹成 {"domain": node} 形（items/degraded 的标准输入形状） */
    private static Map<String, Object> wrap(Map<String, Object> inner, String domain) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(domain, inner);
        return wrapped;
    }

    /** 从解析后的节点取该域候选节点（返回扁平形 domain 节点）；形状不符返回 null */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapDomain(Map<String, Object> node, String domain) {
        if (node == null) return null;
        Object direct = node.get(domain);
        if (direct instanceof Map) {
            // 包裹形：{"attraction":{"candidates":[...]}}
            Map<String, Object> m = (Map<String, Object>) direct;
            return m.containsKey("candidates") || Boolean.TRUE.equals(m.get("degraded")) ? m : null;
        }
        if (domain.equalsIgnoreCase(str(node.get("domain")))) {
            // 扁平形：{"domain":"attraction","candidates":[...]}
            return node.containsKey("candidates") || Boolean.TRUE.equals(node.get("degraded")) ? node : null;
        }
        return null;
    }

    /** 扫描文本中所有平衡的 {...} JSON 片段（跳过字符串内的花括号），最多返回 20 个 */
    private static List<String> balancedJsonFragments(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        char prev = 0;
        for (int i = 0; i < text.length() && out.size() < 20; i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '"' && prev != '\\') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
            prev = c;
        }
        return out;
    }

    /** 取指定域节点（缺域或结构不符返回 null） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> domain(Map<String, Object> candidates, String domain) {
        if (candidates == null) return null;
        Object d = candidates.get(domain);
        return d instanceof Map ? (Map<String, Object>) d : null;
    }

    /** 取指定域候选列表（无数据返回空列表）。★返回原 Map 引用（非拷贝）：markNeedsConfirm/attachEvidence 依赖就地修改。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(Map<String, Object> candidates, String domain) {
        Map<String, Object> d = domain(candidates, domain);
        if (d == null || !(d.get("candidates") instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    /** 指定域候选名称列表（去空，最多 max 个） */
    public static List<String> names(Map<String, Object> candidates, String domain, int max) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> it : items(candidates, domain)) {
            String n = str(it.get("name"));
            if (!n.isBlank()) out.add(n);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 单域置信度均值（无有效值返回 -1） */
    public static double avgConfidence(List<Map<String, Object>> items) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> it : items) {
            double c = confidenceOf(it);
            if (c >= 0) {
                sum += c;
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    /** 单候选置信度（缺失/非法返回 -1） */
    public static double confidenceOf(Map<String, Object> item) {
        Object c = item.get("confidence");
        if (c instanceof Number num) {
            double v = num.doubleValue();
            return (v >= 0 && v <= 1) ? v : -1;
        }
        if (c != null) {
            try {
                double v = Double.parseDouble(String.valueOf(c));
                return (v >= 0 && v <= 1) ? v : -1;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /** 域是否降级（degraded=true 或无候选） */
    public static boolean degraded(Map<String, Object> candidates, String domain) {
        Map<String, Object> d = domain(candidates, domain);
        if (d == null) return true;
        if (Boolean.TRUE.equals(d.get("degraded"))) return true;
        return items(candidates, domain).isEmpty();
    }

    /**
     * 置信度门槛（PDF §十八）：候选置信度 < 0.70 逐条标注 needsConfirm=true（"建议出发前确认"）。
     * 返回是否有低置信候选。（域均值 <0.70 触发重查的逻辑在调用方，标注本身逐候选进行。）
     */
    public static boolean markNeedsConfirm(Map<String, Object> candidates, String domain) {
        boolean marked = false;
        for (Map<String, Object> it : items(candidates, domain)) {
            double c = confidenceOf(it);
            if (c >= 0 && c < 0.70) {
                it.put("needsConfirm", true);
                marked = true;
            }
        }
        return marked;
    }

    /** 为候选补充证据字段（来源/等级/抓取时间）：数据来自工具链（高德+本地库）记 A 级。 */
    public static void attachEvidence(Map<String, Object> candidates, String source, String grade, String retrievedAt) {
        if (candidates == null) return;
        for (String d : DOMAINS) {
            for (Map<String, Object> it : items(candidates, d)) {
                it.putIfAbsent("source", source);
                it.putIfAbsent("grade", grade);
                it.putIfAbsent("retrievedAt", retrievedAt);
            }
        }
    }

    /** 证据链记录列表（每域一条：域/来源/等级/候选数/置信度均值/低置信标记；供 plan.evidence 与事件流） */
    public static List<Map<String, Object>> evidenceList(Map<String, Object> candidates) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (candidates == null) return out;
        for (String d : DOMAINS) {
            List<Map<String, Object>> items = items(candidates, d);
            if (items.isEmpty()) continue;
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("domain", d);
            rec.put("label", domainLabel(d));
            rec.put("count", items.size());
            String source = str(items.get(0).get("source"));
            rec.put("source", source.isBlank() ? "amap+local_db" : source);
            String grade = str(items.get(0).get("grade"));
            rec.put("grade", grade.isBlank() ? "A" : grade);
            String at = str(items.get(0).get("retrievedAt"));
            rec.put("retrievedAt", at);
            double avg = avgConfidence(items);
            rec.put("avgConfidence", avg < 0 ? 0.0 : Math.round(avg * 100) / 100.0);
            boolean low = false;
            for (Map<String, Object> it : items) {
                double c = confidenceOf(it);
                if (c >= 0 && c < 0.70) low = true;
            }
            rec.put("needsConfirm", low);
            out.add(rec);
        }
        return out;
    }

    /** 证据链一行摘要（事件流展示，如 "景点(A级·均0.88) 酒店(A级·均0.91·低置信)"） */
    public static String evidenceSummary(Map<String, Object> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> rec : evidenceList(candidates)) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(rec.get("label")).append("(").append(rec.get("grade")).append("级·均")
                    .append(rec.get("avgConfidence"));
            if (Boolean.TRUE.equals(rec.get("needsConfirm"))) sb.append("·低置信已标注");
            sb.append(")");
        }
        return sb.toString();
    }

    /** 
     * 景点区域分组提示（纯代码空间聚类·文本分组版）：把景点候选按 area 文本归堆，
     * 输出"每个区域含哪些景点"的清单，供聚合提示词按"同区域尽量同一天"约束排天。
     * 景点不足 2 个、无 area 数据或全在同区域时不产生分组价值，返回空串。
     */
    public static String areaGroupHint(Map<String, Object> candidates) {
        List<Map<String, Object>> spots = items(candidates, "attraction");
        if (spots.size() < 2) return "";
        // area → 景点名（保序；area 空白的归"未标注"）
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map<String, Object> it : spots) {
            String area = str(it.get("area")).trim();
            String name = str(it.get("name")).trim();
            if (name.isBlank()) continue;
            groups.computeIfAbsent(area.isBlank() ? "未标注区域" : area, k -> new ArrayList<>()).add(name);
        }
        if (groups.size() < 2) return ""; // 全在同区域/全未标注 → 分组无意义
        StringBuilder sb = new StringBuilder("【景点区域分组（按地理邻近预分组，代码计算）】\n");
        int gi = 1;
        for (Map.Entry<String, List<String>> en : groups.entrySet()) {
            sb.append("组").append(gi++).append("（").append(en.getKey()).append("）：")
              .append(String.join("、", en.getValue())).append("\n");
        }
        return sb.toString().trim();
    }

    /** 聚合/校验用紧凑文本（每域最多 maxPerDomain 条，供提示词拼装） */
    public static String toPromptText(Map<String, Object> candidates, int maxPerDomain) {
        if (candidates == null || candidates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String d : DOMAINS) {
            List<Map<String, Object>> items = items(candidates, d);
            if (items.isEmpty()) continue;
            sb.append("◆").append(domainLabel(d)).append("候选：\n");
            int i = 0;
            for (Map<String, Object> it : items) {
                if (i++ >= maxPerDomain) break;
                sb.append("- ").append(str(it.get("name")));
                String price = str(it.get("price"));
                if (!price.isBlank()) sb.append("（").append(price).append("）");
                String rating = str(it.get("rating"));
                if (!rating.isBlank()) sb.append(" 评分").append(rating);
                String open = str(it.get("openTime"));
                if (!open.isBlank()) sb.append(" 开放").append(open);
                String area = str(it.get("area"));
                if (!area.isBlank()) sb.append(" 区域").append(area);
                String reason = str(it.get("reason"));
                if (!reason.isBlank()) sb.append(" — ").append(reason);
                if (Boolean.TRUE.equals(it.get("needsConfirm"))) sb.append(" ⚠信息待确认");
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 从候选 JSON 文本统计置信度（轻量兜底：解析失败时用正则取均值），失败返回 -1 */
    public static double avgConfidenceFromText(String json) {
        Map<String, Object> node = parse(json);
        if (node != null) {
            double sum = 0;
            int n = 0;
            for (String d : DOMAINS) {
                double v = avgConfidence(items(node, d));
                if (v >= 0) {
                    sum += v;
                    n++;
                }
            }
            return n == 0 ? -1 : sum / n;
        }
        if (json == null || !json.contains("confidence")) return -1;
        Matcher m = CONF.matcher(json);
        double sum = 0;
        int n = 0;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (v >= 0 && v <= 1) {
                    sum += v;
                    n++;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private CandidateUtil() {
    }
}
