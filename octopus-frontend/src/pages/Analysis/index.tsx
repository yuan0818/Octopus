import { useEffect, useState } from 'react';
import { Table, Tag, Button, message, Space } from 'antd';
import { ReloadOutlined, RetweetOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { analysisApi } from '../../api/analysis';
import type { AnalysisTask } from '../../types';

export default function AnalysisList() {
  const [tasks, setTasks] = useState<AnalysisTask[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  const loadTasks = () => {
    setLoading(true);
    analysisApi.listTasks()
      .then(res => setTasks(res.data))
      .catch(() => message.error('加载任务列表失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    analysisApi.listTasks()
      .then(res => setTasks(res.data))
      .catch(() => message.error('加载任务列表失败'))
      .finally(() => setLoading(false));
  }, []);

  const handleRetry = async (id: number) => {
    try {
      await analysisApi.retry(id);
      message.success('重试已提交');
      loadTasks();
    } catch { message.error('重试失败'); }
  };

  const statusColor: Record<string, string> = {
    RUNNING: 'processing', COMPLETED: 'success', FAILED: 'error',
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '仓库', dataIndex: ['repoConfigId'], width: 80, render: (v: number | null) => v ?? '本地' },
    { title: 'Tag', dataIndex: 'tag', width: 120 },
    { title: '分支', dataIndex: 'branch', width: 100 },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => <Tag color={statusColor[v]}>{v}</Tag>,
    },
    { title: '当前步骤', dataIndex: 'currentStep', width: 120 },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    {
      title: '操作', width: 200,
      render: (_: unknown, r: AnalysisTask) => (
        <Space>
          <Button size="small" onClick={() => navigate(`/analysis/${r.id}`)}>详情</Button>
          {r.status === 'FAILED' && (
            <Button size="small" icon={<RetweetOutlined />} onClick={() => handleRetry(r.id)}>重试</Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Button icon={<ReloadOutlined />} onClick={loadTasks}>刷新</Button>
      </div>
      <Table rowKey="id" dataSource={tasks} columns={columns} loading={loading} />
    </div>
  );
}
