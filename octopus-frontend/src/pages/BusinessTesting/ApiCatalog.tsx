import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert,
  App,
  Button,
  Drawer,
  Input,
  Pagination,
  Popover,
  Select,
  Space,
  Spin,
  Tag,
  Tree,
  Tooltip,
} from "antd";
import { ApiOutlined, FolderOutlined, ReloadOutlined, FilterOutlined, PlusOutlined, SearchOutlined } from "@ant-design/icons";
import { queryApi } from "../../api/query";
import type {
  EndpointInfo,
  RepositoryScope,
  RepositoryVersion,
} from "../../types";
import { businessTesting, type Operation } from "../../api/businessTesting";
import type { ContextScope } from "../../api/projectContext";
import type { CatalogViewState } from "./navigationModel";
import { httpMethods } from "./codeOperationModel";
import { EmptyState, Field } from "./FormControls";
import { errorText, hasOperationSchemas, download, refOf } from "./workbenchModel";
import {
  buildCatalog,
  filterCatalog,
  catalogTree,
  catalogBranches,
  catalogModuleIds,
  selectCatalogScope,
  operationKey,
  UNASSIGNED,
  type CatalogItem,
  type CatalogTreeNode,
  type CatalogSelection,
} from "./apiCatalogModel";

const testChoices = (item: CatalogItem) => item.endpoint
  ? item.operations.filter(hasOperationSchemas) : item.operations;

export default function ApiCatalog({
  operations,
  onOperation,
  onResolveCode,
  onConfigure,
  onPick,
  onBackground,
  initialScope,
  navigationState,
  onNavigationChange,
  pickedCounts = {},
  pickDisabled = false,
}: {
  operations: Operation[];
  onOperation: (operation: Operation) => void;
  onResolveCode: (item: CatalogItem, method: string) => Promise<Operation>;
  onConfigure: (operation: Operation) => void;
  onPick?: (operation: Operation) => void;
  onBackground?: (repositoryKey: string, modulePath?: string) => void;
  initialScope?: ContextScope;
  navigationState?: CatalogViewState;
  onNavigationChange?: (update: (current: CatalogViewState) => CatalogViewState) => void;
  pickedCounts?: Record<string, number>;
  pickDisabled?: boolean;
}) {
  const { message } = App.useApp();
  const [usingCode, setUsingCode] = useState("");
  const [codeMethod, setCodeMethod] = useState("");
  const [scopes, setScopes] = useState<RepositoryScope[]>([]);
  const [endpoints, setEndpoints] = useState<EndpointInfo[]>([]);
  const [versions, setVersions] = useState<RepositoryVersion[]>([]);
  const [versionFiltersOpen, setVersionFiltersOpen] = useState(false);
  const [localView, setLocalView] = useState<CatalogViewState>({
    selection: {
      key: initialScope?.modulePaths?.[0] ? JSON.stringify(["module", initialScope.repositoryKey, initialScope.modulePaths[0]]) : initialScope?.repositoryKey || "",
      rootKey: initialScope?.repositoryKey || "", moduleKey: initialScope?.modulePaths?.[0], branch: initialScope?.branch || "", commitId: initialScope?.commitId || "",
    }, search: "", source: "all", page: 1,
  });
  const { selection, search, source, page, expanded } = navigationState || localView;
  const changeView = (update: (current: CatalogViewState) => CatalogViewState) =>
    onNavigationChange ? onNavigationChange(update) : setLocalView(update);
  const setSearch = (search: string) => changeView(current => ({ ...current, search, page: 1 }));
  const setSource = (source: CatalogViewState["source"]) => changeView(current => ({ ...current, source, page: 1 }));
  const setPage = (page: number) => changeView(current => ({ ...current, page }));
  const setExpanded = (expanded: CatalogViewState["expanded"]) => changeView(current => ({ ...current, expanded }));
  const setSelection = (update: CatalogSelection | ((current: CatalogSelection) => CatalogSelection)) =>
    changeView(current => ({ ...current, selection: typeof update === "function" ? update(current.selection) : update, page: 1 }));
  const { branch, commitId } = selection;
  const [detail, setDetail] = useState<CatalogItem | null>(null);
  // 切换查看的 API 时清空上一次选择的方法
  const [codeMethodFor, setCodeMethodFor] = useState(detail?.key);
  if (codeMethodFor !== detail?.key) { setCodeMethodFor(detail?.key); setCodeMethod(""); }
  const applyCode = async (item: CatalogItem, action: "pick" | "configure") => {
    const method = item.method === "*" ? codeMethod : item.method;
    if (!method) { setDetail(item); return; }
    setUsingCode(item.key);
    try {
      const operation = await onResolveCode(item, method);
      setDetail(null);
      if (action === "pick") onPick?.(operation);
      else onConfigure(operation);
    } catch (error) {
      message.error(errorText(error));
    } finally {
      setUsingCode("");
    }
  };
  const [loading, setLoading] = useState(false);
  const [scopeError, setScopeError] = useState("");
  const [endpointError, setEndpointError] = useState("");
  const [versionError, setVersionError] = useState("");
  const [versionLoading, setVersionLoading] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let alive = true;
    setScopeError("");
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
  }, [reload]);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setEndpointError("");
    setEndpoints([]);
    queryApi
      .endpoints(undefined, branch || null, commitId || null)
      .then((r) => {
        if (alive) setEndpoints(r.data);
      })
      .catch((e) => {
        if (alive) setEndpointError(errorText(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [branch, commitId, reload]);
  const scope = scopes.find((s) => s.key === selection.rootKey);
  useEffect(() => {
    let alive = true;
    setVersions([]);
    setVersionError("");
    setVersionLoading(false);
    if (scope && branch) {
      setVersionLoading(true);
      queryApi
        .repositoryVersions(scope.key, branch, selection.moduleKey
          ? catalogModuleIds(scope, selection.moduleKey, branch) : undefined)
        .then((r) => {
          if (alive) setVersions(r.data);
        })
        .catch((e) => {
          if (alive) setVersionError(errorText(e));
        })
        .finally(() => {
          if (alive) setVersionLoading(false);
        });
    }
    return () => {
      alive = false;
    };
  }, [scope, selection.moduleKey, branch, reload]);

  const roots = useMemo(
    () => buildCatalog(scopes, endpoints, operations),
    [scopes, endpoints, operations],
  );
  const filtered = useMemo(
    () => filterCatalog(roots, { search, branch, commitId, source }),
    [roots, search, branch, commitId, source],
  );
  const allItems = filtered.flatMap((r) => r.items);
  const rows = allItems.filter(
    (item) =>
      (!selection.rootKey || item.rootKey === selection.rootKey) &&
      (!selection.moduleKey || item.moduleKey === selection.moduleKey),
  );
  const currentPage = Math.min(page, Math.max(1, Math.ceil(rows.length / 12)));
  const nodes = useMemo(
    () => catalogTree(filtered
      .filter((r) => (!selection.rootKey || r.key === selection.rootKey) &&
        (r.items.length || !search.trim()))
      .map((r) => ({ ...r, items: r.items.filter((item) =>
        !selection.moduleKey || item.moduleKey === selection.moduleKey) }))),
    [filtered, search, selection.rootKey, selection.moduleKey],
  );
  const branchOptions = catalogBranches(scopes, selection.rootKey, selection.moduleKey);
  const moduleOptions = [...new Set(scope
    ? scope.modules.map((m) => m.modulePath)
    : roots.find((r) => r.key === selection.rootKey)?.items.map((item) => item.moduleKey) || [])]
    .sort().map((modulePath) => ({
      value: modulePath, label: modulePath === "." ? "根目录" : modulePath,
    }));
  const activeVersion = versions.find((v) => v.commitId === commitId);
  const changeScope = (target: Parameters<typeof selectCatalogScope>[2]) => {
    const next = selectCatalogScope(scopes, selection, target);
    if (next.rootKey !== selection.rootKey || next.moduleKey !== selection.moduleKey ||
        next.branch !== branch) {
      setVersions([]);
      setVersionError("");
      setVersionLoading(!!next.branch && scopes.some((s) => s.key === next.rootKey));
    }
    setSelection(next);
    setDetail(null);
    setExpanded(undefined);
  };
  const select = (node?: CatalogTreeNode) => {
    changeScope(node
      ? { key: node.key, rootKey: node.rootKey, moduleKey: node.moduleKey }
      : { rootKey: "" });
    if (node?.item) setDetail(node.item);
  };
  type DisplayNode = Omit<CatalogTreeNode, "children"> & { title: ReactNode; children?: DisplayNode[] };
  const treeData = (entries: CatalogTreeNode[]): DisplayNode[] =>
    entries.map((node) => ({
      ...node,
      title: (
        <span className="bt-catalog-tree-title" title={node.label}>
          {node.kind === "api" ? (
            <>
              <span
                className={`bt-method ${(node.item?.method || "").toLowerCase()}`}
              >
                {node.item?.method}
              </span>
              <span>{node.item?.path}</span>
              {!branch && node.item?.branch && (
                <small>{node.item.branch}</small>
              )}
            </>
          ) : (
            <>
              <FolderOutlined />
              <span>{node.label}</span>
              <small>{node.count}</small>
            </>
          )}
        </span>
      ),
      children: node.children ? treeData(node.children) : undefined,
    }));
  const expandedKeys = search.trim()
    ? nodes.flatMap((n) => [
        n.key,
        ...(n.children || []).filter((c) => c.kind !== "api").map((c) => c.key),
      ])
    : (expanded ?? nodes.flatMap((n) => [n.key,
        ...(selection.moduleKey ? (n.children || [])
          .filter((c) => c.kind === "module").map((c) => c.key) : [])]));

  const resetFilters = () => {
    setSearch(""); setSource("all"); changeScope({ rootKey: "", branch: "" });
  };
  const picked = (item: CatalogItem) => item.operations.reduce((sum, operation) => sum + (pickedCounts[operationKey(operation)] || 0), 0);
  const filteringVersion = !!branch || !!commitId || source !== "all";
  const branchFields = <>
        <Field label="分支">
          <Select
            showSearch
            optionFilterProp="label"
            disabled={!branchOptions.length}
            value={branch}
            options={[
              { value: "", label: selection.rootKey === UNASSIGNED ? "未关联分支" : "全部分支" },
              ...branchOptions.map((b) => ({ value: b, label: b })),
            ]}
            onChange={(branch) => changeScope({
              rootKey: selection.rootKey, moduleKey: selection.moduleKey, branch,
            })}
          />
        </Field>
        <Field label="提交" hint={scope && branch && !versionLoading && !versionError && !versions.length
          ? "当前范围在该分支尚无已分析的提交。" : undefined}>
          <Select
            showSearch
            optionFilterProp="label"
            value={commitId}
            disabled={!scope || !branch || versionLoading || !versions.length}
            loading={versionLoading}
            options={[
              { value: "", label: selection.rootKey === UNASSIGNED ? "未关联代码版本" : !scope ? "请先选择项目" : !branch
                ? "请先选择分支" : selection.moduleKey
                  ? "该模块最近一次分析" : "各模块最近一次分析" },
              ...versions.map((v) => ({
                value: v.commitId,
                label: selection.moduleKey ? `${v.commitId.slice(0, 8)} · 已分析`
                  : `${v.commitId.slice(0, 8)} · ${v.projectIds.length} 个模块有分析数据`,
              })),
            ]}
            onChange={(v) => {
              setSelection((current) => ({ ...current, commitId: v }));
              setDetail(null);
            }}
          />
        </Field>
  </>;
  const sourceField = <Field label="接口来源">
          <Select
            value={source}
            onChange={setSource}
            options={[
              { value: "all", label: "全部已知接口" },
              { value: "code", label: "代码已发现" },
              { value: "imported", label: "文档 / 人工配置" },
            ]}
          />
        </Field>;
  return (
    <div className={onPick ? "bt-compact-catalog" : undefined}>
      {onPick && <div className="bt-compact-search">
        <Input aria-label="搜索 API" allowClear prefix={<SearchOutlined />} placeholder="搜索接口路径、名称或关键词…"
          value={search} onChange={e => setSearch(e.target.value)} />
        <Popover trigger="click" placement="bottomRight" open={versionFiltersOpen} onOpenChange={setVersionFiltersOpen}
          title="版本与来源" content={<div className="bt-picker-version-popover">
            <div className="bt-catalog-filters">{branchFields}</div>{sourceField}
            <div className="bt-picker-version-done"><span>仅列出已完成分析的版本</span><Button size="small" type="primary" onClick={() => setVersionFiltersOpen(false)}>完成</Button></div>
          </div>}>
          <Button icon={<FilterOutlined />} type={filteringVersion ? "primary" : "default"}
            aria-expanded={versionFiltersOpen}>版本与来源{filteringVersion ? " · 已筛选" : ""}</Button>
        </Popover>
        <Tooltip title="刷新目录"><Button aria-label="刷新目录" icon={<ReloadOutlined />} loading={loading} onClick={() => setReload(n => n + 1)} /></Tooltip>
      </div>}
      {!onPick && <div className="bt-catalog-heading">
        <p>代码分析发现的接口会自动出现在目录中。选择项目或模块查看列表，展开到 API 可查看实现位置。</p>
        <Space>
        {onBackground && <Button disabled={!scope} onClick={() => scope && onBackground(scope.key, selection.moduleKey)}>项目 / 模块背景</Button>}
        <Button
          icon={<ReloadOutlined />}
          onClick={() => setReload((n) => n + 1)}
          loading={loading}
        >
          刷新目录
        </Button>
        </Space>
      </div>}
      <div className="bt-catalog-filters">
        <Field label="项目">
          <Select
            showSearch
            optionFilterProp="label"
            value={selection.rootKey}
            options={[
              { value: "", label: "全部项目" },
              ...roots.map((root) => ({ value: root.key, label: root.name })),
            ]}
            onChange={(rootKey) => changeScope({ rootKey })}
          />
        </Field>
        <Field label="模块">
          <Select
            showSearch
            optionFilterProp="label"
            disabled={!selection.rootKey}
            value={selection.moduleKey || ""}
            options={[
              { value: "", label: selection.rootKey ? "全部模块" : "请先选择项目" },
              ...moduleOptions,
            ]}
            onChange={(moduleKey) => changeScope({
              rootKey: selection.rootKey, moduleKey: moduleKey || undefined,
            })}
          />
        </Field>
        {!onPick && branchFields}
      </div>
      {!onPick && <p className="bt-muted bt-catalog-filter-hint">
        分支随项目和模块联动，提交仅列出当前范围内已完成代码分析的版本。
      </p>}
      {!onPick && <div className="bt-catalog-search-filters">
        <Field label="搜索 API">
          <Input.Search
            allowClear
            aria-label="搜索 API"
            placeholder="在当前范围搜索路径、方法、模块或类名"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </Field>
        {sourceField}
      </div>}
      {onPick && <div className="bt-compact-scope"><span title={`${branch || "全部分支"} · ${commitId || "最近分析"}`}>{branch || "全部分支"} · {commitId ? commitId.slice(0, 8) : "最近分析"}{source === "code" ? " · 代码接口" : source === "imported" ? " · 文档 / 手工" : ""}</span>
        {(selection.rootKey || search || filteringVersion) && <Button type="link" size="small" onClick={resetFilters}>清除筛选</Button>}</div>}
      {(scopeError || endpointError || versionError) && (
        <Alert
          type="error"
          showIcon
          message="部分目录数据未能加载"
          description={[scopeError, endpointError, versionError]
            .filter(Boolean)
            .join("；")}
          action={<Button onClick={() => setReload((n) => n + 1)}>重试</Button>}
        />
      )}
      {!!activeVersion?.missingProjectIds.length && (
        <Alert
          type="warning"
          showIcon
          message="该版本的部分模块缺少分析数据"
          description={
            activeVersion.missingProjectIds
              .map(
                (id) =>
                  scope?.modules.find((m) => m.projectId === id)?.modulePath ||
                  id,
              )
              .join("、") + "；当前目录不能代表这些模块的完整 API。"
          }
        />
      )}
      <div className={onPick ? "bt-catalog-picker-results" : "bt-catalog-layout"}>
        {!onPick && <aside className="bt-catalog-sidebar" aria-label="项目模块 API 目录">
          <button
            className={`bt-catalog-all ${!selection.key ? "active" : ""}`}
            onClick={() => select()}
          >
            <ApiOutlined /> 全部接口 <span>{allItems.length}</span>
          </button>
          <div className="bt-catalog-tree">
            <Tree
              blockNode
              showLine
              treeData={treeData(nodes)}
              expandedKeys={expandedKeys}
              selectedKeys={selection.key ? [selection.key] : []}
              onExpand={(keys) => setExpanded(keys.map(String))}
              onSelect={(_, info) =>
                select(info.node as unknown as CatalogTreeNode)
              }
            />
          </div>
          <p className="bt-muted">
            项目 → 模块 → API
            <br />
            单模块项目直接展示 API。
          </p>
        </aside>}
        <div className="bt-catalog-results">
          <div className="bt-catalog-result-heading">
            <div>
              {!onPick && <h3>
                {selection.moduleKey ||
                  filtered.find((r) => r.key === selection.rootKey)?.name ||
                  "全部已知接口"}
              </h3>}
              <p>{onPick ? <><strong>{rows.length}</strong> 个接口 <span>· 点击加入右侧顺序列表</span></> : `${rows.length} 条接口记录 · 按实现位置和分支版本区分`}</p>
            </div>
            {loading && <Spin size="small" />}
          </div>
          {!onPick && !selection.rootKey && (
            <p className="bt-muted">
              选择项目和分支，可查看指定提交；模块可进一步缩小范围。
            </p>
          )}
          {!onPick && selection.rootKey === UNASSIGNED && (
            <Alert
              showIcon
              type="info"
              message="这些接口尚未关联代码项目"
              description="保留文档或手工配置中的系统与服务分组，不根据名称猜测代码归属。"
            />
          )}
          {!rows.length ? (
            <EmptyState
              icon={<ApiOutlined />}
              title={loading ? "正在读取已知 API" : "当前范围没有已知 API"}
              description={
                search
                  ? "试试其他关键词，或选择全部接口。"
                  : "代码接口来自已经保存的分析结果；没有识别到接口不代表该模块一定没有 API。"
              }
            />
          ) : (
            <div className="bt-api-list">
              {rows
                .slice((currentPage - 1) * 12, currentPage * 12)
                .map((item) => (
                  <div className={`bt-api-row bt-catalog-row ${onPick && picked(item) ? "is-picked" : ""}`} key={item.key}>
                    <span className={`bt-method ${item.method.toLowerCase()}`}>
                      {item.method}
                    </span>
                    <div className="bt-api-identity">
                      {onPick ? <button className="bt-compact-api-name" title={item.path} onClick={() => setDetail(item)}>{item.path}</button> : <strong>{item.path}</strong>}
                      {onPick ? <small title={`${item.rootName} / ${item.moduleName} · ${item.branch || "文档 / 手工配置"} · ${item.commitId || ""}`}>
                        {item.rootName} / {item.moduleName.split("/").at(-1)} · {item.branch || "文档 / 手工配置"}{item.commitId ? ` · ${item.commitId.slice(0, 8)}` : ""}
                      </small> : <><small title={`${item.rootName} / ${item.moduleName}`}>
                        {item.rootName} / {item.moduleName}
                      </small><small>
                        {item.branch || "文档 / 手工配置"}
                        {item.commitId ? ` · ${item.commitId.slice(0, 8)}` : ""}
                        {item.endpoint
                          ? ` · ${item.endpoint.controllerSimpleName}.${item.endpoint.methodName}`
                          : ""}
                      </small></>}
                    </div>
                    {!onPick && <Tag color={item.operations.some(hasOperationSchemas) ? "green" : "blue"}>
                      {item.operations.some(hasOperationSchemas) ? "测试已配置" : item.endpoint ? "代码已发现" : "待完善测试配置"}
                    </Tag>}
                    <div className="bt-catalog-row-actions">
                      {onPick && picked(item) > 0 && <span className="bt-compact-picked">已选 {picked(item)}</span>}
                      {onPick && testChoices(item).length === 1 && (
                        <Button
                          icon={<PlusOutlined />} disabled={pickDisabled}
                          aria-label={`加入 ${item.method} ${item.path}`}
                          onClick={() => onPick(testChoices(item)[0])}
                        >
                          加入
                        </Button>
                      )}
                      {onPick && testChoices(item).length > 1 && (
                        <Button icon={<PlusOutlined />} disabled={pickDisabled} onClick={() => setDetail(item)}>
                          选择版本
                        </Button>
                      )}
                      {!testChoices(item).length && item.endpoint && <Button
                        type={onPick ? "default" : "primary"} icon={onPick ? <PlusOutlined /> : undefined}
                        aria-label={onPick ? `加入 ${item.method} ${item.path}` : undefined}
                        loading={usingCode === item.key}
                        disabled={!!usingCode || (onPick && pickDisabled)}
                        onClick={() => void applyCode(item, onPick ? "pick" : "configure")}
                      >
                        {item.method === "*" ? "选择方法" : onPick ? "加入" : "配置测试"}
                      </Button>}
                      {!onPick && <Button onClick={() => setDetail(item)}>
                        查看 API
                      </Button>}
                    </div>
                  </div>
                ))}
            </div>
          )}
          {rows.length > 12 && (
            <Pagination
              current={currentPage}
              pageSize={12}
              total={rows.length}
              showSizeChanger={false}
              onChange={setPage}
              size="small"
            />
          )}
        </div>
      </div>
      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        title="API 详情"
        width={660}
      >
        {detail && (
          <>
            <div className="bt-endpoint-title">
              <span className={`bt-method ${detail.method.toLowerCase()}`}>
                {detail.method}
              </span>
              <code>{detail.path}</code>
            </div>
            <dl className="bt-catalog-facts">
              <dt>项目</dt>
              <dd>{detail.rootName}</dd>
              <dt>模块</dt>
              <dd>{detail.moduleName}</dd>
              <dt>分支</dt>
              <dd>{detail.branch || "未关联"}</dd>
              <dt>分析版本</dt>
              <dd>{detail.commitId || "未关联"}</dd>
              {detail.endpoint && (
                <>
                  <dt>实现类</dt>
                  <dd>{detail.endpoint.qualifiedClassName}</dd>
                  <dt>实现方法</dt>
                  <dd>{detail.endpoint.signature}</dd>
                </>
              )}
            </dl>
            {detail.endpoint && (
              <Alert
                type="info"
                showIcon
                message="已从代码声明中发现"
                description="代码 API 已自动进入目录，可直接添加步骤。这里列出静态分析声明与实现位置，可能包含客户端声明；测试参数和预期响应需要单独配置。"
              />
            )}
            {!!testChoices(detail).length && (
              <section style={{ marginTop: 24 }}>
                <h3>关联的测试配置</h3>
                <Space direction="vertical" style={{ width: "100%" }}>
                  {testChoices(detail).map((o) => (
                    <div key={operationKey(o)} className="bt-catalog-contract-choice">
                      <span>{o.system} / {o.service} · {operationKey(o)}</span>
                      <Space wrap>
                        {onPick && <Button
                          type="primary"
                          disabled={pickDisabled} onClick={() => { setDetail(null); onPick(o); }}
                        >
                          添加步骤
                        </Button>}
                        <Button onClick={() => { setDetail(null); onConfigure(o); }}>配置测试</Button>
                        <Button onClick={() => { setDetail(null); onOperation(o); }}>
                          查看参数与契约
                        </Button>
                        <Button onClick={async()=>{
                          try {const document=await businessTesting.exportDocumentation([refOf(o)],`${o.method} ${o.path}`);
                            download(`${o.operationId}-${o.revision}.openapi.json`,JSON.stringify(document.openApi,null,2));
                            document.warnings.forEach(w=>message.warning(w));
                          }catch(e){message.error(errorText(e));}
                        }}>导出 OpenAPI 文档</Button>
                      </Space>
                    </div>
                  ))}
                </Space>
              </section>
            )}
            {detail.endpoint && !testChoices(detail).length && (
              <section style={{ marginTop: 24 }}>
                {detail.method === "*" && <Field label="请求方法">
                  <Select value={codeMethod || undefined} placeholder="代码未限定方法，请选择本次测试的方法"
                    options={httpMethods.map((value) => ({ value }))} onChange={setCodeMethod} />
                </Field>}
                <Button type="primary" loading={usingCode === detail.key}
                  disabled={!!usingCode || (onPick && pickDisabled) || !detail.endpoint.projectId || !detail.branch || !detail.commitId || (detail.method === "*" && !codeMethod)}
                  onClick={() => void applyCode(detail, onPick ? "pick" : "configure")}
                >{onPick ? "添加步骤" : "配置测试"}</Button>
              </section>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
