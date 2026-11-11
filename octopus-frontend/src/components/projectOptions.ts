import type { ProjectInfo, RepositoryScope } from "../types";

type ModuleTreeNode = {
  key: string;
  title: string;
  children?: ModuleTreeNode[];
};

export function repositoryModuleTree(
  modules: RepositoryScope["modules"],
): ModuleTreeNode[] {
  const roots: ModuleTreeNode[] = [];
  for (const module of modules) {
    const segments =
      module.modulePath === "." ? ["根目录源码"] : module.modulePath.split("/");
    let siblings = roots;
    let path = "";
    segments.forEach((segment, index) => {
      path += `/${segment}`;
      let node = siblings.find((n) => n.key === path);
      if (!node) {
        node = { key: path, title: segment };
        siblings.push(node);
      }
      if (index < segments.length - 1) siblings = node.children ??= [];
    });
  }
  return roots;
}

/** Root identity comes from the backend; identical module names never define a repository. */
export function projectOptions(projects: ProjectInfo[]) {
  const groups = new Map<
    string,
    { label: string; options: { value: number; label: string }[] }
  >();
  for (const p of projects) {
    const key = p.repositoryKey || `project:${p.id}`;
    if (!groups.has(key))
      groups.set(key, {
        label: `${p.repositoryName || p.projectName} · 根项目`,
        options: [],
      });
    groups.get(key)!.options.push({
      value: p.id,
      label: `${p.modulePath === "." ? "根目录源码" : p.modulePath || p.projectName}${p.branch ? ` · ${p.branch}` : ""}`,
    });
  }
  return [...groups.values()];
}
