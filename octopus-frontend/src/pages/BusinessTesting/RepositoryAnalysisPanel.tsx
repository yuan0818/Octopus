import { Link } from "react-router-dom";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Alert, Button, Collapse, Select, Space, Tabs, Tag, Empty } from "antd";
import { queryApi } from "../../api/query";
import { projectContextApi as api, type ContextScope, type RepositoryAnalysis, type RepositoryReport } from "../../api/projectContext";
import type { RepositoryVersion } from "../../types";
import { Field } from "./FormControls";
import RepositoryAnalysisProgress, { RepositoryAnalysisStatus } from "./RepositoryAnalysisProgress";
import ChainFailureNotice from "./ChainFailureNotice";
import { errorText } from "./workbenchModel";

const labels: Record<RepositoryAnalysis["state"], string> = { QUEUED: "等待分析", COLLECTING: "读取目录", PLANNING: "制定分析计划", ANALYZING: "分项取证与分析", SYNTHESIZING: "总结全部记忆", SUMMARIZING: "AI 归纳中", READY: "背景已就绪", FAILED: "分析失败", CANCELLED: "已取消" };
const kinds: Record<string, string> = { ROUTING: "路由", AUTH: "鉴权", SIGNING: "签名", ENCRYPTION: "加密", PREPROCESSING: "预处理", DEPENDENCY: "依赖", BUSINESS: "业务规则", DATA_FLOW: "数据流", VALIDATION: "校验" };
const running = (state?: string) => ["QUEUED", "COLLECTING", "PLANNING", "ANALYZING", "SYNTHESIZING", "SUMMARIZING"].includes(state || "");

export function RepositoryKnowledge({ value }: { value: Record<string, unknown> }) {
  const knowledge = value.repositoryAnalysis as { branch: string; commitId: string; report: RepositoryReport } | undefined;
  const params = new URLSearchParams();
  for (const [field, parameter] of [["repositoryKey", "repository"], ["branch", "branch"], ["commitId", "commit"], ["environment", "environment"]]) {
    if (typeof value[field] === "string") params.set(parameter, value[field] as string);
  }
  const link = typeof value.repositoryKey === "string" ? <Link to={`/background?${params}`} target="_blank" rel="noopener noreferrer">查看此 API 所属仓库背景</Link> : null;
  if (!knowledge?.report) return <><p className="bt-muted">{String(value.repositoryAnalysisStatus || "此版本尚无仓库背景分析")}</p>{link}</>;
  return <div><Tag color="blue">已使用仓库背景 · {knowledge.branch} · {knowledge.commitId.slice(0, 8)}</Tag>
    <p>{knowledge.report.summary}</p>{link}<Collapse items={[{ key: "rules", label: `${knowledge.report.rules.length} 条入口与跨模块规则`, children: knowledge.report.rules.map((rule, i) =>
      <div key={i}><strong>{rule.title}</strong><p>{rule.description}</p><p>{rule.clientPreparation.join("；")}</p></div>) }]} /></div>;
}

export default function RepositoryAnalysisPanel({ scope, environment, active, onChange }: {
  scope: ContextScope; environment: string; active: boolean; onChange: (scope: ContextScope) => void;
}) {
  const [branches, setBranches] = useState<string[]>([]);
  const [versions, setVersions] = useState<RepositoryVersion[]>([]);
  const [models, setModels] = useState<{ name: string }[]>([]);
  const [model, setModel] = useState("");
  const [task, setTask] = useState<RepositoryAnalysis | null>(null);
  const [error, setError] = useState("");
  const [catalogError, setCatalogError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  const key = JSON.stringify([scope.repositoryKey, scope.branch, scope.commitId, environment]);
  const currentKey = useRef(key);
  useLayoutEffect(() => { currentKey.current = key; }, [key]);
  useEffect(() => {
    let alive = true; setBranches([]); setCatalogError("");
    if (scope.repositoryKey) void queryApi.repositoryScopes().then(r => { if (alive) setBranches([...new Set(r.data.find(s => s.key === scope.repositoryKey)?.modules.flatMap(m => m.branches) || [])]); })
      .catch(e => { if (alive) setCatalogError(errorText(e)); });
    return () => { alive = false; };
  }, [scope.repositoryKey, refresh]);
  useEffect(() => {
    let alive = true; setVersions([]);
    if (scope.repositoryKey && scope.branch) void queryApi.repositoryVersions(scope.repositoryKey, scope.branch).then(r => { if (alive) setVersions(r.data); })
      .catch(e => { if (alive) setCatalogError(errorText(e)); });
    return () => { alive = false; };
  }, [scope.repositoryKey, scope.branch, refresh]);
  useEffect(() => { let alive = true; void api.models().then(v => { if (alive) setModels(v); }).catch(() => {}); return () => { alive = false; }; }, []);
  useEffect(() => {
    let alive = true; let inFlight = false; let wake = () => {}; let timer: ReturnType<typeof setTimeout> | undefined;
    const revision = ++generation.current;
    setTask(null); setError(""); setBusy(false); setLoading(false);
    if (active && scope.repositoryKey && scope.branch && scope.commitId) {
      const poll = async () => {
        if (!alive || inFlight || revision !== generation.current) return;
        clearTimeout(timer); inFlight = true; setLoading(true);
        try {
          const result = await api.repositoryAnalysis({ repositoryKey: scope.repositoryKey, branch: scope.branch, commitId: scope.commitId });
          if (alive && revision === generation.current) { setTask(result); setError(""); if (running(result?.state)) timer = setTimeout(poll, 4000); }
        } catch (e) { if (alive && revision === generation.current) { setError(errorText(e)); timer = setTimeout(poll, 10000); } }
        finally { inFlight = false; if (alive && revision === generation.current) setLoading(false); }
      };
      wake = () => { if (document.visibilityState === "visible") void poll(); };
      window.addEventListener("focus", wake); document.addEventListener("visibilitychange", wake);
      void poll();
    }
    return () => { alive = false; clearTimeout(timer); window.removeEventListener("focus", wake); document.removeEventListener("visibilitychange", wake); };
  }, [key, active, refresh]);
  const act = async (cancel = false, restart = false) => {
    const requestedKey = key; setBusy(true); setError("");
    try {
      const request = { repositoryKey: scope.repositoryKey, branch: scope.branch, commitId: scope.commitId, model, restart };
      const result = await (cancel ? api.cancelRepositoryAnalysis(request) : api.startRepositoryAnalysis(request));
      if (currentKey.current === requestedKey) { setTask(result); setRefresh(v => v + 1); }
    } catch (e) { if (currentKey.current === requestedKey) setError(errorText(e)); }
    finally { if (currentKey.current === requestedKey) { setBusy(false); setLoading(false); } }
  };
  if (!scope.repositoryKey) return null;
  const version = versions.find(v => v.commitId === scope.commitId);
  return <section className="bt-repository-analysis">
    <div className="bt-section-heading"><div><h3>分析任务</h3></div>
      {task && <Tag color={task.state === "READY" ? "green" : task.state === "FAILED" ? "red" : "blue"}>{labels[task.state]}</Tag>}</div>
    <Collapse ghost defaultActiveKey={scope.commitId ? [] : ["version"]} items={[{ key: "version", label: <Space wrap><strong>代码版本</strong><span>{scope.branch || "选择分支"} · {scope.commitId?.slice(0, 8) || "选择提交"}</span><span className="bt-muted">更换版本</span></Space>, children: (
<div className="bt-form-grid">
      <Field label="仓库分析分支"><Select aria-label="仓库分析分支" value={scope.branch || undefined} placeholder="选择分支" showSearch options={branches.map(value => ({ value }))}
        onChange={branch => onChange({ ...scope, branch, commitId: "" })} /></Field>
      <Field label="仓库分析提交"><Select aria-label="仓库分析提交" value={scope.commitId || undefined} disabled={!scope.branch} placeholder="选择已分析提交" showSearch optionFilterProp="label"
        options={versions.map(v => ({ value: v.commitId, label: `${v.commitId.slice(0, 8)} · ${v.projectIds.length} 个模块${v.missingProjectIds.length ? ` · ${v.missingProjectIds.length} 个未分析` : ""}` }))}
        onChange={commitId => onChange({ ...scope, commitId })} /></Field>
    </div>
    ) }]} />
    <Space wrap><Select aria-label="仓库背景分析模型" style={{ minWidth: 230, maxWidth: "100%" }} value={model} disabled={busy || running(task?.state)}
      options={[{ value: "", label: "使用默认模型" }, ...models.map(m => ({ value: m.name, label: m.name }))]} onChange={setModel} />
      <Button type="primary" disabled={!version || running(task?.state) || loading} loading={busy} onClick={() => void act()}>{task?.state === "READY" ? "重新分析仓库背景" : task?.state === "FAILED" ? "从检查点继续" : "分析仓库背景"}</Button>
      {task?.state === "FAILED" && task.progress && <Button disabled={busy} onClick={() => void act(false, true)}>重新规划全部分析</Button>}
      {running(task?.state) && <Button disabled={busy} onClick={() => void act(true)}>取消分析</Button>}
      <Button disabled={busy} onClick={() => setRefresh(v => v + 1)}>刷新状态</Button></Space>
    {catalogError && <Alert showIcon type="error" message={catalogError} />}
    {error && <Alert showIcon type="error" message={error} />}
    {!task && !loading && <Empty description={scope.commitId ? "此版本还没有背景分析，启动后即可查看 LLM 计划和进度。" : "请选择分支和提交，查看或启动分析任务。"} />}
    {task && <>
      {task.request.goal && <Alert type="info" message="本次分析目标" description={task.request.goal} />}
      <RepositoryAnalysisStatus task={task} />
      {task.failure && <ChainFailureNotice failure={task.failure} />}
      {task.state === "SYNTHESIZING" && <Alert type="info" showIcon message="分项分析已完成，正在总结全部记忆" description="每轮总结最长等待 10 分钟；校验失败会自动修正一次。可以离开本页，恢复时会复用已保存记忆。" />}
      <Tabs defaultActiveKey="plan" items={[
        { key: "plan", label: "分析计划与进度", children: <RepositoryAnalysisProgress task={task} /> },
        { key: "report", label: "最终报告", children: task.report ? <>
          <Alert type="success" showIcon message={task.progress ? `已汇总全部 ${task.progress.summarizedTaskIds.length} 份分项记忆` : "仓库背景已就绪"} />

      <p className="bt-repository-summary">{task.report.summary}</p>
      <Space wrap><Tag>{task.request.branch} · {task.request.commitId.slice(0, 8)}</Tag><Tag>{task.model}</Tag><span className="bt-muted">{new Date(task.updatedAt).toLocaleString()}</span></Space>
      <Collapse items={task.report.rules.map((rule, i) => ({ key: i, label: <Space wrap><Tag>{kinds[rule.kind] || rule.kind}</Tag>{rule.title}</Space>, children: <>
        <p>{rule.description}</p><p className="bt-muted">适用模块：{rule.modulePaths.join("、")}</p>
        {!!rule.clientPreparation.length && <><strong>请求准备要求</strong><ul>{rule.clientPreparation.map((v, j) => <li key={j}>{v}</li>)}</ul></>}
        {!!rule.uncertainties.length && <Alert type="warning" message="待确认" description={<ul>{rule.uncertainties.map((v, j) => <li key={j}>{v}</li>)}</ul>} />}
        <Collapse items={rule.evidenceIds.map(id => task.sources.find(s => s.id === id)).filter(s => !!s).map(s => ({ key: s.id, label: s.filePath, children: <>
          <p>源码依据 · {s.commitId.slice(0, 8)} · 行 {s.lineStart}–{s.lineEnd}{s.truncated ? " · 已截断" : ""}</p><pre>{s.text}</pre></> }))} />
      </> }))} />
      {!!task.report.questions.length && <Alert type="warning" showIcon message="部署与执行前仍需确认" description={<ul>{task.report.questions.map((v, i) => <li key={i}>{v}</li>)}</ul>} />}
      <details><summary>模块职责与覆盖范围 · {task.sources.length} 份源码依据</summary>{task.report.modules.map((m, i) => <p key={i}><strong>{m.modulePath}</strong><br />{m.role}</p>)}</details>
    
        </> : <Empty description="全部分项记忆完成并通过最终归纳后，报告会显示在这里。" /> },
        { key: "coverage", label: "覆盖与待确认", children: <>
          {task.warnings.length ? <Alert type="warning" showIcon message="分析覆盖边界" description={<ul>{task.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>} /> : <p>暂无额外覆盖说明。</p>}
          {task.report?.modules.map(m => <p key={m.modulePath}><strong>{m.modulePath}</strong><br />{m.role}</p>)}
        </> },
      ]} />
    </>}
    <p className="bt-muted">分析结果与仓库、分支、提交、测试环境绑定。完成后自动提供给当前版本的步骤补全；旧步骤候选不会自动改写。运行时密钥与动态部署配置仍需通过环境配置确认。</p>
  </section>;
}
