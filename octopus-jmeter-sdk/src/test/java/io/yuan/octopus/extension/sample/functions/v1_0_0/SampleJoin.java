package io.yuan.octopus.extension.sample.functions.v1_0_0;

import io.yuan.octopus.extension.sdk.*;

/** Test fixture: a PURE tool that joins two values with a separator. */
@OctopusTool(name = "sample.join", version = "1.0.0", summary = "用分隔符连接两个文本",
        description = "返回 left + separator + right；separator 缺省为 '-'。", whenToUse = "需要拼接两个请求值时",
        whenNotToUse = "需要摘要或加密时", determinism = Determinism.PURE, stages = {Stage.REQUEST_VALUE, Stage.EXPECTED_VALUE})
@ToolParam(index = 0, name = "left", type = ParamType.STRING, description = "左侧文本", maxLength = 100)
@ToolParam(index = 1, name = "right", type = ParamType.STRING, description = "右侧文本")
@ToolParam(index = 2, name = "separator", type = ParamType.STRING, required = false, defaultValue = "-", description = "分隔符")
@ToolOutput(type = ParamType.STRING, description = "连接结果")
@ToolExample(args = {"a", "b"}, output = "a-b", description = "默认分隔符")
@ToolExample(args = {"a,1", "$b", ":"}, output = "a,1:$b", description = "含逗号与美元符号")
@ToolError(code = "JOIN_EMPTY", description = "left 为空")
public class SampleJoin extends OctopusFunction {
    @Override
    protected Object compute(Args args) throws ToolException {
        if (args.string("left").isEmpty()) throw new ToolException("JOIN_EMPTY");
        return args.string("left") + args.string("separator") + args.string("right");
    }
}
