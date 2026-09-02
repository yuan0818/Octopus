import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from 'react';
import { queryApi } from '../api/query';
import type { ProjectInfo } from '../types';

interface BranchCommitState {
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

const BranchCommitContext = createContext<BranchCommitState>({
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

export function BranchCommitProvider({ children }: { children: ReactNode }) {
  const [projects, setProjects] = useState<ProjectInfo[]>([]);
  const [projectId, setProjectId] = useState<number | null>(null);
  const [branch, setBranch] = useState<string | null>(null);
  const [commitId, setCommitId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    // 只在首次挂载加载项目列表；projectId == null 是"尚未初始化"的判断依据，不加入依赖
    setLoading(true);
    queryApi.projects()
      .then((res) => {
        const list = (res?.data ?? []) as ProjectInfo[];
        setProjects(list);
        if (list.length > 0 && projectId == null) {
          const first = list[0];
          setProjectId(first.id);
          setBranch(first.branch);
          setCommitId(first.commitId);
        }
      })
      .catch((err) => {
        // 静默吞错会让全局选择器只剩一个空下拉且无任何提示
        console.error('[BranchCommitContext] 加载项目列表失败:', err);
        setLoadError(true);
      })
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setProject = useCallback((id: number) => {
    setProjectId(id);
    const p = projects.find((x) => x.id === id);
    if (p) {
      setBranch(p.branch);
      setCommitId(p.commitId);
    }
  }, [projects]);

  const projectName = projectId != null
    ? projects.find((p) => p.id === projectId)?.projectName ?? null
    : null;

  return (
    <BranchCommitContext.Provider
      value={{
        projectId, branch, commitId, projectName, projects,
        setProject, setBranch, setCommitId, loading, loadError,
      }}
    >
      {children}
    </BranchCommitContext.Provider>
  );
}
