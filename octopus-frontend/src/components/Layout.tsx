import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout as AntLayout, Menu, Typography } from 'antd';
import {
  DashboardOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  SearchOutlined,
  CodeOutlined,
  ApiOutlined,
} from '@ant-design/icons';

const { Sider, Content, Header } = AntLayout;

const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: '看板' },
  { key: '/repos', icon: <DatabaseOutlined />, label: '仓库管理' },
  { key: '/analysis', icon: <ExperimentOutlined />, label: '分析任务' },
  { key: '/query/callgraph', icon: <SearchOutlined />, label: '调用图查询' },
  { key: '/query/class', icon: <CodeOutlined />, label: '类详情' },
  { key: '/query/endpoints', icon: <ApiOutlined />, label: '端点列表' },
];

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  // Highlight the menu item that matches the current path prefix
  const selectedKey = menuItems
    .filter((m) => m.key !== '/')
    .find((m) => location.pathname.startsWith(m.key))?.key || '/';

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Sider collapsible collapsed={collapsed} onCollapse={setCollapsed}>
        <div style={{ height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Typography.Text strong style={{ color: '#fff', fontSize: collapsed ? 14 : 18 }}>
            {collapsed ? 'OC' : 'Octopus'}
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <AntLayout>
        <Header style={{ background: '#fff', padding: '0 24px', borderBottom: '1px solid #f0f0f0' }}>
          <Typography.Title level={4} style={{ margin: '16px 0' }}>
            Octopus Analysis Platform
          </Typography.Title>
        </Header>
        <Content style={{ margin: 0, background: '#f5f5f5', padding: 16, minHeight: 360 }}>
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  );
}
