import { Alert, Button } from 'antd';
import { useNavigate } from 'react-router-dom';

/** 旧版「场景测试」原型的迁移提示：功能保留只读兼容，新的测试请在「业务 API 测试」中创建。 */
export default function LegacyNotice() {
  const navigate = useNavigate();
  return (
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 16 }}
      message="「场景测试」是旧版原型，已从导航中移除"
      description="新的接口测试请使用「业务 API 测试」：支持版本化流程、人工确认、AI 补全、变更回归与 JMeter 执行。现有场景仍可在此查看，后续版本将下线。"
      action={<Button size="small" type="primary" onClick={() => navigate('/business-testing/overview')}>前往业务 API 测试</Button>}
    />
  );
}
