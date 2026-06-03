import http from './client';
import type { AnalysisTask } from '../types';

export const analysisApi = {
  start: (repoConfigId: number, tag: string) =>
    http.post<AnalysisTask>(`/analysis/${repoConfigId}?tag=${encodeURIComponent(tag)}`),

  startLocal: (projectPath: string, projectName?: string) =>
    http.post<AnalysisTask>('/analysis/local', { projectPath, projectName }),

  listTags: (repoConfigId: number) =>
    http.get<string[]>(`/analysis/${repoConfigId}/tags`),

  listTasks: () => http.get<AnalysisTask[]>('/analysis/tasks'),

  getTask: (taskId: number) => http.get<AnalysisTask>(`/analysis/tasks/${taskId}`),

  retry: (taskId: number) =>
    http.post<AnalysisTask>(`/analysis/tasks/${taskId}/retry`),
};
