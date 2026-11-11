import type { StepData } from "./DagEditor";

/** DAG 编辑器与后端 dag JSON 的互转（从 DagEditor.tsx 拆出，组件文件只导出组件以保证热更新）。 */
export function stepsToDagJson(steps: StepData[]): string {
  return JSON.stringify({ steps: steps.map(s => ({
    id: s.id,
    ...(s.label ? { label: s.label } : {}),
    ...(s.calls.length === 1 && !s.label ? { api: s.calls[0].api } : {}),
    ...(s.calls.length > 1 || s.label ? { calls: s.calls } : {}),
    ...((s.dependsOn ?? []).length ? { dependsOn: s.dependsOn } : {}),
  }))});
}

export function dagJsonToSteps(json: string | null | undefined): StepData[] {
  if (!json) return [];
  try {
    const dag = JSON.parse(json);
    return (dag.steps || []).map((s: { id: string; label?: string; calls?: StepData["calls"]; api?: StepData["calls"][number]["api"]; dependsOn?: string[] }) => ({
      id: s.id, label: s.label,
      calls: s.calls || (s.api ? [{ api: s.api }] : []),
      dependsOn: s.dependsOn || [],
    }));
  } catch { return []; }
}
