package com.example.octofixture.plain;

import io.yuan.octopus.extension.sdk.*;

@OctopusTool(name = "fixture.misplaced", version = "1.0.0", summary = "包名不合规", description = "不在 functions 包中。",
        whenToUse = "测试", determinism = Determinism.PURE, stages = {Stage.REQUEST_VALUE})
@ToolParam(index = 0, name = "a", type = ParamType.STRING, description = "a")
@ToolOutput(type = ParamType.STRING, description = "o")
@ToolExample(args = {"x"}, output = "x")
@ToolExample(args = {"y"}, output = "y")
public class MisplacedTool extends OctopusFunction {
    @Override protected Object compute(Args args) { return args.string("a"); }
}
