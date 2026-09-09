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

    /** 区块内的记录 */
    private List<SectionItem> items = new ArrayList<>();
}
