import { useState, useEffect } from 'react';
import { Input, Button, Card, Descriptions, Table, Tag, Space, App, Typography, Empty, Select } from 'antd';
import { SearchOutlined, CodeOutlined, BranchesOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { queryApi } from '../../api/query';
import { useBranchCommit } from '../../components/BranchCommitContext';
import BranchCommitInlineSelector from '../../components/BranchCommitInlineSelector';
import type { ClassDetail as ClassDetailType } from '../../types';

const { Text } = Typography;

export default function ClassDetail() {
  const { message } = App.useApp();
  const { branch, commitId, projects, setBranch, setCommitId } = useBranchCommit();
  // 顶栏已无全局项目选择器，项目在本页面内选择
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  // 当前项目的默认分支/commit，作为本地（无 Git）项目的兜底选项来源
  const currentProject = projects.find((p) => p.id === selectedProjectId);
  const [searchParams] = useSearchParams();
  const [qualifiedName, setQualifiedName] = useState(searchParams.get('name') || '');
  const [data, setData] = useState<ClassDetailType | null>(null);
  const [loading, setLoading] = useState(false);

  const search = async (name?: string) => {
    const q = name ?? qualifiedName;
    if (!q) { message.warning('请输入全限定类名'); return; }
    setLoading(true);
    try {
      const res = await queryApi.classDetail(q, branch, commitId);
      setData(res.data);
    } catch { message.error('查询失败'); }
    setLoading(false);
  };

  useEffect(() => {
    const name = searchParams.get('name');
    if (name) {
      queryApi.classDetail(name, branch, commitId)
        .then((res) => { setQualifiedName(name); setData(res.data); })
        .catch(() => message.error('查询失败'));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, branch, commitId]);

  return (
    <div className="page-container">
      {/* Search bar */}
      <Card style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
          <Space.Compact style={{ flex: 1, minWidth: 300, maxWidth: 600 }}>
            <Input
              value={qualifiedName}
              onChange={(e) => setQualifiedName(e.target.value)}
              placeholder="输入全限定类名，如 com.example.OrderService"
              onPressEnter={() => search()}
              prefix={<CodeOutlined />}
              allowClear
            />
            <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={() => search()}>
              查询
            </Button>
          </Space.Compact>
          <Select
            showSearch
            style={{ minWidth: 200 }}
            placeholder="选择项目"
            value={selectedProjectId ?? undefined}
            onChange={(v) => {
              setSelectedProjectId(v ?? null);
              const p = projects.find((x) => x.id === v);
              if (p) {
                // 切项目时用项目自带 branch/commitId 作初值，避免残留上一项目的版本选择
                setBranch(p.branch);
                setCommitId(p.commitId);
              }
            }}
            optionFilterProp="label"
            allowClear
            options={projects.map((p) => ({
              value: p.id,
              label: `${p.projectName} [${p.branch ?? 'local'}]`,
            }))}
          />
          <BranchCommitInlineSelector
            projectId={selectedProjectId}
            branch={branch}
            commitId={commitId}
            onBranchChange={setBranch}
            onCommitChange={setCommitId}
            fallbackBranch={currentProject?.branch}
            fallbackCommitId={currentProject?.commitId}
          />
        </div>
      </Card>

      {data && (
        <>
          {/* Basic info */}
          <Card
            title={
              <Space>
                <CodeOutlined />
                <span>{data.simpleName}</span>
                <Tag>{data.classType}</Tag>
              </Space>
            }
            style={{ marginBottom: 16 }}
          >
            <Descriptions column={{ xs: 1, sm: 2, md: 2 }} size="small">
              <Descriptions.Item label="全限定名">
                <Text copyable>{data.qualifiedName}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="包名">{data.packageName}</Descriptions.Item>
              <Descriptions.Item label="修饰符">{data.modifiers || '-'}</Descriptions.Item>
              <Descriptions.Item label="父类">{data.parentClass || '-'}</Descriptions.Item>
              <Descriptions.Item label="源文件" span={2}>
                <Text type="secondary">{data.sourceFilePath}</Text>
              </Descriptions.Item>
            </Descriptions>
          </Card>

          {/* Interfaces & Annotations */}
          <Card size="small" style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 12 }}>
              <Text strong><BranchesOutlined /> 接口</Text>
              <div style={{ marginTop: 6 }}>
                {data.interfaces?.length
                  ? data.interfaces.map((i) => <Tag key={i} color="blue">{i}</Tag>)
                  : <Text type="secondary">无</Text>}
              </div>
            </div>
            <div>
              <Text strong>@ 注解 ({data.annotations?.length || 0})</Text>
              <div style={{ marginTop: 6 }}>
                {data.annotations?.length
                  ? data.annotations.map((a) => <Tag key={a.annotationName} color="purple">@{a.annotationName}</Tag>)
                  : <Text type="secondary">无</Text>}
              </div>
            </div>
          </Card>

          {/* Methods */}
          <Card
            title={<Text strong>方法 ({data.methods?.length || 0})</Text>}
            size="small"
            style={{ marginBottom: 16 }}
          >
            {data.methods?.length ? (
              <Table
                rowKey="methodSignature"
                dataSource={data.methods}
                size="small"
                pagination={false}
                columns={[
                  { title: '方法名', dataIndex: 'methodName', width: 180, render: (v: string) => <Text strong>{v}</Text> },
                  { title: '返回类型', dataIndex: 'returnType', width: 140, render: (v: string) => <Tag>{v}</Tag> },
                  { title: '参数', dataIndex: 'parameterTypes', render: (v: string[]) => v?.join(', ') || '-', width: 220 },
                  { title: '修饰符', dataIndex: 'modifiers', width: 100, render: (v: string) => <Text type="secondary">{v}</Text> },
                  { title: '签名', dataIndex: 'methodSignature', ellipsis: true, render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text> },
                ]}
              />
            ) : <Empty description="暂无方法" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
          </Card>

          {/* Fields */}
          <Card
            title={<Text strong>字段 ({data.fields?.length || 0})</Text>}
            size="small"
            style={{ marginBottom: 16 }}
          >
            {data.fields?.length ? (
              <Table
                rowKey="fieldName"
                dataSource={data.fields}
                size="small"
                pagination={false}
                columns={[
                  { title: '字段名', dataIndex: 'fieldName', width: 180, render: (v: string) => <Text strong>{v}</Text> },
                  { title: '类型', dataIndex: 'fieldType', width: 200, render: (v: string) => <Tag>{v}</Tag> },
                  { title: '修饰符', dataIndex: 'modifiers', width: 100, render: (v: string) => <Text type="secondary">{v}</Text> },
                ]}
              />
            ) : <Empty description="暂无字段" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
          </Card>

          {/* Dependencies */}
          <Card
            title={<Text strong>依赖 ({data.dependencies?.length || 0})</Text>}
            size="small"
          >
            {data.dependencies?.length ? (
              <Table
                rowKey="qualifiedName"
                dataSource={data.dependencies}
                size="small"
                pagination={false}
                columns={[
                  { title: '类名', dataIndex: 'qualifiedName', ellipsis: true, render: (v: string) => <Text copyable={{ text: v }}>{v}</Text> },
                  { title: '类型', dataIndex: 'type', width: 120, render: (v: string) => <Tag>{v}</Tag> },
                ]}
              />
            ) : <Empty description="暂无依赖" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
          </Card>
        </>
      )}

      {!data && !loading && (
        <Empty description="输入全限定类名进行查询" style={{ marginTop: 60 }} />
      )}
    </div>
  );
}
