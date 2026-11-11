import type { ChainFailure, ChainState, FlowChain } from "../../api/projectContext.ts";
import { snapshot } from "./flowFillModel.ts";
import type { Flow } from "../../api/businessTesting.ts";

export const chainStateNames: Record<ChainState,string> = { QUEUED:"等待开始", RUNNING:"自动推进中", PAUSE_REQUESTED:"当前节点完成后暂停", PAUSED:"已暂停 · 可继续", FAILED:"节点失败 · 等待处理", READY:"全链路建议已生成 · 待审阅", ACCEPTING:"正在应用", ACCEPTED:"已确认 · 可恢复草稿", CANCELLED:"已取消" };
export const failureStages: Record<string,string> = { REQUEST:"创建任务", QUEUED:"排队", PREPARING:"读取契约与上下文", GENERATING:"模型补全", CHECKPOINT:"保存检查点", CONFIRMING:"确认步骤记忆", RECOVERY:"恢复检查点", HEARTBEAT:"执行器续约", REPOSITORY_SOURCE:"读取仓库源码", REPOSITORY_ANALYSIS:"归纳仓库背景", REPOSITORY_PLANNING:"规划仓库分析", REPOSITORY_MODULE:"分项取证与记忆", REPOSITORY_SYNTHESIS:"总结全部模块记忆" };
export function chainCanvasMismatch(flow: Flow | null, task: FlowChain | null): boolean {
  if(!flow || !task)return false;
  return snapshot(flow)!==snapshot(task.original) && !(task.state==="ACCEPTED" && snapshot(flow)===snapshot(task.candidate));
}
export const retryLabel = (failure: ChainFailure | null) => failure && !failure.retryable ? "修复后重试并继续" : "重试失败节点并继续";
