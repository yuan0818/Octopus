import { useEffect, useState } from "react";
import { useSearchParams, Link } from "react-router-dom";
import { Alert, Button, Drawer, Empty, Input, Select, Space, Table, Tag } from "antd";
import { HistoryOutlined, ReloadOutlined } from "@ant-design/icons";
import { queryApi } from "../../api/query";
import { projectContextApi as api, type RepositoryTaskSummary } from "../../api/projectContext";
import type { RepositoryScope } from "../../types";
import { Field } from "../BusinessTesting/FormControls";
import { errorText } from "../BusinessTesting/workbenchModel";
import { backgroundAnalysisPath } from "../BusinessTesting/navigationModel";
import TaskDetail from "./TaskDetail";
import BackgroundNavigation from "./Navigation";
import { moduleOptions, scopeBranches } from "./scopeModel";
import "../BusinessTesting/workbench.css";
import "./background.css";
const states: Record<string, string> = { QUEUED:"等待分析", COLLECTING:"读取目录", PLANNING:"制定计划", ANALYZING:"逐项分析", SYNTHESIZING:"汇总记忆", SUMMARIZING:"汇总记忆", READY:"已完成", FAILED:"失败待恢复", CANCELLED:"已取消" };
const running = (state?: string) => !!state && ["QUEUED","COLLECTING","PLANNING","ANALYZING","SYNTHESIZING","SUMMARIZING"].includes(state);
export default function BackgroundTasksPage() {
  const [params, setParams] = useSearchParams(); const selected = params.get("task") || "";
  const [repos, setRepos] = useState<RepositoryScope[]>([]); const [catalogError, setCatalogError] = useState("");
  const [rows, setRows] = useState<RepositoryTaskSummary[]>([]); const [total, setTotal] = useState(0);
  const [error, setError] = useState(""); const [loading, setLoading] = useState(true); const [refresh, setRefresh] = useState(0);
  const page = Math.max(1, Math.floor(Number(params.get("page")) || 1));
  const repository = params.get("repository") || ""; const module = params.get("module") || "";
  const repo = repos.find(r => r.key === repository);
  useEffect(()=>{if(params.has("environment")){const next=new URLSearchParams(params);next.delete("environment");setParams(next,{replace:true});}},[params,setParams]);
  const filter = { repositoryKey: repository, modulePath: module, branch:params.get("branch") || "", commitId:params.get("commit") || "", state:params.get("state") || "", search:params.get("search") || "", page, pageSize:20 };
  const filterKey = JSON.stringify(filter);
  const change = (key: string, value?: string) => { const next = new URLSearchParams(params); if (value) next.set(key,value); else next.delete(key); next.delete("page"); next.delete("task");
    if (key === "repository") ["module","branch","commit","environment"].forEach(k => next.delete(k));
    if (key === "module") { next.delete("commit"); if (!scopeBranches(repo,value).includes(next.get("branch") || "")) next.delete("branch"); }
    if (key === "branch") next.delete("commit"); setParams(next); };
  useEffect(() => { let alive = true; void queryApi.repositoryScopes().then(r => { if (alive) { setRepos(r.data); setCatalogError(""); } }).catch(e => { if (alive) setCatalogError(errorText(e)); }); return () => { alive = false; }; }, [refresh]);
  useEffect(() => {
    let alive = true, inFlight = false; let timer: ReturnType<typeof setTimeout> | undefined;
    setRows([]); setLoading(true);
    const load = async () => { if (!alive || inFlight) return; inFlight = true; clearTimeout(timer);
      try { const result = await api.repositoryTasks(filter); if (alive) { setRows(result.items); setTotal(result.total); setError(""); if (result.items.some(r => running(r.state))) timer = setTimeout(load,5000); } }
      catch(e) { if (alive) { setError(errorText(e)); timer = setTimeout(load,10000); } } finally { inFlight = false; if (alive) setLoading(false); } };
    void load(); window.addEventListener("focus",load); return () => { alive = false; clearTimeout(timer); window.removeEventListener("focus",load); };
  }, [filterKey,refresh]);
  const open = (id: string) => { const next = new URLSearchParams(params); if (id) next.set("task",id); else next.delete("task"); setParams(next); };
  return <div className="bk-page">
    <header className="bk-heading"><div><span className="bk-eyebrow"><HistoryOutlined /> ANALYSIS HISTORY</span><h1>任务历史</h1><p>查看每次分析的目标、进度和结果，从检查点恢复当前失败任务。</p></div><Space><Button icon={<ReloadOutlined />} onClick={() => setRefresh(v=>v+1)}>刷新</Button><Link className="ant-btn" to={backgroundAnalysisPath(params.toString())}>前往背景分析</Link></Space></header>
    <BackgroundNavigation />
    <section className="bk-filters bk-history-filters" aria-label="分析任务筛选">
      <Field label="项目"><Select aria-label="历史项目" showSearch optionFilterProp="label" value={repository} options={[{value:"",label:"全部项目"},...repos.map(r=>({value:r.key,label:r.name}))]} onChange={v=>change("repository",v)} /></Field>
      <Field label="模块"><Select aria-label="历史模块" showSearch optionFilterProp="label" value={module} disabled={!repo} options={[{value:"",label:"全部模块"},...moduleOptions(repo)]} onChange={v=>change("module",v)} /></Field>
      <Field label="分支"><Select aria-label="历史分支" value={filter.branch} disabled={!repo} showSearch options={[{value:"",label:"全部分支"},...scopeBranches(repo,module).map(value=>({value}))]} onChange={v=>change("branch",v)} /></Field>
      <Field label="提交"><Input aria-label="历史提交" key={filter.commitId} defaultValue={filter.commitId} placeholder="完整提交号，回车查询" onPressEnter={e=>change("commit",e.currentTarget.value.trim())} allowClear onChange={e=>{if(!e.target.value)change("commit","");}} /></Field>
      <Field label="状态"><Select aria-label="历史状态" value={filter.state} options={[{value:"",label:"全部状态"},...Object.entries(states).filter(([key])=>key!=="SUMMARIZING").map(([value,label])=>({value,label}))]} onChange={v=>change("state",v)} /></Field>
      <Field label="搜索"><Input.Search aria-label="任务搜索" key={filter.search} defaultValue={filter.search} placeholder="分析目标、项目或提交" allowClear onSearch={v=>change("search",v)} /></Field>
      <Button onClick={()=>setParams(new URLSearchParams())}>查看全部历史</Button>
    </section>
    {catalogError && <Alert type="warning" showIcon message="项目目录加载失败" description={catalogError} />}
    {error && <Alert type="error" showIcon message={error} />}
    <p className="bk-muted">重新规划会产生新任务；检查点重试保留原任务。历史保存从本次升级开始，旧系统已覆盖的记录无法补回。</p>
    <Table<RepositoryTaskSummary> className="bk-task-table" rowKey="taskId" loading={loading} dataSource={rows} scroll={{x:980}} locale={{emptyText:<Empty description="当前范围没有分析任务" />}}
      pagination={{current:page,pageSize:20,total,showSizeChanger:false,showTotal:n=>`${n} 条任务`,onChange:n=>{const next=new URLSearchParams(params);next.set("page",String(n));setParams(next);}}}
      columns={[
        {title:"项目与分析目标",key:"goal",width:310,render:(_,r)=><><Button type="link" className="bk-task-title" onClick={()=>open(r.taskId)}>{r.repositoryName || repos.find(p=>p.key===r.repositoryKey)?.name || "仓库分析"}</Button><p className="bk-task-goal">{r.goal || "归纳仓库背景"}</p><Tag>{r.current?"当前任务":"历史快照"}</Tag></>},
        {title:"代码版本",key:"scope",width:190,render:(_,r)=><><div>{r.branch} · {r.commitId.slice(0,8)}</div><span className="bk-muted">{r.modulePaths.length} 个模块</span></>},
        {title:"状态与进度",key:"state",width:200,render:(_,r)=><><Tag color={r.state==="READY"?"green":r.state==="FAILED"?"red":running(r.state)?"blue":"default"}>{states[r.state]}</Tag><div>{r.total?`${r.completed} / ${r.total} 项完成`:r.state==="READY"?"报告已完成（无分项记录）":"尚未形成计划"}</div>{r.currentTask && <small>{r.currentTask}</small>}{r.failure && <p className="bk-task-goal">{r.failure.message}</p>}</>},
        {title:"模型 / 最近更新",key:"time",width:190,render:(_,r)=><><div>{r.model || "默认模型"}</div><small>{new Date(r.updatedAt).toLocaleString()}</small></>},
        {title:"操作",key:"action",width:100,render:(_,r)=><Button onClick={()=>open(r.taskId)}>{r.current && r.state==="FAILED"?"查看并恢复":"查看详情"}</Button>},
      ]} />
    <Drawer title="分析任务详情" open={!!selected} width="min(1100px, 100vw)" destroyOnClose onClose={()=>open("")}>{selected && <TaskDetail key={selected} id={selected} onUpdated={()=>setRefresh(v=>v+1)} />}</Drawer>
  </div>;
}
