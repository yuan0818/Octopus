import { Form, Input, Select, Button } from 'antd';
import type { RepoConfig } from '../../types';

interface Props {
  initialValues?: Partial<RepoConfig>;
  onOk: (values: Record<string, unknown>) => void;
}

export default function RepoForm({ initialValues, onOk }: Props) {
  const [form] = Form.useForm();
  const authType = Form.useWatch('authType', form);

  const handleFinish = (values: Record<string, unknown>) => {
    onOk(values);
    form.resetFields();
  };

  return (
    <Form form={form} layout="vertical" initialValues={initialValues} onFinish={handleFinish}>
      <Form.Item name="repoUrl" label="仓库 URL" rules={[{ required: true, message: '请输入仓库地址' }]}>
        <Input placeholder="https://github.com/example/demo.git" />
      </Form.Item>

      <Form.Item name="branch" label="默认分支" initialValue="main" rules={[{ required: true }]}>
        <Input />
      </Form.Item>

      <Form.Item name="authType" label="认证方式" initialValue="NONE" rules={[{ required: true }]}>
        <Select options={[
          { label: '无认证', value: 'NONE' },
          { label: 'HTTP (用户名+密码/Token)', value: 'HTTP' },
          { label: 'SSH (私钥)', value: 'SSH' },
        ]} />
      </Form.Item>

      {authType === 'HTTP' && (
        <>
          <Form.Item name="httpUsername" label="用户名" rules={[{ required: true }]}>
            <Input placeholder="token 或用户名" />
          </Form.Item>
          <Form.Item name="httpCredentialEncrypted" label="密码/Token (加密后)" rules={[{ required: true }]}>
            <Input.Password placeholder="AES/GCM 加密后的凭证" />
          </Form.Item>
        </>
      )}

      {authType === 'SSH' && (
        <>
          <Form.Item name="sshPrivateKeyPath" label="私钥路径" rules={[{ required: true }]}>
            <Input placeholder="/home/user/.ssh/id_rsa" />
          </Form.Item>
          <Form.Item name="sshPassphraseEncrypted" label="私钥密码 (加密后)">
            <Input.Password placeholder="可选，加密后的私钥密码" />
          </Form.Item>
          <Form.Item name="sshKnownHostsPath" label="known_hosts 路径">
            <Input placeholder="/home/user/.ssh/known_hosts" />
          </Form.Item>
        </>
      )}

      <Form.Item>
        <Button type="primary" htmlType="submit">
          {initialValues ? '保存修改' : '创建仓库'}
        </Button>
      </Form.Item>
    </Form>
  );
}
