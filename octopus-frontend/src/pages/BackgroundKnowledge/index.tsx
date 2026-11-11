import { useEffect, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { Alert, Button, Collapse, Empty, Space, Spin, Tag } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { projectContextApi as api, type ContextScope, type RepositoryAnalysis } from "../../api/projectContext";
import { backgroundKnowledgePath, backgroundAnalysisPath } from "../BusinessTesting/navigationModel";
import { errorText } from "../BusinessTesting/workbenchModel";
import BackgroundScopePage from "./ScopePage";
import Evidence from "./Evidence";
import "../BusinessTesting/workbench.css";
import "./background.css";

const running = (task: RepositoryAnalysis | null) => !!task && ["QUEUED", "COLLECTING", "PLANNING", "ANALYZING", "SYNTHESIZING", "SUMMARIZING"].includes(task.state);
export function LegacyBackgroundRedirect() { const { pathname, search } = useLocation(); return <Navigate replace to={pathname === "/business-testing/repository-analysis" ? backgroundAnalysisPath(search) : backgroundKnowledgePath(search)} />; }

export default function BackgroundKnowledgePage() {
  return <BackgroundScopePage title="背景查询" description="查阅当前版本最近一次已采用的分析结论、业务规则和源码依据。">
    {scope => <KnowledgeWorkspace scope={scope} />}
  </BackgroundScopePage>;
}

function KnowledgeWorkspace({ scope }: { scope: ContextScope }) {
  const navigate = useNavigate(); const { search } = useLocation();
  const [task, setTask] = useState<RepositoryAnalysis | null>(null); const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [latest, setLatest] = useState<RepositoryAnalysis | null>(null); const [adoptedAt, setAdoptedAt] = useState("");
  const [revision, setRevision] = useState(0);
  const module = scope.modulePaths?.[0] || "";
  const request = { repositoryKey: scope.repositoryKey, branch: scope.branch, commitId: scope.commitId };
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => { try { const [adopted, newest] = await Promise.all([api.adoptedRepositoryBackground(request), api.repositoryAnalysis(request)]); if (active) {
        const value = adopted?.analysis || null;
        setTask(value); setAdoptedAt(adopted?.adoptedAt || ""); setLatest(newest); setError(""); if (running(newest)) timer = setTimeout(poll, 4000); } }
      catch (e) { if (active) { setError(errorText(e)); timer = setTimeout(poll, 10000); } } finally { if (active) setLoading(false); } };
    void poll(); return () => { active = false; clearTimeout(timer); };
  }, [revision]);
  const report = task?.state === "READY" ? task.report : null;
  const rules = report?.rules.filter(r => !module || r.modulePaths.includes(module)) || [];
  const others = module ? report?.rules.filter(r => !r.modulePaths.includes(module)) || [] : [];
  const ruleItems = (items: typeof rules) => items.map((r, i) => ({ key: i, label: <Space wrap><Tag>{r.kind}</Tag><strong>{r.title}</strong></Space>, children: <>
    <p>{r.description}</p><p className="bk-muted">涉及模块：{r.modulePaths.join("、")}</p>
    {!!r.clientPreparation.length && <><strong>请求准备</strong><ul>{r.clientPreparation.map((v, j) => <li key={j}>{v}</li>)}</ul></>}
    {!!r.uncertainties.length && <Alert type="warning" message="待确认" description={r.uncertainties.join("；")} />}
    <Evidence task={task} ids={r.evidenceIds} />
  </> }));
  return <main className="bk-knowledge">
      <div className="bk-section-title"><h2>已知背景</h2><Button icon={<ReloadOutlined />} onClick={() => setRevision(v => v + 1)}>刷新</Button></div>
      <Space wrap><Tag>{scope.branch} · {scope.commitId.slice(0, 8)}</Tag><Tag>{module || "全部模块"}</Tag></Space>
      {error && <Alert type="error" showIcon message={error} />}
      {task && <Space wrap><Tag color="green">已采用结论</Tag><span className="bk-muted">采用于 {new Date(adoptedAt).toLocaleString()}</span></Space>}
      {latest && latest.taskId !== task?.taskId && <Alert type="info" showIcon message={latest.state === "READY" ? "有新的分析结论待采用" : "另有分析任务；已采用背景保持不变"} action={<Button onClick={() => navigate(backgroundAnalysisPath(search))}>查看分析</Button>} />}

      {loading ? <Spin /> : report ? <>
        <p className="bk-muted">归纳于 {new Date(task!.updatedAt).toLocaleString()} · {task!.model}</p>
        <h3>仓库概览</h3><p className="bk-summary">{report.summary}</p>
        <Collapse items={[{ key: "roles", label: module ? "当前模块职责" : `模块职责 · ${report.modules.length}`, children: report.modules.filter(m => !module || m.modulePath === module).map(m => <p key={m.modulePath}><strong>{m.modulePath || "根模块"}</strong><br />{m.role}</p>) }]} />
        <h3>{module ? "当前模块相关规则" : "入口与业务规则"} <span className="bk-muted">{rules.length}</span></h3>
        {rules.length ? <Collapse items={ruleItems(rules)} /> : <p className="bk-muted">尚无明确归属于此模块的规则，可前往「背景分析」补充分析目标。</p>}
        {!!others.length && <Collapse className="bk-cross-module" items={[{ key: "cross", label: `仓库其他模块规则 · ${others.length}（含网关等入口背景）`, children: <><p className="bk-muted">用于关联跨模块上下文，具体适用关系仍需核对证据。</p><Collapse items={ruleItems(others)} /></> }]} />}
        {!!report.questions.length && <Collapse items={[{ key: "questions", label: `待确认信息 · ${report.questions.length}`, children: <ul>{report.questions.map((q, i) => <li key={i}>{q}</li>)}</ul> }]} />}
        {!!task!.warnings.length && <Collapse items={[{ key: "coverage", label: "分析覆盖边界", children: <ul>{task!.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul> }]} />}
      </> : <Empty description={"此版本尚无已采用的背景，请在背景分析或任务历史中选择已完成的结论并采用。"} />}

    </main>;
}
