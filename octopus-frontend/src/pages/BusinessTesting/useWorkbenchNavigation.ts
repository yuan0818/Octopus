import { useCallback, useLayoutEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { businessTestingPages, businessTestingPath, businessTestingSearch, type BusinessTestingArea } from "./navigationModel";

export default function useWorkbenchNavigation() {
  const { area: routeArea } = useParams();
  const location = useLocation();
  const routerNavigate = useNavigate();
  const pendingSearch = useRef(location.search);
  const [querySearch, setQuerySearch] = useState(location.search);
  useLayoutEffect(() => {
    pendingSearch.current = location.search;
    setQuerySearch(location.search);
  }, [location.key, location.search]);
  const page = businessTestingPages.find(p => p.key === routeArea);
  const updateQuery = useCallback((update: (params: URLSearchParams) => void) => {
    const next = new URLSearchParams(pendingSearch.current);
    update(next);
    const search = businessTestingSearch(next.toString());
    pendingSearch.current = search;
    // Controlled inputs must update immediately, before Router's transition commits.
    setQuerySearch(search);
    routerNavigate({ pathname: location.pathname, search }, { replace: true, preventScrollReset: true });
  }, [location.pathname, routerNavigate]);
  const navigate = useCallback((area: BusinessTestingArea) => {
    routerNavigate(businessTestingPath(area, pendingSearch.current));
  }, [routerNavigate]);
  return { page, area: page?.key || "overview", params: new URLSearchParams(querySearch), updateQuery, navigate,
    fallback: businessTestingPath("overview", location.search) };
}
