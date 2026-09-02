import { Tabs, Card } from 'antd';
import { CloudServerOutlined, FileTextOutlined, ThunderboltOutlined, ApiOutlined } from '@ant-design/icons';
import ModelManager from './ModelManager';
import PromptManager from './PromptManager';
import SkillManager from './SkillManager';
import RequirementSourceManager from './RequirementSourceManager';

export default function AdminPage() {
  return (
    <div className="page-container">
      <Card>
        <Tabs
          defaultActiveKey="models"
          tabPosition="left"
          style={{ minHeight: 'calc(100vh - 160px)' }}
          items={[
            {
              key: 'models',
              label: <span><CloudServerOutlined /> 模型管理</span>,
              children: <ModelManager />,
            },
            {
              key: 'prompts',
              label: <span><FileTextOutlined /> Prompt 管理</span>,
              children: <PromptManager />,
            },
            {
              key: 'skills',
              label: <span><ThunderboltOutlined /> Skill 管理</span>,
              children: <SkillManager />,
            },
            {
              key: 'requirement-sources',
              label: <span><ApiOutlined /> 需求源</span>,
              children: <RequirementSourceManager />,
            },
          ]} />
      </Card>
    </div>
  );
}
