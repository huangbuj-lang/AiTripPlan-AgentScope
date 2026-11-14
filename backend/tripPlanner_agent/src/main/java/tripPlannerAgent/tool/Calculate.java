package tripPlannerAgent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** 计算工具 */
public class Calculate {  // 类声明：给行程子 Agent 用的计算工具


      //求和计算：把逗号分隔的数字串加起来，返回总和
    /**
     * 作用：求和工具：把逗号分隔的数字串累加并返回结果文本，供子 Agent 做费用/里程合计
     * 参数：numbers 逗号分隔的数字串，例如 100,200,50
     * 返回值：求和结果文本；入参为空或含非数字时返回失败提示文本
     * 异常：无（数字格式异常内部捕获并转成提示文本）
     */


    @Tool(description = "求和计算：计算多个数字的总和，传入逗号分隔的数字，例如：100,200,50")  // @Tool：注册成 Agent 可调用工具（description 是给模型看的说明）
    public String sum(  // sum 方法签名：入参是数字串，返回结果文本
            @ToolParam(name = "numbers", description = "需要求和的数字，多个用逗号分隔，例如：100,200,50")  // @ToolParam：声明参数名与含义（模型据此填参）
            String numbers) {  // numbers 参数：逗号分隔的数字串

        //(1) 保护：输入为空直接返回提示（不让模型拿到空结果）
        if (numbers == null || numbers.isBlank()) {  // 空 / 空白判断
            return "求和失败：没有传入数字";  // 返回失败提示文本（模型能看到并纠正）
        }  // if 结束

        try {  // try：解析失败走 catch，不抛异常
            //(2) 用逗号把字符串拆成多个数字片段，例如 "100,200,50" -> ["100","200","50"]
            String[] parts = numbers.split(",");  // split 成数字片段

            //(3) 逐个转成数字并累加
            double total = 0;  // total：累加器
            for (String part : parts) {  // 遍历每个片段
                total += Double.parseDouble(part.trim()); // trim()去掉多余空格  // 解析成 double 并累加（trim 去空格）
            }  // for 结束

            //(4) 返回求和结果文本（模型会读到这个返回值）
            return "求和结果：" + total;  // 拼“求和结果：”+ total
        } catch (NumberFormatException e) {  // 捕获数字格式异常
            //(5) 输入里有非数字内容时给出友好提示（不抛异常，避免Agent中断）
            return "求和失败：包含无法识别的数字，请用逗号分隔纯数字";  // 返回提示文本，不中断 Agent 轮次
        }  // catch 结束
    }  // 方法结束
}  // 类结束

