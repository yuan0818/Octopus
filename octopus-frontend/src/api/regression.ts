import axios from "axios";

/** Phase 1：批量回归、失败分析、LLM 建议度量（octopus-scenario-runner）。 */
const http = axios.create({ baseURL: "/business-testing/api/business-testing", timeout: 230000 });

export type BatchVerdict = "PENDING" | "PASS" | "FAIL" | "INCONCLUSIVE" | "NO_AFFECTED_TESTS";
export interface BatchItem {
  flowId: string;
  flowRevision: number;
  testPointId: string;
  evidenceRefs: string[];
  scenarioId: string | null;
  runId: string | null;
  state: "PENDING" | "SUBMITTED" | "DONE" | "SKIPPED" | "ERROR";
  verdict: string | null;
  reason: string | null;
}
export interface BatchCounts {
  total: number; pass: number; fail: number; inconclusive: number; skipped: number; error: number; pending: number; running: number;
}
export interface RegressionBatch {
  id: string;
  status: "OPEN" | "COMPLETED" | "CANCELLED" | "FAILED";
  verdict: BatchVerdict;
  triggerSource: string;
  externalRef: string | null;
  environment: string;
  branch: string;
  baseCommitId: string;
  targetCommitId: string;
  counts: BatchCounts;
  items: BatchItem[];
  impact: Record<string, unknown> | null;
  callbackStatus: string;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
}
export interface StartBatch {
  projectIds: number[];
  branch: string;
  baseCommitId: string;
  targetCommitId: string;
  environment: string;
  timeoutSeconds?: number;
  initialContext?: Record<string, unknown>;
}

export interface TriageView {
  runId: string;
  triage: {
    category: string;
    confidence: "LOW" | "MEDIUM" | "HIGH";
    summary: string;
    failedChecks: { stepId: string; assertionId: string | null; explanation: string }[];
    evidence: string[];
    suggestedActions: string[];
    questions: string[];
  };
  deterministicSignals: string[];
  model: string | null;
  createdAt: string;
  advisory: boolean;
}

export interface MetricsRow {
  kind: string; model: string | null; generated: number; failed: number; accepted: number; rejected: number; edited: number;
  failureRate: number | null; acceptanceRate: number | null; p50LatencyMs: number | null; p95LatencyMs: number | null;
  failureCodes: Record<string, number>;
}

export const regressionApi = {
  start: (body: StartBatch) => http.post<RegressionBatch>("/regression-batches", { ...body, triggerSource: "MANUAL" }).then(r => r.data),
  list: () => http.get<RegressionBatch[]>("/regression-batches").then(r => r.data),
  get: (id: string) => http.get<RegressionBatch>(`/regression-batches/${encodeURIComponent(id)}`).then(r => r.data),
  cancel: (id: string) => http.post<RegressionBatch>(`/regression-batches/${encodeURIComponent(id)}/cancel`).then(r => r.data),
  triage: (runId: string) => http.get<TriageView>(`/runs/${encodeURIComponent(runId)}/triage`, { validateStatus: s => s === 200 || s === 404 })
    .then(r => (r.status === 404 ? null : r.data)),
  runTriage: (runId: string, model?: string) => http.post<TriageView>(`/runs/${encodeURIComponent(runId)}/triage`, model ? { model } : {}).then(r => r.data),
  metrics: (days = 30) => http.get<{ since: string; events: number; rows: MetricsRow[] }>("/suggestion-metrics", { params: { days } }).then(r => r.data),
};

export function errorMessage(e: unknown): string {
  const data = (e as { response?: { data?: { message?: string } } })?.response?.data;
  return data?.message || (e instanceof Error ? e.message : String(e));
}
