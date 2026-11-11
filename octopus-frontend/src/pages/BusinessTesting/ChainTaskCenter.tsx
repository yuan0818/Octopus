import { environmentLabel } from "./environmentModel";
import { useEffect, useState } from "react";
import { Alert, Button, Empty, Input, Pagination, Progress, Select, Space, Spin, Tag } from "antd";
import { ArrowRightOutlined, CheckCircleOutlined, ClockCircleOutlined, PauseCircleOutlined, ReloadOutlined } from "@ant-design/icons";
import { projectContextApi as api, type ChainTaskSummary, type FlowChain } from "../../api/projectContext";
import type { Operation } from "../../api/businessTesting";
import FlowChainGenerator from "./FlowChainGenerator";
import { errorText } from "./workbenchModel";
import { retryLabel } from "./chainRecoveryModel";
import { filterTasks, readTaskFilters, taskGroup, taskGroups, taskNodeNames, taskProgress, taskStateColors, taskStateLabels } from "./chainTasksModel";
import { setQueryValue } from "./navigationModel";

const pageSize = 10;
export default function ChainTaskCenter({ active, params, updateQuery, operations, onOpenFlow, onFlows }: {
  active: boolean; params: URLSearchParams; updateQuery: (change: (query: URLSearchParams) => void) => void;
  operations: Operation[]; onOpenFlow: (task: FlowChain) => void; onFlows: () => void;
}) {
  const [tasks, setTasks] = useState<ChainTaskSummary[]>([]); const [loading, setLoading] = useState(false);
  const [error, setError] = useState(""); const [actionError, setActionError] = useState(""); const [busy, setBusy] = useState("");
  const [updated, setUpdated] = useState(""); const [attempt, setAttempt] = useState(0);
  const taskId = params.get("fillTask") || "";
  useEffect(() => {
    if (!active) return;
    let alive = true; let timer: ReturnType<typeof setTimeout>;
    const refresh = async () => {
      setLoading(true);
      try { const result = await api.chainTasks(); if (alive) { setTasks(result); setUpdated(new Date().toLocaleTimeString()); setError(""); } }
      catch (e) { if (alive) setError(errorText(e)); }
      finally { if (alive) { setLoading(false); timer = setTimeout(() => void refresh(), 5000); } }
    };
    void refresh();
    return () => { alive = false; clearTimeout(timer); };
  }, [active, attempt]);
  const filters = readTaskFilters(params);
  const filtered = filterTasks(tasks, filters);
  const page = Math.min(filters.page, Math.max(1, Math.ceil(filtered.length / pageSize)));
  const setFilter = (key: string, value: string) => updateQuery(query => { setQueryValue(query, key, value); query.delete("taskPage"); });
  const showDetail = (id: string) => updateQuery(query => setQueryValue(query, "fillTask", id));
  const closeDetail = () => { showDetail(""); setAttempt(n => n + 1); };
  const action = async (task: ChainTaskSummary, name: "pause" | "resume") => {
    setBusy(task.id); setActionError("");
    try { await api.chainAction(task.id, name); }
    catch (e) { setActionError(`${task.name || "未命名流程"}：${errorText(e)}`); }
    finally { setBusy(""); setAttempt(n => n + 1); }
  };
  const clearFilters = () => updateQuery(query => ["taskStatus", "taskSearch", "taskEnvironment", "taskPage"].forEach(key => query.delete(key)));
  return <section className="bt-panel bt-task-center">
    <div className="bt-section-heading">
      <div><h2>整链补全任务</h2><p className="bt-muted">离开页面后仍在后台推进。建议生成后，回到流程审阅并应用。</p></div>
      <Button onClick={onFlows}>前往业务流程 <ArrowRightOutlined /></Button>
    </div>
    <div className="bt-task-groups" role="group" aria-label="按任务状态筛选">
      {taskGroups.map(group => <button type="button" key={group.key} aria-pressed={filters.group === group.key}
        onClick={() => setFilter("taskStatus", group.key === "all" ? "" : group.key)}>
        <span>{group.label}</span><strong>{tasks.filter(task => group.key === "all" || taskGroup(task.state) === group.key).length}</strong>
      </button>)}
    </div>
    <div className="bt-task-filters">
      <Input allowClear aria-label="搜索补全任务" placeholder="搜索流程名称、任务 ID 或节点" value={filters.search} onChange={e => setFilter("taskSearch", e.target.value)} />
      <Select aria-label="筛选任务环境" value={filters.environment} onChange={value => setFilter("taskEnvironment", value)}
        options={[{ value: "", label: "全部环境" }, ...[...new Set([...tasks.map(task => task.environment), filters.environment].filter(Boolean))].sort().map(value => ({ value, label: environmentLabel(value) }))]} />
      <Button icon={<ReloadOutlined />} loading={loading} onClick={() => setAttempt(n => n + 1)}>刷新任务</Button>
    </div>
    <div className="bt-task-sync"><span>最近更新的 100 个任务（含已取消） · {filtered.length} 个符合筛选</span><span>{error ? "连接中断，正在重连" : "每 5 秒自动更新"}{updated && ` · 最近同步 ${updated}`}</span></div>
    {error && <Alert type="error" showIcon message="任务列表暂时无法更新" description={`保留上次结果，连接恢复后继续同步。${error}`} />}
    {actionError && <Alert type="error" showIcon message={actionError} closable afterClose={() => setActionError("")} />}
    {!updated && loading ? <div className="bt-task-empty"><Spin tip="正在读取补全任务"><div style={{ height: 100 }} /></Spin></div> : !filtered.length ? <Empty className="bt-task-empty" description={tasks.length ? "没有符合筛选条件的补全任务" : error ? "暂时无法读取补全任务" : "还没有整链补全任务"}>
      {tasks.length ? <Button onClick={clearFilters}>清除筛选</Button> : !error && <><p className="bt-muted">先在业务流程中规划 API 路径，再点击「AI 补全整个链路」。</p><Button type="primary" onClick={onFlows}>去规划业务流程</Button></>}
    </Empty> : <div className="bt-task-list">
      {filtered.slice((page - 1) * pageSize, page * pageSize).map(task => <article className="bt-task-card" key={task.id} aria-label={`${task.name || "未命名流程"} · ${taskStateLabels[task.state]}`}>
        <div className="bt-task-card-main">
          <div className="bt-task-card-title"><button type="button" onClick={() => showDetail(task.id)}>{task.name || "未命名流程"}</button><Tag color={taskStateColors[task.state]}>{taskStateLabels[task.state]}</Tag></div>
          <div className="bt-task-meta"><Tag>{task.environment || "环境未知"}</Tag><span>{task.model || "自动选择模型"}</span><span title={task.id}>任务 {task.id.slice(0, 8)}</span></div>
          <div className="bt-task-progress"><Progress percent={taskProgress(task)} showInfo={false} size="small" status={task.state === "FAILED" ? "exception" : taskGroup(task.state) === "active" ? "active" : "normal"} /><span>{task.completed} / {task.total} 节点</span></div>
          <p className="bt-task-current">{task.state === "READY" ? <><CheckCircleOutlined /> 全部节点已生成，等待审阅参数变化与步骤记忆</> : task.state === "ACCEPTED" ? <><CheckCircleOutlined /> 建议与步骤记忆已确认，可恢复到草稿后保存流程</> : task.state === "CANCELLED" ? "任务已取消，已生成的建议和错误记录仍可查看" : <><ClockCircleOutlined /> {task.currentNodeLabel || "等待执行器"}{task.currentNodeState && ` · ${taskNodeNames[task.currentNodeState]}`}</>}</p>
          {task.failure && <div className="bt-task-failure"><strong>{task.failure.message}</strong><span>{task.failure.suggestion}</span><small>{task.failure.code}{task.failure.diagnosticId && ` · 诊断编号 ${task.failure.diagnosticId}`}</small></div>}
          <div className="bt-task-time"><span>创建 {new Date(task.createdAt).toLocaleString()}</span><span>更新 {new Date(task.updatedAt).toLocaleString()}</span></div>
        </div>
        <Space className="bt-task-actions" wrap>
          <Button type="primary" onClick={() => showDetail(task.id)}>{task.state === "READY" ? "审阅建议" : task.state === "FAILED" || task.state === "PAUSED" ? "查看原因与进度" : taskGroup(task.state) === "active" ? "查看进度" : "查看结果"}</Button>
          {["QUEUED", "RUNNING"].includes(task.state) && <Button icon={<PauseCircleOutlined />} loading={busy === task.id} disabled={!!busy && busy !== task.id} onClick={() => void action(task, "pause")}>暂停</Button>}
          {["PAUSED", "FAILED"].includes(task.state) && <Button loading={busy === task.id} disabled={!!busy && busy !== task.id} onClick={() => void action(task, "resume")}>{task.state === "FAILED" ? retryLabel(task.failure) : "从检查点继续"}</Button>}
        </Space>
      </article>)}
    </div>}
    {filtered.length > pageSize && <Pagination className="bt-task-pagination" current={page} total={filtered.length} pageSize={pageSize} showSizeChanger={false} onChange={value => updateQuery(query => setQueryValue(query, "taskPage", value === 1 ? "" : String(value)))} />}
    <FlowChainGenerator open={active && !!taskId} flow={null} operations={operations} initialTaskId={taskId || undefined}
      onClose={closeDetail} onOpenFlow={onOpenFlow} />
  </section>;
}
