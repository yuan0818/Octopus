import type { ChainNodeState, ChainState, ChainTaskSummary } from "../../api/projectContext.ts";

export const taskGroups = [
  { key: "all", label: "全部" }, { key: "active", label: "进行中" }, { key: "attention", label: "待处理" },
  { key: "review", label: "待审阅" }, { key: "accepted", label: "已确认" }, { key: "cancelled", label: "已取消" },
] as const;
export type TaskGroup = typeof taskGroups[number]["key"];
export function taskGroup(state: ChainState): Exclude<TaskGroup, "all"> {
  if (["QUEUED", "RUNNING", "PAUSE_REQUESTED", "ACCEPTING"].includes(state)) return "active";
  if (["PAUSED", "FAILED"].includes(state)) return "attention";
  if (state === "READY") return "review";
  return state === "ACCEPTED" ? "accepted" : "cancelled";
}
export const taskNodeNames: Record<ChainNodeState, string> = { WAITING: "等待前序节点", PREPARING: "读取契约与上下文", GENERATING: "模型补全中", COMPLETED: "建议已生成", FAILED: "生成失败", INTERRUPTED: "中断待恢复" };
export const taskStateLabels: Record<ChainState, string> = { QUEUED: "排队中", RUNNING: "补全中", PAUSE_REQUESTED: "正在暂停", PAUSED: "已暂停", FAILED: "失败待处理", READY: "待审阅", ACCEPTING: "正在应用", ACCEPTED: "已确认", CANCELLED: "已取消" };
export const taskStateColors: Record<ChainState, string> = { QUEUED: "blue", RUNNING: "processing", PAUSE_REQUESTED: "orange", PAUSED: "orange", FAILED: "error", READY: "purple", ACCEPTING: "processing", ACCEPTED: "success", CANCELLED: "default" };
export function readTaskFilters(params: URLSearchParams) {
  const group = params.get("taskStatus"); const page = Number(params.get("taskPage") || 1);
  return { group: taskGroups.find(g => g.key === group)?.key || "all", search: params.get("taskSearch") || "", environment: params.get("taskEnvironment") || "",
    page: Number.isSafeInteger(page) && page > 0 ? page : 1 };
}
export function filterTasks(tasks: ChainTaskSummary[], filters: ReturnType<typeof readTaskFilters>) {
  const search = filters.search.trim().toLocaleLowerCase();
  return tasks.filter(task => (filters.group === "all" || taskGroup(task.state) === filters.group)
    && (!filters.environment || task.environment === filters.environment)
    && (!search || [task.name, task.id, task.flowId, task.currentNodeLabel].some(value => value?.toLocaleLowerCase().includes(search))));
}
export function taskProgress(task: Pick<ChainTaskSummary, "completed" | "total">) {
  return task.total > 0 ? Math.min(100, Math.max(0, Math.round(task.completed / task.total * 100))) : 0;
}
