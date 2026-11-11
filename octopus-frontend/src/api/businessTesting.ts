import axios from "axios";
const http = axios.create({
  baseURL: "/business-testing/api/business-testing",
  timeout: 130000,
});
export interface Port {
  location: string;
  name: string;
  type: string;
  required: boolean;
  prefix: string | null;
}
export interface Operation {
  operationId: string;
  revision: string;
  system: string;
  service: string;
  method: string;
  path: string;
  inputs: Record<string, Port>;
  requestSchema: Record<string, unknown> | null;
  responseSchema: Record<string, unknown> | null;
  implementations: {
    projectId: number;
    branch: string;
    commitId: string;
    signature: string;
  }[];
  source: { kind: string; location: string; digest: string };
  requiresCapabilities: string[];
  securityProfile: string | null;
}
export interface Binding {
  source: string;
  value: unknown;
}
export interface Output {
  key: string;
  path: string;
  type: string;
  sensitive: boolean;
}
export interface Assertion {
  id: string;
  kind: string;
  path: string | null;
  expected: unknown;
}
export interface StateConstraint {
  modelId: string;
  ruleId: string;
  entityContext: string;
  observedContext: string;
  afterPath: string;
  observedEntityContext: string;
  afterEntityPath: string;
}
export interface PreparationTool {
  id: string; version: string; title: string; inputs: Record<string,string>; outputs: Record<string,string>;
  sensitiveInputs: string[]; description: string;
}
export interface PreparationPlan {
  tools: { id: string; tool: string; version: string; inputs: Record<string,Binding> }[];
  body: Binding | null; headers: Record<string,Binding>;
}
export interface FlowNode {
  id: string;
  label: string;
  operationRef: string;
  inputs: Record<string, Binding>;
  outputs: Output[];
  states: StateConstraint[];
  waitMillis: number;
  preparation?: PreparationPlan | null;
  /** 轮询：重复请求直到本步骤全部检查项通过或超时（Phase 2.1） */
  poll?: { intervalMillis: number; timeoutMillis: number } | null;
}
export interface Dependency {
  from: string;
  to: string;
  kind: string;
  reason: string;
}
export interface TestPoint {
  id: string;
  title: string;
  covers: string[];
  assertions: Record<string, Assertion[]>;
  inputOverrides: Record<string, Record<string, Binding>>;
  intentionallyViolatedRule: string | null;
  endAtStepRef?: string | null;
  suppressedOutputCalls?: string[] | null;
  generatedFrom?: CaseGeneration | null;
}
/** 候选来源与追溯；taskId 起由三角色生成任务填写（TRACE-001）。 */
export interface CaseGeneration {
  flowHash:string;sourceRule:string;fingerprint:string;sourcePointId:string;
  taskId?:string|null;intentId?:string|null;round?:number|null;
  expectationSources?:Record<string,ExpectationSource>|null;observationIds?:string[]|null;flags?:string[]|null;
}
export interface ExpectationSource {type:string;ref:string|null;observationId?:string|null}
export interface FlowStep { id: string; label: string; mode: "SERIAL" | "PARALLEL"; calls: FlowNode[]; assertions?: {id:string;leftKey:string;rightKey:string}[] }
export interface CaseCandidate {fingerprint:string;sourceRule:string;callId:string|null;input:string|null;status:string;issues:string[];point:TestPoint}
export interface CleanupCall { call: FlowNode; assertions: Assertion[]; resourceKey:string; when:string[]; required:boolean; timeoutMillis:number }
export interface Flow {
  flowId: string;
  revision: number;
  name: string;
  initialContext: Record<string, { type: string; sensitive: boolean }>;
  nodes: FlowNode[];
  dependencies: Dependency[];
  stateModels: {
    modelId: string;
    revision: string;
    entityType: string;
    states: string[];
    transitions: { ruleId: string; from: string; to: string }[];
    origin: string;
  }[];
  testPoints: TestPoint[];
  steps?: FlowStep[] | null;
  cleanup?: CleanupCall[] | null;
}
export interface FlowRevision {
  definition: Flow;
  hash: string;
  status: string;
  confirmedBy: string | null;
  confirmedAt: string | null;
}
export interface Scenario {
  id: string;
  flowId: string;
  flowRevision: number;
  testPointId: string;
  irHash: string;
  irJson: string;
}
export interface Run {
  id: string;
  scenarioId: string;
  state: string;
  verdict: string;
  reportJson: string | null;
  error: string | null;
}
export interface Profile {
  environment?: string;
  services: Record<string, { baseUrl: string }>;
  initialContext: Record<string, unknown>;
  secretEnvironmentNames: Record<string, string>;
}
export interface Compilation {
  jmx: string;
  irHash: string;
  sourceMap: Record<string, string>;
  capabilities: unknown[];
}
export interface OrderedPlanCandidate {
  flow: Flow; model: string; searchedOperationRefs: string[]; catalogHash: string;
  plan: { name: string; steps: { id: string; label: string; mode: string; operationRefs: string[]; reason: string }[]; assumptions: string[]; questions: string[] };
}
export interface Impact {
  codeImpact: { projectIds: number[]; branch: string; coverage: string; warnings: string[]; evidence: unknown[] };
  testPoints: {
    flowId: string;
    flowRevision: number;
    testPointId: string;
    affectedNodes: string[];
    prerequisiteNodes: string[];
    evidenceRefs: string[];
    reason: string;
  }[];
  unmappedEndpoints: string[];
}
// ------------------------------------------------------------------ 三角色用例生成（Planner / Operator / Observer）
export type GenerationStrategy = "RULE" | "MODEL";
export type ProbeMode = "OFF" | "ON";
export interface CaseGenerationRequest {
  requestId:string;flowId:string;flowRevision:number;testPointId:string;strategies:GenerationStrategy[];coverage:string[];limit:number;
  goal:string|null;model:string|null;rejection:{status:number|null;evidence:string|null}|null;
  probe:{mode:ProbeMode;environment:string|null;initialContext:Record<string,unknown>|null;secretEnvironmentNames:Record<string,string>|null;services:null};
}
export interface GenerationCandidate {
  intentId:string;title:string;category:string;source:GenerationStrategy;sourceRule:string;targetCallIds:string[];
  status:"BLOCKED"|"READY_FOR_REVIEW";group:"CONTRADICTED"|"SIDE_EFFECT_UNKNOWN"|"EXPECTATION_MISSING"|"BLOCKED"|"READY"|"NOT_PROBED";
  flags:string[];reasons:string[];conclusion:string|null;round:number;fingerprint:string;probeRunId:string|null;skippedReason:string|null;rationale:string|null;point:TestPoint;
}
export interface GenerationEvent {revision:number;at:string;round:number;kind:string;message:string}
export interface GenerationTaskView {
  id:string;state:string;phase:string|null;round:number;flowId:string;flowRevision:number;flowHash:string;testPointId:string;strategies:GenerationStrategy[];
  probeMode:ProbeMode;probeEnvironment:string|null;createdAt:string;updatedAt:string;endReason:string|null;errorCode:string|null;error:string|null;cancelRequested:boolean;
  budget:{roundsUsed:number;maxRounds:number;probesUsed:number;maxProbes:number;maxProbesPerIntent:number;taskTimeoutSeconds:number};
  warnings:string[];questions:string[];candidates:GenerationCandidate[];
  summary:{conclusions:Record<string,number>;suspectedDefects:number;sideEffectUnknown:number;blocked:number;ready:number;openQuestions:number};
  events:GenerationEvent[];roleCalls:{role:string;round:number;intentId:string|null;model:string|null;promptVersion:string|null;outcome:string;at:string}[];
}
export interface GenerationTaskSummary {id:string;flowId:string;flowRevision:number;state:string;phase:string|null;round:number;probeMode:ProbeMode;createdAt:string;updatedAt:string;endReason:string|null}
export interface GenerationFeedback {feedbackId:string;type:string;route:"PLANNER"|"HUMAN";intentId:string;stepId:string|null;callId:string|null;assertionId:string|null;httpStatus:string|null;platformCode:string|null;message:string}
export interface GenerationObservation {
  intentId:string;round:number;source:"STATIC"|"PROBE";probeRunId:string|null;conclusion:string;
  assertions:{callId:string;assertionId:string;classification:string;sourceType:string}[];
  calls:{callId:string;status:string;httpStatus:string|null;target:boolean;write:boolean;outcomeUnknown:boolean;platformCode:string|null}[];
  feedback:GenerationFeedback[];evidence:{callId:string;httpStatus:string|null;platformCode:string|null;status:string;assertions:{assertionId:string;kind:string;path:string|null;expected:unknown;source:string;classification:string;actual?:string}[]}[];
  cleanupStatus:string|null;
}
export interface IntentDetail {
  intent:{intentId:string;title:string;category:string;source:GenerationStrategy;rationale:string|null;preconditionNotes:string|null;issues:string[];
    expectations:{callId:string;assertion:Assertion;sourceType:string;sourceRef:string|null;proposed:unknown;note:string|null}[]};
  rounds:{round:number;carried:boolean;skippedReason:string|null;
    candidate:{point:TestPoint;staticResult:{ok:boolean;errors:string[]};probeEligible:boolean;probeIneligibleReason:string|null;issues:string[]}|null;
    observation:GenerationObservation|null;
    probe:{id:string;state:string;environment:string;environmentRevision:number;namespace:string;cleanupStatus:string|null;requestsMayHaveBeenSent:boolean;error:string|null;createdAt:string|null;finishedAt:string|null}|null}[];
}
export interface ApplyResult {flowId:string;flowRevision:number;flowHash:string;points:TestPoint[];skipped:{intentId:string;reason:string}[]}
export interface ProbeEnvironmentView {environment:string;enabled:boolean;dataPolicy:"CLEANUP_REQUIRED"|"DISPOSABLE";resetCycleHours:number|null;nonProductionDeclared:boolean;reason:string;revision:number;updatedBy:string|null;updatedAt:string|null}
export interface ProbeEnvironmentRequest {enabled:boolean;dataPolicy:"CLEANUP_REQUIRED"|"DISPOSABLE";resetCycleHours:number|null;nonProductionDeclared:boolean;reason:string}

export const caseGeneration = {
  create: (body: CaseGenerationRequest) => http.post<GenerationTaskView>("/case-generation/tasks", body).then(r=>r.data),
  get: (id: string) => http.get<GenerationTaskView>(`/case-generation/tasks/${encodeURIComponent(id)}`).then(r=>r.data),
  recent: (flowId?: string) => http.get<GenerationTaskSummary[]>("/case-generation/tasks", {params: flowId ? {flowId} : {}}).then(r=>r.data),
  cancel: (id: string) => http.post<GenerationTaskView>(`/case-generation/tasks/${encodeURIComponent(id)}/cancel`).then(r=>r.data),
  resume: (id: string) => http.post<GenerationTaskView>(`/case-generation/tasks/${encodeURIComponent(id)}/resume`).then(r=>r.data),
  intent: (id: string, intentId: string) => http.get<IntentDetail>(`/case-generation/tasks/${encodeURIComponent(id)}/intents/${encodeURIComponent(intentId)}`).then(r=>r.data),
  apply: (id: string, body: {intentIds:string[];decisions:Record<string,"DEFECT"|"SOURCE_WRONG">;expectationSources:null}) =>
    http.post<ApplyResult>(`/case-generation/tasks/${encodeURIComponent(id)}/apply`, body).then(r=>r.data),
  probeEnvironments: () => http.get<ProbeEnvironmentView[]>("/case-generation/probe-environments").then(r=>r.data),
  probeAccess: () => http.get<{user:string;admin:boolean}>("/case-generation/probe-environments/access").then(r=>r.data),
  putProbeEnvironment: (environment: string, body: ProbeEnvironmentRequest) =>
    http.put<ProbeEnvironmentView>(`/case-generation/probe-environments/${encodeURIComponent(environment)}`, body).then(r=>r.data),
  revokeProbeEnvironment: (environment: string, reason: string) =>
    http.post<ProbeEnvironmentView>(`/case-generation/probe-environments/${encodeURIComponent(environment)}/revoke`, {reason}).then(r=>r.data),
  probeHistory: (environment: string) =>
    http.get<{revision:number;snapshot:Record<string,unknown>;changedBy:string|null;changedAt:string}[]>(`/case-generation/probe-environments/${encodeURIComponent(environment)}/history`).then(r=>r.data),
};

export const businessTesting = {
  caseCandidates: (body: {flowId:string;flowRevision:number;testPointId:string;limit:number;rejectionStatus?:number;rejectionEvidence?:string}) => http.post<{flowHash:string;candidates:CaseCandidate[];total:number;truncated:boolean;warnings:string[]}>("/case-candidates",body).then(r=>r.data),
  /** Phase 2.3：LLM 负向用例草稿（不保存；结构由平台按负向规则构造） */
  negativeSuggestions: (body: {flowId:string;flowRevision:number;testPointId:string;limit:number;goal?:string}) => http.post<{flowHash:string;candidates:CaseCandidate[];rationales:string[];questions:string[];model:string|null}>("/case-candidates/negative-suggestions",body,{timeout:270000}).then(r=>r.data),
  exportDocumentation: (operationRefs:string[],title:string) => http.post<{digest:string;openApi:unknown;warnings:string[]}>("/documentation/export",{operationRefs,title}).then(r=>r.data),
  compareDocumentation: (before:string,after:string) => http.get<{path:string;before:unknown;after:unknown}[]>("/documentation/compare",{params:{before,after}}).then(r=>r.data),
  planOrdered: (body: { goal: string; operationRefs: string[]; model?: string }) => http.post<OrderedPlanCandidate>("/ordered-flows/plan",body,{timeout:1100000}).then(r=>r.data),
  migrateOrdered: (flow: Flow, testPointId: string) => http.post<{ flow: Flow; baseHash: string; idMap: Record<string,string>; executionOrder: string[]; warnings: string[] }>("/flows/migrate-ordered", { flow, testPointId }).then(r => r.data),
  preparationTools: () => http.get<PreparationTool[]>("/preparation-tools").then(r => r.data),
  operations: () => http.get<Operation[]>("/operations").then((r) => r.data),
  importOpenApi: (body: unknown) =>
    http
      .post<Operation[]>("/operations/import-openapi", body)
      .then((r) => r.data),
  saveOperation: (body: Operation) =>
    http.post<Operation>("/operations", body).then((r) => r.data),
  flows: () => http.get<FlowRevision[]>("/flows").then((r) => r.data),
  saveFlow: (body: Flow) =>
    http.post<FlowRevision>("/flows", body).then((r) => r.data),
  decide: (flow: FlowRevision, accept: boolean) =>
    http
      .post<FlowRevision>(
        `/flows/${flow.definition.flowId}/${flow.definition.revision}/decision`,
        { expectedHash: flow.hash, accept },
      )
      .then((r) => r.data),
  scenarios: () => http.get<Scenario[]>("/scenarios").then((r) => r.data),
  generate: (body: {
    flowId: string;
    flowRevision: number;
    testPointId: string;
    changeEvidenceRefs?: string[];
  }) => http.post<Scenario>("/scenarios", body).then((r) => r.data),
  compile: (id: string, profile: Profile) =>
    http
      .post<Compilation>(`/scenarios/${id}/compile`, profile)
      .then((r) => r.data),
  run: (id: string, profile: Profile, idempotencyKey?:string) =>
    http
      .post<Run>(`/scenarios/${id}/runs`, { profile, timeoutSeconds: 300 },{headers:idempotencyKey?{"Idempotency-Key":idempotencyKey}:undefined})
      .then((r) => r.data),
  runs: () => http.get<Run[]>("/runs").then((r) => r.data),
  cancel: (id: string) => http.post(`/runs/${id}/cancel`),
  impact: (body: unknown) =>
    http.post<Impact>("/impact", body).then((r) => r.data),
};
