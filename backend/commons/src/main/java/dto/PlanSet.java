package dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 多方案行程集合（功能4：多方案盲测对比）。 */
@Data
@NoArgsConstructor
public class PlanSet {

    /** 多套方案（按 styles 顺序一一对应） */
    private List<TripPlanData> plans = new ArrayList<>();

    /** 方案风格标签（food=美食向 / photo=出片向 / budget=省钱向） */
    private List<String> styles = new ArrayList<>();

    /** 每个方案的简短推荐语（对比卡片顶部展示） */
    private List<String> slogans = new ArrayList<>();
}
