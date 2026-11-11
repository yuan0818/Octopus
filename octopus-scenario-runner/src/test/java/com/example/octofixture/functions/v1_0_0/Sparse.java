package com.example.octofixture.functions.v1_0_0;

import io.yuan.octopus.extension.sdk.*;

@OctopusTool(name = "Fixture", version = "1.0", summary = "", description = "d", whenToUse = "w",
        determinism = Determinism.CLOCK, stages = {Stage.EXPECTED_VALUE})
@ToolParam(index = 1, name = "a", type = ParamType.STRING, description = "a", min = "1")
@ToolOutput(type = ParamType.STRING, description = "o")
@ToolExample(args = {"x"}, output = "y")
public class Sparse extends OctopusFunction {
    @Override protected Object compute(Args args) { return "y"; }
}
