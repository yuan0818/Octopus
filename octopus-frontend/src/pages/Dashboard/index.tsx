import { useEffect, useState } from 'react';
import { Card, Col, Row, Table, Tag, Button, Space, Typography, Empty } from 'antd';
import {
  DatabaseOutlined,
  ExperimentOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CodeOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  RightOutlined,
  RocketOutlined,
  CommentOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { repoApi } from '../../api/repos';
import { analysisApi } from '../../api/analysis';
import type { RepoConfig, AnalysisTask } from '../../types';
import { palette } from '../../theme';

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
    { title: '仓库总数', value: repos.length, icon: <DatabaseOutlined />, color: palette.primary, bg: palette.primaryBg },
    { title: '运行中', value: running, icon: <ExperimentOutlined />, color: palette.primary, bg: palette.primaryBg },
    { title: '已完成', value: completed, icon: <CheckCircleOutlined />, color: palette.success, bg: palette.successBg },
    { title: '失败', value: failed, icon: <CloseCircleOutlined />, color: palette.error, bg: palette.errorBg },
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
    { label: '管理仓库', desc: '添加 Git 仓库与凭证', icon: <DatabaseOutlined />, path: '/repos', color: palette.primary, bg: palette.primaryBg },
    { label: '分析任务', desc: '按 Tag 触发代码分析', icon: <RocketOutlined />, path: '/analysis', color: palette.purple, bg: palette.purpleBg },
    { label: '调用图查询', desc: '方法级调用链与影响面', icon: <CodeOutlined />, path: '/query/callgraph', color: palette.cyan, bg: palette.cyanBg },
    { label: 'Code Review', desc: '入口链路智能审查', icon: <CommentOutlined />, path: '/review', color: palette.orange, bg: palette.warningBg },
  ];

  const isFirstTime = !loading && repos.length === 0;

  return (
    <div className="page-container">
      {/* Stats */}
      <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
        {statCards.map((s) => (
          <Col xs={12} sm={12} md={6} key={s.title}>
            <Card className="stat-card" styles={{ body: { padding: '18px 20px' } }}>
              <div className="stat-card-inner">
                <div className="stat-card-icon" style={{ color: s.color, background: s.bg }}>
                  {s.icon}
                </div>
                <div>
                  <div className="stat-card-value" style={{ color: s.color }}>{s.value}</div>
                  <div className="stat-card-title">{s.title}</div>
                </div>
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      {/* Quick Actions */}
      <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
        {quickActions.map((a) => (
          <Col xs={12} md={6} key={a.path}>
            <div className="quick-action" onClick={() => navigate(a.path)}>
              <div className="quick-action-icon" style={{ color: a.color, background: a.bg }}>
                {a.icon}
              </div>
              <div style={{ minWidth: 0 }}>
                <div className="quick-action-label">{a.label}</div>
                <div className="quick-action-desc">{a.desc}</div>
              </div>
            </div>
          </Col>
        ))}
      </Row>

      {/* 首次使用引导：还没有仓库时给出明确的上手路径 */}
      {isFirstTime && (
        <Card style={{ marginBottom: 20 }} styles={{ body: { padding: '32px 24px' } }}>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span>
                还没有配置仓库<br />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  添加仓库 → 触发 Tag 分析 → 查询调用图 / 发起 Code Review
                </Text>
              </span>
            }
          >
            <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => navigate('/repos')}>
              添加第一个仓库
            </Button>
          </Empty>
        </Card>
      )}

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
        style={{ marginBottom: 20 }}
      >
        <Table
          dataSource={tasks.slice(0, 8)}
          rowKey="id"
          size="small"
          pagination={false}
          columns={taskColumns}
          loading={loading}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无分析任务" /> }}
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
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无仓库，点击右上角「管理」添加" /> }}
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
