import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Table, Tag, Button, Space, Typography } from 'antd';
import {
  DatabaseOutlined,
  ExperimentOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CodeOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  RightOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { repoApi } from '../../api/repos';
import { analysisApi } from '../../api/analysis';
import type { RepoConfig, AnalysisTask } from '../../types';

const { Text } = Typography;

const statusColors: Record<string, string> = {
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
  PENDING: 'default',
  SKIPPED: 'warning',
  DEGRADED: 'warning',
};

export default function Dashboard() {
  const [repos, setRepos] = useState<RepoConfig[]>([]);
  const [tasks, setTasks] = useState<AnalysisTask[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    Promise.all([
      repoApi.list().catch(() => ({ data: [] })),
      analysisApi.listTasks().catch(() => ({ data: [] })),
    ]).then(([r, t]) => {
      setRepos(r.data);
      setTasks(t.data);
      setLoading(false);
    });
  }, []);

  const running = tasks.filter((t) => t.status === 'RUNNING').length;
  const completed = tasks.filter((t) => t.status === 'COMPLETED').length;
  const failed = tasks.filter((t) => t.status === 'FAILED').length;

  const statCards = [
    { title: '仓库总数', value: repos.length, icon: <DatabaseOutlined />, color: '#1677ff', bg: '#e6f4ff' },
    { title: '运行中', value: running, icon: <ExperimentOutlined />, color: '#1677ff', bg: '#e6f4ff' },
    { title: '已完成', value: completed, icon: <CheckCircleOutlined />, color: '#52c41a', bg: '#f6ffed' },
    { title: '失败', value: failed, icon: <CloseCircleOutlined />, color: '#ff4d4f', bg: '#fff2f0' },
  ];

  const taskColumns = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 60,
      render: (v: number) => <Text code style={{ fontSize: 12 }}>#{v}</Text>,
    },
    {
      title: '标签/路径',
      key: 'tag',
      ellipsis: true,
      render: (_: unknown, r: AnalysisTask) => r.repoConfigId ? r.tag : '📁 ' + r.tag,
    },
    {
      title: '分支',
      dataIndex: 'branch',
      width: 120,
      render: (v: string) => v ? <Tag icon={<BranchesOutlined />} color="blue">{v}</Tag> : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (v: string) => <Tag color={statusColors[v]}>{v}</Tag>,
    },
    {
      title: '步骤',
      dataIndex: 'currentStep',
      width: 140,
      ellipsis: true,
      render: (v: string) => <Text type="secondary">{v || '-'}</Text>,
    },
    {
      title: '操作',
      width: 80,
      render: (_: unknown, r: AnalysisTask) => (
        <Button type="link" size="small" onClick={() => navigate(`/analysis/${r.id}`)}>
          详情 <RightOutlined />
        </Button>
      ),
    },
  ];

  const quickActions = [
    { label: '管理仓库', icon: <DatabaseOutlined />, path: '/repos', primary: true },
    { label: '分析任务', icon: <ExperimentOutlined />, path: '/analysis', primary: false },
    { label: '调用图查询', icon: <CodeOutlined />, path: '/query/callgraph', primary: false },
    { label: 'Code Review', icon: <BranchesOutlined />, path: '/review', primary: false },
  ];

  return (
    <div className="page-container">
      {/* Stats */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {statCards.map((s) => (
          <Col xs={12} sm={12} md={6} key={s.title}>
            <Card className="stat-card" style={{ borderTop: `3px solid ${s.color}` }}>
              <Statistic
                title={<Text type="secondary">{s.title}</Text>}
                value={s.value}
                valueStyle={{ color: s.color, fontWeight: 600 }}
                prefix={s.icon}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* Quick Actions */}
      <Card style={{ marginBottom: 24 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
          {quickActions.map((a) => (
            <Button
              key={a.path}
              type={a.primary ? 'primary' : 'default'}
              icon={a.icon}
              size="large"
              onClick={() => navigate(a.path)}
            >
              {a.label}
            </Button>
          ))}
        </div>
      </Card>

      {/* Recent Tasks */}
      <Card
        title={
          <Space>
            <ClockCircleOutlined />
            <span>最近分析任务</span>
          </Space>
        }
        extra={
          <Button type="link" onClick={() => navigate('/analysis')}>
            查看全部 <RightOutlined />
          </Button>
        }
        style={{ marginBottom: 24 }}
      >
        <Table
          dataSource={tasks.slice(0, 8)}
          rowKey="id"
          size="small"
          pagination={false}
          columns={taskColumns}
          loading={loading}
        />
      </Card>

      {/* Repo Table */}
      <Card
        title={
          <Space>
            <DatabaseOutlined />
            <span>仓库列表</span>
          </Space>
        }
        extra={
          <Button type="link" onClick={() => navigate('/repos')}>
            管理 <RightOutlined />
          </Button>
        }
      >
        <Table
          dataSource={repos}
          rowKey="id"
          size="small"
          pagination={false}
          loading={loading}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => <Text code style={{ fontSize: 12 }}>#{v}</Text> },
            { title: '仓库 URL', dataIndex: 'repoUrl', ellipsis: true, render: (v: string) => <Text copyable={{ text: v }}>{v}</Text> },
            { title: '分支', dataIndex: 'branch', width: 120, render: (v: string) => <Tag icon={<BranchesOutlined />}>{v}</Tag> },
            { title: '认证', dataIndex: 'authType', width: 80, render: (v: string) => <Tag color={v === 'NONE' ? 'default' : 'blue'}>{v}</Tag> },
            {
              title: '操作',
              width: 160,
              render: (_: unknown, r: RepoConfig) => (
                <Space>
                  <Button type="link" size="small" onClick={() => navigate('/repos')}>管理</Button>
                  <Button type="link" size="small" onClick={() => {
                    const tag = prompt('输入 Tag 名称:');
                    if (tag) analysisApi.start(r.id, tag).then(() => navigate('/analysis'));
                  }}>分析</Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
