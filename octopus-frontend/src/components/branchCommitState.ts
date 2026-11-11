import { createContext, useContext } from 'react';
import type { ProjectInfo } from '../types';

/** 共享的项目/分支/commit 选择状态；Provider 见 BranchCommitContext.tsx。 */
export interface BranchCommitState {
  projectId: number | null;
  branch: string | null;
  commitId: string | null;
  projectName: string | null;
  projects: ProjectInfo[];
  setProject: (projectId: number) => void;
  setBranch: (branch: string | null) => void;
  setCommitId: (commitId: string | null) => void;
  loading: boolean;
  /** 项目列表加载失败（选择器据此给出可见的错误提示）。 */
  loadError: boolean;
}

export const BranchCommitContext = createContext<BranchCommitState>({
  projectId: null,
  branch: null,
  commitId: null,
  projectName: null,
  projects: [],
  setProject: () => {},
  setBranch: () => {},
  setCommitId: () => {},
  loading: false,
  loadError: false,
});

export function useBranchCommit() {
  return useContext(BranchCommitContext);
}

