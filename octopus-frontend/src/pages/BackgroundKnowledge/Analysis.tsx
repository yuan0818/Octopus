import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { Alert, Button, Empty, Input, Select, Space, Spin, Tabs } from "antd";
import { ReloadOutlined, SendOutlined } from "@ant-design/icons";
import { projectContextApi as api, type ContextScope, type RepositoryAnalysis } from "../../api/projectContext";
import { backgroundTasksPath } from "../BusinessTesting/navigationModel";
import { errorText } from "../BusinessTesting/workbenchModel";
import BackgroundScopePage from "./ScopePage";
import TaskDetail from "./TaskDetail";
import BackgroundChat from "./BackgroundChat";

const running = (task: RepositoryAnalysis | null) => !!task && ["QUEUED", "COLLECTING", "PLANNING", "ANALYZING", "SYNTHESIZING", "SUMMARIZING"].includes(task.state);

export default function BackgroundAnalysisPage() {
  return <BackgroundScopePage title="背景分析" description="给出分析目标，由 AI 制定计划、逐项读取源码并归纳全部模块；在这里对话、跟踪进度和恢复失败任务。">
    {scope => <AnalysisWorkspace scope={scope} />}
  </BackgroundScopePage>;
}

function AnalysisWorkspace({ scope }: { scope: ContextScope }) {
  const { search } = useLocation();
  const [task, setTask] = useState<RepositoryAnalysis | null>(null);
  const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false);
  const [error, setError] = useState(""); const [submitError, setSubmitError] = useState("");
  const [revision, setRevision] = useState(0);
  const [goal, setGoal] = useState(""); const [model, setModel] = useState("");
  const [models, setModels] = useState<{ name: string }[]>([]);
  const alive = useRef(true); const submitting = useRef(false);
  const module = scope.modulePaths?.[0] || "";
  const { repositoryKey, branch, commitId } = scope;
  useEffect(() => { alive.current = true; void api.models().then(value => { if (alive.current) setModels(value); }).catch(e => { if (alive.current) setSubmitError(errorText(e)); }); return () => { alive.current = false; }; }, []);
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      try {
        const value = await api.repositoryAnalysis({ repositoryKey, branch, commitId });
        if (active) { setTask(value); setError(""); if (running(value)) timer = setTimeout(load, 4000); }
      } catch (e) { if (active) { setError(errorText(e)); timer = setTimeout(load, 10000); } }
      finally { if (active) setLoading(false); }
    };
    void load(); return () => { active = false; clearTimeout(timer); };
  }, [repositoryKey, branch, commitId, revision]);
  const start = async () => {
    if (!goal.trim() || submitting.current || loading || error || running(task)) return;
    submitting.current = true; setBusy(true); setSubmitError("");
    try {
      const value = await api.startRepositoryAnalysis({ repositoryKey, branch, commitId, model, restart: true,
        goal: module ? `重点关注模块：${module}。\n${goal.trim()}` : goal.trim() });
      if (alive.current) { setTask(value); setGoal(""); setRevision(v => v + 1); }
    } catch (e) { if (alive.current) { setSubmitError(errorText(e)); setRevision(v => v + 1); } }
    finally { submitting.current = false; if (alive.current) setBusy(false); }
  };
  return <div className="bk-analysis-workspace">
    <section className="bk-analysis-interaction" aria-label="背景分析工作区">
    <Tabs defaultActiveKey="analysis" items={[{ key: "analysis", label: "分析目标", forceRender: true, children: (
    <section className="bk-analysis-composer" aria-label="发起背景分析">
      <p className="bk-muted">{module ? `重点关注 ${module}，最终仍归纳全部模块。` : "从仓库目录开始规划，关联业务逻辑、入口处理和配置管理，最终归纳全部模块。"}</p>
      <Space wrap className="bk-analysis-examples">
        <Button disabled={busy || running(task)} onClick={() => setGoal("分析仓库的业务职责、关键行为和跨模块关系，核对配置来源与生效条件，保留证据、例外和待确认项，最终归纳全部模块。")}>全面理解仓库</Button>
        <Button disabled={busy || running(task)} onClick={() => setGoal("分析入口到业务模块的上下文传递，查明鉴权、签名、加解密、请求头预处理及启用条件；按实际源码取证，最终归纳全部模块。")}>梳理入口与跨模块规则</Button>
      </Space>
      <Input.TextArea aria-label="分析目标" value={goal} maxLength={5500} showCount autoSize={{ minRows: 4, maxRows: 10 }} placeholder="描述希望查明的问题、重点模块或已有背景中的缺口…" disabled={busy || running(task)} onChange={e => setGoal(e.target.value)} onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); void start(); } }} />
      {submitError && <Alert type="error" showIcon message="分析未提交" description={submitError} />}
      <div className="bk-send"><Select aria-label="分析模型" disabled={busy || running(task)} value={model} options={[{ value: "", label: "默认模型" }, ...models.map(m => ({ value: m.name, label: m.name }))]} onChange={setModel} /><Button type="primary" icon={<SendOutlined />} loading={busy} disabled={!goal.trim() || loading || !!error || running(task)} onClick={() => void start()}>{task ? "发起新分析" : "开始分析"}</Button></div>
      <p className="bk-muted">{running(task) ? "当前分析正在后台运行，可在下方查看实时计划和进度。" : task ? "发起新分析会重新规划，原任务保留在历史中；新结论需采用后才更新背景，失败任务可从检查点继续。" : "提交后会自动保存分析目标、计划、分项记忆和执行进度。"}</p>
    </section>
    ) }, { key: "chat", label: "背景对话", forceRender: true, children: <BackgroundChat scope={scope} refreshToken={revision} analysisRunning={running(task)} /> }]} />
    </section>
    <section className="bk-analysis-current" aria-label="当前背景分析">
      <div className="bk-section-title"><h2>当前分析</h2><Space wrap><Button icon={<ReloadOutlined />} onClick={() => setRevision(v => v + 1)}>刷新</Button><Link to={backgroundTasksPath(search)}>任务历史</Link></Space></div>
      {error && <Alert type="error" showIcon message={error} />}
      {loading ? <Spin /> : task?.taskId ? <TaskDetail key={task.taskId} id={task.taskId} initialTab="plan" refreshToken={revision} onUpdated={() => setRevision(v => v + 1)} /> : <Empty description="当前版本尚无分析任务，填写目标开始分析。" />}
    </section>
  </div>;
}
