import type { RepositoryScope, RepositoryVersion } from '../types';
import http from './client';
import reconstructHttp from './reconstructClient';
import type {
  BranchInfo, CallChainResult, CallTraceDTO, ClassDetail, ClassSummary, CommitInfo,
  EndpointDiffPaths, EndpointInfo, EntryPointInfo, ImpactPaths, MethodEdgeDTO,
  ProjectInfo, SourcePath,
} from '../types';

export const queryApi = {
  callers: (className: string, methodName: string, branch?: string | null, commitId?: string | null) =>
    http.get<MethodEdgeDTO[]>('/query/callers', {
      params: { className, methodName, branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),

  callees: (className: string, methodName: string, branch?: string | null, commitId?: string | null) =>
    http.get<MethodEdgeDTO[]>('/query/callees', {
      params: { className, methodName, branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),

  callChain: (className: string, methodName: string, maxDepth?: number, branch?: string | null, commitId?: string | null) =>
    http.get<CallChainResult>('/query/call-chain', {
      params: { className, methodName, maxDepth, branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),

  /** Combined upstream callers + downstream callees with configurable depth. */
  traceMethod: (className: string, methodName: string, depth: number = 5, branch?: string | null, commitId?: string | null) =>
    http.get<CallTraceDTO>('/query/trace-method', {
      params: { className, methodName, depth, branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),

  impactPaths: (signature: string, projectId?: number, branch?: string | null, commitId?: string | null) =>
    http.get<ImpactPaths>('/query/impact-paths', {
      params: { signature, projectId, branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),

  endpointDiffPaths: (signature: string, fromCommitId: string, toCommitId: string, projectId?: number, branch?: string | null) =>
    http.get<EndpointDiffPaths>('/query/endpoint-diff-paths', {
      params: { signature, fromCommitId, toCommitId, projectId, branch: branch ?? undefined },
    }),

  sourcePath: (signatures: string[], projectId?: number, branch?: string | null, commitId?: string | null) =>
    http.post<SourcePath>('/query/paths/source', { signatures, projectId, branch: branch ?? undefined, commitId: commitId ?? undefined }),

  classDetail: (qualifiedName: string, branch?: string | null, commitId?: string | null) =>
    http.get<ClassDetail>(`/query/classes/${encodeURIComponent(qualifiedName)}`, {
      params: { branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),

  implementors: (interfaceName: string) =>
    http.get<ClassSummary[]>('/query/implementors', { params: { interfaceName } }),

  endpoints: (projectId?: number, branch?: string | null, commitId?: string | null) =>
    http.get<EndpointInfo[]>('/query/endpoints', {
      params: {
        ...(projectId != null ? { projectId } : {}),
        ...(branch ? { branch } : {}),
        ...(commitId ? { commitId } : {}),
      },
    }),

  entryPoints: (projectId?: number, type?: string, branch?: string | null, commitId?: string | null) =>
    http.get<EntryPointInfo[]>('/query/entry-points', {
      params: {
        ...(projectId != null ? { projectId } : {}),
        ...(type ? { type } : {}),
        ...(branch ? { branch } : {}),
        ...(commitId ? { commitId } : {}),
      },
    }),

  repositoryScopes: () => http.get<RepositoryScope[]>('/query/repository-scopes'),
  repositoryVersions: (key: string, branch: string, projectIds?: number[]) =>
    http.get<RepositoryVersion[]>('/query/repository-scopes/versions', {
      params: { key, branch, ...(projectIds ? { projectIds: projectIds.join(',') } : {}) },
    }),

  projects: () =>
    http.get<ProjectInfo[]>('/query/projects'),

  /** 列出某项目关联 Git 仓库的所有真实分支（JGit）。 */
  branches: (projectId: number) =>
    http.get<BranchInfo[]>(`/query/projects/${projectId}/branches`),

  /** 列出某项目某分支最近的 commit 历史（JGit）。 */
  commits: (projectId: number, branch: string, limit = 50) =>
    http.get<CommitInfo[]>(`/query/projects/${projectId}/commits`, {
      params: { branch, limit },
    }),

  /** 从元数据枚举已分析过的分支（本地项目的版本选择事实来源）。 */
  analyzedBranches: (projectId: number) =>
    http.get<BranchInfo[]>(`/query/projects/${projectId}/analyzed-branches`),

  /** 从元数据枚举指定分支已分析过的 commit。 */
  analyzedCommits: (projectId: number, branch: string) =>
    http.get<CommitInfo[]>(`/query/projects/${projectId}/analyzed-commits`, {
      params: { branch },
    }),

  /** Reverse chain: find all API endpoints that reach a given method */
  reverseChain: (projectId: number, className: string, methodName: string, strategy = 'BFS', branch?: string | null, commitId?: string | null) =>
    reconstructHttp.get('/reverse-chain', {
      params: { projectId, className, methodName, strategy, branch: branch ?? undefined, commitId: commitId ?? undefined },
    }),
};
