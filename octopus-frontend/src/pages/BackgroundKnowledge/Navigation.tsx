import { Link, useLocation } from "react-router-dom";
import { backgroundKnowledgePath, backgroundAnalysisPath, backgroundTasksPath } from "../BusinessTesting/navigationModel";
export default function BackgroundNavigation() {
  const { pathname, search } = useLocation();
  return <nav className="bk-navigation" aria-label="背景知识子页面">
    <Link aria-current={pathname === "/background/query" ? "page" : undefined} to={backgroundKnowledgePath(search)}>背景查询</Link>
    <Link aria-current={pathname === "/background/analysis" ? "page" : undefined} to={backgroundAnalysisPath(search)}>背景分析</Link>
    <Link aria-current={pathname === "/background/tasks" ? "page" : undefined} to={backgroundTasksPath(search)}>任务历史</Link>
  </nav>;
}
