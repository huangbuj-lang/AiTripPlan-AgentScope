package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 行程的一个区块（时间轴/交通/住宿餐饮/实用信息）。
 */
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造（Jackson 反序列化需要）
public class Section {  // 类声明：行程区块（时间轴/交通/住宿餐饮/实用信息）

    /** 区块类型：timeline / transport / stay / tips */
    private String type = "";  // type：区块类型 timeline / transport / stay / tips

    /** 区块标题（如 "第1天" / "交通" / "住宿餐饮"） */
    private String title = "";  // title：区块标题（如“第1天”“交通”“住宿餐饮”）

    /** 该天天气（timeline 区块用；行程Agent 输出 sections[].weather，缺此字段会导致 Jackson 反序列化失败降级） */
    private String weather = "";  // weather：该天天气（timeline 区块用，缺它会导致反序列化降级）

    /** 区块内的记录 */
    private List<SectionItem> items = new ArrayList<>();  // items：区块内的记录列表
}  // 类结束
