import { Collapse } from "antd";
import type { RepositoryAnalysis } from "../../api/projectContext";

export default function Evidence({ task, ids }: { task: RepositoryAnalysis | null; ids: string[] }) {
  const sources = ids.map(id => task?.sources.find(s => s.id === id)).filter(s => !!s);
  if (!sources.length) return null;
  return <Collapse ghost size="small" items={[{ key: "evidence", label: `源码依据 · ${sources.length}`, children: sources.map(s => <details key={s.id}><summary>{s.filePath} · {s.lineStart}–{s.lineEnd}</summary><pre>{s.text}</pre></details>) }]} />;
}
