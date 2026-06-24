import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Space, App, Tag, Tabs
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined } from '@ant-design/icons';
import { adminApi } from '../../api/admin';

const { TextArea } = Input;

interface PromptTemplate {
  id: string;
  name: string;
  systemPrompt: string;
  userPrompt: string;
  outputSchema: string;
}

export default function PromptManager() {
  const { message } = App.useApp();
  const [templates, setTemplates] = useState<PromptTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [editing, setEditing] = useState<PromptTemplate | null>(null);
  const [viewing, setViewing] = useState<PromptTemplate | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const ids = await adminApi.getPromptTemplates();
      const items: PromptTemplate[] = [];
      if (Array.isArray(ids)) {
        for (const id of ids) {
          try { items.push(await adminApi.getPromptTemplate(id)); } catch {}
        }
      }
      setTemplates(items);
    } catch (e: any) { message.error(e.message); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => {
    setEditing(null); form.resetFields(); setModalOpen(true);
  };
  const openEdit = (t: PromptTemplate) => {
    setEditing(t); form.setFieldsValue(t); setModalOpen(true);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    try {
      if (editing) {
        await adminApi.updatePromptTemplate(editing.id, { ...values, id: editing.id });
      } else {
        await adminApi.createPromptTemplate(values);
      }
      message.success('保存成功');
      setModalOpen(false);
      load();
    } catch (e: any) { message.error(e.message); }
  };

  const handleDelete = async (id: string) => {
    Modal.confirm({ title: '确认删除', content: `删除模板 "${id}"？`, onOk: async () => {
      await adminApi.deletePromptTemplate(id);
      message.success('已删除');
      load();
    }});
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', render: (v: string) => <Tag>{v}</Tag> },
    { title: '名称', dataIndex: 'name', ellipsis: true },
    { title: 'System Prompt', dataIndex: 'systemPrompt', ellipsis: true, render: (v: string) => v?.slice(0, 80) + (v?.length > 80 ? '...' : '') },
    { title: '操作', width: 140, render: (_: any, r: PromptTemplate) => (
      <Space>
        <Button size="small" icon={<EyeOutlined />} onClick={() => { setViewing(r); setDetailOpen(true); }} />
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.id)} />
      </Space>
    )},
  ];

  return (
    <Card title="Prompt 模板管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加模板</Button>}>
      <Table dataSource={templates} columns={columns} rowKey="id" loading={loading} size="small" pagination={false} />

      {/* Edit modal */}
      <Modal title={editing ? '编辑模板' : '添加模板'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={800}>
        <Form form={form} layout="vertical">
          {!editing && <Form.Item name="id" label="ID" rules={[{ required: true }]}><Input placeholder="review.code" /></Form.Item>}
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="代码审查" /></Form.Item>
          <Form.Item name="systemPrompt" label="System Prompt"><TextArea rows={6} /></Form.Item>
          <Form.Item name="userPrompt" label="User Prompt 模板"><TextArea rows={8} placeholder="支持 {{variable}} 占位符" /></Form.Item>
          <Form.Item name="outputSchema" label="输出 Schema"><TextArea rows={4} placeholder='{"type":"json","schema": {...}}' /></Form.Item>
        </Form>
      </Modal>

      {/* Detail modal */}
      <Modal title={`模板: ${viewing?.id}`} open={detailOpen} onCancel={() => setDetailOpen(false)} footer={null} width={800}>
        {viewing && (
          <Tabs items={[
            { key: 'system', label: 'System Prompt', children: <pre style={{ whiteSpace: 'pre-wrap', background: '#f5f5f5', padding: 12, borderRadius: 4 }}>{viewing.systemPrompt}</pre> },
            { key: 'user', label: 'User Prompt', children: <pre style={{ whiteSpace: 'pre-wrap', background: '#f5f5f5', padding: 12, borderRadius: 4 }}>{viewing.userPrompt}</pre> },
            { key: 'output', label: 'Output Schema', children: <pre style={{ whiteSpace: 'pre-wrap', background: '#f5f5f5', padding: 12, borderRadius: 4 }}>{viewing.outputSchema}</pre> },
          ]} />
        )}
      </Modal>
    </Card>
  );
}
