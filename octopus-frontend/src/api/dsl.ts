import axios from "axios";

/** DSL 工作台、JMeter 扩展、函数目录与轮询授权（octopus-scenario-runner）。 */
const http = axios.create({ baseURL: "/business-testing/api/business-testing", timeout: 230000 });

export interface Diagnostic {
  severity: "ERROR" | "WARNING" | "INFO";
  code: string;
  message: string;
  line: number;
  column: number;
  hint: string | null;
}

export interface LintResult {
  diagnostics: Diagnostic[];
  scenarios: { id: string; name: string; steps: number; roles: string[] }[];
}

export interface Scope {
  projectIds: number[];
  branch: string;
  commitId: string;
  system?: string;
  service?: string;
}

export interface Provenance { nodeId: string; item: string; source: "DSL" | "CONTRACT" | "RULE" | "AUTHORIZATION" | string; line: number; detail: string }
export interface Pending { nodeId: string; item: string; reason: string; line: number }

export interface FlowNodeView {
  id: string;
  label: string;
  operationRef: string;
  inputs: Record<string, { source: string; value: unknown }>;
  outputs: { key: string; path: string; type: string }[];
  waitMillis: number;
  poll?: { intervalMillis: number; timeoutMillis: number; authorizationRef?: string | null } | null;
  preparation?: { tools: { id: string; tool: string; version: string }[] } | null;
}

export interface ScenarioResult {
  scenarioId: string;
  name: string;
  flow: { flowId: string; name: string; nodes: FlowNodeView[]; testPoints: { id: string; assertions: Record<string, { id: string; kind: string; path: string | null; expected: unknown }[]> }[] } | null;
  roles: string[];
  provenance: Provenance[];
  pending: Pending[];
  diagnostics: Diagnostic[];
}

export interface Translation {
  sourceHash: string;
  scenarios: ScenarioResult[];
  diagnostics: Diagnostic[];
  saved: { scenarioId: string; flowId: string; revision: number; hash: string; unchanged: boolean }[];
}

export interface Issue { severity: "ERROR" | "WARNING"; code: string; message: string; className: string | null }

export interface ToolParam {
  index: number; name: string; type: string; required: boolean; defaultValue: string; description: string;
  enumValues: string[]; pattern: string; min: string; max: string; format: string; maxLength: number; sensitive: boolean;
}

export interface ExtensionTool {
  className: string; name: string; version: string; referenceKey: string; displayName: string; summary: string;
  description: string; whenToUse: string; whenNotToUse: string; category: string; tags: string[];
  determinism: "PURE" | "CLOCK" | "RANDOM"; stages: string[]; params: ToolParam[];
  output: { type: string; format: string; description: string } | null;
  examples: { args: string[]; output: string; match: string; description: string }[];
  errors: { code: string; description: string }[];
}

export interface ExtensionSummary {
  id: string; fileName: string; sha256: string; sizeBytes: number;
  status: "REJECTED" | "VALIDATED" | "PUBLISHED" | "DISABLED"; deprecated: string | null;
  tools: ExtensionTool[]; issues: Issue[]; unannotatedFunctions: string[];
  uploadedBy: string | null; uploadedAt: string; publishedBy: string | null; publishedAt: string | null;
}

export interface FunctionBrief { name: string; kind: "PLATFORM" | "JMETER" | "EXTENSION"; title: string; summary: string; determinism: string; stages: string[]; deprecated: boolean }

export interface PollAuthorization {
  id: string; projectId: number; httpMethod: string; path: string; signature: string; revision: number;
  intervalMillis: number; maxAttempts: number; timeoutMillis: number; reason: string; revoked: boolean;
  createdBy: string | null; createdAt: string; updatedBy: string | null; updatedAt: string;
}

export interface PollAuthorizationRequest {
  projectId: number; httpMethod: string; path: string; signature: string;
  intervalMillis: number; maxAttempts: number; timeoutMillis: number; reason: string;
}

export const dslApi = {
  lint: (source: string) => http.post<LintResult>("/dsl/lint", { source }).then(r => r.data),
  translate: (source: string, scope: Scope, save: boolean) => http.post<Translation>("/dsl/translate", { source, scope, save }).then(r => r.data),
  render: (flowId: string, revision: number) =>
    http.get<{ text: string; diagnostics: Diagnostic[]; sourceHash: string }>("/dsl/render", { params: { flowId, revision } }).then(r => r.data),
};

export const extensionApi = {
  list: () => http.get<ExtensionSummary[]>("/extensions").then(r => r.data),
  upload: (file: File, publish: boolean) => {
    const form = new FormData();
    form.append("file", file);
    form.append("publish", String(publish));
    return http.post<ExtensionSummary>("/extensions", form).then(r => r.data);
  },
  publish: (id: string) => http.post<ExtensionSummary>(`/extensions/${encodeURIComponent(id)}/publish`).then(r => r.data),
  disable: (id: string) => http.post<ExtensionSummary>(`/extensions/${encodeURIComponent(id)}/disable`).then(r => r.data),
  enable: (id: string) => http.post<ExtensionSummary>(`/extensions/${encodeURIComponent(id)}/enable`).then(r => r.data),
  deprecate: (id: string, note: string) => http.post<ExtensionSummary>(`/extensions/${encodeURIComponent(id)}/deprecate`, { note }).then(r => r.data),
  docs: (id: string) => http.get<string>(`/extensions/${encodeURIComponent(id)}/docs`, { responseType: "text" }).then(r => r.data),
};

export const functionApi = {
  search: (query?: string, stage?: string) => http.get<FunctionBrief[]>("/functions", { params: { query, stage } }).then(r => r.data),
  describe: (name: string) => http.get<Record<string, unknown>>("/functions/describe", { params: { name } }).then(r => r.data),
  docs: (name: string) => http.get<string>("/functions/docs", { params: { name }, responseType: "text" }).then(r => r.data),
  tryCall: (name: string, args: Record<string, unknown>) =>
    http.post<{ outputs?: Record<string, unknown>; error?: string }>("/functions/try", { name, arguments: args }).then(r => r.data),
};

export const pollAuthorizationApi = {
  list: () => http.get<PollAuthorization[]>("/poll-authorizations").then(r => r.data),
  authorize: (body: PollAuthorizationRequest) => http.post<PollAuthorization>("/poll-authorizations", body).then(r => r.data),
  revoke: (id: string) => http.post<PollAuthorization>(`/poll-authorizations/${encodeURIComponent(id)}/revoke`).then(r => r.data),
};

export function errorMessage(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const data = e.response?.data as { error?: string; message?: string } | undefined;
    return data?.error || data?.message || e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

/** Provenance label and colour (FR-CONV-02). */
export function sourceBadge(source: string): { color: string; text: string } {
  switch (source) {
    case "DSL": return { color: "blue", text: "DSL 声明" };
    case "CONTRACT": return { color: "geekblue", text: "契约" };
    case "RULE": return { color: "orange", text: "规则推导·待核对" };
    case "AUTHORIZATION": return { color: "purple", text: "轮询授权" };
    default: return { color: "default", text: source };
  }
}
