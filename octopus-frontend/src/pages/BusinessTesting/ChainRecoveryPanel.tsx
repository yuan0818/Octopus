import { useEffect, useState } from "react";
import { Alert, Button, Drawer, Empty, List, Space, Spin, Tag } from "antd";
import { projectContextApi as api, type ChainTaskSummary, type FlowChain } from "../../api/projectContext";
import { errorText } from "./workbenchModel";
import { chainStateNames } from "./chainRecoveryModel";

export default function ChainRecoveryPanel({open,onClose,onRestore}:{open:boolean;onClose:()=>void;onRestore:(task:FlowChain)=>void}) {
  const [tasks,setTasks]=useState<ChainTaskSummary[]>([]),[loading,setLoading]=useState(false),[busy,setBusy]=useState("");
  const [error,setError]=useState(""),[attempt,setAttempt]=useState(0);
  useEffect(() => {
    if(!open)return;let alive=true;setLoading(true);setError("");
    void api.recoverableChains().then(value=>{if(alive)setTasks(value);}).catch(e=>{if(alive)setError(errorText(e));}).finally(()=>{if(alive)setLoading(false);});
    return()=>{alive=false;};
  },[open,attempt]);
  return <Drawer title="恢复 AI 补全任务" width={760} open={open} closable={!busy} maskClosable={!busy} keyboard={!busy} onClose={onClose}>
    <p className="bt-lead">任务检查点会持久保存。即使页面刷新或服务重启，也可找回未保存流程的补全任务；已确认结果可恢复到草稿。</p>
    <Space><Button disabled={!!busy} loading={loading} onClick={()=>setAttempt(n=>n+1)}>刷新任务列表</Button><span className="bt-muted">最近 50 个未取消任务</span></Space>
    {error && <Alert type="error" showIcon message={error} />}
    {loading ? <Spin /> : tasks.length ? <List dataSource={tasks} renderItem={task=><List.Item actions={[<Button key="restore" loading={busy===task.id} disabled={!!busy && busy!==task.id} onClick={async()=>{
      setBusy(task.id);setError("");try{const detail=await api.chain(task.id);onRestore(detail);}catch(e){setError(errorText(e));}finally{setBusy("");}
    }}>{task.state==="ACCEPTED"?"恢复结果到草稿":"查看并恢复任务"}</Button>]}><List.Item.Meta title={task.name} description={<>
      <Space wrap><Tag>{chainStateNames[task.state]}</Tag><span>{task.completed} / {task.total} 节点 · {task.environment}</span></Space>
      {task.failure && <p>{task.failure.message}</p>}<p className="bt-muted">最后保存 {new Date(task.updatedAt).toLocaleString()}</p>
    </>} /></List.Item>} /> : <Empty description="暂无可恢复的补全任务" />}
  </Drawer>;
}
