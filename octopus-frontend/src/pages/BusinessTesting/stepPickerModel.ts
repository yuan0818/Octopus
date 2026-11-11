import type { Flow, Operation } from "../../api/businessTesting";
import { appendOperation, refOf, uid } from "./workbenchModel.ts";

export interface PendingStep { id: string; operation: Operation }
export const pendingStep = (operation: Operation): PendingStep => ({ id: uid("pick"), operation });
export function movePending(steps: PendingStep[], fromId: string, toId: string): PendingStep[] {
  const from = steps.findIndex(s => s.id === fromId), to = steps.findIndex(s => s.id === toId);
  if (from < 0 || to < 0 || from === to) return steps;
  const result = [...steps]; const [step] = result.splice(from, 1); result.splice(to, 0, step); return result;
}
export function pendingCounts(steps: PendingStep[]): Record<string, number> {
  return steps.reduce<Record<string, number>>((counts, step) => {
    const key = refOf(step.operation); counts[key] = (counts[key] || 0) + 1; return counts;
  }, {});
}
/** Apply the reviewed batch in its chosen order; keep all existing node identities and business rules. */
export function appendPending(flow: Flow, steps: PendingStep[]): Flow {
  if(flow.steps) {
    if(flow.nodes.length+steps.length>300 || flow.steps.length+steps.length>100) throw new Error("一个流程最多添加 100 个步骤、300 次调用；可先合并步骤再添加接口");
  } else if(flow.nodes.length+steps.length>50) throw new Error("一个流程最多添加 50 个步骤");
  return steps.reduce((next, step) => appendOperation(next, step.operation), flow);
}
