package managerAgent.orchestrator;

import dto.TripPlanData;
import dto.TripRequest;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import managerAgent.hook.CancelledException;
import managerAgent.hook.EventStore;
import managerAgent.tool.RemoteAgentTool;
import utils.AgentPrompts;
import utils.AgentUtils;
import utils.CandidateUtil;
import utils.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话式增量修正器（用户中途修改 → 影响分析 → 仅受影响天重聚合 → 再校验 → 合并）。
 * 不推翻整份行程：基于上一版候选存档，仅受影响天重聚合 → 确定性校验 + 聚焦审查 → 存档修订记录。
 * 任一环节失败保留上一版交付（修正失败不劣化）。
 */
@Slf4j  // @Slf4j
public class PlanReviser {  // 类声明

    private final String sessionId;  // sessionId 字段
    private final Model model;  // model 字段（LLM 客户端）
    private final RemoteAgentTool remoteTool;  // remoteTool 字段（调子 Agent）
    /**
     * 作用：构造改行程编排器，绑定本次修改任务的会话 ID
     * 参数：sessionId 会话 ID
     * 返回值：无
     * 异常：无
     */

    public PlanReviser(String sessionId) {  // 构造器签名
        this.sessionId = sessionId;  // 保存会话 ID
        this.model = AgentUtils.buildModel();  // 构建模型客户端
        this.remoteTool = new RemoteAgentTool();  // 创建 A2A 工具
        this.remoteTool.setSessionId(sessionId);  // 注入会话 ID
    }  // 构造器结束

    /** 影响分析结果（LLM 结构化产物） */
    public static class Impact {  // 内部类 Impact
        public String understanding = "";  // understanding：对用户意图的理解
        public String changeType = "";  // changeType：修改类型
        public List<String> targetDays = new ArrayList<>();  // targetDays：受影响的天（如“2”、“overall”）
        public List<String> domains = new ArrayList<>();  // domains：受影响的域（景点/酒店/餐饮/交通）

        String daysText() {  // daysText：把受影响天拼成可读文本
            return targetDays.isEmpty() ? "overall" : String.join("、", targetDays);  // 空则“overall”，否则顿号拼接
        }  // 方法结束
    }  // 类结束

    /**
     * 作用：改行程主流程：分析影响范围 → 只重折叠受影响的天 → 交叉评审 → 不合格带反馈重试 → 全量复校验
     * 参数：instruction 用户修改指令；prev 修改前的完整行程
     * 返回值：修改后的行程（无法改好时返回原行程并标注）
     * 异常：CancelledException 用户中断时抛出
     */
    public TripPlanData revise(String instruction, TripPlanData prev) {  // revise 签名
        // ① 影响分析：判定修改牵动哪些天/哪些域（最小影响范围，宁少勿多）
        EventStore.add(sessionId, "修订", "影响分析：正在解析修改指令的影响范围...");  // 写事件：影响分析中
        utils.SafeModel.setStage("修订影响分析");  // setStage：标记“修订影响分析”
        Impact impact = analyzeImpact(instruction, prev);  // analyzeImpact：让模型分析影响范围
        if (impact == null) {  // 分析失败（解析不出来）
            EventStore.add(sessionId, "修订", "影响分析失败，按全局修订处理");  // 写事件：按全局修订处理
            impact = new Impact();  // 新建空 Impact
            impact.understanding = instruction;  // understanding 用原指令
            impact.targetDays.add("overall");  // 目标天标 overall（全量重排）
        }  // if 结束
        EventStore.add(sessionId, "修订", "影响范围：" + impact.daysText()  // 写事件：影响范围（天/域）
                + "｜受影响域：" + (impact.domains.isEmpty() ? "无（仅重排）" : String.join("、", impact.domains))  // 续：没有任何域受影响就写“无（仅重排）”
                + "（" + impact.understanding + "）");  // 续：附上理解
        checkCancel();  // checkCancel

        // ② 受影响域定向重查已移除（直连候选重查路径下线）：局部重排直接基于上一版候选存档执行
        String candidatesJson = prev.getCandidatesJson() == null ? "" : prev.getCandidatesJson();  // 取上一版的候选 JSON（为空则空串）
        if (!candidatesJson.isBlank()) {  // 有候选存档
            String cluster = utils.CandidateUtil.clusterHintSummary(CandidateUtil.parse(candidatesJson));  // 生成聚类提示摘要
            if (!cluster.isBlank()) {  // 非空
                EventStore.add(sessionId, "修订", "基于存档候选局部重排｜" + cluster);  // 写事件：基于存档候选局部重排 + 聚类摘要
            }  // if 结束
            EventStore.putExtra(sessionId, "clusterStats", utils.CandidateUtil.clusterStats(CandidateUtil.parse(candidatesJson)));  // 聚类统计塞进附加信息
        }  // if 结束

        // ③ 局部重聚合：只改受影响天，其余天逐字保留
        EventStore.add(sessionId, "修订", "局部重聚合：只重排 " + impact.daysText() + "，其余天保持不变...");  // 写事件：局部重聚合（只重排 X，其余天不变）
        utils.SafeModel.setStage("修订聚合");  // setStage：标记“修订聚合”
        TripRequest req = minimalRequest(prev);  // minimalRequest：用上一版行程还原一个最小需求对象
        TripPlanData revised = refoldPlan(prev, instruction, impact, candidatesJson, "");  // refoldPlan：重折叠行程（feedback 空）
        if (revised == null) {  // 折叠失败
            EventStore.add(sessionId, "修订", "局部重聚合失败，保留原方案");  // 写事件：局部重聚合失败，保留原方案
            return null;  // 返回 null（调用方保留上一版）
        }  // if 结束

        // ④ 确定性校验（含新增的营业时间/酒店位置检查）→ FAIL 带反馈重聚合一次
        dto.ValidationReport vr = safeValidate(req, revised, candidatesJson);  // safeValidate：跑约束校验
        EventStore.add(sessionId, "校验", "修订后校验结果 " + vr.summary());  // 写事件：修订后校验结果
        if ("FAIL".equals(vr.getStatus())) {  // 校验 FAIL
            EventStore.add(sessionId, "修订", "校验未通过，带反馈重聚合一次...");  // 写事件：校验未通过，带反馈重聚合一次
            TripPlanData fixed = refoldPlan(prev, instruction, impact, candidatesJson,  // 再次重折叠，feedback 带上校验反馈（必须修复），
                    "★校验反馈（必须修复）：" + vr.summary());  // 续行
            if (fixed != null) {  // 折叠成功
                revised = fixed;  // 采用新版
                vr = safeValidate(req, revised, candidatesJson);  // 重新校验
                EventStore.add(sessionId, "校验", "二次校验结果 " + vr.summary());  // 写事件：二次校验结果
            }  // if 结束
        }  // if 结束
        checkCancel();  // checkCancel

        // ⑤ 聚焦审查：Reviewer 只盯受影响天与本次修改
        utils.SafeModel.setStage("修订审查");  // setStage：标记“修订审查”
        ReviewVerdict v = reviewRevise(req, instruction, impact, revised, vr);  // reviewRevise：把指令、影响范围、行程与校验结果交给评审模型
        if (v != null && v.needsRevise()) {  // 需要修改
            EventStore.add(sessionId, "审查", "修订审查 REVISE：" + v.feedbackText());  // 写事件：修订审查 REVISE + 反馈
            TripPlanData fixed = refoldPlan(prev, instruction, impact, candidatesJson,  // 按审查反馈再重折叠一次，
                    "★审查反馈（必须修复）：" + v.feedbackText());  // 续：feedback 带审查反馈（必须修复）
            if (fixed != null) {  // 折叠成功
                revised = fixed;  // 采用新版
                EventStore.add(sessionId, "审查", "按审查反馈完成二次修正");  // 写事件：按审查反馈完成二次修正
            }  // if 结束
        } else if (v != null) {  // 否则（审查通过）
            EventStore.add(sessionId, "审查", "修订审查通过"  // 写事件：修订审查通过
                    + (v.getScore() >= 0 ? "（质量分 " + v.getScore() + "）" : ""));  // 续：有质量分就带上
        }  // if 结束

        // ⑥ 评分 + 证据链 + 修订记录存档
        revised.setCandidatesJson(candidatesJson);  // 挂回候选 JSON
        revised.setEvidence(CandidateUtil.evidenceList(CandidateUtil.parse(candidatesJson)));  // 挂证据链（来源/等级/时间）
        revised.setRevision(impact.understanding + "（影响：" + impact.daysText()  // 挂修订说明：理解 + （影响：天/域）
                + (impact.domains.isEmpty() ? "" : "/" + String.join("+", impact.domains)) + "）");  // 续行
        revised.setMarkdown(renderMarkdown(revised));  // 重渲染 markdown
        try {  // try
            dto.ScoreResult sr = utils.PlanScorer.score(req, revised, vr, true, true);  // 评分（路线/行程都按“有真实数据”算）
            EventStore.add(sessionId, "评分", "修订后 " + sr.getSummary());  // 写事件：修订后评分
        } catch (Exception e) {  // 评分异常
            log.warn("修订评分异常(忽略): {}", e.getMessage());  // 只 warn（评分失败不影响交付）
        }  // catch 结束
        EventStore.add(sessionId, "完成", "增量修正完成（仅重排 " + impact.daysText() + "，其余天未改动）");  // 写事件：增量修正完成（仅重排 X，其余天未改动）
        return revised;  // 返回修改后的行程
    }  // 方法结束

    // ==================== 影响分析 ====================

    /**
     * 作用：分析修改指令的影响范围：改哪一天、是否牵动跨天衔接、需要哪些候选数据
     * 参数：instruction 用户修改指令；prev 修改前行程
     * 返回值：影响范围对象（目标天/联动天/需要的域）
     * 异常：无（解析失败时按整体重排处理）
     */
    private Impact analyzeImpact(String instruction, TripPlanData prev) {  // analyzeImpact 签名
        String user = "【修改指令】" + instruction  // 拼用户消息：修改指令
                + "\n\n【行程概要】\n" + planDigest(prev)  // ＋ 行程概要（planDigest）
                + (prev.getCandidatesJson() == null || prev.getCandidatesJson().isBlank() ? ""  // 有候选存档时
                        : "\n\n【已有候选摘要】\n" + CandidateUtil.toPromptText(CandidateUtil.parse(prev.getCandidatesJson()), 3));  // ＋ 已有候选摘要（每域 3 条）
        String out = callModelJson(AgentPrompts.reviseImpactAnalyzer(), user);  // callModelJson：用影响分析提示词调模型
        Map<?, ?> node = JsonUtils.extractAndParse(out, Map.class);  // 解析成 Map
        if (node == null) return null;  // 解析失败返回 null
        Impact impact = new Impact();  // 新建 Impact
        impact.understanding = str(node.get("understanding"));  // 取理解
        impact.changeType = str(node.get("changeType"));  // 取修改类型
        if (node.get("targetDays") instanceof List<?> days) {  // 有 targetDays 列表
            for (Object o : days) {  // 逐个取
                String d = str(o);  // 转字符串
                if (!d.isBlank()) impact.targetDays.add(d);  // 非空就加入
            }  // for 结束
        }  // if 结束
        if (node.get("domains") instanceof List<?> ds) {  // 有 domains 列表
            for (Object o : ds) {  // 逐个取
                String d = str(o).trim().toLowerCase();  // 归一（trim + 小写）
                if (d.equals("attraction") || d.equals("hotel") || d.equals("dining") || d.equals("transport")) {  // 只接受四类合法域
                    impact.domains.add(d);  // 加入
                }  // if 结束
            }  // for 结束
        }  // if 结束
        if (impact.understanding.isBlank()) impact.understanding = instruction;  // 理解为空就用原指令兜底
        return impact;  // 返回影响范围
    }  // 方法结束

    // ==================== 局部重聚合 ====================

    /**
     * 作用：按影响范围重折叠行程：只让模型重写受影响的天，其余天原样保留，并用候选数据兜住真实性
     * 参数：prev 修改前行程；instruction 用户指令；impact 影响范围；candidatesJson 三域候选数据；feedback 上一轮评审反馈
     * 返回值：修改后的行程；折叠失败返回 null
     * 异常：无（异常内部捕获）
     */
    private TripPlanData refoldPlan(TripPlanData prev, String instruction, Impact impact,  // refoldPlan 签名
                                    String candidatesJson, String feedback) {  // 签名第 2 行：candidatesJson、feedback
        String sys = AgentPrompts.reviseDayAggregator()  // 系统提示词：改行程聚合模板
                + (feedback == null || feedback.isBlank() ? "" : "\n" + feedback);  // 有反馈就追加（必须修复的校验/审查意见）
        Map<String, Object> cands = candidatesJson.isBlank() ? null : CandidateUtil.parse(candidatesJson);  // 解析候选（空则 null）
        String groupHint = CandidateUtil.coordinateGroupHint(cands); // ★坐标聚类（P1b）  // 坐标聚类提示
        if (groupHint.isBlank()) groupHint = CandidateUtil.areaGroupHint(cands); // 坐标缺失回退文本分组  // 坐标缺失回退文本分组
        String user = "【修改指令】" + instruction  // 用户消息：修改指令
                + "\n【影响范围】" + impact.daysText() + "｜受影响域：" + impact.domains  // ＋ 影响范围（天/域）
                + "\n\n【原行程JSON】\n" + clip(JsonUtils.toJson(prev), 12000)  // ＋ 原行程 JSON（clip 到 12000）
                + (cands == null ? "" :  // 有候选时
                        "\n\n【三域候选（受影响域已重查，含置信度）】\n"  // ＋ 三域候选标题（受影响域已重查，含置信度）
                                + CandidateUtil.toPromptText(cands, 3)  // ＋ 候选可读文本（每域 3 条）
                                + (groupHint.isBlank() ? "" : "\n\n" + groupHint)  // ＋ 分组提示
                                + "\n完整候选JSON：\n" + clip(candidatesJson, 4000));  // ＋ 完整候选 JSON（clip 4000）
        String out = callModelJson(sys, user);  // callModelJson：调模型重折叠
        TripPlanData revised = JsonUtils.extractAndParse(out, TripPlanData.class);  // 解析成 TripPlanData
        if (revised == null || revised.getSections() == null || revised.getSections().isEmpty()) {  // 解析失败或无区块
            return null;  // 返回 null（调用方保留上一版）
        }  // if 结束
        // 保留降级渲染需要的字段
        if (revised.getTitle() == null || revised.getTitle().isBlank()) revised.setTitle(prev.getTitle());  // 标题空就用原值
        if (revised.getDestination() == null || revised.getDestination().isBlank()) revised.setDestination(prev.getDestination());  // 目的地空就用原值
        if (revised.getDays() < 1) revised.setDays(prev.getDays());  // 天数无效就用原值
        if (revised.getDate() == null || revised.getDate().isBlank()) revised.setDate(prev.getDate());  // 日期空就用原值
        if (revised.getWeather() == null || revised.getWeather().isBlank()) revised.setWeather(prev.getWeather());  // 天气空就用原值
        return revised;  // 返回修改后的行程
    }  // 方法结束

    /**
     * 作用：交叉评审：把修改后的行程再校验一遍，并让模型判断是否真的按指令改对了
     * 参数：req 需求；instruction 用户指令；impact 影响范围；plan 修改后行程；vr 校验报告
     * 返回值：评审结论（是否通过、问题清单、评分）
     * 异常：无（评审失败时按"需重做"处理）
     */
    private ReviewVerdict reviewRevise(TripRequest req, String instruction, Impact impact,  // reviewRevise 签名第 1 行
                                       TripPlanData plan, dto.ValidationReport vr) {  // 签名第 2 行
        String user = "【本次修改】" + instruction + "（影响：" + impact.daysText() + "）"  // 用户消息：本次修改 + 影响范围
                + "\n\n【确定性校验结果】" + (vr == null ? "未执行" : vr.detailedSummary())  // ＋ 确定性校验明细
                + "\n\n★重点检查受影响天（" + impact.daysText() + "）的修改质量与前后衔接；未受影响天只需确认未被误改。\n"  // ＋ 要求重点检查受影响天，未受影响天只确认没被误改
                + "\n【行程JSON】\n" + clip(JsonUtils.toJson(plan), 6000);  // ＋ 行程 JSON（clip 6000）
        String out = callModelJson(AgentPrompts.tripReviewer(), user);  // callModelJson：用评审提示词调模型
        return ReviewVerdict.parse(out);  // 解析成 ReviewVerdict
    }  // 方法结束

    // ==================== 工具方法 ====================

    /**
     * 作用：从已有行程反推一个最小需求对象（改行程时没有原始需求，用它做校验基准）
     * 参数：prev 修改前行程
     * 返回值：最小可用的需求对象
     * 异常：无
     */
    private TripRequest minimalRequest(TripPlanData prev) {  // minimalRequest 签名
        TripRequest req = new TripRequest();  // 新建需求对象
        req.setDestination(prev.getDestination());  // 目的地来自行程
        req.setDays(prev.getDays());  // 天数来自行程
        req.setDate(prev.getDate());  // 日期来自行程
        req.setRaw("（增量修正）" + prev.getTitle());  // raw 标“（增量修正）+标题”
        return req;  // 返回
    }  // 方法结束

    /**
     * 作用：把行程压成紧凑摘要（天数/每天条目/车程），供提示词使用
     * 参数：plan 行程
     * 返回值：摘要文本
     * 异常：无
     */
    private String planDigest(TripPlanData plan) {  // planDigest 签名
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("标题: ").append(plan.getTitle()).append("；目的地: ").append(plan.getDestination())  // 标题/目的地/
                .append("；").append(plan.getDays()).append(" 天\n");  // 续：天数 + 换行
        if (plan.getSections() != null) {  // 有区块
            for (dto.Section s : plan.getSections()) {  // 遍历区块
                if (!"timeline".equals(s.getType()) || s.getItems() == null) continue;  // 只看 timeline 且有条目
                sb.append(s.getTitle()).append(": ");  // 区块标题 + 冒号
                for (dto.SectionItem it : s.getItems()) {  // 遍历条目
                    if (it.getPlace() != null && !it.getPlace().isBlank()) sb.append(it.getPlace()).append("、");  // 有地点就加入（顿号分隔）
                }  // for 结束
                sb.append("\n");  // 换行
            }  // for 结束
        }  // if 结束
        return sb.toString();  // 返回摘要
    }  // 方法结束
    /**
     * 作用：安全校验：调用约束引擎校验，异常时返回空报告（不影响主流程）
     * 参数：req 需求；plan 行程；candidatesJson 候选数据
     * 返回值：校验报告，永不为 null
     * 异常：无（异常内部捕获）
     */

    private dto.ValidationReport safeValidate(TripRequest req, TripPlanData plan, String candidatesJson) {  // safeValidate 签名
        try {  // try
            return utils.ConstraintEngine.validate(req, plan, candidatesJson);  // 调约束引擎校验
        } catch (Exception e) {  // 异常
            log.warn("修订校验异常: {}", e.getMessage());  // warn 日志
            return new dto.ValidationReport();  // 返回空报告（不阻断修订）
        }  // catch 结束
    }  // 方法结束
    /**
     * 作用：改行程专用 LLM 调用（要求 JSON 输出），带会话上下文用于调用链归位
     * 参数：sysPrompt 系统提示词；userPrompt 用户提示词
     * 返回值：模型返回文本（可能为空串）
     * 异常：无（异常已在 LlmClient 内部吞掉）
     */

    private String callModelJson(String sysPrompt, String userPrompt) {  // callModelJson 签名
        utils.SafeModel.setSessionId(sessionId);  // 绑定会话（调用链 span 归位）
        return utils.LlmClient.chatJson(sysPrompt, userPrompt);  // 交给 LlmClient.chatJson
    }  // 方法结束
    /**
     * 作用：检查用户是否已请求中断，是则抛出类型化取消异常
     * 参数：无
     * 返回值：无
     * 异常：CancelledException 用户已请求中断
     */

    private void checkCancel() {  // checkCancel 签名
        if (EventStore.isCancelRequested(sessionId)) {  // 取消标志已置位
            throw new CancelledException();  // 抛 CancelledException
        }  // if 结束
    }  // 方法结束
    /**
     * 作用：截断过长文本
     * 参数：s 原文本；maxLen 最大长度
     * 返回值：截断后的文本；null 返回空串
     * 异常：无
     */

    private String clip(String s, int maxLen) {  // clip 签名
        if (s == null) return "";  // null 返回空串
        if (s.length() <= maxLen) return s;  // 没超长原样返回
        return s.substring(0, maxLen) + "\n…(数据过长已截断)…";  // 截断并加“…(数据过长已截断)…”
    }  // 方法结束
    /**
     * 作用：安全转字符串
     * 参数：o 任意对象
     * 返回值：对象字符串；null 返回空串
     * 异常：无
     */

    private String str(Object o) {  // str 签名
        return o == null ? "" : String.valueOf(o);  // null 归一空串
    }  // 方法结束

    /**
     * 作用：把结构化行程渲染成 markdown（前端展示/降级字段用）
     * 参数：plan 行程
     * 返回值：markdown 文本
     * 异常：无
     */
    private String renderMarkdown(TripPlanData plan) {  // renderMarkdown 签名
        StringBuilder sb = new StringBuilder();  // 输出缓冲
        sb.append("# ").append(plan.getTitle()).append("（修订版）\n\n");  // 一级标题：行程标题 +（修订版）
        if (plan.getWeather() != null && !plan.getWeather().isBlank()) {  // 有天气
            sb.append("**天气**：").append(plan.getWeather()).append("\n\n");  // 加天气行
        }  // if 结束
        if (plan.getSections() != null) {  // 有区块
            for (dto.Section sec : plan.getSections()) {  // 遍历区块
                sb.append("## ").append(sec.getTitle()).append("\n");  // 二级标题
                if (sec.getItems() == null) continue;  // 没有条目就跳过
                for (dto.SectionItem it : sec.getItems()) {  // 遍历条目
                    sb.append("- ");  // 以“- ”开头
                    switch (sec.getType()) {  // 按类型渲染
                        case "timeline" -> sb.append("**").append(it.getTime()).append("** ")  // timeline：**时间** 地点 活动
                                .append(it.getPlace()).append(" ").append(it.getActivity());  // 续行
                        case "transport" -> sb.append(it.getFrom()).append(" → ").append(it.getTo())  // transport：从 → 到（方式，时长）
                                .append("（").append(it.getWay()).append("，").append(it.getDuration()).append("）");  // 续行
                        case "stay" -> sb.append(it.getName()).append("（").append(it.getCategory())  // stay：名称（类型 价格）
                                .append(it.getPrice().isBlank() ? "" : " " + it.getPrice()).append("）");  // 续：价格空则不加
                        case "tips" -> sb.append(it.getKind()).append("：").append(it.getText());  // tips：类型：正文
                        default -> sb.append(it.getActivity()).append(it.getPlace()).append(it.getText());  // default：活动+地点+文本
                    }  // switch 结束
                    sb.append("\n");  // 换行
                }  // for 结束
                sb.append("\n");  // 区块之间空行
            }  // for 结束
        }  // if 结束
        return sb.toString().trim();  // 返回 trim 后的 markdown
    }  // 方法结束
}  // 类结束

