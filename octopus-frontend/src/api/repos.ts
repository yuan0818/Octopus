import http from './client';
import type { RepoConfig, RepoConfigForm, RepoSyncResult, DiscoveredProject } from '../types';

export const repoApi = {
  list: () => http.get<RepoConfig[]>('/repos'),

  get: (id: number) => http.get<RepoConfig>(`/repos/${id}`),

  create: (data: RepoConfigForm) => http.post<RepoConfig>('/repos', data),

  update: (id: number, data: RepoConfigForm) => http.put<RepoConfig>(`/repos/${id}`, data),

  delete: (id: number) => http.delete(`/repos/${id}`),

  sync: (id: number) => http.post<RepoSyncResult>(`/repos/${id}/sync`),

  projects: (id: number) => http.get<DiscoveredProject[]>(`/repos/${id}/projects`),
};
