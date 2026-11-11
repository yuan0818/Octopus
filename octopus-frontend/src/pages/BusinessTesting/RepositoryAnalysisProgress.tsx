import { useState } from "react";
import { Alert, Button, Empty, Space, Steps, Tabs, Tag, Timeline } from "antd";
import type { RepositoryAnalysis } from "../../api/projectContext";

const activityLabels: Record<string, string> = { GENERATING: "等待模型决策", READING: "读取源码", VALIDATING: "校验分项记忆", SUMMARIZING: "归纳已有证据", COMPLETED: "记忆已保存" };
const stateLabels: Record<string, string> = { WAITING: "待分析", ANALYZING: "分析中", COMPLETED: "记忆已保存", FAILED: "失败待恢复", CANCELLED: "已取消" };
const taskLabel = (entry: NonNullable<RepositoryAnalysis["progress"]>["tasks"][number]) => entry.state === "ANALYZING" ? activityLabels[entry.activity || ""] || "取证分析中" : stateLabels[entry.state] || entry.state;
export function RepositoryAnalysisStatus({ task }: { task: RepositoryAnalysis }) {
  const p = task.progress, completed = p?.tasks.filter(t => t.state === "COMPLETED").length || 0;
  const stage = task.state === "READY" ? 4 : ["SYNTHESIZING", "SUMMARIZING"].includes(task.state) || (p && completed === p.tasks.length) ? 3 : p ? 2 : task.state === "PLANNING" || task.failure?.stage === "REPOSITORY_PLANNING" ? 1 : 0;
  const current = p?.tasks.find(t => t.task.id === p.currentTaskId);
  const message = task.state === "READY" ? "仓库背景已就绪" : task.state === "FAILED" ? "任务已暂停，等待恢复" : task.state === "CANCELLED" ? "分析已取消" :
    task.state === "QUEUED" ? "等待执行已保存的分析任务" : task.state === "PLANNING" ? "LLM 正在根据目录制定分析计划" : stage === 3 ? `正在汇总全部 ${p?.tasks.length || ""} 份记忆${p?.synthesisAttempts ? ` · 第 ${p.synthesisAttempts} 次归纳调用` : ""}` :
      current ? `${current.task.title} · ${taskLabel(current)}` : "正在准备仓库目录";
  return <div className="bt-analysis-status" aria-live="polite">
    <Steps size="small" current={stage} status={task.state === "FAILED" ? "error" : task.state === "READY" ? "finish" : "process"}
      items={[{ title: "读取目录" }, { title: "LLM 规划" }, { title: "分项分析" }, { title: "总结全部记忆" }, { title: "背景就绪" }]} />
    <div className="bt-analysis-current"><strong>{message}</strong><span>{task.state === "READY" ? task.model : task.request.model || "默认模型"} · 最近进展 {new Date(task.updatedAt).toLocaleString()}</span></div>
    {!!current?.pendingFiles?.length && task.state === "ANALYZING" && <p className="bt-analysis-path">正在读取：{current.pendingFiles.join("、")}</p>}
    <div className="bt-analysis-metrics">
      <div><span>已保存记忆</span><strong>{completed} / {p?.tasks.length ?? "—"}</strong></div>
      <div><span>计划覆盖模块</span><strong>{p ? new Set(p.tasks.flatMap(t => t.task.modulePaths)).size : "待规划"}</strong></div>
      <div><span>已读取文件</span><strong>{Math.max(task.sources.length, new Set(p?.tasks.flatMap(t => t.sourceFiles || []) || []).size)}</strong></div>
      <div><span>最终总结</span><strong>{task.state === "READY" ? "已完成" : "未完成"}</strong></div>
    </div>
  </div>;
}

export default function RepositoryAnalysisProgress({ task }: { task: RepositoryAnalysis }) {
  const [selected, setSelected] = useState<string | null>(null);
  const p = task.progress;
  if (!p) return <Empty description={task.state === "PLANNING" ? "模型正在规划分析目标、模块分工与执行顺序，计划产生后自动显示。" : "启动分析后，LLM 的计划和任务进度会显示在这里。"} />;
  const entry = p.tasks.find(t => t.task.id === (selected || p.currentTaskId)) || p.tasks.find(t => t.state === "FAILED") || p.tasks[0];
  const title = (id: string) => p.tasks.find(t => t.task.id === id)?.task.title || id;
  return <>
    <div className="bt-analysis-plan-heading"><div><h3>LLM 分析计划</h3><p>{p.summary}</p></div>
      {p.currentTaskId && <Button size="small" onClick={() => setSelected(null)}>{selected ? "跟随当前任务" : "正在跟随当前任务"}</Button>}</div>
    <div className="bt-analysis-workspace">
      <nav aria-label="分析计划任务" className="bt-analysis-task-list">{p.tasks.map((t, i) => <button type="button" key={t.task.id}
        className={`bt-analysis-task ${t.task.id === entry?.task.id ? "is-selected" : ""}`} aria-pressed={t.task.id === entry?.task.id} onClick={() => setSelected(t.task.id)}>
        <div className="bt-analysis-task-top"><strong>{i + 1}. {t.task.title}</strong><Tag color={t.state === "FAILED" ? "red" : t.state === "COMPLETED" ? "green" : "blue"}>{taskLabel(t)}</Tag></div>
        <p>{t.task.objective}</p>{t.task.configurationObjective && <p>配置管理：{t.task.configurationObjective}</p>}<small>{t.task.modulePaths.length} 个模块 · {t.sourceCount} 份文件 · {t.attempts} 次模型调用</small>
        {!!t.task.dependsOn.length && <small>依赖：{t.task.dependsOn.map(title).join("、")}</small>}
        {t.state === "COMPLETED" && !t.sourceCount && <Tag color="orange">缺少直接源码证据</Tag>}
      </button>)}</nav>
      {entry && <article className="bt-analysis-detail" key={entry.task.id}>
        <h3>{entry.task.title}</h3><p>{entry.task.objective}</p>{entry.task.configurationObjective && <p><strong>配置管理取证目标：</strong>{entry.task.configurationObjective}</p>}<Space wrap>{entry.task.modulePaths.map(m => <Tag key={m}>{m}</Tag>)}</Space>
        {entry.error && entry.state !== "COMPLETED" && <Alert type={entry.state === "FAILED" ? "error" : "warning"} showIcon message={entry.state === "FAILED" ? entry.error : "正在根据上次失败反馈继续"} description={entry.state === "FAILED" ? undefined : entry.error} />}
        <Tabs defaultActiveKey={entry.memory ? "memory" : "activity"} items={[
          { key: "activity", label: "执行记录", children: <>
            <p>{taskLabel(entry)} · {entry.rounds} 轮取证与归纳 · {entry.attempts} 次模型调用</p>
            {entry.toolFeedback && entry.state !== "COMPLETED" && <Alert type="info" message={entry.toolFeedback} />}
            {entry.toolHistory?.length ? <Timeline items={entry.toolHistory.map((event, i) => ({ key: i, color: event.rejected ? "red" : "blue", children: <div>
              <strong>{event.rejected ? "工具请求校验未通过（未执行）" : event.action === "READ" ? "读取源码文件" : "查询目录"}</strong>{event.at && <small> · {new Date(event.at).toLocaleTimeString()}</small>}
              {event.rejected ? <ul>{event.issues?.map((issue, j) => <li key={j}>{issue}</li>)}</ul> : event.action === "READ" ? <ul>{event.files?.map(f => <li className="bt-analysis-path" key={f}>{f}</li>)}</ul> : <p className="bt-analysis-path">{event.prefix || "仓库根目录"}{event.query ? ` · 搜索 ${event.query}` : ""} · {event.total ?? 0} 个匹配文件 · 偏移 {event.offset ?? 0}</p>}
            </div> }))} /> : <Empty description="暂无逐次操作记录。旧任务未记录的历史不会补造，可在“读取文件”中查看已有结果。" />}
          </> },
          { key: "memory", label: "分项记忆", children: entry.memory ? <>
            <p className="bt-repository-summary">{entry.memory.summary}</p>
            {entry.memory.modules.map(m => <p key={m.modulePath}><strong>{m.modulePath}</strong><br />{m.role}</p>)}
            {entry.memory.rules.map((r, i) => <div key={i}><h4>{r.title}</h4><p>{r.description}</p></div>)}
            {!!entry.memory.questions.length && <Alert type="warning" message="待确认信息" description={<ul>{entry.memory.questions.map((q, i) => <li key={i}>{q}</li>)}</ul>} />}
          </> : <Empty description="此项尚未保存记忆，完成后可在这里查看结论和待确认信息。" /> },
          { key: "sources", label: `读取文件 (${entry.sourceCount})`, children: entry.sourceFiles?.length ? <ul>{entry.sourceFiles.map(f => <li key={f} className="bt-analysis-path">{f}</li>)}</ul> : <Empty description="此项尚无已读取文件" /> },
        ]} />
      </article>}
    </div>
  </>;
}
