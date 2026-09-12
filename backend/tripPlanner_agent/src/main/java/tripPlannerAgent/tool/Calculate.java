package tripPlannerAgent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** 计算工具 */
public class Calculate {


      //求和计算：把逗号分隔的数字串加起来，返回总和


    @Tool(description = "求和计算：计算多个数字的总和，传入逗号分隔的数字，例如：100,200,50")
    public String sum(
            @ToolParam(name = "numbers", description = "需要求和的数字，多个用逗号分隔，例如：100,200,50")
            String numbers) {

        //(1) 保护：输入为空直接返回提示（不让模型拿到空结果）
        if (numbers == null || numbers.isBlank()) {
            return "求和失败：没有传入数字";
        }

        try {
            //(2) 用逗号把字符串拆成多个数字片段，例如 "100,200,50" -> ["100","200","50"]
            String[] parts = numbers.split(",");

            //(3) 逐个转成数字并累加
            double total = 0;
            for (String part : parts) {
                total += Double.parseDouble(part.trim()); // trim()去掉多余空格
            }

            //(4) 返回求和结果文本（模型会读到这个返回值）
            return "求和结果：" + total;
        } catch (NumberFormatException e) {
            //(5) 输入里有非数字内容时给出友好提示（不抛异常，避免Agent中断）
            return "求和失败：包含无法识别的数字，请用逗号分隔纯数字";
        }
    }
}
