import { useEffect, useRef, useState } from "react";
import { Alert, App, Button, Checkbox, Input, InputNumber, Select, Space, Spin, Tag } from "antd";
import { DatabaseOutlined, DeleteOutlined, PlusOutlined, SaveOutlined } from "@ant-design/icons";
import { projectContextApi as api, type ContextScope, type ContextDefinition, type ResolvedContext, type CredentialUpdate } from "../../api/projectContext";
import { environmentLabel, environmentOptions, presetEnvironments } from "./environmentModel";
import ProjectScopeFields from "./ProjectScopeFields";
import ProbeEnvironmentPanel from "./ProbeEnvironmentPanel";

import { EmptyState, Field, ValueInput } from "./FormControls";
import { errorText, typeOptions, defaultValue } from "./workbenchModel";

const kinds = [{ value: "mysql", label: "MySQL" }, { value: "postgresql", label: "PostgreSQL" }, { value: "mongodb", label: "MongoDB" },
  { value: "redis", label: "Redis" }, { value: "kafka", label: "Kafka" }, { value: "rabbitmq", label: "RabbitMQ" },
  { value: "elasticsearch", label: "Elasticsearch" }, { value: "other", label: "其他" }];
export default function ProjectContextPanel({ navigationScope, navigationEnvironment, onScopeChange, onEnvironmentChange, active, onGenerate, onAnalyze }: {
  navigationScope: ContextScope; navigationEnvironment: string; active: boolean;
  onScopeChange: (scope: ContextScope) => void; onEnvironmentChange: (environment: string) => void;
  onGenerate: (scope: ContextScope, environment: string) => void;
  onAnalyze: (scope: ContextScope, environment: string) => void;
}) {
  const { message, modal } = App.useApp();
  const [globalMode,setGlobalMode] = useState(false);
  const [scope, setLocalScope] = useState(navigationScope);
  const [environment, setLocalEnvironment] = useState(navigationEnvironment);
  const setScope = (next: ContextScope) => {
    const changed = scope.repositoryKey !== next.repositoryKey || scope.modulePaths?.[0] !== next.modulePaths?.[0];
    const selection = changed ? { ...next, commitId: "" } : next;
    setLocalScope(selection); onScopeChange(selection);
  };
  const setEnvironment = (next: string) => { setLocalEnvironment(next); onEnvironmentChange(next); };
  const [environments, setEnvironments] = useState(presetEnvironments);
  const [newEnvironment, setNewEnvironment] = useState("");
  const [data, setData] = useState<ResolvedContext | null>(null);
  const [definition, setDefinition] = useState<ContextDefinition | null>(null);
  const [credentials, setCredentials] = useState<Record<string, CredentialUpdate>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const modulePath = scope.modulePaths?.[0] || "";
  const apply = (result: ResolvedContext) => { setData(result); setDefinition(result.own.definition); setCredentials({}); setDirty(false); };
  useEffect(() => {
    let alive = true; setData(null); setDefinition(null); setCredentials({}); setDirty(false); setError(""); setLoading(false);
    if (globalMode || scope.repositoryKey) {
      setLoading(true);
      void (globalMode ? api.globalContext(environment) : api.get(scope.repositoryKey, modulePath, environment)).then(r => { if (alive) apply(r); })
        .catch(e => { if (alive) setError(errorText(e)); }).finally(() => { if (alive) setLoading(false); });
      void (globalMode ? Promise.resolve(presetEnvironments) : api.environments(scope.repositoryKey)).then(names => { if (alive) setEnvironments([...new Set([...names, environment])]); })
        .catch(() => { if (alive) setEnvironments([environment]); });
    }
    return () => { alive = false; };
  }, [scope.repositoryKey, modulePath, environment, reload,globalMode]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
  const change = (action: () => void) => dirty ? modal.confirm({ title: "切换配置范围？", content: "当前环境变量尚未保存，切换会丢弃本次修改。", okText: "切换", cancelText: "继续编辑", onOk: action }) : action();
  const pendingScope = useRef("");
  const navigationKey = JSON.stringify([navigationScope, navigationEnvironment]);
  useEffect(() => {
    if (!active) return;
    const sameConfiguration = scope.repositoryKey === navigationScope.repositoryKey &&
      scope.modulePaths?.[0] === navigationScope.modulePaths?.[0] && environment === navigationEnvironment;
    if (sameConfiguration) {
      pendingScope.current = "";
      if (scope.branch !== navigationScope.branch || scope.commitId !== navigationScope.commitId) setLocalScope(navigationScope);
      return;
    }
    if (pendingScope.current === navigationKey) return;
    pendingScope.current = navigationKey;
    const applyNavigation = () => { setLocalScope(navigationScope); setLocalEnvironment(navigationEnvironment); };
    if (dirty) modal.confirm({ title: "切换配置范围？", content: "当前环境变量尚未保存，切换会丢弃本次修改。", okText: "切换", cancelText: "继续编辑",
      onOk: applyNavigation, onCancel: () => { onScopeChange(scope); onEnvironmentChange(environment); } });
    else applyNavigation();
  }, [active, navigationKey, dirty]);
  const patch = (value: Partial<ContextDefinition>) => { setDefinition(d => d ? { ...d, ...value } : d); setDirty(true); };
  const connection = (id: string, value: Partial<ContextDefinition["connections"][number]>) =>
    patch({ connections: definition!.connections.map(c => c.id === id ? { ...c, ...value } : c) });
  const secret = (id: string, value: CredentialUpdate) => { setCredentials(c => ({ ...c, [id]: { ...c[id], ...value } })); setDirty(true); };
  const save = async () => {
    if (!definition || !data) return;
    setSaving(true); setError("");
    try { apply(await (globalMode ? api.saveGlobalContext({environment,version:data.own.version,definition,credentials}) : api.save({ ...data.own, definition, credentials }))); message.success("环境变量已保存"); }
    catch (e) { setError(errorText(e)); } finally { setSaving(false); }
  };
  return <section className="bt-panel">
    <div className="bt-page-actions">
      <Button onClick={() => change(() => onGenerate(scope, environment))}>编排业务路径</Button></div>
    <Field label="配置层级"><Select aria-label="配置层级" value={globalMode?"global":"project"} disabled={saving}
      options={[{value:"project",label:"项目 / 模块"},{value:"global",label:"工作空间全局"}]}
      onChange={value=>change(()=>setGlobalMode(value==="global"))} /></Field>
    {!globalMode && <ProjectScopeFields value={scope} disabled={saving} onChange={next => change(() => setScope(next))} />}
    {globalMode && <Alert type="info" message="当前环境的工作空间全局配置" description="项目和模块可以覆盖同名变量，类型必须一致。各环境独立保存，不跨环境继承。" />}
    <div className="bt-context-environment">
      <Field label="环境"><Select aria-label="环境" disabled={saving || (!globalMode && !scope.repositoryKey)} value={environment} options={environmentOptions([...environments, environment])}
        onChange={value => change(() => setEnvironment(value))} /></Field>
      <Field label="自定义环境" hint="按需添加，例如 qa 或 dev"><Input disabled={saving} value={newEnvironment} placeholder="环境标识"
        onChange={e => setNewEnvironment(e.target.value)} /></Field>
      <Button disabled={(!globalMode && !scope.repositoryKey) || saving || !newEnvironment.trim()} onClick={() => {
        if (!/^[A-Za-z0-9_-]{1,64}$/.test(newEnvironment.trim())) { message.error("环境标识仅支持字母、数字、横线或下划线"); return; }
        change(() => { setEnvironment(newEnvironment.trim()); setNewEnvironment(""); });
      }}>添加环境</Button>
    </div>
    <ProbeEnvironmentPanel environment={environment} />
    <div className="bt-analysis-entry"><div><strong>仓库背景知识</strong><p>分析源码、查看 LLM 计划与执行进度，统一归纳跨模块背景。服务地址和连接凭据在本页按环境维护。</p></div>
      <Button disabled={!scope.repositoryKey} onClick={() => onAnalyze(scope, environment)}>打开背景知识（新标签页）</Button></div>
    {loading && <Spin />}
    {!globalMode && !scope.repositoryKey && <EmptyState icon={<DatabaseOutlined />} title="先选择项目" description="项目分析完成后，即可在这里配置各环境的服务地址、数据库和中间件。" />}
    {error && <Alert type="error" showIcon message={error} action={!definition ? <Button onClick={() => setReload(v => v + 1)}>重试</Button> : undefined} />}
    {data && definition && <div className="bt-context-body">
      <div className="bt-inline"><Tag color="blue">{globalMode ? "工作空间全局" : modulePath ? "模块配置" : "项目默认配置"}</Tag><Tag>{environmentLabel(environment)}</Tag>
        {dirty ? <Tag color="orange">未保存</Tag> : data.own.updatedAt ? <span className="bt-muted">已保存 · {new Date(data.own.updatedAt).toLocaleString()}</span> : <span className="bt-muted">尚未配置</span>}</div>
      <details className="bt-advanced"><summary>补充说明与使用约束（可选）</summary>
      <Field label="环境说明" hint="记录此环境的用途与差异，已有说明继续保留。文本会提供给 LLM，连接凭据请填入独立字段。">
        <Input.TextArea rows={3} maxLength={12000} value={definition.description} disabled={saving}
          placeholder="例如：预发布环境用于上线前验收，使用独立测试数据" onChange={e => patch({ description: e.target.value })} /></Field>
      <Field label="环境使用约束"><Input.TextArea rows={3} maxLength={12000} value={definition.businessRules} disabled={saving}
        placeholder="例如：修改资料前必须登录；验证码只能使用一次；测试账号不得触发真实通知" onChange={e => patch({ businessRules: e.target.value })} /></Field>
      </details>
      <Field label="服务 URL" hint={data.inheritedBaseUrl ? `当前继承：${data.effective.baseUrl}` : modulePath ? "留空时继承当前环境的项目服务地址" : "运行测试时可以直接复用此地址"}>
        <Input disabled={saving} value={definition.baseUrl} placeholder="https://test.example.com" onChange={e => patch({ baseUrl: e.target.value })} /></Field>
      {modulePath && <details className="bt-advanced"><summary>查看当前生效的补充说明</summary>
        <p style={{ whiteSpace: "pre-wrap" }}>{data.effective.description || "暂无环境说明"}</p><p style={{ whiteSpace: "pre-wrap" }}>{data.effective.businessRules || "暂无使用约束"}</p>
        <small>显示最近保存的配置；保存后更新。</small></details>}
      <div className="bt-section-heading"><div><h3>自定义变量</h3><p>保存可复用的普通测试数据。在流程设置中选择这些 key，再在步骤参数中引用预置上下文；运行前按环境加载实际值。</p></div></div>
      {!globalMode && (data.effective.variables || []).filter(v => !(definition.variables || []).some(own => own.key === v.key)).map(v =>
        <div className="bt-context-inherited" key={v.key}><div><strong>{v.key}</strong> <Tag>{v.type}</Tag><Tag color="blue">{data.variableOrigins?.[v.key] || "继承项目"}</Tag></div>
          <Button disabled={saving} onClick={() => patch({ variables: [...(definition.variables || []), { ...v }] })}>覆盖此变量</Button></div>)}
      {(definition.variables || []).map((v, i) => <div className="bt-param" key={i}>
        <div className="bt-form-grid">
          <Field label={`变量 ${i + 1} key`}><Input disabled={saving} value={v.key} maxLength={120} placeholder="例如 tenantId、device.model" onChange={e => patch({ variables: definition.variables!.map((item, j) => j === i ? { ...item, key: e.target.value } : item) })} /></Field>
          <Field label={`变量 ${i + 1} 类型`}><Select disabled={saving} value={v.type} options={typeOptions} onChange={type => patch({variables:definition.variables!.map((item,j)=>j===i?{...item,type,value:defaultValue(type)}:item)})} /></Field>
        </div>
        <fieldset disabled={saving} style={{border:0,padding:0,margin:"12px 0"}}><ValueInput label={`变量 ${i + 1} value`} type={v.type} value={v.value} onChange={value => patch({variables:definition.variables!.map((item,j)=>j===i?{...item,value:value as typeof v.value}:item)})} /></fieldset>
        <Button danger type="text" disabled={saving} icon={<DeleteOutlined />} onClick={() => patch({variables:definition.variables!.filter((_,j)=>j!==i)})}>删除变量</Button>
      </div>)}
      <Button icon={<PlusOutlined />} disabled={saving || (definition.variables?.length || 0) >= 100} onClick={() => patch({variables:[...(definition.variables || []),{key:"",type:"string",value:""}]})}>添加变量</Button>
      <p className="bt-muted">同环境按模块、项目、工作空间全局顺序解析，同名覆盖须保持类型一致。用户名、密码等连接凭据使用下方独立字段；自定义变量值不会自动发送给模型。</p>
      <div className="bt-section-heading"><div><h3>数据库与中间件</h3><p>记录部署位置和用途。用户名、密码加密保存，留空保留已有凭据；凭据不会提供给 LLM。</p></div></div>
      {!data.encryptionReady && <Alert type="warning" showIcon message="连接凭据暂不可保存" description="服务尚未配置 OCTOPUS_ENCRYPTION_KEY；环境说明和无凭据连接仍可保存。" />}
      {modulePath && data.effective.connections.filter(c => !definition.connections.some(own => own.id === c.id)).map(c =>
        <div className="bt-context-inherited" key={c.id}><div><strong>{c.name || "未命名连接"}</strong> <Tag>{c.kind}</Tag><Tag color="blue">继承项目</Tag><p>{c.host}{c.port ? `:${c.port}` : ""} {c.database}</p></div>
          <Button disabled={saving} onClick={() => patch({ connections: [...definition.connections, { ...c }] })}>在模块中覆盖</Button></div>)}
      {definition.connections.map((c, i) => <section className="bt-context-connection" key={c.id}>
        <div className="bt-section-heading"><strong>连接 {i + 1} · {c.name || "未命名连接"}</strong><Button danger type="text" disabled={saving} icon={<DeleteOutlined />} onClick={() => {
          patch({ connections: definition.connections.filter(v => v.id !== c.id) }); setCredentials(current => Object.fromEntries(Object.entries(current).filter(([key]) => key !== c.id)));
        }}>删除连接</Button></div>
        <div className="bt-form-grid">
          <Field label={`连接 ${i + 1} 名称`}><Input disabled={saving} value={c.name} placeholder="例如：账号数据库" onChange={e => connection(c.id, { name: e.target.value })} /></Field>
          <Field label={`连接 ${i + 1} 类型`}><Select disabled={saving} value={c.kind} options={kinds} onChange={kind => connection(c.id, { kind, security: "plain" })} /></Field>
          <Field label={`连接 ${i + 1} 主机`}><Input disabled={saving} value={c.host} placeholder="主机名或 IP" onChange={e => connection(c.id, { host: e.target.value })} /></Field>
          <Field label={`连接 ${i + 1} 端口`}><InputNumber disabled={saving} min={1} max={65535} value={c.port} onChange={port => connection(c.id, { port })} /></Field>
          <Field label={`连接 ${i + 1} 数据库或实例`}><Input disabled={saving} value={c.database} placeholder="数据库名、Redis DB 或集群实例" onChange={e => connection(c.id, { database: e.target.value })} /></Field>
          <Field label={`连接 ${i + 1} 用户名`} hint={data.own.credentials[c.id]?.hasUsername ? "已保存，留空保留" : "不会回显已保存值"}>
            <Input.Password autoComplete="off" disabled={saving || !data.encryptionReady} value={credentials[c.id]?.username || ""}
              placeholder={data.own.credentials[c.id]?.hasUsername ? "已保存 · 输入新值可替换" : "连接用户名"} onChange={e => secret(c.id, { username: e.target.value || undefined, clear: false })} /></Field>
          <Field label={`连接 ${i + 1} 密码`} hint={data.own.credentials[c.id]?.hasPassword ? "已保存，留空保留" : "加密保存，模型不可见"}>
            <Input.Password autoComplete="new-password" disabled={saving || !data.encryptionReady} value={credentials[c.id]?.password || ""}
              placeholder={data.own.credentials[c.id]?.hasPassword ? "已保存 · 输入新值可替换" : "连接密码"} onChange={e => secret(c.id, { password: e.target.value || undefined, clear: false })} /></Field>
        </div>
        {(data.own.credentials[c.id]?.hasPassword || data.own.credentials[c.id]?.hasUsername) && <Checkbox disabled={saving} checked={credentials[c.id]?.clear || false}
          onChange={e => secret(c.id, { clear: e.target.checked, username: undefined, password: undefined })}>保存时清除该连接已有凭据</Checkbox>}
        <Field label={`连接 ${i + 1} 传输与认证`}><Select disabled={saving} value={c.security || "plain"}
          options={[{ value: "plain", label: "普通连接" }, { value: "tls", label: "TLS · 校验证书与主机" }, ...(c.kind === "kafka" ? [
            { value: "sasl_ssl_plain", label: "SASL/PLAIN + TLS" }, { value: "sasl_ssl_scram256", label: "SASL/SCRAM-SHA-256 + TLS" }, { value: "sasl_ssl_scram512", label: "SASL/SCRAM-SHA-512 + TLS" }] : [])]}
          onChange={security => connection(c.id, { security })} /></Field>
        <Field label={`连接 ${i + 1} 用途与数据结构`} hint="用于 AI 生成验证。写明表与字段、Redis key 格式或 Kafka topic 与消息字段，凭据填入上方独立字段。"><Input.TextArea rows={4} disabled={saving} value={c.notes}
          placeholder="例如：orders(id varchar, status varchar)；支付后 status=PAID。Redis key 为 order:{order.id}，值包含 status。" onChange={e => connection(c.id, { notes: e.target.value })} /></Field>
      </section>)}
      <Button icon={<PlusOutlined />} disabled={saving || definition.connections.length >= 30} onClick={() => patch({ connections: [...definition.connections,
        { id: `conn_${crypto.randomUUID().replaceAll("-", "")}`, name: "", kind: "mysql", host: "", port: 3306, database: "", notes: "" }] })}>添加数据库或中间件</Button>
      <div className="bt-context-save"><Space><Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存环境变量</Button>
        <Button disabled={!dirty || saving} onClick={() => change(() => apply(data))}>放弃更改</Button></Space></div>
    </div>}
  </section>;
}
