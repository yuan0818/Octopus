import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, App
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { adminApi } from '../../api/admin';

interface Skill {
  id: string;
  name: string;
  description: string;
  promptTemplateId: string;
  preferredProvider: string;
  outputParser: string;
  maxTokens: number;
  enabled: boolean;
}

export default function SkillManager() {
  const { message } = App.useApp();
  const [skills, setSkills] = useState<Skill[]>([]);
  const [prompts, setPrompts] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Skill | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const [s, p] = await Promise.all([
        adminApi.getSkills(),
        adminApi.getPromptTemplates(),
      ]);
      setSkills(s);
      setPrompts(Array.isArray(p) ? p : []);
    } catch (e: any) { message.error(e.message); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ preferredProvider: 'claude', maxTokens: 128000, enabled: true });
    setModalOpen(true);
  };
  const openEdit = (s: Skill) => {
    setEditing(s);
    form.setFieldsValue(s);
    setModalOpen(true);
  };

  const handleSave = async () => {
    const values = await form.validateFields();
    try {
      if (editing) {
        await adminApi.updateSkill(editing.id, { ...values, id: editing.id });
      } else {
        await adminApi.createSkill(values);
      }
      message.success('保存成功');
      setModalOpen(false);
      load();
    } catch (e: any) { message.error(e.message); }
  };

  const handleDelete = async (id: string) => {
    Modal.confirm({ title: '确认删除', content: `删除 Skill "${id}"？`, onOk: async () => {
      await adminApi.deleteSkill(id);
      message.success('已删除');
      load();
    }});
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', render: (v: string) => <Tag color="blue">{v}</Tag> },
    { title: '名称', dataIndex: 'name' },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: 'Prompt', dataIndex: 'promptTemplateId', render: (v: string) => <Tag>{v}</Tag> },
    { title: 'Provider', dataIndex: 'preferredProvider', render: (v: string) => <Tag>{v}</Tag> },
    { title: 'Max Tokens', dataIndex: 'maxTokens', width: 100 },
    { title: '状态', dataIndex: 'enabled', width: 60, render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '禁用'}</Tag> },
    { title: '操作', width: 120, render: (_: any, r: Skill) => (
      <Space>
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.id)} />
      </Space>
    )},
  ];

  return (
    <Card title="Skill 管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加 Skill</Button>}>
      <Table dataSource={skills} columns={columns} rowKey="id" loading={loading} size="small" pagination={false} />
      <Modal title={editing ? '编辑 Skill' : '添加 Skill'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={600}>
        <Form form={form} layout="vertical">
          {!editing && <Form.Item name="id" label="ID" rules={[{ required: true }]}><Input placeholder="code-review" /></Form.Item>}
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="代码审查" /></Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="promptTemplateId" label="关联 Prompt 模板" rules={[{ required: true }]}>
            <Select options={prompts.map(p => ({ value: p, label: p }))} />
          </Form.Item>
          <Form.Item name="preferredProvider" label="首选 Provider" rules={[{ required: true }]}>
            <Select options={[{ value: 'claude', label: 'Claude' }, { value: 'openai', label: 'OpenAI' }]} />
          </Form.Item>
          <Form.Item name="outputParser" label="输出解析器"><Input placeholder="requirement-check" /></Form.Item>
          <Form.Item name="maxTokens" label="Max Tokens"><Input type="number" /></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
