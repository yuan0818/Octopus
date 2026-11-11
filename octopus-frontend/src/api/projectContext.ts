import axios from "axios";
import type { Flow, Operation } from "./businessTesting";
const http = axios.create({ baseURL: "/business-testing/api/business-testing", timeout: 30000 });
export interface ContextScope { repositoryKey: string; modulePaths?: string[]; branch: string; commitId: string }
export const emptyContextScope = (): ContextScope => ({ repositoryKey: "", branch: "", commitId: "" });
export interface ConnectionInfo { security?: string | null; id: string; name: string; kind: string; host: string; port: number | null; database: string; notes: string }
export interface EnvironmentVariable { key: string; type: "string" | "integer" | "number" | "boolean" | "object" | "array"; value: unknown }
export interface ContextDefinition { description: string; businessRules: string; baseUrl: string; connections: ConnectionInfo[]; variables?: EnvironmentVariable[] }
export interface ContextView {
  repositoryKey: string; modulePath: string; environment: string; version: number | null;
  definition: ContextDefinition; credentials: Record<string, { hasUsername: boolean; hasPassword: boolean }>; updatedAt: string | null;
}
export interface ResolvedContext {
  own: ContextView; effective: ContextDefinition; connectionOrigins: Record<string, string>; inheritedBaseUrl: boolean; encryptionReady: boolean;
  variableOrigins?: Record<string,string>;
}
export interface CredentialUpdate { username?: string; password?: string; clear?: boolean }
export interface FlowSuggestion { flow: Flow; operations: Operation[]; model: string; assumptions: string[]; questions: string[]; warnings: string[]; baseHash: string }
export interface PreparedContracts { flow: Flow; operations: Operation[]; warnings: string[] }
export interface SourceEvidence { id: string; projectId: number; commitId: string; filePath: string; lineStart: number; lineEnd: number; kind: string; text: string; truncated: boolean }
export interface RepositoryAnalysisRequest { repositoryKey: string; branch: string; commitId: string; model?: string; restart?: boolean; goal?: string }
export interface RepositoryReport { summary: string; modules: { modulePath: string; role: string; evidenceIds: string[] }[];
  rules: { title: string; kind: string; description: string; modulePaths: string[]; clientPreparation: string[]; evidenceIds: string[]; uncertainties: string[] }[]; questions: string[] }
export interface RepositoryAnalysis { taskId?: string; id: string; state: "QUEUED" | "COLLECTING" | "PLANNING" | "ANALYZING" | "SYNTHESIZING" | "SUMMARIZING" | "READY" | "FAILED" | "CANCELLED"; request: RepositoryAnalysisRequest;
  model: string | null; createdAt: string; updatedAt: string; report: RepositoryReport | null; sources: SourceEvidence[]; warnings: string[]; failure: ChainFailure | null;
  progress?: { summary: string; currentTaskId: string | null; summarizedTaskIds: string[]; synthesisAttempts?: number; tasks: {
    task: { id: string; title: string; objective: string; configurationObjective?: string; modulePaths: string[]; dependsOn: string[] };
    state: string; rounds: number; attempts: number; model: string | null; error: string | null; sourceCount: number; memory: RepositoryReport | null; activity?: string; pendingFiles?: string[]; sourceFiles?: string[]; toolFeedback?: string; toolHistory?: { action: string; rejected?: boolean; issues?: string[]; files?: string[]; prefix?: string; query?: string; offset?: number; total?: number; at?: string }[]
  }[] } | null }
export interface StepMemoryView { id: string; nodeId: string; state: "PENDING" | "CONFIRMED" | "STALE" | "WORKING"; model: string; createdAt: string;
  memory: { summary: string; decisions: string[]; questions: string[]; evidence: Omit<SourceEvidence, "text" | "truncated">[]; inputBindings: string[]; outputs: Flow["nodes"][number]["outputs"]; operationRef: string } }
export interface PreparedStep extends PreparedContracts { nodeId: string; sources: SourceEvidence[]; background: Record<string, unknown>[]; memories: StepMemoryView[]; upstreamNodeIds: string[] }
export interface StepSuggestion extends FlowSuggestion { nodeId: string; memory: StepMemoryView }
export type ChainState = "QUEUED" | "RUNNING" | "PAUSE_REQUESTED" | "PAUSED" | "FAILED" | "READY" | "ACCEPTING" | "ACCEPTED" | "CANCELLED";
export type ChainNodeState = "WAITING" | "PREPARING" | "GENERATING" | "COMPLETED" | "FAILED" | "INTERRUPTED";
export interface ChainFailure { code: string; stage: string; nodeId: string | null; message: string; suggestion: string; retryable: boolean; upstreamStatus: number | null; diagnosticId: string; occurredAt: string; bindingIssue?: { code: string; input: string; expectedType: string; actualType: string | null; contextKey: string | null; availableContextKeys: string[]; suggestion: string } | null }
export interface ChainEvent { revision: number; at: string; nodeId: string | null; state: string; message: string; failure: ChainFailure | null }
export interface ChainTaskSummary { id: string; flowId: string; name: string; state: ChainState; environment: string; updatedAt: string; completed: number; total: number; failure: ChainFailure | null;
  createdAt: string; model: string; currentNodeId: string | null; currentNodeLabel: string | null; currentNodeState: ChainNodeState | null }
export interface ChainNode { nodeId: string; label: string; state: ChainNodeState; attempts: number; startedAt: string | null; finishedAt: string | null; error: string | null;
  context: { sourceCount: number; sources: Omit<SourceEvidence, "text" | "truncated">[]; background: Record<string, unknown>[]; upstreamNodeIds: string[] } | null;
  memory: StepMemoryView | null; assumptions: string[]; questions: string[]; warnings: string[]; failure: ChainFailure | null }
export interface FlowChain { id: string; revision: number; state: ChainState; original: Flow; candidate: Flow; originalHash: string; resultHash: string; environment: string;
  goal: string; model: string; createdAt: string; updatedAt: string; error: string | null; nodes: ChainNode[]; operations: Operation[]; failure: ChainFailure | null; events: ChainEvent[] }
export interface RepositoryTaskSummary { taskId: string; current: boolean; repositoryKey: string; repositoryName: string | null; branch: string; commitId: string; modulePaths: string[]; goal: string | null; model: string | null; state: RepositoryAnalysis["state"]; createdAt: string; updatedAt: string; completed: number; total: number; currentTask: string | null; failure: ChainFailure | null }
export interface RepositoryTaskDetail { taskId: string; current: boolean; analysis: RepositoryAnalysis; adopted: boolean; adoptedAt: string | null }
export const projectContextApi = {
  globalContext: (environment: string) => http.get<ResolvedContext>("/contexts/global",{params:{environment}}).then(r=>r.data),
  saveGlobalContext: (body: { environment: string; version: number | null; definition: ContextDefinition; credentials: Record<string,CredentialUpdate> }) => http.put<ResolvedContext>("/contexts/global",body).then(r=>r.data),
  repositoryTasks: (params: Record<string, string | number | undefined>) => http.get<{ items: RepositoryTaskSummary[]; total: number; page: number; pageSize: number }>("/repository-analysis/tasks", { params }).then(r => r.data),
  repositoryTask: (id: string) => http.get<RepositoryTaskDetail>(`/repository-analysis/tasks/${encodeURIComponent(id)}`).then(r => r.data),
  adoptedRepositoryBackground: (params: RepositoryAnalysisRequest) => http.get<{ analysis: RepositoryAnalysis; adoptedAt: string } | null>("/repository-analysis/adopted", { params }).then(r => r.data || null),
  adoptRepositoryTask: (id: string) => http.post(`/repository-analysis/tasks/${encodeURIComponent(id)}/adopt`).then(r => r.data),
  repositoryTaskAction: (id: string, action: "resume" | "cancel") => http.post<RepositoryAnalysis>(`/repository-analysis/tasks/${encodeURIComponent(id)}/${action}`).then(r => r.data),
  askRepositoryBackground: (body: { scope: RepositoryAnalysisRequest; modulePath: string; question: string; backgroundTaskId?: string; history: { role: string; content: string }[] }) =>
    http.post<{ answer: string; evidenceIds: string[]; model: string; backgroundUpdatedAt: string | null }>("/repository-analysis/chat", body, { timeout: 270000 }).then(r => r.data),
  repositoryAnalysis: (params: RepositoryAnalysisRequest) => http.get<RepositoryAnalysis | null>("/repository-analysis", { params }).then(r => r.data || null),
  startRepositoryAnalysis: (body: RepositoryAnalysisRequest) => http.post<RepositoryAnalysis>("/repository-analysis", body).then(r => r.data),
  cancelRepositoryAnalysis: (body: RepositoryAnalysisRequest) => http.post<RepositoryAnalysis>("/repository-analysis/cancel", body).then(r => r.data),
  recoverableChains: () => http.get<ChainTaskSummary[]>("/flow-suggestions/chains/recoverable").then(r => r.data),
  chainTasks: () => http.get<ChainTaskSummary[]>("/flow-suggestions/chains/tasks").then(r => r.data),
  startChain: (body: { requestId: string; flow: Flow; environment: string; goal: string; model: string; contextValues: Record<string, unknown> }) => http.post<FlowChain>("/flow-suggestions/chains", body).then(r => r.data),
  latestChain: (flowId: string) => http.get<FlowChain | null>("/flow-suggestions/chains", { params: { flowId } }).then(r => r.data || null),
  chain: (id: string) => http.get<FlowChain>(`/flow-suggestions/chains/${encodeURIComponent(id)}`).then(r => r.data),
  chainAction: (id: string, action: "pause" | "resume" | "cancel" | "retry", nodeId?: string) => http.post<FlowChain>(`/flow-suggestions/chains/${encodeURIComponent(id)}/${action}`, { nodeId }).then(r => r.data),
  confirmChain: (task: FlowChain) => http.post<FlowChain>(`/flow-suggestions/chains/${encodeURIComponent(task.id)}/confirm`, { originalHash: task.originalHash, resultHash: task.resultHash }).then(r => r.data),
  prepareStep: (body: { flow: Flow; nodeId: string; environment: string }) => http.post<PreparedStep>("/flow-suggestions/steps/prepare", body, { timeout: 120000 }).then(r => r.data),
  suggestStep: (body: { flow: Flow; nodeId: string; environment: string; goal: string; model: string; contextValues: Record<string, unknown>; editableInputs: string[] }) =>
    http.post<StepSuggestion>("/flow-suggestions/steps", body, { timeout: 270000 }).then(r => r.data),
  confirmStep: (body: { memoryId: string; flow: Flow; environment: string }) => http.post<StepMemoryView>("/flow-suggestions/steps/confirm", body).then(r => r.data),
  get: (repositoryKey: string, modulePath: string, environment: string) =>
    http.get<ResolvedContext>("/contexts", { params: { repositoryKey, modulePath, environment } }).then(r => r.data),
  environments: (repositoryKey: string) => http.get<string[]>("/contexts/environments", { params: { repositoryKey } }).then(r => r.data),
  save: (body: Omit<ContextView, "credentials" | "updatedAt"> & { credentials: Record<string, CredentialUpdate> }) =>
    http.put<ResolvedContext>("/contexts", body).then(r => r.data),
  models: () => http.get<{ name: string; provider: string; modelId: string }[]>("/flow-suggestions/models").then(r => r.data),
  prepare: (flow: Flow) => http.post<PreparedContracts>("/flow-suggestions/prepare", flow, { timeout: 120000 }).then(r => r.data),
  suggest: (body: { flow: Flow; goal: string; environment: string; model: string; contextValues: Record<string, unknown>; editableInputs: Record<string, string[]> }) =>
    http.post<FlowSuggestion>("/flow-suggestions", body, { timeout: 270000 }).then(r => r.data),
  executionDefaults: (scenarioId: string, environment: string) =>
    http.post<{ services: Record<string, { baseUrl: string }>; initialContext: Record<string, unknown>; warnings: string[] }>("/contexts/execution-defaults", { scenarioId, environment }).then(r => r.data),
};
