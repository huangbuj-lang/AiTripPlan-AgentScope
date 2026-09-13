package utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 实用信息区块必须覆盖：自驾装备 / 路线难度 / 高原反应（用户明确要求，走提示词实现）。 */
class AgentPromptsTipsTest {

    @Test
    @DisplayName("tripAggregator 提示词里必须写清 自驾装备 / 路线难度 / 高原反应 三类内容")
    void tipsMustCoverGearDifficultyAndAltitude() {
        String p = AgentPrompts.tripAggregator();
        assertNotNull(p, "tripAggregator 提示词不能为空");
        assertTrue(p.contains("实用信息"), "提示词里应有 实用信息(tips) 区块");
        assertTrue(p.contains("自驾装备"), "实用信息里缺【自驾装备】");
        assertTrue(p.contains("路线难度"), "实用信息里缺【路线难度】");
        assertTrue(p.contains("高原反应"), "实用信息里缺【高原反应】");
        assertTrue(p.contains("2500"), "高原反应应给出海拔门槛（2500 米以上）");
    }

    @Test
    @DisplayName("加内容不许破坏原有硬规则（住宿餐饮 / stops / duration）")
    void mustNotBreakExistingHardRules() {
        String p = AgentPrompts.tripAggregator();
        assertTrue(p.contains("sections 必须包含住宿餐饮(stay)区块"), "住宿餐饮硬规则被破坏");
        assertTrue(p.contains("stops"), "stops 硬规则被破坏");
        assertTrue(p.contains("duration 必须填原文里的实际时长"), "duration 硬规则被破坏");
        assertTrue(p.contains("第N天"), "逐日 timeline 规则被破坏");
    }
}
