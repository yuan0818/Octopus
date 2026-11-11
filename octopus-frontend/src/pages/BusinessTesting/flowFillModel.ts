import { synchronizeOrdered } from "./orderedFlowModel.ts";
import { preparationValues } from "./preparationModel.ts";
import { flowEdges } from "./workbenchModel.ts";
import type { Flow, Operation, Binding } from "../../api/businessTesting";

export const sensitiveContextKey = (key: string) => /^auth\.|password|passwd|secret|token|authorization|cookie|api.?key|credential/i.test(key);
export function snapshot(value: unknown): string {
  const sorted = (v: unknown): unknown => Array.isArray(v) ? v.map(sorted) : v && typeof v === "object"
    ? Object.fromEntries(Object.entries(v).filter(([k,item]) => !(k === "preparation" && item == null && "operationRef" in v)).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([k, item]) => [k, sorted(item)])) : v;
  return JSON.stringify(sorted(value));
}
export function defaultFillInputs(flow: Flow, operations: Operation[]): Record<string, string[]> {
  return Object.fromEntries(flow.nodes.map(n => [n.id, Object.keys(operations.find(o => `${o.operationId}@${o.revision}` === n.operationRef)?.inputs || {})
    .filter(key => !(key in n.inputs))]));
}
/** Contract discovery may change only the contract revision of the same pinned implementation. */
export function acceptPreparedContracts(original: Flow, prepared: Flow, before: Operation[], after: Operation[]): Flow {
  if (prepared.steps && prepared.dependencies.length) throw new Error("有序流程不能包含额外依赖边");
  const normalized = synchronizeOrdered({ ...prepared, nodes: prepared.nodes.map((node, i) => ({ ...node, operationRef: original.nodes[i]?.operationRef })) });
  if (snapshot(original) !== snapshot(normalized)) throw new Error("读取契约时改变了原有流程，未应用");
  for (let i = 0; i < original.nodes.length; i++) {
    const previous = before.find(o => `${o.operationId}@${o.revision}` === original.nodes[i].operationRef);
    const next = after.find(o => `${o.operationId}@${o.revision}` === prepared.nodes[i].operationRef);
    const identity = (o: Operation) => [o.operationId, o.system, o.service, o.method, o.path, o.implementations, o.requiresCapabilities, o.securityProfile];
    if (!previous || !next || snapshot(identity(previous)) !== snapshot(identity(next))) throw new Error("读取契约时切换了所选 API 或代码版本，未应用");
    if (Object.entries(previous.inputs).some(([key, port]) => snapshot(next.inputs[key]) !== snapshot(port))) throw new Error("契约与原输入端口冲突，未应用");
  }
  return structuredClone(prepared);
}
export function schemaFields(schema: unknown, prefix = "", depth = 0): { path: string; type: string }[] {
  if (!schema || typeof schema !== "object" || depth > 8) return [];
  const s = schema as { type?: string; properties?: Record<string, unknown>; items?: unknown };
  const own = prefix && s.type ? [{ path: prefix, type: s.type }] : [];
  return [...own, ...Object.entries(s.properties || {}).flatMap(([key, value]) => schemaFields(value, prefix ? `${prefix}.${key}` : key, depth + 1)),
    ...(s.items ? schemaFields(s.items, `${prefix}[]`, depth + 1) : [])];
}
const pathIdentity = (f: Flow) => ({ flowId: f.flowId, revision: f.revision, name: f.name, initialContext: f.initialContext,
  steps: f.steps?.map(s => ({ id:s.id, mode:s.mode, label:s.label, assertions:s.assertions||[], callIds:s.calls.map(c => c.id) })),
  nodes: f.nodes.map(({ inputs: _inputs, outputs: _outputs, preparation: _preparation, ...node }) => node), dependencies: f.dependencies, stateModels: f.stateModels,
  cleanup: f.cleanup,
  testPoints: f.testPoints.map(({ assertions: _assertions, ...point }) => point) });
export function acceptFill(current: Flow, original: Flow, prepared: Flow, filled: Flow): Flow {
  if (snapshot(current) !== snapshot(original)) throw new Error("流程在生成期间已修改，请按当前路径重新补全");
  if (snapshot(pathIdentity(prepared)) !== snapshot(pathIdentity(filled))) throw new Error("补全结果改变了既定路径，未应用");
  return structuredClone(filled);
}
export function fillChanges(before: Flow, after: Flow) {
  const display = (binding: Binding, key: string) => {
    if (binding.source === "context") return `引用上下文 ${String(binding.value)}`;
    if (binding.source === "secret") return `引用运行时密钥 ${String(binding.value)}`;
    if (binding.source === "result") return `前面动作 ${String(binding.value)}`;
    if (binding.source === "input") return `已填参数 ${String(binding.value)}`;
    if (binding.source === "request") return `原始请求 ${String(binding.value)}`;
    if (sensitiveContextKey(key)) return "敏感测试值";
    return `固定值 ${JSON.stringify(binding.value)}`;
  };
  return after.nodes.map(node => {
    const old = before.nodes.find(n => n.id === node.id)!;
    const changes: string[] = [];
    if (snapshot(old.preparation || null) !== snapshot(node.preparation || null)) {
      changes.push(`请求预处理：${node.preparation?.tools.map(t => t.tool).join(" → ") || "已移除"}`);
      node.preparation?.tools.forEach(t => changes.push(`${t.id} · ${t.tool}：${Object.entries(t.inputs).map(([key, value]) => `${key} ← ${display(value, key)}`).join("；") || "每次请求自动生成"}`));
      if (node.preparation?.body) changes.push(`实际请求体：${display(node.preparation.body, "body")}（替换原始 Body）`);
      if (node.preparation) changes.push(`网关请求头：${Object.entries(node.preparation.headers).map(([key,value]) => `${key} ← ${display(value,key)}`).join("；") || "无"}`);
    }
    if (old.operationRef !== node.operationRef) changes.push("读取并固定所选代码版本的入参、响应契约");
    Object.entries(node.inputs).forEach(([key, value]) => {
      if (snapshot(value) !== snapshot(old.inputs[key])) changes.push(`${key}：${old.inputs[key] ? `${display(old.inputs[key], key)} → ` : ""}${display(value, key)}`);
    });
    node.outputs.filter(o => !old.outputs.some(v => v.key === o.key)).forEach(o => changes.push(`提取 ${o.path} → ${o.key}（${o.type}${o.sensitive ? "，敏感" : ""}）`));
    after.testPoints.forEach(point => {
      const oldChecks = before.testPoints.find(p => p.id === point.id)?.assertions[node.id] || [];
      (point.assertions[node.id] || []).filter(a => !oldChecks.some(old => old.id === a.id)).forEach(a => changes.push(
        a.kind === "SCHEMA_CONFORMS" ? `${point.title} · 响应应符合接口契约 Schema` :
        `${point.title} · ${a.kind === "STATUS_EQUALS" ? "状态码" : a.path} ${a.kind === "JSON_EXISTS" ? "应存在" : `应等于 ${JSON.stringify(a.expected)}`}`));
    });
    return { id: node.id, label: node.label, changes };
  });
}

/** Only the selected node and its assertions may change in a step suggestion. */
export function acceptStepFill(current: Flow, original: Flow, prepared: Flow, filled: Flow, nodeId: string): Flow {
  const accepted = acceptFill(current, original, prepared, filled);
  if (!prepared.nodes.some(n => n.id === nodeId)) throw new Error("当前步骤不存在");
  const others = (f: Flow) => ({ nodes: f.nodes.filter(n => n.id !== nodeId), checks: f.testPoints.map(p =>
    Object.fromEntries(Object.entries(p.assertions).filter(([id]) => id !== nodeId))) });
  if (snapshot(others(prepared)) !== snapshot(others(filled))) throw new Error("补全修改了其他步骤，未应用");
  return accepted;
}
/** Stable topological traversal; presentation order is not necessarily execution order. */
export function orderedFillNodes(flow: Flow): Flow["nodes"] {
  if (flow.steps) return flow.steps.flatMap(s => s.calls);
  const producers = new Map(flow.nodes.flatMap(n => n.outputs.map(o => [o.key, n.id] as const)));
  const edges = [...flowEdges(flow).map(e => [e.source, e.target]), ...flow.nodes.flatMap(n => {
    const bindings = [...Object.values(n.inputs), ...preparationValues(n.preparation), ...flow.testPoints.flatMap(p => Object.values(p.inputOverrides[n.id] || {}))];
    return bindings.filter(b => b.source === "context" && producers.has(String(b.value))).map(b => [producers.get(String(b.value))!, n.id]);
  })];
  const remaining = new Set(flow.nodes.map(n => n.id)); const ordered: Flow["nodes"] = [];
  while (remaining.size) {
    const next = flow.nodes.find(n => remaining.has(n.id) && !edges.some(([from, to]) => to === n.id && remaining.has(from)));
    if (!next) throw new Error("流程存在循环依赖，请先调整路径");
    ordered.push(next); remaining.delete(next.id);
  }
  return ordered;
}

export function acceptChainFill(current: Flow, original: Flow, filled: Flow, before: Operation[], after: Operation[]): Flow {
  const prepared = synchronizeOrdered({ ...original, nodes: original.nodes.map((n, i) => ({ ...n, operationRef: filled.nodes[i]?.operationRef })) });
  acceptPreparedContracts(original, prepared, before, after);
  return acceptFill(current, original, prepared, filled);
}
