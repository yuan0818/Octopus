import { useEffect, useState } from "react";
import { Alert, Select, Segmented, Tree } from "antd";
import { repositoryModuleTree } from "../../components/projectOptions";
import { queryApi } from "../../api/query";
import type { RepositoryScope, RepositoryVersion } from "../../types";
import type { VersionSelection } from "./ImportPanel";
import { Field } from "./FormControls";
import { errorText } from "./workbenchModel";
import { palette } from '../../theme';

export default function RepositoryVersionPicker({
  value,
  onChange,
}: {
  value: VersionSelection;
  onChange: (patch: Partial<VersionSelection>) => void;
}) {
  const [scopes, setScopes] = useState<RepositoryScope[]>([]);
  const [versions, setVersions] = useState<RepositoryVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [scopeError, setScopeError] = useState("");
  useEffect(() => {
    let alive = true;
    queryApi
      .repositoryScopes()
      .then((r) => {
        if (alive) setScopes(r.data);
      })
      .catch((e) => {
        if (alive) setScopeError(errorText(e));
      });
    return () => {
      alive = false;
    };
  }, []);
  const scope = scopes.find((s) => s.key === value.repositoryKey);
  const modules =
    scope?.modules.filter((m) => m.branches.includes(value.branch)) || [];
  const selectedIds = value.scopeProjectIds;
  const selectedKey = selectedIds?.join(",");
  useEffect(() => {
    let alive = true;
    setVersions([]);
    setError("");
    setLoading(false);
    if (value.repositoryKey && value.branch && selectedIds?.length !== 0) {
      setLoading(true);
      queryApi
        .repositoryVersions(value.repositoryKey, value.branch, selectedIds)
        .then((r) => {
          if (alive) setVersions(r.data);
        })
        .catch((e) => {
          if (alive) setError(errorText(e));
        })
        .finally(() => {
          if (alive) setLoading(false);
        });
    }
    return () => {
      alive = false;
    };
  }, [value.repositoryKey, value.branch, selectedKey]);
  const branches = [
    ...new Set(scope?.modules.flatMap((m) => m.branches) || []),
  ].sort();
  const options = versions.map((v) => ({
    value: v.commitId,
    label: `${v.commitId.slice(0, 8)} · ${v.projectIds.length}/${v.projectIds.length + v.missingProjectIds.length} 个模块有分析数据`,
  }));
  const incomplete = [value.baseCommitId, value.targetCommitId]
    .map((id) => versions.find((v) => v.commitId === id))
    .filter((v) => v && v.missingProjectIds.length);
  const resetVersions = { baseCommitId: "", targetCommitId: "" };
  return (
    <div className="bt-form-grid">
      <Field
        label="仓库 / 根项目"
        hint="从完整仓库选择变更范围，模块仍保留各自的分析数据。"
      >
        <Select
          showSearch
          optionFilterProp="label"
          value={value.repositoryKey || undefined}
          placeholder="选择已分析的仓库"
          options={scopes.map((s) => ({
            value: s.key,
            label: `${s.name} · ${new Set(s.modules.map((m) => m.modulePath)).size} 个模块`,
          }))}
          onChange={(repositoryKey) =>
            onChange({
              repositoryKey,
              projectId: null,
              branch: "",
              scopeProjectIds: undefined,
              ...resetVersions,
            })
          }
        />
      </Field>
      <Field label="已分析分支">
        <Select
          value={value.branch || undefined}
          placeholder="选择分支"
          disabled={!scope}
          options={branches.map((b) => ({ value: b, label: b }))}
          onChange={(branch) =>
            onChange({ branch, scopeProjectIds: undefined, ...resetVersions })
          }
        />
      </Field>
      {scope && (
        <div style={{ gridColumn: "1 / -1" }}>
          <p className="bt-muted" style={{ overflowWrap: "anywhere" }}>
            {scope.rootPath}
          </p>
          <Field
            label="分析范围"
            hint="全仓库模式会沿跨模块调用关系查找影响，无需逐个添加模块。"
          >
            <Segmented
              value={selectedIds === undefined ? "all" : "selected"}
              disabled={!value.branch}
              options={[
                { value: "all", label: "整个仓库" },
                { value: "selected", label: "指定模块" },
              ]}
              onChange={(mode) =>
                onChange({
                  scopeProjectIds:
                    mode === "all"
                      ? undefined
                      : modules.map((m) => m.projectId),
                  ...resetVersions,
                })
              }
            />
          </Field>
          {selectedIds !== undefined ? (
            <Field label="参与分析的模块">
              <Select
                mode="multiple"
                showSearch
                optionFilterProp="label"
                value={selectedIds}
                placeholder="至少选择一个模块"
                options={modules.map((m) => ({
                  value: m.projectId,
                  label: m.modulePath === "." ? "根目录源码" : m.modulePath,
                }))}
                onChange={(scopeProjectIds) =>
                  onChange({ scopeProjectIds, ...resetVersions })
                }
              />
            </Field>
          ) : (
            value.branch && (
              <div style={{ marginBottom: 16 }}>
                <p>已纳入该分支全部 {modules.length} 个已分析模块</p>
                <details>
                  <summary style={{ cursor: "pointer", color: palette.primary }}>
                    查看模块目录
                  </summary>
                  <Tree
                    style={{ marginTop: 8, overflow: "auto", maxHeight: 300 }}
                    selectable={false}
                    showLine
                    treeData={repositoryModuleTree(modules)}
                  />
                </details>
              </div>
            )
          )}
        </div>
      )}
      <Field label="变更前版本">
        <Select
          showSearch
          optionFilterProp="label"
          loading={loading}
          value={value.baseCommitId || undefined}
          options={options}
          placeholder="选择基准版本"
          disabled={!versions.length}
          onChange={(baseCommitId) => onChange({ baseCommitId })}
        />
      </Field>
      <Field label="变更后版本">
        <Select
          showSearch
          optionFilterProp="label"
          loading={loading}
          value={value.targetCommitId || undefined}
          options={options}
          placeholder="选择目标版本"
          disabled={!versions.length}
          onChange={(targetCommitId) => onChange({ targetCommitId })}
        />
      </Field>
      {(error || scopeError) && (
        <Alert
          style={{ gridColumn: "1 / -1" }}
          type="error"
          showIcon
          message={error || scopeError}
        />
      )}
      {!!incomplete.length && (
        <Alert
          style={{ gridColumn: "1 / -1" }}
          type="warning"
          showIcon
          message="所选版本的模块分析数据不齐全"
          description={
            incomplete
              .map(
                (v) =>
                  `${v!.commitId.slice(0, 8)} 缺少：${v!.missingProjectIds.map((id) => modules.find((m) => m.projectId === id)?.modulePath || id).join("、")}`,
              )
              .join("；") +
            "。结果将标明分析缺口；新增或删除模块也可能导致这种情况。"
          }
        />
      )}
      {value.branch && !loading && !versions.length && !error && (
        <p className="bt-muted" style={{ gridColumn: "1 / -1" }}>
          {selectedIds?.length === 0
            ? "至少选择一个模块。"
            : "该范围没有可比较的 Git 分析版本，请先完成仓库分析。"}
        </p>
      )}
    </div>
  );
}
