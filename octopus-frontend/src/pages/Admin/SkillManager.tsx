import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, App, Tabs, Empty, Collapse
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined } from '@ant-design/icons';
import { adminApi } from '../../api/admin';
import RichTextEditor from '../../components/RichTextEditor';

interface Skill {
  id: string;
  name: string;
  description: string;
  promptTemplateId: string;
  outputParser: string;
  maxTokens: number;
  enabled: boolean;
  instructions?: string;
  references?: { title: string; content: string; type: string }[];
}

export default function SkillManager() {
  const { message } = App.useApp();
  const [skills, setSkills] = useState<Skill[]>([]);
  const [prompts, setPrompts] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Skill | null>(null);
  const [form] = Form.useForm();
  const [viewing, setViewing] = useState<Skill | null>(null);
  const [activeTab, setActiveTab] = useState('body');
  const references = Form.useWatch('references', form) || [];

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
    setActiveTab('body');
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ maxTokens: 128000, enabled: true, references: [], instructions: '' });
    setModalOpen(true);
  };
  const openEdit = (s: Skill) => {
    setActiveTab('body');
    setEditing(s);
    form.resetFields();
    form.setFieldsValue({ ...s, references: s.references || [], instructions: s.instructions || '' });
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
    { title: '参考资料', render: (_: any, s: Skill) => `${s.references?.length || 0} 份` },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: 'Prompt', dataIndex: 'promptTemplateId', render: (v: string) => <Tag>{v}</Tag> },
    { title: 'Max Tokens', dataIndex: 'maxTokens', width: 100 },
    { title: '状态', dataIndex: 'enabled', width: 60, render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '禁用'}</Tag> },
    { title: '操作', width: 120, render: (_: any, r: Skill) => (
      <Space>
        <Button size="small" aria-label={`查看 ${r.name}`} icon={<EyeOutlined />} onClick={() => setViewing(r)} />
        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)} />
        <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r.id)} />
      </Space>
    )},
  ];

  return (
    <Card title="Skill 管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加 Skill</Button>}>
      <p>Skill 定义提示词与审查规则；模型在发起任务时独立选择。</p>
      <Table dataSource={skills} columns={columns} rowKey="id" loading={loading} size="small" pagination={false} />
      <Modal title={editing ? '编辑 Skill' : '添加 Skill'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={1100} styles={{ body: { maxHeight: '76vh', overflowY: 'auto' } }}>
        <Form form={form} layout="vertical">
          {!editing && <Form.Item name="id" label="ID" rules={[{ required: true }]}><Input placeholder="code-review" /></Form.Item>}
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="代码审查" /></Form.Item>
          <Form.Item name="description" label="简介"><Input placeholder="用一句话描述这个 Skill 的用途" /></Form.Item>
          <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
            { key: 'body', label: 'Skill 正文', forceRender: true, children: <>
              <Form.Item name="instructions" label="执行说明" extra="定义任务目标、执行步骤和注意事项；运行时与关联 Prompt 一起发送给模型。">
                <RichTextEditor label="Skill 执行说明" />
              </Form.Item>
              <Form.Item name="promptTemplateId" label="关联 Prompt 模板" rules={[{ required: true }]}>
                <Select showSearch options={prompts.map(p => ({ value: p, label: p }))} />
              </Form.Item>
            </> },
            { key: 'references', label: `参考资料 (${references.length})`, forceRender: true, children: <>
              <p>为当前 Skill 创建规范、业务约定或示例文档。保存后随 Skill 加入模型上下文，最多 20 份。</p>
              <Form.List name="references">{(fields, { add, remove }) => <>
                {fields.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无参考资料，添加一份让 Skill 掌握具体规则" />}
                {fields.map(field => <Card key={field.key} size="small" style={{ marginBottom: 16 }}
                  title={`参考资料 ${field.name + 1}`} extra={<Button danger type="text" aria-label={`移除参考资料 ${field.name + 1}`} onClick={() => remove(field.name)}>移除</Button>}>
                  <Form.Item name={[field.name, 'title']} label="资料标题" rules={[{ required: true, whitespace: true, message: '请输入资料标题' }, { max: 160 }]}>
                    <Input aria-label={`参考资料 ${field.name + 1} 标题`} placeholder="例如：接口安全检查规范" />
                  </Form.Item>
                  <Form.Item name={[field.name, 'content']} label="资料正文" rules={[{ required: true, whitespace: true, message: '请输入参考资料正文' }, { max: 50000 }]}>
                    <RichTextEditor label={`参考资料 ${field.name + 1} 正文`} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'type']} hidden><Input /></Form.Item>
                </Card>)}
                <Button type="dashed" block icon={<PlusOutlined />} disabled={fields.length >= 20}
                  onClick={() => add({ title: '', content: '', type: 'markdown' })}>创建 Reference</Button>
              </>}</Form.List>
            </> },
            { key: 'settings', label: '高级设置', forceRender: true, children: <>
              <Form.Item name="outputParser" label="输出解析器"><Input placeholder="requirement-check" /></Form.Item>
              <Form.Item name="maxTokens" label="Max Tokens"><Input type="number" /></Form.Item>
              <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
            </> },
          ]} />
        </Form>
      </Modal>
      <Modal title={viewing?.name} open={!!viewing} onCancel={() => setViewing(null)} footer={null} width={1100}
        styles={{ body: { maxHeight: '76vh', overflowY: 'auto' } }}>
        {viewing && <>
          <p>{viewing.description}</p>
          <Tag>Prompt：{viewing.promptTemplateId}</Tag>
          <RichTextEditor readOnly value={viewing.instructions || ''} label="Skill 正文" />
          <h3>参考资料</h3>
          {viewing.references?.length ? <Collapse items={viewing.references.map((ref, index) => ({
            key: index, label: ref.title, children: <RichTextEditor readOnly value={ref.content} label={ref.title} />,
          }))} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无参考资料" />}
        </>}
      </Modal>
    </Card>
  );
}
