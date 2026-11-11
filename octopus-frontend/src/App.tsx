import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import AppLayout from './components/Layout';
import './App.css';
import { antdTheme } from './theme';
import Dashboard from './pages/Dashboard';
import RepoList from './pages/Repos';
import AnalysisList from './pages/Analysis';
import TaskDetail from './pages/Analysis/TaskDetail';
import CallGraph from './pages/Query/CallGraph';
import ClassDetail from './pages/Query/ClassDetail';
import EndpointsPage from './pages/Query/Endpoints';
import ReviewPage from './pages/Review';
import ReviewHistoryPage from './pages/ReviewHistory';
import AdminPage from './pages/Admin';
import ChangeSummaryPage from './pages/ChangeSummary';
import ScenariosPage from './pages/Scenarios';
import ScenarioEdit from './pages/Scenarios/ScenarioEdit';
import BusinessTestingPage from './pages/BusinessTesting';
import RegressionPage from './pages/Regression';
import DslWorkbench from './pages/Dsl';
import ExtensionsPage from './pages/Extensions';
import BackgroundTasksPage from './pages/BackgroundKnowledge/Tasks';
import BackgroundAnalysisPage from './pages/BackgroundKnowledge/Analysis';
import BackgroundKnowledgePage, { LegacyBackgroundRedirect } from './pages/BackgroundKnowledge';

export default function App() {
  return (
    <ConfigProvider locale={zhCN} theme={antdTheme}>
      <AntApp>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/" element={<Dashboard />} />
            <Route path="/repos" element={<RepoList />} />
            <Route path="/analysis" element={<AnalysisList />} />
            <Route path="/analysis/:taskId" element={<TaskDetail />} />
            <Route path="/query/callgraph" element={<CallGraph />} />
            <Route path="/query/class" element={<ClassDetail />} />
            <Route path="/query/endpoints" element={<EndpointsPage />} />
            <Route path="/change-summary" element={<ChangeSummaryPage />} />
            <Route path="/scenarios" element={<ScenariosPage />} />
            <Route path="/background" element={<LegacyBackgroundRedirect />} />
            <Route path="/background/query" element={<BackgroundKnowledgePage />} />
            <Route path="/background/analysis" element={<BackgroundAnalysisPage />} />
            <Route path="/background/tasks" element={<BackgroundTasksPage />} />
            <Route path="/business-testing/repository-analysis" element={<LegacyBackgroundRedirect />} />
            <Route path="/business-testing/regressions" element={<RegressionPage />} />
            <Route path="/business-testing/dsl" element={<DslWorkbench />} />
            <Route path="/business-testing/extensions" element={<ExtensionsPage />} />
            <Route path="/business-testing/:area?" element={<BusinessTestingPage />} />
            <Route path="/scenarios/edit" element={<ScenarioEdit />} />
            <Route path="/scenarios/edit/:id" element={<ScenarioEdit />} />
            <Route path="/review" element={<ReviewPage />} />
            <Route path="/review/history" element={<ReviewHistoryPage />} />
            <Route path="/admin" element={<AdminPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AntApp>
  </ConfigProvider>
  );
}
