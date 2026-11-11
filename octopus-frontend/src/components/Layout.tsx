import { useState, useMemo } from 'react';
import { Link, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout as AntLayout, Menu, Typography, Breadcrumb, Avatar, theme, Grid, Button } from 'antd';
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
  SafetyCertificateOutlined,
  FileSearchOutlined,
  ThunderboltOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  FileTextOutlined,
  AppstoreAddOutlined,
} from '@ant-design/icons';
import { BranchCommitProvider } from './BranchCommitContext';
import { businessTestingPages, businessTestingPath, backgroundKnowledgePath, backgroundAnalysisPath, backgroundTasksPath } from '../pages/BusinessTesting/navigationModel';
import { palette } from '../theme';

const { Sider, Content, Header } = AntLayout;

type MenuItem = Required<MenuProps>['items'][number];

const businessPageIcons = { overview: <ThunderboltOutlined />, operations: <ApiOutlined />, context: <SafetyCertificateOutlined />, "repository-analysis": <CodeOutlined />,
  flows: <BranchesOutlined />, tasks: <HistoryOutlined />, impact: <CodeOutlined />, scenarios: <ExperimentOutlined />, runs: <FileSearchOutlined /> };

/** 业务测试子页面：「回归批次」紧跟在「变更回归」之后（先分析影响，再批量执行）。 */
function businessTestingChildren(linkFor: (key: string, title: string) => React.ReactNode): MenuItem[] {
  const items: MenuItem[] = [];
  for (const page of businessTestingPages) {
    items.push({ key: businessTestingPath(page.key), icon: businessPageIcons[page.key], label: linkFor(businessTestingPath(page.key), page.title) });
    if (page.key === 'impact') items.push({ key: '/business-testing/regressions', icon: <ThunderboltOutlined />, label: linkFor('/business-testing/regressions', '回归批次') });
    // DSL 是第三种编写方式，放在「业务流程」之后；扩展与函数目录服务于三种方式。
    if (page.key === 'flows') items.push({ key: '/business-testing/dsl', icon: <FileTextOutlined />, label: linkFor('/business-testing/dsl', 'DSL 工作台') });
  }
  items.push({ key: '/business-testing/extensions', icon: <AppstoreAddOutlined />, label: linkFor('/business-testing/extensions', 'JMeter 扩展与函数') });
  return items;
}

/**
 * 导航按工作阶段分组：代码分析 → 变更与审查 → 业务测试 → 系统。
 * 旧的「场景测试」原型（/scenarios）已不在导航中，路由保留并提示迁移到「业务 API 测试」。
 */
function buildMenu(linkFor: (key: string, title: string) => React.ReactNode, backgroundLinks: MenuItem[]): MenuItem[] {
  return [
    { key: '/', icon: <DashboardOutlined />, label: '看板' },
    { type: 'group', key: 'g-analysis', label: '代码分析', children: [
      { key: '/repos', icon: <DatabaseOutlined />, label: '仓库管理' },
      { key: '/analysis', icon: <ExperimentOutlined />, label: '分析任务' },
      { key: '/query', icon: <SearchOutlined />, label: '代码查询', children: [
        { key: '/query/callgraph', icon: <BranchesOutlined />, label: '调用图查询' },
        { key: '/query/class', icon: <CodeOutlined />, label: '类详情' },
        { key: '/query/endpoints', icon: <ApiOutlined />, label: '入口列表' },
      ] },
      { key: '/background', icon: <CodeOutlined />, label: '背景知识', children: backgroundLinks },
    ] },
    { type: 'group', key: 'g-review', label: '变更与审查', children: [
      { key: '/change-summary', icon: <DiffOutlined />, label: '变更总结' },
      { key: '/review', icon: <CommentOutlined />, label: 'Code Review' },
      { key: '/review/history', icon: <HistoryOutlined />, label: '审查历史' },
    ] },
    { type: 'group', key: 'g-testing', label: '业务测试', children: [
      { key: '/business-testing', icon: <BranchesOutlined />, label: '业务 API 测试', children: businessTestingChildren(linkFor) },
    ] },
    { type: 'group', key: 'g-system', label: '系统', children: [
      { key: '/admin', icon: <SettingOutlined />, label: 'Agent 配置' },
    ] },
  ];
}

const breadcrumbMap: Record<string, string> = {
  '/': '看板',
  '/repos': '仓库管理',
  '/analysis': '分析任务',
  '/background': '背景知识',
  '/background/query': '背景查询',
  '/background/analysis': '背景分析',
  '/background/tasks': '任务历史',
  '/scenarios': '场景测试',
  '/business-testing': '业务 API 测试',
  ...Object.fromEntries(businessTestingPages.map(page => [businessTestingPath(page.key), page.title])),
  '/business-testing/regressions': '回归批次',
  '/business-testing/dsl': 'DSL 工作台',
  '/business-testing/extensions': 'JMeter 扩展与函数',
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
  const screens = Grid.useBreakpoint();
  const mobile = screens.sm === false;
  const navigate = useNavigate();
  const location = useLocation();
  const { token } = theme.useToken();

  // Match selected key and open sub-menus
  const selectedKey = useMemo(() => {
    const path = location.pathname;
    // Try exact match first, then prefix match (longest first)
    const allKeys = ['/business-testing/regressions', '/business-testing/dsl', '/business-testing/extensions', ...businessTestingPages.map(page => businessTestingPath(page.key)), '/background/query', '/background/analysis', '/background/tasks', '/background', '/repos', '/analysis', '/review/history', '/review', '/business-testing', '/change-summary', '/query/callgraph', '/query/class', '/query/endpoints', '/admin', '/'];
    return allKeys.find((k) => path === k || (k !== '/' && path.startsWith(k))) || '/';
  }, [location.pathname]);

  const openKeys = useMemo(() => {
    if (selectedKey.startsWith('/background')) return ['/background'];
    if (selectedKey.startsWith('/query')) return ['/query'];
    if (selectedKey.startsWith('/business-testing')) return ['/business-testing'];
    return [];
  }, [selectedKey]);

  const [expandedMenus, setExpandedMenus] = useState(openKeys);
  // 路由切换到新的分组时自动展开它（渲染期间按 openKeys 变化调整，而不是在 effect 中二次渲染）
  const [syncedOpenKeys, setSyncedOpenKeys] = useState(openKeys.join('|'));
  if (syncedOpenKeys !== openKeys.join('|')) {
    setSyncedOpenKeys(openKeys.join('|'));
    setExpandedMenus(current => [...new Set([...current, ...openKeys])]);
  }
  const contextualMenuItems = useMemo<MenuItem[]>(() => {
    const keepSearch = location.pathname.startsWith('/business-testing') ? location.search : '';
    const linkFor = (key: string, title: string) => {
      const page = businessTestingPages.find(p => businessTestingPath(p.key) === key);
      const to = page ? businessTestingPath(page.key, keepSearch) : key;
      return <Link aria-current={location.pathname === key ? 'page' : undefined} to={to}>{title}</Link>;
    };
    const backgroundLinks: MenuItem[] = [
      { key: '/background/query', icon: <SearchOutlined />, label: <Link to={backgroundKnowledgePath(location.search)}>背景查询</Link> },
      { key: '/background/analysis', icon: <ExperimentOutlined />, label: <Link to={backgroundAnalysisPath(location.search)}>背景分析</Link> },
      { key: '/background/tasks', icon: <HistoryOutlined />, label: <Link to={backgroundTasksPath(location.search)}>任务历史</Link> },
    ];
    return buildMenu(linkFor, backgroundLinks);
  }, [location.pathname, location.search]);

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
      if (label) items.push({ title: currentPath === '/business-testing'
        ? <Link to={businessTestingPath('overview', location.search)}>{label}</Link> : label });
    }
    // Handle /analysis/:taskId
    if (path.startsWith('/analysis/') && segments.length === 2) {
      items.push({ title: `#${segments[1]}` });
    }
    return items;
  }, [location.pathname, location.search]);

  return (
    // Provider 包住整个 Layout，向页面 Outlet 提供共享的项目列表与分支/commit 状态；
    // 项目/分支/commit 的选择入口在各页面内部（BranchCommitInlineSelector 等）
    <BranchCommitProvider>
      <AntLayout style={{ minHeight: '100vh' }}>
        {mobile && !collapsed && <button type="button" aria-label="关闭导航" onClick={() => setCollapsed(true)}
          style={{ position: 'fixed', inset: 0, zIndex: 100, border: 0, background: 'rgba(0,0,0,.25)' }} />}
        <Sider
          collapsible
          breakpoint="lg"
          onBreakpoint={setCollapsed}
          collapsed={collapsed}
          collapsedWidth={mobile ? 0 : 80}
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
              style={{ background: `linear-gradient(135deg, ${palette.primary}, ${palette.primaryLight})`, flexShrink: 0, boxShadow: '0 2px 8px rgba(22,119,255,0.35)' }}
              icon={<GithubOutlined />}
            />
            {!collapsed && (
              <Typography.Text strong style={{ color: palette.textOnDark, fontSize: 17, whiteSpace: 'nowrap', letterSpacing: 0.5 }}>
                Octopus
              </Typography.Text>
            )}
          </div>

          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            openKeys={collapsed ? undefined : expandedMenus}
            onOpenChange={setExpandedMenus}
            items={contextualMenuItems}
            onClick={({ key, domEvent }) => {
              if (mobile) setCollapsed(true);
              if (domEvent.target instanceof Element && domEvent.target.closest('a')) return;
              const page = businessTestingPages.find(page => businessTestingPath(page.key) === key);
              if (key === '/background/analysis') { navigate(backgroundAnalysisPath(location.search)); return; }
              if (key === '/background/tasks') { navigate(backgroundTasksPath(location.search)); return; }
              if (key === '/background' || key === '/background/query') { navigate(backgroundKnowledgePath(location.search)); return; }
              navigate(page ? businessTestingPath(page.key, location.pathname.startsWith('/business-testing') ? location.search : '') : key);
            }}
            style={{ borderRight: 0, marginTop: 8, background: 'transparent' }}
          />
        </Sider>

        <AntLayout style={{ marginLeft: mobile ? 0 : collapsed ? 80 : 220, transition: 'margin-left 0.2s' }}>
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
              <Button type="text" aria-label={collapsed ? "展开导航" : "收起导航"} onClick={() => setCollapsed(!collapsed)}
                icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} />
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
