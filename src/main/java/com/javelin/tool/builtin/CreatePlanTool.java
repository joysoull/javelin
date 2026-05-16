package com.javelin.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javelin.tool.Tool;

/**
 * LLM 用来提交执行计划的特殊工具。
 *
 * 这个工具不会真正"执行"——PlanAndExecuteAgent 在 ReAct 循环中检测到
 * create_plan 调用时直接拦截，解析出 Plan 对象后转入执行阶段。
 * 如果万一走到 execute()，抛出 UnsupportedOperationException。
 *
 * inputSchema 的结构约束了 LLM 输出的计划格式：
 *   - goal: 总体目标
 *   - steps[]: 步骤列表，每步含 id/description/tool/arguments/depends_on
 */
public class CreatePlanTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "create_plan";
    }

    @Override
    public String description() {
        return "当你已经充分探索、准备好执行计划时，用此工具提交完整的执行计划。"
                + "每个步骤是一个工具调用，步骤之间通过 depends_on 声明依赖关系。"
                + "无依赖的步骤可以并行执行。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        // goal
        ObjectNode goalProp = MAPPER.createObjectNode();
        goalProp.put("type", "string");
        goalProp.put("description", "本次计划的总体目标，一句话描述");
        properties.set("goal", goalProp);

        // steps
        ObjectNode stepsProp = MAPPER.createObjectNode();
        stepsProp.put("type", "array");
        stepsProp.put("description", "执行步骤列表，按 depends_on 自动排序");

        ObjectNode stepItem = MAPPER.createObjectNode();
        stepItem.put("type", "object");

        ObjectNode stepProps = MAPPER.createObjectNode();
        // 任务类型
        ObjectNode typeProp = MAPPER.createObjectNode();
        typeProp.put("type", "string");
        typeProp.put("description", "步骤的语义类型："
                + "PLANNING(规划任务:分析和决策)、"
                + "FILE_READ(读取文件:获取信息)、"
                + "FILE_WRITE(写入文件:输出结果)、"
                + "COMMAND(执行命令:编译运行等)、"
                + "ANALYSIS(分析结果:中间决策)、"
                + "VERIFICATION(验证结果:检查正确性)");
        ArrayNode typeEnum = MAPPER.createArrayNode();
        typeEnum.add("PLANNING");
        typeEnum.add("FILE_READ");
        typeEnum.add("FILE_WRITE");
        typeEnum.add("COMMAND");
        typeEnum.add("ANALYSIS");
        typeEnum.add("VERIFICATION");
        typeProp.set("enum", typeEnum);
        stepProps.set("type", typeProp);

        ObjectNode idProp = MAPPER.createObjectNode();
        idProp.put("type", "string");
        idProp.put("description", "步骤唯一标识，如 step_1");
        stepProps.set("id", idProp);

        ObjectNode descProp = MAPPER.createObjectNode();
        descProp.put("type", "string");
        descProp.put("description", "这一步要做什么，人类可读");
        stepProps.set("description", descProp);

        ObjectNode toolProp = MAPPER.createObjectNode();
        toolProp.put("type", "string");
        toolProp.put("description", "要调用的工具名称，必须是可用工具之一");
        stepProps.set("tool", toolProp);

        ObjectNode argsProp = MAPPER.createObjectNode();
        argsProp.put("type", "object");
        argsProp.put("description", "传给工具的参数，与工具的 inputSchema 匹配。"
                + "可以使用 ${step_id.result} 引用前置步骤的输出");
        stepProps.set("arguments", argsProp);

        ObjectNode depsProp = MAPPER.createObjectNode();
        depsProp.put("type", "array");
        depsProp.put("description", "依赖的前置步骤 id 列表，无依赖时省略或空数组");
        ObjectNode depsItem = MAPPER.createObjectNode();
        depsItem.put("type", "string");
        ArrayNode depsItems = MAPPER.createArrayNode();
        depsItems.add(depsItem);
        depsProp.set("items", depsItems);
        stepProps.set("depends_on", depsProp);

        stepItem.set("properties", stepProps);

        ArrayNode stepRequired = MAPPER.createArrayNode();
        stepRequired.add("type");
        stepRequired.add("id");
        stepRequired.add("description");
        stepRequired.add("tool");
        stepRequired.add("arguments");
        stepItem.set("required", stepRequired);

        ArrayNode stepItems = MAPPER.createArrayNode();
        stepItems.add(stepItem);
        stepsProp.set("items", stepItems);
        properties.set("steps", stepsProp);

        schema.set("properties", properties);

        ArrayNode required = MAPPER.createArrayNode();
        required.add("goal");
        required.add("steps");
        schema.set("required", required);

        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        throw new UnsupportedOperationException(
                "create_plan 不应被直接执行，由 PlanAndExecuteAgent 在 ReAct 循环中拦截");
    }
}
