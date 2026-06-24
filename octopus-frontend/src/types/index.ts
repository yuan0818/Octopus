// ── API Response ──
export interface ApiResponse<T> {
  code: number;
  message: string;
  success: boolean;
  data: T;
}

// ── Repo ──
export interface RepoConfig {
  id: number;
  repoUrl: string;
  branch: string;
  authType: 'NONE' | 'HTTP' | 'SSH';
  httpUsername?: string;
  httpCredentialEncrypted?: string;
  sshPrivateKeyPath?: string;
  sshPassphraseEncrypted?: string;
  sshKnownHostsPath?: string;
  createdAt: string;
  updatedAt: string;
}

export interface RepoConfigForm {
  repoUrl: string;
  branch: string;
  authType: 'NONE' | 'HTTP' | 'SSH';
  httpUsername?: string;
  httpCredentialEncrypted?: string;
  sshPrivateKeyPath?: string;
  sshPassphraseEncrypted?: string;
  sshKnownHostsPath?: string;
}

export interface RepoSyncResult {
  repoLocalPath: string;
  repoUrl: string;
  commitId: string;
  tag: string | null;
}

export interface JavaProject {
  projectName: string;
  projectPath: string;
  buildTool: 'MAVEN' | 'GRADLE';
  javaVersion: string;
}

// ── Analysis Task ──
export interface AnalysisTask {
  id: number;
  repoConfigId: number | null;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  currentStep: string;
  commitId: string;
  tag: string;
  branch: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
  steps: TaskStep[];
}

export interface TaskStep {
  stepName: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'DEGRADED';
  errorMessage?: string;
  startedAt: string | null;
  completedAt: string | null;
}

// ── Query ──
export interface CallEdge {
  qualifiedClassName: string;
  methodName: string;
  signature: string;
  callType: string;
  lineNumber: number;
}

// Node inside chain array: { method, callType, lineNumber, children }
export interface CallChainItem {
  method: MethodNodeInfo;
  callType: string;
  lineNumber: number;
  children: CallChainItem[];
}

// Root node returned by API (flat format)
export interface MethodNodeInfo {
  qualifiedClassName: string;
  methodName: string;
  parameterTypes: string[];
  returnType: string | null;
  modifiers: string | null;
  signature: string | null;
}

export interface CallChainResult {
  root: MethodNodeInfo;
  chain: CallChainItem[];
}

export interface CodePathStep {
  method: MethodNodeInfo;
  callType: string;
  lineNumber: number;
  sourceFilePath: string | null;
  sourceCode: string | null;
}

export interface CodePath {
  direction: string;
  steps: CodePathStep[];
}

export interface ImpactPaths {
  anchor: MethodNodeInfo;
  upstreamPaths: CodePath[];
  downstreamPaths: CodePath[];
  affectedPaths: CodePath[];
  truncated: boolean;
}

export interface EndpointDiffPaths {
  endpointSignature: string;
  fromCommitId: string;
  toCommitId: string;
  addedPaths: CodePath[];
  removedPaths: CodePath[];
  unchangedPaths: CodePath[];
  truncated: boolean;
}

export interface SourcePath {
  steps: CodePathStep[];
}

export interface ClassSummary {
  id: number;
  qualifiedName: string;
  simpleName: string;
  classType: string | null;
  modifiers: string | null;
}

export interface ClassDetail {
  qualifiedName: string;
  simpleName: string;
  classType: string;
  packageName: string;
  modifiers: string;
  sourceFilePath: string;
  parentClass?: string;
  interfaces: string[];
  methods: MethodInfo[];
  fields: FieldInfo[];
  annotations: AnnotationInfo[];
  dependencies: DependencyInfo[];
}

export interface MethodInfo {
  methodName: string;
  methodSignature: string;
  parameterTypes: string[];
  returnType: string;
  modifiers: string;
  sourceCode?: string;
}

export interface FieldInfo {
  fieldName: string;
  fieldType: string;
  modifiers: string;
}

export interface AnnotationInfo {
  annotationName: string;
  annotationParams: string;
}

export interface DependencyInfo {
  qualifiedName: string;
  type: string;
}

export interface EndpointInfo {
  httpMethod: string;
  path: string;
  methodName: string;
  qualifiedClassName: string;
  controllerSimpleName: string;
  signature: string;
  projectId: number | null;
  projectName: string | null;
  branch: string | null;
  commitId: string | null;
}

export type EntryPointType = 'HTTP_API' | 'SCHEDULED' | 'MESSAGE_LISTENER' | 'ROOT_METHOD';

export interface EntryPointInfo {
  entryType: EntryPointType | string;
  displayName: string;
  triggerLabel: string | null;
  httpMethod: string | null;
  path: string | null;
  methodName: string;
  qualifiedClassName: string;
  ownerSimpleName: string | null;
  controllerSimpleName: string | null;
  signature: string;
  projectId: number | null;
  projectName: string | null;
  branch: string | null;
  commitId: string | null;
}

/** Method node enriched with edge metadata (callType + lineNumber). */
export interface MethodEdgeDTO {
  qualifiedClassName: string;
  methodName: string;
  parameterTypes: string[];
  returnType: string | null;
  modifiers: string | null;
  signature: string;
  callType: string;
  lineNumber: number;
}

/** Combined upstream callers + downstream callees for graph visualization. */
export interface CallTraceDTO {
  anchor: MethodNodeInfo;
  upstream: MethodEdgeDTO[];
  downstream: MethodEdgeDTO[];
}

// ── Review Agent ──
export interface RequirementItem {
  id: string;
  title: string;
  description: string;
}

export interface LLMConfig {
  provider: string;
  model: string;
  maxTokens: number;
  temperature: number;
}

export interface ReviewRequest {
  projectId: number;
  className: string;
  methodName: string;
  strategy?: string;
  requirements?: RequirementItem[];
  docPath?: string;
  reviewMode?: 'REQUIREMENT_AWARE' | 'RISK_BASED';
  mode?: 'SYNC' | 'ASYNC';
  skillId?: string;
  modelName?: string;
  llmConfig?: LLMConfig;
}

export interface ReviewSummary {
  totalRequirements: number;
  passed: number;
  failed: number;
  uncertain: number;
  highestSeverity: string;
}

export interface RequirementCheck {
  requirementId: string;
  requirementTitle: string;
  passed: boolean;
  confidence: string;
  detail: string;
  codeReferences: string[];
  highlightedCode: string | null;
  suggestion: string | null;
}

export interface CodeIssue {
  severity: string;
  category: string;
  description: string;
  codeReferences: string[];
  highlightedCode: string | null;
  suggestion: string | null;
}

export interface ReviewResponse {
  sessionId: string;
  anchor: string;
  reviewMode?: 'REQUIREMENT_AWARE' | 'RISK_BASED';
  summary: ReviewSummary;
  requirementChecks: RequirementCheck[];
  issues: CodeIssue[];
  involvedClasses: number;
  totalMethods: number;
  truncated: boolean;
}

export interface ProjectInfo {
  id: number;
  projectName: string;
  projectPath: string;
  branch: string | null;
  commitId: string | null;
  isBackendProject: boolean;
  isApiService: boolean | null;
  buildTool: string | null;
  javaVersion: string | null;
}
