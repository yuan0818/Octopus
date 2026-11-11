package io.yuan.octopus.extension.fixture.functions.v1_0_0;

import io.yuan.octopus.extension.sdk.*;

/** Test fixture: joins all arguments with '|', preserving every character. */
@OctopusTool(name = "fixture.echo", version = "1.0.0", summary = "原样连接参数", description = "用 | 连接全部参数。",
        whenToUse = "测试", determinism = Determinism.PURE, stages = {Stage.REQUEST_VALUE})
@ToolParam(index = 0, name = "a", type = ParamType.STRING, description = "第一个")
@ToolParam(index = 1, name = "b", type = ParamType.STRING, required = false, description = "第二个")
@ToolParam(index = 2, name = "n", type = ParamType.INTEGER, required = false, defaultValue = "7", description = "数字")
@ToolOutput(type = ParamType.STRING, description = "连接结果")
@ToolExample(args = {"x", "y"}, output = "x|y|7")
@ToolExample(args = {"a,1", "${v}", "3"}, output = "a,1|${v}|3")
public class EchoJoin extends OctopusFunction {
    @Override
    protected Object compute(Args args) {
        return args.string("a") + "|" + (args.has("b") ? args.string("b") : "") + "|" + args.integer("n");
    }
}
