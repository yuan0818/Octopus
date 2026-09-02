import { useEffect, useState } from 'react';
import { Card, Select, Button, Tag, Row, Col, Typography, Space, Empty, App } from 'antd';
import { DiffOutlined, ThunderboltOutlined, SwapOutlined } from '@ant-design/icons';
import { queryApi } from '../../api/query';
import { reviewApi } from '../../api/review';
import type { ProjectInfo, EntryPointInfo } from '../../types';

const { Text } = Typography;

interface VersionSel {
  branch?: string;
  commit?: string;
}

/**
 * 变更总结：同一入口在两个分析版本（分支/commit/tag 任意组合）之间的
 * 修改要点 + 测试要点。链路差异 git-backed（源码文本来自 git blob）。
 */
export default function ChangeSummaryPage() {
  const { message } = App.useApp();
  const [projects, setProjects] = useState<ProjectInfo[]>([]);
  const [projectId, setProjectId] = useState<number>();

  const [entryPoints, setEntryPoints] = useState<EntryPointInfo[]>([]);
  const [entryLoading, setEntryLoading] = useState(false);
  const [entry, setEntry] = useState<string>('');

  const [branchOptions, setBranchOptions] = useState<any[]>([]);
  const [from, setFrom] = useState<VersionSel>({});
  const [to, setTo] = useState<VersionSel>({});
  const [fromCommits, setFromCommits] = useState<any[]>([]);
  const [toCommits, setToCommits] = useState<any[]>([]);

  const [result, setResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  // 项目列表
  useEffect(() => {
    queryApi.projects().then(r => setProjects(Array.isArray(r?.data) ? r.data : [])).catch(() => {});
  }, []);

  // 项目切换：加载分支，默认对比最近两个分支（或同分支 HEAD）
  useEffect(() => {
    setResult(null); setEntry(''); setEntryPoints([]); setFrom({}); setTo({});
    if (projectId == null) { setBranchOptions([]); return; }
    queryApi.analyzedBranches(projectId)
      .then(r => {
        const list = (r?.data ?? []) as any[];
        setBranchOptions(list);
        if (list.length >= 2) {
          setFrom({ branch: list[1].name, commit: list[1].latestCommitId ?? undefined });
          setTo({ branch: list[0].name, commit: list[0].latestCommitId ?? undefined });
        } else if (list.length === 1) {
          setFrom({ branch: list[0].name });
          setTo({ branch: list[0].name });
        }
      })
      .catch(() => setBranchOptions([]));
  }, [projectId]);

  // 版本切换：加载各侧 commit 选项
  useEffect(() => {
    if (projectId == null || !from.branch) { setFromCommits([]); return; }
    queryApi.analyzedCommits(projectId, from.branch)
      .then(r => setFromCommits((r?.data ?? []) as any[])).catch(() => setFromCommits([]));
  }, [projectId, from.branch]);
  useEffect(() => {
    if (projectId == null || !to.branch) { setToCommits([]); return; }
    queryApi.analyzedCommits(projectId, to.branch)
      .then(r => setToCommits((r?.data ?? []) as any[])).catch(() => setToCommits([]));
  }, [projectId, to.branch]);

  // 目标版本切换：加载该版本入口列表
  useEffect(() => {
    if (projectId == null || !to.branch) { setEntryPoints([]); return; }
    setEntryLoading(true);
    queryApi.entryPoints(projectId, undefined, to.branch, to.commit ?? null)
      .then(r => setEntryPoints(Array.isArray(r?.data) ? r.data : []))
      .catch(() => setEntryPoints([]))
      .finally(() => setEntryLoading(false));
  }, [projectId, to.branch, to.commit]);

  const run = async () => {
    const e = entryPoints.find(x => x.signature === entry);
    if (!e || projectId == null) return;
    setLoading(true);
    setResult(null);
    try {
      const r = await reviewApi.changeSummary({
        projectId,
        className: e.qualifiedClassName,
        methodName: e.methodName,
        fromBranch: from.branch, fromCommitId: from.commit,
        toBranch: to.branch, toCommitId: to.commit,
      });
      setResult(r);
    } catch (err: any) {
      message.error(err?.message || '总结失败');
    } finally {
      setLoading(false);
    }
  };

  const commitOptions = (list: any[]) =>
    list.map(c => ({ value: c.commitId, label: `${c.commitId?.slice(0, 8)} ${c.shortMessage ?? ''}` }));

  return (
    <div className="page-container">
      {/* 选择器区 */}
      <Card size="small" styles={{ body: { padding: '16px 20px' } }} style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 12 }}>
          <DiffOutlined /> 变更总结 <Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>
            同一入口在两个版本间的修改要点与测试要点（源码对比来自 git）
          </Text>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center' }}>
          <Select style={{ minWidth: 200 }} placeholder="选择项目" value={projectId ?? undefined}
            onChange={setProjectId} showSearch optionFilterProp="label"
            options={projects.map(p => ({ value: p.id, label: p.projectName }))} />

          <Select style={{ minWidth: 240 }} placeholder="选择入口" value={entry || undefined}
            onChange={setEntry} showSearch optionFilterProp="label" loading={entryLoading}
            notFoundContent={projectId == null ? '请先选项目' : '该版本暂无入口'}
            options={entryPoints.map(e => ({
              value: e.signature,
              label: e.displayName ? `${e.displayName} → ${e.methodName}` : `${e.qualifiedClassName}.${e.methodName}`,
            }))} />

          <SwapOutlined style={{ color: '#999' }} />

          {/* 基准版本 */}
          <Select style={{ width: 140 }} placeholder="基准分支" value={from.branch}
            onChange={v => setFrom({ branch: v, commit: undefined })}
            options={branchOptions.map(b => ({ value: b.name, label: b.name }))} />
          <Select style={{ width: 200 }} placeholder="commit（默认 HEAD）" allowClear
            value={from.commit} onChange={v => setFrom({ ...from, commit: v })}
            options={commitOptions(fromCommits)} />

          <Text type="secondary">→</Text>

          {/* 目标版本 */}
          <Select style={{ width: 140 }} placeholder="目标分支" value={to.branch}
            onChange={v => setTo({ branch: v, commit: undefined })}
            options={branchOptions.map(b => ({ value: b.name, label: b.name }))} />
          <Select style={{ width: 200 }} placeholder="commit（默认 HEAD）" allowClear
            value={to.commit} onChange={v => setTo({ ...to, commit: v })}
            options={commitOptions(toCommits)} />

          <Button type="primary" icon={<ThunderboltOutlined />} loading={loading}
            disabled={!entry || !from.branch || !to.branch} onClick={run}>
            生成总结
          </Button>
        </div>
      </Card>

      {/* 结果区 */}
      {!result && !loading && (
        <Card styles={{ body: { padding: 60 } }}>
          <Empty description="选择项目、入口和两个版本后生成变更总结" />
        </Card>
      )}
      {result && (
        <Card size="small" styles={{ body: { padding: '16px 20px' } }}>
          <Space size={8} wrap style={{ marginBottom: 12 }}>
            <Tag color={result.gitBacked ? 'green' : 'orange'}>
              {result.gitBacked ? 'git 源码对比' : '快照对比（git 不可用）'}
            </Tag>
            <Tag color="blue">+{result.addedCount} 新增</Tag>
            <Tag color="red">-{result.removedCount} 移除</Tag>
            <Tag color="gold">~{result.changedCount} 变更</Tag>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {result.anchor} · {result.fromVersion} → {result.toVersion} · 模型 {result.model}
            </Text>
          </Space>
          <Row gutter={16}>
            <Col span={12}>
              <Card size="small" title="修改要点" styles={{ body: { maxHeight: 'calc(100vh - 340px)', overflow: 'auto' } }}>
                {(result.highlights ?? []).map((h: any, i: number) => (
                  <div key={i} style={{ marginBottom: 12 }}>
                    <Text strong>{i + 1}. {h.title}</Text>
                    <div style={{ color: '#555', fontSize: 12, whiteSpace: 'pre-wrap', marginTop: 2 }}>{h.detail}</div>
                  </div>
                ))}
              </Card>
            </Col>
            <Col span={12}>
              <Card size="small" title="测试要点" styles={{ body: { maxHeight: 'calc(100vh - 340px)', overflow: 'auto' } }}>
                {(result.testPoints ?? []).map((t: any, i: number) => (
                  <div key={i} style={{ marginBottom: 12 }}>
                    <Text strong>{i + 1}. {t.scenario}</Text>
                    <div style={{ color: '#555', fontSize: 12, marginTop: 2 }}>{t.reason}</div>
                  </div>
                ))}
              </Card>
            </Col>
          </Row>
        </Card>
      )}
    </div>
  );
}
