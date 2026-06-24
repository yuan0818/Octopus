import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Steps, Tag, Spin, Typography, Button, Space, Alert } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { analysisApi } from '../../api/analysis';
import type { AnalysisTask } from '../../types';

const { Text } = Typography;

const stepStatusMap: Record<string, 'finish' | 'process' | 'error' | 'wait'> = {
  COMPLETED: 'finish',
  RUNNING: 'process',
  FAILED: 'error',
  PENDING: 'wait',
  SKIPPED: 'wait',
  DEGRADED: 'wait',
};

const stepColorMap: Record<string, string> = {
  COMPLETED: '#52c41a',
  RUNNING: '#1677ff',
  FAILED: '#ff4d4f',
  PENDING: '#d9d9d9',
  SKIPPED: '#faad14',
  DEGRADED: '#fa8c16',
};

export default function TaskDetail() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const [task, setTask] = useState<AnalysisTask | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!taskId) return;
    const id = parseInt(taskId);
    analysisApi.getTask(id)
      .then((res) => setTask(res.data))
      .catch(() => setTask(null))
      .finally(() => setLoading(false));
  }, [taskId]);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }
  if (!task) return <Alert type="error" message="任务不存在" showIcon style={{ margin: 24 }} />;

  return (
    <div className="page-container">
      {/* Header */}
      <div style={{ marginBottom: 20, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/analysis')}>返回</Button>
          <Text strong style={{ fontSize: 18 }}>任务 #{task.id}</Text>
          <Tag
            color={task.status === 'COMPLETED' ? 'success' : task.status === 'FAILED' ? 'error' : 'processing'}
            style={{ marginLeft: 8, fontSize: 13 }}
          >
            {task.status}
          </Tag>
        </Space>
        <Button icon={<ReloadOutlined />} onClick={() => window.location.reload()}>刷新</Button>
      </div>

      {/* Task Info */}
      <Card style={{ marginBottom: 20 }}>
        <Descriptions column={{ xs: 1, sm: 2, md: 3 }} size="small">
          <Descriptions.Item label="Tag">{task.tag || '-'}</Descriptions.Item>
          <Descriptions.Item label="分支">
            <Tag color="blue">{task.branch || '-'}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Commit">
            <Text code>{task.commitId?.substring(0, 12) || '-'}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">{task.createdAt ? new Date(task.createdAt).toLocaleString() : '-'}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{task.updatedAt ? new Date(task.updatedAt).toLocaleString() : '-'}</Descriptions.Item>
        </Descriptions>
        {task.errorMessage && (
          <Alert
            type="error"
            showIcon
            icon={<CloseCircleOutlined />}
            message="错误信息"
            description={task.errorMessage}
            style={{ marginTop: 12 }}
          />
        )}
      </Card>

      {/* Steps Progress */}
      <Card title="步骤进度">
        <Steps
          direction="vertical"
          size="small"
          current={task.steps?.findIndex((s) => s.status === 'RUNNING') ?? -1}
          items={task.steps?.map((step) => ({
            status: stepStatusMap[step.status] || 'wait',
            icon: step.status === 'COMPLETED' ? <CheckCircleOutlined style={{ color: stepColorMap.COMPLETED }} /> :
                  step.status === 'FAILED' ? <CloseCircleOutlined style={{ color: stepColorMap.FAILED }} /> :
                  step.status === 'RUNNING' ? <ClockCircleOutlined style={{ color: stepColorMap.RUNNING }} /> : undefined,
            title: (
              <Space>
                <Text strong={step.status === 'RUNNING' || step.status === 'FAILED'}>{step.stepName}</Text>
                <Tag color={stepColorMap[step.status]}>{step.status}</Tag>
              </Space>
            ),
            description: (
              <div>
                {step.completedAt && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    完成于 {new Date(step.completedAt).toLocaleString()}
                  </Text>
                )}
                {step.errorMessage && (
                  <div style={{ color: step.status === 'DEGRADED' ? '#fa8c16' : '#ff4d4f', fontSize: 12, marginTop: 4 }}>
                    {step.errorMessage}
                  </div>
                )}
              </div>
            ),
          })) || []}
        />
      </Card>
    </div>
  );
}
