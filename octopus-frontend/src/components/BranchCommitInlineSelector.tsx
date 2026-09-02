import { useEffect, useState } from 'react';
import { Select, Space } from 'antd';
import { BranchesOutlined, TagOutlined } from '@ant-design/icons';
import { queryApi } from '../api/query';
import type { BranchInfo, CommitInfo } from '../types';

export interface BranchCommitInlineSelectorProps {
  /** 当前项目 id，为 null 时选择器禁用 */
  projectId: number | null;
  /** 当前选中的分支（受控） */
  branch: string | null;
  /** 当前选中的 commitId（受控） */
  commitId: string | null;
  /** 分支变化回调（受控） */
  onBranchChange: (branch: string) => void;
  /** commit 变化回调（受控）；传 null 表示清空（如切分支后 HEAD 未知） */
  onCommitChange: (commitId: string | null) => void;
  /**
   * 兜底分支/commit：当项目是本地分析项目（无 Git 仓库配置，branches 接口返回空）时，
   * 分支下拉回退显示项目自带的 branch，让本地项目也能看到/切换版本信息。
   */
  fallbackBranch?: string | null;
  fallbackCommitId?: string | null;
  /** Select 宽度，默认 160 */
  width?: number;
}

function shortSha(sha: string | null | undefined): string {
  if (!sha) return 'HEAD';
  return sha.length > 8 ? sha.substring(0, 8) : sha;
}

/**
 * 页面内联的「分支 + commit」联动下拉选择器。
 *
 * 数据来源：queryApi.branches / queryApi.commits（JGit 真实读取）。
 * 设计为受控组件：列表数据自管理，但 branch/commitId 的值由父组件持有，
 * 通过 onBranchChange / onCommitChange 回写。
 */
export default function BranchCommitInlineSelector({
  projectId,
  branch,
  commitId,
  onBranchChange,
  onCommitChange,
  fallbackBranch,
  fallbackCommitId,
  width = 160,
}: BranchCommitInlineSelectorProps) {
  const [branches, setBranches] = useState<BranchInfo[]>([]);
  const [commits, setCommits] = useState<CommitInfo[]>([]);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [loadingCommits, setLoadingCommits] = useState(false);

  // 切换项目时加载分支列表：真实 Git 分支优先，本地项目（Git 服务返回空）
  // 回退到元数据枚举的已分析分支——同仓库多分支分析后各分支均可选
  useEffect(() => {
    if (projectId == null) {
      setBranches([]);
      return;
    }
    let cancelled = false;
    setLoadingBranches(true);
    Promise.all([
      queryApi.branches(projectId).catch(() => ({ data: [] as BranchInfo[] })),
      queryApi.analyzedBranches(projectId).catch(() => ({ data: [] as BranchInfo[] })),
    ])
      .then(([gitRes, analyzedRes]) => {
        if (cancelled) return;
        // queryApi 返回 AxiosResponse，真实数据在 .data
        const git = Array.isArray(gitRes?.data) ? (gitRes.data as BranchInfo[]) : [];
        const analyzed = Array.isArray(analyzedRes?.data) ? (analyzedRes.data as BranchInfo[]) : [];
        // 合并去重：Git 分支在前（带真实 HEAD），已分析分支补充（带分析过的 HEAD）
        const byName = new Map<string, BranchInfo>();
        for (const b of [...git, ...analyzed]) {
          if (b?.name && !byName.has(b.name)) byName.set(b.name, b);
        }
        let data = Array.from(byName.values());
        // 最终兜底：两者都为空且项目自带 branch 时显示单项
        if (data.length === 0 && fallbackBranch) {
          data = [{ name: fallbackBranch, latestCommitId: fallbackCommitId ?? null }];
        }
        setBranches(data);
      })
      .finally(() => { if (!cancelled) setLoadingBranches(false); });
    return () => { cancelled = true; };
  }, [projectId, fallbackBranch, fallbackCommitId]);

  // 切换分支时加载 commit 列表：Git 历史优先，本地项目回退到元数据枚举的已分析版本
  useEffect(() => {
    if (projectId == null || !branch) {
      setCommits([]);
      setLoadingCommits(false);
      return;
    }
    let cancelled = false;
    setLoadingCommits(true);
    queryApi
      .commits(projectId, branch)
      .then((res) => {
        if (cancelled) return;
        const data = Array.isArray(res?.data) ? (res.data as unknown as CommitInfo[]) : [];
        if (data.length > 0) {
          setCommits(data);
          return;
        }
        // Git 无历史（本地项目）→ 元数据已分析版本
        return queryApi.analyzedCommits(projectId, branch).then((r2) => {
          if (cancelled) return;
          const analyzed = Array.isArray(r2?.data) ? (r2.data as unknown as CommitInfo[]) : [];
          if (analyzed.length > 0) {
            setCommits(analyzed);
            return;
          }
          // 最终兜底：项目自带 commitId
          if (fallbackBranch && branch === fallbackBranch && fallbackCommitId) {
            setCommits([{ commitId: fallbackCommitId, shortMessage: '当前已分析版本', author: '', committedAt: 0 }]);
          } else {
            setCommits([]);
          }
        });
      })
      .catch(() => { if (!cancelled) setCommits([]); })
      .finally(() => { if (!cancelled) setLoadingCommits(false); });
    return () => { cancelled = true; };
  }, [projectId, branch, fallbackBranch, fallbackCommitId]);

  const disabled = projectId == null;

  // 防御：切项目/分支后，共享上下文里残留的旧值可能不在当前选项中
  // （antd Select 会把不在 options 里的 value 原样显示成裸字符串），
  // 加载完成后按选项过滤，让残留值显示为占位符而非误导性的旧值。
  const branchValue = !loadingBranches && branch && !branches.some((b) => b.name === branch)
    ? undefined
    : branch ?? undefined;
  const commitValue = !loadingCommits && commitId && !commits.some((c) => c.commitId === commitId)
    ? undefined
    : commitId ?? undefined;

  const handleBranchChange = (value: string) => {
    // 切换分支：重置 commit 为该分支 HEAD，并通知父组件；
    // HEAD 未知（null）时也要清空 commitId，避免残留旧分支的值
    const head = branches.find((b) => b.name === value)?.latestCommitId ?? null;
    onBranchChange(value);
    onCommitChange(head);
  };

  return (
    <Space size="small" wrap>
      <Select
        showSearch
        size="small"
        style={{ width }}
        placeholder="分支"
        value={branchValue}
        loading={loadingBranches}
        disabled={disabled}
        onChange={handleBranchChange}
        optionFilterProp="label"
        suffixIcon={<BranchesOutlined />}
        notFoundContent={disabled ? '请先选项目' : '无 Git 分支（本地项目）'}
        options={branches.map((b) => ({ value: b.name, label: b.name }))}
      />
      <Select
        showSearch
        size="small"
        style={{ width: width + 20 }}
        placeholder="Commit"
        value={commitValue}
        loading={loadingCommits}
        disabled={disabled || !branch}
        onChange={onCommitChange}
        optionFilterProp="label"
        suffixIcon={<TagOutlined />}
        notFoundContent={disabled ? '请先选项目' : '无 commit'}
        options={commits.map((c) => ({
          value: c.commitId,
          label: `${shortSha(c.commitId)} ${c.shortMessage ?? ''}`,
        }))}
      />
    </Space>
  );
}
