import { useEffect, useState } from 'react';
import { Form, Input, Button, Space, Tag, App as AntApp, Typography } from 'antd';
import { ApiOutlined, SaveOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { reviewApi } from '../../api/review';

const { Text } = Typography;

/**
 * 需求源连接配置：默认 Jira；其他来源（飞书/TAPD…）由使用者实现
 * RequirementSourceAdapter 注册后自动出现在这里。
 */
export default function RequirementSourceManager() {
  const { message } = AntApp.useApp();
  const [sources, setSources] = useState<any[]>([]);
  const [active, setActive] = useState<string>('jira');
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  useEffect(() => {
    reviewApi.fetchRequirementSources().then(d => {
      const list = Array.isArray(d) ? d : [];
      setSources(list);
      const first = list.find((s: any) => s.configured) ?? list[0];
      if (first) loadConfig(first.sourceType);
    }).catch(() => setSources([]));
  }, []);

  function loadConfig(sourceType: string) {
    setActive(sourceType);
    reviewApi.getRequirementConfig(sourceType).then((cfg: any) => {
      form.setFieldsValue({
        baseUrl: cfg?.baseUrl ?? '',
        account: cfg?.account ?? '',
        secret: cfg?.secret ?? '',
      });
    }).catch(() => form.resetFields());
  }

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await reviewApi.saveRequirementConfig(active, values);
      message.success('已保存');
    } catch (e: any) {
      message.error(e?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const test = async () => {
    setTesting(true);
    try {
      const result = await reviewApi.testRequirementConfig(active);
      message.info(result || '测试完成');
    } catch (e: any) {
      message.error(e?.message || '测试失败');
    } finally {
      setTesting(false);
    }
  };

  return (
    <div>
      {sources.length === 0 ? (
        <Text type="secondary">无已注册需求源（实现 RequirementSourceAdapter 并注册后自动出现）</Text>
      ) : (
        <>
          <Space wrap style={{ marginBottom: 16 }}>
            {sources.map((s: any) => (
              <Button key={s.sourceType} size="small"
                type={active === s.sourceType ? 'primary' : 'default'}
                icon={<ApiOutlined />}
                onClick={() => loadConfig(s.sourceType)}>
                {s.displayName}
                <Tag style={{ marginLeft: 6 }} color={s.configured ? 'green' : 'orange'}>
                  {s.configured ? '已配置' : '未配置'}
                </Tag>
              </Button>
            ))}
          </Space>

          <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
            <Form.Item label="Base URL" name="baseUrl"
              rules={[{ required: true, message: '必填，如 https://your-company.atlassian.net' }]}>
              <Input placeholder="https://your-company.atlassian.net" />
            </Form.Item>
            <Form.Item label="账号（Jira 为邮箱）" name="account" rules={[{ required: true }]}>
              <Input placeholder="you@company.com" />
            </Form.Item>
            <Form.Item label="API Token / 密钥" name="secret" rules={[{ required: true }]}>
              <Input.Password placeholder="Jira API Token（保存后脱敏显示，留 **** 表示不修改）" />
            </Form.Item>
            <Space>
              <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>保存</Button>
              <Button icon={<SafetyCertificateOutlined />} loading={testing} onClick={test}>测试配置</Button>
            </Space>
          </Form>
        </>
      )}
    </div>
  );
}
