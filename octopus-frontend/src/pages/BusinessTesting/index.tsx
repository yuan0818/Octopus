import { synchronizeOrdered } from "./orderedFlowModel";
import { preparationErrors } from "./preparationModel";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Drawer,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Timeline,
} from "antd";
import {
  ApiOutlined,
  ArrowRightOutlined,
  BranchesOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  DownloadOutlined,
  ExperimentOutlined,
  FileSearchOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";
import {
  businessTesting as api,
  type Flow,
  type FlowRevision,
  type Impact,
  type Operation,
  type Profile,
  type Run,
  type Scenario,
} from "../../api/businessTesting";
import RunTriagePanel from "./RunTriagePanel";
import FlowEditor from "./FlowEditor";
import ApiCatalog from "./ApiCatalog";

import ProjectContextPanel from "./ProjectContextPanel";
import FlowGenerator from "./FlowGenerator";
import FlowChainGenerator from "./FlowChainGenerator";
import ChainTaskCenter from "./ChainTaskCenter";
import { acceptStepFill, acceptChainFill } from "./flowFillModel";
import { projectContextApi, type ContextScope } from "../../api/projectContext";
import type { CatalogItem } from "./apiCatalogModel";
import { codeOperation, replaceFlowOperation } from "./codeOperationModel";
import ImportPanel, { VersionPicker, type VersionSelection } from "./ImportPanel";
import { EmptyState, Field, ValueInput } from "./FormControls";
import {
  download,
  emptyFlow,
  errorText,
  executionProfile,
  flowIssues,
  profileIssues,
  scenarioIR,
  refOf,
  statusLabel,
} from "./workbenchModel";
import { Navigate } from "react-router-dom";
import StartRegressionBatch from "./StartRegressionBatch";
import useWorkbenchNavigation from "./useWorkbenchNavigation";
import { readCatalogView, writeCatalogView, readScope, writeScope, readImpactVersion, writeImpactVersion, setQueryValue, type BusinessTestingArea } from "./navigationModel";
import "./workbench.css";

const guide = [
  {
    title: "选择 API",
    description: "直接浏览已分析 API，也可补充外部接口文档。",
    icon: <ApiOutlined />,
  },
  {
    title: "连接业务步骤",
    description: "先由你选择接口和顺序，再让 AI 补全参数与上下文传递。",
    icon: <BranchesOutlined />,
  },
  {
    title: "设置预期结果",
    description: "检查状态码和业务数据，确认什么才算通过。",
    icon: <SafetyCertificateOutlined />,
  },
  {
    title: "运行并看结果",
    description: "填写测试环境，查看每个步骤的执行结论。",
    icon: <PlayCircleOutlined />,
  },
];

export default function BusinessTestingPage() {
  const { message, modal } = App.useApp();
  const { page, area, params, updateQuery, navigate, fallback } = useWorkbenchNavigation();
  const [operations, setOperations] = useState<Operation[]>([]);
  const [flows, setFlows] = useState<FlowRevision[]>([]);
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [flow, setFlow] = useState<Flow | null>(null);
  const currentFlow = useRef(flow);
  useLayoutEffect(() => { currentFlow.current = flow; }, [flow]);
  const [saved, setSaved] = useState<FlowRevision | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [operationConfig, setOperationConfig] = useState<Operation | null>(null);
  const [generatorOpen, setGeneratorOpen] = useState(false);
  const [chainGeneratorOpen, setChainGeneratorOpen] = useState(false);
  const [chainTaskId, setChainTaskId] = useState<string>();
  const [generationSeed, setGenerationSeed] = useState<{ scope: ContextScope; environment: string }>();
  const [fillPresets, setFillPresets] = useState<Record<string, { environment: string; values: Record<string, unknown>; variables: Flow["initialContext"] }>>({});
  const contextScope = readScope(params);
  const setContextScope = (scope: ContextScope) => updateQuery(query => writeScope(query, scope));
  const [runEnvironment, setRunEnvironment] = useState("test");
  const [defaultsLoading, setDefaultsLoading] = useState(false);
  const defaultsRequest = useRef(0);
  const [exampleOpen, setExampleOpen] = useState(false);
  const [exampleStep, setExampleStep] = useState(0);
  const search = area === "flows" ? params.get("flowSearch") || "" : "";
  const setSearch = (value: string) => updateQuery(query => setQueryValue(query, "flowSearch", value));
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewAccepted, setReviewAccepted] = useState(false);
  const [reviewPoint, setReviewPoint] = useState("");
  const [execution, setExecution] = useState<Scenario | null>(null);
  const [profile, setProfile] = useState<Profile>({
    services: {},
    initialContext: {},
    secretEnvironmentNames: {},
  });
  const [executionError, setExecutionError] = useState("");
  const [reportId, setReportId] = useState<string | null>(null);
  const [operationDetail, setOperationDetail] = useState<Operation | null>(
    null,
  );
  const [rawDetail, setRawDetail] = useState<unknown | null>(null);
  const version = readImpactVersion(params);
  const setVersion = (update: (current: VersionSelection) => VersionSelection) =>
    updateQuery(query => writeImpactVersion(query, update(readImpactVersion(query))));
  const impactRequestSeq = useRef(0);
  const runSubmission=useRef<{identity:string;key:string}|null>(null);
  const [impact, setImpact] = useState<Impact | null>(null);
  const [impactError, setImpactError] = useState("");
  const impactVersionKey = JSON.stringify(version);
  useEffect(() => {
    impactRequestSeq.current++;
    setImpact(null);
    setImpactError("");
  }, [impactVersionKey]);
  const refresh = useCallback(async () => {
    const results = await Promise.allSettled([
      api.operations(),
      api.flows(),
      api.scenarios(),
      api.runs(),
    ]);
    if (results[0].status === "fulfilled") setOperations(results[0].value);
    if (results[1].status === "fulfilled") setFlows(results[1].value);
    if (results[2].status === "fulfilled") setScenarios(results[2].value);
    if (results[3].status === "fulfilled") setRuns(results[3].value);
    const failed = results.find((r) => r.status === "rejected");
    setLoadError(failed?.status === "rejected" ? errorText(failed.reason) : "");
    setLoading(false);
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  useEffect(() => {
    if (!runs.some((r) => ["RUNNING", "QUEUED"].includes(r.state))) return;
    let alive = true;
    const timer = setInterval(() => {
      api
        .runs()
        .then((r) => {
          if (alive) setRuns(r);
        })
        .catch((e) => {
          if (alive) setLoadError(errorText(e));
        });
    }, 2000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [runs]);
  const perform = async (fn: () => Promise<void>) => {
    setBusy(true);
    try {
      await fn();
      await refresh();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const latestFlows = useMemo(() => {
    const map = new Map<string, FlowRevision>();
    flows.forEach((f) => {
      if (
        !map.has(f.definition.flowId) ||
        map.get(f.definition.flowId)!.definition.revision <
          f.definition.revision
      )
        map.set(f.definition.flowId, f);
    });
    return [...map.values()];
  }, [flows]);
  const flowRevision = (id: string, revision: number) =>
    flows.find(
      (f) => f.definition.flowId === id && f.definition.revision === revision,
    );
  const scenarioName = (s: Scenario) =>
    flowRevision(s.flowId, s.flowRevision)?.definition.name || s.flowId;
  const pointName = (s: Scenario) =>
    flowRevision(s.flowId, s.flowRevision)?.definition.testPoints.find(
      (p) => p.id === s.testPointId,
    )?.title || s.testPointId;
  const runScenario = (r: Run) => scenarios.find((s) => s.id === r.scenarioId);
  const report = runs.find((r) => r.id === reportId);
  const issues = flow ? flowIssues(flow, operations) : [];
  const resolveCode = async (item: CatalogItem, method: string) => {
    const reference = await codeOperation(item, method);
    const existing = operations.find((operation) => refOf(operation) === refOf(reference));
    if (existing) return existing;
    const captured = await api.saveOperation(reference);
    setOperations((current) => [...current.filter((operation) => refOf(operation) !== refOf(captured)), captured]);
    return captured;
  };
  const configure = (operation: Operation) => {
    setOperationConfig(operation);
    setImportOpen(true);
  };
  const discardGuard = (action: () => void) => {
    if (flow && !saved && flow.nodes.length) {
      modal.confirm({
        title: "离开当前流程？",
        content: "当前改动还未保存。离开编辑器会丢弃这些改动。",
        okText: "离开",
        cancelText: "继续编辑",
        onOk: action,
      });
    } else action();
  };
  useEffect(() => {
    if (!flow || saved) return;
    const listener = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener("beforeunload", listener);
    return () => window.removeEventListener("beforeunload", listener);
  }, [flow, saved]);
  const createFlow = (seed?: { scope: ContextScope; environment: string }) =>
    discardGuard(() => {
      setGeneratorOpen(false); setChainGeneratorOpen(false);
      setGenerationSeed(seed || { scope: contextScope, environment: params.get("environment") || "test" });
      setFlow(emptyFlow());
      setSaved(null);
      navigate("flows");
    });
  const newFlow = () => createFlow();
  const edit = (f: FlowRevision) =>
    discardGuard(() => {
      setGenerationSeed(undefined);
      setFlow(structuredClone(f.definition));
      setSaved(f);
      navigate("flows");
    });
  const openExecution = (s: Scenario) => {
    defaultsRequest.current++;
    const preset = fillPresets[s.flowId];
    setRunEnvironment(preset?.environment || "test");
    setExecution(s);
    const next = executionProfile(s);
    const variables = scenarioIR(s).initialContext;
    if (preset) Object.entries(preset.values).forEach(([key, value]) => {
      if (variables[key] && !variables[key].sensitive && variables[key].type === preset.variables[key]?.type) next.initialContext[key] = value;
    });
    setProfile(next);
    setExecutionError("");
  };
  const save = () =>
    void perform(async () => {
      if (!flow) return;
      const result = await api.saveFlow(flow);
      setFlow(result.definition);
      setSaved(result);
      message.success("流程草稿已保存");
    });
  const review = () => {
    setReviewPoint(flow?.testPoints[0]?.id || "");
    setReviewAccepted(false);
    setReviewOpen(true);
  };
  const generate = () =>
    void perform(async () => {
      if (!flow || !reviewAccepted || issues.length) return;
      let current = saved;
      if (!current || current.status === "REJECTED") {
        current = await api.saveFlow(flow);
        setSaved(current);
        setFlow(current.definition);
      }
      if (current.status !== "CONFIRMED") {
        current = await api.decide(current, true);
        setSaved(current);
        setFlow(current.definition);
      }
      const scenario = await api.generate({
        flowId: current.definition.flowId,
        flowRevision: current.definition.revision,
        testPointId: reviewPoint,
      });
      setReviewOpen(false);
      openExecution(scenario);
      navigate("scenarios");
      message.success("流程已确认，测试场景已生成");
    });
  const runTest = async (compile = false) => {
    if (!execution) return;
    const errors = profileIssues(profile);
    if (errors.length) {
      setExecutionError(errors.join("；"));
      return;
    }
    setBusy(true);
    setExecutionError("");
    try {
      if (compile) {
        const result = await api.compile(execution.id, { ...profile, environment: runEnvironment });
        download(`${scenarioName(execution)}.jmx`, result.jmx);
        message.success("JMeter 测试文件已下载");
      } else {
        const runProfile={...profile,environment:runEnvironment};
        const identity=JSON.stringify({id:execution.id,profile:runProfile});
        if(runSubmission.current?.identity!==identity) runSubmission.current={identity,key:crypto.randomUUID()};
        const result = await api.run(execution.id,runProfile,runSubmission.current.key);
        runSubmission.current=null;
        setRuns((old) => [result, ...old.filter((r) => r.id !== result.id)]);
        setExecution(null);
        setReportId(result.id);
        navigate("runs");
      }
      await refresh();
    } catch (e) {
      setExecutionError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const summaryReport = (r: Run) => {
    try {
      return JSON.parse(r.reportJson || "{}") as {
        samples?: {
          label: string;
          success: boolean;
          responseCode: string;
          elapsed: number;
          failure?: string;
        }[];
        error?: string;
        executionComplete?: boolean;
        cleanupStatus?: string;
        gatePassed?: boolean;
        calls?: {callId:string;status:string;cleanup:boolean}[];
      };
    } catch {
      return {};
    }
  };
  const sectionHeading = (
    title: string,
    description: string,
    action?: React.ReactNode,
  ) => area !== "overview" ? (action ? <div className="bt-page-actions">{action}</div> : null) : (
    <div className="bt-section-heading">
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
      </div>
      {action}
    </div>
  );
  const emptyAction = (
    <Button type="primary" icon={<PlusOutlined />} onClick={newFlow}>
      创建业务流程
    </Button>
  );
  const flowList = () =>
    latestFlows.length ? (
      <div className="bt-flow-grid">
        {latestFlows
          .filter((f) =>
            f.definition.name.toLowerCase().includes(search.toLowerCase()),
          )
          .map((f) => (
            <button
              key={f.definition.flowId}
              className="bt-flow-tile"
              onClick={() => edit(f)}
            >
              <div className="bt-tile-top">
                <span className="bt-tile-icon">
                  <BranchesOutlined />
                </span>
                <Tag color={f.status === "CONFIRMED" ? "green" : "gold"}>
                  {statusLabel(f.status)}
                </Tag>
              </div>
              <h3>{f.definition.name}</h3>
              <p>
                {f.definition.nodes.length} 个业务步骤 ·{" "}
                {f.definition.testPoints.length} 个测试点
              </p>
              <div className="bt-mini-steps">
                {f.definition.nodes.slice(0, 3).map((n, i) => (
                  <span key={n.id}>
                    {i + 1}. {n.label}
                  </span>
                ))}
              </div>
              <footer>
                <span>版本 {f.definition.revision}</span>
                <span>
                  打开流程 <ArrowRightOutlined />
                </span>
              </footer>
            </button>
          ))}
      </div>
    ) : (
      <EmptyState
        icon={<BranchesOutlined />}
        title="把接口串成一次真实的业务操作"
        description="例如：登录 → 查询资料 → 修改昵称 → 确认修改成功。流程会保存步骤、数据关系和验证规则。"
        action={emptyAction}
      />
    );
  const scrollPositions = useRef<Partial<Record<BusinessTestingArea, number>>>({});
  useLayoutEffect(() => {
    if (loading) return;
    const restore = requestAnimationFrame(() => window.scrollTo({ top: scrollPositions.current[area] || 0, behavior: "instant" }));
    const remember = () => { scrollPositions.current[area] = window.scrollY; };
    window.addEventListener("scroll", remember, { passive: true });
    return () => { cancelAnimationFrame(restore); window.removeEventListener("scroll", remember); };
  }, [area, loading]);
  if (!page) return <Navigate to={fallback} replace />;
  return (
    <div className="bt-workbench">
      <header className="bt-page-header">
        <div><h1>{page?.title}</h1><p>{page?.description}</p></div>
        <Space wrap>
          {area !== "flows" && flow && !saved && <Button onClick={() => navigate("flows")}>继续编辑流程</Button>}
          {area !== "tasks" && <Button onClick={() => navigate("tasks")}>AI 补全任务</Button>}
          <Button type="text" icon={<ReloadOutlined />} loading={loading || busy}
            onClick={() => void refresh()} aria-label="刷新工作台数据" />
        </Space>
      </header>
      {loadError && (
        <Alert
          showIcon
          type="error"
          message="部分服务暂时无法连接"
          description={loadError}
          action={<Button onClick={() => void refresh()}>重试</Button>}
        />
      )}
      {loading ? (
        <div className="bt-loading">
          <Spin />
          <span className="bt-muted">正在加载工作台</span>
        </div>
      ) : (
        <>
          <WorkbenchArea name="overview" active={area}>
            <>
              <section className="bt-welcome">
                <div className="bt-welcome-copy">
                  <span className="bt-pill">
                    <BranchesOutlined /> 从单个接口，到完整业务
                  </span>
                  <h2>
                    接口能通，
                    <br />
                    业务流程也要跑得通。
                  </h2>
                  <p>
                    登录拿到的 token，能否继续下单？订单创建后，状态是否正确？
                    <br className="bt-wide-break" />
                    在这里连接接口、传递数据，让预期结果变成可重复运行的测试。
                  </p>
                  <Space wrap size={12}>
                    <Button
                      type="primary"
                      size="large"
                      icon={<PlusOutlined />}
                      onClick={newFlow}
                    >
                      创建业务流程
                    </Button>
                    <Button
                      size="large"
                      icon={<PlayCircleOutlined />}
                      onClick={() => {
                        setExampleStep(0);
                        setExampleOpen(true);
                      }}
                    >
                      先看一个例子
                    </Button>
                  </Space>
                  <div className="bt-welcome-note">
                    <CheckCircleOutlined /> 表单配置 · 可视化编排 · JMeter 执行
                  </div>
                </div>
                <div className="bt-example-preview">
                  <div className="bt-example-caption">
                    <span>一个流程可以验证什么？</span>
                    <Tag bordered={false}>流程示意</Tag>
                  </div>
                  {[
                    {
                      title: "用户登录",
                      api: "POST /auth/login",
                      hint: "保存返回的 token",
                    },
                    {
                      title: "查询个人资料",
                      api: "GET /users/me",
                      hint: "使用上一步的 token",
                    },
                    {
                      title: "检查业务结果",
                      api: "用户 ID 与登录用户一致",
                      hint: "不仅检查 HTTP 200",
                    },
                  ].map((s, i) => (
                    <button
                      key={s.title}
                      className="bt-preview-step"
                      onClick={() => {
                        setExampleStep(i);
                        setExampleOpen(true);
                      }}
                    >
                      <span className="bt-preview-number">{i + 1}</span>
                      <div>
                        <strong>{s.title}</strong>
                        <code>{s.api}</code>
                        <small>{s.hint}</small>
                      </div>
                      <ArrowRightOutlined />
                    </button>
                  ))}
                </div>
              </section>
              <section className="bt-get-started">
                {sectionHeading(
                  "从这里开始",
                  "按这四步，建立你的第一条业务回归测试。",
                )}
                <div className="bt-guide-grid">
                  {guide.map((g, i) => (
                    <button
                      key={g.title}
                      onClick={() =>
                        i === 0
                          ? navigate("operations")
                          : i === 1
                            ? newFlow()
                            : navigate(i === 2 ? "flows" : "scenarios")
                      }
                    >
                      <div>
                        <span className="bt-guide-number">0{i + 1}</span>
                        {g.icon}
                      </div>
                      <h3>{g.title}</h3>
                      <p>{g.description}</p>
                      <span className="bt-guide-link">
                        {i === 0
                          ? "查看已知 API"
                          : i === 1 && latestFlows.length
                            ? `已有 ${latestFlows.length} 条流程`
                            : "开始配置"}{" "}
                        <ArrowRightOutlined />
                      </span>
                    </button>
                  ))}
                </div>
              </section>
              <div className="bt-overview-bottom">
                <section className="bt-panel">
                  {sectionHeading(
                    "我的业务流程",
                    `${latestFlows.length} 条流程 · 从最关键的一条业务链路开始`,
                    <Button type="text" onClick={() => navigate("flows")}>
                      查看全部 <ArrowRightOutlined />
                    </Button>,
                  )}
                  {latestFlows.length ? (
                    flowList()
                  ) : (
                    <div className="bt-compact-empty">
                      <BranchesOutlined />
                      <div>
                        <strong>第一条流程，从一个业务目标开始</strong>
                        <p>
                          创建流程后，它会一直保存在这里，供后续版本重复验证。
                        </p>
                      </div>
                      <Button onClick={newFlow}>创建流程</Button>
                    </div>
                  )}
                </section>
                <section className="bt-impact-teaser">
                  <span className="bt-tile-icon">
                    <CodeOutlined />
                  </span>
                  <h3>改了代码，该测哪些流程？</h3>
                  <p>
                    选择变更前后的代码版本，沿调用关系定位受影响接口，再找到关联的业务流程和测试点。
                  </p>
                  <Button onClick={() => navigate("impact")}>
                    查看变更影响 <ArrowRightOutlined />
                  </Button>
                  <small>需先完成代码分析，并关联接口实现。</small>
                </section>
              </div>
            </>
          </WorkbenchArea>
          <WorkbenchArea name="operations" active={area}>
            <section className="bt-panel">
              {sectionHeading(
                "接口资产",
                "按项目、模块浏览当前已知 API，查看实现位置与测试资产。",
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => setImportOpen(true)}
                >
                  导入 / 新增外部 API
                </Button>,
              )}
              <ApiCatalog operations={operations} onOperation={setOperationDetail}
                onResolveCode={resolveCode} onConfigure={configure}
                navigationState={readCatalogView(params)}
                onNavigationChange={update => updateQuery(query => writeCatalogView(query, update(readCatalogView(query))))}
                onBackground={(repositoryKey, modulePath) => {
                  setContextScope({ ...contextScope, repositoryKey, modulePaths: modulePath ? [modulePath] : undefined });
                  navigate("context");
                }} />
            </section>
          </WorkbenchArea>
          <WorkbenchArea name="context" active={area}>
            <ProjectContextPanel navigationScope={contextScope} navigationEnvironment={params.get("environment") || "test"}
              active={area === "context"} onScopeChange={setContextScope}
              onEnvironmentChange={value => updateQuery(query => setQueryValue(query, "environment", value))}
              onGenerate={(scope, environment) => createFlow({ scope, environment })}
              onAnalyze={(scope, environment) => { setContextScope(scope); updateQuery(query => setQueryValue(query, "environment", environment)); window.open(`/background?${new URLSearchParams({ repository: scope.repositoryKey, module: scope.modulePaths?.[0] || "", branch: scope.branch, commit: scope.commitId, environment })}`, "_blank", "noopener,noreferrer"); }} />
          </WorkbenchArea>

          <WorkbenchArea name="flows" active={area}>
            {flow ? (
              <FlowEditor
                key={flow.flowId}
                active={area === "flows"}
                flow={flow}
                initialScope={generationSeed?.scope}
                saved={saved}
                operations={operations}
                busy={busy}
                onChange={(f) => {
                  setFlow(synchronizeOrdered(f));
                  setSaved(null);
                }}
                onBack={() =>
                  discardGuard(() => {
                    setFlow(null);
                    setSaved(null);
                  })
                }
                onSave={save}
                onReview={review}
                onImport={() => { setOperationConfig(null); setImportOpen(true); }}
                onOperation={setOperationDetail}
                onResolveCode={resolveCode}
                onConfigure={configure}
                onGenerate={() => { setChainTaskId(undefined); setChainGeneratorOpen(true); }}
                onGenerateStep={() => setGeneratorOpen(true)}
              />
            ) : (
              <section className="bt-panel">
                {sectionHeading(
                  "业务流程",
                  "用业务动作命名步骤，把请求数据、步骤依赖和预期结果放在同一张画布上。",
                  emptyAction,
                )}
                {latestFlows.length > 0 && (
                  <Input.Search
                    aria-label="搜索业务流程"
                    className="bt-search"
                    placeholder="搜索流程名称"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                  />
                )}
                {flowList()}
              </section>
            )}
          </WorkbenchArea>
          <WorkbenchArea name="tasks" active={area}>
            <ChainTaskCenter active={area === "tasks"} params={params} updateQuery={updateQuery} operations={operations}
              onFlows={() => navigate("flows")} onOpenFlow={task => discardGuard(() => {
                setOperations(current => [...current.filter(o => !task.operations.some(next => refOf(next) === refOf(o))), ...task.operations]);
                setFlow(structuredClone(task.state === "ACCEPTED" ? task.candidate : task.original)); setSaved(null);
                setGenerationSeed(undefined); setChainTaskId(task.id); setChainGeneratorOpen(task.state === "READY"); navigate("flows");
              })} />
          </WorkbenchArea>
          <WorkbenchArea name="impact" active={area}>
            <section className="bt-panel">
              {sectionHeading(
                "这次改动，需要回归什么？",
                "选择仓库和两个已分析版本，一次查找多个模块中受影响的接口与业务流程。",
              )}
              <div className="bt-impact-layout">
                <div>
                  <VersionPicker
                    value={version}
                    compare
                    onChange={(patch) => {
                      setVersion((v) => ({ ...v, ...patch }));
                    }}
                  />
                  <Button
                    type="primary"
                    icon={<SearchOutlined />}
                    loading={busy}
                    disabled={
                      !version.baseCommitId ||
                      !version.targetCommitId ||
                      version.baseCommitId === version.targetCommitId
                    }
                    onClick={() => {
                      const requestId = ++impactRequestSeq.current;
                      setBusy(true);
                      setImpactError("");
                      setImpact(null);
                      void api
                        .impact({
                          repositoryKey: version.repositoryKey,
                          projectIds: version.scopeProjectIds,
                          branch: version.branch,
                          baseCommitId: version.baseCommitId,
                          targetCommitId: version.targetCommitId,
                        })
                        .then((result) => {
                          if (impactRequestSeq.current === requestId)
                            setImpact(result);
                        })
                        .catch((e) => {
                          if (impactRequestSeq.current === requestId)
                            setImpactError(errorText(e));
                        })
                        .finally(() => setBusy(false));
                    }}
                  >
                    查找需要回归的测试
                  </Button>
                  {impactError && (
                    <Alert
                      style={{ marginTop: 16 }}
                      type="error"
                      showIcon
                      message="暂时无法分析"
                      description={impactError}
                    />
                  )}
                </div>
                <aside className="bt-help-box">
                  <h3>结果从哪里来？</h3>
                  <ol>
                    <li>定位这次修改的方法。</li>
                    <li>沿调用图找到受影响接口。</li>
                    <li>匹配业务流程与数据依赖。</li>
                    <li>给出需要回归的测试点和依据。</li>
                  </ol>
                  <p>
                    没有实现关联的接口会明确列出；静态分析不等同于完整运行时覆盖。
                  </p>
                </aside>
              </div>
              {impact && (
                <div className="bt-impact-results">
                  <Alert
                    showIcon
                    type={
                      impact.codeImpact.coverage === "PARTIAL"
                        ? "warning"
                        : "success"
                    }
                    message={
                      impact.codeImpact.coverage === "PARTIAL"
                        ? "当前结果仅覆盖部分代码范围"
                        : "所选静态代码范围分析完成"
                    }
                    description={
                      <>
                        <span>
                          已检查 {impact.codeImpact.projectIds.length} 个模块，找到 {impact.testPoints.length} 个关联测试点。
                        </span>
                        {impact.codeImpact.warnings.map((w, i) => (
                          <p key={i}>{w}</p>
                        ))}
                        {impact.unmappedEndpoints.length > 0 && (
                          <p>
                            {impact.unmappedEndpoints.length}{" "}
                            个接口尚未关联业务流程。
                          </p>
                        )}
                      </>
                    }
                  />
                  <StartRegressionBatch
                    projectIds={impact.codeImpact.projectIds}
                    branch={version.branch}
                    baseCommitId={version.baseCommitId}
                    targetCommitId={version.targetCommitId}
                    affected={impact.testPoints.length}
                  />
                  {impact.testPoints.map((p) => {
                    const f = flowRevision(p.flowId, p.flowRevision);
                    return (
                      <div
                        className="bt-impact-match"
                        key={`${p.flowId}/${p.testPointId}`}
                      >
                        <BranchesOutlined />
                        <div>
                          <h3>
                            {f?.definition.name || p.flowId} ·{" "}
                            {f?.definition.testPoints.find(
                              (t) => t.id === p.testPointId,
                            )?.title || p.testPointId}
                          </h3>
                          <p>
                            受影响：
                            {p.affectedNodes
                              .map(
                                (id) =>
                                  f?.definition.nodes.find((n) => n.id === id)
                                    ?.label || id,
                              )
                              .join("、")}
                          </p>
                          {p.prerequisiteNodes.length > 0 && (
                            <p>
                              同时执行前置步骤：
                              {p.prerequisiteNodes
                                .map(
                                  (id) =>
                                    f?.definition.nodes.find((n) => n.id === id)
                                      ?.label || id,
                                )
                                .join("、")}
                            </p>
                          )}
                          <small>{p.reason}</small>
                        </div>
                        <Button
                          loading={busy}
                          onClick={() =>
                            void perform(async () => {
                              openExecution(
                                await api.generate({
                                  flowId: p.flowId,
                                  flowRevision: p.flowRevision,
                                  testPointId: p.testPointId,
                                  changeEvidenceRefs: p.evidenceRefs,
                                }),
                              );
                            })
                          }
                        >
                          生成回归测试
                        </Button>
                      </div>
                    );
                  })}
                  {!impact.testPoints.length && (
                    <p className="bt-muted">
                      暂未匹配到业务测试点。这不表示无需测试，请检查接口实现映射和已确认的流程。
                    </p>
                  )}
                  <details className="bt-advanced">
                    <summary>查看分析证据</summary>
                    <pre>{JSON.stringify(impact, null, 2)}</pre>
                  </details>
                </div>
              )}
            </section>
          </WorkbenchArea>
          <WorkbenchArea name="scenarios" active={area}>
            <section className="bt-panel">
              {sectionHeading(
                "可执行的测试场景",
                "每个场景固定了一个已确认的流程版本和检查项，可重复运行或下载 JMeter 文件。",
              )}
              {scenarios.length ? (
                <div className="bt-scenario-list">
                  {scenarios.map((s) => (
                    <div key={s.id} className="bt-scenario-row">
                      <span className="bt-tile-icon">
                        <ExperimentOutlined />
                      </span>
                      <div className="bt-grow">
                        <h3>{scenarioName(s)}</h3>
                        <p>
                          {pointName(s)} <span>·</span> 流程版本{" "}
                          {s.flowRevision} <span>·</span>{" "}
                          {scenarioIR(s).steps.length} 个步骤
                        </p>
                      </div>
                      <Button onClick={() => setRawDetail(scenarioIR(s))}>
                        查看固定定义
                      </Button>
                      <Button
                        type="primary"
                        icon={<PlayCircleOutlined />}
                        onClick={() => openExecution(s)}
                      >
                        运行测试
                      </Button>
                    </div>
                  ))}
                </div>
              ) : (
                <EmptyState
                  icon={<ExperimentOutlined />}
                  title="确认一条流程，就能生成测试"
                  description="先配置业务步骤和检查项，点击「检查并生成测试」。生成后在这里选择环境并运行。"
                  action={emptyAction}
                />
              )}
            </section>
          </WorkbenchArea>
          <WorkbenchArea name="runs" active={area}>
            <section className="bt-panel">
              {sectionHeading(
                "运行结果",
                "从业务结论开始，查看具体是哪个步骤、哪项检查没有通过。",
              )}
              {runs.length ? (
                <div className="bt-run-list">
                  {runs.map((r) => {
                    const s = runScenario(r);
                    return (
                      <button
                        className="bt-run-row"
                        key={r.id}
                        onClick={() => setReportId(r.id)}
                      >
                        <span
                          className={`bt-result-icon ${r.verdict.toLowerCase()}`}
                        >
                          {r.verdict === "PASS" ? (
                            <CheckCircleOutlined />
                          ) : ["RUNNING", "QUEUED"].includes(r.state) ? (
                            <ClockCircleOutlined />
                          ) : (
                            <FileSearchOutlined />
                          )}
                        </span>
                        <div className="bt-grow">
                          <h3>{s ? scenarioName(s) : "业务测试"}</h3>
                          <p>
                            {s ? pointName(s) : r.scenarioId} ·{" "}
                            {r.id.slice(0, 8)}
                          </p>
                        </div>
                        <Tag
                          color={
                            r.verdict === "PASS"
                              ? "green"
                              : r.verdict === "FAIL"
                                ? "red"
                                : "default"
                          }
                        >
                          {["RUNNING", "QUEUED"].includes(r.state)
                            ? statusLabel(r.state)
                            : statusLabel(r.verdict)}
                        </Tag>
                        <span className="bt-muted">
                          查看报告 <ArrowRightOutlined />
                        </span>
                      </button>
                    );
                  })}
                </div>
              ) : (
                <EmptyState
                  icon={<FileSearchOutlined />}
                  title="你的第一份测试结果会出现在这里"
                  description="先生成测试场景，填写测试环境并运行，即可看到每个步骤的状态和业务验证结论。"
                  action={
                    <Button
                      type="primary"
                      onClick={() => navigate("scenarios")}
                    >
                      选择测试场景
                    </Button>
                  }
                />
              )}
            </section>
          </WorkbenchArea>
        </>
      )}
      <ImportPanel
        open={importOpen}
        initialOperation={operationConfig}
        onClose={() => { setImportOpen(false); setOperationConfig(null); }}
        onConfigured={(previous, next) => {
          setOperations((current) => [...current.filter((operation) => refOf(operation) !== refOf(next)), next]);
          if (flow) {
            const updated = replaceFlowOperation(flow, previous, next);
            if (updated !== flow) { setFlow(updated); setSaved(null); }
          }
        }}
        onImported={async (count) => {
          await refresh();
          message.success(operationConfig ? "接口测试配置已保存" : `已接入 ${count} 个外部接口`);
          if (!flow) navigate("operations");
        }}
      />
      <FlowChainGenerator open={chainGeneratorOpen} flow={flow} operations={operations} initialTaskId={chainTaskId}
        initialEnvironment={(flow && fillPresets[flow.flowId]?.environment) || generationSeed?.environment || "test"}
        initialValues={(flow && fillPresets[flow.flowId]?.values) || {}} onClose={() => setChainGeneratorOpen(false)}
        onViewTasks={task => { setChainGeneratorOpen(false); updateQuery(query => setQueryValue(query, "fillTask", task.id)); navigate("tasks"); }}
        onAccept={async (task, values) => {
          if (!currentFlow.current) throw new Error("当前流程已关闭");
          const filled = acceptChainFill(currentFlow.current, task.original, task.candidate, operations, task.operations);
          const confirmed = await projectContextApi.confirmChain(task);
          if (!currentFlow.current) throw new Error("当前流程已关闭");
          acceptChainFill(currentFlow.current, task.original, task.candidate, operations, task.operations);
          setOperations(current => [...current.filter(o => !task.operations.some(next => refOf(next) === refOf(o))), ...task.operations]);
          setFlow(filled); setSaved(null);
          setFillPresets(current => ({ ...current, [filled.flowId]: { environment: task.environment, values, variables: filled.initialContext } }));
          message.success("整条链路建议已应用，步骤记忆已确认；请检查并保存流程草稿");
          return confirmed;
        }} />
      <FlowGenerator open={generatorOpen} flow={flow} operations={operations}
        initialEnvironment={(flow && fillPresets[flow.flowId]?.environment) || generationSeed?.environment || "test"}
        initialValues={(flow && fillPresets[flow.flowId]?.values) || {}} onClose={() => setGeneratorOpen(false)}
        onAccept={async ({ original, prepared, suggestion, environment, contextValues }) => {
          if (!currentFlow.current) throw new Error("当前流程已关闭");
          const filled = acceptStepFill(currentFlow.current, original, prepared, suggestion.flow, suggestion.nodeId);
          await projectContextApi.confirmStep({ memoryId: suggestion.memory.id, flow: filled, environment });
          if (!currentFlow.current) throw new Error("当前流程已关闭");
          acceptStepFill(currentFlow.current, original, prepared, suggestion.flow, suggestion.nodeId);
          setOperations(current => [...current.filter(o => !suggestion.operations.some(next => refOf(next) === refOf(o))), ...suggestion.operations]);
          setFlow(filled); setSaved(null);
          setFillPresets(current => ({ ...current, [filled.flowId]: { environment, values: contextValues, variables: prepared.initialContext } }));
          message.success("当前步骤已应用，步骤记忆已保存；流程草稿仍需保存");
        }} />
      <Drawer
        title="两分钟看懂业务流程测试"
        width={640}
        open={exampleOpen}
        onClose={() => setExampleOpen(false)}
        footer={
          <div className="bt-dialog-actions">
            <Button onClick={() => setExampleOpen(false)}>我明白了</Button>
            <Button
              type="primary"
              onClick={() => {
                setExampleOpen(false);
                newFlow();
              }}
            >
              开始配置自己的测试
            </Button>
          </div>
        }
      >
        <Tag color="blue">说明示例 · 不会调用真实接口或保存数据</Tag>
        <h2>验证「登录后能正确获取我的资料」</h2>
        <p className="bt-lead">
          一次业务测试，关注的是多个接口协作后产生的结果。
        </p>
        <div className="bt-example-tabs">
          {["登录并保存凭证", "把数据传给下一步", "检查业务结果"].map(
            (title, i) => (
              <button
                key={title}
                className={exampleStep === i ? "active" : ""}
                onClick={() => setExampleStep(i)}
              >
                <span>{i + 1}</span>
                {title}
              </button>
            ),
          )}
        </div>
        <div className="bt-example-detail">
          {exampleStep === 0 ? (
            <>
              <Tag color="blue">POST /auth/login</Tag>
              <h3>拿到登录凭证，留给后续步骤使用</h3>
              <p>
                请求中填测试账号。响应中提取 <code>$.data.token</code>，命名为{" "}
                <code>auth.token</code>。
              </p>
              <div className="bt-example-data">
                <span>登录接口的返回值</span>
                <ArrowRightOutlined />
                <strong>auth.token</strong>
              </div>
              <p className="bt-muted">
                在步骤面板里通过「传递一个返回值」配置，不需要编写脚本。
              </p>
            </>
          ) : exampleStep === 1 ? (
            <>
              <Tag color="green">GET /users/me</Tag>
              <h3>第二步直接使用第一步的数据</h3>
              <p>
                将认证参数的数据来源设为「使用其他步骤的数据」，选择{" "}
                <code>auth.token</code>。系统自动建立数据依赖，先登录再查询。
              </p>
              <div className="bt-example-data">
                <strong>auth.token</strong>
                <ArrowRightOutlined />
                <span>查询资料的认证参数</span>
              </div>
            </>
          ) : (
            <>
              <Tag color="purple">业务检查</Tag>
              <h3>返回 200，还不够</h3>
              <p>
                添加检查项：响应状态码等于 200，且返回的用户 ID
                等于测试账号对应的 ID。
              </p>
              <div className="bt-check-example">
                <CheckCircleOutlined /> 状态码 = 200
              </div>
              <div className="bt-check-example">
                <CheckCircleOutlined /> $.data.id = 预期用户 ID
              </div>
              <p className="bt-muted">
                只有全部必要步骤和检查项通过，整条流程才会判定为通过。这里仅演示配置思路，没有执行结果。
              </p>
            </>
          )}
        </div>
        {exampleStep < 2 && (
          <Button onClick={() => setExampleStep((s) => s + 1)}>
            下一步 <ArrowRightOutlined />
          </Button>
        )}
      </Drawer>
      <Modal
        title="确认业务预期，再生成测试"
        open={reviewOpen}
        onCancel={() => setReviewOpen(false)}
        width={700}
        okText="确认并生成测试"
        onOk={generate}
        confirmLoading={busy}
        okButtonProps={{ disabled: !reviewAccepted || issues.length > 0 }}
      >
        <p className="bt-lead">
          这一步会固定流程和预期结果。请确认下面的检查规则符合你要验证的业务。
        </p>
        {flow && (
          <>
            <h3>{flow.name || "未命名流程"}</h3>
            <Field label="要生成的测试点">
              <Select
                value={reviewPoint}
                options={flow.testPoints.map((p) => ({
                  value: p.id,
                  label: p.title,
                }))}
                onChange={setReviewPoint}
              />
            </Field>
            <div className="bt-review-steps">
              {flow.steps && <p>{flow.steps.map((step,i)=>`${i+1}. ${step.label}（${step.mode==="PARALLEL"?"并行":"串行"}）`).join(" → ")}</p>}
              {flow.nodes.filter(n=>{
                const end=flow.testPoints.find(p=>p.id===reviewPoint)?.endAtStepRef;
                return !end || !flow.steps || flow.steps.slice(0,flow.steps.findIndex(s=>s.id===end)+1).some(s=>s.calls.some(c=>c.id===n.id));
              }).map((n, i) => (
                <div key={n.id}>
                  <span className="bt-review-number">{i + 1}</span>
                  <div>
                    <strong>{n.label}</strong>
                    {Object.keys(flow.testPoints.find(p=>p.id===reviewPoint)?.inputOverrides[n.id]||{}).length>0 && <p>此用例单独覆盖输入：{Object.keys(flow.testPoints.find(p=>p.id===reviewPoint)!.inputOverrides[n.id]).join("、")}；请在调用详情核对实际值。</p>}
                    {(
                      flow.testPoints.find((p) => p.id === reviewPoint)
                        ?.assertions[n.id] || []
                    ).map((a) => (
                      <p key={a.id}>
                        {a.kind === "STATUS_EQUALS"
                          ? `响应状态码应为 ${a.expected}`
                          : a.kind === "JSON_EXISTS"
                            ? `响应应包含 ${a.path}`
                            : a.kind === "SCHEMA_CONFORMS"
                              ? "响应应符合接口契约 Schema"
                              : `${a.path} 应等于 ${JSON.stringify(a.expected)}`}
                      </p>
                    ))}
                  </div>
                </div>
              ))}
            </div>
            {issues.length > 0 && (
              <Alert
                showIcon
                type="warning"
                message="请先完成这些配置"
                description={
                  <ul>
                    {issues.map((i) => (
                      <li key={i}>{i}</li>
                    ))}
                  </ul>
                }
              />
            )}
            <Checkbox
              checked={reviewAccepted}
              onChange={(e) => setReviewAccepted(e.target.checked)}
              style={{ marginTop: 20 }}
            >
              我已检查业务步骤、数据关系和预期结果
            </Checkbox>
          </>
        )}
      </Modal>
      <Drawer
        title="运行测试"
        width={660}
        open={!!execution}
        onClose={() => setExecution(null)}
        footer={
          <div className="bt-dialog-actions">
            <Button
              icon={<DownloadOutlined />}
              loading={busy}
              onClick={() => void runTest(true)}
            >
              下载 JMeter 文件
            </Button>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={busy}
              onClick={() => void runTest()}
            >
              开始运行
            </Button>
          </div>
        }
      >
        {execution && (
          <>
            <Tag color="green">已确认的流程版本 {execution.flowRevision}</Tag>
            <h2>{scenarioName(execution)}</h2>
            <p>
              {pointName(execution)} · {scenarioIR(execution).steps.length}{" "}
              个步骤
            </p>
            <section className="bt-inspector-section">
              <h3>在哪个环境执行？</h3>
              <p>测试地址用于接口调用。数据库、Redis、Kafka 检查使用下方环境标识对应的已保存连接与凭据。</p>
              <div className="bt-inline" style={{ marginBottom: 16 }}>
                <Input aria-label="已配置环境标识" placeholder="环境标识，例如 test" value={runEnvironment}
                  disabled={defaultsLoading} onChange={e => { setRunEnvironment(e.target.value); setProfile(executionProfile(execution)); }} />
                <Button loading={defaultsLoading} onClick={async () => {
                  const request = ++defaultsRequest.current;
                  setDefaultsLoading(true); setExecutionError("");
                  try {
                    const defaults = await projectContextApi.executionDefaults(execution.id, runEnvironment);
                    if (request !== defaultsRequest.current) return;
                    setProfile(p => ({ ...p, services: { ...p.services, ...defaults.services }, initialContext: { ...p.initialContext, ...defaults.initialContext } }));
                    if (defaults.warnings.length) setExecutionError(defaults.warnings.join("；"));
                    else message.success("已加载服务地址和环境变量");
                  } catch (e) { if (request === defaultsRequest.current) setExecutionError(errorText(e)); }
                  finally { setDefaultsLoading(false); }
                }}>加载环境配置与变量</Button>
              </div>
              {Object.entries(profile.services).map(([key, value]) => (
                <Field
                  key={key}
                  label={`${key} 测试地址`}
                  hint="例如 https://staging.example.com"
                >
                  <Input
                    placeholder="https://staging.example.com"
                    value={value.baseUrl}
                    onChange={(e) =>
                      setProfile((p) => ({
                        ...p,
                        services: {
                          ...p.services,
                          [key]: { baseUrl: e.target.value },
                        },
                      }))
                    }
                  />
                </Field>
              ))}
            </section>
            {Object.keys(profile.initialContext).length > 0 && (
              <section className="bt-inspector-section">
                <h3>本次测试数据</h3>
                <p>这些值只用于本次执行，不写入场景定义。</p>
                {Object.entries(scenarioIR(execution).initialContext).map(
                  ([key, v]) => (
                    <Field key={key} label={key}>
                      <ValueInput
                        label={key}
                        value={profile.initialContext[key]}
                        type={v.type}
                        onChange={(value) =>
                          setProfile((p) => ({
                            ...p,
                            initialContext: {
                              ...p.initialContext,
                              [key]: value,
                            },
                          }))
                        }
                      />
                    </Field>
                  ),
                )}
              </section>
            )}
            {Object.keys(profile.secretEnvironmentNames).length > 0 && (
              <section className="bt-inspector-section">
                <h3>密钥引用</h3>
                <p>填写服务进程中已有的环境变量名称，不需要输入密钥本身。</p>
                {Object.entries(profile.secretEnvironmentNames).map(
                  ([key, value]) => (
                    <Field key={key} label={`${key} 环境变量名称`}>
                      <Input
                        value={value}
                        placeholder="例如 TEST_API_TOKEN"
                        onChange={(e) =>
                          setProfile((p) => ({
                            ...p,
                            secretEnvironmentNames: {
                              ...p.secretEnvironmentNames,
                              [key]: e.target.value,
                            },
                          }))
                        }
                      />
                    </Field>
                  ),
                )}
              </section>
            )}
            <section className="bt-inspector-section">
              <h3>将执行这些步骤</h3>
              <Timeline
                items={scenarioIR(execution).steps.map((n, i) => ({
                  children: (
                    <>
                      <strong>
                        {flowRevision(
                          execution.flowId,
                          execution.flowRevision,
                        )?.definition.nodes.find((node) => node.id === n.id)
                          ?.label || `步骤 ${i + 1}`}
                      </strong>
                      <p>{n.assertions.length} 项检查</p>
                    </>
                  ),
                }))}
              />
            </section>
            {executionError && (
              <Alert
                showIcon
                type="error"
                message="请检查执行配置"
                description={executionError}
              />
            )}
          </>
        )}
      </Drawer>
      <Drawer
        title="业务测试报告"
        width={780}
        open={!!report}
        onClose={() => setReportId(null)}
      >
        {report && (
          <>
            <div
              className={`bt-report-summary ${report.verdict.toLowerCase()}`}
            >
              <span className="bt-result-icon">
                {report.verdict === "PASS" ? (
                  <CheckCircleOutlined />
                ) : (
                  <FileSearchOutlined />
                )}
              </span>
              <div>
                <h2>
                  {["RUNNING", "QUEUED"].includes(report.state)
                    ? statusLabel(report.state)
                    : statusLabel(report.verdict)}
                </h2>
                <p>
                  {runScenario(report)
                    ? scenarioName(runScenario(report)!)
                    : "业务测试"}{" "}
                  · {statusLabel(report.state)}
                </p>
              </div>
            </div>
            {report.error && (
              <Alert type="warning" showIcon message={report.error} />
            )}
            <RunTriagePanel runId={report.id} verdict={report.verdict} state={report.state} />
            {["RUNNING", "QUEUED"].includes(report.state) ? (
              <div className="bt-running">
                <Spin />
                <p>正在按顺序执行步骤，结果将自动更新。</p>
                <Button
                  danger
                  onClick={() =>
                    void perform(async () => {
                      await api.cancel(report.id);
                    })
                  }
                >
                  取消本次执行
                </Button>
              </div>
            ) : (
              <>
                {summaryReport(report).executionComplete===false && <Alert type="warning" showIcon message="执行未完整结束" description="保留已取得的业务结论；未派发或结果未知的调用不计为通过。" />}
                {summaryReport(report).cleanupStatus && summaryReport(report).cleanupStatus!=="NOT_REQUIRED" && <Alert type={summaryReport(report).gatePassed?"success":"warning"}
                  message={`清理结果：${summaryReport(report).cleanupStatus} · 门禁${summaryReport(report).gatePassed?"通过":"未通过"}`} />}
                <h3>步骤执行明细</h3>
                {summaryReport(report).calls?.length ? <Table rowKey="callId" pagination={false} size="small" style={{marginBottom:20}} dataSource={summaryReport(report).calls} columns={[
                  {title:"调用",dataIndex:"callId",render:(id:string)=>{
                    const scenario=runScenario(report);const flow=scenario && flowRevision(scenario.flowId,scenario.flowRevision)?.definition;
                    return flow?.nodes.find(c=>c.id===id)?.label || flow?.cleanup?.find(c=>c.call.id===id)?.call.label || id;
                  }},
                  {title:"用途",dataIndex:"cleanup",render:(cleanup:boolean)=>cleanup?"固定收尾":"业务调用"},
                  {title:"结果",dataIndex:"status",render:(status:string)=><Tag color={status==="PASS"?"green":status==="FAIL"?"red":"orange"}>{({PASS:"通过",FAIL:"业务失败",ERROR:"执行异常",UNKNOWN:"结果未知",BLOCKED:"前序失败，已阻断",NOT_RUN:"未执行",NOT_APPLICABLE:"不适用"} as Record<string,string>)[status]||status}</Tag>},
                ]}/>:null}
                {summaryReport(report).samples?.length ? (
                  <Table
                    rowKey="label"
                    pagination={false}
                    size="small"
                    scroll={{ x: 520 }}
                    dataSource={summaryReport(report).samples}
                    columns={[
                      {
                        title: "执行步骤",
                        dataIndex: "label",
                        render: (label: string) => {
                          const s = runScenario(report);
                          const f =
                            s &&
                            flowRevision(s.flowId, s.flowRevision)?.definition;
                          const [phase, nodeId, checkId] = label.split(":");
                          const n = f?.nodes.find((n) => n.id === nodeId) || f?.cleanup?.find(c=>c.call.id===nodeId)?.call;
                          const check = f?.testPoints.find(p => p.id === s?.testPointId)?.assertions[nodeId]?.find(a => a.id === checkId);
                          const description = check?.kind === "DATASTORE_VERIFY" ? (check.expected as { description: string }).description : "";
                          return n ? (
                            <>
                              <strong>{n.label}</strong>
                              <small className="bt-block">
                                {phase === "prepare" ? `请求预处理 · ${checkId === "request" ? "组装并校验最终请求" : n.preparation?.tools.find(t => t.id === checkId)?.tool || checkId}` : phase === "complete" ? "调用完成与输出汇总" : phase === "guard" ? "执行前校验" : phase === "observe" ? "Kafka 消息观察起点"
                                  : phase === "verify" ? description || "数据验证" : "接口请求与响应检查"}
                              </small>
                            </>
                          ) : (
                            label
                          );
                        },
                      },
                      {
                        title: "结果",
                        dataIndex: "success",
                        render: (v: boolean, row: { responseCode: string }) => (
                          <Tag color={v ? "green" : "red"}>
                            {v ? "通过" : row.responseCode === "FLOW_BLOCKED" ? "受阻，未派发" : row.responseCode.startsWith("PREPARE_") ? "执行失败" : "未通过"}
                          </Tag>
                        ),
                      },
                      { title: "结果码", dataIndex: "responseCode" },
                      {
                        title: "耗时",
                        dataIndex: "elapsed",
                        render: (v: number) => `${v} ms`,
                      },
                      { title: "说明", dataIndex: "failure", render: (v: string, row: { responseCode: string }) => preparationErrors[row.responseCode] || v },
                    ]}
                  />
                ) : (
                  <p className="bt-muted">
                    没有产生完整的步骤结果，请查看执行状态和错误说明。
                  </p>
                )}
                <details className="bt-advanced">
                  <summary>查看原始报告</summary>
                  <pre>{JSON.stringify(summaryReport(report), null, 2)}</pre>
                </details>
              </>
            )}
          </>
        )}
      </Drawer>
      <Drawer
        title="接口详情"
        width={600}
        open={!!operationDetail}
        onClose={() => setOperationDetail(null)}
      >
        {operationDetail && (
          <>
            <div className="bt-endpoint-title">
              <span
                className={`bt-method ${operationDetail.method.toLowerCase()}`}
              >
                {operationDetail.method}
              </span>
              <code>{operationDetail.path}</code>
            </div>
            <p>
              {operationDetail.system} / {operationDetail.service}
            </p>
            <h3>请求参数</h3>
            {Object.entries(operationDetail.inputs).length ? (
              Object.entries(operationDetail.inputs).map(([key, p]) => (
                <div className="bt-detail-row" key={key}>
                  <strong>{p.name}</strong>
                  <span>
                    {p.location} · {p.type}
                  </span>
                  <Tag>{p.required ? "必填" : "可选"}</Tag>
                </div>
              ))
            ) : (
              <p className="bt-muted">没有声明请求参数</p>
            )}
            <h3>响应状态</h3>
            <Space wrap>
              {Object.keys(operationDetail.responseSchema || {}).map((k) => (
                <Tag key={k}>{k}</Tag>
              ))}
            </Space>
            <h3>代码实现关联</h3>
            <p className="bt-muted">
              {operationDetail.implementations.length
                ? `${operationDetail.implementations.length} 个版本定位，可参与代码变更影响匹配。`
                : "尚未关联代码实现。仍可编排和测试；要匹配变更影响，请从相同系统、服务的代码版本导入入口。"}
            </p>
            <details className="bt-advanced">
              <summary>查看完整接口定义</summary>
              <pre>{JSON.stringify(operationDetail, null, 2)}</pre>
            </details>
          </>
        )}
      </Drawer>
      <Modal
        open={rawDetail !== null}
        onCancel={() => setRawDetail(null)}
        title="固定测试定义（高级）"
        footer={null}
        width={850}
      >
        <pre className="bt-json-view">{JSON.stringify(rawDetail, null, 2)}</pre>
      </Modal>
    </div>
  );
}

function WorkbenchArea({ name, active, children }: { name: BusinessTestingArea; active: BusinessTestingArea; children: ReactNode }) {
  const [visited, setVisited] = useState(name === active);
  if (name === active && !visited) setVisited(true);
  return visited || name === active ? <div hidden={name !== active}>{children}</div> : null;
}
