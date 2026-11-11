import { useState } from "react";
import { Alert, Button, Input, Modal, Select, Space, Tag } from "antd";
import { ArrowUpOutlined, ArrowDownOutlined } from "@ant-design/icons";
import { businessTesting, type Flow, type FlowStep } from "../../api/businessTesting";
import { replaceSteps } from "./orderedFlowModel";
import { errorText, uid } from "./workbenchModel";

export default function OrderedFlowSteps({ flow, onChange, onSelect, selected }: {
  flow: Flow; onChange: (flow: Flow) => void; onSelect: (id: string) => void; selected: string | null;
}) {
  const [busy,setBusy] = useState(false), [error,setError] = useState("");
  const [migration,setMigration] = useState<Awaited<ReturnType<typeof businessTesting.migrateOrdered>> | null>(null);
  const update = (steps: FlowStep[]) => onChange(replaceSteps(flow,steps));
  const moveStep = (index: number, offset: number) => {
    const steps = [...flow.steps!]; const [step] = steps.splice(index,1);steps.splice(index+offset,0,step);update(steps);
  };
  const moveCall = (id: string, target: string) => {
    const call = flow.nodes.find(c => c.id === id)!;
    const steps = flow.steps!.map(s => ({ ...s, calls:s.calls.filter(c => c.id !== id) }));
    if (target === "new") steps.push({ id:uid("step"),label:call.label,mode:"SERIAL",calls:[call] });
    else steps.find(s => s.id === target)!.calls.push(call);
    update(steps.filter(s => s.calls.length));
  };
  if (!flow.steps) return <div style={{padding:24}}>
    <Alert type="info" showIcon message="历史流程保留原执行规则" description="迁移预览会显示原测试点的执行顺序，保存并确认新版本后使用有序步骤。" />
    <Button loading={busy} onClick={async () => {
      setBusy(true);setError("");
      try { setMigration(await businessTesting.migrateOrdered(flow,flow.testPoints[0]?.id)); }
      catch(e) { setError(errorText(e)); } finally { setBusy(false); }
    }}>预览迁移为单向流程</Button>
    {error && <Alert type="error" message={error} />}
    {flow.nodes.map((c,i) => <div className="bt-param" key={c.id}><Button onClick={() => onSelect(c.id)}>{i+1}. {c.label}</Button></div>)}
    <Modal open={!!migration} title="迁移预览" okText="应用到草稿" onCancel={() => setMigration(null)} onOk={() => { if(migration) onChange(migration.flow);setMigration(null); }}>
      {migration?.warnings.map(w => <p key={w}>{w}</p>)}
      <ol>{migration?.executionOrder.map(id => <li key={id}>{flow.nodes.find(n => n.id === id)?.label}</li>)}</ol>
    </Modal>
  </div>;
  return <div style={{padding:24,display:"grid",gap:16}} aria-label="有序业务步骤">
    <p className="bt-muted">步骤按顺序执行；每步可串行或并行调用多个接口。并行调用最多同时执行 4 个，全部通过后进入下一步。</p>
    {flow.steps.map((step,index) => <section className="bt-param" key={step.id}>
      <Space wrap style={{marginBottom:12}}>
        <Tag>{index+1}</Tag>
        <Input aria-label={`步骤 ${index+1} 名称`} value={step.label} style={{width:260}} onChange={e => update(flow.steps!.map(s => s.id === step.id ? {...s,label:e.target.value} : s))} />
        <Select aria-label={`步骤 ${index+1} 调用模式`} value={step.mode} options={[{value:"SERIAL",label:"串行调用"},{value:"PARALLEL",label:"并行调用"}]}
          onChange={mode => update(flow.steps!.map(s => s.id === step.id ? {...s,mode} : s))} />
        <Button aria-label={`上移步骤 ${index+1}`} icon={<ArrowUpOutlined />} disabled={index===0} onClick={() => moveStep(index,-1)} />
        <Button aria-label={`下移步骤 ${index+1}`} icon={<ArrowDownOutlined />} disabled={index===flow.steps!.length-1} onClick={() => moveStep(index,1)} />
      </Space>
      {(step.assertions||[]).map(check=><Space key={check.id} style={{marginBottom:8}}>
        <Select aria-label="比较左侧输出" placeholder="左侧输出" style={{width:220}} value={check.leftKey||undefined}
          options={[...Object.keys(flow.initialContext),...flow.steps!.slice(0,index+1).flatMap(s=>s.calls.flatMap(c=>c.outputs.map(o=>o.key)))].map(value=>({value,label:value}))}
          onChange={leftKey=>update(flow.steps!.map(s=>s.id===step.id?{...s,assertions:(s.assertions||[]).map(a=>a.id===check.id?{...a,leftKey}:a)}:s))} />
        <span>等于</span>
        <Select aria-label="比较右侧输出" placeholder="右侧输出" style={{width:220}} value={check.rightKey||undefined}
          options={[...Object.keys(flow.initialContext),...flow.steps!.slice(0,index+1).flatMap(s=>s.calls.flatMap(c=>c.outputs.map(o=>o.key)))].map(value=>({value,label:value}))}
          onChange={rightKey=>update(flow.steps!.map(s=>s.id===step.id?{...s,assertions:(s.assertions||[]).map(a=>a.id===check.id?{...a,rightKey}:a)}:s))} />
        <Button onClick={()=>update(flow.steps!.map(s=>s.id===step.id?{...s,assertions:(s.assertions||[]).filter(a=>a.id!==check.id)}:s))}>移除比较</Button>
      </Space>)}
      <Button size="small" onClick={()=>update(flow.steps!.map(s=>s.id===step.id?{...s,assertions:[...(s.assertions||[]),{id:uid("check"),leftKey:"",rightKey:""}]}:s))}>添加跨调用结果比较</Button>
      {step.calls.map((call,callIndex) => <div key={call.id} style={{display:"flex",gap:12,margin:"8px 0",flexWrap:"wrap"}}>
        <Button type={selected===call.id?"primary":"default"} onClick={() => onSelect(call.id)}>{index+1}.{callIndex+1} {call.label}</Button>
        <Button aria-label={`上移调用 ${call.label}`} size="small" disabled={callIndex===0} onClick={() => {
          const calls=[...step.calls];[calls[callIndex-1],calls[callIndex]]=[calls[callIndex],calls[callIndex-1]];
          update(flow.steps!.map(s => s.id===step.id?{...s,calls}:s));
        }}>上移</Button>
        <Select aria-label={`移动调用 ${call.label}`} placeholder="移入其他步骤" style={{minWidth:180}} value={undefined}
          options={[...flow.steps!.filter(s => s.id!==step.id).map(s => ({value:s.id,label:s.label})),{value:"new",label:"拆为新步骤"}]}
          onChange={(value: string) => moveCall(call.id,value)} />
      </div>)}
    </section>)}
  </div>;
}
