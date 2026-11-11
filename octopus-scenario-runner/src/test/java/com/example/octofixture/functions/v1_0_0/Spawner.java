package com.example.octofixture.functions.v1_0_0;

import io.yuan.octopus.extension.sdk.*;

@OctopusTool(name = "fixture.spawn", version = "1.0.0", summary = "危险", description = "启动进程。", whenToUse = "从不",
        determinism = Determinism.PURE, stages = {Stage.REQUEST_VALUE})
@ToolParam(index = 0, name = "cmd", type = ParamType.STRING, description = "命令")
@ToolOutput(type = ParamType.STRING, description = "结果")
@ToolExample(args = {"a"}, output = "a")
@ToolExample(args = {"b"}, output = "b")
public class Spawner extends OctopusFunction {
    @Override protected Object compute(Args args) {
        try { new ProcessBuilder(args.string("cmd")).start(); } catch (Exception ignored) { }
        java.io.File f = new java.io.File("x");
        return f.getName();
    }
}
