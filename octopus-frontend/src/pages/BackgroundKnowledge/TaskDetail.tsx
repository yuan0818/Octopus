import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Alert, Button, Collapse, Empty, Space, Spin, Tabs, Tag } from "antd";
import { projectContextApi as api, type RepositoryTaskDetail } from "../../api/projectContext";
import { errorText } from "../BusinessTesting/workbenchModel";
import { backgroundKnowledgePath } from "../BusinessTesting/navigationModel";
import RepositoryAnalysisProgress, { RepositoryAnalysisStatus } from "../BusinessTesting/RepositoryAnalysisProgress";
import ChainFailureNotice from "../BusinessTesting/ChainFailureNotice";
const running = (state?: string) => !!state && ["QUEUED","COLLECTING","PLANNING","ANALYZING","SYNTHESIZING","SUMMARIZING"].includes(state);
export default function TaskDetail({id,onUpdated,initialTab,refreshToken=0}:{id:string;onUpdated:()=>void;initialTab?:string;refreshToken?:number}) {
  const [data,setData]=useState<RepositoryTaskDetail|null>(null); const [error,setError]=useState(""); const [busy,setBusy]=useState(false); const [revision,setRevision]=useState(0); const alive=useRef(true);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  useEffect(()=>{let live=true;let timer:ReturnType<typeof setTimeout>|undefined;const load=async()=>{try {const value=await api.repositoryTask(id);if(live){setData(value);setError("");if(value.current && running(value.analysis.state))timer=setTimeout(load,4000);}}catch(e){if(live){setError(errorText(e));timer=setTimeout(load,10000);}}};void load();return()=>{live=false;clearTimeout(timer);};},[id,revision,refreshToken]);
  const act=async(action:"resume"|"cancel"|"adopt")=>{setBusy(true);setError("");try {if(action==="adopt")await api.adoptRepositoryTask(id);else await api.repositoryTaskAction(id,action);if(alive.current){setRevision(v=>v+1);onUpdated();}}catch(e){if(alive.current)setError(errorText(e));}finally{if(alive.current)setBusy(false);}};
  if(!data)return error?<Alert type="error" message={error} action={<Button onClick={()=>setRevision(v=>v+1)}>重试</Button>} />:<Spin />;
  const task=data.analysis;
  return <div className="bk-task-detail">
    <h2>{task.request.goal || "归纳仓库背景"}</h2><Space wrap><Tag>{task.request.branch} · {task.request.commitId.slice(0,8)}</Tag><Tag>{data.current?"当前任务":"历史任务"}</Tag>{data.adopted && <Tag color="green">当前已采用背景</Tag>}</Space>
    <p className="bk-muted">创建于 {new Date(task.createdAt).toLocaleString()} · 更新于 {new Date(task.updatedAt).toLocaleString()}</p>
    {!data.current && <Alert type="info" showIcon message="历史结果保留原始内容，已完成的结论可以采用为背景；恢复和取消仅适用于当前任务。" />}
    {task.state==="READY" && <Alert type={data.adopted?"success":"info"} showIcon message={data.adopted?`此结论已采用 · ${new Date(data.adoptedAt!).toLocaleString()}`:"此结论尚未采用，背景查询和测试补全继续使用已采用的结论。"} />}
    <Space wrap>{task.state==="READY" && !data.adopted && <Button type="primary" loading={busy} onClick={()=>void act("adopt")}>采用为背景</Button>}<Link to={backgroundKnowledgePath(new URLSearchParams({repository:task.request.repositoryKey,branch:task.request.branch,commit:task.request.commitId}).toString())}>查询此版本背景</Link><Button onClick={()=>setRevision(v=>v+1)}>刷新详情</Button>{data.current && task.state==="FAILED" && <Button type="primary" loading={busy} onClick={()=>void act("resume")}>从检查点继续</Button>}{data.current && running(task.state) && <Button disabled={busy} onClick={()=>void act("cancel")}>取消分析</Button>}</Space>
    {error && <Alert type="error" showIcon message={error} />}
    <RepositoryAnalysisStatus task={task} />{task.failure && <ChainFailureNotice failure={task.failure} />}
    <Tabs defaultActiveKey={initialTab || (task.state==="READY"?"report":"plan")} items={[
      {key:"plan",label:"分析计划与进度",children:<RepositoryAnalysisProgress task={task} />},
      {key:"report",label:"归纳报告",children:task.report?<><p className="bk-summary">{task.report.summary}</p><Collapse items={task.report.rules.map((r,i)=>({key:i,label:r.title,children:<><p>{r.description}</p><p>涉及模块：{r.modulePaths.join("、")}</p>{!!r.clientPreparation.length && <ul>{r.clientPreparation.map((v,j)=><li key={j}>{v}</li>)}</ul>}{!!r.uncertainties.length && <Alert type="warning" message={r.uncertainties.join("；")} />}<p className="bk-muted">源码依据：{r.evidenceIds.map(id=>task.sources.find(s=>s.id===id)?.filePath).filter(Boolean).join("、")}</p></>}))} /></>:<Empty description="尚未形成完整归纳报告" />},
      {key:"coverage",label:"覆盖与待确认",children:<><ul>{task.warnings.map((v,i)=><li key={i}>{v}</li>)}</ul>{task.report?.modules.map(m=><p key={m.modulePath}><strong>{m.modulePath}</strong><br/>{m.role}</p>)}<ul>{task.report?.questions.map((v,i)=><li key={i}>{v}</li>)}</ul></>},
    ]} />
  </div>;
}
