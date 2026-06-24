import { useEffect, useState } from 'react';
import { Select, Table, Tag, Typography, Space, Card, Empty } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { queryApi } from '../../api/query';
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

function shortCommit(commitId: string | null | undefined): string {
  if (!commitId) return '—';
  return commitId.length > 8 ? commitId.substring(0, 8) : commitId;
}

export default function EndpointsPage() {
  const [projects, setProjects] = useState<ProjectInfo[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [entryPoints, setEntryPoints] = useState<EntryPointInfo[]>([]);
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [loadingEntryPoints, setLoadingEntryPoints] = useState(false);

  useEffect(() => {
    queryApi.projects()
      .then((res) => setProjects((res.data as unknown as ProjectInfo[]) ?? []))
      .catch(() => {})
      .finally(() => setLoadingProjects(false));
  }, []);

  const handleProjectChange = (projectId: number) => {
    setSelectedProjectId(projectId);
    setLoadingEntryPoints(true);
    queryApi.entryPoints(projectId)
      .then((res) => setEntryPoints((res.data as unknown as EntryPointInfo[]) ?? []))
      .catch(() => setEntryPoints([]))
      .finally(() => setLoadingEntryPoints(false));
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
        <div style={{ marginBottom: 20 }}>
          <Space style={{ marginBottom: 8 }}>
            <ApiOutlined />
            <Text strong>选择项目（API 优先展示入口）</Text>
          </Space>
          <Select
            showSearch
            style={{ width: '100%', maxWidth: 560 }}
            placeholder="请选择要查看入口的项目（仅显示已分析项目）"
            loading={loadingProjects}
            onChange={handleProjectChange}
            optionFilterProp="label"
            options={projects.map((p) => ({
              value: p.id,
              label: `${p.projectName} [${p.branch ?? 'local'}] ${shortCommit(p.commitId)}`,
              project: p,
            }))}
          />
        </div>

        {selectedProject && (
          <div
            style={{
              marginBottom: 16,
              padding: '10px 16px',
              background: '#f6f8fa',
              borderRadius: 6,
              display: 'flex',
              flexWrap: 'wrap',
              gap: 16,
              alignItems: 'center',
            }}
          >
            <Text>项目：<Text strong>{selectedProject.projectName}</Text></Text>
            <Text>分支：<Tag color="blue">{selectedProject.branch ?? 'local'}</Tag></Text>
            <Text>Commit：<Text code style={{ fontSize: 12 }}>{selectedProject.commitId ?? '—'}</Text></Text>
            <Text type="secondary" style={{ fontSize: 12 }}>{selectedProject.projectPath}</Text>
          </div>
        )}

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
