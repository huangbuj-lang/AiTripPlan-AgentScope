package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行程区块内的一条记录（按区块类型取用不同字段）。
 * 采用扁平字段，与前端 JSON 契约一一对应；未用字段为空串，Jackson 序列化时忽略。
 */
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造（Jackson 反序列化需要）
public class SectionItem {  // 类声明：区块内一条记录（扁平字段，未用字段留空串）

    // timeline（时间轴）字段
    private String time = "";       // 时间（如 09:00）  // timeline 字段：时间（如 09:00）
    private String activity = "";   // 活动  // timeline 字段：活动
    private String place = "";      // 地点/景点  // timeline 字段：地点/景点
    private String category = "";   // 类别（景点/美食/交通）  // timeline 字段：类别（景点/美食/交通）
    private String cost = "";       // 费用  // timeline 字段：费用
    private String note = "";       // 备注  // timeline 字段：备注

    // transport（交通）字段
    private String from = "";       // 出发地  // transport 字段：出发地
    private String to = "";         // 到达地  // transport 字段：到达地
    private String way = "";        // 方式（自驾/高铁）  // transport 字段：交通方式（自驾/高铁）
    private String duration = "";   // 时长  // transport 字段：时长

    // stay（住宿餐饮）字段
    private String name = "";       // 名称  // stay 字段：名称（酒店/餐厅）
    private String price = "";      // 价格  // stay 字段：价格

    // tips（实用信息）字段
    private String kind = "";       // 类型（电话/提示）  // tips 字段：类型（电话/提示）
    private String text = "";       // 内容  // tips 字段：内容
}  // 类结束
