import http from './client';
import reconstructHttp from './reconstructClient';
import type {
  CallChainResult, CallTraceDTO, ClassDetail, ClassSummary, EndpointDiffPaths,
  EndpointInfo, EntryPointInfo, ImpactPaths, MethodEdgeDTO, ProjectInfo, SourcePath,
} from '../types';

export const queryApi = {
  callers: (className: string, methodName: string) =>
    http.get<MethodEdgeDTO[]>('/query/callers', { params: { className, methodName } }),

  callees: (className: string, methodName: string) =>
    http.get<MethodEdgeDTO[]>('/query/callees', { params: { className, methodName } }),

  callChain: (className: string, methodName: string, maxDepth?: number) =>
    http.get<CallChainResult>('/query/call-chain', {
      params: { className, methodName, maxDepth },
    }),

  /** Combined upstream callers + downstream callees with configurable depth. */
  traceMethod: (className: string, methodName: string, depth: number = 5) =>
    http.get<CallTraceDTO>('/query/trace-method', {
      params: { className, methodName, depth },
    }),

  impactPaths: (signature: string, projectId?: number, branch?: string, commitId?: string) =>
    http.get<ImpactPaths>('/query/impact-paths', {
      params: { signature, projectId, branch, commitId },
    }),

  endpointDiffPaths: (signature: string, fromCommitId: string, toCommitId: string, projectId?: number, branch?: string) =>
    http.get<EndpointDiffPaths>('/query/endpoint-diff-paths', {
      params: { signature, fromCommitId, toCommitId, projectId, branch },
    }),

  sourcePath: (signatures: string[], projectId?: number, branch?: string, commitId?: string) =>
    http.post<SourcePath>('/query/paths/source', { signatures, projectId, branch, commitId }),

  classDetail: (qualifiedName: string) =>
    http.get<ClassDetail>(`/query/classes/${encodeURIComponent(qualifiedName)}`),

  implementors: (interfaceName: string) =>
    http.get<ClassSummary[]>('/query/implementors', { params: { interfaceName } }),

  endpoints: (projectId?: number) =>
    http.get<EndpointInfo[]>('/query/endpoints', {
      params: projectId != null ? { projectId } : {},
    }),

  entryPoints: (projectId?: number, type?: string) =>
    http.get<EntryPointInfo[]>('/query/entry-points', {
      params: {
        ...(projectId != null ? { projectId } : {}),
        ...(type ? { type } : {}),
      },
    }),

  projects: () =>
    http.get<ProjectInfo[]>('/query/projects'),

  /** Reverse chain: find all API endpoints that reach a given method */
  reverseChain: (projectId: number, className: string, methodName: string, strategy = 'BFS') =>
    reconstructHttp.get('/reverse-chain', {
      params: { projectId, className, methodName, strategy },
    }),
};
