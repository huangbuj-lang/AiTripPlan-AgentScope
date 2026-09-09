package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 完整结构化行程数据契约（阶段三主线产物）。 */
@Data
@NoArgsConstructor
public class TripPlanData {

    /** 行程标题（如 "深圳—惠州 3 日自驾游"） */
    private String title = "";

    /** 目的地 */
    private String destination = "";

    /** 天数 */
    private int days = 1;

    /** 出行日期 */
    private String date = "";

    /** 天气摘要 */
    private String weather = "";

    /** 区块列表 */
    private List<Section> sections = new ArrayList<>();

    /** 降级渲染用 markdown 原文（旧前端/旧数据仍可用） */
    private String markdown = "";
}
