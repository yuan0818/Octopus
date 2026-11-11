import { synchronizeOrdered, orderedReferenceIssues } from "./orderedFlowModel.ts";
import { preparationValues, preparationIssues, preparedPort } from "./preparationModel.ts";
import { verificationValues } from "./verificationModel.ts";
import type { VerificationPlan } from "../../api/verification";
import type {
  Flow,
  FlowNode,
  Operation,
  Scenario,
  Profile,
} from "../../api/businessTesting";

export const typeOptions = [
  { value: "string", label: "文本" },
  { value: "integer", label: "整数" },
  { value: "number", label: "小数" },
  { value: "boolean", label: "是 / 否" },
  { value: "object", label: "对象" },
  { value: "array", label: "列表" },
];
export const refOf = (o: Operation) => `${o.operationId}@${o.revision}`;
export const hasOperationSchemas = (operation: Operation) =>
  !!operation.requestSchema && !!operation.responseSchema;
export const uid = (prefix: string) =>
  `${prefix}_${crypto.randomUUID().replaceAll("-", "").slice(0, 12)}`;
export const defaultValue = (type: string): unknown =>
  ({
    string: "",
    integer: 0,
    number: 0,
    boolean: false,
    object: {},
    array: [],
  })[type];
export const valueType = (value: unknown): string =>
  Array.isArray(value) ? "array" : value === null ? "string" : typeof value;
export const statusLabel = (status: string) =>
  ({
    DRAFT: "待确认",
    CONFIRMED: "已确认",
    REJECTED: "已退回",
    QUEUED: "排队中",
    RUNNING: "运行中",
    COMPLETED: "已完成",
    CANCELLED: "已取消",
    TIMED_OUT: "已超时",
    INTERRUPTED: "已中断",
    FAILED: "执行异常",
    PASS: "通过",
    FAIL: "未通过",
    INCONCLUSIVE: "尚无结论",
  })[status] || status;
export const errorText = (e: unknown) =>
  (e as { response?: { data?: { error?: string } } })?.response?.data?.error ||
  (e as { response?: { data?: { message?: string } } })?.response?.data?.message ||
  (e as Error)?.message ||
  "操作未完成，请重试";
export function emptyFlow(ordered = true): Flow {
  return {
    flowId: uid("flow"),
    revision: 0,
    name: "",
    initialContext: {},
    nodes: [],
    ...(ordered ? { steps: [] } : {}),
    dependencies: [],
    stateModels: [],
    testPoints: [
      {
        id: "normal",
        title: "正常业务流程",
        covers: [],
        assertions: {},
        inputOverrides: {},
        intentionallyViolatedRule: null,
      },
    ],
  };
}
export function appendOperation(flow: Flow, operation: Operation): Flow {
  const id = uid("step");
  const node: FlowNode = {
    id,
    label: `${operation.method} ${operation.path}`,
    operationRef: refOf(operation),
    inputs: {},
    outputs: [],
    states: [],
    waitMillis: 0,
  };
  const previous = flow.nodes.at(-1);
  const response = Object.keys(operation.responseSchema || {}).find((k) =>
    /^2\d\d$/.test(k),
  );
  return synchronizeOrdered({
    ...flow,
    nodes: [...flow.nodes, node],
    dependencies: !flow.steps && previous
      ? [
          ...flow.dependencies,
          {
            from: previous.id,
            to: id,
            kind: "ORDERING_CONSTRAINT",
            reason: "按添加的业务步骤顺序执行",
          },
        ]
      : flow.dependencies,
    testPoints: flow.testPoints.map((p) => ({
      ...p,
      covers: [...p.covers, id],
      assertions: {
        ...p.assertions,
        [id]: response ? [
          {
            id: "status",
            kind: "STATUS_EQUALS",
            path: null,
            expected: Number(response),
          },
        ] : [],
      },
    })),
  });
}
export function removeNode(flow: Flow, id: string): Flow {
  return synchronizeOrdered({
    ...flow,
    nodes: flow.nodes.filter((n) => n.id !== id),
    dependencies: flow.dependencies.filter((d) => d.from !== id && d.to !== id),
    testPoints: flow.testPoints.map((p) => ({
      ...p,
      covers: p.covers.filter((x) => x !== id),
      assertions: Object.fromEntries(
        Object.entries(p.assertions).filter(([x]) => x !== id),
      ),
      inputOverrides: Object.fromEntries(
        Object.entries(p.inputOverrides).filter(([x]) => x !== id),
      ),
    })),
  });
}
export function flowEdges(flow: Flow) {
  const producers = new Map(
    flow.nodes.flatMap((n) => n.outputs.map((o) => [o.key, n.id] as const)),
  );
  return [
    ...flow.dependencies.map((d, i) => ({
      id: `order-${i}`,
      source: d.from,
      target: d.to,
      label: d.kind === "ORDERING_CONSTRAINT" ? "执行顺序" : d.reason,
      inferred: false,
    })),
    ...flow.nodes.flatMap((n) =>
      [
        ...new Set([
          ...[...Object.values(n.inputs), ...preparationValues(n.preparation)]
            .filter((b) => b.source === "context")
            .map((b) => String(b.value)),
          ...flow.testPoints.flatMap(p => (p.assertions[n.id] || []).filter(a => a.kind === "DATASTORE_VERIFY")
            .flatMap(a => verificationValues(a.expected as VerificationPlan)).filter(v => v.source === "context" && producers.get(String(v.value)) !== n.id).map(v => String(v.value))),
          ...n.states.flatMap((s) => [
            s.entityContext,
            s.observedContext,
            s.observedEntityContext,
          ]),
        ]),
      ]
        .filter((k) => producers.has(k))
        .map((k) => ({
          id: `data-${n.id}-${k}`,
          source: producers.get(k)!,
          target: n.id,
          label: k,
          inferred: true,
        })),
    ),
  ];
}
export function hasCycle(flow: Flow) {
  const edges = flowEdges(flow);
  const remaining = new Set(flow.nodes.map((n) => n.id));
  while (remaining.size) {
    const ready = [...remaining].filter(
      (id) => !edges.some((e) => e.target === id && remaining.has(e.source)),
    );
    if (!ready.length) return true;
    ready.forEach((id) => remaining.delete(id));
  }
  return false;
}
export function flowIssues(flow: Flow, operations: Operation[]): string[] {
  const issues: string[] = [];
  const byRef = new Map(operations.map((o) => [refOf(o), o]));
  if (!flow.name.trim()) issues.push("给这条业务流程起一个名字");
  if (!flow.nodes.length) issues.push("至少添加一个接口步骤");
  if (!flow.testPoints.length) issues.push("至少添加一个测试点");
  const keys = [
    ...Object.keys(flow.initialContext),
    ...flow.nodes.flatMap((n) => n.outputs.map((o) => o.key)),
  ];
  if (new Set(keys).size !== keys.length)
    issues.push("数据名称重复，请为每个输出使用不同名称");
  for (const n of flow.nodes) {
    const op = byRef.get(n.operationRef);
    if (!op) {
      issues.push(`${n.label}：接口版本已不可用`);
      continue;
    }
    if (!op.requestSchema || !op.responseSchema)
      issues.push(`${n.label}：请补充请求参数与响应定义`);
    for (const [key, port] of Object.entries(op.inputs)) {
      if (port.required && !n.inputs[key] && !preparedPort(n.preparation, port))
        issues.push(`${n.label}：配置必填参数 ${key}`);
      const b = n.inputs[key];
      if (
        b?.source === "context" &&
        (!keys.includes(String(b.value)) ||
          n.outputs.some((o) => o.key === b.value))
      )
        issues.push(`${n.label}：${key} 引用的数据不可用`);
      if (b?.source === "secret" && !String(b.value).trim())
        issues.push(`${n.label}：填写 ${key} 的密钥引用名称`);
    }
    issues.push(...preparationIssues(n.preparation).map(e => `${n.label}：${e}`));
    for (const point of flow.testPoints) {
      if (point.endAtStepRef && flow.steps) {
        const end = flow.steps.findIndex(s => s.id === point.endAtStepRef);
        if (end < 0) issues.push(`${point.title}：结束步骤不存在`);
        else if (!flow.steps.slice(0,end+1).some(s => s.calls.some(c => c.id === n.id))) continue;
      }
      const assertions = point.assertions[n.id] || [];
      for (const a of assertions.filter(a => a.kind === "DATASTORE_VERIFY")) {
        const p = a.expected as VerificationPlan;
        if (!p?.connection?.connectionId || !p.description || !p.expect?.path) issues.push(`${point.title} / ${n.label}：完善数据验证配置`);
        else if (verificationValues(p).some(v => v.source === "context" && !keys.includes(String(v.value)))) issues.push(`${point.title} / ${n.label}：数据验证引用的流程变量不可用`);
      }
      if (assertions.filter((a) => a.kind === "STATUS_EQUALS").length !== 1)
        issues.push(`${point.title} / ${n.label}：需要一个预期状态码`);
      if (assertions.some((a) => a.kind !== "STATUS_EQUALS" && a.kind !== "DATASTORE_VERIFY" && !a.path))
        issues.push(`${point.title} / ${n.label}：填写检查的响应字段`);
    }
  }
  issues.push(...orderedReferenceIssues(flow, n => [
    ...[...Object.values(n.inputs), ...preparationValues(n.preparation),
      ...flow.testPoints.flatMap(p => Object.values(p.inputOverrides[n.id] || {}))]
      .filter(b => b.source === "context").map(b => String(b.value)),
    ...n.states.flatMap(s=>[s.entityContext,s.observedContext,s.observedEntityContext]),
    ...flow.testPoints.flatMap(p=>(p.assertions[n.id]||[]).filter(a=>a.kind==="DATASTORE_VERIFY")
      .flatMap(a=>verificationValues(a.expected as VerificationPlan)).filter(b=>b.source==="context" && !n.outputs.some(o=>o.key===b.value)).map(b=>String(b.value))),
  ]));
  for(const point of flow.testPoints) for(const id of point.suppressedOutputCalls||[]) {
    if(!point.endAtStepRef || !flow.steps) issues.push(`${point.title}：负向用例需要明确结束步骤`);
    if(!(point.assertions[id]||[]).some(a=>a.kind!=="STATUS_EQUALS")) issues.push(`${point.title}：补充拒绝请求的无副作用业务检查`);
  }
  if(flow.steps) for(const point of flow.testPoints) {
    const end=point.endAtStepRef?flow.steps.findIndex(s=>s.id===point.endAtStepRef):flow.steps.length-1;
    const groups=flow.steps.slice(0,end+1);
    if(!groups.some(g=>(g.assertions||[]).length || g.calls.some(c=>c.states.length || (point.assertions[c.id]||[]).some(a=>a.kind!=="STATUS_EQUALS"))))
      issues.push(`${point.title}：增加业务字段、状态、数据验证或跨调用比较，不能只检查 HTTP 状态码`);
  }
  if (!flow.steps && hasCycle(flow)) issues.push("步骤之间存在循环依赖，请检查连线和数据来源");
  return [...new Set(issues)];
}
export function scenarioIR(s: Scenario) {
  return JSON.parse(s.irJson) as {
    operations: Operation[];
    executionSteps?: {id:string;mode:string;callIds:string[]}[];
    cleanup?: {call:FlowNode}[];
    steps: (FlowNode & {
      assertions: { kind: string; path: string; expected: unknown }[];
    })[];
    initialContext: Flow["initialContext"];
    changeEvidenceRefs?: string[];
  };
}
export function executionProfile(s: Scenario): Profile {
  const ir = scenarioIR(s);
  const allCalls=[...ir.steps,...(ir.cleanup||[]).map(c=>c.call)];
  const secrets = allCalls.flatMap((n) =>
    [...Object.values(n.inputs), ...preparationValues(n.preparation)]
      .filter((b) => b.source === "secret")
      .map((b) => String(b.value)),
  );
  return {
    services: Object.fromEntries(
      ir.executionSteps ? allCalls.map(c=>[`call:${c.id}`,{baseUrl:""}]) : ir.operations.map((o) => [`${o.system}.${o.service}`, { baseUrl: "" }]),
    ),
    initialContext: Object.fromEntries(
      Object.entries(ir.initialContext).map(([k, v]) => [
        k,
        defaultValue(v.type),
      ]),
    ),
    secretEnvironmentNames: Object.fromEntries(secrets.map((k) => [k, ""])),
  };
}
export function profileIssues(profile: Profile): string[] {
  const errors = Object.entries(profile.services).flatMap(([k, v]) => {
    try {
      const u = new URL(v.baseUrl);
      if (
        ["http:", "https:"].includes(u.protocol) &&
        !u.username &&
        !u.password &&
        !u.search &&
        !u.hash
      )
        return [];
    } catch {
      /* show useful field error */
    }
    return [`填写 ${k} 的完整 HTTP / HTTPS 测试地址`];
  });
  for (const [k, v] of Object.entries(profile.secretEnvironmentNames))
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(v))
      errors.push(`填写 ${k} 对应的环境变量名`);
  return errors;
}
export function download(name: string, content: string) {
  const url = URL.createObjectURL(
    new Blob([content], { type: "application/octet-stream" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Reject malformed advanced drafts before they can replace a working visual editor. */
export function parseFlowDraft(text: string): Flow {
  const f = JSON.parse(text) as Flow;
  const object = (v: unknown): v is Record<string, unknown> =>
    !!v && typeof v === "object" && !Array.isArray(v);
  const strings = (v: unknown): v is string[] =>
    Array.isArray(v) && v.every((s) => typeof s === "string");
  const bindings = (v: unknown) =>
    object(v) &&
    Object.values(v).every(
      (b) =>
        object(b) &&
        ["literal", "context", "secret", "environment"].includes(String(b.source)) &&
        "value" in b,
    );
  const valid =
    object(f) &&
    typeof f.flowId === "string" &&
    typeof f.name === "string" &&
    Number.isInteger(f.revision) &&
    f.revision >= 0 &&
    object(f.initialContext) &&
    Object.values(f.initialContext).every(
      (v) =>
        object(v) &&
        typeof v.type === "string" &&
        typeof v.sensitive === "boolean",
    ) &&
    Array.isArray(f.nodes) &&
    f.nodes.every(
      (n) =>
        object(n) &&
        typeof n.id === "string" &&
        typeof n.label === "string" &&
        typeof n.operationRef === "string" &&
        bindings(n.inputs) &&
        Array.isArray(n.outputs) &&
        n.outputs.every(
          (o) =>
            object(o) &&
            typeof o.key === "string" &&
            typeof o.path === "string" &&
            typeof o.type === "string" &&
            typeof o.sensitive === "boolean",
        ) &&
        Array.isArray(n.states) &&
        n.states.every(
          (s) =>
            object(s) &&
            [
              "modelId",
              "ruleId",
              "entityContext",
              "observedContext",
              "afterPath",
              "observedEntityContext",
              "afterEntityPath",
            ].every((k) => typeof s[k] === "string"),
        ) &&
        typeof n.waitMillis === "number",
    ) &&
    Array.isArray(f.dependencies) &&
    f.dependencies.every(
      (d) =>
        object(d) &&
        ["from", "to", "kind", "reason"].every((k) => typeof d[k] === "string"),
    ) &&
    Array.isArray(f.testPoints) &&
    f.testPoints.every(
      (p) =>
        object(p) &&
        typeof p.id === "string" &&
        typeof p.title === "string" &&
        strings(p.covers) &&
        object(p.assertions) &&
        Object.values(p.assertions).every(
          (a) =>
            Array.isArray(a) &&
            a.every(
              (v) =>
                object(v) &&
                typeof v.id === "string" &&
                typeof v.kind === "string",
            ),
        ) &&
        object(p.inputOverrides) &&
        Object.values(p.inputOverrides).every(bindings),
    ) &&
    Array.isArray(f.stateModels) &&
    f.stateModels.every(
      (m) =>
        object(m) &&
        typeof m.modelId === "string" &&
        strings(m.states) &&
        Array.isArray(m.transitions) &&
        m.transitions.every(
          (t) =>
            object(t) &&
            (["ruleId", "from", "to"] as const).every(
              (k) => typeof t[k] === "string",
            ),
        ),
    );
  if (!valid)
    throw new Error(
      "流程结构不完整：请保留步骤、输入输出、测试点和检查项的结构。",
    );
  if (new Set(f.nodes.map((n) => n.id)).size !== f.nodes.length)
    throw new Error("步骤 ID 重复，请使用唯一标识。");
  if(f.steps!=null) {
    if(!Array.isArray(f.steps) || f.dependencies.length || f.steps.length>100 || f.nodes.length>300 ||
      !f.steps.every(s=>object(s) && typeof s.id==="string" && typeof s.label==="string" && ["SERIAL","PARALLEL"].includes(s.mode) && Array.isArray(s.calls) && s.calls.length>0 &&
        s.calls.every(c=>object(c) && typeof c.id==="string") && (s.assertions==null || Array.isArray(s.assertions) && s.assertions.every(a=>object(a) && ["id","leftKey","rightKey"].every(k=>typeof a[k as keyof typeof a]==="string")))))
      throw new Error("有序步骤需要唯一 ID、串行或并行模式及非空调用列表，不能同时保留依赖连线。");
    const stable=(value:unknown):unknown=>Array.isArray(value)?value.map(stable):object(value)?Object.fromEntries(Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>[k,stable(v)])):value;
    if(new Set(f.steps.map(s=>s.id)).size!==f.steps.length || JSON.stringify(stable(f.steps.flatMap(s=>s.calls)))!==JSON.stringify(stable(f.nodes)))
      throw new Error("步骤顺序或调用内容与 nodes 不一致，请保留完整的有序定义。");
  }
  for(const p of f.testPoints) if(p.endAtStepRef!=null && typeof p.endAtStepRef!=="string" || p.suppressedOutputCalls!=null && !strings(p.suppressedOutputCalls))
    throw new Error("用例执行范围或负向输出配置不合法。");
  if(f.cleanup!=null && (!Array.isArray(f.cleanup) || f.cleanup.length>30 || !f.cleanup.every(c=>object(c) && object(c.call) && typeof c.call.id==="string" &&
    typeof c.call.operationRef==="string" && bindings(c.call.inputs) && Array.isArray(c.call.outputs) && c.call.outputs.length===0 && Array.isArray(c.call.states) &&
    c.call.waitMillis===0 && Array.isArray(c.assertions) && typeof c.resourceKey==="string" && strings(c.when) && c.when.length>0 && c.when.every(w=>["PASS","FAIL","INCONCLUSIVE"].includes(w)) &&
    typeof c.required==="boolean" && typeof c.timeoutMillis==="number" && c.timeoutMillis>=1 && c.timeoutMillis<=60000)))
    throw new Error("固定收尾清单不完整：需要资源来源、适用结果和 1–60000 毫秒预算，不能发布输出或添加等待。");
  return f;
}
