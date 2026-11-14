package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整结构化行程数据契约（阶段三主线产物）。
 * 前后端、段间都传此 DTO，替代自由文本 + 正则清洗。
 */
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造（Jackson 反序列化需要）
public class TripPlanData {  // 类声明：完整结构化行程契约（前后端、段间都传它）

    /** 行程标题（如 "深圳—惠州 3 日自驾游"） */
    private String title = "";  // title：行程标题

    /** 目的地 */
    private String destination = "";  // destination：目的地

    /** 天数 */
    private int days = 1;  // days：天数（默认 1）

    /** 出行日期 */
    private String date = "";  // date：出行日期

    /** 天气摘要 */
    private String weather = "";  // weather：天气摘要

    /** 区块列表 */
    private List<Section> sections = new ArrayList<>();  // sections：区块列表

    /** 降级渲染用 markdown 原文（旧前端/旧数据仍可用） */
    private String markdown = "";  // markdown：降级渲染用原文（旧前端/旧数据兼容）

    /** 三域结构化候选 JSON（生成时的景点/酒店/餐饮 Top-3 存档，增量修正时复用；前端不渲染） */
    private String candidatesJson = "";  // candidatesJson：三域候选 JSON 存档（增量修正时复用，前端不渲染）

    /** 证据链记录（每域：来源/等级/抓取时间/置信度均值/是否需复核；仅后端留存与事件流展示，前端不渲染） */
    private List<java.util.Map<String, Object>> evidence = new ArrayList<>();  // evidence：证据链记录（来源/等级/抓取时间/置信度，前端不渲染）

    /** 修订记录（会话式增量修正的说明；空=首次生成） */
    private String revision = "";  // revision：修订记录说明（空=首次生成）

    /** ★替代路线建议（物理不可行时的兜底：天数内可行的替代选项，可为空）。 */
    private String alternative = "";  // alternative：替代路线建议（物理不可行时的兜底，可为空）
}  // 类结束
