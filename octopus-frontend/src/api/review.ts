import http from './reviewClient';
// Lookup 数据（项目/类/方法）由 code-intelligence 提供：表归其所有，review-agent 不再跨服务直读
import queryHttp from './client';
import reconstructHttp from './reconstructClient';
import type { ReviewRequest, ReviewResponse } from '../types';

/** Unwrap AxiosResponse to get the interceptor-modified data */
async function unwrap<T>(promise: Promise<any>): Promise<T> {
  const res = await promise;
  return res?.data as T;
}

export const reviewApi = {
  // ── Lookup ──
  listProjects(): Promise<any[]> { return unwrap(queryHttp.get('/lookup/projects')); },
  listClasses(projectId: number, q?: string, branch?: string | null, commitId?: string | null): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/classes', { params: { projectId, q, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  listEndpointClasses(projectId: number, q?: string, branch?: string | null, commitId?: string | null): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/endpoint-classes', { params: { projectId, q, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  listEntryClasses(projectId: number, q?: string, branch?: string | null, commitId?: string | null): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/entry-classes', { params: { projectId, q, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  listMethods(classId: number, q?: string, branch?: string | null, commitId?: string | null): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/methods', { params: { classId, q, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  listEndpointMethods(classId: number, q?: string, branch?: string | null, commitId?: string | null): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/endpoint-methods', { params: { classId, q, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  listEntryMethods(classId: number, q?: string, branch?: string | null, commitId?: string | null): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/entry-methods', { params: { classId, q, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  searchClasses(q: string, projectId?: number): Promise<any[]> {
    return unwrap(queryHttp.get('/lookup/search', { params: { q, projectId } }));
  },

  // ── Review ──
  validate(req: Partial<ReviewRequest>): Promise<{ valid: boolean; errors: string[]; warnings: string[] }> {
    return unwrap(http.post('/review/validate', req));
  },
  preview(req: Partial<ReviewRequest>): Promise<any> { return unwrap(http.post('/review/preview', req)); },
  review(req: ReviewRequest): Promise<ReviewResponse> { return unwrap(http.post('/review', req)); },
  getReview(sessionId: string): Promise<ReviewResponse> { return unwrap(http.get(`/review/${sessionId}`)); },

  // ── 审查追问（混合形态对话层）──
  chat(req: {
    threadId?: string;
    question: string;
    projectId?: number;
    className?: string;
    methodName?: string;
    strategy?: string;
    branch?: string | null;
    commitId?: string | null;
    skillId?: string;
    modelName?: string;
    reviewResult?: string;
    diffContext?: string;
  }): Promise<{ threadId: string; answer: string; model: string }> {
    return unwrap(http.post('/review/chat', req));
  },
  chatHistory(threadId: string): Promise<Array<{ role: string; content: string; createdAt?: string }>> {
    return unwrap(http.get(`/review/chat/${threadId}`));
  },

  // ── 需求源（默认 Jira；其他来源由使用者实现 RequirementSourceAdapter）──
  fetchRequirementSources(): Promise<Array<{ sourceType: string; displayName: string; configured: boolean; baseUrl?: string }>> {
    return unwrap(http.get('/requirements/sources'));
  },
  fetchRequirement(source: string, ticket: string): Promise<{ source: string; ticketKey: string; title: string; url: string; items: any[] }> {
    return unwrap(http.get('/requirements/fetch', { params: { source, ticket } }));
  },
  getRequirementConfig(sourceType: string): Promise<any> {
    return unwrap(http.get(`/admin/requirements/config/${sourceType}`));
  },
  saveRequirementConfig(sourceType: string, config: any): Promise<any> {
    return unwrap(http.put(`/admin/requirements/config/${sourceType}`, config));
  },
  testRequirementConfig(sourceType: string): Promise<string> {
    return unwrap(http.post(`/admin/requirements/config/${sourceType}/test`));
  },

  // ── 变更总结（同一入口跨版本：修改要点 + 测试要点）──
  changeSummary(req: {
    projectId: number; className: string; methodName: string;
    fromBranch?: string; fromCommitId?: string;
    toBranch?: string; toCommitId?: string;
    modelName?: string;
  }): Promise<any> {
    return unwrap(http.post('/review/change-summary', req));
  },

  // ── Upload ──
  async upload(projectId: number, file: File): Promise<{ savedPath: string; originalName: string }> {
    const formData = new FormData();
    formData.append('files', file);
    // Use fetch directly instead of axios to avoid Content-Type conflicts
    const res = await fetch(`/review/api/review/upload?projectId=${projectId}`, {
      method: 'POST',
      body: formData,
    });
    if (!res.ok) {
      const errBody = await res.text().catch(() => '');
      throw new Error(`Upload failed (${res.status}): ${errBody.slice(0, 200)}`);
    }
    const json = await res.json();
    const body = json.data || json;
    const items = Array.isArray(body) ? body : [];
    const item = items[0];
    if (!item?.savedPath) throw new Error('Upload response missing savedPath');
    return { savedPath: item.savedPath, originalName: item.originalName || file.name };
  },

  // ── Memory ──
  getKnowledge(projectId: string, type?: string): Promise<any[]> {
    return unwrap(http.get('/memory/knowledge', { params: { projectId, ...(type ? { type } : {}) } }));
  },
  addKnowledge(data: any): Promise<any> { return unwrap(http.post('/memory/knowledge', { ...data, source: 'MANUAL' })); },
  getFindings(projectId: string, className?: string): Promise<any[]> {
    return unwrap(http.get('/memory/findings', { params: { projectId, className } }));
  },
  getSessions(projectId: string, anchorClass?: string, anchorMethod?: string): Promise<any[]> {
    return unwrap(http.get('/memory/sessions', { params: { projectId, anchorClass, anchorMethod } }));
  },
  getSessionsPaged(projectId: string, params?: { status?: string; anchorClass?: string; page?: number; size?: number; commitId?: string | null }): Promise<any> {
    const { commitId, ...rest } = params || {};
    return unwrap(http.get('/memory/sessions/page', { params: { projectId, ...rest, ...(commitId ? { commitId } : {}) } }));
  },
  getSessionDetail(sessionId: string): Promise<any> {
    return unwrap(http.get(`/memory/sessions/${sessionId}`));
  },
  getSessionFindings(sessionId: string): Promise<any[]> {
    return unwrap(http.get(`/memory/sessions/${sessionId}/findings`));
  },

  // ── Reconstruct ──
  getChainMetadata(projectId: number, className: string, methodName: string, strategy = 'BFS', branch?: string | null, commitId?: string | null): Promise<any> {
    return unwrap(reconstructHttp.get('/chain-metadata', { params: { projectId, className, methodName, strategy, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
  getReverseChain(projectId: number, className: string, methodName: string, strategy = 'BFS', branch?: string | null, commitId?: string | null): Promise<any> {
    return unwrap(reconstructHttp.get('/reverse-chain', { params: { projectId, className, methodName, strategy, branch: branch ?? undefined, commitId: commitId ?? undefined } }));
  },
};
