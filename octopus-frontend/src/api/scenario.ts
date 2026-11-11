import axios from 'axios';

/** scenario-runner 服务直连（8086，vite proxy /scenarios → 8086） */
const http = axios.create({ baseURL: '/scenarios/api', timeout: 30000 });

async function unwrap<T>(promise: Promise<any>): Promise<T> {
  const res = await promise;
  return res?.data as T;
}

export interface ScenarioSummary {
  id: number; name: string; projectId: number | null; envProfileId: number | null;
  currentVersion: number; irStale: boolean; updatedAt: string;
}

export interface RunReport {
  success: boolean; totalMs: number;
  steps: Array<{
    stepId: string; callLabel: string | null; success: boolean; status: number;
    requestUrl: string | null; responseBody: any; extracted: Record<string, any>;
    assertionResults: string[]; error: string | null; elapsedMs: number;
  }>;
}

export const scenarioApi = {
  list(projectId?: number): Promise<ScenarioSummary[]> {
    return unwrap(http.get('/scenarios', { params: projectId ? { projectId } : {} }));
  },
  get(id: number): Promise<any> { return unwrap(http.get(`/scenarios/${id}`)); },
  save(body: any): Promise<any> { return unwrap(http.post('/scenarios', body)); },
  remove(id: number): Promise<void> { return unwrap(http.delete(`/scenarios/${id}`)); },
  tools(): Promise<any[]> { return unwrap(http.get('/scenarios/tools')); },
  run(id: number, mode: 'record' | 'execute'): Promise<RunReport> {
    return unwrap(http.post(`/scenarios/${id}/run?mode=${mode}`));
  },
  saveIr(id: number, irJson: string): Promise<any> {
    return unwrap(http.post(`/scenarios/${id}/ir`, { irJson }));
  },
  envProfiles(): Promise<any[]> { return unwrap(http.get('/env-profiles')); },
  saveEnvProfile(body: any): Promise<any> { return unwrap(http.post('/env-profiles', body)); },
};
