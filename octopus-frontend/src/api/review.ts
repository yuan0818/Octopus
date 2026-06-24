import http from './reviewClient';
import reconstructHttp from './reconstructClient';
import type { ReviewRequest, ReviewResponse } from '../types';

/** Unwrap AxiosResponse to get the interceptor-modified data */
async function unwrap<T>(promise: Promise<any>): Promise<T> {
  const res = await promise;
  return res?.data as T;
}

export const reviewApi = {
  // ── Lookup ──
  listProjects(): Promise<any[]> { return unwrap(http.get('/lookup/projects')); },
  listClasses(projectId: number, q?: string): Promise<any[]> {
    return unwrap(http.get('/lookup/classes', { params: { projectId, q } }));
  },
  listEndpointClasses(projectId: number, q?: string): Promise<any[]> {
    return unwrap(http.get('/lookup/endpoint-classes', { params: { projectId, q } }));
  },
  listEntryClasses(projectId: number, q?: string): Promise<any[]> {
    return unwrap(http.get('/lookup/entry-classes', { params: { projectId, q } }));
  },
  listMethods(classId: number, q?: string): Promise<any[]> {
    return unwrap(http.get('/lookup/methods', { params: { classId, q } }));
  },
  listEndpointMethods(classId: number, q?: string): Promise<any[]> {
    return unwrap(http.get('/lookup/endpoint-methods', { params: { classId, q } }));
  },
  listEntryMethods(classId: number, q?: string): Promise<any[]> {
    return unwrap(http.get('/lookup/entry-methods', { params: { classId, q } }));
  },
  searchClasses(q: string, projectId?: number): Promise<any[]> {
    return unwrap(http.get('/lookup/search', { params: { q, projectId } }));
  },

  // ── Review ──
  validate(req: Partial<ReviewRequest>): Promise<{ valid: boolean; errors: string[]; warnings: string[] }> {
    return unwrap(http.post('/review/validate', req));
  },
  preview(req: Partial<ReviewRequest>): Promise<any> { return unwrap(http.post('/review/preview', req)); },
  review(req: ReviewRequest): Promise<ReviewResponse> { return unwrap(http.post('/review', req)); },
  getReview(sessionId: string): Promise<ReviewResponse> { return unwrap(http.get(`/review/${sessionId}`)); },

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
  getSessionsPaged(projectId: string, params?: { status?: string; anchorClass?: string; page?: number; size?: number }): Promise<any> {
    return unwrap(http.get('/memory/sessions/page', { params: { projectId, ...params } }));
  },
  getSessionDetail(sessionId: string): Promise<any> {
    return unwrap(http.get(`/memory/sessions/${sessionId}`));
  },
  getSessionFindings(sessionId: string): Promise<any[]> {
    return unwrap(http.get(`/memory/sessions/${sessionId}/findings`));
  },

  // ── Reconstruct ──
  getChainMetadata(projectId: number, className: string, methodName: string, strategy = 'BFS'): Promise<any> {
    return unwrap(reconstructHttp.get('/chain-metadata', { params: { projectId, className, methodName, strategy } }));
  },
  getReverseChain(projectId: number, className: string, methodName: string, strategy = 'BFS'): Promise<any> {
    return unwrap(reconstructHttp.get('/reverse-chain', { params: { projectId, className, methodName, strategy } }));
  },
};
