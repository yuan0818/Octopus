import { useEffect, useState, useCallback } from 'react';
import { Table, Tag, Button, App, Space, Card, Typography, Input, Modal, Form } from 'antd';
import { ReloadOutlined, RetweetOutlined, SearchOutlined, RightOutlined, FolderOpenOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { analysisApi } from '../../api/analysis';
import type { AnalysisTask } from '../../types';

const { Text } = Typography;

const statusColor: Record<string, string> = {
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
};

export default function AnalysisList() {
  const { message } = App.useApp();
  const [tasks, setTasks] = useState<AnalysisTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [localModalOpen, setLocalModalOpen] = useState(false);
  const [localLoading, setLocalLoading] = useState(false);
  const [localForm] = Form.useForm();
  const navigate = useNavigate();

  const loadTasks = useCallback(() => {
    setLoading(true);
    analysisApi.listTasks()
      .then((res) => setTasks(res.data))
      .catch(() => message.error('加载任务列表失败'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { loadTasks(); }, [loadTasks]);

  const handleRetry = async (id: number) => {
    try {
      await analysisApi.retry(id);
      message.success('重试已提交');
      loadTasks();
    } catch { message.error('重试失败'); }
  };

  const handleLocalAnalysis = async (values: { projectPath: string; projectName?: string }) => {
    setLocalLoading(true);
    try {
      const res = await analysisApi.startLocal(values.projectPath, values.projectName);
      message.success('本地分析已提交');
      setLocalModalOpen(false);
      localForm.resetFields();
      navigate(`/analysis/${res.data.id}`);
    } catch {
      message.error('提交本地分析失败');
    } finally {
      setLocalLoading(false);
    }
  };

  const filteredTasks = tasks.filter((t) => {
    if (!search) return true;
    const q = search.toLowerCase();
    return (
      String(t.id).includes(q) ||
      t.tag?.toLowerCase().includes(q) ||
      t.branch?.toLowerCase().includes(q) ||
      t.status.toLowerCase().includes(q)
    );
  });

  const columns = [
    {
      title: 'ID', dataIndex: 'id', width: 70,
      render: (v: number) => <Text code style={{ fontSize: 12 }}>#{v}</Text>,
    },
    {
      title: '仓库', dataIndex: 'repoConfigId', width: 90,
      render: (v: number | null) => v ? <Tag color="blue">#{v}</Tag> : <Tag>本地</Tag>,
    },
    { title: 'Tag', dataIndex: 'tag', width: 140, ellipsis: true },
    {
      title: '分支', dataIndex: 'branch', width: 120,
      render: (v: string) => v ? <Tag>{v}</Tag> : '-',
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v: string) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    {
      title: '当前步骤', dataIndex: 'currentStep', width: 140, ellipsis: true,
      render: (v: string) => <Text type="secondary">{v || '-'}</Text>,
    },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 170,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    {
      title: '操作', width: 180,
      render: (_: unknown, r: AnalysisTask) => (
        <Space>
          <Button size="small" type="link" onClick={() => navigate(`/analysis/${r.id}`)}>
            详情 <RightOutlined />
          </Button>
          {r.status === 'FAILED' && (
            <Button size="small" icon={<RetweetOutlined />} onClick={() => handleRetry(r.id)}>
              重试
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="page-container">
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <Input.Search
            placeholder="搜索 ID / Tag / 分支 / 状态"
            allowClear
            style={{ maxWidth: 320 }}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            prefix={<SearchOutlined />}
          />
          <Space>
            <Button icon={<FolderOpenOutlined />} onClick={() => setLocalModalOpen(true)}>
              分析本地项目
            </Button>
            <Button icon={<ReloadOutlined />} onClick={loadTasks}>
              刷新
            </Button>
          </Space>
        </div>
        <Table
          rowKey="id"
          dataSource={filteredTasks}
          columns={columns}
          loading={loading}
          size="small"
          pagination={{
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            defaultPageSize: 20,
          }}
        />
      </Card>

      <Modal
        title="分析本地项目"
        open={localModalOpen}
        onCancel={() => { setLocalModalOpen(false); localForm.resetFields(); }}
        footer={null}
        width={520}
        destroyOnHidden
      >
        <Form form={localForm} layout="vertical" onFinish={handleLocalAnalysis} style={{ marginTop: 16 }}>
          <Form.Item
            name="projectPath"
            label="项目路径"
            rules={[{ required: true, message: '请输入本地项目绝对路径' }]}
          >
            <Input placeholder="/Users/dev/workspace/my-project" />
          </Form.Item>
          <Form.Item name="projectName" label="项目名称（可选）">
            <Input placeholder="留空则自动检测" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={localLoading}>
                开始分析
              </Button>
              <Button onClick={() => { setLocalModalOpen(false); localForm.resetFields(); }}>
                取消
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
