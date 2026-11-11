import { useEffect, useRef, useState } from "react";
import { Alert, Button, Input, Select, Spin } from "antd";
import { SendOutlined, RobotOutlined } from "@ant-design/icons";
import { projectContextApi as api, type ContextScope, type RepositoryAnalysis } from "../../api/projectContext";
import { errorText } from "../BusinessTesting/workbenchModel";
import Evidence from "./Evidence";

type ChatMessage = { role: "user" | "assistant"; content: string; model?: string; evidenceIds?: string[] };
export default function BackgroundChat({ scope, refreshToken, analysisRunning }: { scope: ContextScope; refreshToken: number; analysisRunning: boolean }) {
  const [task, setTask] = useState<RepositoryAnalysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(""); const [chatError, setChatError] = useState("");
  const [revision, setRevision] = useState(0);
  const [text, setText] = useState("");
  const [model, setModel] = useState(""); const [models, setModels] = useState<{ name: string }[]>([]);
  const [busy, setBusy] = useState(false); const [messages, setMessages] = useState<ChatMessage[]>([]);
  const alive = useRef(true); const bottom = useRef<HTMLDivElement>(null);
  const adoptedTaskId = useRef<string | undefined>(undefined);
  const module = scope.modulePaths?.[0] || "";
  const { repositoryKey, branch, commitId } = scope;
  const request = { repositoryKey, branch, commitId, model };
  useEffect(() => { alive.current = true; void api.models().then(value => { if (alive.current) setModels(value); }).catch(e => { if (alive.current) setChatError(errorText(e)); }); return () => { alive.current = false; }; }, []);
  useEffect(() => {
    let active = true; setLoading(true);
    void api.adoptedRepositoryBackground({ repositoryKey, branch, commitId }).then(adopted => {
      if (!active) return;
      const value = adopted?.analysis || null;
      if (adoptedTaskId.current !== value?.taskId) { setMessages([]); adoptedTaskId.current = value?.taskId; }
      setTask(value); setError("");
    }).catch(e => { if (active) setError(errorText(e)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [repositoryKey, branch, commitId, refreshToken, revision]);
  useEffect(() => { const list = bottom.current?.parentElement; if (list && (messages.length || busy)) list.scrollTo({ top: list.scrollHeight, behavior: "smooth" }); }, [messages, busy]);
  const send = async () => {
    const input = text.trim(); if (!input || busy || loading || error || !task?.report) return;
    setBusy(true); setChatError("");
    const history = messages.slice(-12).map(m => ({ role: m.role, content: m.content }));
    setMessages(m => [...m, { role: "user", content: input }]);
    try {
      const result = await api.askRepositoryBackground({ scope: request, modulePath: module, question: input, backgroundTaskId: task?.taskId, history });
      if (!alive.current || adoptedTaskId.current !== task.taskId) return;
      setMessages(m => [...m, { role: "assistant", content: result.answer, model: result.model, evidenceIds: result.evidenceIds }]);
      setText("");
    } catch (e) { if (alive.current) { setChatError(errorText(e)); setMessages(m => m.slice(0, -1)); setRevision(v => v + 1); } }
    finally { if (alive.current) setBusy(false); }
  };
  const report = task?.state === "READY" ? task.report : null;
  return (
    <aside className="bk-chat" aria-label="背景对话">
      <div className="bk-chat-heading"><p>依据当前版本已采用的结论追问业务规则与证据。</p></div>
      {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setRevision(v => v + 1)}>重试</Button>} />}
      {!loading && !error && !task?.report && <Alert type="info" showIcon message="当前版本尚无已采用背景，完成分析并采用结论后即可对话。" />}
      <div className="bk-messages" aria-live="polite">
        {!messages.length && <div className="bk-welcome"><RobotOutlined /><h3>想先了解哪一部分？</h3><p>问答依据当前版本已采用的仓库归纳。需要补充源码分析时，切换到「分析目标」填写目标并发起任务。</p>
          <Button block onClick={() => { setText("当前入口有哪些签名、加密和请求头要求？请说明依据及待确认项。"); }}>入口有哪些前置要求？</Button>
          </div>}
        {messages.map((m, i) => <article key={i} className={`bk-message bk-message-${m.role}`}><strong>{m.role === "user" ? "你" : "背景助手"}{m.model && <small> · {m.model}</small>}</strong><p>{m.content.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, j) => part.startsWith("**") ? <strong key={j}>{part.slice(2, -2)}</strong> : part.startsWith("`") ? <code key={j}>{part.slice(1, -1)}</code> : part)}</p>{!!m.evidenceIds?.length && <Evidence task={task} ids={m.evidenceIds} />}</article>)}
        {busy && <div><Spin size="small" /> 正在结合背景回答…</div>}<div ref={bottom} />
      </div>
      <div className="bk-composer">
        <Input.TextArea aria-label="背景问题" value={text} maxLength={5500} autoSize={{ minRows: 3, maxRows: 8 }} placeholder="询问当前版本的业务规则、入口要求或证据…" disabled={busy || !report} onChange={e => setText(e.target.value)} onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); if (!loading && !error) void send(); } }} />
        {chatError && <Alert type="error" showIcon message="本次操作未完成" description={chatError} />}
        <div className="bk-send"><Select aria-label="对话模型" value={model} disabled={busy} options={[{ value: "", label: "默认模型" }, ...models.map(m => ({ value: m.name, label: m.name }))]} onChange={setModel} /><Button type="primary" icon={<SendOutlined />} loading={busy} disabled={!report || !text.trim() || loading || !!error} onClick={() => void send()}>发送</Button></div>
        <p className="bk-muted">{analysisRunning ? "新分析在后台运行，问答继续使用已采用背景。" : "Ctrl / ⌘ + Enter 发送"} 对话保留在本页会话中。</p>
        <details className="bk-prompt-info"><summary>模型如何使用这些信息</summary><p>系统提示词：服务端固定维护编排规则、证据要求和工具边界。<br />用户消息：本次问题或分析目标。<br />上下文：选定版本的背景和相关记忆。分析时按计划读取源码，最终总结全部分项记忆。</p></details>
      </div>
    </aside>
  );
}
