import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Table, Button, Tag, Space, Typography, App, Empty } from 'antd';
import { ExperimentOutlined, ToolOutlined, PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { scenarioApi, type ScenarioSummary } from '../../api/scenario';
import LegacyNotice from '../../components/LegacyNotice';

const { Text } = Typography;

/** 场景列表页：点击新建/编辑 → 全屏编辑页（/scenarios/edit 或 /scenarios/edit/:id） */
export default function ScenariosPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [scenarios, setScenarios] = useState<ScenarioSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [tools, setTools] = useState<any[]>([]);

  const load = useCallback(() => {
    setLoading(true);
    scenarioApi.list()
      .then(d => setScenarios(Array.isArray(d) ? d : []))
      .catch(() => setScenarios([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    scenarioApi.tools().then(d => setTools(Array.isArray(d) ? d : [])).catch(() => {});
  }, [load]);

  const handleDelete = (id: number) => {
    modal.confirm({
      title: '确认删除',
      content: `删除场景 #${id}？`,
      onOk: async () => { await scenarioApi.remove(id); message.success('已删除'); load(); },
    });
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '场景名称', dataIndex: 'name', render: (v: string) => <Text strong>{v}</Text> },
    { title: '版本', dataIndex: 'currentVersion', width: 70, render: (v: number) => <Tag color="blue">v{v}</Tag> },
    { title: 'IR 状态', dataIndex: 'irStale', width: 100, render: (v: boolean) =>
      <Tag color={v ? 'orange' : 'green'}>{v ? '待组装' : '已就绪'}</Tag> },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v: string) =>
      <Text type="secondary" style={{ fontSize: 12 }}>{v?.substring(0, 19).replace('T', ' ')}</Text> },
    { title: '操作', width: 200, render: (_: any, r: ScenarioSummary) => (
      <Space size={4}>
        <Button size="small" type="primary" icon={<EditOutlined />} onClick={() => navigate(`/scenarios/edit/${r.id}`)}>
          编辑
        </Button>
        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.id)} />
      </Space>
    )},
  ];

  return (
    <div className="page-container">
      <LegacyNotice />
      <Card size="small" style={{ marginBottom: 16 }} styles={{ body: { padding: '12px 20px' } }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Text strong style={{ fontSize: 14 }}>
            <ExperimentOutlined /> 场景化测试
            <Text type="secondary" style={{ fontWeight: 400, fontSize: 12, marginLeft: 8 }}>
              人组装业务 DAG → LLM 组装 IR → Runner 执行
            </Text>
          </Text>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/scenarios/edit')}>
            新建场景
          </Button>
        </div>
      </Card>

      <Card size="small" style={{ marginBottom: 16 }} styles={{ body: { padding: '8px 12px' } }}>
        <Space size={8} wrap>
          <ToolOutlined /> <Text type="secondary" style={{ fontSize: 12 }}>工具目录：</Text>
          {tools.map(t => (
            <Tag key={t.name} color={t.kind === 'codec' ? 'blue' : t.kind === 'context' ? 'green' : 'purple'}>
              {t.name}<span style={{ marginLeft: 4, opacity: 0.6 }}>{t.kind}</span>
            </Tag>
          ))}
        </Space>
      </Card>

      <Table
        columns={columns}
        dataSource={scenarios}
        rowKey="id"
        size="small"
        loading={loading}
        locale={{ emptyText: <Empty description="暂无场景，点击右上角「新建场景」创建" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
        pagination={false}
      />
    </div>
  );
}
