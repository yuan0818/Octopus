import { useEffect, useState } from 'react';
import { Card, Table, Button, Modal, Form, Input, Switch, Space, Tag, App, Select } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, PoweroffOutlined } from '@ant-design/icons';
import { adminApi } from '../../api/admin';

export default function ModelManager() {
  const { message } = App.useApp();
  const [models, setModels] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const raw = await adminApi.getModelConfigs(); // api layer returns array already
      if (Array.isArray(raw)) {
        setModels([...raw]); // force new array reference
      }
    } catch (e: any) {
      message.error(String(e));
    }
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ provider: 'claude', maxTokens: 128000, temperature: 0.3, enabled: true, weight: 100 });
    setModalOpen(true);
  };

  const openEdit = (record: any) => {
    setEditing(record);
    // 密钥不回显：留空表示沿用已存密钥（后端加密存储）
    form.setFieldsValue({ ...record, apiKey: '' });
    setModalOpen(true);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    try {
      if (editing) {
        await adminApi.updateModel(editing.name, values);
      } else {
        await adminApi.createModel(values);
      }
      message.success('保存成功');
      setModalOpen(false);
      load();
    } catch (e: any) {
      message.error(String(e));
    }
  };

  const handleDelete = (name: string) => {
    Modal.confirm({
      title: '确认删除',
      content: `删除模型 "${name}"？`,
      onOk: async () => { await adminApi.deleteModel(name); message.success('已删除'); load(); },
    });
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: 'Provider', dataIndex: 'provider', key: 'provider', render: (v: string) => <Tag>{v}</Tag> },
    { title: 'API 格式', dataIndex: 'apiFormat', key: 'apiFormat',
      render: (v: string, r: any) => <Tag color={(v || r.provider) === 'claude' ? 'purple' : 'blue'}>
        {v === 'claude' || (!v && r.provider === 'claude') ? 'Claude' : 'OpenAI 兼容'}</Tag> },
    { title: 'Model ID', dataIndex: 'modelId', key: 'modelId', ellipsis: true },
    { title: 'API URL', dataIndex: 'apiUrl', key: 'apiUrl', ellipsis: true },
    { title: 'Max Tokens', dataIndex: 'maxTokens', key: 'maxTokens', width: 100 },
    { title: '启用', dataIndex: 'enabled', key: 'enabled', width: 60, render: (v: boolean) => (v ? '✅' : '❌') },
    { title: '操作', key: 'action', width: 120, render: (_: any, r: any) => (
      <Space>
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
        <Button size="small" icon={<PoweroffOutlined />} onClick={async () => { await adminApi.toggleModel(r.name); load(); }} />
        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.name)} />
      </Space>
    )},
  ];

  return (
    <Card title="模型管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加模型</Button>}>
      <Table rowKey="name" dataSource={models} columns={columns} loading={loading} size="small" pagination={false} />
      <Modal title={editing ? '编辑模型' : '添加模型'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={600}>
        <Form form={form} layout="vertical">
          {!editing && <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="claude-sonnet-4" /></Form.Item>}
          <Form.Item name="provider" label="Provider" rules={[{ required: true }]}><Input placeholder="claude / openai / deepseek …" /></Form.Item>
          <Form.Item name="apiFormat" label="API 格式" initialValue="openai"
            tooltip="请求/响应报文格式。OpenAI 兼容（DeepSeek/GLM/GPT 等）选 openai；Anthropic 原生接口选 claude">
            <Select options={[
              { value: 'openai', label: 'OpenAI 兼容' },
              { value: 'claude', label: 'Claude (Anthropic)' },
            ]} />
          </Form.Item>
          <Form.Item name="modelId" label="Model ID" rules={[{ required: true }]}><Input placeholder="claude-sonnet-4-20250514" /></Form.Item>
          <Form.Item name="apiUrl" label="API URL" rules={[{ required: true }]}><Input placeholder="https://api.anthropic.com/v1" /></Form.Item>
          <Form.Item name="apiKey" label="API Key（加密存储）"
            tooltip="保存后以 AES-GCM 加密存储，不会回显明文；编辑时留空表示沿用已存密钥">
            <Input.Password placeholder={editing ? '留空表示不修改（已加密存储）' : 'sk-...'} />
          </Form.Item>
          <Form.Item name="maxTokens" label="Max Tokens"><Input type="number" /></Form.Item>
          <Form.Item name="temperature" label="Temperature"><Input type="number" step={0.1} /></Form.Item>
          <Form.Item name="weight" label="路由权重"><Input type="number" /></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
