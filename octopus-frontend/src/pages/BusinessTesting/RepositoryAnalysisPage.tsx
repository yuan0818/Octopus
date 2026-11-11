import { useEffect, useState } from "react";
import { Alert, Button, Select } from "antd";
import { queryApi } from "../../api/query";
import { projectContextApi, type ContextScope } from "../../api/projectContext";
import type { RepositoryScope } from "../../types";
import { EmptyState, Field } from "./FormControls";
import { errorText } from "./workbenchModel";
import RepositoryAnalysisPanel from "./RepositoryAnalysisPanel";

export default function RepositoryAnalysisPage({ scope, environment, active, onScopeChange, onEnvironmentChange, onContext }: {
  scope: ContextScope; environment: string; active: boolean; onScopeChange: (scope: ContextScope) => void;
  onEnvironmentChange: (value: string) => void; onContext: () => void;
}) {
  const [repositories, setRepositories] = useState<RepositoryScope[]>([]);
  const [environments, setEnvironments] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  useEffect(() => {
    if (!active) return;
    let alive = true; setError("");
    void Promise.all([queryApi.repositoryScopes(), scope.repositoryKey ? projectContextApi.environments(scope.repositoryKey) : Promise.resolve([])])
      .then(([repos, envs]) => { if (alive) { setRepositories(repos.data); setEnvironments(envs); } })
      .catch(e => { if (alive) setError(errorText(e)); });
    return () => { alive = false; };
  }, [active, scope.repositoryKey, reload]);
  return <section className="bt-panel bt-analysis-page">
    <div className="bt-analysis-scope">
      <Field label="分析仓库"><Select aria-label="分析仓库" showSearch optionFilterProp="label" value={scope.repositoryKey || undefined}
        placeholder="选择已分析的仓库" options={repositories.map(r => ({ value: r.key, label: r.name }))}
        onChange={repositoryKey => onScopeChange({ repositoryKey, branch: "", commitId: "" })} /></Field>
      <Field label="背景所属环境"><Select aria-label="背景所属环境" value={environment}
        options={[...new Set([environment, ...environments])].map(value => ({ value }))} onChange={onEnvironmentChange} /></Field>
      <Button onClick={onContext}>维护业务与环境信息</Button>
    </div>
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    {!scope.repositoryKey ? <EmptyState title="从一个仓库开始" description="选择仓库和代码版本，让 AI 制定计划、逐项分析，最终形成可用于步骤补全的仓库背景。" />
      : <RepositoryAnalysisPanel scope={scope} environment={environment} active={active} onChange={onScopeChange} />}
  </section>;
}
