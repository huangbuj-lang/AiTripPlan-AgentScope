package managerAgent.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.Section;
import dto.SectionItem;
import dto.TripPlanData;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import managerAgent.controller.NotificationHub;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import utils.ExternalServices;
import utils.JsonUtils;
import utils.LlmClient;
import utils.ModelTimeouts;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【行中提醒】每天到点（默认 7:00，可在管理端热更）发当日天气早报。
 *
 * <p>流程（每个【今天处于行程日期区间内】的行程各发一条）：
 * <ol>
 *   <li>从 trip_plans.plan_json 取结构化行程，解析出行程日期区间（date 字段优先；
 *       缺失时从第一个 timeline 区块标题里的「M月d日」兜底）；解析不出日期就<b>跳过</b>（宁可不发，不能乱发）。</li>
 *   <li>取「今天的城市」：当日 timeline 区块里的地点 / 行程目的地。</li>
 *   <li>后端 Java 直调<b>高德</b>天气接口（今天起 4 天预报）——<b>天气数据不经 LLM、不编造</b>，取不到写「暂无」。</li>
 *   <li>把真实的天气数据交给外部 LLM 写一段「人话早报」（≤80 字，只能依据给定数据）。</li>
 *   <li>写进现有 notifications 表（type=6，新未占用值），并 SSE 推送；首页红点/通知页复用现成功能。</li>
 * </ol>
 *
 * <p>幂等：同一用户 + 同一行程 + 同一天只发一次（查 notifications 当天是否已有同 type/同链接的记录）。
 * 全流程 try/catch，任一环节失败只跳过该行程/本次执行，绝不影响服务；
 * <b>绝不重做行程、绝不写 plan_json</b>。
 */
@Slf4j
@Component
public class WeatherBriefJob {

    /** 通知类型：6 —— 未被占用（现有：1=评论、2=点赞、4=AI 答主、5=公告；3 为前端预留的收藏图标位）。 */
    private static final int TYPE_WEATHER_BRIEF = 6;

    /** 单次执行最多处理多少条行程（防御性上限，避免异常数据把一次任务拖长）。 */
    private static final int MAX_TRIPS_PER_RUN = 200;

    /** 高德 QPS 限制（3/秒）：逐个城市查询之间 sleep。 */
    private static final long AMAP_GAP_MS = 400L;

    private static final Pattern MD = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");

    /** ★手动触发用：true 时跳过"天气没变化就不发"（只影响手动触发，定时任务不传） */
    private static final ThreadLocal<Boolean> FORCE_ONCE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** 是否手动触发（用于执行记录里的 by 字段） */
    private static final ThreadLocal<Boolean> MANUAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 本次运行实际发出的条数（手动触发时回给前端） */
    private volatile int lastSentCount = 0;

    /** 本次运行生成的早报正文（★用户口径：早报不再发给行程用户，改为只呈现在管理员弹窗里） */
    private final java.util.List<String> lastBriefs = new java.util.ArrayList<>();

    private final ObjectMapper om = new ObjectMapper();

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 每天早上 7:00 执行一次。
     * cron 走配置项 {@code weather.brief.cron}（默认 {@code 0 0 7 * * ?}），便于自验时用临时实例改频次，
     * 默认行为仍是「每天 7:00」，且<b>不</b>在启动时立即执行。
     */
    /** ★天气签名缓存：userId#planId -> 上次观测到的天气签名（内存，重启即清） */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> LAST_WEATHER_SIG =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取"今天"那天的天气签名（天气现象+昼夜温度）；取不到返回 null（宁可不发） */
    @SuppressWarnings("unchecked")
    private static String weatherSignature(Map<String, Object> weather, LocalDate today) {
        if (weather == null) return null;
        Object daysObj = weather.get("days");
        if (!(daysObj instanceof List)) return null;
        Map<String, String> hit = null;
        for (Object o : (List<Object>) daysObj) {
            if (!(o instanceof Map)) continue;
            Map<String, String> d = (Map<String, String>) o;
            String dt = d.get("date");
            if (dt != null && (dt.equals(today.toString())
                    || dt.equals(today.getMonthValue() + "月" + today.getDayOfMonth() + "日"))) {
                hit = d;
                break;
            }
            if (hit == null) hit = d;
        }
        if (hit == null) return null;
        return (hit.getOrDefault("dayWeather", "") + "|" + hit.getOrDefault("nightWeather", "")
                + "|" + hit.getOrDefault("dayTemp", "") + "|" + hit.getOrDefault("nightTemp", "")).trim();
    }

    /** 配置项：每天触发时间（HH:mm）/ 是否启用（存 app_settings 表，管理端可热更） */
    private static final String KEY_TIME = "weather.brief.time";
    private static final String KEY_ENABLED = "weather.brief.enabled";
    private static final String DEFAULT_TIME = "07:00";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 上次已执行的标记：yyyy-MM-dd|HH:mm —— 同一天同一时间点只跑一次，
     *  但管理端把时间改成新值后（哪怕今天已经跑过）到新时间点仍会执行（实测踩坑：写死"今天只跑一次"会导致改完时间不触发） */
    private volatile String lastRunTag = "";

    /**
     * ★调度入口：每 60 秒判断一次「现在是否到点 && 今天还没跑」。
     * 触发时间从 app_settings 读 → 管理端改完【下一轮就生效，无需重启】（用户口径 A 方案）。
     * 纯内存+一次极小的库查询，不消耗高德/LLM 额度。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void tick() {
        try {
            String want = setting(KEY_TIME, DEFAULT_TIME);
            if (!"1".equals(setting(KEY_ENABLED, "1"))) return;
            LocalDate today = LocalDate.now();
            String tag = today.toString() + "|" + want;
            if (tag.equals(lastRunTag)) return;                //这个时间点今天已经跑过
            String now = LocalTime.now().format(HM);
            if (!now.equals(want)) return;                     //还没到点
            lastRunTag = tag;
            log.info("行中提醒-定时触发：到点 {}（配置 {}），开始执行", now, want);
            runDaily();
            log.info("行中提醒-定时触发：执行结束，本次发出 {} 条", lastSentCount);
        } catch (Throwable t) {
            log.warn("行中提醒-定时判断失败: {}", t.toString());
        }
    }

    /** ★最近一次执行记录（写 app_settings，供管理端弹窗用；不写通知模块、不给普通用户） */
    private static final String KEY_LAST_RUN = "weather.brief.last_run";

    private void recordRun(String by, long planIdsHash) {
        try {
            String brief = lastBriefs.isEmpty() ? "" : lastBriefs.get(0);
            brief = brief.replace("|", "／").replace("\n", " ").replace("\r", " ").trim();
            if (brief.length() > 90) brief = brief.substring(0, 90);
            String v = System.currentTimeMillis() + "|" + LocalTime.now().format(HMS)
                    + "|" + lastSentCount + "|" + by + "|" + brief;
            jdbcTemplate.update(
                    "INSERT INTO app_settings(k, v, updated_by) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE v = VALUES(v), updated_by = VALUES(updated_by)",
                    KEY_LAST_RUN, v, "weather-brief");
        } catch (Exception ignore) { }
    }

    /** 读配置（表不存在/查不到 → 用默认值，绝不抛错） */
    private String setting(String key, String def) {
        try {
            List<String> vs = jdbcTemplate.queryForList("SELECT v FROM app_settings WHERE k = ?", String.class, key);
            if (vs != null && !vs.isEmpty() && vs.get(0) != null && !vs.get(0).isBlank()) return vs.get(0).trim();
        } catch (Exception ignore) { }
        return def;
    }

    public void runDaily() {
        try {
            lastSentCount = 0;
            lastBriefs.clear();
            LocalDate today = LocalDate.now();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, user_id, plan_json FROM trip_plans WHERE plan_json IS NOT NULL LIMIT ?",
                    MAX_TRIPS_PER_RUN);
            for (Map<String, Object> row : rows) {
                try {
                    handleOneTrip(row, today);
                } catch (Throwable t) {
                    //单个行程失败不影响其他行程
                    log.debug("行中提醒-单条行程处理失败: {}", t.toString());
                }
            }
        } catch (Throwable t) {
            //定时任务绝不把异常抛给容器
            log.warn("行中提醒任务执行失败: {}", t.toString());
        } finally {
            recordRun(MANUAL.get() ? "manual" : "auto", 0L);   //★记下本次执行（管理端弹窗读它）
        }
    }

    /**
     * ★手动触发一次行中提醒（演示/自验用）。force=true 时跳过"天气没变化就不发"，直接发。
     * 只影响这一次调用；每日 7:00 的定时任务逻辑一个字没改。
     *
     * @return 本次实际发出的条数
     */
    public int runManually(boolean force) {
        FORCE_ONCE.set(force);
        MANUAL.set(Boolean.TRUE);
        try {
            runDaily();
        } finally {
            FORCE_ONCE.remove();
            MANUAL.remove();
        }
        return lastSentCount;
    }

    /** 处理一个行程：命中今天在区间内才发早报。 */
    private void handleOneTrip(Map<String, Object> row, LocalDate today) throws Exception {
        Object idObj = row.get("id");
        Object uidObj = row.get("user_id");
        if (!(idObj instanceof Number) || !(uidObj instanceof Number)) return;
        long planId = ((Number) idObj).longValue();
        long userId = ((Number) uidObj).longValue();

        Object pj = row.get("plan_json");
        if (pj == null) return;
        TripPlanData plan = JsonUtils.parse(pj.toString(), TripPlanData.class);
        if (plan == null) return;

        //① 日期区间：date 字段优先，缺失时用第一个 timeline 标题里的「M月d日」兜底
        LocalDate[] range = parseRange(plan.getDate());
        if (range == null) range = rangeFromFirstDayTitle(plan);
        if (range == null) return; //解析不出日期：跳过，宁可不发

        LocalDate start = range[0];
        LocalDate end = range[1];
        if (today.isBefore(start) || today.isAfter(end)) return; //今天不在行程区间内

        //② 今天的城市候选：当日 timeline 地点 / 目的地
        List<String> cities = cityCandidates(plan, today, start);
        if (cities.isEmpty()) return;

        //③ 高德天气（后端直调，不经 LLM）
        Map<String, Object> weather = null;
        String hitCity = null;
        for (String city : cities) {
            weather = amapWeather(city);
            if (weather != null) {
                hitCity = city;
                break;
            }
            sleepQuiet(AMAP_GAP_MS);
        }

        //★用户口径：只有【天气出现变化】才通知 —— 与上一次观测到的天气比较；没变化就静默。
        //  首次观测只记录不发送（避免服务重启后第一天误报"变化"）。
        String sig = weatherSignature(weather, today);
        if (sig == null) return;
        String prevSig = LAST_WEATHER_SIG.put(userId + "#" + planId, sig);
        if (!FORCE_ONCE.get()) {          //★手动触发时可强制发出（仅演示用；定时任务照旧）
            if (prevSig == null) return;      //首次观测：只记不发
            if (prevSig.equals(sig)) return;  //天气没变：不发
        } else {
            log.info("[手动触发] 跳过变化判断，强制发早报 planId={}", planId);
        }

        //④ 今天行程要点（真实数据，来自 timeline）
        String highlights = todayHighlights(plan, today, start);

        //⑤ 外部 LLM 写「人话早报」（只能依据给定数据）；LLM 不可用时退回纯数据模板（同样不编造）
        String content = llmBrief(hitCity, today, weather, highlights);
        if (content == null || content.isBlank()) {
            content = fallbackBrief(hitCity, today, weather, highlights);
        }
        content = clip(content, 80);
        if (content == null || content.isBlank()) return;


        //⑥ ★用户口径（2026-09-13）：早报【不再发给普通用户】✗ 不写通知模块 ✗ 不推 SSE ✗
        //   只把它记进"本次执行记录"，由管理端弹窗呈现给管理员（前台/管理端右下角弹窗）。
        //   如需恢复给用户发：把下面注释掉的 INSERT + push 放开即可，其余逻辑无需改动。
        lastBriefs.add("【" + hitCity + "】" + content);
        lastSentCount++;
        //String link = "/index.html?planId=" + planId;
        //if (alreadySentToday(userId, link)) return;
        //try {
        //    jdbcTemplate.update(
        //            "INSERT INTO notifications(user_id, type, content, link_url) VALUES (?, ?, ?, ?)",
        //            userId, TYPE_WEATHER_BRIEF, content, link);
        //    NotificationHub.push(userId, "{...}");
        //} catch (Exception ignored) { }
    }

    /** 幂等判断：该用户当天是否已有指向同一行程的本类型通知。 */
    private boolean alreadySentToday(long userId, String link) {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ? " +
                            "AND DATE(created_at) = CURDATE() AND link_url = ?",
                    Integer.class, userId, TYPE_WEATHER_BRIEF, link);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            return false; //查询失败按「未发过」处理，最终仍受数据库约束保护
        }
    }

    //==================== 日期解析 ====================

    /** 解析 "9月12日—9月19日" / "9月12日" 这类区间（当前年份；跨年自动 +1 年）。解析失败返回 null。 */
    private LocalDate[] parseRange(String date) {
        if (date == null || date.isBlank()) return null;
        Matcher m = MD.matcher(date);
        List<int[]> found = new ArrayList<>();
        while (m.find() && found.size() < 2) {
            found.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        if (found.isEmpty()) return null;
        LocalDate start = safeDate(LocalDate.now().getYear(), found.get(0)[0], found.get(0)[1]);
        if (start == null) return null;
        LocalDate end = start;
        if (found.size() > 1) {
            end = safeDate(start.getYear(), found.get(1)[0], found.get(1)[1]);
            if (end == null) return null;
            if (end.isBefore(start)) end = end.plusYears(1); //跨年区间
        }
        return new LocalDate[]{start, end};
    }

    /** 兜底：从第一个 timeline 区块标题里的「M月d日」取起始日，终点按天数推算。 */
    private LocalDate[] rangeFromFirstDayTitle(TripPlanData plan) {
        if (plan.getSections() == null) return null;
        for (Section s : plan.getSections()) {
            if (!isTimeline(s) || s.getTitle() == null) continue;
            Matcher m = MD.matcher(s.getTitle());
            if (!m.find()) continue;
            LocalDate start = safeDate(LocalDate.now().getYear(),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (start == null) return null;
            int days = Math.max(plan.getDays(), 1);
            return new LocalDate[]{start, start.plusDays(days - 1L)};
        }
        return null;
    }

    private LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null; //如 2月30日 这类非法日期
        }
    }

    //==================== 城市 / 行程要点 ====================

    /** 今天的城市候选：当日 timeline 里的地点/到发地 → 目的地拆词。保持顺序去重。 */
    private List<String> cityCandidates(TripPlanData plan, LocalDate today, LocalDate start) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Section sec = todayTimeline(plan, today, start);
        if (sec != null) {
            if (sec.getStops() != null) {
                for (String s : sec.getStops()) addClean(out, s);
            }
            if (sec.getItems() != null) {
                for (SectionItem it : sec.getItems()) {
                    addClean(out, it.getPlace());
                    addClean(out, it.getTo());
                    addClean(out, it.getFrom());
                }
            }
        }
        String dest = plan.getDestination();
        if (dest != null) {
            for (String part : dest.split("[、，,·（）()\\-—/区县市]")) {
                addClean(out, part);
            }
            addClean(out, dest);
        }
        return new ArrayList<>(out);
    }

    private void addClean(LinkedHashSet<String> set, String s) {
        if (s == null) return;
        String t = s.replaceAll("\\s+", "").trim();
        if (t.isEmpty() || t.length() > 20) return;
        set.add(t);
    }

    /** 今天对应的 timeline 区块：优先按下标（= 第几天），否则按标题含「第N天」找。 */
    private Section todayTimeline(TripPlanData plan, LocalDate today, LocalDate start) {
        if (plan.getSections() == null || plan.getSections().isEmpty()) return null;
        List<Section> timelines = new ArrayList<>();
        for (Section s : plan.getSections()) {
            if (isTimeline(s)) timelines.add(s);
        }
        if (timelines.isEmpty()) return null;
        long idx = java.time.temporal.ChronoUnit.DAYS.between(start, today); //0 基
        if (idx >= 0 && idx < timelines.size()) return timelines.get((int) idx);
        String want = "第" + (idx + 1) + "天";
        for (Section s : timelines) {
            if (s.getTitle() != null && s.getTitle().startsWith(want)) return s;
        }
        return timelines.get(0);
    }

    private boolean isTimeline(Section s) {
        return s != null && "timeline".equalsIgnoreCase(s.getType());
    }

    /** 今天行程要点（真实数据：站点/活动），用于给 LLM 一句上下文；取不到返回空串。 */
    private String todayHighlights(TripPlanData plan, LocalDate today, LocalDate start) {
        Section sec = todayTimeline(plan, today, start);
        if (sec == null) return "";
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (sec.getStops() != null) {
            for (String s : sec.getStops()) {
                if (s != null && !s.isBlank()) parts.add(s.trim());
            }
        }
        if (parts.isEmpty() && sec.getItems() != null) {
            for (SectionItem it : sec.getItems()) {
                String p = it.getPlace() == null || it.getPlace().isBlank() ? it.getActivity() : it.getPlace();
                if (p != null && !p.isBlank()) parts.add(p.trim());
            }
        }
        List<String> list = new ArrayList<>(parts);
        if (list.size() > 4) list = list.subList(0, 4);
        return String.join("、", list);
    }

    //==================== 高德天气（后端直调） ====================

    /** 城市级天气预报（今天起 4 天）。失败/查不到返回 null（上层写「暂无」），绝不编造。 */
    private Map<String, Object> amapWeather(String city) {
        try {
            String key = ExternalServices.amapKey();
            if (key == null || key.isBlank()) return null;
            String qs = "key=" + key + "&city=" + URLEncoder.encode(city, "UTF-8")
                    + "&extensions=all&output=json";
            String body = httpGet("https://restapi.amap.com/v3/weather/weatherInfo?" + qs);
            JsonNode forecasts = om.readTree(body).path("forecasts");
            if (!forecasts.isArray() || forecasts.isEmpty()) return null;
            JsonNode f = forecasts.get(0);
            JsonNode casts = f.path("casts");
            if (!casts.isArray() || casts.isEmpty()) return null;
            List<Map<String, String>> days = new ArrayList<>();
            for (JsonNode cast : casts) {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("date", cast.path("date").asText(""));
                d.put("week", cast.path("week").asText(""));
                d.put("dayWeather", cast.path("dayweather").asText(""));
                d.put("nightWeather", cast.path("nightweather").asText(""));
                d.put("dayTemp", cast.path("daytemp").asText(""));
                d.put("nightTemp", cast.path("nighttemp").asText(""));
                days.add(d);
            }
            if (days.isEmpty()) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("city", f.path("city").asText(city));
            out.put("province", f.path("province").asText(""));
            out.put("reportTime", f.path("reporttime").asText(""));
            out.put("days", days);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    //==================== 早报文案 ====================

    /** 真实天气 -> 文本行（当天 + 未来几天），交给 LLM 与人读都用它。 */
    @SuppressWarnings("unchecked")
    private String weatherText(Map<String, Object> weather) {
        if (weather == null) return "暂无";
        List<Map<String, String>> days = (List<Map<String, String>>) weather.get("days");
        if (days == null || days.isEmpty()) return "暂无";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map<String, String> d : days) {
            if (n >= 4) break;
            String w = d.get("dayWeather");
            if (d.get("nightWeather") != null && !d.get("nightWeather").isBlank()
                    && !d.get("nightWeather").equals(w)) {
                w = w + "转" + d.get("nightWeather");
            }
            sb.append(md(d.get("date"))).append(" ").append(w)
                    .append(" ").append(d.get("nightTemp")).append("℃~").append(d.get("dayTemp")).append("℃");
            if (n < 3) sb.append("；");
            n++;
        }
        return sb.toString();
    }

    private String md(String date) {
        if (date != null && date.length() >= 10) {
            try {
                return Integer.parseInt(date.substring(5, 7)) + "月" + Integer.parseInt(date.substring(8, 10)) + "日";
            } catch (Exception ignored) {
            }
        }
        return date == null ? "" : date;
    }

    /** 外部 LLM 写一句早报；只能依据给定数据。失败返回空串（上层退回纯数据模板）。 */
    private String llmBrief(String city, LocalDate today, Map<String, Object> weather, String highlights) {
        try {
            String sysPrompt = "你是旅行助手，负责写一句当天出行的中文『人话早报』。"
                    + "硬性要求：只能使用我提供的数据，绝不补充或编造任何天气、温度、日期、地点；"
                    + "长度不超过 80 字；口语自然，直接输出这一句话，不要标题、不要 markdown、不要引号。";
            StringBuilder up = new StringBuilder();
            up.append("今天城市：").append(city == null ? "暂无" : city).append("\n");
            up.append("今天日期：").append(today.getMonthValue()).append("月").append(today.getDayOfMonth()).append("日\n");
            up.append("未来天气（高德，实取）：").append(weatherText(weather)).append("\n");
            if (highlights != null && !highlights.isBlank()) {
                up.append("今天行程要点：").append(highlights).append("\n");
            }
            up.append("请根据以上数据写一句不超过 80 字的早报，可提醒带伞/防晒/增减衣物，但不得虚构任何数据。");
            Msg system = Msg.builder().role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text(sysPrompt).build()).build();
            Msg user = Msg.builder().role(MsgRole.USER)
                    .content(TextBlock.builder().text(up.toString()).build()).build();
            GenerateOptions options = GenerateOptions.builder().build();
            ChatResponse resp = LlmClient.model().stream(List.of(system, user), null, options)
                    .blockLast(ModelTimeouts.defaultDuration());
            StringBuilder sb = new StringBuilder();
            if (resp != null) {
                for (ContentBlock block : resp.getContent()) {
                    if (block instanceof TextBlock tb) sb.append(tb.getText());
                }
            }
            return sb.toString().replaceAll("[\\r\\n]+", " ").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** LLM 不可用时的兜底：纯粹把真实天气数据拼成人话（依然不编造）。 */
    private String fallbackBrief(String city, LocalDate today, Map<String, Object> weather, String highlights) {
        StringBuilder sb = new StringBuilder();
        sb.append("早上好，今天在").append(city == null ? "目的地" : city).append("：");
        sb.append(weatherText(weather)).append("。");
        if (highlights != null && !highlights.isBlank()) {
            sb.append("今日行程：").append(highlights).append("。");
        }
        return sb.toString();
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    //==================== HTTP ====================

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        try {
            return new String(c.getInputStream().readAllBytes(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
