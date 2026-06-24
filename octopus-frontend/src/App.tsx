import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import AppLayout from './components/Layout';
import './App.css';
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

export default function App() {
  return (
    <ConfigProvider locale={zhCN} theme={{ token: { borderRadius: 6, colorPrimary: '#1677ff' } }}>
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
