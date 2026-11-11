import CleanupEditor from "./CleanupEditor";
import CaseGenerationPanel from "./CaseGenerationPanel";
import OrderedFlowSteps from "./OrderedFlowSteps";
import OrderedFlowPlanner from "./OrderedFlowPlanner";
import { availableCalls } from "./orderedFlowModel";
import { useMemo, useState } from "react";
import {
  Alert,
  App,
  Button,
  Drawer,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
} from "antd";
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  PlusOutlined,
  SaveOutlined,
  SettingOutlined,
  LinkOutlined,
  AimOutlined,
  ApiOutlined,
} from "@ant-design/icons";
import type {
  Assertion,
  Flow,
  FlowNode,
  FlowRevision,
  Operation,
  TestPoint,
} from "../../api/businessTesting";
import { Field, EmptyState, ValueInput } from "./FormControls";
import VerificationEditor, { VerificationCard } from "./VerificationEditor";
import StepPicker from "./StepPicker";
import PreparationEditor from "./PreparationEditor";
import EnvironmentVariablePicker from "./EnvironmentVariablePicker";
import { preparedPort } from "./preparationModel";
import { appendPending, type PendingStep } from "./stepPickerModel";
import type { CatalogItem } from "./apiCatalogModel";
import type { ContextScope } from "../../api/projectContext";
import {
  defaultValue,
  flowIssues,
  hasOperationSchemas,
  parseFlowDraft,
  refOf,
  removeNode,
  statusLabel,
  typeOptions,
  uid,
  valueType,
} from "./workbenchModel";

export default function FlowEditor({
  flow,
  saved,
  operations,
  busy,
  onChange: emitChange,
  onBack,
  onSave,
  onReview,
  onImport,
  onOperation,
  onResolveCode,
  onConfigure,
  onGenerate,
  onGenerateStep,
  initialScope,
}: {
  flow: Flow;
  saved: FlowRevision | null;
  operations: Operation[];
  busy: boolean;
  onChange: (f: Flow) => void;
  onBack: () => void;
  onSave: () => void;
  onReview: () => void;
  onImport: () => void;
  onOperation: (operation: Operation) => void;
  onResolveCode: (item: CatalogItem, method: string) => Promise<Operation>;
  onConfigure: (operation: Operation) => void;
  onGenerate: () => void;
  onGenerateStep: () => void;
  initialScope?: ContextScope;
  active?: boolean;
}) {
  // 撤销/重做栈放在 state 中（按钮可用状态随之重新渲染）；切换流程时重置
  const [history,setHistory]=useState<{flowId:string;undo:Flow[];redo:Flow[]}>({flowId:flow.flowId,undo:[],redo:[]});
  if(history.flowId!==flow.flowId) setHistory({flowId:flow.flowId,undo:[],redo:[]});
  const onChange=(next:Flow)=>{
    setHistory(h=>({flowId:h.flowId,undo:[...h.undo,structuredClone(flow)].slice(-100),redo:[]}));
    emitChange(next);
  };
  const travel=(direction:"undo"|"redo")=>{
    const stack=history[direction];const next=stack[stack.length-1];if(!next)return;
    const current=structuredClone(flow);
    setHistory(h=>direction==="undo"
      ?{flowId:h.flowId,undo:h.undo.slice(0,-1),redo:[...h.redo,current]}
      :{flowId:h.flowId,undo:[...h.undo,current],redo:h.redo.slice(0,-1)});
    emitChange(next);
  };
  const { message } = App.useApp();
  const [selected, setSelected] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [pointId, setPointId] = useState(flow.testPoints[0]?.id || "normal");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [verificationEnvironment, setVerificationEnvironment] = useState("test");
  const [verificationEdit, setVerificationEdit] = useState<{ nodeId: string; pointId: string; assertion?: Assertion } | null>(null);
  const [rawOpen, setRawOpen] = useState(false);
  const [raw, setRaw] = useState("");
  const [rawError, setRawError] = useState("");
  const operationMap = useMemo(
    () => new Map(operations.map((o) => [refOf(o), o])),
    [operations],
  );
  const cleanup = flow.cleanup?.find(c=>c.call.id===selected);
  const point:TestPoint|undefined = cleanup ? {id:"cleanup",title:"清理",covers:[cleanup.call.id],assertions:{[cleanup.call.id]:cleanup.assertions},inputOverrides:{},intentionallyViolatedRule:null,endAtStepRef:null} :
    flow.testPoints.find((p) => p.id === pointId) || flow.testPoints[0];
  const node = flow.nodes.find((n) => n.id === selected) || cleanup?.call;
  const operation = node && operationMap.get(node.operationRef);
  const issues = flowIssues(flow, operations);
  const updateNode = (n: FlowNode) =>
    cleanup ? onChange({...flow,cleanup:flow.cleanup!.map(c=>c.call.id===n.id?{...c,call:n}:c)}) : onChange({
      ...flow,
      nodes: flow.nodes.map((old) => (old.id === n.id ? n : old)),
    });
  const updateAssertions = (next: Assertion[]) => {
    if(cleanup) {onChange({...flow,cleanup:flow.cleanup!.map(c=>c===cleanup?{...c,assertions:next}:c)});return;}
    if (node && point)
      onChange({
        ...flow,
        testPoints: flow.testPoints.map((p) =>
          p.id === point.id
            ? { ...p, assertions: { ...p.assertions, [node.id]: next } }
            : p,
        ),
      });
  };
  const availableVariables = [
    ...Object.entries(flow.initialContext).map(([key, v]) => ({
      key,
      type: v.type,
      label: `${key} · 运行时填写`,
    })),
    ...flow.nodes
      .filter((n) => selected && availableCalls(flow, selected).has(n.id))
      .flatMap((n) =>
        n.outputs.map((o) => ({
          key: o.key,
          type: o.type,
          label: `${o.key} · 来自 ${n.label}`,
        })),
      ),
  ];
  const addBatch = (steps: PendingStep[], fill: boolean) => {
    const next = appendPending(flow, steps);
    onChange(next); setAddOpen(false);
    if (fill) onGenerate();
    else message.success(`已按顺序加入 ${steps.length} 个步骤，可继续编排或使用 AI 补全`);
  };
  const fields: { value: string; label: string; type: string }[] = [];
  const walk = (schema: unknown, path: string, depth: number) => {
    if (!schema || typeof schema !== "object" || depth > 6) return;
    const s = schema as { type?: string; properties?: Record<string, unknown> };
    if (s.type && path !== "$")
      fields.push({ value: path, label: path, type: s.type });
    Object.entries(s.properties || {}).forEach(([k, v]) =>
      walk(v, `${path}.${k}`, depth + 1),
    );
  };
  Object.values(operation?.responseSchema || {}).forEach((s) =>
    walk(s, "$", 0),
  );
  return (
    <div className="bt-flow-workspace">
      <div className="bt-editor-heading">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={onBack}>
          所有流程
        </Button>
        <Tag color={saved?.status === "CONFIRMED" ? "green" : "gold"}>
          {saved
            ? `${statusLabel(saved.status)} · v${flow.revision}`
            : "未保存的草稿"}
        </Tag>
        <div className="bt-grow" />
        <Button disabled={!history.undo.length||busy} onClick={()=>travel("undo")}>撤销</Button>
        <Button disabled={!history.redo.length||busy} onClick={()=>travel("redo")}>重做</Button>
        <Button icon={<SaveOutlined />} loading={busy} onClick={onSave}>
          保存草稿
        </Button>
        <Button
          type="primary"
          icon={<CheckCircleOutlined />}
          loading={busy}
          onClick={onReview}
          disabled={!flow.nodes.length}
        >
          检查并生成测试
        </Button>
      </div>
      <div className="bt-flow-title">
        <Input
          aria-label="业务流程名称"
          variant="borderless"
          placeholder="给流程起个名字，例如：用户登录与查询资料"
          value={flow.name}
          onChange={(e) => onChange({ ...flow, name: e.target.value })}
        />
        <p>
          将接口排成业务步骤。点击步骤填写参数和预期结果，输出的数据可以直接传给下一步。
        </p>
      </div>
      <div className="bt-editor-toolbar">
        <Space wrap>
          <span className="bt-muted">当前测试点</span>
          <Select
            aria-label="当前测试点"
            value={point?.id}
            options={flow.testPoints.map((p) => ({
              value: p.id,
              label: p.title,
            }))}
            onChange={setPointId}
            style={{ minWidth: 160 }}
          />
          {flow.steps && point && <Select aria-label="用例结束步骤" value={point.endAtStepRef || ""} style={{minWidth:180}}
            options={[{value:"",label:"执行完整流程"},...flow.steps.map(step => ({value:step.id,label:`执行至：${step.label}`}))]}
            onChange={endAtStepRef => {
              const end=flow.steps!.findIndex(s => s.id===endAtStepRef);
              const included=new Set((end<0?flow.steps!:flow.steps!.slice(0,end+1)).flatMap(s=>s.calls.map(c=>c.id)));
              onChange({...flow,testPoints:flow.testPoints.map(p=>p.id===point.id?{...p,endAtStepRef:endAtStepRef||null,
                covers:p.covers.filter(id=>included.has(id)),
                assertions:Object.fromEntries(Object.entries(p.assertions).filter(([id])=>included.has(id))),
                inputOverrides:Object.fromEntries(Object.entries(p.inputOverrides).filter(([id])=>included.has(id)))}:p)});
            }} />}
          <Button
            type="text"
            icon={<PlusOutlined />}
            onClick={() => {
              const id = uid("point");
              onChange({
                ...flow,
                testPoints: [
                  ...flow.testPoints,
                  {
                    id,
                    title: `测试点 ${flow.testPoints.length + 1}`,
                    covers: flow.nodes.map((n) => n.id),
                    assertions: structuredClone(point?.assertions || {}),
                    inputOverrides: {},
                    intentionallyViolatedRule: null,
                  },
                ],
              });
              setPointId(id);
            }}
          >
            测试点
          </Button>
        </Space>
        <Space>
          {point && !cleanup && <CaseGenerationPanel flow={flow} saved={saved} pointId={point.id} onApply={next=>{onChange(next);setPointId(next.testPoints.at(-1)!.id);}}/>}
          {!flow.nodes.length && <OrderedFlowPlanner operations={operations} onApply={onChange} />}
          <Button type="primary" disabled={busy || !flow.nodes.length} onClick={onGenerate}>AI 补全整个链路</Button>
          <Button disabled={busy || !flow.nodes.length} onClick={onGenerateStep}>补全单个步骤</Button>
          <Button
            icon={<SettingOutlined />}
            onClick={() => setSettingsOpen(true)}
          >
            流程设置
          </Button>
          <Button icon={<PlusOutlined />} onClick={() => setAddOpen(true)}>
            添加接口步骤
          </Button>
        </Space>
      </div>
      <div className="bt-canvas">
        {!flow.nodes.length ? (
          <EmptyState
            icon={<ApiOutlined />}
            title="从第一个接口开始"
            description="比如先登录，再使用登录结果查询资料。添加接口后，可以调整步骤顺序和串并行模式。"
            action={
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => setAddOpen(true)}
              >
                选择第一个接口
              </Button>
            }
          />
        ) : (
          <OrderedFlowSteps flow={flow} onChange={onChange} onSelect={setSelected} selected={selected} />
        )}
      </div>
      {flow.nodes.length > 0 && (
        <div className="bt-step-strip">
          {flow.nodes.map((n, i) => (
            <button key={n.id} onClick={() => setSelected(n.id)}>
              <span>{String(i + 1).padStart(2, "0")}</span>
              {n.label}
            </button>
          ))}
        </div>
      )}
      <div className={`bt-readiness ${issues.length ? "" : "ready"}`}>
        <CheckCircleOutlined />
        <div>
          <strong>
            {issues.length
              ? `还有 ${issues.length} 项配置需要完成`
              : "基础配置已齐全，可以检查并生成测试"}
          </strong>
          <p>
            {issues.slice(0, 2).join("；") ||
              "确认预期状态码和业务断言后，生成可执行的测试场景。"}
          </p>
        </div>
      </div>
      <CleanupEditor flow={flow} operations={operations} onChange={onChange} onSelect={setSelected} />
      <StepPicker open={addOpen} flow={flow} operations={operations} initialScope={initialScope}
        onClose={() => setAddOpen(false)} onAppend={addBatch} onOperation={onOperation}
        onConfigure={onConfigure} onResolveCode={onResolveCode} onImport={onImport} />
      <Drawer
        title={
          node
            ? `配置步骤 · ${flow.nodes.findIndex((n) => n.id === node.id) + 1}`
            : "配置步骤"
        }
        width={620}
        open={!!node}
        onClose={() => setSelected(null)}
        footer={
          <div className="bt-dialog-actions">
            <Button
              danger
              icon={<DeleteOutlined />}
              onClick={() => {
                if (node) {
                  if(cleanup) onChange({...flow,cleanup:flow.cleanup!.filter(c=>c!==cleanup)});
                  else onChange(removeNode(flow, node.id));
                  setSelected(null);
                }
              }}
            >
              移除步骤
            </Button>
            <Button type="primary" onClick={() => setSelected(null)}>
              完成配置
            </Button>
          </div>
        }
      >
        {node && operation && (
          <>
            <div className="bt-endpoint-title">
              <span className={`bt-method ${operation.method.toLowerCase()}`}>
                {operation.method}
              </span>
              <code>{operation.path}</code>
            </div>
            <Field
              label="步骤名称"
              hint="用业务动作命名，更容易理解，例如：获取登录凭证"
            >
              <Input
                value={node.label}
                onChange={(e) => updateNode({ ...node, label: e.target.value })}
              />
            </Field>
            <PreparationEditor flow={flow} node={node} operation={operation} onChange={preparation => updateNode({ ...node, preparation })} />
            <section className="bt-inspector-section">
              {!hasOperationSchemas(operation) && <Alert type="info" showIcon
                message="已引用该 API，请补充测试配置"
                description="无需重新导入项目。可先编排并保存草稿，生成测试前确认请求参数和响应定义。"
              />}
              <Button style={{ marginTop: 12 }} onClick={() => onConfigure(operation)}>
                {hasOperationSchemas(operation) ? "编辑接口测试配置" : "配置请求参数与响应"}
              </Button>
              <h3>
                <span>1</span> 这个接口需要什么？
              </h3>
              <p>显示当前测试点的实际输入。可覆盖单个测试点的数据，未覆盖的参数沿用流程配置。</p>
              {point?.generatedFrom && <Alert type="info" message={`候选来源：${point.generatedFrom.sourceRule}`} style={{marginBottom:12}}/>}
              {!Object.keys(operation.inputs).length && (
                <div className="bt-info-line">{hasOperationSchemas(operation)
                  ? "这个接口没有配置请求参数。" : "当前分析结果未提供完整参数定义，请按接口实际要求补充。"}</div>
              )}
              {Object.entries(operation.inputs).map(([key, port]) => {
                if (preparedPort(node.preparation, port) && !node.inputs[key]) return <div className="bt-info-line" key={key}>{key} · 由请求预处理生成</div>;
                const override=!cleanup && point?.inputOverrides[node.id]?.[key];
                const binding = override || node.inputs[key];
                const update = (source: string, value: unknown) => {
                  if(override && point) {onChange({...flow,testPoints:flow.testPoints.map(p=>p.id===point.id?{...p,inputOverrides:{...p.inputOverrides,[node.id]:{...p.inputOverrides[node.id],[key]:{source,value}}}}:p)});return;}
                  updateNode({
                    ...node,
                    inputs: { ...node.inputs, [key]: { source, value } },
                  });
                };
                return (
                  <div className="bt-param" key={key}>
                    <div className="bt-row-title">
                      <strong>
                        {port.name === "$" ? "请求内容" : port.name}
                      </strong>
                      <Tag>{port.location}</Tag>
                      {!cleanup && point && <Space size={4}><Switch size="small" aria-label={`${port.name} 仅当前测试点覆盖`} checked={!!override} onChange={checked=>{
                        const inputs={...point.inputOverrides[node.id]};
                        if(checked) inputs[key]=structuredClone(binding||{source:"literal",value:defaultValue(port.type)});else delete inputs[key];
                        onChange({...flow,testPoints:flow.testPoints.map(p=>p.id===point.id?{...p,inputOverrides:{...p.inputOverrides,[node.id]:inputs}}:p)});
                      }}/><small>仅当前测试点覆盖</small></Space>}
                      {preparedPort(node.preparation, port) && <Tag color="purple">保留原始配置，发送值由预处理生成</Tag>}
                      <small>{port.required ? "必填" : "可选"}</small>
                      {!port.required && binding && !override && (
                        <Button
                          type="text"
                          size="small"
                          onClick={() =>
                            updateNode({
                              ...node,
                              inputs: Object.fromEntries(
                                Object.entries(node.inputs).filter(
                                  ([k]) => k !== key,
                                ),
                              ),
                            })
                          }
                        >
                          不发送
                        </Button>
                      )}
                    </div>
                    <Field label={`${port.name} 的数据来源`}>
                      <Select
                        placeholder="选择来源"
                        value={binding?.source}
                        options={[
                          { value: "literal", label: "填写测试数据" },
                          { value: "context", label: "引用预置或上游数据" },
                          { value: "environment", label: "引用当前调用的环境配置" },
                          { value: "secret", label: "使用环境中的密钥" },
                        ]}
                        onChange={(source) =>
                          update(
                            source,
                            source === "literal" ? defaultValue(port.type) : "",
                          )
                        }
                      />
                    </Field>
                    {binding?.source === "context" ? (
                      <Field label={`${port.name} 引用的数据`}>
                        <Select
                          showSearch
                          optionFilterProp="label"
                          value={String(binding.value) || undefined}
                          placeholder="选择数据，例如登录步骤的 token"
                          options={availableVariables
                            .filter((v) => v.type === port.type)
                            .map((v) => ({ value: v.key, label: v.label }))}
                          onChange={(value) => update("context", value)}
                        />
                      </Field>
                    ) : binding?.source === "environment" ? (
                      <Field label={`${port.name} 环境变量 key`} hint="按当前接口所属模块、项目、工作空间逐层解析；不与其他调用的同名变量合并。跨范围引用可在高级定义中指定来源。">
                        <Input value={typeof binding.value==="string"?binding.value:JSON.stringify(binding.value)}
                          onChange={e=>update("environment",e.target.value)} />
                      </Field>
                    ) : binding?.source === "secret" ? (
                      <Field
                        label={`${port.name} 密钥引用名`}
                        hint="这里填写引用名称，运行时再绑定环境变量，不填真实密钥。"
                      >
                        <Input
                          value={String(binding.value)}
                          onChange={(e) => update("secret", e.target.value)}
                        />
                      </Field>
                    ) : (
                      binding && (
                        <ValueInput
                          label={`${port.name} 测试数据`}
                          value={binding.value}
                          type={port.type}
                          onChange={(value) => update("literal", value)}
                        />
                      )
                    )}
                  </div>
                );
              })}
            </section>
            <section className="bt-inspector-section">
              <h3>
                <span>2</span> 怎么判断它是正确的？
              </h3>
              <p>
                为「{point?.title}」设置检查项。状态码的初始建议需要你确认。
              </p>
              {(point?.assertions[node.id] || []).map((a, i) => {
                const all = point!.assertions[node.id];
                const update = (patch: Partial<Assertion>) =>
                  updateAssertions(
                    all.map((v, j) => (i === j ? { ...v, ...patch } : v)),
                  );
                if (a.kind === "DATASTORE_VERIFY") return <VerificationCard key={a.id} assertion={a}
                  onEdit={() => setVerificationEdit({ nodeId: node.id, pointId: point!.id, assertion: a })}
                  onRemove={() => updateAssertions(all.filter((_, j) => j !== i))} />;
                return (
                  <div className="bt-param" key={a.id}>
                    <div className="bt-row-title">
                      <strong>
                        {a.kind === "STATUS_EQUALS"
                          ? "预期响应状态码"
                          : `业务检查 ${i}`}
                      </strong>
                      {a.kind !== "STATUS_EQUALS" && (
                        <Button
                          type="text"
                          danger
                          icon={<DeleteOutlined />}
                          aria-label={`删除检查项 ${i}`}
                          onClick={() =>
                            updateAssertions(all.filter((_, j) => j !== i))
                          }
                        />
                      )}
                    </div>
                    {a.kind === "STATUS_EQUALS" ? (
                      <Field label="预期响应状态码">
                        <InputNumber
                          min={100}
                          max={599}
                          value={Number(a.expected)}
                          onChange={(v) => update({ expected: v ?? 200 })}
                        />
                      </Field>
                    ) : (
                      <>
                        <Field label={`检查项 ${i} 条件`}>
                          <Select
                            value={a.kind}
                            options={[
                              {
                                value: "JSON_EQUALS",
                                label: "响应字段等于预期值",
                              },
                              {
                                value: "JSON_EXISTS",
                                label: "响应中存在这个字段",
                              },
                              {
                                value: "SCHEMA_CONFORMS",
                                label: "响应符合接口契约（Schema）",
                              },
                            ]}
                            onChange={(kind) =>
                              update(
                                kind === "SCHEMA_CONFORMS"
                                  ? { kind, path: "$", expected: null }
                                  : { kind },
                              )
                            }
                          />
                        </Field>
                        {a.kind === "SCHEMA_CONFORMS" && (
                          <Alert
                            type="info"
                            showIcon
                            style={{ marginBottom: 8 }}
                            message="按该步骤预期状态码在接口契约中的响应 Schema 校验整个响应体（类型、必填、枚举、范围等）。需要安装最新的 JMeter 扩展。"
                          />
                        )}
                        {a.kind !== "SCHEMA_CONFORMS" && (
                        <Field
                          label={`检查项 ${i} 响应字段`}
                          hint="例如 $.data.id 或 $.success"
                        >
                          <Input
                            value={a.path || ""}
                            onChange={(e) => update({ path: e.target.value })}
                          />
                        </Field>
                        )}
                        {a.kind === "JSON_EQUALS" && (
                          <>
                            <Field label={`检查项 ${i} 值类型`}>
                              <Select
                                value={valueType(a.expected)}
                                options={typeOptions.filter(
                                  (t) => t.value !== "integer",
                                )}
                                onChange={(type) =>
                                  update({ expected: defaultValue(type) })
                                }
                              />
                            </Field>
                            <ValueInput
                              label={`检查项 ${i} 预期值`}
                              value={a.expected}
                              onChange={(expected) => update({ expected })}
                            />
                          </>
                        )}
                      </>
                    )}
                  </div>
                );
              })}
              <Button
                icon={<PlusOutlined />}
                onClick={() =>
                  updateAssertions([
                    ...(point?.assertions[node.id] || []),
                    {
                      id: uid("check"),
                      kind: "JSON_EQUALS",
                      path: "$.success",
                      expected: true,
                    },
                  ])
                }
              >
                添加响应检查
              </Button>
              <Button style={{ marginTop: 12 }} icon={<AimOutlined />} disabled={!point}
                onClick={() => setVerificationEdit({ nodeId: node.id, pointId: point!.id })}>验证数据库 / Redis / Kafka</Button>
            </section>
            {point?.suppressedOutputCalls?.includes(node.id) && <Alert type="info" message="此测试点预期请求被拒绝，不提取正常成功响应的输出。"/>}
            {!cleanup && !point?.suppressedOutputCalls?.includes(node.id) && <section className="bt-inspector-section">
              <h3>
                <span>3</span> 后面的步骤需要哪些结果？
              </h3>
              <p>提取 token、订单号等返回值，后续步骤可直接选择这些数据。</p>
              {node.outputs.map((o, i) => {
                const update = (patch: Partial<typeof o>) =>
                  updateNode({
                    ...node,
                    outputs: node.outputs.map((v, j) =>
                      i === j ? { ...v, ...patch } : v,
                    ),
                  });
                return (
                  <div className="bt-param" key={i}>
                    <Field
                      label={`输出 ${i + 1} 数据名称`}
                      hint="例如 auth.token 或 order.id"
                    >
                      <Input
                        value={o.key}
                        onChange={(e) => update({ key: e.target.value })}
                      />
                    </Field>
                    <Field label={`输出 ${i + 1} 响应字段`}>
                      <Input
                        list="bt-response-fields"
                        placeholder="$.data.token"
                        value={o.path}
                        onChange={(e) => update({ path: e.target.value })}
                      />
                    </Field>
                    <div className="bt-form-grid">
                      <Field label={`输出 ${i + 1} 数据类型`}>
                        <Select
                          value={o.type}
                          options={typeOptions}
                          onChange={(type) => update({ type })}
                        />
                      </Field>
                      <Field label={`输出 ${i + 1} 敏感数据`}>
                        <Switch
                          checked={o.sensitive}
                          onChange={(sensitive) => update({ sensitive })}
                        />
                      </Field>
                    </div>
                    <Button
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() =>
                        updateNode({
                          ...node,
                          outputs: node.outputs.filter((_, j) => j !== i),
                        })
                      }
                    >
                      删除输出
                    </Button>
                  </div>
                );
              })}
              <datalist id="bt-response-fields">
                {[...new Set(fields.map((f) => f.value))].map((v) => (
                  <option value={v} key={v} />
                ))}
              </datalist>
              <Button
                icon={<LinkOutlined />}
                onClick={() =>
                  updateNode({
                    ...node,
                    outputs: [
                      ...node.outputs,
                      {
                        key: `result_${flow.nodes.findIndex((n) => n.id === node.id) + 1}_${node.outputs.length + 1}`,
                        path: "$.data.id",
                        type: "string",
                        sensitive: false,
                      },
                    ],
                  })
                }
              >
                传递一个返回值
              </Button>
            </section>}
            {!cleanup && <details className="bt-advanced">
              <summary>更多设置</summary>
              <Field label="执行前等待（毫秒）">
                <InputNumber
                  min={0}
                  max={60000}
                  value={node.waitMillis}
                  onChange={(v) => updateNode({ ...node, waitMillis: v || 0 })}
                />
              </Field>
              <Field label="轮询直到检查项通过" hint="仅限在「JMeter 扩展与函数 → 轮询授权」中显式授权的 API；参数不能超过授权的间隔、次数与超时，否则确认与运行都会被拒绝。每隔一段时间重发同一请求，直到本步骤全部检查项通过；超时则判为失败。">
                <Space wrap>
                  <Switch
                    checked={!!node.poll}
                    onChange={(on) => updateNode({ ...node, poll: on ? { intervalMillis: 2000, timeoutMillis: 60000 } : null })}
                  />
                  {node.poll && (
                    <>
                      <span>间隔（毫秒）</span>
                      <InputNumber min={200} max={30000} value={node.poll.intervalMillis}
                        onChange={(v) => updateNode({ ...node, poll: { ...node.poll!, intervalMillis: v || 2000 } })} />
                      <span>总时长（毫秒）</span>
                      <InputNumber min={1000} max={300000} value={node.poll.timeoutMillis}
                        onChange={(v) => updateNode({ ...node, poll: { ...node.poll!, timeoutMillis: v || 60000 } })} />
                    </>
                  )}
                </Space>
              </Field>
              <p className="bt-muted">
                此步骤配置了 {node.states.length}{" "}
                项实体状态约束；复杂状态规则可在流程设置的高级定义中维护。
              </p>
            </details>}
          </>
        )}
      </Drawer>
      <Drawer
        title="流程设置"
        width={600}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
      >
        <Field label="测试点名称">
          <Input
            value={point?.title || ""}
            onChange={(e) =>
              onChange({
                ...flow,
                testPoints: flow.testPoints.map((p) =>
                  p.id === point?.id ? { ...p, title: e.target.value } : p,
                ),
              })
            }
          />
        </Field>
        <p className="bt-muted">
          测试点描述这次要验证的业务目标。同一流程可设置多个测试点，每个测试点有自己的检查项。
        </p>
        <h3>运行前需要准备的数据</h3>
        <p className="bt-muted">在这里声明数据名称，具体值可在运行时填写或从环境配置加载。</p>
        {settingsOpen && <EnvironmentVariablePicker flow={flow} operations={operations} disabled={busy} onChange={onChange} />}
        {Object.entries(flow.initialContext).map(([k, v]) => (
          <div className="bt-param" key={k}>
            <strong>{k}</strong>
            <div className="bt-form-grid">
              <Field label={`${k} 类型`}>
                <Select
                  value={v.type}
                  options={typeOptions}
                  onChange={(type) =>
                    onChange({
                      ...flow,
                      initialContext: {
                        ...flow.initialContext,
                        [k]: { ...v, type },
                      },
                    })
                  }
                />
              </Field>
              <Field label={`${k} 敏感数据`}>
                <Switch
                  checked={v.sensitive}
                  onChange={(sensitive) =>
                    onChange({
                      ...flow,
                      initialContext: {
                        ...flow.initialContext,
                        [k]: { ...v, sensitive },
                      },
                    })
                  }
                />
              </Field>
            </div>
            <Button
              danger
              type="text"
              onClick={() =>
                onChange({
                  ...flow,
                  initialContext: Object.fromEntries(
                    Object.entries(flow.initialContext).filter(
                      ([key]) => key !== k,
                    ),
                  ),
                })
              }
            >
              移除声明
            </Button>
          </div>
        ))}
        <Input.Search
          aria-label="新增初始数据名称"
          placeholder="数据名称，例如 user.id"
          enterButton="添加数据"
          onSearch={(key) => {
            if (
              !/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(key) ||
              flow.initialContext[key]
            ) {
              message.warning("使用唯一的英文数据名称，例如 user.id");
              return;
            }
            onChange({
              ...flow,
              initialContext: {
                ...flow.initialContext,
                [key]: { type: "string", sensitive: false },
              },
            });
          }}
        />
        <h3>步骤间的关系</h3>
        {flow.dependencies.map((d, i) => (
          <div className="bt-param" key={`${d.from}-${d.to}`}>
            <strong>
              {flow.nodes.find((n) => n.id === d.from)?.label} →{" "}
              {flow.nodes.find((n) => n.id === d.to)?.label}
            </strong>
            <Field label={`依赖 ${i + 1} 原因`}>
              <Input
                value={d.reason}
                onChange={(e) =>
                  onChange({
                    ...flow,
                    dependencies: flow.dependencies.map((v, j) =>
                      i === j ? { ...v, reason: e.target.value } : v,
                    ),
                  })
                }
              />
            </Field>
            <Button
              type="text"
              danger
              onClick={() =>
                onChange({
                  ...flow,
                  dependencies: flow.dependencies.filter((_, j) => j !== i),
                })
              }
            >
              移除顺序约束
            </Button>
          </div>
        ))}
        <details className="bt-advanced">
          <summary>高级定义与状态规则</summary>
          <p>
            维护实体状态约束、负向输入覆盖或完整流程定义。常规 HTTP
            测试无需使用此选项。
          </p>
          <Button
            onClick={() => {
              setRaw(JSON.stringify(flow, null, 2));
              setRawError("");
              setRawOpen(true);
            }}
          >
            编辑高级定义
          </Button>
        </details>
      </Drawer>
      {verificationEdit && <VerificationEditor flow={flow} initialEnvironment={verificationEnvironment} onEnvironmentChange={setVerificationEnvironment}
        operation={operationMap.get((flow.nodes.find(n => n.id === verificationEdit.nodeId) || flow.cleanup?.find(c=>c.call.id===verificationEdit.nodeId)?.call)?.operationRef || "")}
        assertion={verificationEdit.assertion} onClose={() => setVerificationEdit(null)}
        onSave={assertion => {
          if(verificationEdit.pointId==="cleanup") {
            onChange({...flow,cleanup:flow.cleanup?.map(c=>c.call.id===verificationEdit.nodeId?{...c,assertions:[...c.assertions.filter(a=>a.id!==assertion.id),assertion]}:c)});
            setVerificationEdit(null);return;
          }
          onChange({ ...flow, testPoints: flow.testPoints.map(p => p.id !== verificationEdit.pointId ? p : {
            ...p, assertions: { ...p.assertions, [verificationEdit.nodeId]: [
              ...(p.assertions[verificationEdit.nodeId] || []).filter(a => a.id !== assertion.id), assertion,
            ] },
          }) });
          setVerificationEdit(null);
        }} />}
      <Modal
        title="高级流程定义"
        open={rawOpen}
        width={850}
        onCancel={() => setRawOpen(false)}
        onOk={() => {
          try {
            const f = parseFlowDraft(raw);
            onChange(f);
            setRawOpen(false);
          } catch (e) {
            setRawError((e as Error).message);
          }
        }}
        okText="应用定义"
      >
        <Input.TextArea
          aria-label="高级流程 JSON"
          rows={18}
          value={raw}
          onChange={(e) => setRaw(e.target.value)}
        />
        {rawError && <Alert type="error" message={rawError} />}
      </Modal>
    </div>
  );
}
