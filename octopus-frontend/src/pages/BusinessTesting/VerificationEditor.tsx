import { environmentOptions } from "./environmentModel";
import { useEffect, useRef, useState } from "react";
import { Alert, Button, ConfigProvider, Drawer, Input, InputNumber, Select, Space, Switch, Tag } from "antd";
import { DeleteOutlined, PlusOutlined, ThunderboltOutlined } from "@ant-design/icons";
import type { Assertion, Binding, Flow, Operation } from "../../api/businessTesting";
import { emptyContextScope, projectContextApi, type ConnectionInfo } from "../../api/projectContext";
import { verificationApi, type VerificationPlan, type VerificationSuggestion } from "../../api/verification";
import { queryApi } from "../../api/query";
import { verificationValues } from "./verificationModel";
import ProjectScopeFields from "./ProjectScopeFields";
import { Field, ValueInput } from "./FormControls";
import { errorText, uid, valueType, defaultValue } from "./workbenchModel";

function BoundValue({ label, value, variables, onChange }: { label: string; value: Binding; variables: Record<string,string>; onChange: (v: Binding) => void }) {
  return <div className="bt-verification-value">
    <Field label={`${label} 来源`}><Select value={value.source} options={[{ value: "context", label: "使用流程数据" }, { value: "literal", label: "填写固定值" }]}
      onChange={source => onChange({ source, value: "" })} /></Field>
    {value.source === "context" ? <Field label={label}><Select showSearch value={String(value.value) || undefined} placeholder="选择初始数据或步骤输出"
      options={Object.entries(variables).map(([key, type]) => ({ value: key, label: `${key} · ${type}` }))} onChange={v => onChange({ source: "context", value: v })} /></Field>
      : <><Field label={`${label} 类型`}><Select value={valueType(value.value)} options={[{ value: "string", label: "文本" }, { value: "number", label: "数字" }, { value: "boolean", label: "是 / 否" }]}
        onChange={type => onChange({ source: "literal", value: defaultValue(type) })} /></Field><ValueInput label={label} value={value.value}
          onChange={v => onChange({ source: "literal", value: v })} /></>}
  </div>;
}

export default function VerificationEditor({ flow, operation, assertion, onClose, onSave, initialEnvironment = "test", onEnvironmentChange }: {
  flow: Flow; operation?: Operation; assertion?: Assertion; initialEnvironment?: string; onEnvironmentChange?: (v: string) => void; onClose: () => void; onSave: (a: Assertion) => void;
}) {
  const initial = assertion?.expected as VerificationPlan | undefined;
  const [scope, setScope] = useState(initial ? { ...emptyContextScope(), repositoryKey: initial.connection.repositoryKey,
    modulePaths: initial.connection.modulePath ? [initial.connection.modulePath] : undefined } : emptyContextScope());
  const [environment, setEnvironment] = useState(initialEnvironment);
  const [environments, setEnvironments] = useState(["test"]);
  const [connections, setConnections] = useState<ConnectionInfo[]>([]);
  const [connectionId, setConnectionId] = useState(initial?.connection.connectionId || "");
  const [description, setDescription] = useState(initial?.description || "");
  const [plan, setPlan] = useState<VerificationPlan | null>(initial || null);
  const [result, setResult] = useState<VerificationSuggestion | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [model, setModel] = useState("");
  const [models, setModels] = useState<{ name: string }[]>([]);
  const [preview, setPreview] = useState("");
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const modulePath = scope.modulePaths?.[0] || "";
  const connection = connections.find(c => c.id === connectionId);
  const variables = Object.fromEntries([...Object.entries(flow.initialContext).map(([key, v]) => [key, v.type]),
    ...flow.nodes.flatMap(n => n.outputs.map(o => [o.key, o.type]))].filter(([, type]) => ["string", "integer", "number", "boolean"].includes(type)));
  useEffect(() => {
    let alive = true;
    void projectContextApi.models().then(r => { if (alive) setModels(r); }).catch(() => { /* manual editing remains available */ });
    if (!initial && operation?.implementations.length) void queryApi.repositoryScopes().then(r => {
      if (!alive) return;
      const project = r.data.find(s => s.modules.some(m => operation.implementations.some(i => i.projectId === m.projectId)));
      const module = project?.modules.find(m => operation.implementations.some(i => i.projectId === m.projectId));
      if (project && module) setScope({ ...emptyContextScope(), repositoryKey: project.key, modulePaths: [module.modulePath] });
    }).catch(() => {});
    return () => { alive = false; };
  }, []);
  useEffect(() => {
    let alive = true; setConnections([]); setPreview(""); setLoading(false);
    if (scope.repositoryKey) {
      setLoading(true);
      void Promise.all([projectContextApi.get(scope.repositoryKey, modulePath, environment), projectContextApi.environments(scope.repositoryKey)])
        .then(([context, names]) => { if (!alive) return; setConnections(context.effective.connections.filter(c => ["mysql", "postgresql", "redis", "kafka"].includes(c.kind))); setEnvironments(names); })
        .catch(e => { if (alive) setError(errorText(e)); }).finally(() => { if (alive) setLoading(false); });
    }
    return () => { alive = false; };
  }, [scope.repositoryKey, modulePath, environment]);
  const current = (p: VerificationPlan): VerificationPlan => ({ ...p, description, connection: { repositoryKey: scope.repositoryKey, modulePath, connectionId } });
  const update = (patch: Partial<VerificationPlan>) => { setPlan(p => p ? { ...p, ...patch } : null); setPreview(""); };
  const manual = () => {
    if (!connection) return;
    const kind = connection.kind === "redis" ? "REDIS" : connection.kind === "kafka" ? "KAFKA" : "SQL";
    setPlan(current({ kind, connection: { repositoryKey: "", modulePath: "", connectionId: "" }, description,
      sql: kind === "SQL" ? { table: "", columns: [], count: false, where: [{ column: "", operator: "EQ", value: { source: "context", value: "" } }] } : null,
      redis: kind === "REDIS" ? { command: "GET", keyPrefix: "", key: { source: "context", value: "" }, field: null } : null,
      kafka: kind === "KAFKA" ? { topic: "", key: { source: "context", value: "" } } : null,
      expect: { path: kind === "SQL" ? "$.rows[0].status" : "$.value", operator: "EQ", value: { source: "literal", value: "" } }, timeoutMillis: 5000 }));
    setResult(null); setPreview(""); setError("");
  };
  const validate = async (save: boolean) => {
    if (!plan) return;
    setBusy(true); setError("");
    try {
      const next = current(plan);
      if (verificationValues(next).some(v => v.source === "context" && !variables[String(v.value)])) throw new Error("请选择已有的流程变量；可在流程设置中添加初始数据，或为接口步骤添加输出。");
      const checked = await verificationApi.preview(next, environment);
      if (!active.current) return;
      setPreview(checked.preview);
      if (save) onSave({ id: assertion?.id || uid("datastore"), kind: "DATASTORE_VERIFY", path: null, expected: next });
    } catch (e) { if (active.current) setError(errorText(e)); } finally { if (active.current) setBusy(false); }
  };
  return <Drawer open title="验证数据库与中间件" width={760} maskClosable={!busy} closable={!busy} onClose={onClose}
    footer={<div className="bt-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button disabled={!plan || !connection || loading || !description.trim()} loading={busy} type="primary" onClick={() => void validate(true)}>加入当前测试点</Button></div>}>
    <p className="bt-muted">描述接口执行后应该发生的数据变化。AI 生成查询草稿，你确认后将它作为步骤的业务检查。</p>
    <ConfigProvider componentDisabled={busy}><fieldset disabled={busy} className="bt-verification-fields">
      <ProjectScopeFields value={scope} disabled={busy} onChange={v => { setScope(v); setConnectionId(""); setPlan(null); setResult(null); setError(""); }} />
      <div className="bt-form-grid"><Field label="验证环境"><Select disabled={busy} value={environment} options={environmentOptions([...environments, environment])}
        onChange={v => { setEnvironment(v); onEnvironmentChange?.(v); setResult(null); setError(""); }} /></Field>
      <Field label="验证连接"><Select disabled={busy || loading} loading={loading} value={connection?.id} placeholder="选择已保存的数据库或中间件"
        options={connections.map(c => ({ value: c.id, label: `${c.name || c.id} · ${c.kind}` }))}
        onChange={v => { setConnectionId(v); setPlan(null); setResult(null); setError(""); }} /></Field></div>
      {!loading && scope.repositoryKey && !connections.length && <Alert type="info" showIcon message="此范围还没有可验证的连接" description="在「环境变量」中保存 MySQL、PostgreSQL、Redis 或 Kafka 连接，并填写表字段、key 格式或 topic 说明。" />}
      {connection && <div className="bt-context-inherited"><div><strong>结构与用途说明</strong><p style={{ whiteSpace: "pre-wrap" }}>{connection.notes || "尚未填写。AI 需要表与字段、Redis key 格式或 Kafka topic 信息；请在环境变量中补充，或手工配置。"}</p></div></div>}
      <Field label="想验证什么？"><Input.TextArea rows={3} value={description} placeholder="例如：支付后，orders 表中本次 order.id 对应记录的 status 为 PAID"
        onChange={e => { setDescription(e.target.value); setPreview(""); }} /></Field>
      <div className="bt-form-grid"><Field label="生成模型"><Select disabled={busy} value={model} options={[{ value: "", label: "使用已配置的默认模型" }, ...models.map(m => ({ value: m.name, label: m.name }))]} onChange={setModel} /></Field>
      <Space wrap><Button type="primary" icon={<ThunderboltOutlined />} disabled={!connection || !description.trim() || loading || busy} loading={busy} onClick={async () => {
        setBusy(true); setError(""); setPreview(""); setPlan(null); setResult(null);
        try {
          const next = await verificationApi.suggest({ source: { repositoryKey: scope.repositoryKey, modulePath, connectionId }, environment, description, variables, model });
          if (active.current) { setResult(next); setPlan(next.plan); setPreview(next.preview); }
        } catch (e) { if (active.current) setError(errorText(e)); } finally { if (active.current) setBusy(false); }
      }}>AI 生成验证</Button><Button disabled={!connection || busy || loading} onClick={manual}>手工配置</Button></Space></div>
      {result && <><Tag color="blue">生成模型 · {result.model}</Tag>{result.questions.length > 0 && <Alert type="warning" showIcon message="需要补充或确认的信息" description={result.questions.map((q, i) => <p key={i}>{q}</p>)} />}
        {result.assumptions.length > 0 && <Alert type="info" message="草稿假设" description={result.assumptions.map((a, i) => <p key={i}>{a}</p>)} />}</>}
      {plan && <section className="bt-inspector-section"><h3>查询与预期结果 <Tag>{plan.kind}</Tag></h3>
        {plan.sql && <>
          <Field label="数据表"><Input value={plan.sql.table} placeholder="orders 或 schema.orders" onChange={e => update({ sql: { ...plan.sql!, table: e.target.value } })} /></Field>
          <Field label="只统计匹配数量"><Switch disabled={busy} checked={plan.sql.count} onChange={count => update({ sql: { ...plan.sql!, count } })} /></Field>
          {!plan.sql.count && <Field label="读取字段"><Select disabled={busy} mode="tags" value={plan.sql.columns} tokenSeparators={[","]} placeholder="输入字段名后回车"
            onChange={columns => update({ sql: { ...plan.sql!, columns } })} /></Field>}
          {plan.sql.where.map((filter, i) => <div className="bt-param" key={i}><div className="bt-form-grid">
            <Field label={`条件 ${i + 1} 字段`}><Input value={filter.column} onChange={e => update({ sql: { ...plan.sql!, where: plan.sql!.where.map((f, j) => j === i ? { ...f, column: e.target.value } : f) } })} /></Field>
            <Field label={`条件 ${i + 1} 比较`}><Select disabled={busy} value={filter.operator} options={["EQ", "NE", "GT", "GE", "LT", "LE"].map((value, j) => ({ value, label: ["等于", "不等于", "大于", "大于等于", "小于", "小于等于"][j] }))}
              onChange={operator => update({ sql: { ...plan.sql!, where: plan.sql!.where.map((f, j) => j === i ? { ...f, operator } : f) } })} /></Field></div>
            <BoundValue label={`条件 ${i + 1} 数据`} value={filter.value} variables={variables} onChange={value => update({ sql: { ...plan.sql!, where: plan.sql!.where.map((f, j) => j === i ? { ...f, value } : f) } })} />
            <Button danger type="text" disabled={busy || plan.sql!.where.length <= 1} onClick={() => update({ sql: { ...plan.sql!, where: plan.sql!.where.filter((_, j) => j !== i) } })}>移除条件</Button></div>)}
          <Button icon={<PlusOutlined />} disabled={busy || plan.sql.where.length >= 10} onClick={() => update({ sql: { ...plan.sql!, where: [...plan.sql!.where, { column: "", operator: "EQ", value: { source: "literal", value: "" } }] } })}>添加过滤条件</Button>
        </>}
        {plan.redis && <><Field label="Redis 查询命令"><Select disabled={busy} value={plan.redis.command} options={["GET", "HGET", "EXISTS", "TTL"].map(value => ({ value }))}
          onChange={command => update({ redis: { ...plan.redis!, command } })} /></Field>
          <Field label="Key 前缀"><Input value={plan.redis.keyPrefix} placeholder="例如 order:" onChange={e => update({ redis: { ...plan.redis!, keyPrefix: e.target.value } })} /></Field>
          <BoundValue label="Key 数据" value={plan.redis.key} variables={variables} onChange={key => update({ redis: { ...plan.redis!, key } })} />
          {plan.redis.command === "HGET" && <Field label="Hash 字段"><Input value={plan.redis.field || ""} onChange={e => update({ redis: { ...plan.redis!, field: e.target.value } })} /></Field>}</>}
        {plan.kafka && <><Alert type="info" showIcon message="只观察本步骤产生的新消息" description="接口调用前记录各分区位置；验证消息 key 和内容，不加入业务消费组、不提交位点。" />
          <Field label="Kafka Topic"><Input value={plan.kafka.topic} onChange={e => update({ kafka: { ...plan.kafka!, topic: e.target.value } })} /></Field>
          <BoundValue label="消息关联 Key" value={plan.kafka.key} variables={variables} onChange={key => update({ kafka: { ...plan.kafka!, key } })} /></>}
        <div className="bt-form-grid"><Field label="检查结果字段" hint={plan.kind === "SQL" ? "例如 $.rows[0].status、$.rowCount" : "例如 $.value、$.value.status"}><Input value={plan.expect.path} onChange={e => update({ expect: { ...plan.expect, path: e.target.value } })} /></Field>
          <Field label="预期条件"><Select disabled={busy} value={plan.expect.operator} options={[{ value: "EQ", label: "等于预期值" }, { value: "EXISTS", label: "字段存在且非空值" }, { value: "GT", label: "大于" }, { value: "GE", label: "大于等于" }]}
            onChange={operator => update({ expect: { ...plan.expect, operator, value: operator === "EXISTS" ? null : plan.expect.value || { source: "literal", value: "" } } })} /></Field></div>
        {plan.expect.value && <BoundValue label="预期值" value={plan.expect.value} variables={variables} onChange={value => update({ expect: { ...plan.expect, value } })} />}
        <Field label="等待数据变化（秒）"><InputNumber min={1} max={30} value={plan.timeoutMillis / 1000} onChange={v => update({ timeoutMillis: (v || 5) * 1000 })} /></Field>
        <Button disabled={busy || !connection || loading} onClick={() => void validate(false)}>检查配置并预览查询</Button>
        {preview && <pre className="bt-verification-preview">{preview}</pre>}
        <p className="bt-muted">保存后会随当前测试点确认。执行时从所选环境读取连接凭据，检查结果会出现在运行报告中。</p>
      </section>}
    </fieldset></ConfigProvider>
    {error && <Alert type="error" showIcon message={error} />}
  </Drawer>;
}

export function VerificationCard({ assertion, onEdit, onRemove }: { assertion: Assertion; onEdit: () => void; onRemove: () => void }) {
  const p = assertion.expected as VerificationPlan;
  return <div className="bt-param bt-verification-card"><div className="bt-row-title"><Tag color="purple">{p.kind}</Tag><Button type="text" danger icon={<DeleteOutlined />} aria-label={`删除数据验证 ${p.description}`} onClick={onRemove} /></div>
    <strong>{p.description}</strong><p className="bt-muted">{p.connection.modulePath || "项目默认"} · {p.sql?.table || (p.redis ? `${p.redis.command} ${p.redis.keyPrefix}<key>` : p.kafka?.topic)}</p>
    <p><code>{p.expect.path}</code> · {p.expect.operator === "EXISTS" ? "存在" : `${p.expect.operator === "EQ" ? "=" : p.expect.operator === "GT" ? ">" : ">="} ${p.expect.value?.source === "context" ? `流程数据 ${String(p.expect.value.value)}` : JSON.stringify(p.expect.value?.value)}`} · 等待 {p.timeoutMillis / 1000} 秒</p>
    <Button onClick={onEdit}>查看或编辑验证</Button></div>;
}
