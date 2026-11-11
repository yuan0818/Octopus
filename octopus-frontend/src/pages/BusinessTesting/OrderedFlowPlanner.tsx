import { useState } from "react";
import { Alert, Button, Drawer, Input, Select, Space, Tag } from "antd";
import { businessTesting, type Flow, type Operation, type OrderedPlanCandidate } from "../../api/businessTesting";
import { Field } from "./FormControls";
import { errorText, refOf } from "./workbenchModel";

export default function OrderedFlowPlanner({ operations, onApply }: { operations:Operation[];onApply:(flow:Flow)=>void }) {
  const [open,setOpen]=useState(false),[goal,setGoal]=useState(""),[refs,setRefs]=useState<string[]>([]);
  const [busy,setBusy]=useState(false),[error,setError]=useState(""),[candidate,setCandidate]=useState<OrderedPlanCandidate|null>(null);
  return <>
    <Button onClick={()=>setOpen(true)}>自然语言规划流程</Button>
    <Drawer title="从业务目标规划流程" open={open} width={720} onClose={()=>setOpen(false)} maskClosable={!busy}
      footer={<Space><Button onClick={()=>setOpen(false)}>关闭</Button>
        <Button type="primary" loading={busy} disabled={!goal.trim()||!refs.length} onClick={async()=>{
          setError("");setCandidate(null);setBusy(true);
          try {setCandidate(await businessTesting.planOrdered({goal,operationRefs:refs}));}catch(e){setError(errorText(e));}finally{setBusy(false);}
        }}>生成步骤草稿</Button>
        {candidate && <Button disabled={busy||!candidate.flow.nodes.length} onClick={()=>{onApply(candidate.flow);setOpen(false);}}>应用草稿并配置参数</Button>}
      </Space>}>
      <Field label="业务目标"><Input.TextArea rows={4} maxLength={12000} value={goal} disabled={busy}
        placeholder="例如：登录后创建订单，再独立查询订单和支付状态" onChange={e=>{setGoal(e.target.value);setCandidate(null);}} /></Field>
      <Field label="候选 API 范围" hint="模型只能从所选版本中选择接口；参数和业务预期仍需补全与审阅。">
        <Select mode="multiple" showSearch optionFilterProp="label" maxCount={300} value={refs} disabled={busy}
          options={operations.map(o=>({value:refOf(o),label:`${o.system}/${o.service} · ${o.method} ${o.path} · ${o.revision}`}))}
          onChange={v=>{setRefs(v);setCandidate(null);}} style={{width:"100%"}} />
      </Field>
      {error && <Alert type="error" message={error} />}
      {candidate && <>
        <p>已检索 {candidate.searchedOperationRefs.length} 个固定 API 版本 · {candidate.model}</p>
        {candidate.plan.steps.map((s,i)=><section className="bt-param" key={s.id}><strong>{i+1}. {s.label}</strong> <Tag>{s.mode==="PARALLEL"?"并行":"串行"}</Tag>
          <p>{s.reason}</p>{s.operationRefs.map((r,j)=><p key={j}>{r}</p>)}</section>)}
        {candidate.plan.assumptions.map((s,i)=><Alert key={`a${i}`} type="warning" message={`假设：${s}`} />)}
        {candidate.plan.questions.map((s,i)=><Alert key={`q${i}`} type="info" message={`需要补充：${s}`} />)}
      </>}
    </Drawer>
  </>;
}
