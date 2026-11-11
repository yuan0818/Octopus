import { projectOptions } from '../../components/projectOptions';
import { useEffect, useState, useCallback } from 'react';
import { Select, Table, Tag, Typography, Space, Card, Empty } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { queryApi } from '../../api/query';
import { useBranchCommit } from '../../components/branchCommitState';
import BranchCommitInlineSelector from '../../components/BranchCommitInlineSelector';
import type { EntryPointInfo, ProjectInfo } from '../../types';

const { Text } = Typography;

const HTTP_METHOD_COLORS: Record<string, string> = {
  GET: 'green',
  POST: 'blue',
  PUT: 'orange',
  DELETE: 'red',
  PATCH: 'purple',
  '*': 'default',
};

const ENTRY_TYPE_COLORS: Record<string, string> = {
  HTTP_API: 'blue',
  MESSAGE_LISTENER: 'purple',
  SCHEDULED: 'gold',
  ROOT_METHOD: 'default',
};

export default function EndpointsPage() {
  const { branch, commitId, setBranch, setCommitId } = useBranchCommit();
  const [projects, setProjects] = useState<ProjectInfo[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [entryPoints, setEntryPoints] = useState<EntryPointInfo[]>([]);
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [loadingEntryPoints, setLoadingEntryPoints] = useState(false);

  // 与 CallGraph 相同的 effect 驱动写法：加载统一由 [projectId, branch, commitId] 触发，
  // 避免 handler 闭包读到旧值发出 (新branch, 旧commit) 之类的混合请求。
  // branch/commitId 允许为 null（本地项目无 Git 信息，透传给后端取默认值）。
  const loadEntryPoints = useCallback((projectId: number | null) => {
    if (projectId == null) {
      setEntryPoints([]);
      return;
    }
    setLoadingEntryPoints(true);
    queryApi.entryPoints(projectId, undefined, branch, commitId)
      .then((res) => setEntryPoints(res.data as unknown as EntryPointInfo[]))
      .catch(() => setEntryPoints([]))
      .finally(() => setLoadingEntryPoints(false));
  }, [branch, commitId]);

  useEffect(() => { loadEntryPoints(selectedProjectId); }, [selectedProjectId, loadEntryPoints]);

  useEffect(() => {
    queryApi.projects()
      .then((res) => setProjects(res.data as unknown as ProjectInfo[]))
      .catch(() => {})
      .finally(() => setLoadingProjects(false));
  }, []);

  const handleProjectChange = (projectId: number) => {
    setSelectedProjectId(projectId);
    const p = projects.find((x) => x.id === projectId);
    if (p) {
      // 切项目时用项目自带 branch/commitId 作初值
      setBranch(p.branch);
      setCommitId(p.commitId);
    }
  };

  const selectedProject = projects.find((p) => p.id === selectedProjectId);

  const columns = [
    {
      title: '入口类型',
      dataIndex: 'entryType',
      width: 120,
      render: (v: string) => <Tag color={ENTRY_TYPE_COLORS[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: '触发信息',
      dataIndex: 'displayName',
      width: 320,
      render: (_: string, row: EntryPointInfo) => row.entryType === 'HTTP_API'
        ? (
          <Space>
            <Tag color={HTTP_METHOD_COLORS[row.httpMethod ?? '*'] ?? 'default'} style={{ minWidth: 52, textAlign: 'center' }}>
              {row.httpMethod ?? '*'}
            </Tag>
            <Text code>{row.path || '/'}</Text>
          </Space>
        )
        : <Text>{row.triggerLabel || row.displayName}</Text>,
    },
    {
      title: '入口类',
      dataIndex: 'ownerSimpleName',
      width: 200,
      render: (v: string) => <Text strong>{v}</Text>,
    },
    {
      title: '方法名',
      dataIndex: 'methodName',
      width: 160,
    },
    {
      title: '签名',
      dataIndex: 'signature',
      ellipsis: true,
      render: (v: string) => <Text type="secondary" style={{ fontSize: 12 }}>{v}</Text>,
    },
  ];

  return (
    <div className="page-container">
      <Card>
        <div style={{ marginBottom: 16 }}>
          <Space style={{ marginBottom: 8 }}>
            <ApiOutlined />
            <Text strong>选择项目（API 优先展示入口）</Text>
          </Space>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
            <Select
              showSearch
              style={{ flex: 1, minWidth: 280, maxWidth: 480 }}
              placeholder="请选择要查看入口的项目（仅显示已分析项目）"
              loading={loadingProjects}
              onChange={handleProjectChange}
              optionFilterProp="label"
              options={projectOptions(projects)}
            />
            <BranchCommitInlineSelector
              projectId={selectedProjectId}
              branch={branch}
              commitId={commitId}
              onBranchChange={setBranch}
              onCommitChange={setCommitId}
              fallbackBranch={selectedProject?.branch}
              fallbackCommitId={selectedProject?.commitId}
            />
          </div>
        </div>

        {selectedProjectId != null ? (
          <Table
            rowKey={(r) => `${r.signature}-${r.entryType}-${r.displayName}`}
            dataSource={entryPoints}
            columns={columns}
            loading={loadingEntryPoints}
            size="small"
            pagination={{
              pageSize: 50,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 个入口`,
            }}
            locale={{
              emptyText: selectedProject && !selectedProject.isApiService
                ? '该项目无 HTTP API，已按其它入口类型自动兼容'
                : '无数据',
            }}
          />
        ) : (
          <Empty description="请先选择一个项目查看其入口列表" style={{ padding: '60px 0' }} />
        )}
      </Card>
    </div>
  );
}
