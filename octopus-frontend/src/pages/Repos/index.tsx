import { useEffect, useState } from 'react';
import { Button, Table, Tag, Space, Modal, App, Popconfirm, Card, Typography, Input } from 'antd';
import { PlusOutlined, SyncOutlined, ProjectOutlined, SearchOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import type { RepoConfig, JavaProject } from '../../types';
import { repoApi } from '../../api/repos';
import RepoForm from './RepoForm';

const { Text } = Typography;

export default function RepoList() {
  const { message } = App.useApp();
  const [repos, setRepos] = useState<RepoConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [editRepo, setEditRepo] = useState<RepoConfig | null>(null);
  const [projects, setProjects] = useState<Record<number, JavaProject[]>>({});
  const [search, setSearch] = useState('');

  const loadRepos = () => {
    setLoading(true);
    repoApi.list().then((res) => setRepos(res.data)).finally(() => setLoading(false));
  };

  useEffect(() => {
    repoApi.list().then((res) => setRepos(res.data)).finally(() => setLoading(false));
  }, []);

  const handleSync = async (id: number) => {
    try {
      await repoApi.sync(id);
      message.success('同步成功');
      loadRepos();
    } catch { message.error('同步失败'); }
  };

  const handleDelete = async (id: number) => {
    try {
      await repoApi.delete(id);
      message.success('删除成功');
      loadRepos();
    } catch { message.error('删除失败'); }
  };

  const handleShowProjects = async (id: number) => {
    try {
      const res = await repoApi.projects(id);
      setProjects((prev) => ({ ...prev, [id]: res.data }));
    } catch { message.error('获取项目列表失败'); }
  };

  const handleFormOk = async (values: Record<string, unknown>) => {
    try {
      if (editRepo) {
        await repoApi.update(editRepo.id, values as never);
      } else {
        await repoApi.create(values as never);
      }
      message.success(editRepo ? '更新成功' : '创建成功');
      setFormOpen(false);
      setEditRepo(null);
      loadRepos();
    } catch { message.error('操作失败'); }
  };

  const filteredRepos = repos.filter((r) => {
    if (!search) return true;
    const q = search.toLowerCase();
    return r.repoUrl.toLowerCase().includes(q) || r.branch.toLowerCase().includes(q);
  });

  const columns = [
    {
      title: 'ID', dataIndex: 'id', width: 60,
      render: (v: number) => <Text code style={{ fontSize: 12 }}>#{v}</Text>,
    },
    {
      title: '仓库 URL', dataIndex: 'repoUrl', ellipsis: true,
      render: (v: string) => <Text copyable={{ text: v }}>{v}</Text>,
    },
    {
      title: '分支', dataIndex: 'branch', width: 120,
      render: (v: string) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: '认证', dataIndex: 'authType', width: 90,
      render: (v: string) => <Tag color={v === 'NONE' ? 'default' : 'blue'}>{v}</Tag>,
    },
    {
      title: '操作', width: 340,
      render: (_: unknown, r: RepoConfig) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => { setEditRepo(r); setFormOpen(true); }}>
            编辑
          </Button>
          <Button size="small" icon={<SyncOutlined />} onClick={() => handleSync(r.id)}>
            同步
          </Button>
          <Button size="small" icon={<ProjectOutlined />} onClick={() => handleShowProjects(r.id)}>
            项目
          </Button>
          <Popconfirm title="确定删除该仓库?" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="page-container">
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <Input.Search
            placeholder="搜索仓库 URL 或分支"
            allowClear
            style={{ maxWidth: 320 }}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            prefix={<SearchOutlined />}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditRepo(null); setFormOpen(true); }}>
            新建仓库
          </Button>
        </div>

        <Table
          rowKey="id"
          dataSource={filteredRepos}
          columns={columns}
          loading={loading}
          size="small"
          expandable={{
            expandedRowRender: (r) => projects[r.id] ? (
              <Table
                rowKey="projectName"
                dataSource={projects[r.id]}
                pagination={false}
                size="small"
                columns={[
                  { title: '项目名', dataIndex: 'projectName' },
                  { title: '路径', dataIndex: 'projectPath', ellipsis: true },
                  { title: '构建工具', dataIndex: 'buildTool', width: 100, render: (v: string) => <Tag>{v}</Tag> },
                  { title: 'Java 版本', dataIndex: 'javaVersion', width: 100 },
                ]}
              />
            ) : <Text type="secondary">点击「项目」按钮加载</Text>,
          }}
        />
      </Card>

      <Modal
        title={editRepo ? '编辑仓库' : '新建仓库'}
        open={formOpen}
        onCancel={() => { setFormOpen(false); setEditRepo(null); }}
        footer={null}
        width={600}
        destroyOnHidden
      >
        <RepoForm initialValues={editRepo || undefined} onOk={handleFormOk} />
      </Modal>
    </div>
  );
}
