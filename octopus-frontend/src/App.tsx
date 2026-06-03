import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { App as AntApp } from 'antd';
import AppLayout from './components/Layout';
import Dashboard from './pages/Dashboard';
import RepoList from './pages/Repos';
import AnalysisList from './pages/Analysis';
import TaskDetail from './pages/Analysis/TaskDetail';
import CallGraph from './pages/Query/CallGraph';
import ClassDetail from './pages/Query/ClassDetail';
import EndpointsPage from './pages/Query/Endpoints';

export default function App() {
  return (
    <AntApp>
      <BrowserRouter>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/" element={<Dashboard />} />
            <Route path="/repos" element={<RepoList />} />
            <Route path="/analysis" element={<AnalysisList />} />
            <Route path="/analysis/:taskId" element={<TaskDetail />} />
            <Route path="/query/callgraph" element={<CallGraph />} />
            <Route path="/query/class" element={<ClassDetail />} />
            <Route path="/query/endpoints" element={<EndpointsPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AntApp>
  );
}
