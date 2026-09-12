package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 行程的一个区块（时间轴/交通/住宿餐饮/实用信息）。 */
@Data
@NoArgsConstructor
public class Section {

    /** 区块类型：timeline / transport / stay / tips */
    private String type = "";

    /** 区块标题（如 "第1天" / "交通" / "住宿餐饮"） */
    private String title = "";

    /** 当天行程概览：按顺序的站点名列表（LLM 写；不含括号说明、同一地点只出现一次）。
     *  前端卡片按【原有样式】逐行填充它 —— 不要改前端格式。 */
    private java.util.List<String> stops = new ArrayList<>();

    /** 区块内的记录 */
    private List<SectionItem> items = new ArrayList<>();
}
