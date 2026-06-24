import { useEffect, useState } from 'react';
import { Card, Table, Button, Modal, Form, Input, Switch, Space, Tag, App } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, PoweroffOutlined } from '@ant-design/icons';
import { adminApi } from '../../api/admin';

interface ToolItem {
  id: string;
  name: string;
  description: string;
  parameters: string;
  enabled: boolean;
}

export default function ToolManager() {
  const { message } = App.useApp();
  const [tools, setTools] = useState<ToolItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ToolItem | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setTools(await adminApi.getTools()); }
    catch (e: any) { message.error(e.message); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => { setEditing(null); form.resetFields(); setModalOpen(true); };
  const openEdit = (t: ToolItem) => { setEditing(t); form.setFieldsValue(t); setModalOpen(true); };

  const handleSave = async () => {
    const values = await form.validateFields();
    try {
      if (editing) await adminApi.updateTool(editing.id, values);
      else await adminApi.createTool(values);
      message.success('保存成功');
      setModalOpen(false);
      load();
    } catch (e: any) { message.error(e.message); }
  };

  const handleDelete = (id: string) => {
    Modal.confirm({ title: '确认删除', content: `删除 Tool "${id}"？`, onOk: async () => {
      await adminApi.deleteTool(id); message.success('已删除'); load();
    }});
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', render: (v: string, r: ToolItem) => (
      <Space><Tag color={r.enabled ? 'green' : 'default'}>{v}</Tag></Space>
    )},
    { title: '名称', dataIndex: 'name' },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: '参数 Schema', dataIndex: 'parameters', ellipsis: true, render: (v: string) => v ? v.slice(0, 60) + '...' : '-' },
    { title: '状态', dataIndex: 'enabled', render: (v: boolean) => <Switch checked={v} size="small" /> },
    { title: '操作', width: 140, render: (_: any, r: ToolItem) => (
      <Space>
        <Button size="small" icon={<PoweroffOutlined />} onClick={() => adminApi.toggleTool(r.id).then(load)} />
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.id)} />
      </Space>
    )},
  ];

  return (
    <Card title="Tool 管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加 Tool</Button>}>
      <Table dataSource={tools} columns={columns} rowKey="id" loading={loading} size="small" pagination={false} />
      <Modal title={editing ? '编辑 Tool' : '添加 Tool'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={600}>
        <Form form={form} layout="vertical">
          {!editing && <Form.Item name="id" label="ID" rules={[{ required: true }]}><Input placeholder="retrieve_source_code" /></Form.Item>}
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="获取源码" /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="parameters" label="参数 (JSON Schema)"><Input.TextArea rows={4} placeholder='{"type":"object","properties":{}}' /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
