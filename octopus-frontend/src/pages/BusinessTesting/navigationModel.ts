import type { ContextScope } from "../../api/projectContext";
import type { CatalogSelection } from "./apiCatalogModel";
import type { VersionSelection } from "./ImportPanel";

export const businessTestingPages = [
  { key: "overview", title: "概览", description: "把接口组织成业务流程，验证每次改动后，关键业务是否仍然正常。" },
  { key: "operations", title: "接口资产", description: "按项目、模块浏览当前已知 API，查看实现位置与测试资产。" },
  { key: "context", title: "环境变量", description: "按测试、预发布和生产环境管理服务地址、数据库、中间件及连接凭据。模块可覆盖项目默认配置。" },
  { key: "flows", title: "业务流程", description: "连接业务步骤、传递请求数据，并定义预期结果。" },
  { key: "tasks", title: "AI 补全任务", description: "跟踪整链补全进度，处理失败节点，审阅生成的参数、上下文传递与步骤记忆。" },
  { key: "impact", title: "变更回归", description: "选择仓库和两个已分析版本，查找受影响的接口与业务流程。" },
  { key: "scenarios", title: "测试场景", description: "运行已确认的流程版本和检查项，或下载 JMeter 测试文件。" },
  { key: "runs", title: "运行结果", description: "查看业务结论，定位具体步骤和检查项的执行结果。" },
] as const;
export type BusinessTestingArea = (typeof businessTestingPages)[number]["key"];
export function backgroundKnowledgePath(search = "") {
  const input = new URLSearchParams(search); const output = new URLSearchParams();
  ["repository", "module", "branch", "commit"].forEach(key => { const value = input.get(key); if (value) output.set(key, value); });
  return `/background/query${output.size ? `?${output}` : ""}`;
}
export function backgroundTasksPath(search = "", taskId = "") {
  const scope = new URLSearchParams(backgroundKnowledgePath(search).split("?")[1] || "");
  const input = new URLSearchParams(search);
  ["state", "search", "page"].forEach(key => { const value = input.get(key); if (value) scope.set(key, value); });
  if (taskId) scope.set("task", taskId);
  return `/background/tasks${scope.size ? `?${scope}` : ""}`;
}
export function backgroundAnalysisPath(search = "") {
  return backgroundKnowledgePath(search).replace("/background/query", "/background/analysis");
}
export const businessTestingBase = "/business-testing";

// Only navigation metadata belongs in URLs. Drafts, profiles and credentials stay out.
const queryKeys = ["repository", "module", "branch", "commit", "apiSearch", "apiSource", "apiPage", "apiNode", "apiExpanded", "flowSearch", "environment", "baseCommit", "targetCommit", "impactModules", "fillTask", "taskSearch", "taskStatus", "taskEnvironment", "taskPage"];
export function businessTestingSearch(search: string) {
  const input = new URLSearchParams(search);
  const result = new URLSearchParams();
  queryKeys.forEach(key => { const value = input.get(key); if (value) result.set(key, value); });
  return result.toString();
}
export function businessTestingPath(area: BusinessTestingArea, search = "") {
  const query = businessTestingSearch(search);
  return `${businessTestingBase}/${area}${query ? `?${query}` : ""}`;
}
export function setQueryValue(params: URLSearchParams, key: string, value?: string) {
  if (value) params.set(key, value); else params.delete(key);
}
export function readScope(params: URLSearchParams): ContextScope {
  const module = params.get("module");
  return { repositoryKey: params.get("repository") || "", modulePaths: module ? [module] : undefined,
    branch: params.get("branch") || "", commitId: params.get("commit") || "" };
}
export function writeScope(params: URLSearchParams, scope: ContextScope) {
  const previous = readScope(params);
  if (previous.repositoryKey !== scope.repositoryKey || previous.branch !== scope.branch || previous.modulePaths?.[0] !== scope.modulePaths?.[0]) {
    ["baseCommit", "targetCommit", "impactModules", "apiNode", "apiExpanded", "apiPage"].forEach(key => params.delete(key));
  }
  setQueryValue(params, "repository", scope.repositoryKey);
  setQueryValue(params, "module", scope.modulePaths?.[0]);
  setQueryValue(params, "branch", scope.branch);
  setQueryValue(params, "commit", scope.commitId);
}
function stringList(value: string | null): string[] | undefined {
  if (!value) return undefined;
  try { const parsed: unknown = JSON.parse(value); return Array.isArray(parsed) && parsed.every(v => typeof v === "string") ? parsed : undefined; }
  catch { return undefined; }
}
export interface CatalogViewState {
  selection: CatalogSelection;
  search: string;
  source: "all" | "code" | "imported";
  page: number;
  expanded?: (string | number)[];
}
export function readCatalogView(params: URLSearchParams): CatalogViewState {
  const scope = readScope(params);
  const moduleKey = scope.modulePaths?.[0];
  const source = params.get("apiSource");
  const page = Number(params.get("apiPage") || 1);
  return {
    selection: { key: params.get("apiNode") || (moduleKey ? JSON.stringify(["module", scope.repositoryKey, moduleKey]) : scope.repositoryKey),
      rootKey: scope.repositoryKey, moduleKey, branch: scope.branch, commitId: scope.commitId },
    search: params.get("apiSearch") || "", source: source === "code" || source === "imported" ? source : "all",
    page: Number.isSafeInteger(page) && page > 0 ? page : 1, expanded: stringList(params.get("apiExpanded")),
  };
}
export function writeCatalogView(params: URLSearchParams, view: CatalogViewState) {
  const { selection } = view;
  writeScope(params, { repositoryKey: selection.rootKey, modulePaths: selection.moduleKey ? [selection.moduleKey] : undefined,
    branch: selection.branch, commitId: selection.commitId });
  setQueryValue(params, "apiSearch", view.search);
  setQueryValue(params, "apiSource", view.source === "all" ? "" : view.source);
  setQueryValue(params, "apiPage", view.page === 1 ? "" : String(view.page));
  setQueryValue(params, "apiNode", selection.key);
  setQueryValue(params, "apiExpanded", view.expanded ? JSON.stringify(view.expanded.map(String)) : "");
}
export function readImpactVersion(params: URLSearchParams): VersionSelection {
  const ids = params.get("impactModules");
  const numbers = ids === null ? undefined : ids === "none" ? [] : ids.split(",").map(Number);
  return { projectId: null, repositoryKey: params.get("repository") || "", branch: params.get("branch") || "",
    baseCommitId: params.get("baseCommit") || "", targetCommitId: params.get("targetCommit") || "",
    scopeProjectIds: numbers?.every(n => Number.isSafeInteger(n) && n > 0) ? numbers : undefined };
}
export function writeImpactVersion(params: URLSearchParams, value: VersionSelection) {
  const scope = readScope(params);
  const changed = scope.repositoryKey !== value.repositoryKey || scope.branch !== value.branch;
  writeScope(params, { ...scope, repositoryKey: value.repositoryKey, branch: value.branch,
    ...(changed ? { modulePaths: undefined, commitId: "" } : {}) });
  setQueryValue(params, "baseCommit", value.baseCommitId);
  setQueryValue(params, "targetCommit", value.targetCommitId);
  setQueryValue(params, "impactModules", value.scopeProjectIds === undefined ? "" : value.scopeProjectIds.length ? value.scopeProjectIds.join(",") : "none");
}
