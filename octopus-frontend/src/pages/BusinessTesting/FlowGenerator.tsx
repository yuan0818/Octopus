import { environmentOptions } from "./environmentModel";
import { RepositoryKnowledge } from "./RepositoryAnalysisPanel";
import { useEffect, useRef, useState } from "react";
import { Alert, Button, Checkbox, Collapse, Drawer, Input, Select, Space, Tag, Timeline } from "antd";
import { LockOutlined, PlusOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { projectContextApi as api, type StepSuggestion, type PreparedStep, type StepMemoryView } from "../../api/projectContext";
import { queryApi } from "../../api/query";
import type { Flow, Operation } from "../../api/businessTesting";
import { Field, ValueInput } from "./FormControls";
import { defaultValue, errorText, parseFlowDraft, refOf, typeOptions } from "./workbenchModel";
import { acceptPreparedContracts, orderedFillNodes, snapshot, defaultFillInputs, fillChanges, schemaFields, sensitiveContextKey } from "./flowFillModel";

export interface FillAcceptance {
  original: Flow; prepared: Flow; suggestion: StepSuggestion; environment: string; contextValues: Record<string, unknown>;
}
export default function FlowGenerator({ open, flow, operations, initialEnvironment = "test", initialValues = {}, onClose, onAccept }: {
  open: boolean; flow: Flow | null; operations: Operation[]; initialEnvironment?: string; initialValues?: Record<string, unknown>;
  onClose: () => void; onAccept: (result: FillAcceptance) => Promise<void>;
}) {
  const [original, setOriginal] = useState<Flow | null>(null);
  const [prepared, setPrepared] = useState<Flow | null>(null);
  const [contractOperations, setContractOperations] = useState<Operation[]>([]);
  const [contractWarnings, setContractWarnings] = useState<string[]>([]);
  const [contractLoading, setContractLoading] = useState(false);
  const [contractReady, setContractReady] = useState(false);
  const [prepareAttempt, setPrepareAttempt] = useState(0);
  const [editableInputs, setEditableInputs] = useState<Record<string, string[]>>({});
  const [contextValues, setContextValues] = useState<Record<string, unknown>>({});
  const [environment, setEnvironment] = useState("test");
  const [environments, setEnvironments] = useState(["test"]);
  const [goal, setGoal] = useState("");
  const [newKey, setNewKey] = useState("");
  const [newType, setNewType] = useState("string");
  const [model, setModel] = useState("");
  const [models, setModels] = useState<{ name: string; modelId: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [modelLoading, setModelLoading] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<StepSuggestion | null>(null);
  const [activeId, setActiveId] = useState("");
  const [details, setDetails] = useState<PreparedStep | null>(null);
  const [completed, setCompleted] = useState(false);
  const ordered = (() => { try { return flow ? orderedFillNodes(flow) : []; } catch { return flow?.nodes || []; } })();
  const activeIndex = ordered.findIndex(n => n.id === activeId);
  const active = prepared?.nodes.find(n => n.id === activeId);
  const operation = contractOperations.find(o => o.operationId + "@" + o.revision === active?.operationRef);
  const upstreamMemory = details?.memories.filter(m => details.upstreamNodeIds.includes(m.nodeId)) || [];
  const requestId = useRef(0);
  const resultElement = useRef<HTMLElement>(null);
  useEffect(() => { if (result) resultElement.current?.scrollIntoView({ behavior: "smooth", block: "start" }); }, [result]);
  // Seed only on opening; preserve the goal and preset data when advancing to the next node.
  useEffect(() => {
    if (!open || !flow) return;
    setActiveId(ordered[0]?.id || ""); setGoal(flow.name); setEnvironment(initialEnvironment); setCompleted(false);
    setContextValues(Object.fromEntries(Object.entries(initialValues).filter(([k]) => flow.initialContext[k] && !flow.initialContext[k].sensitive && !sensitiveContextKey(k))));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  useEffect(() => {
    const id = ++requestId.current; setResult(null); setBusy(false); setError(""); setContractReady(false); setDetails(null);
    if (!open || !flow || !activeId) return;
    setOriginal(structuredClone(flow)); setPrepared(structuredClone(flow)); setContractLoading(true); setContractWarnings([]);
    setContractOperations(operations.filter(o => flow.nodes.some(n => n.operationRef === refOf(o))));
    void api.prepareStep({ flow, nodeId: activeId, environment }).then(response => {
      if (requestId.current !== id) return;
      const enriched = acceptPreparedContracts(flow, response.flow, operations, response.operations);
      if (response.nodeId !== activeId || enriched.nodes.some((n, i) => n.id !== activeId && snapshot(n) !== snapshot(flow.nodes[i]))) throw new Error("契约读取修改了其他步骤");
      setPrepared(enriched); setContractOperations(response.operations); setContractWarnings(response.warnings); setDetails(response);
      setEditableInputs(defaultFillInputs(enriched, response.operations)); setContractReady(true);
    }).catch(e => { if (requestId.current === id) setError(errorText(e)); })
      .finally(() => { if (requestId.current === id) setContractLoading(false); });
    // Asset refreshes must not discard the active draft or its pending suggestion.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, flow, activeId, environment, prepareAttempt]);
  useEffect(() => {
    if (!open) return; let alive = true; setModelLoading(true);
    void api.models().then(value => { if (alive) setModels(value); }).catch(e => { if (alive) setError(errorText(e)); })
      .finally(() => { if (alive) setModelLoading(false); });
    return () => { alive = false; };
  }, [open]);
  useEffect(() => {
    if (!open || !prepared) return;
    let alive = true;
    const ids = new Set(contractOperations.filter(o => prepared.nodes.some(n => n.operationRef === refOf(o))).flatMap(o => o.implementations.map(i => i.projectId)));
    setEnvironments([...new Set(["test", initialEnvironment])]);
    if (ids.size) void queryApi.repositoryScopes().then(async response => {
      const scopes = response.data.filter(s => s.modules.some(m => ids.has(m.projectId)));
      const names = await Promise.all(scopes.map(s => api.environments(s.key)));
      if (alive) setEnvironments([...new Set(["test", initialEnvironment, ...names.flat()])]);
    }).catch(e => { if (alive) setError(errorText(e)); });
    return () => { alive = false; };
  }, [open, prepared?.flowId, contractOperations, initialEnvironment]);
  const invalidate = () => { setResult(null); setError(""); };
  const generate = async () => {
    if (!prepared || !contractReady) return;
    const id = ++requestId.current;
    setBusy(true); setError(""); setResult(null);
    try {
      const response = await api.suggestStep({ flow: prepared, nodeId: activeId, environment, goal, model, contextValues, editableInputs: editableInputs[activeId] || [] });
      parseFlowDraft(JSON.stringify(response.flow));
      if (response.nodeId !== activeId) throw new Error("模型返回了其他步骤");
      if (requestId.current === id) setResult(response);
    } catch (e) { if (requestId.current === id) setError(errorText(e)); }
    finally { if (requestId.current === id) setBusy(false); }
  };
  const changes = result && original ? fillChanges(original, result.flow).filter(n => n.id === activeId) : [];
  const count = changes.reduce((sum, n) => sum + n.changes.length, 0);
  const apply = async () => {
    if (!original || !prepared || !result) return;
    setBusy(true); setError("");
    try {
      await onAccept({ original, prepared, suggestion: result, environment, contextValues });
      setResult(null);
      if (activeIndex < ordered.length - 1) setActiveId(ordered[activeIndex + 1].id);
      else setCompleted(true);
    } catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  };
  return <Drawer open={open} onClose={onClose} closable={!busy} maskClosable={!busy} keyboard={!busy} width={840}
    title="逐步 AI 补全" footer={<div className="bt-dialog-actions">
      <Button disabled={busy} onClick={onClose}>返回编排</Button>
      {result ? <Button type="primary" loading={busy} onClick={() => void apply()}>{activeIndex < ordered.length - 1 ? "应用并继续下一步" : "应用并保存步骤记忆"}</Button>
        : <Button type="primary" icon={<ThunderboltOutlined />} loading={busy} disabled={!goal.trim() || !active || !models.length || modelLoading || contractLoading || !contractReady}
          onClick={() => { setCompleted(false); void generate(); }}>补全当前步骤</Button>}
    </div>}>
    <p className="bt-lead">沿着你规划的路径，一次只补全一个节点。确认参数与提取项后保存步骤记忆，后续节点会参考依赖链上仍有效的记忆。</p>
    {prepared && <>
      <div className="bt-section-heading"><h3>当前第 {activeIndex + 1} / {ordered.length} 步</h3><Tag icon={<LockOutlined />}>接口与依赖已固定</Tag></div>
      <Space wrap style={{ marginBottom: 20 }}>
        {ordered.map((n, i) => { const memory = details?.memories.find(m => m.nodeId === n.id);
          return <Button key={n.id} disabled={busy} type={n.id === activeId ? "primary" : "default"} onClick={() => { setCompleted(false); setActiveId(n.id); }}>
            {i + 1}. {n.label} {memory?.state === "CONFIRMED" ? " · 已记忆" : memory?.state === "STALE" ? " · 记忆过期" : ""}
          </Button>;
        })}
      </Space>
      {completed && <Alert type="success" showIcon message="当前步骤已应用并保存记忆" description="可以返回编排检查并保存流程，或选择任意节点继续调整。" />}
      <div className="bt-param"><strong>{operation?.method} {operation?.path || active?.label}</strong>
        <p className="bt-muted">{operation?.system} / {operation?.service}</p>
        {operation?.implementations.map((impl, i) => <Tag key={i}>{impl.branch} · {impl.commitId.slice(0, 8)}</Tag>)}
        {contractReady && operation && <Tag color="blue">入参 {Object.keys(operation.inputs).length} 项 · 响应 {Object.values(operation.responseSchema || {}).flatMap(s => schemaFields(s)).length} 个字段</Tag>}
      </div>
      {contractLoading && <Alert type="info" showIcon message="正在读取当前节点的契约、源码、背景及有效记忆" />}
      {!contractReady && !contractLoading && <Button onClick={() => setPrepareAttempt(n => n + 1)}>重新读取节点上下文</Button>}
      <Collapse style={{ marginBottom: 16 }} items={[
        { key: "evidence", label: `本次源码与背景 · ${details?.sources.length || 0} 段源码 · ${details?.background.length || 0} 组背景`, children: <>
          <p className="bt-muted">固定到所选提交，包含接口实现及相关 DTO；源码已隐藏字符串常量和注释。网关、动态配置与完整跨服务调用仍可能缺失，以实际列出的证据为准。</p>
          {details?.background.map((b, i) => <div className="bt-param" key={i}><strong>{String(b.project || "项目")} · {String(b.modulePath || "项目根目录")}</strong>
            <BackgroundDetails value={b} /></div>)}
          {!details?.background.length && <p className="bt-muted">当前接口未关联项目背景，可在项目与模块配置中补充。</p>}
          <Collapse items={details?.sources.map(source => ({ key: source.id, label: `${source.filePath.split("/").pop()}:${source.lineStart}–${source.lineEnd} · ${source.kind}`, children: <>
            <p className="bt-muted" style={{ overflowWrap: "anywhere" }}>{source.filePath} · {source.commitId.slice(0, 8)}</p>
            <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontSize: 12 }}>{source.text}</pre>{source.truncated && <Tag>片段已截断</Tag>}
          </> }))} />
        </> },
        { key: "memories", label: `上游步骤记忆 · ${upstreamMemory.filter(m => m.state === "CONFIRMED").length} 条可用`, children: <>
          <p className="bt-muted">只带入当前节点的上游记忆。接口版本、参数、依赖、环境或背景改变后，相关记忆失效，需要重新补全确认。</p>
          {upstreamMemory.length ? upstreamMemory.map(m => <MemoryCard key={m.id} value={m} label={ordered.find(n => n.id === m.nodeId)?.label} />) : <p>暂无可用上游记忆；现有上游输出声明仍会提供给模型。</p>}
        </> },
        ...(details?.memories.filter(m => m.nodeId === activeId).map(m => ({ key: m.id, label: "当前节点已有记忆", children: <MemoryCard value={m} /> })) || []),
      ]} />
      <fieldset disabled={busy || !contractReady} style={{ border: 0, padding: 0, margin: 0 }}>
        <div className="bt-form-grid">
          <Field label="参考环境" hint="自动读取已选 API 所属项目/模块的背景"><Select value={environment} disabled={busy || !contractReady} options={environmentOptions([...environments, environment])}
            onChange={value => { setEnvironment(value); setContextValues({}); invalidate(); }} /></Field>
          <Field label="补全模型"><Select disabled={busy || !contractReady} loading={modelLoading} value={model}
            options={[{ value: "", label: "自动选择已配置模型" }, ...models.map(m => ({ value: m.name, label: m.name }))]}
            onChange={value => { setModel(value); invalidate(); }} /></Field>
        </div>
        <Field label="这条路径要验证什么" hint="说明测试数据的业务含义和预期结果；缺失步骤会提示你调整路径。">
          <Input.TextArea disabled={busy || !contractReady} rows={3} maxLength={12000} value={goal}
            placeholder="例如：用预置的测试用户创建订单，将订单 ID 传给查询步骤，确认订单处于待支付状态。"
            onChange={e => { setGoal(e.target.value); invalidate(); }} /></Field>
        <Collapse items={[
          { key: "contracts", label: "当前节点的入参和响应契约", children: <>
            <p className="bt-muted">模型将收到下面的参数位置、类型和响应字段。代码版本保持固定，读取到的契约随补全结果一起确认应用。</p>
            {contractOperations.filter(o => refOf(o) === active?.operationRef).map(operation => <div className="bt-param" key={refOf(operation)}><strong>{operation.method} {operation.path}</strong>
              <h4>入参</h4><ul>{Object.entries(operation.inputs).map(([key, port]) => <li key={key}>{key} · {port.type} · {port.required ? "必填" : "选填"}</li>)}</ul>
              {schemaFields(operation.requestSchema).length > 0 && <ul>{schemaFields(operation.requestSchema).map(field => <li key={field.path}>body.{field.path} · {field.type}</li>)}</ul>}
              <h4>响应</h4>{Object.entries(operation.responseSchema || {}).map(([status, schema]) => <div key={status}><Tag>HTTP {status}</Tag>
                <ul>{schemaFields(schema, "$.body").map(field => <li key={field.path}>{field.path} · {field.type}</li>)}</ul></div>)}
            </div>)}
            {!!contractWarnings.length && <Alert type="warning" showIcon message="已读取现有契约，以下内容仍需确认" description={<ul>{contractWarnings.map((warning, i) => <li key={i}>{warning}</li>)}</ul>} />}
          </> },
          { key: "context", label: `预置上下文 · ${Object.keys(contextValues).length} 项参考数据`, children: <>
            <p className="bt-muted">普通测试数据供模型建立绑定，变量声明随草稿保存。具体值只在当前页面保留，并带入相同环境的运行配置；刷新后需重新填写。敏感变量仅传名称和类型。</p>
            {Object.entries(prepared.initialContext).map(([key, variable]) => <div className="bt-param" key={key}>
              <Space><strong>{key}</strong><Tag>{variable.type}</Tag>
                {variable.sensitive || sensitiveContextKey(key) ? <Tag>运行时提供敏感值</Tag> : <Checkbox disabled={busy || !contractReady} checked={key in contextValues}
                  onChange={e => { setContextValues(current => e.target.checked ? { ...current, [key]: defaultValue(variable.type) } : Object.fromEntries(Object.entries(current).filter(([k]) => k !== key))); invalidate(); }}>提供预置值</Checkbox>}
              </Space>
              {key in contextValues && <ValueInput value={contextValues[key]} type={variable.type} label={`${key} 预置值`}
                onChange={value => { setContextValues(v => ({ ...v, [key]: value })); invalidate(); }} />}
            </div>)}
            <div className="bt-inline"><Input aria-label="预置变量名称" disabled={busy || !contractReady} value={newKey} placeholder="例如 test.userId" onChange={e => setNewKey(e.target.value)} />
              <Select aria-label="预置变量类型" disabled={busy || !contractReady} value={newType} options={typeOptions} onChange={setNewType} style={{ minWidth: 100 }} />
              <Button disabled={busy || !contractReady || !/^[A-Za-z_][A-Za-z0-9_.-]{0,119}$/.test(newKey) || sensitiveContextKey(newKey) || !!prepared.initialContext[newKey] || prepared.nodes.some(n => n.outputs.some(o => o.key === newKey))}
                icon={<PlusOutlined />} onClick={() => { setPrepared({ ...prepared, initialContext: { ...prepared.initialContext, [newKey]: { type: newType, sensitive: false } } });
                  setContextValues(v => ({ ...v, [newKey]: defaultValue(newType) })); setNewKey(""); invalidate(); }}>添加变量</Button></div>
          </> },
          { key: "inputs", label: "允许补全的参数 · 默认保留已填内容", children: <>
            <p className="bt-muted">未填写的参数默认勾选。勾选已填参数表示允许 AI 提出替换建议，应用前会显示变化。现有提取项、断言及测试点覆盖值会保留。</p>
            {prepared.nodes.filter(n => n.id === activeId).map(node => <div className="bt-param" key={node.id}><strong>{node.label}</strong>
              {Object.keys(contractOperations.find(o => refOf(o) === node.operationRef)?.inputs || {}).length ? <Checkbox.Group disabled={busy || !contractReady} value={editableInputs[node.id] || []}
                options={Object.keys(contractOperations.find(o => refOf(o) === node.operationRef)?.inputs || {}).map(key => ({ value: key, label: `${key}${node.inputs[key] ? "（已填，允许重填）" : "（待填）"}` }))}
                onChange={values => { setEditableInputs(v => ({ ...v, [node.id]: values })); invalidate(); }} /> : <p className="bt-muted">无已知输入参数</p>}
            </div>)}
          </> },
        ]} />
      </fieldset>
    </>}
    {!modelLoading && !models.length && <Alert type="info" showIcon message="尚无可用模型，可继续手工配置步骤" description="在 Agent 配置中启用模型后即可使用补全。" />}
    {!!contractWarnings.length && <p className="bt-muted">契约有 {contractWarnings.length} 项待确认信息，可展开「入参和响应契约」查看。</p>}
    {busy && <Alert type="info" showIcon message="正在处理当前节点，请稍候" description="模型生成可能需要几分钟。" />}
    {error && <Alert type="error" showIcon message="暂时无法补全" description={error} />}
    {result && <section ref={resultElement} className="bt-suggestion-result">
      <div className="bt-section-heading"><div><h2>{count ? `${count} 项补全建议` : "参数保持不变，可保存步骤记忆"}</h2><Tag color="blue">待确认 · {result.model}</Tag></div>
        <Button disabled={busy || !contractReady} onClick={() => void generate()}>重新补全</Button></div>
      <Timeline items={changes.map(node => ({ children: <div><strong>{activeIndex + 1}. {node.label}</strong>
        {node.changes.length ? <ul>{node.changes.map((change, i) => <li key={i}>{change}</li>)}</ul> : <p className="bt-muted">保留现有配置</p>}</div> }))} />
      <h3>本次生成的步骤记忆</h3><MemoryCard value={result.memory} />
      {[{ title: "需要确认的假设", values: result.assumptions }, { title: "待补充信息", values: result.questions }, { title: "契约与背景缺口", values: result.warnings }]
        .filter(group => group.values.length).map(group => <div key={group.title}><h3>{group.title}</h3><ul>{group.values.map((value, i) => <li key={i}>{value}</li>)}</ul></div>)}
    </section>}
  </Drawer>;
}

function MemoryCard({ value, label }: { value: StepMemoryView; label?: string }) {
  const memory = value.memory;
  return <div className="bt-param">
    <Space><strong>{label || "步骤记忆"}</strong><Tag color={value.state === "CONFIRMED" ? "green" : value.state === "STALE" ? "orange" : "blue"}>
      {value.state === "CONFIRMED" ? "已确认" : value.state === "STALE" ? "已过期 · 不会带入模型" : "待确认 · 应用后保存"}</Tag></Space>
    <p>{memory.summary}</p>
    {!!memory.decisions.length && <ul>{memory.decisions.map((s, i) => <li key={i}>{s}</li>)}</ul>}
    {!!memory.inputBindings.length && <p className="bt-muted">参数绑定：{memory.inputBindings.join("；")}</p>}
    {!!memory.outputs.length && <p className="bt-muted">可传递输出：{memory.outputs.map(o => `${o.key} ← ${o.path}`).join("；")}</p>}
    {!!memory.questions.length && <><strong>尚需确认</strong><ul>{memory.questions.map((s, i) => <li key={i}>{s}</li>)}</ul></>}
    {memory.evidence.map(e => <Tag key={e.id}>{e.filePath.split("/").pop()}:{e.lineStart} · {e.commitId.slice(0, 8)}</Tag>)}
  </div>;
}

function BackgroundDetails({ value }: { value: Record<string, unknown> }) {
  const project = value.projectBackground as { description?: string; businessRules?: string } | undefined;
  const module = value.moduleBackground as typeof project;
  const connections = value.connections as { name?: string; kind?: string; notes?: string }[] | undefined;
  return <>
    <p>环境：{String(value.environment || "test")}</p>
    <RepositoryKnowledge value={value} />
    {[{ title: "项目说明", text: project?.description }, { title: "项目业务规则", text: project?.businessRules },
      { title: "模块说明", text: module?.description }, { title: "模块业务规则", text: module?.businessRules }].map(row =>
      <p key={row.title}><strong>{row.title}：</strong>{row.text || "尚未填写"}</p>)}
    {!!connections?.length && <><strong>关联的数据服务</strong><ul>{connections.map((c, i) => <li key={i}>{c.name} · {c.kind}{c.notes ? ` · ${c.notes}` : ""}</li>)}</ul></>}
  </>;
}
