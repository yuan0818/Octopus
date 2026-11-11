import { Fragment, useEffect, useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Alert, Button, Empty, Select } from "antd";
import { BookOutlined } from "@ant-design/icons";
import { queryApi } from "../../api/query";
import type { ContextScope } from "../../api/projectContext";
import type { RepositoryScope, RepositoryVersion } from "../../types";
import { readScope, writeScope } from "../BusinessTesting/navigationModel";
import { Field } from "../BusinessTesting/FormControls";
import { errorText } from "../BusinessTesting/workbenchModel";
import BackgroundNavigation from "./Navigation";
import { moduleOptions, scopeBranches, scopeVersions } from "./scopeModel";
import "../BusinessTesting/workbench.css";
import "./background.css";

export default function BackgroundScopePage({ title, description, children }: { title: string; description: string; children: (scope: ContextScope) => ReactNode }) {
  const [params, setParams] = useSearchParams();
  const scope = readScope(params);
  useEffect(() => { if (params.has("environment")) { const next = new URLSearchParams(params); next.delete("environment"); setParams(next, { replace: true }); } }, [params, setParams]);
  const [repos, setRepos] = useState<RepositoryScope[]>([]);
  const [versions, setVersions] = useState<RepositoryVersion[]>([]);
  const [error, setError] = useState(""); const [reload, setReload] = useState(0);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const repo = repos.find(r => r.key === scope.repositoryKey);
  const modulePath = scope.modulePaths?.[0] || "";
  const branches = scopeBranches(repo, modulePath);
  const change = (value: ContextScope) => { const next = new URLSearchParams(); writeScope(next, value); setParams(next); };
  useEffect(() => { let alive = true; setCatalogLoading(true); setError(""); setVersions([]);
    void Promise.all([queryApi.repositoryScopes(), scope.repositoryKey && scope.branch ? queryApi.repositoryVersions(scope.repositoryKey, scope.branch) : Promise.resolve({ data: [] as RepositoryVersion[] })])
      .then(([r, v]) => { if (alive) { setRepos(r.data); setVersions(v.data); } }).catch(e => { if (alive) setError(errorText(e)); }).finally(() => { if (alive) setCatalogLoading(false); });
    return () => { alive = false; };
  }, [scope.repositoryKey, scope.branch, reload]);
  const filteredVersions = scopeVersions(versions, repo, modulePath, scope.branch);
  const complete = !!repo && !!scope.branch && branches.includes(scope.branch) && filteredVersions.some(v => v.commitId === scope.commitId);
  const sessionKey = JSON.stringify([scope.repositoryKey, modulePath, scope.branch, scope.commitId]);
  return <div className="bk-page">
    <header className="bk-heading"><div><span className="bk-eyebrow"><BookOutlined /> REPOSITORY KNOWLEDGE</span><h1>{title}</h1><p>{description}</p></div>
      <Link className="ant-btn" to={`/business-testing/context?${new URLSearchParams({ repository: scope.repositoryKey, module: modulePath, branch: scope.branch, commit: scope.commitId })}`}>维护环境变量</Link></header>
    <BackgroundNavigation />
    <section className="bk-filters" aria-label="背景范围">
      <Field label="项目"><Select aria-label="项目" showSearch optionFilterProp="label" value={scope.repositoryKey || undefined} placeholder="选择项目" loading={catalogLoading} options={repos.map(r => ({ value: r.key, label: r.name }))} onChange={repositoryKey => change({ repositoryKey, branch: "", commitId: "" })} /></Field>
      <Field label="模块"><Select aria-label="模块" disabled={!repo} showSearch optionFilterProp="label" value={modulePath} options={[{ value: "", label: "全部模块 · 仓库背景" }, ...moduleOptions(repo)]} onChange={m => { const valid = scopeBranches(repo, m).includes(scope.branch); change({ ...scope, modulePaths: m ? [m] : undefined, branch: valid ? scope.branch : "", commitId: "" }); }} /></Field>
      <Field label="分支"><Select aria-label="分支" disabled={!repo} value={scope.branch || undefined} placeholder="选择分支" showSearch options={branches.map(value => ({ value }))} onChange={branch => change({ ...scope, branch, commitId: "" })} /></Field>
      <Field label="提交"><Select aria-label="提交" disabled={!scope.branch} loading={catalogLoading} value={scope.commitId || undefined} placeholder="选择已分析提交" showSearch optionFilterProp="label" options={filteredVersions.map(v => ({ value: v.commitId, label: `${v.commitId.slice(0, 8)} · ${v.projectIds.length} 个模块` }))} onChange={commitId => change({ ...scope, commitId })} /></Field>
    </section>
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => setReload(v => v + 1)}>重试</Button>} />}
    {complete ? <Fragment key={sessionKey}>{children(scope)}</Fragment> : <section className="bk-empty"><Empty description={catalogLoading ? "正在查询背景范围…" : "选择项目、模块范围、分支和提交后继续。"} /></section>}
  </div>;
}

