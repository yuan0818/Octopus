import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Table, Tag, Button, Space } from 'antd';
import { DatabaseOutlined, ExperimentOutlined, CheckCircleOutlined, CloseCircleOutlined, CodeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { repoApi } from '../../api/repos';
import { analysisApi } from '../../api/analysis';
import type { RepoConfig, AnalysisTask } from '../../types';

const statusColors: Record<string, string> = {
  RUNNING: '#1677ff', COMPLETED: '#52c41a', FAILED: '#ff4d4f',
  PENDING: '#d9d9d9', SKIPPED: '#faad14', DEGRADED: '#fa8c16',
};

export default function Dashboard() {
  const [repos, setRepos] = useState<RepoConfig[]>([]);
  const [tasks, setTasks] = useState<AnalysisTask[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    repoApi.list().then(res => setRepos(res.data)).catch(console.error);
    analysisApi.listTasks().then(res => setTasks(res.data)).catch(console.error);
  }, []);

  const running = tasks.filter((t) => t.status === 'RUNNING').length;
  const completed = tasks.filter((t) => t.status === 'COMPLETED').length;
  const failed = tasks.filter((t) => t.status === 'FAILED').length;

  const taskColumns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '标签/路径', key: 'tag', render: (_: any, r: AnalysisTask) => r.repoConfigId ? r.tag : '📁 ' + r.tag, ellipsis: true },
    { title: '分支', dataIndex: 'branch', width: 100 },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (v: string) => <Tag color={statusColors[v]}>{v}</Tag>,
    },
    { title: '步骤', dataIndex: 'currentStep', width: 120 },
    {
      title: '操作', width: 100,
      render: (_: any, r: AnalysisTask) => (
        <Button type="link" size="small" onClick={() => navigate(`/analysis/${r.id}`)}>详情</Button>
      ),
    },
  ];

  return (
    <div>
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic title="仓库总数" value={repos.length} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="运行中" value={running} valueStyle={{ color: '#1677ff' }} prefix={<ExperimentOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="已完成" value={completed} valueStyle={{ color: '#52c41a' }} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="失败" value={failed} valueStyle={{ color: '#ff4d4f' }} prefix={<CloseCircleOutlined />} />
          </Card>
        </Col>
      </Row>

      <Card title="分析任务（最近 10 条）" style={{ marginBottom: 24 }}>
        <Table
          dataSource={tasks.slice(0, 10)}
          rowKey="id"
          size="small"
          pagination={false}
          columns={taskColumns}
        />
      </Card>

      <Card title="仓库列表" style={{ marginBottom: 24 }}>
        <Table
          dataSource={repos}
          rowKey="id"
          size="small"
          pagination={false}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 60 },
            { title: '仓库 URL', dataIndex: 'repoUrl', ellipsis: true },
            { title: '分支', dataIndex: 'branch', width: 100 },
            {
              title: '认证', dataIndex: 'authType', width: 80,
              render: (v: string) => <Tag>{v}</Tag>,
            },
            {
              title: '操作', width: 200,
              render: (_, r: RepoConfig) => (
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

      <Card title="快速操作">
        <Space>
          <Button type="primary" onClick={() => navigate('/repos')}>管理仓库</Button>
          <Button onClick={() => navigate('/analysis')}>查看分析任务</Button>
          <Button icon={<CodeOutlined />} onClick={() => navigate('/query/callgraph')}>调用图查询</Button>
        </Space>
      </Card>
    </div>
  );
}
