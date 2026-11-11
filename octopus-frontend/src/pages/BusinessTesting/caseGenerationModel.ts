import type { ApplyResult, CaseGenerationRequest, Flow, GenerationCandidate, GenerationStrategy, GenerationTaskView, ProbeEnvironmentView } from "../../api/businessTesting";

/** 三角色用例生成的纯函数（无 React），便于单测。 */

export const TERMINAL_STATES = new Set(["READY", "NEEDS_INPUT", "FAILED", "CANCELLED"]);
export const isTerminal = (state: string) => TERMINAL_STATES.has(state);

export const GROUPS: { key: GenerationCandidate["group"]; title: string; hint: string; color: string }[] = [
  { key: "CONTRADICTED", title: "疑似缺陷或依据错误", hint: "实际行为与预期依据不符：请判断是缺陷（保留原预期）还是依据错误（不应用）。", color: "red" },
  { key: "SIDE_EFFECT_UNKNOWN", title: "副作用未知", hint: "探测中写调用结果未知或清理未完成，请先人工核查残留；此类候选不能应用。", color: "volcano" },
  { key: "EXPECTATION_MISSING", title: "缺少预期依据", hint: "预期没有契约、已确认规则或人工依据；加入草稿后请补充业务断言。", color: "orange" },
  { key: "BLOCKED", title: "待补充", hint: "静态校验未通过或前置失败，按原因补充后再确认。", color: "gold" },
  { key: "READY", title: "可审阅", hint: "探测与预期一致或证据不足但静态校验通过；仍需人工确认。", color: "blue" },
  { key: "NOT_PROBED", title: "未探测", hint: "静态校验通过，未在探测环境中执行；原因见各候选。", color: "default" },
];

export const CONCLUSION_LABELS: Record<string, string> = {
  CONSISTENT: "探测一致", CONTRADICTED: "实际与预期不符", PRECONDITION_FAILED: "前置失败", INCONCLUSIVE: "证据不足",
  SIDE_EFFECT_UNKNOWN: "副作用未知", NOT_PROBED: "未探测", STATIC_INVALID: "静态校验未通过",
};

export const END_REASONS: Record<string, string> = {
  CONVERGED: "没有可由 Planner 处理的反馈", NO_PROGRESS: "修订没有带来变化，提前结束", BUDGET: "轮次、探测或时间预算已用尽",
  PLAN_REJECTED: "模型修订越界，已沿用上一轮计划", CANCELLED: "已取消",
};

export const PHASE_LABELS: Record<string, string> = { PLANNING: "Planner 规划", MATERIALIZING: "Operator 物化与静态校验", PROBING: "Operator 探测", OBSERVING: "Observer 观察" };

export function groupCandidates(candidates: GenerationCandidate[]) {
  return GROUPS.map(g => ({ ...g, items: candidates.filter(c => c.group === g.key) })).filter(g => g.items.length > 0);
}

export interface GenerationForm {
  strategies: GenerationStrategy[]; coverage: string[]; limit: number; goal: string; model: string;
  rejectionStatus: number | null; rejectionEvidence: string;
  probe: boolean; environment: string | null; initialContextJson: string;
}

export const defaultForm = (): GenerationForm => ({
  strategies: ["RULE"], coverage: [], limit: 20, goal: "", model: "", rejectionStatus: null, rejectionEvidence: "",
  probe: false, environment: null, initialContextJson: "",
});

/** 校验表单并构造请求；探测默认关闭，只能选择已开启授权的环境（LOOP-008、ENVG-001）。 */
export function buildRequest(form: GenerationForm, flow: Flow, pointId: string, environments: ProbeEnvironmentView[], requestId: string):
    { request: CaseGenerationRequest | null; errors: string[] } {
  const errors: string[] = [];
  if (!form.strategies.length) errors.push("请至少选择一种生成策略");
  if (!(form.limit >= 1 && form.limit <= 100)) errors.push("候选上限为 1–100");
  if (form.goal.length > 4000) errors.push("补充说明最多 4000 字");
  if (form.rejectionStatus != null && (form.rejectionStatus < 400 || form.rejectionStatus > 599 || !form.rejectionEvidence.trim()))
    errors.push("负向预期需要 4xx/5xx 拒绝状态及业务依据");
  let initialContext: Record<string, unknown> | null = null;
  if (form.probe) {
    const env = environments.find(e => e.environment === form.environment);
    if (!env || !env.enabled) errors.push("请选择已开启生成探测的环境");
    if (form.initialContextJson.trim()) {
      try {
        const parsed = JSON.parse(form.initialContextJson);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) errors.push("探测初始上下文必须是 JSON 对象");
        else initialContext = parsed as Record<string, unknown>;
      } catch { errors.push("探测初始上下文不是合法 JSON"); }
    }
  }
  if (errors.length) return { request: null, errors };
  return {
    errors,
    request: {
      requestId, flowId: flow.flowId, flowRevision: flow.revision, testPointId: pointId, strategies: [...form.strategies], coverage: [...form.coverage],
      limit: form.limit, goal: form.goal.trim() || null, model: form.model.trim() || null,
      rejection: form.rejectionStatus == null ? null : { status: form.rejectionStatus, evidence: form.rejectionEvidence.trim() },
      probe: form.probe ? { mode: "ON", environment: form.environment, initialContext, secretEnvironmentNames: null, services: null }
        : { mode: "OFF", environment: null, initialContext: null, secretEnvironmentNames: null, services: null },
    },
  };
}

/** 被选中的矛盾候选必须先判断；副作用未知的候选不能选择。 */
export function selectionIssues(task: GenerationTaskView, selected: string[], decisions: Record<string, "DEFECT" | "SOURCE_WRONG">) {
  const issues: string[] = [];
  for (const id of selected) {
    const c = task.candidates.find(x => x.intentId === id);
    if (!c) continue;
    if (c.group === "SIDE_EFFECT_UNKNOWN") issues.push(`${c.title}：副作用未知，请先核查残留`);
    if (c.group === "CONTRADICTED" && !decisions[id]) issues.push(`${c.title}：请判断是缺陷还是依据错误`);
  }
  return issues;
}

/** 把服务端返回的测试点加入编辑器中的流程草稿：按指纹去重，最多 20 个测试点。 */
export function applyToFlow(flow: Flow, result: ApplyResult, maxPoints = 20): { flow: Flow; added: number; messages: string[] } {
  const messages = result.skipped.map(s => s.reason);
  const existing = new Set(flow.testPoints.map(p => p.generatedFrom?.fingerprint).filter(Boolean) as string[]);
  const ids = new Set(flow.testPoints.map(p => p.id));
  const fresh = result.points.filter(p => !existing.has(p.generatedFrom?.fingerprint ?? "") && !ids.has(p.id));
  if (fresh.length < result.points.length) messages.push(`${result.points.length - fresh.length} 个候选已在流程中，已跳过`);
  if (flow.testPoints.length + fresh.length > maxPoints) return { flow, added: 0, messages: [...messages, `每个流程最多 ${maxPoints} 个测试点，请减少选择`] };
  return { flow: { ...flow, testPoints: [...flow.testPoints, ...fresh] }, added: fresh.length, messages };
}

export function newRequestId() {
  const bytes = new Uint8Array(12);
  globalThis.crypto.getRandomValues(bytes);
  return "casegen_" + Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}

export function budgetText(task: GenerationTaskView) {
  const b = task.budget;
  return `第 ${Math.max(task.round, 0)}/${b.maxRounds} 轮 · 探测 ${b.probesUsed}/${b.maxProbes} 次（每个意图最多 ${b.maxProbesPerIntent} 次）`;
}
