import type { Binding, FlowNode, PreparationPlan, Port } from "../../api/businessTesting";

export function preparationValues(plan?: PreparationPlan | null): Binding[] {
  return plan ? [...plan.tools.flatMap(t => Object.values(t.inputs)), ...(plan.body ? [plan.body] : []), ...Object.values(plan.headers)] : [];
}
export function preparedPort(plan: PreparationPlan | null | undefined, port: Port): boolean {
  return !!plan && (port.location === "body" && !!plan.body || port.location === "header" && Object.keys(plan.headers).some(k => k.toLowerCase() === port.name.toLowerCase()));
}
const literal = (value: unknown): Binding => ({ source: "literal", value });
const result = (value: string): Binding => ({ source: "result", value });
export function platformPreparation(encrypt: boolean, node: FlowNode): PreparationPlan {
  const timestamp = result("timestamp.value"), textTimestamp = result("timestampText.value"), requestid = result("requestId.value");
  const body: Binding = encrypt ? result("encrypt.body") : { source: "request", value: "body" };
  const appid = literal("");
  return {
    tools: [
      { id: "timestamp", tool: "time.now", version: "1", inputs: {} },
      { id: "timestampText", tool: "string.value", version: "1", inputs: { value: timestamp } },
      { id: "requestId", tool: "uuid", version: "1", inputs: {} },
      ...(encrypt ? [
        { id: "payload", tool: "json.object", version: "1", inputs: {} },
        { id: "encrypt", tool: "platform.encrypt", version: "1", inputs: {
          content: node.inputs["body.content"] ? { source: "input", value: "body.content" } : result("payload.value"),
          publicKey: { source: "secret", value: "platform.publicKey" }, keyPairId: literal(""), timestamp, version: literal(1),
        } },
      ] : []),
      { id: "sign", tool: "platform.sign", version: "1", inputs: {
        body, query: { source: "request", value: "query" }, appid, requestid, timestamp: textTimestamp,
        algorithm: literal(""), signingSecret: { source: "secret", value: "platform.signingSecret" },
      } },
    ],
    body: encrypt ? body : null,
    headers: { appid, requestid, timestamp: textTimestamp, sign: result("sign.sign") },
  };
}
export function preparationIssues(plan?: PreparationPlan | null): string[] {
  if (!plan) return [];
  const errors: string[] = [];
  if (!plan.tools.length) errors.push("至少添加一个预处理动作，或移除预处理计划");
  const outputs = new Set<string>();
  plan.tools.forEach(call => {
    Object.entries(call.inputs).forEach(([key, value]) => {
      if (value.source !== "literal" && !String(value.value || "").trim()) errors.push(`${call.id}：填写 ${key} 的数据来源`);
      if (value.source === "result" && !outputs.has(String(value.value))) errors.push(`${call.id}：${key} 引用的前置动作不存在`);
      if (["appid", "keyPairId", "algorithm"].includes(key) && value.source === "literal" && !value.value) errors.push(`${call.id}：确认环境中的 ${key}`);
    });
    if (call.tool === "json.object" && !Object.keys(call.inputs).length && plan.tools.some(t => Object.values(t.inputs).some(v => v.source === "result" && v.value === `${call.id}.value`))) errors.push(`${call.id}：添加明文业务字段`);
    if (call.tool === "platform.sign") {
      const same = (a: Binding | undefined, b: Binding | null | undefined) => a?.source === b?.source && JSON.stringify(a?.value) === JSON.stringify(b?.value);
      if (!same(call.inputs.body, plan.body || { source: "request", value: "body" })) errors.push(`${call.id}：签名必须使用最终请求体`);
      for (const key of ["appid", "requestid", "timestamp"]) if (!same(call.inputs[key], plan.headers[key])) errors.push(`${call.id}：签名与 Header 的 ${key} 必须绑定同一个值`);
    }
    outputs.add(`${call.id}.${call.tool === "platform.encrypt" ? "body" : call.tool === "platform.sign" ? "sign" : "value"}`);
  });
  for (const value of [...Object.values(plan.headers), ...(plan.body ? [plan.body] : [])]) {
    if (value.source === "result" && !outputs.has(String(value.value))) errors.push("请求映射引用了不存在的预处理输出");
    if (value.source !== "literal" && !String(value.value || "").trim()) errors.push("填写最终请求映射的数据来源");
  }
  return errors;
}
export const preparationErrors: Record<string, string> = {
  PREPARE_CREDENTIAL_MISSING: "运行时凭据未配置或为空，请检查密钥引用对应的服务环境变量",
  PREPARE_INPUT_MISSING: "预处理所需的上下文或参数尚未产生",
  PREPARE_INPUT_INVALID: "预处理参数类型或格式不正确",
  PREPARE_BINDING_INVALID: "预处理数据绑定无效",
  PREPARE_INPUT_TOO_LARGE: "预处理输入超过 1 MB 限制",
  PREPARE_PUBLIC_KEY_INVALID: "RSA 公钥格式无效，请提供 X.509 PUBLIC KEY PEM 或 Base64",
  PREPARE_ALGORITHM_UNSUPPORTED: "签名算法不支持，请核对环境中的 MD5 / SHA-256 配置",
  PREPARE_CRYPTO_FAILED: "加密失败，请核对密钥及协议配置；请求未发送",
  PREPARE_HEADER_INVALID: "生成的请求头不是合法文本；请求未发送",
  PREPARE_TOOL_UNAVAILABLE: "JMeter 预处理扩展缺失或版本不匹配，请更新运行时",
};
