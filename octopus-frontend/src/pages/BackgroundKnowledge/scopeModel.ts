import type { RepositoryScope, RepositoryVersion } from "../../types";
export function moduleOptions(repo?: RepositoryScope) {
  return [...new Set(repo?.modules.map(m => m.modulePath) || [])].map(value => ({ value, label: value === "." ? "根模块" : value }));
}
export function scopeBranches(repo?: RepositoryScope, module = "") {
  return [...new Set(repo?.modules.filter(m => !module || m.modulePath === module).flatMap(m => repo.key.startsWith("repo:") ? (m.branch ? [m.branch] : []) : m.branches) || [])];
}
export function scopeVersions(versions: RepositoryVersion[], repo?: RepositoryScope, module = "", branch = "") {
  if (!module) return versions;
  const ids = new Set(repo?.modules.filter(m => m.modulePath === module && (repo.key.startsWith("repo:") ? m.branch === branch : m.branches.includes(branch))).map(m => m.projectId));
  return versions.filter(v => v.projectIds.some(id => ids.has(id)));
}
