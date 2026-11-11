import { useEffect, useState } from "react";
import { Alert, Button, Select, Space } from "antd";
import { queryApi } from "../../api/query";
import { projectContextApi as api, type EnvironmentVariable } from "../../api/projectContext";
import type { Flow, Operation } from "../../api/businessTesting";
import { environmentOptions } from "./environmentModel";
import { errorText, refOf } from "./workbenchModel";

export default function EnvironmentVariablePicker({ flow, operations, disabled, onChange }: {
  flow: Flow; operations: Operation[]; disabled: boolean; onChange: (flow: Flow) => void;
}) {
  const [environment, setEnvironment] = useState("test");
  const [environments, setEnvironments] = useState<string[]>([]);
  const [variables, setVariables] = useState<EnvironmentVariable[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [error, setError] = useState(""); const [conflicts, setConflicts] = useState<string[]>([]);
  const [loading, setLoading] = useState(false); const [revision, setRevision] = useState(0);
  const projects = [...new Set(operations.filter(o => flow.nodes.some(n => n.operationRef === refOf(o))).flatMap(o => o.implementations.map(i => i.projectId)))].sort((a,b)=>a-b);
  const projectKey = JSON.stringify(projects);
  useEffect(() => {
    let active = true; setVariables([]); setSelected([]); setConflicts([]); setError(""); setLoading(false);
    if (projects.length) {
      setLoading(true);
      void queryApi.repositoryScopes().then(async result => {
        const scopes = result.data.filter(s => s.modules.some(m => projects.includes(m.projectId)));
        const names = await Promise.all(scopes.map(s => api.environments(s.key)));
        const unique = new Map(scopes.flatMap(s => s.modules.filter(m => projects.includes(m.projectId)).map(m => [JSON.stringify([s.key,m.modulePath]), {key:s.key,module:m.modulePath}] as const)));
        const contexts = await Promise.all([...unique.values()].map(s => api.get(s.key, s.module, environment)));
        if (!active) return;
        setEnvironments(names.flat());
        const candidates = new Map<string, EnvironmentVariable>(); const ambiguous = new Set<string>();
        contexts.forEach(c => (c.effective.variables || []).forEach(v => {
          const existing = candidates.get(v.key);
          if (existing && (existing.type !== v.type || existing.value !== v.value)) ambiguous.add(v.key);
          candidates.set(v.key, v);
        }));
        setConflicts([...ambiguous]); setVariables([...candidates.values()].filter(v => !ambiguous.has(v.key)));
      }).catch(e => { if (active) setError(errorText(e)); }).finally(() => { if (active) setLoading(false); });
    }
    return () => { active = false; };
  }, [projectKey, environment, revision]);
  const unavailable = (v: EnvironmentVariable) => !!flow.initialContext[v.key] || flow.nodes.some(n => n.outputs.some(o => o.key === v.key));
  return <div className="bt-param">
    <strong>从环境变量添加引用</strong>
    <p className="bt-muted">仅添加变量名称和类型，不把值固定在流程中。运行时点击“加载环境配置与变量”读取所选环境的值。</p>
    <Space wrap><Select aria-label="变量来源环境" disabled={disabled || loading} value={environment} options={environmentOptions(environments)} onChange={setEnvironment} />
      <Button disabled={disabled} loading={loading} onClick={()=>setRevision(v=>v+1)}>刷新变量</Button></Space>
    <Select aria-label="可引用的环境变量" mode="multiple" style={{width:"100%",margin:"12px 0"}} disabled={disabled || loading} loading={loading} value={selected}
      placeholder={projects.length ? "选择要在步骤中引用的 key" : "先添加已关联项目的 API 步骤"} options={variables.map(v=>({value:v.key,label:`${v.key} · ${v.type}${unavailable(v)?"（流程已声明或与输出同名）":""}`,disabled:unavailable(v)}))} onChange={setSelected} />
    <Button disabled={disabled || loading || !selected.length} onClick={()=>{
      const additions=variables.filter(v=>selected.includes(v.key) && !unavailable(v));
      onChange({...flow,initialContext:{...flow.initialContext,...Object.fromEntries(additions.map(v=>[v.key,{type:v.type,sensitive:false}]))}});setSelected([]);
    }}>添加到预置上下文</Button>
    {!!conflicts.length && <Alert type="warning" showIcon message="同名变量存在冲突，暂不可添加" description={conflicts.join("、")} />}
    {error && <Alert type="error" showIcon message={error} />}
    {!loading && !error && !!projects.length && !variables.length && <p className="bt-muted">此范围暂无可引用变量，请先在“环境变量”页面保存配置。</p>}
  </div>;
}
