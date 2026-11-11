import { useState } from "react";
import { Button, Checkbox, InputNumber, Select, Space } from "antd";
import type { Flow, Operation } from "../../api/businessTesting";
import { uid, refOf } from "./workbenchModel";

export default function CleanupEditor({flow,operations,onChange,onSelect}:{flow:Flow;operations:Operation[];onChange:(f:Flow)=>void;onSelect:(id:string)=>void}) {
  const [ref,setRef]=useState<string>();
  if(!flow.steps) return null;
  return <section className="bt-param" aria-label="固定收尾清单">
    <h3>固定收尾清单</h3><p>按列表顺序清理已验证属于本次运行的资源。取消或强制终止后未完成的清理会保留为未知，不自动重放。</p>
    {(flow.cleanup||[]).map((item,i)=><div key={item.call.id} style={{marginBottom:16}}>
      <strong>{i+1}. {item.call.label}</strong>
      <Space wrap>
        <Select aria-label={`清理 ${i+1} 资源标识`} value={item.resourceKey||undefined} placeholder="本次资源标识" style={{minWidth:180}}
          options={flow.nodes.flatMap(n=>n.outputs.map(o=>({value:o.key,label:`${n.label} · ${o.key}`})))}
          onChange={resourceKey=>onChange({...flow,cleanup:flow.cleanup!.map(c=>c===item?{...c,resourceKey}:c)})} />
        <Select aria-label={`清理 ${i+1} 适用结果`} mode="multiple" value={item.when} style={{minWidth:200}}
          options={[{value:"PASS",label:"业务通过"},{value:"FAIL",label:"业务失败"},{value:"INCONCLUSIVE",label:"业务结论不确定"}]}
          onChange={when=>onChange({...flow,cleanup:flow.cleanup!.map(c=>c===item?{...c,when}:c)})} />
        <InputNumber aria-label={`清理 ${i+1} 超时毫秒`} value={item.timeoutMillis} min={1} max={60000} addonAfter="ms"
          onChange={timeoutMillis=>onChange({...flow,cleanup:flow.cleanup!.map(c=>c===item?{...c,timeoutMillis:timeoutMillis||1}:c)})} />
        <Checkbox checked={item.required} onChange={e=>onChange({...flow,cleanup:flow.cleanup!.map(c=>c===item?{...c,required:e.target.checked}:c)})}>门禁必需</Checkbox>
        <Button onClick={()=>onSelect(item.call.id)}>配置参数和预期</Button>
        <Button danger onClick={()=>onChange({...flow,cleanup:flow.cleanup!.filter(c=>c!==item)})}>移除</Button>
      </Space>
    </div>)}
    <Space><Select showSearch optionFilterProp="label" value={ref} style={{width:380}} placeholder="选择清理 API 的固定版本"
      options={operations.map(o=>({value:refOf(o),label:`${o.method} ${o.path} · ${o.revision}`}))} onChange={setRef} />
      <Button disabled={!ref||(flow.cleanup?.length||0)>=30} onClick={()=>{
        const op=operations.find(o=>refOf(o)===ref)!;const id=uid("cleanup");
        const status=Object.keys(op.responseSchema||{}).find(s=>/^2[0-9]{2}$/.test(s));
        onChange({...flow,cleanup:[...(flow.cleanup||[]),{call:{id,label:`${op.method} ${op.path}`,operationRef:ref!,inputs:{},outputs:[],states:[],waitMillis:0},
          assertions:status?[{id:"status",kind:"STATUS_EQUALS",path:null,expected:Number(status)}]:[],resourceKey:"",when:["PASS","FAIL","INCONCLUSIVE"],required:true,timeoutMillis:30000}]});setRef(undefined);
      }}>添加收尾调用</Button></Space>
  </section>;
}
