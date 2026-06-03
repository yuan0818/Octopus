import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Card, Descriptions, Timeline, Tag, Spin } from 'antd';
import { analysisApi } from '../../api/analysis';
import type { AnalysisTask } from '../../types';

const stepStatusColor: Record<string, string> = {
  COMPLETED: 'green', RUNNING: 'blue', FAILED: 'red', PENDING: 'default', SKIPPED: 'orange', DEGRADED: 'gold',
};

export default function TaskDetail() {
  const { taskId } = useParams<{ taskId: string }>();
  const [task, setTask] = useState<AnalysisTask | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!taskId) return;
    const id = parseInt(taskId);
    analysisApi.getTask(id).then(res => setTask(res.data)).catch(() => setTask(null)).finally(() => setLoading(false));
  }, [taskId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!task) return <div>任务不存在</div>;

  return (
    <div>
      <Card title={`任务 #${task.id}`} style={{ marginBottom: 24 }}>
        <Descriptions column={3} size="small">
          <Descriptions.Item label="状态">
            <Tag color={task.status === 'COMPLETED' ? 'green' : task.status === 'FAILED' ? 'red' : 'blue'}>
              {task.status}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="Tag">{task.tag}</Descriptions.Item>
          <Descriptions.Item label="分支">{task.branch}</Descriptions.Item>
          <Descriptions.Item label="Commit">{task.commitId?.substring(0, 12)}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{task.createdAt ? new Date(task.createdAt).toLocaleString() : '-'}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{task.updatedAt ? new Date(task.updatedAt).toLocaleString() : '-'}</Descriptions.Item>
        </Descriptions>
        {task.errorMessage && (
          <Descriptions column={1} size="small" style={{ marginTop: 12 }}>
            <Descriptions.Item label="错误信息">
              <span style={{ color: 'red' }}>{task.errorMessage}</span>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card title="步骤进度">
        <Timeline
          items={task.steps?.map((step) => ({
            color: stepStatusColor[step.status] || 'default',
            children: (
              <div>
                <strong>{step.stepName}</strong>
                <Tag style={{ marginLeft: 8 }} color={stepStatusColor[step.status]}>{step.status}</Tag>
                {step.completedAt && (
                  <span style={{ marginLeft: 12, color: '#999', fontSize: 12 }}>
                    {new Date(step.completedAt).toLocaleString()}
                  </span>
                )}
                {step.errorMessage && (
                  <div style={{ color: step.status === 'DEGRADED' ? 'orange' : 'red', fontSize: 12, marginTop: 4 }}>
                    {step.errorMessage}
                  </div>
                )}
              </div>
            ),
          }))}
        />
      </Card>
    </div>
  );
}
