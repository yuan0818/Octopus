import { useState, useMemo } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout as AntLayout, Menu, Typography, Breadcrumb, Avatar, theme } from 'antd';
import type { MenuProps } from 'antd';
import {
  DashboardOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  SearchOutlined,
  CodeOutlined,
  ApiOutlined,
  CommentOutlined,
  HistoryOutlined,
  SettingOutlined,
  BranchesOutlined,
  GithubOutlined,
  DiffOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from '@ant-design/icons';
import { BranchCommitProvider } from './BranchCommitContext';

const { Sider, Content, Header } = AntLayout;

type MenuItem = Required<MenuProps>['items'][number];

const menuItems: MenuItem[] = [
  { key: '/', icon: <DashboardOutlined />, label: '看板' },
  { key: '/repos', icon: <DatabaseOutlined />, label: '仓库管理' },
  { key: '/analysis', icon: <ExperimentOutlined />, label: '分析任务' },
  { key: '/change-summary', icon: <DiffOutlined />, label: '变更总结' },
  { key: '/review', icon: <CommentOutlined />, label: 'Code Review' },
  { key: '/review/history', icon: <HistoryOutlined />, label: '审查历史' },
  {
    key: '/query',
    icon: <SearchOutlined />,
    label: '代码查询',
    children: [
      { key: '/query/callgraph', icon: <BranchesOutlined />, label: '调用图查询' },
      { key: '/query/class', icon: <CodeOutlined />, label: '类详情' },
      { key: '/query/endpoints', icon: <ApiOutlined />, label: '入口列表' },
    ],
  },
  { key: '/admin', icon: <SettingOutlined />, label: 'Agent 配置' },
];

const breadcrumbMap: Record<string, string> = {
  '/': '看板',
  '/repos': '仓库管理',
  '/analysis': '分析任务',
  '/change-summary': '变更总结',
  '/review': 'Code Review',
  '/review/history': '审查历史',
  '/query': '代码查询',
  '/query/callgraph': '调用图查询',
  '/query/class': '类详情',
  '/query/endpoints': '入口列表',
  '/admin': 'Agent 配置',
};

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { token } = theme.useToken();

  // Match selected key and open sub-menus
  const selectedKey = useMemo(() => {
    const path = location.pathname;
    // Try exact match first, then prefix match (longest first)
    const allKeys = ['/repos', '/analysis', '/review/history', '/review', '/change-summary', '/query/callgraph', '/query/class', '/query/endpoints', '/admin', '/'];
    return allKeys.find((k) => path === k || (k !== '/' && path.startsWith(k))) || '/';
  }, [location.pathname]);

  const openKeys = useMemo(() => {
    if (selectedKey.startsWith('/query')) return ['/query'];
    return [];
  }, [selectedKey]);

  // Build breadcrumb items
  const breadcrumbItems = useMemo(() => {
    const path = location.pathname;
    const segments = path.split('/').filter(Boolean);
    const items: { title: React.ReactNode; href?: string }[] = [
      { title: <DashboardOutlined />, href: '/' },
    ];
    if (segments.length === 0) return items;

    let currentPath = '';
    for (const seg of segments) {
      currentPath += `/${seg}`;
      const label = breadcrumbMap[currentPath];
      if (label) items.push({ title: label });
    }
    // Handle /analysis/:taskId
    if (path.startsWith('/analysis/') && segments.length === 2) {
      items.push({ title: `#${segments[1]}` });
    }
    return items;
  }, [location.pathname]);

  return (
    // Provider 包住整个 Layout，向页面 Outlet 提供共享的项目列表与分支/commit 状态；
    // 项目/分支/commit 的选择入口在各页面内部（BranchCommitInlineSelector 等）
    <BranchCommitProvider>
      <AntLayout style={{ minHeight: '100vh' }}>
        <Sider
          collapsible
          collapsed={collapsed}
          onCollapse={setCollapsed}
          width={220}
          trigger={null}
          theme="dark"
          style={{
            overflow: 'auto',
            height: '100vh',
            position: 'fixed',
            left: 0,
            top: 0,
            bottom: 0,
            zIndex: 100,
            boxShadow: '2px 0 8px rgba(0,0,0,0.18)',
          }}
        >
          {/* Logo area */}
          <div
            style={{
              height: 56,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 10,
              borderBottom: '1px solid rgba(255,255,255,0.08)',
              cursor: 'pointer',
              transition: 'all 0.2s',
            }}
            onClick={() => navigate('/')}
          >
            <Avatar
              size={collapsed ? 28 : 34}
              style={{ background: 'linear-gradient(135deg, #1677ff, #69b1ff)', flexShrink: 0, boxShadow: '0 2px 8px rgba(22,119,255,0.35)' }}
              icon={<GithubOutlined />}
            />
            {!collapsed && (
              <Typography.Text strong style={{ color: '#fff', fontSize: 17, whiteSpace: 'nowrap', letterSpacing: 0.5 }}>
                Octopus
              </Typography.Text>
            )}
          </div>

          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            defaultOpenKeys={openKeys}
            items={menuItems}
            onClick={({ key }) => navigate(key)}
            style={{ borderRight: 0, marginTop: 8, background: 'transparent' }}
          />
        </Sider>

        <AntLayout style={{ marginLeft: collapsed ? 80 : 220, transition: 'margin-left 0.2s' }}>
          <Header
            style={{
              background: 'rgba(255,255,255,0.86)',
              backdropFilter: 'blur(8px)',
              WebkitBackdropFilter: 'blur(8px)',
              padding: '0 24px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              height: 56,
              borderBottom: `1px solid ${token.colorBorderSecondary}`,
              position: 'sticky',
              top: 0,
              zIndex: 99,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
              <span
                onClick={() => setCollapsed(!collapsed)}
                style={{ fontSize: 18, cursor: 'pointer', color: token.colorTextTertiary, transition: 'color 0.2s' }}
              >
                {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              </span>
              <Breadcrumb items={breadcrumbItems} style={{ fontSize: 14 }} />
            </div>
          </Header>

          <Content
            style={{
              margin: 16,
              minHeight: 'calc(100vh - 56px - 32px)',
            }}
          >
            <Outlet />
          </Content>
        </AntLayout>
      </AntLayout>
    </BranchCommitProvider>
  );
}
