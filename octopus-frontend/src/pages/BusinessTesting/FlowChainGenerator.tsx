import { environmentLabel, environmentOptions } from "./environmentModel";
import { RepositoryKnowledge } from "./RepositoryAnalysisPanel";
import { useEffect, useRef, useState } from "react";
import { Alert, Button, Checkbox, Collapse, Drawer, Input, Progress, Select, Space, Spin, Tag, Typography } from "antd";
import { CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined, LoadingOutlined, PauseCircleOutlined, ReloadOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { projectContextApi as api, type ChainState, type ChainNodeState, type FlowChain } from "../../api/projectContext";
import { queryApi } from "../../api/query";
import type { Flow, Operation } from "../../api/businessTesting";
import { Field, ValueInput } from "./FormControls";
import ChainFailureNotice from "./ChainFailureNotice";
import { chainStateNames as stateNames, chainCanvasMismatch, retryLabel } from "./chainRecoveryModel";
import { defaultValue, errorText, refOf } from "./workbenchModel";
import { fillChanges, sensitiveContextKey, snapshot } from "./flowFillModel";

const nodeNames: Record<ChainNodeState, string> = { WAITING: "等待", PREPARING: "读取上下文", GENERATING: "模型补全中", COMPLETED: "建议已生成", FAILED: "生成失败", INTERRUPTED: "执行中断 · 可恢复" };
const running = (state: ChainState) => ["QUEUED", "RUNNING", "PAUSE_REQUESTED", "ACCEPTING"].includes(state);
export default function FlowChainGenerator({ open, flow, operations, initialTaskId, initialEnvironment = "test", initialValues = {}, onClose, onAccept, onOpenFlow, onViewTasks }: {
  open: boolean; flow: Flow | null; operations: Operation[]; initialTaskId?: string; initialEnvironment?: string; initialValues?: Record<string, unknown>;
  onClose: () => void; onAccept?: (task: FlowChain, contextValues: Record<string, unknown>) => Promise<FlowChain>;
  onOpenFlow?: (task: FlowChain) => void; onViewTasks?: (task: FlowChain) => void;
}) {
  const [task, setTask] = useState<FlowChain | null>(null);
  const [goal, setGoal] = useState(""); const [environment, setEnvironment] = useState("test"); const [model, setModel] = useState("");
  const [models, setModels] = useState<{ name: string }[]>([]); const [environments, setEnvironments] = useState<string[]>(["test"]);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [selected, setSelected] = useState(""); const [error, setError] = useState("");
  const [loading, setLoading] = useState(false); const [busy, setBusy] = useState(false); const [attempt, setAttempt] = useState(0);
  const receive = (next: FlowChain) => setTask(current => current?.id === next.id && current.revision > next.revision ? current : next);
  const request = useRef<{ key: string; id: string } | null>(null);
  const viewEpoch = useRef(0);
  const taskValues = useRef(new Map<string, Record<string, unknown>>());
  useEffect(() => {
    const epoch = ++viewEpoch.current;
    if (!open || (!flow && !initialTaskId)) return;
    setTask(null); setSelected(""); setError(""); setBusy(false);
    let alive = true; setLoading(true); setGoal(flow?.name || ""); setEnvironment(initialEnvironment);
    setValues(Object.fromEntries(Object.entries(initialValues).filter(([key]) => flow?.initialContext[key] && !flow.initialContext[key].sensitive && !sensitiveContextKey(key))));
    void (initialTaskId ? api.chain(initialTaskId) : api.latestChain(flow!.flowId)).then(value => {
      if (alive && viewEpoch.current === epoch) { setTask(value); if (value) { setGoal(value.goal); setEnvironment(value.environment); setValues(taskValues.current.get(value.id) || (value.environment === initialEnvironment ? Object.fromEntries(Object.entries(initialValues).filter(([key]) => flow?.initialContext[key] && !flow.initialContext[key].sensitive && !sensitiveContextKey(key))) : {})); } }
    }).catch(e => { if (alive) setError(errorText(e)); }).finally(() => { if (alive) setLoading(false); });
    if (!onOpenFlow && flow) {
    void api.models().then(v => { if (alive) setModels(v); }).catch(e => { if (alive) setError(errorText(e)); });
    const ids = new Set(operations.filter(o => flow.nodes.some(n => n.operationRef === refOf(o))).flatMap(o => o.implementations.map(i => i.projectId)));
    void queryApi.repositoryScopes().then(async result => {
      const scopes = result.data.filter(s => s.modules.some(m => ids.has(m.projectId)));
      const names = await Promise.all(scopes.map(s => api.environments(s.key)));
      if (alive) setEnvironments([...new Set(["test", initialEnvironment, ...names.flat()])]);
    }).catch(e => { if (alive) setError(errorText(e)); });
    }
    return () => { alive = false; ++viewEpoch.current; };
    // Reopening reconnects to the server-owned task. Canvas edits must not silently replace its snapshot.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, flow?.flowId, initialTaskId, attempt]);
  useEffect(() => {
    if (!open || !task || ["ACCEPTED", "CANCELLED"].includes(task.state)) return;
    let alive = true, failures = 0; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try { const next = await api.chain(task.id); if (alive) { receive(next); failures = 0; setError(""); } }
      catch (e) { failures++; if (alive) setError(`进度连接中断，任务可能仍在服务端运行。${errorText(e)}`); }
      if (alive && failures < 3) timer = setTimeout(() => void poll(), running(task.state) ? 1500 : 5000);
    };
    timer = setTimeout(() => void poll(), 600);
    return () => { alive = false; clearTimeout(timer); };
  }, [open, task?.id, task?.state, attempt]);
  const start = async () => {
    if (!flow) return; const epoch = viewEpoch.current;
    const body = { flow: structuredClone(flow), goal, environment, model, contextValues: values }; const key = snapshot(body);
    if (request.current?.key !== key) request.current = { key, id: crypto.randomUUID() };
    setBusy(true); setError("");
    try { const next = await api.startChain({ ...body, requestId: request.current.id }); if (epoch === viewEpoch.current) { taskValues.current.set(next.id, values); receive(next); setSelected(""); } }
    catch (e) { if (epoch === viewEpoch.current) setError(errorText(e)); }
    finally { if (epoch === viewEpoch.current) setBusy(false); }
  };
  const action = async (name: "pause" | "resume" | "cancel" | "retry", nodeId?: string) => {
    if (!task) return; const epoch = viewEpoch.current; setBusy(true); setError("");
    try { const next = await api.chainAction(task.id, name, nodeId); if (epoch === viewEpoch.current) { receive(next); if (name === "retry" || name === "resume") setSelected(""); } }
    catch (e) { if (epoch === viewEpoch.current) setError(errorText(e)); }
    finally { if (epoch === viewEpoch.current) setBusy(false); }
  };
  const apply = async () => {
    if (!task || !onAccept) return; setBusy(true); setError("");
    try { receive(await onAccept(task, values)); } catch (e) { setError(errorText(e)); }
    finally { setBusy(false); }
  };
  const mismatch = chainCanvasMismatch(flow, task);
  const completed = task?.nodes.filter(n => n.state === "COMPLETED").length || 0;
  const focused = task?.nodes.find(n => n.nodeId === selected) || task?.nodes.find(n => ["PREPARING", "GENERATING", "FAILED", "INTERRUPTED"].includes(n.state)) || task?.nodes.find(n => n.state === "WAITING") || task?.nodes.at(-1);
  const changes = task ? fillChanges(task.original, task.candidate) : [];
  const focusedChanges = changes.find(n => n.id === focused?.nodeId)?.changes || [];
  const totalChanges = changes.reduce((sum, n) => sum + n.changes.length, 0);
  return <Drawer title={onOpenFlow ? "补全任务详情" : "AI 补全整个链路"} width={1080} open={open} onClose={onClose} closable={!busy} maskClosable={!busy} keyboard={!busy}
    footer={<div className="bt-dialog-actions">
      <Button disabled={busy} onClick={onClose}>{onOpenFlow ? "返回任务列表" : task && running(task.state) ? "后台继续，返回编排" : "返回编排"}</Button>
      <Space wrap>
        {task && onViewTasks && <Button disabled={busy} onClick={() => onViewTasks(task)}>在任务中心查看</Button>}
        {task && ["QUEUED", "RUNNING"].includes(task.state) && <Button icon={<PauseCircleOutlined />} disabled={busy} onClick={() => void action("pause")}>暂停推进</Button>}
        {task && ["PAUSED", "FAILED"].includes(task.state) && <Button type="primary" loading={busy} onClick={() => void action("resume")}>{task.state === "FAILED" ? retryLabel(task.failure) : "继续补全"}</Button>}
        {task && onOpenFlow && <Button type="primary" disabled={busy} onClick={() => onOpenFlow(task)}>{task.state === "ACCEPTED" ? "恢复结果到流程草稿" : task.state === "READY" ? "到流程审阅并应用" : "打开任务的流程草稿"}</Button>}
        {onAccept && task?.state === "ACCEPTED" && flow && snapshot(flow) === snapshot(task.original) && <Button type="primary" loading={busy} disabled={mismatch} onClick={() => void apply()}>恢复已确认结果到草稿</Button>}
        {onAccept && task?.state === "READY" && <Button type="primary" loading={busy} disabled={mismatch} onClick={() => void apply()}>统一应用 {totalChanges} 项建议与步骤记忆</Button>}
        {task && !["ACCEPTED", "CANCELLED", "ACCEPTING"].includes(task.state) && <Button disabled={busy} onClick={() => void action("cancel")}>取消任务</Button>}
        {!onOpenFlow && task && ["ACCEPTED", "CANCELLED"].includes(task.state) && <Button disabled={busy} onClick={() => { setTask(null); setError(""); request.current = null; }}>开始新的补全</Button>}
        {!onOpenFlow && !task && <Button type="primary" icon={<ThunderboltOutlined />} loading={busy || loading} disabled={!flow?.nodes.length || !goal.trim() || !models.length} onClick={() => void start()}>开始整链补全</Button>}
      </Space>
    </div>}>
    <p className="bt-lead">一次启动，按你规划的依赖顺序自动推进每个节点。每步读取自己的契约、源码和项目背景，并传递本次任务的候选配置与记忆；全部生成后统一审阅应用。</p>
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setAttempt(n => n + 1)}>重新连接任务</Button>} />}
    {mismatch && <Alert type="warning" showIcon message="当前画布已修改，旧任务不能覆盖新编辑" description="可以继续查看结果；取消旧任务后，按当前画布重新开始补全。" />}
    {!task && onOpenFlow ? loading && <Spin /> : !task ? <>
      <div className="bt-form-grid">
        <Field label="参考环境"><Select value={environment} disabled={loading || busy} options={environmentOptions([...environments, environment])} onChange={value => { setEnvironment(value); setValues({}); }} /></Field>
        <Field label="补全模型"><Select value={model} disabled={loading || busy} options={[{ value: "", label: "自动选择已配置模型" }, ...models.map(m => ({ value: m.name, label: m.name }))]} onChange={setModel} /></Field>
      </div>
      <Field label="整条链路要验证什么"><Input.TextArea rows={3} value={goal} disabled={loading || busy} maxLength={12000} onChange={e => setGoal(e.target.value)} placeholder="说明这条路径的业务目标、预置数据含义与预期结果" /></Field>
      <Collapse items={[{ key: "values", label: `预置上下文 · ${Object.keys(values).length} 项参考值`, children: <>
        <p className="bt-muted">在流程设置中声明初始数据。普通值供本次补全参考；敏感值仅在测试运行时提供。</p>
        {Object.entries(flow?.initialContext || {}).map(([key, v]) => <div className="bt-param" key={key}><Space><strong>{key}</strong><Tag>{v.type}</Tag>
          {v.sensitive || sensitiveContextKey(key) ? <Tag>运行时提供</Tag> : <Checkbox disabled={busy || loading} checked={key in values} onChange={e => setValues(current => e.target.checked ? { ...current, [key]: defaultValue(v.type) } : Object.fromEntries(Object.entries(current).filter(([k]) => k !== key)))}>提供参考值</Checkbox>}</Space>
          {key in values && <ValueInput type={v.type} label={`${key} 参考值`} value={values[key]} onChange={value => setValues(old => ({ ...old, [key]: value }))} />}
        </div>)}
      </> }]} />
      <h3>人工规划的链路 · {flow?.nodes.length || 0} 个节点</h3>
      <Space wrap>{flow?.nodes.map((node, i) => <Tag key={node.id}>{i + 1}. {node.label}</Tag>)}</Space>
      <p className="bt-muted">默认保留已填参数、输出和断言，只补全空缺。执行中的节点结束后可暂停，失败时停在原节点；这里生成测试建议，不执行业务接口。</p>
    </> : <>
      <h2>{task.original.name || "未命名流程"}</h2>
      <Typography.Paragraph className="bt-muted" copyable={{ text: task.id }}>任务 ID：{task.id}</Typography.Paragraph>
      <div className="bt-section-heading"><h3>{stateNames[task.state]}</h3><Tag>{environmentLabel(task.environment)} · {completed} / {task.nodes.length} 个节点已生成</Tag></div>
      <Progress percent={task.nodes.length ? Math.round(completed / task.nodes.length * 100) : 0} status={task.state === "FAILED" ? "exception" : running(task.state) ? "active" : "normal"} />
      {task.failure ? <ChainFailureNotice failure={task.failure} /> : task.error && <Alert type="error" showIcon message={task.error} />}
      {task.state === "PAUSE_REQUESTED" && <Alert type="info" showIcon message="当前模型请求完成后暂停，后续节点不会启动" />}
      {task.state === "READY" && <Alert type="success" showIcon message="整条链路的候选建议已生成" description="逐项查看参数变化、步骤记忆及待确认信息。统一应用后仍需保存流程，并确认业务预期。" />}
      {task.state === "ACCEPTED" && <Alert type="success" showIcon message="链路建议已应用，步骤记忆已确认" description="返回画布后保存流程草稿。" />}
      <div className="bt-chain-layout">
        <nav className="bt-chain-nodes" aria-label="链路补全步骤状态">
          {task.nodes.map((node, i) => <button type="button" key={node.nodeId} data-state={node.state} aria-current={focused?.nodeId === node.nodeId ? "step" : undefined} onClick={() => setSelected(node.nodeId)}>
            <span>{["PREPARING", "GENERATING"].includes(node.state) ? <LoadingOutlined /> : node.state === "COMPLETED" ? <CheckCircleOutlined /> : node.state === "FAILED" ? <CloseCircleOutlined /> : node.state === "INTERRUPTED" ? <PauseCircleOutlined /> : <ClockCircleOutlined />} {i + 1}. {node.label}</span>
            <small>{nodeNames[node.state]}{node.attempts > 1 ? ` · 第 ${node.attempts} 次尝试` : ""}</small>
          </button>)}
        </nav>
        {focused && <section className="bt-chain-detail">
          <div className="bt-section-heading"><h3>{task.nodes.indexOf(focused) + 1}. {focused.label}</h3><Tag color={focused.state === "FAILED" ? "red" : focused.state === "COMPLETED" ? "green" : "blue"}>{nodeNames[focused.state]}</Tag></div>
          {focused.failure && focused.failure.diagnosticId !== task.failure?.diagnosticId ? <ChainFailureNotice failure={focused.failure} /> : !focused.failure && focused.error && <Alert type="error" showIcon message={focused.error} />}
          {focused.context && <Space wrap><Tag>{focused.context.sourceCount} 段源码</Tag><Tag>{focused.context.background.length} 组项目/模块背景</Tag><Tag>{focused.context.upstreamNodeIds.length} 个上游节点</Tag></Space>}
          {focused.state === "WAITING" && <p className="bt-muted">等待前序节点生成建议，再携带它们的候选输出和记忆继续补全。</p>}
          {["PREPARING", "GENERATING"].includes(focused.state) && <p className="bt-muted">{focused.state === "PREPARING" ? "正在读取当前版本的入参、响应、源码与背景。" : "模型正在生成当前节点建议，完成后自动推进下一节点。"}</p>}
          {focused.state === "COMPLETED" && <><h4>候选变化 · {focusedChanges.length} 项</h4>{focusedChanges.length ? <ul>{focusedChanges.map((line, i) => <li key={i}>{line}</li>)}</ul> : <p className="bt-muted">参数配置保持不变，已生成步骤记忆。</p>}</>}
          {focused.memory && <div className="bt-param"><Space><strong>步骤记忆</strong><Tag>{task.state === "ACCEPTED" ? "已确认" : "本次任务候选 · 尚未确认"}</Tag></Space>
            <p>{focused.memory.memory.summary}</p><ul>{focused.memory.memory.decisions.map((line, i) => <li key={i}>{line}</li>)}</ul>
          </div>}
          <Collapse items={[
            { key: "questions", label: `待确认信息 · ${new Set([...focused.questions, ...focused.assumptions, ...(focused.memory?.memory.questions || [])]).size} 项`, children: <ul>{[...new Set([...focused.questions, ...focused.assumptions, ...(focused.memory?.memory.questions || [])])].map((line, i) => <li key={i}>{line}</li>)}</ul> },
            { key: "source", label: "源码依据与背景范围", children: <>
              {focused.context?.background.map((b, i) => <div key={i}><strong>{String(b.project || "项目")} / {String(b.modulePath || "根目录")}</strong><RepositoryKnowledge value={b} /></div>)}
              <ul>{focused.context?.sources.map(s => <li key={s.id} style={{ overflowWrap: "anywhere" }}>{s.filePath.split("/").pop()}:{s.lineStart}–{s.lineEnd} · {s.kind} · {s.commitId.slice(0, 8)}</li>)}</ul>
              <ul>{focused.warnings.map((line, i) => <li key={i}>{line}</li>)}</ul>
            </> },
          ]} />
          {["PAUSED", "FAILED", "READY"].includes(task.state) && focused.attempts > 0 && <Button style={{ marginTop: 16 }} icon={<ReloadOutlined />} disabled={busy} onClick={() => void action("retry", focused.nodeId)}>从此节点重新生成后续链路</Button>}
        </section>}
      </div>
      <Collapse items={[{ key: "history", label: `运行记录与错误历史 · ${task.events?.length || 0} 条`, children: <ol className="bt-chain-history">{[...(task.events || [])].reverse().map(event => <li key={event.revision}>
        <Space wrap><time>{new Date(event.at).toLocaleString()}</time>{event.nodeId && <Tag>{task.nodes.find(n => n.nodeId === event.nodeId)?.label || event.nodeId}</Tag>}<span>{event.message}</span></Space>
        {event.failure && <ChainFailureNotice failure={event.failure} />}
      </li>)}</ol> }]} />
      <p className="bt-muted">检查点与预置参考值已加密保存。页面刷新后可从侧栏“AI 补全任务”找回；执行器失联后约 45–50 秒转为可恢复暂停，已完成节点保留。</p>
    </>}
  </Drawer>;
}
