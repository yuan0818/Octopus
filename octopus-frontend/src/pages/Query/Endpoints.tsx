import { useEffect, useState } from 'react';
import { Select, Table, Tag, Typography, Space, Alert } from 'antd';
import { queryApi } from '../../api/query';
import type { EndpointInfo, ProjectInfo } from '../../types';

const { Text } = Typography;

const HTTP_METHOD_COLORS: Record<string, string> = {
  GET: 'green',
  POST: 'blue',
  PUT: 'orange',
  DELETE: 'red',
  PATCH: 'purple',
  '*': 'default',
};

function shortCommit(commitId: string | null | undefined): string {
  if (!commitId) return '—';
  return commitId.length > 8 ? commitId.substring(0, 8) : commitId;
}

export default function EndpointsPage() {
  const [projects, setProjects] = useState<ProjectInfo[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [endpoints, setEndpoints] = useState<EndpointInfo[]>([]);
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [loadingEndpoints, setLoadingEndpoints] = useState(false);

  useEffect(() => {
    queryApi.projects()
      .then(res => {
        setProjects((res.data as unknown as ProjectInfo[]) ?? []);
      })
      .catch(() => {})
      .finally(() => setLoadingProjects(false));
  }, []);

  const handleProjectChange = (projectId: number) => {
    setSelectedProjectId(projectId);
    setLoadingEndpoints(true);
    queryApi.endpoints(projectId)
      .then(res => setEndpoints((res.data as unknown as EndpointInfo[]) ?? []))
      .catch(() => setEndpoints([]))
      .finally(() => setLoadingEndpoints(false));
  };

  const selectedProject = projects.find(p => p.id === selectedProjectId);

  const projectOptions = projects.map(p => ({
    value: p.id,
    label: (
      <Space>
        <Text strong>{p.projectName}</Text>
        {p.isApiService
          ? <Tag color="green">API服务</Tag>
          : p.isBackendProject
            ? <Tag color="blue">后端非API</Tag>
            : <Tag color="default">非后端</Tag>
        }
        <Text type="secondary">{p.branch ?? 'local'}</Text>
        <Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 11 }}>
          {shortCommit(p.commitId)}
        </Text>
      </Space>
    ),
  }));

  const columns = [
    {
      title: '方法',
      dataIndex: 'httpMethod',
      width: 80,
      render: (v: string) => <Tag color={HTTP_METHOD_COLORS[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: '路径',
      dataIndex: 'path',
      width: 280,
      render: (v: string) => <Text code>{v || '/'}</Text>,
    },
    {
      title: '控制器',
      dataIndex: 'controllerSimpleName',
      width: 200,
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
    <div style={{ padding: 16 }}>
      <div style={{ marginBottom: 16 }}>
        <Text strong style={{ marginRight: 8 }}>选择项目：</Text>
        <Select
          showSearch
          style={{ width: 480 }}
          placeholder="请选择要查看端点的项目（仅显示已分析项目）"
          loading={loadingProjects}
          optionLabelProp="label"
          optionFilterProp="label"
          onChange={handleProjectChange}
          options={projectOptions}
          filterOption={(input, option) =>
            String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
          }
        />
      </div>

      {selectedProject && (
        <Alert
          style={{ marginBottom: 16 }}
          type="info"
          message={
            <Space>
              <Text>项目：<Text strong>{selectedProject.projectName}</Text></Text>
              <Text>分支：<Tag color="blue">{selectedProject.branch ?? 'local'}</Tag></Text>
              <Text>Commit：<Text code style={{ fontSize: 12 }}>{selectedProject.commitId ?? '—'}</Text></Text>
              <Text>路径：<Text type="secondary">{selectedProject.projectPath}</Text></Text>
            </Space>
          }
        />
      )}

      {selectedProjectId != null && (
        <Table
          rowKey={(r, i) => `${r.qualifiedClassName}-${r.methodName}-${i}`}
          dataSource={endpoints}
          columns={columns}
          loading={loadingEndpoints}
          size="small"
          pagination={{ pageSize: 50, showSizeChanger: true, showTotal: (t) => `共 ${t} 个端点` }}
          locale={{ emptyText: selectedProject && !selectedProject.isApiService ? '该项目无 HTTP 端点（非 API 服务）' : '无数据' }}
        />
      )}

      {selectedProjectId == null && (
        <div style={{ textAlign: 'center', color: '#999', marginTop: 80 }}>
          请先选择一个项目查看其 API 端点
        </div>
      )}
    </div>
  );
}
