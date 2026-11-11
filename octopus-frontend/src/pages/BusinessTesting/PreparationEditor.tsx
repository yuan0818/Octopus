import { useEffect, useState } from "react";
import { Alert, Button, Collapse, Input, Select, Space, Tag } from "antd";
import { DeleteOutlined, PlusOutlined, SafetyCertificateOutlined } from "@ant-design/icons";
import { businessTesting, type Binding, type Flow, type FlowNode, type Operation, type PreparationPlan, type PreparationTool } from "../../api/businessTesting";
import { Field, ValueInput } from "./FormControls";
import { defaultValue, valueType } from "./workbenchModel";
import { platformPreparation, preparationIssues } from "./preparationModel";

export default function PreparationEditor({ flow, node, operation, onChange }: {
  flow: Flow; node: FlowNode; operation: Operation; onChange: (plan: PreparationPlan | null) => void;
}) {
  const [catalog, setCatalog] = useState<PreparationTool[]>([]);
  const [error, setError] = useState(false);
  const [field, setField] = useState("");
  const [literalTypes, setLiteralTypes] = useState<Record<string,string>>({});
  const [header, setHeader] = useState("");
  const [toolId, setToolId] = useState<string>();
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let active = true;
    businessTesting.preparationTools().then(v => { if (active) { setCatalog(v); setError(false); } }).catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [reload]);
  const plan = node.preparation;
  const context = [...Object.keys(flow.initialContext), ...flow.nodes.filter(n => n.id !== node.id).flatMap(n => n.outputs.map(o => o.key))];
  const outputs = (before: number) => (plan?.tools || []).slice(0, before).flatMap(c => Object.keys(catalog.find(t => t.id === c.tool)?.outputs || {}).map(key => `${c.id}.${key}`));
  function bindingEditor(value: Binding, update: (v: Binding) => void, label: string, type: string, before: number, sensitive = false) {
    const typeKey = `${plan?.tools[before]?.id || "wire"}:${label}`;
    const literalType = type === "any" ? literalTypes[typeKey] || valueType(value.value) : type;
    const choices = [
      ...(!sensitive ? [{ value: "literal", label: "固定值" }] : []),
      { value: "context", label: "流程上下文" }, { value: "secret", label: "运行时凭据引用" },
      ...(!["publicKey", "signingSecret"].includes(label) ? [{ value: "result", label: "前面动作的结果" }, { value: "input", label: "已填 API 参数" }, ...(["object", "any"].includes(type) ? [{ value: "request", label: "原始请求" }] : [])] : []),
    ];
    const options = value.source === "context" ? context : value.source === "result" ? outputs(before) : value.source === "input" ? Object.keys(node.inputs) : ["body", "query", "headers"];
    return <div className="bt-preparation-binding">
      <Select aria-label={`${label}来源`} value={value.source} options={choices} onChange={source => update({ source, value: source === "literal" ? defaultValue(type === "any" ? "string" : type) : "" })} />
      {value.source === "literal" ? label === "algorithm" ? <Select aria-label="签名算法" placeholder="选择当前环境的算法" value={value.value || undefined} options={[{ value: "MD5", label: "MD5" }, { value: "SHA-256", label: "SHA-256" }]} onChange={v => update({ source: "literal", value: v })} /> :
        <div>{type === "any" && <Select aria-label={`${label}值类型`} style={{ width: "100%", marginBottom: 6 }} value={literalType} options={["string", "integer", "number", "boolean", "object", "array"].map(value => ({ value, label: ({ string: "文本", integer: "整数", number: "数字", boolean: "是 / 否", object: "对象", array: "列表" } as Record<string,string>)[value] }))} onChange={v => { setLiteralTypes(current => ({ ...current, [typeKey]: v })); update({ source: "literal", value: defaultValue(v) }); }} />}
          <ValueInput label={label} type={literalType} value={value.value} onChange={v => update({ source: "literal", value: v })} /></div> :
        value.source === "secret" ? <Input aria-label={`${label}凭据引用`} placeholder="引用名称，例如 platform.signingSecret" value={String(value.value)} onChange={e => update({ source: "secret", value: e.target.value })} /> :
        <Select aria-label={`${label}绑定`} showSearch placeholder="选择数据来源" value={value.value || undefined} options={options.map(v => ({ value: v, label: v }))} onChange={v => update({ ...value, value: v })} />}
    </div>;
  }
  function updateInput(index: number, key: string, value: Binding) {
    if (!plan) return;
    const next = structuredClone(plan); next.tools[index].inputs[key] = value;
    if (next.tools[index].tool === "platform.sign" && ["appid", "timestamp", "requestid"].includes(key)) next.headers[key] = value;
    onChange(next);
  }
  const issues = preparationIssues(plan);
  return <section className="bt-inspector-section bt-preparation">
    <h3><SafetyCertificateOutlined /> 请求加密与网关预处理</h3>
    <p>每次请求前生成动态数据。模型可以根据仓库背景选择工具，你也可以在这里配置。</p>
    {error && <Alert type="warning" showIcon message="工具目录加载失败" action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    {!plan ? <Space wrap>
      <Button disabled={!catalog.length} onClick={() => onChange(platformPreparation(true, node))}>Platform 加密并签名</Button>
      <Button disabled={!catalog.length} onClick={() => onChange(platformPreparation(false, node))}>仅网关签名</Button>
      <Button disabled={!catalog.length} onClick={() => onChange({ tools: [{ id: "timestamp", tool: "time.now", version: "1", inputs: {} }], body: null, headers: {} })}>自定义预处理</Button>
    </Space> : <>
      <div className="bt-preparation-heading"><Tag color="purple">{plan.tools.length} 个动作 · 发送请求前执行</Tag><Button size="small" danger onClick={() => onChange(null)}>移除预处理</Button></div>
      <Alert type="info" showIcon message={plan.body ? "实际发送的 Body 将由预处理结果替换" : "保留原始 Body，补充网关请求头"}
        description="凭据只填写引用名称，在运行页面绑定服务环境变量。失败会停止请求；重新运行会重新生成动态数据。" />
      <Collapse className="bt-preparation-tools" items={plan.tools.map((call, index) => {
        const definition = catalog.find(t => t.id === call.tool);
        return { key: call.id, label: <span>{index + 1}. {definition?.title || call.tool} <small> · {call.id}</small></span>, children: <>
          <p className="bt-muted">{definition?.description}</p>
          {Object.entries(call.inputs).map(([key, value]) => <div key={key}>
            <Field label={key}>{bindingEditor(value, v => updateInput(index, key, v), key, definition?.inputs[key] || "any", index,
              !!definition?.sensitiveInputs.includes(key) || /password|secret|token/i.test(key))}</Field>
            {call.tool === "json.object" && <Button size="small" type="text" danger onClick={() => {
              const next = structuredClone(plan); delete next.tools[index].inputs[key]; onChange(next);
            }}>移除字段 {key}</Button>}
          </div>)}
          {call.tool === "json.object" && <Space.Compact style={{ width: "100%" }}>
            <Input aria-label="明文业务字段名" placeholder="业务字段名，如 email、password" value={field} onChange={e => setField(e.target.value)} />
            <Button disabled={!/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(field) || field in call.inputs} onClick={() => {
              updateInput(index, field, /password|secret|token/i.test(field) ? { source: "secret", value: `${node.id}.${field}` } : { source: "literal", value: "" }); setField("");
            }}>添加字段</Button>
          </Space.Compact>}
          <div className="bt-preparation-heading"><small>输出：{Object.keys(definition?.outputs || {}).map(key => `${call.id}.${key}`).join("、")}</small>
            <Button type="text" danger icon={<DeleteOutlined />} onClick={() => onChange({ ...plan, tools: plan.tools.filter((_, i) => i !== index) })}>删除动作</Button></div>
        </> };
      })} />
      <Space.Compact style={{ width: "100%" }}><Select aria-label="添加预处理工具" placeholder="选择工具" style={{ flex: 1 }} value={toolId} options={catalog.map(t => ({ value: t.id, label: t.title }))} onChange={setToolId} />
        <Button icon={<PlusOutlined />} disabled={!toolId || plan.tools.length >= 32} onClick={() => {
          const tool = catalog.find(t => t.id === toolId)!;
          const id = `action${Date.now()}`;
          onChange({ ...plan, tools: [...plan.tools, { id, tool: tool.id, version: tool.version,
            inputs: Object.fromEntries(Object.entries(tool.inputs).map(([key, type]) => [key, { source: tool.sensitiveInputs.includes(key) ? "secret" : "literal", value: tool.sensitiveInputs.includes(key) ? "" : defaultValue(type === "any" ? "string" : type) }])) }] });
          setToolId(undefined);
        }}>添加</Button></Space.Compact>
      <Collapse style={{ marginTop: 12 }} items={[{ key: "wire", label: "实际请求映射 · Body 与网关 Header", children: <>
        <Field label="Body 来源"><Select aria-label="请求体来源" value={plan.body ? "generated" : "original"} options={[{ value: "original", label: "原始 API 请求体" }, { value: "generated", label: "使用预处理结果" }]} onChange={v => onChange({ ...plan, body: v === "original" ? null : { source: "result", value: "" } })} /></Field>
        {plan.body && bindingEditor(plan.body, v => onChange({ ...plan, body: v }), "最终请求体", "object", plan.tools.length)}
        {Object.entries(plan.headers).map(([key, value]) => <div key={key}><Field label={`Header · ${key}`}>{bindingEditor(value, v => onChange({ ...plan, headers: { ...plan.headers, [key]: v } }), key, "string", plan.tools.length, /authorization|token|cookie/i.test(key))}</Field>
          <Button size="small" type="text" danger onClick={() => { const headers = { ...plan.headers }; delete headers[key]; onChange({ ...plan, headers }); }}>移除 Header · {key}</Button></div>)}
        <Space.Compact style={{ width: "100%" }}><Input aria-label="新增网关 Header" placeholder="Header 名称，例如 flToken" value={header} onChange={e => setHeader(e.target.value)} />
          <Button disabled={!/^[A-Za-z][A-Za-z0-9-]*$/.test(header) || header in plan.headers} onClick={() => { onChange({ ...plan, headers: { ...plan.headers, [header]: { source: "context", value: "" } } }); setHeader(""); }}>添加 Header</Button></Space.Compact>
      </> }]} />
      {!!issues.length && <Alert style={{ marginTop: 12 }} type="warning" message="还需要完成配置" description={<ul>{issues.map(e => <li key={e}>{e}</li>)}</ul>} />}
    </>}
    <small className="bt-muted">API 契约：{operation.method} {operation.path}。使用模板前请核对所选仓库版本与测试环境的协议。</small>
  </section>;
}
