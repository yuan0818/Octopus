import { useEffect, useState } from "react";
import { Alert, Select } from "antd";
import { queryApi } from "../../api/query";
import type { RepositoryScope, RepositoryVersion } from "../../types";
import type { ContextScope } from "../../api/projectContext";
import { Field } from "./FormControls";
import { errorText } from "./workbenchModel";

export default function ProjectScopeFields({ value, onChange, withVersion = false, disabled = false }: {
  value: ContextScope; onChange: (value: ContextScope) => void; withVersion?: boolean; disabled?: boolean;
}) {
  const [scopes, setScopes] = useState<RepositoryScope[]>([]);
  const [versions, setVersions] = useState<RepositoryVersion[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  useEffect(() => { let alive = true; void queryApi.repositoryScopes().then(r => { if (alive) setScopes(r.data); })
    .catch(e => { if (alive) setError(errorText(e)); }); return () => { alive = false; }; }, []);
  const scope = scopes.find(s => s.key === value.repositoryKey);
  const paths = [...new Set(scope?.modules.filter(m => !withVersion || !value.branch || m.branches.includes(value.branch)).map(m => m.modulePath) || [])].sort();
  const branches = [...new Set(scope?.modules.flatMap(m => m.branches) || [])].sort();
  const modulesKey = value.modulePaths?.join("\n") || "";
  useEffect(() => {
    let alive = true; setVersions([]); setLoading(false);
    if (withVersion && scope && value.branch) {
      setLoading(true); setError("");
      const ids = value.modulePaths?.length ? scope.modules.filter(m => value.modulePaths!.includes(m.modulePath) && m.branches.includes(value.branch)).map(m => m.projectId) : undefined;
      void queryApi.repositoryVersions(scope.key, value.branch, ids).then(r => { if (alive) setVersions(r.data); })
        .catch(e => { if (alive) setError(errorText(e)); }).finally(() => { if (alive) setLoading(false); });
    }
    return () => { alive = false; };
  }, [scope, value.branch, modulesKey, withVersion]);
  return <div className="bt-form-grid">
    <Field label="项目"><Select showSearch optionFilterProp="label" disabled={disabled} value={value.repositoryKey || undefined}
      placeholder="选择已分析的项目" options={scopes.map(s => ({ value: s.key, label: s.name }))}
      onChange={repositoryKey => onChange({ repositoryKey, branch: "", commitId: "" })} /></Field>
    <Field label={withVersion ? "参与生成的模块" : "配置层级"} hint={withVersion ? "留空时使用当前项目的全部模块" : "项目默认值会被模块继承，模块可以覆盖"}>
      {withVersion ? <Select mode="multiple" allowClear showSearch optionFilterProp="label" disabled={!scope || disabled}
        value={value.modulePaths || []} placeholder="全部模块" options={paths.map(p => ({ value: p, label: p === "." ? "根目录源码" : p }))}
        onChange={modulePaths => onChange({ ...value, modulePaths: modulePaths.length ? modulePaths : undefined, commitId: "" })} />
        : <Select showSearch optionFilterProp="label" disabled={!scope || disabled} value={value.modulePaths?.[0] || ""}
          options={[{ value: "", label: "整个项目 · 默认配置" }, ...paths.map(p => ({ value: p, label: p === "." ? "根目录源码模块" : p }))]}
          onChange={modulePath => onChange({ ...value, modulePaths: modulePath ? [modulePath] : undefined })} />}
    </Field>
    {withVersion && <>
      <Field label="分支"><Select disabled={!scope || disabled} value={value.branch || undefined} placeholder="选择已分析分支"
        options={branches.map(value => ({ value }))} onChange={branch => {
          const kept = value.modulePaths?.filter(path => scope?.modules.some(m => m.modulePath === path && m.branches.includes(branch)));
          onChange({ ...value, branch, modulePaths: kept?.length ? kept : undefined, commitId: "" });
        }} /></Field>
      <Field label="提交"><Select showSearch optionFilterProp="label" disabled={!versions.length || disabled} loading={loading} value={value.commitId || undefined}
        placeholder={value.branch && !loading ? "请选择已分析提交" : "先选择分支"}
        options={versions.map(v => ({ value: v.commitId, label: `${v.commitId.slice(0, 8)} · ${v.projectIds.length} 个模块已分析${v.missingProjectIds.length ? ` / ${v.missingProjectIds.length} 个缺失` : ""}` }))}
        onChange={commitId => onChange({ ...value, commitId })} /></Field>
    </>}
    {error && <Alert style={{ gridColumn: "1 / -1" }} type="error" showIcon message={error} />}
  </div>;
}
