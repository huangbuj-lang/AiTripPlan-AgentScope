package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 多方案行程集合（功能4：多方案盲测对比）。
 * 一份需求产出多套差异化方案，供用户左右对比、选中后再细化。
 */
@Data  // @Data：Lombok 生成 getter/setter
@NoArgsConstructor  // @NoArgsConstructor：无参构造（Jackson 反序列化需要）
public class PlanSet {  // 类声明：多方案集合（一份需求产出多套方案供对比）

    /** 多套方案（按 styles 顺序一一对应） */
    private List<TripPlanData> plans = new ArrayList<>();  // plans：多套方案，与 styles 顺序一一对应

    /** 方案风格标签（food=美食向 / photo=出片向 / budget=省钱向） */
    private List<String> styles = new ArrayList<>();  // styles：方案风格 key（classic/loop/leisure）

    /** 每个方案的简短推荐语（对比卡片顶部展示） */
    private List<String> slogans = new ArrayList<>();  // slogans：每套方案的一句推荐语（对比卡片顶部展示）

    /** 每个方案的六维加权总分（0~100，与 plans 一一对应；旧数据无此字段） */
    private List<Integer> scores = new ArrayList<>();  // scores：每套方案的六维加权总分（0~100，与 plans 对应；旧数据无此字段）

    /** 推荐方案下标（评分择优，默认 0；前端可据此高亮「推荐」标记） */
    private int recommendedIndex = 0;  // recommendedIndex：评分择优后的推荐方案下标（前端据此高亮“推荐”）
}  // 类结束
