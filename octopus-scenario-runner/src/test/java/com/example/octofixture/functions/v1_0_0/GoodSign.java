package com.example.octofixture.functions.v1_0_0;

import io.yuan.octopus.extension.sdk.*;

@OctopusTool(name = "fixture.sign", version = "1.0.0", summary = "计算测试签名", description = "返回 body 与 secret 拼接后的长度，用于测试。",
        whenToUse = "测试签名场景", whenNotToUse = "生产签名", determinism = Determinism.PURE,
        stages = {Stage.REQUEST_TRANSFORM}, category = "crypto", tags = {"sign"})
@ToolParam(index = 0, name = "body", type = ParamType.STRING, description = "请求体")
@ToolParam(index = 1, name = "secret", type = ParamType.STRING, sensitive = true, description = "密钥")
@ToolOutput(type = ParamType.INTEGER, description = "长度")
@ToolExample(args = {"{}", "<test-secret>"}, output = "15")
@ToolExample(args = {"{\"a\":1}", "<test-secret>"}, output = "20")
@ToolError(code = "SIGN_EMPTY", description = "body 为空")
public class GoodSign extends OctopusFunction {
    @Override protected Object compute(Args args) throws ToolException {
        if (args.string("body").isEmpty()) throw new ToolException("SIGN_EMPTY");
        return (long) (args.string("body") + args.string("secret")).length();
    }
}
