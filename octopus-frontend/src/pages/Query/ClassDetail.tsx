import { useState, useEffect } from 'react';
import { Input, Button, Card, Descriptions, Table, Tag, Space, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import { queryApi } from '../../api/query';
import type { ClassDetail as ClassDetailType } from '../../types';

export default function ClassDetail() {
  const [searchParams] = useSearchParams();
  const [qualifiedName, setQualifiedName] = useState(searchParams.get('name') || '');
  const [data, setData] = useState<ClassDetailType | null>(null);
  const [loading, setLoading] = useState(false);

  const search = async (name?: string) => {
    const q = name ?? qualifiedName;
    if (!q) { message.warning('请输入全限定类名'); return; }
    setLoading(true);
    try {
      const res = await queryApi.classDetail(q);
      setData(res.data);
    } catch { message.error('查询失败'); }
    setLoading(false);
  };

  // 如果 URL 带了 ?name= 参数，自动触发查询
  useEffect(() => {
    const name = searchParams.get('name');
    if (name) {
      queryApi.classDetail(name)
        .then(res => {
          setQualifiedName(name);
          setData(res.data);
        })
        .catch(() => message.error('查询失败'));
    }
  }, [searchParams]);

  return (
    <div>
      <Space style={{ marginBottom: 24 }}>
        <Input value={qualifiedName} onChange={(e) => setQualifiedName(e.target.value)}
          placeholder="com.example.OrderService" style={{ width: 400 }}
          onPressEnter={() => search()} />
        <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={() => search()}>查询</Button>
      </Space>

      {data && (
        <>
          <Card title={data.simpleName} style={{ marginBottom: 16 }}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label="全限定名">{data.qualifiedName}</Descriptions.Item>
              <Descriptions.Item label="包名">{data.packageName}</Descriptions.Item>
              <Descriptions.Item label="类型">
                <Tag>{data.classType}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="修饰符">{data.modifiers}</Descriptions.Item>
              <Descriptions.Item label="父类">{data.parentClass || '-'}</Descriptions.Item>
              <Descriptions.Item label="源文件" span={2}>{data.sourceFilePath}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="接口" size="small" style={{ marginBottom: 16 }}>
            {data.interfaces?.length ? data.interfaces.map((i) => <Tag key={i} color="blue">{i}</Tag>) : '无'}
          </Card>

          <Card title={`注解 (${data.annotations?.length || 0})`} size="small" style={{ marginBottom: 16 }}>
            {data.annotations?.length ? data.annotations.map((a) => (
              <Tag key={a.annotationName} color="purple">{a.annotationName}</Tag>
            )) : '无'}
          </Card>

          <Card title={`方法 (${data.methods?.length || 0})`} size="small" style={{ marginBottom: 16 }}>
            <Table rowKey="methodSignature" dataSource={data.methods} size="small" pagination={false}
              columns={[
                { title: '方法名', dataIndex: 'methodName', width: 180 },
                { title: '返回类型', dataIndex: 'returnType', width: 140 },
                { title: '参数', dataIndex: 'parameterTypes', render: (v: string[]) => v?.join(', '), width: 200 },
                { title: '修饰符', dataIndex: 'modifiers', width: 100 },
                { title: '签名', dataIndex: 'methodSignature', ellipsis: true },
              ]}
            />
          </Card>

          <Card title={`字段 (${data.fields?.length || 0})`} size="small" style={{ marginBottom: 16 }}>
            <Table rowKey="fieldName" dataSource={data.fields} size="small" pagination={false}
              columns={[
                { title: '字段名', dataIndex: 'fieldName', width: 180 },
                { title: '类型', dataIndex: 'fieldType', width: 200 },
                { title: '修饰符', dataIndex: 'modifiers', width: 100 },
              ]}
            />
          </Card>

          <Card title={`依赖 (${data.dependencies?.length || 0})`} size="small">
            <Table rowKey="qualifiedName" dataSource={data.dependencies} size="small" pagination={false}
              columns={[
                { title: '类名', dataIndex: 'qualifiedName', ellipsis: true },
                { title: '类型', dataIndex: 'type', width: 120 },
              ]}
            />
          </Card>
        </>
      )}
    </div>
  );
}
