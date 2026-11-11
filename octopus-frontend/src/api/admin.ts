import http from './reviewClient';

/** Unwrap AxiosResponse to get the interceptor-modified data */
async function unwrap<T>(promise: Promise<any>): Promise<T> {
  const res = await promise;
  return res?.data as T;
}

export const adminApi = {
  // ── Config ──
  getConfigStatus(): Promise<Record<string, any>> { return unwrap(http.get('/admin/config/status')); },
  getAllApiKeys(): Promise<Record<string, string>> { return unwrap(http.get('/admin/config/api-keys')); },
  updateApiKey(provider: string, apiKey: string): Promise<void> {
    return unwrap(http.put(`/admin/config/api-keys/${provider}`, { apiKey }));
  },
  // ── Models ──
  getModels(): Promise<string[]> { return unwrap(http.get('/admin/models')); },
  async getModelConfigs(): Promise<any[]> {
    const data: any = await unwrap(http.get('/admin/models/configs'));
    // API returns map {name: config}, convert to array
    if (data && typeof data === 'object' && !Array.isArray(data)) {
      return Object.values(data);
    }
    return [];
  },
  createModel(config: any): Promise<void> { return unwrap(http.post('/admin/models', config)); },
  updateModel(name: string, config: any): Promise<void> { return unwrap(http.put(`/admin/models/${name}`, config)); },
  deleteModel(name: string): Promise<void> { return unwrap(http.delete(`/admin/models/${name}`)); },
  toggleModel(name: string): Promise<void> { return unwrap(http.post(`/admin/models/${name}/toggle`)); },
  testModel(name: string): Promise<{ success: boolean; message: string; elapsedMs: number; detail: Record<string, unknown> }> {
    return unwrap(http.post(`/admin/models/${name}/test`, undefined, { timeout: 190000 }));
  },

  // ── Prompts ──
  getPromptTemplates(): Promise<string[]> { return unwrap(http.get('/admin/prompts')); },
  getPromptTemplate(id: string): Promise<any> { return unwrap(http.get(`/admin/prompts/${id}`)); },
  createPromptTemplate(tpl: any): Promise<void> { return unwrap(http.post('/admin/prompts', tpl)); },
  updatePromptTemplate(id: string, tpl: any): Promise<void> { return unwrap(http.put(`/admin/prompts/${id}`, tpl)); },
  deletePromptTemplate(id: string): Promise<void> { return unwrap(http.delete(`/admin/prompts/${id}`)); },

  // ── Tools ──
  getTools(): Promise<any[]> { return unwrap(http.get('/admin/tools')); },
  getTool(id: string): Promise<any> { return unwrap(http.get(`/admin/tools/${id}`)); },
  createTool(tool: any): Promise<void> { return unwrap(http.post('/admin/tools', tool)); },
  updateTool(id: string, tool: any): Promise<void> { return unwrap(http.put(`/admin/tools/${id}`, tool)); },
  deleteTool(id: string): Promise<void> { return unwrap(http.delete(`/admin/tools/${id}`)); },
  toggleTool(id: string): Promise<void> { return unwrap(http.post(`/admin/tools/${id}/toggle`)); },

  // ── Skills ──
  getSkills(): Promise<any[]> { return unwrap(http.get('/admin/skills')); },
  getSkill(id: string): Promise<any> { return unwrap(http.get(`/admin/skills/${id}`)); },
  createSkill(skill: any): Promise<void> { return unwrap(http.post('/admin/skills', skill)); },
  updateSkill(id: string, skill: any): Promise<void> { return unwrap(http.put(`/admin/skills/${id}`, skill)); },
  deleteSkill(id: string): Promise<void> { return unwrap(http.delete(`/admin/skills/${id}`)); },
};
