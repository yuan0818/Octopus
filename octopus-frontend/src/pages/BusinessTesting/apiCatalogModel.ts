import type { EndpointInfo, RepositoryScope } from "../../types";
import type { Operation } from "../../api/businessTesting";

export interface CatalogItem {
  key: string;
  rootKey: string;
  rootName: string;
  moduleKey: string;
  moduleName: string;
  method: string;
  path: string;
  branch: string | null;
  commitId: string | null;
  endpoint?: EndpointInfo;
  operations: Operation[];
}
export interface CatalogRoot {
  key: string;
  name: string;
  moduleCount: number;
  items: CatalogItem[];
}
export const UNASSIGNED = "catalog:unassigned";
export interface CatalogSelection {
  key: string;
  rootKey: string;
  moduleKey?: string;
  branch: string;
  commitId: string;
}

export function catalogBranches(
  scopes: RepositoryScope[],
  rootKey: string,
  moduleKey?: string,
): string[] {
  return [...new Set(scopes
    .filter((scope) => !rootKey || scope.key === rootKey)
    .flatMap((scope) => scope.modules
      .filter((module) => !moduleKey || module.modulePath === moduleKey)
      .flatMap((module) => scope.key.startsWith("repo:")
        ? module.branch ? [module.branch] : []
        : module.branches)))].sort();
}

/** Remote modules have separate project IDs per branch; local IDs retain history. */
export function catalogModuleIds(
  scope: RepositoryScope,
  moduleKey: string,
  branch: string,
): number[] {
  return [...new Set(scope.modules
    .filter((module) => module.modulePath === moduleKey &&
      (scope.key.startsWith("repo:")
        ? module.branch === branch : module.branches.includes(branch)))
    .map((module) => module.projectId))];
}

export function selectCatalogScope(
  scopes: RepositoryScope[],
  current: CatalogSelection,
  target: { rootKey: string; moduleKey?: string; branch?: string; key?: string },
): CatalogSelection {
  const candidateBranch = target.branch ?? current.branch;
  const branch = catalogBranches(scopes, target.rootKey, target.moduleKey)
    .includes(candidateBranch) ? candidateBranch : "";
  const changed = current.rootKey !== target.rootKey ||
    current.moduleKey !== target.moduleKey || current.branch !== branch;
  return {
    rootKey: target.rootKey,
    moduleKey: target.moduleKey,
    key: target.key ?? (target.moduleKey
      ? JSON.stringify(["module", target.rootKey, target.moduleKey]) : target.rootKey),
    branch,
    commitId: changed ? "" : current.commitId,
  };
}

export const operationKey = (o: Operation) => `${o.operationId}@${o.revision}`;
const signature = (s: string) => s.replace(/\s+/g, "");
const endpointKey = (e: EndpointInfo) =>
  JSON.stringify([
    e.projectId,
    e.branch,
    e.commitId,
    e.httpMethod,
    e.path,
    signature(e.signature),
  ]);

export function buildCatalog(
  scopes: RepositoryScope[],
  endpoints: EndpointInfo[],
  operations: Operation[],
): CatalogRoot[] {
  const owners = new Map<
    number,
    { scope: RepositoryScope; module: RepositoryScope["modules"][number] }
  >();
  scopes.forEach((scope) =>
    scope.modules.forEach((module) =>
      owners.set(module.projectId, { scope, module }),
    ),
  );
  const items = new Map<string, CatalogItem>();
  const describe = (projectId: number | null, fallback: string) => {
    const owner = projectId === null ? undefined : owners.get(projectId);
    return owner
      ? {
          rootKey: owner.scope.key,
          rootName: owner.scope.name,
          moduleKey: owner.module.modulePath,
          moduleName:
            owner.module.modulePath === "."
              ? "根目录"
              : owner.module.modulePath,
        }
      : {
          rootKey: UNASSIGNED,
          rootName: "未关联项目的接口",
          moduleKey: fallback,
          moduleName: fallback,
        };
  };
  endpoints.forEach((endpoint) => {
    const key = `code:${endpointKey(endpoint)}`;
    items.set(key, {
      key,
      ...describe(endpoint.projectId, endpoint.projectName || "代码接口"),
      method: endpoint.httpMethod,
      path: endpoint.path,
      branch: endpoint.branch,
      commitId: endpoint.commitId,
      endpoint,
      operations: [],
    });
  });
  operations.forEach((operation) => {
    let assigned = false;
    for (const implementation of operation.implementations) {
      const matches = [...items.values()].filter(
        (item) =>
          item.endpoint &&
          item.endpoint.projectId === implementation.projectId &&
          item.branch === implementation.branch &&
          item.commitId === implementation.commitId &&
          signature(item.endpoint.signature) ===
            signature(implementation.signature) &&
          (item.method === operation.method || item.method === "*") &&
          item.path === operation.path,
      );
      if (matches.length) {
        assigned = true;
        matches.forEach((item) => {
          if (
            !item.operations.some(
              (o) => operationKey(o) === operationKey(operation),
            )
          )
            item.operations.push(operation);
        });
      } else if (owners.has(implementation.projectId)) {
        assigned = true;
        const key = `asset:${JSON.stringify([operationKey(operation), implementation])}`;
        items.set(key, {
          key,
          ...describe(implementation.projectId, operation.service),
          method: operation.method,
          path: operation.path,
          branch: implementation.branch,
          commitId: implementation.commitId,
          operations: [operation],
        });
      }
    }
    if (!assigned) {
      const key = `asset:${operationKey(operation)}`;
      items.set(key, {
        key,
        ...describe(null, `${operation.system} / ${operation.service}`),
        method: operation.method,
        path: operation.path,
        branch: null,
        commitId: null,
        operations: [operation],
      });
    }
  });
  const roots: CatalogRoot[] = scopes.map((s) => ({
    key: s.key,
    name: s.name,
    moduleCount: new Set(s.modules.map((m) => m.modulePath)).size,
    items: [],
  }));
  roots.push({
    key: UNASSIGNED,
    name: "未关联项目的接口",
    moduleCount: 0,
    items: [],
  });
  items.forEach((item) =>
    roots.find((root) => root.key === item.rootKey)!.items.push(item),
  );
  roots.forEach((root) =>
    root.items.sort(
      (a, b) =>
        a.moduleName.localeCompare(b.moduleName) ||
        a.path.localeCompare(b.path) ||
        a.method.localeCompare(b.method) ||
        (a.branch || "").localeCompare(b.branch || "") ||
        a.key.localeCompare(b.key),
    ),
  );
  return roots.filter((r) => r.key !== UNASSIGNED || r.items.length);
}

export function filterCatalog(
  roots: CatalogRoot[],
  filters: {
    search: string;
    branch: string;
    commitId: string;
    source: "all" | "code" | "imported";
  },
): CatalogRoot[] {
  const terms = filters.search
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  return roots.map((root) => ({
    ...root,
    items: root.items.filter((item) => {
      if (filters.branch && item.branch !== filters.branch) return false;
      if (filters.commitId && item.commitId !== filters.commitId) return false;
      if (filters.source === "code" && !item.endpoint) return false;
      if (filters.source === "imported" && !item.operations.some((o) => o.source?.kind !== "ANALYZED_CODE"))
        return false;
      const haystack =
        `${root.name} ${item.moduleName} ${item.method} ${item.path} ${item.endpoint?.signature || ""} ${item.branch || ""} ${item.operations.map((o) => `${o.system} ${o.service} ${operationKey(o)}`).join(" ")}`.toLowerCase();
      return terms.every((term) => haystack.includes(term));
    }),
  }));
}

export interface CatalogTreeNode {
  key: string;
  label: string;
  kind: "root" | "module" | "api";
  count?: number;
  rootKey: string;
  moduleKey?: string;
  item?: CatalogItem;
  children?: CatalogTreeNode[];
}
export function catalogTree(roots: CatalogRoot[]): CatalogTreeNode[] {
  const leaf = (item: CatalogItem): CatalogTreeNode => ({
    key: item.key,
    label: `${item.method} ${item.path}`,
    kind: "api",
    rootKey: item.rootKey,
    moduleKey: item.moduleKey,
    item,
  });
  return roots.map((root) => {
    const rootNode: CatalogTreeNode = {
      key: root.key,
      label: root.name,
      kind: "root",
      count: root.items.length,
      rootKey: root.key,
      children: [],
    };
    if (root.moduleCount <= 1 && root.key !== UNASSIGNED)
      rootNode.children = root.items.map(leaf);
    else {
      const groups = new Map<string, CatalogItem[]>();
      root.items.forEach((item) => {
        if (!groups.has(item.moduleKey)) groups.set(item.moduleKey, []);
        groups.get(item.moduleKey)!.push(item);
      });
      rootNode.children = [...groups.entries()].map(([moduleKey, entries]) => ({
        key: JSON.stringify(["module", root.key, moduleKey]),
        label: root.key !== UNASSIGNED && [...groups.keys()].filter(key => key.split("/").at(-1) === moduleKey.split("/").at(-1)).length === 1
          ? moduleKey.split("/").at(-1)! : entries[0].moduleName,
        kind: "module",
        count: entries.length,
        rootKey: root.key,
        moduleKey,
        children: entries.map(leaf),
      }));
    }
    return rootNode;
  });
}
