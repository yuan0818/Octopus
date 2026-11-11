import type { Flow, FlowNode, FlowStep } from "../../api/businessTesting";

/** Reconcile edits to call fields while retaining the explicitly chosen group order/mode. */
export function synchronizeOrdered(flow: Flow): Flow {
  if (!flow.steps) return flow;
  const byId = new Map(flow.nodes.map(n => [n.id,n]));
  const assigned = new Set<string>();
  const steps = flow.steps.map(s => ({ ...s, assertions:s.assertions||[], calls: s.calls.flatMap(c => {
    const call = byId.get(c.id); if (!call) return []; assigned.add(c.id); return [call];
  }) })).filter(s => s.calls.length);
  for (const call of flow.nodes) if (!assigned.has(call.id)) steps.push({ id: call.id, label: call.label, mode: "SERIAL", calls: [call], assertions:[] });
  return { ...flow, dependencies: [], steps, nodes: steps.flatMap(s => s.calls) };
}
export function replaceSteps(flow: Flow, steps: FlowStep[]): Flow {
  return { ...flow, steps:steps.map(s=>({...s,assertions:s.assertions||[]})), dependencies: [], nodes: steps.flatMap(s => s.calls) };
}
export function availableCalls(flow: Flow, id: string): Set<string> {
  const result = new Set<string>();
  if (!flow.steps) return new Set(flow.nodes.filter(c => c.id !== id).map(c => c.id));
  for (const step of flow.steps) {
    for (const call of step.calls) {
      if (call.id === id) return result;
      if (step.mode === "SERIAL") result.add(call.id);
    }
    if (step.calls.some(c => c.id === id)) return result;
    step.calls.forEach(c => result.add(c.id));
  }
  return result;
}
export function orderedReferenceIssues(flow: Flow, references: (call: FlowNode) => string[]): string[] {
  if (!flow.steps) return [];
  const producers = new Map(flow.nodes.flatMap(n => n.outputs.map(o => [o.key,n.id] as const)));
  return flow.nodes.flatMap(n => {
    const allowed = availableCalls(flow,n.id);
    return references(n).filter(key => producers.has(key) && !allowed.has(producers.get(key)!))
      .map(key => `${n.label}：${key} 引用了当前调用、后续调用或并行组内输出`);
  });
}
