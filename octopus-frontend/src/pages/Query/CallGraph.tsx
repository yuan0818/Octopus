import { useState, useMemo, useCallback, useEffect } from 'react';
import { Input, Button, Table, Tabs, Space, Form, message, Modal, Tag, Tooltip, InputNumber } from 'antd';
import { SearchOutlined, ApartmentOutlined, SortAscendingOutlined, SortDescendingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  MarkerType,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { queryApi } from '../../api/query';
import type { CallEdge, CallChainResult, CallChainItem, CallTraceDTO, MethodNodeInfo, ClassSummary, EndpointInfo, MethodEdgeDTO, ProjectInfo } from '../../types';

const NODE_W = 280;
const NODE_H = 68;

function nodeKey(m: MethodNodeInfo, callType?: string): string {
  return (m.signature ?? `${m.qualifiedClassName}.${m.methodName}`) + '|' + (callType ?? 'ROOT');
}

// ── Extract all root-to-leaf paths ──────────────────────────────────────────
type PathStep = { method: MethodNodeInfo; callType: string };

function extractPaths(root: MethodNodeInfo, chainList: CallChainItem[]): PathStep[][] {
  if (!chainList.length) return [];
  const allPaths: PathStep[][] = [];
  const rootStep: PathStep = { method: root, callType: 'ROOT' };

  function dfs(items: CallChainItem[], current: PathStep[]) {
    // Get the parent class (last item in current path, if any)
    const parentClass = current.length > 0 ? current[current.length-1].method.qualifiedClassName : null;
    for (const item of items) {
      // Skip internal delegation within the same class
      if (parentClass !== null && item.method.qualifiedClassName === parentClass && item.method.methodName === current[current.length-1].method.methodName) continue;
      current.push({ method: item.method, callType: item.callType });
      if (!item.children?.length) {
        allPaths.push([...current]);
      } else {
        var beforeLen = allPaths.length;
        dfs(item.children, current);
        if (allPaths.length === beforeLen) {
          allPaths.push([...current]);
        }
      }
      current.pop();
    }
  }

  dfs(chainList, [rootStep]);
  return allPaths;
}

function chainToFlow(
  root: MethodNodeInfo,
  chainList: CallChainItem[],
  onClassClick: (name: string) => void,
  onImplClick: (iface: string, method: string) => void,
): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = [];
  const edges: Edge[] = [];
  const visited = new Set<string>();

  function addNode(m: MethodNodeInfo, callType: string, parentId: string | null) {
    const id = nodeKey(m, callType);
    if (!visited.has(id)) {
      visited.add(id);
      const isInterface = callType === 'INTERFACE';
      const shortClass = m.qualifiedClassName.split('.').pop()!;
      nodes.push({
        id,
        position: { x: 0, y: 0 },
        data: {
          label: (
            <div style={{ padding: '8px 12px', background: '#fff', border: '1px solid #d9d9d9', borderRadius: 6, fontSize: 12, lineHeight: 1.5, minWidth: 240 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
                <span style={{ color: '#1677ff', cursor: 'pointer', fontWeight: 500 }}
                  onClick={(e) => { e.stopPropagation(); onClassClick(m.qualifiedClassName); }}>
                  {shortClass}
                </span>
                <span style={{ color: '#333', fontWeight: 600 }}>#{m.methodName}</span>
                <Tag color={callType === 'ROOT' ? 'green' : callType === 'INTERFACE' ? 'purple' : 'blue'}
                  style={{ margin: 0, fontSize: 10, lineHeight: '16px' }}>{callType}</Tag>
                {isInterface && (
                  <Tooltip title="查看实现类">
                    <ApartmentOutlined style={{ color: '#52c41a', fontSize: 13, cursor: 'pointer' }}
                      onClick={(e) => { e.stopPropagation(); onImplClick(m.qualifiedClassName, m.methodName); }} />
                  </Tooltip>
                )}
              </div>
              <div style={{ color: '#999', fontSize: 11 }}>
                {m.qualifiedClassName}
                {m.returnType && <span style={{ marginLeft: 6 }}>→ {m.returnType}</span>}
              </div>
            </div>
          ),
        },
        type: 'default',
      });
    }

    // Always add edge from parent – even if node already existed (cross-edges in DAG)
    if (parentId) {
      const edgeId = `${parentId}->${id}`;
      if (!edges.some(e => e.id === edgeId)) {
        edges.push({
          id: edgeId,
          source: parentId,
          target: id,
          type: 'smoothstep',
          animated: false,
          markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
          style: { stroke: '#91caff', strokeWidth: 1.5 },
        });
      }
    }
    return id;
  }

  function traverse(item: CallChainItem, parentId: string) {
    const id = addNode(item.method, item.callType, parentId);
    if (item.children?.length) {
      for (const child of item.children) {
        // Skip calls to other methods in the same class (internal delegation)
        if (child.method.qualifiedClassName === item.method.qualifiedClassName && child.method.methodName === item.method.methodName) continue;
        traverse(child, id);
      }
    }
  }

  const rootId = addNode(root, 'ROOT', null);
  for (const c of chainList) traverse(c, rootId);
return { nodes, edges };
}

function subtreeSize(item: CallChainItem): number {
  return 1 + item.children.reduce((s, c) => s + subtreeSize(c), 0);
}

function radialLayout(nodes: Node[], root: MethodNodeInfo, chainList: CallChainItem[]): Node[] {
  const RADIUS_STEP = 240;
  const positions = new Map<string, { x: number; y: number }>();
  positions.set(nodeKey(root, 'ROOT'), { x: 0, y: 0 });

  function place(items: CallChainItem[], level: number, startAngle: number, endAngle: number) {
    const total = items.reduce((s, c) => s + subtreeSize(c), 0);
    const r = level * RADIUS_STEP;
    let angle = startAngle;
    for (const item of items) {
      const size = subtreeSize(item);
      const sector = total > 0 ? (size / total) * (endAngle - startAngle) : (endAngle - startAngle) / items.length;
      const mid = angle + sector / 2;
      positions.set(nodeKey(item.method, item.callType), { x: Math.cos(mid) * r, y: Math.sin(mid) * r });
      if (item.children.length) place(item.children, level + 1, angle, angle + sector);
      angle += sector;
    }
  }

  place(chainList, 1, -Math.PI, Math.PI);
  return nodes.map((n) => {
    const pos = positions.get(n.id) ?? { x: 0, y: 0 };
    return { ...n, position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 } };
  });
}

export default function CallGraph() {
  const navigate = useNavigate();
  const [className, setClassName] = useState('');
  const [methodName, setMethodName] = useState('');
  const [callers, setCallers] = useState<CallEdge[]>([]);
  const [callees, setCallees] = useState<CallEdge[]>([]);
  const [chain, setChain] = useState<CallChainResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<string>('endpoints');
  const [selectedPathIdx, setSelectedPathIdx] = useState<number | null>(null);
  const [minDepth, setMinDepth] = useState<number>(1);
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');
  const [traceDepth, setTraceDepth] = useState<number>(5);
  const [trace, setTrace] = useState<CallTraceDTO | null>(null);

  // ── Projects / Endpoints ──────────────────────────────────────────────
  const [projects, setProjects] = useState<ProjectInfo[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<number | undefined>(undefined);
  const [endpoints, setEndpoints] = useState<EndpointInfo[]>([]);
  const [endpointsLoading, setEndpointsLoading] = useState(false);

  // Load projects on mount
  useEffect(() => {
    queryApi.projects().then(r => setProjects(r.data)).catch(() => {});
  }, []);

  // Load endpoints when project selection changes
  const loadEndpoints = useCallback(async (projectId?: number) => {
    setEndpointsLoading(true);
    try {
      const r = await queryApi.endpoints(projectId);
      setEndpoints(r.data);
    } catch { setEndpoints([]); }
    setEndpointsLoading(false);
  }, []);

  useEffect(() => { loadEndpoints(selectedProjectId); }, [selectedProjectId, loadEndpoints]);

  const [implModalOpen, setImplModalOpen] = useState(false);
  const [implModalTitle, setImplModalTitle] = useState('');
  const [implList, setImplList] = useState<ClassSummary[]>([]);
  const [implLoading, setImplLoading] = useState(false);

  const goToClass = useCallback((name: string) => {
    navigate(`/query/class?name=${encodeURIComponent(name)}`);
  }, [navigate]);

  const showImplementors = useCallback(async (interfaceName: string, targetMethod: string) => {
    setImplModalTitle(`${interfaceName.split('.').pop()}#${targetMethod} 的实现类`);
    setImplList([]);
    setImplModalOpen(true);
    setImplLoading(true);
    try {
      const r = await queryApi.implementors(interfaceName);
      setImplList(r.data);
    } catch { message.error('查询实现类失败'); }
    setImplLoading(false);
  }, []);

  const { initialNodes, initialEdges } = useMemo(() => {
    if (!chain) return { initialNodes: [], initialEdges: [] };
    const raw = chainToFlow(chain.root, chain.chain, goToClass, showImplementors);
    return {
      initialNodes: radialLayout(raw.nodes, chain.root, chain.chain),
      initialEdges: raw.edges,
    };
  }, [chain, goToClass, showImplementors]);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  useEffect(() => {
    setNodes(initialNodes);
    setEdges(initialEdges);
  }, [initialNodes, initialEdges, setNodes, setEdges]);

  // ── Path extraction ────────────────────────────────────────────────────────
  const paths = useMemo(() =>
    chain ? extractPaths(chain.root, chain.chain) : [],
    [chain]);

  // filtered + sorted view (stores original index alongside each path)
  const filteredPaths = useMemo(() => {
    const indexed = paths.map((p, i) => ({ path: p, origIdx: i }));
    const filtered = indexed.filter(({ path }) => path.length >= minDepth);
    filtered.sort((a, b) =>
      sortOrder === 'desc' ? b.path.length - a.path.length : a.path.length - b.path.length
    );
    return filtered;
  }, [paths, minDepth, sortOrder]);

  const pathNodeIdSets = useMemo(() =>
    paths.map(path => new Set(path.map(s => nodeKey(s.method, s.callType)))),
    [paths]);

  const pathEdgeIdSets = useMemo(() =>
    paths.map(path => {
      const ids = new Set<string>();
      for (let i = 0; i < path.length - 1; i++) {
        ids.add(`${nodeKey(path[i].method, path[i].callType)}->${nodeKey(path[i + 1].method, path[i + 1].callType)}`);
      }
      return ids;
    }),
    [paths]);

  // ── Highlighted display nodes / edges ────────────────────────────────────
  const displayNodes = useMemo(() => {
    if (selectedPathIdx === null) return nodes;
    const ids = pathNodeIdSets[selectedPathIdx];
    return nodes.map(n => ({
      ...n,
      style: { ...n.style, opacity: ids?.has(n.id) ? 1 : 0.12 },
    }));
  }, [nodes, selectedPathIdx, pathNodeIdSets]);

  const displayEdges = useMemo(() => {
    if (selectedPathIdx === null) return edges;
    const ids = pathEdgeIdSets[selectedPathIdx];
    return edges.map(e => ({
      ...e,
      animated: ids?.has(e.id) ?? false,
      style: {
        ...e.style,
        opacity: ids?.has(e.id) ? 1 : 0.08,
        stroke: ids?.has(e.id) ? '#1677ff' : '#91caff',
        strokeWidth: ids?.has(e.id) ? 2.5 : 1.5,
      },
    }));
  }, [edges, selectedPathIdx, pathEdgeIdSets]);

  const httpMethodColor: Record<string, string> = {
    GET: 'green', POST: 'blue', PUT: 'orange', DELETE: 'red', PATCH: 'cyan', '*': 'default'
  };

  // Group endpoints by controller
  const endpointsByController = useMemo(() => {
    const map = new Map<string, EndpointInfo[]>();
    for (const ep of endpoints) {
      const key = ep.controllerSimpleName ?? ep.qualifiedClassName ?? '?';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(ep);
    }
    return map;
  }, [endpoints]);

  const launchEndpoint = (ep: EndpointInfo) => {
    setClassName(ep.qualifiedClassName ?? '');
    setMethodName(ep.methodName ?? '');
    setActiveTab('chain');
    // slight delay so state updates settle before search
    setTimeout(() => search('chain', ep.qualifiedClassName, ep.methodName), 50);
  };

  const search = async (type: 'callers' | 'callees' | 'chain', cn?: string, mn?: string) => {
    const resolvedClass = cn ?? className;
    const resolvedMethod = mn ?? methodName;
    if (!resolvedClass || !resolvedMethod) { message.warning('请输入类名和方法名'); return; }
    setLoading(true);
    try {
      if (type === 'callers') { const r = await queryApi.callers(resolvedClass, resolvedMethod); setCallers(r.data); }
      else if (type === 'callees') { const r = await queryApi.callees(resolvedClass, resolvedMethod); setCallees(r.data); }
      else {
        const r = await queryApi.callChain(resolvedClass, resolvedMethod);
        setSelectedPathIdx(null);
        setChain(r.data);
      }
      setActiveTab(type);
    } catch { message.error('查询失败'); }
    setLoading(false);
  };

  const searchTrace = async () => {
    if (!className || !methodName) { message.warning('请输入类名和方法名'); return; }
    setLoading(true);
    try {
      const r = await queryApi.traceMethod(className, methodName, traceDepth);
      setTrace(r.data);
      setActiveTab('trace');
    } catch { message.error('查询调用轨迹失败'); }
    setLoading(false);
  };

  const edgeColumns = [
    {
      title: '类名', dataIndex: 'qualifiedClassName', ellipsis: true, width: 300,
      render: (name: string) => (
        <span style={{ color: '#1677ff', cursor: 'pointer', textDecoration: 'underline' }}
          onClick={() => goToClass(name)}>{name}</span>
      ),
    },
    { title: '方法', dataIndex: 'methodName', width: 160 },
    {
      title: '调用类型', dataIndex: 'callType', width: 140,
      render: (t: string) => (
        t === 'SPRING_DI'
          ? <Tag color="volcano" title="Spring 依赖注入关系（类级别），非方法调用">SPRING_DI 🔗</Tag>
          : <Tag color="blue">{t}</Tag>
      ),
    },
    { title: '行号', dataIndex: 'lineNumber', width: 70 },
    { title: '签名', dataIndex: 'signature', ellipsis: true },
  ];

  const implColumns = [
    {
      title: '实现类', dataIndex: 'qualifiedName', ellipsis: true,
      render: (name: string, row: ClassSummary) => (
        <span style={{ color: '#1677ff', cursor: 'pointer', textDecoration: 'underline' }}
          onClick={() => { setImplModalOpen(false); goToClass(name); }}>{row.simpleName}</span>
      ),
    },
    { title: '全限定名', dataIndex: 'qualifiedName', ellipsis: true },
    { title: '类型', dataIndex: 'classType', width: 100, render: (t: string) => <Tag>{t}</Tag> },
    { title: '修饰符', dataIndex: 'modifiers', width: 120 },
  ];

  return (
    <div>
      <Form layout="inline" style={{ marginBottom: 16 }}>
        <Form.Item label="全限定类名">
          <Input value={className} onChange={(e) => setClassName(e.target.value)}
            placeholder="com.example.OrderService" style={{ width: 300 }} />
        </Form.Item>
        <Form.Item label="方法名">
          <Input value={methodName} onChange={(e) => setMethodName(e.target.value)}
            placeholder="createOrder" style={{ width: 160 }}
            onPressEnter={() => search('chain')} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" icon={<SearchOutlined />} loading={loading}
              onClick={() => search('chain')}>调用链</Button>
            <Button loading={loading} onClick={() => search('callers')}>调用者</Button>
            <Button loading={loading} onClick={() => search('callees')}>被调用者</Button>
            <Button loading={loading} icon={<ApartmentOutlined />}
              onClick={searchTrace}>调用轨迹</Button>
          </Space>
        </Form.Item>
        <Form.Item label="深度">
          <InputNumber min={1} max={20} value={traceDepth} onChange={v => setTraceDepth(v ?? 5)} style={{ width: 70 }} />
        </Form.Item>
      </Form>

      <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
        {
          key: 'endpoints', label: `API 端点 (${endpoints.length})`,
          children: (
            <div>
              <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
                <span style={{ fontSize: 13, color: '#666' }}>服务/模块：</span>
                <select
                  value={selectedProjectId ?? ''}
                  onChange={e => setSelectedProjectId(e.target.value ? Number(e.target.value) : undefined)}
                  style={{ padding: '4px 8px', borderRadius: 6, border: '1px solid #d9d9d9', fontSize: 13, minWidth: 200 }}
                >
                  <option value="">全部模块</option>
                  {projects.filter(p => p.isApiService).map(p => (
                    <option key={p.id} value={p.id}>{p.projectName}</option>
                  ))}
                </select>
              </div>
              <div style={{ maxHeight: 'calc(100vh - 330px)', overflowY: 'auto' }}>
              {endpointsLoading && <div style={{ color: '#999', padding: 16 }}>加载中...</div>}
              {!endpointsLoading && endpoints.length === 0 && (
                <div style={{ color: '#aaa', padding: 24, textAlign: 'center' }}>请选择服务/模块查看 API 端点</div>
              )}
              {[...endpointsByController.entries()].map(([ctrl, eps]) => (
                <div key={ctrl} style={{ marginBottom: 16 }}>
                  <div style={{ fontWeight: 600, fontSize: 13, color: '#333', marginBottom: 6, padding: '4px 0', borderBottom: '1px solid #f0f0f0' }}>
                    {ctrl}
                  </div>
                  {eps.map((ep, i) => (
                    <div key={i}
                      onClick={() => launchEndpoint(ep)}
                      style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 8px', cursor: 'pointer', borderRadius: 6, marginBottom: 2, transition: 'background 0.15s' }}
                      onMouseEnter={e => (e.currentTarget.style.background = '#f5f5f5')}
                      onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
                    >
                      <Tag color={httpMethodColor[ep.httpMethod] ?? 'default'} style={{ minWidth: 52, textAlign: 'center', margin: 0 }}>
                        {ep.httpMethod}
                      </Tag>
                      <span style={{ color: '#1677ff', fontFamily: 'monospace', fontSize: 13 }}>{ep.path || '/'}</span>
                      <span style={{ color: '#888', fontSize: 12 }}>→ {ep.methodName}</span>
                    </div>
                  ))}
                </div>
              ))}
            </div>
            </div>
          ),
        },
        {
          key: 'chain', label: '调用链',
          children: chain ? (
            <div style={{ display: 'flex', height: 'calc(100vh - 300px)', minHeight: 500, gap: 8 }}>
              {/* ── Left: path list ── */}
              <div style={{ width: 280, flexShrink: 0, border: '1px solid #f0f0f0', borderRadius: 8, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
                {/* Header */}
                <div style={{ padding: '8px 10px', borderBottom: '1px solid #f0f0f0', flexShrink: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                    <span style={{ fontWeight: 600, fontSize: 13 }}>
                      路径 ({filteredPaths.length}/{paths.length})
                    </span>
                    {selectedPathIdx !== null && (
                      <Button size="small" type="link" style={{ padding: 0 }}
                        onClick={() => setSelectedPathIdx(null)}>显示全部</Button>
                    )}
                  </div>
                  {/* Filter controls */}
                  <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <span style={{ fontSize: 11, color: '#888', whiteSpace: 'nowrap' }}>最少步数</span>
                    <InputNumber
                      size="small" min={1} max={50} value={minDepth}
                      onChange={(v) => { setMinDepth(v ?? 1); setSelectedPathIdx(null); }}
                      style={{ width: 60 }}
                    />
                    <Button
                      size="small"
                      icon={sortOrder === 'desc' ? <SortDescendingOutlined /> : <SortAscendingOutlined />}
                      onClick={() => setSortOrder(s => s === 'desc' ? 'asc' : 'desc')}
                      title={sortOrder === 'desc' ? '当前：长路径优先' : '当前：短路径优先'}
                    >
                      {sortOrder === 'desc' ? '长→短' : '短→长'}
                    </Button>
                  </div>
                </div>
                {/* List */}
                <div style={{ flex: 1, overflowY: 'auto' }}>
                  {filteredPaths.length === 0 && (
                    <div style={{ padding: 16, color: '#aaa', fontSize: 12, textAlign: 'center' }}>
                      无符合条件的路径
                    </div>
                  )}
                  {filteredPaths.map(({ path, origIdx }, viewIdx) => (
                    <div
                      key={origIdx}
                      onClick={() => setSelectedPathIdx(origIdx === selectedPathIdx ? null : origIdx)}
                      style={{
                        padding: '7px 10px',
                        cursor: 'pointer',
                        background: origIdx === selectedPathIdx ? '#e6f4ff' : 'transparent',
                        borderLeft: `3px solid ${origIdx === selectedPathIdx ? '#1677ff' : 'transparent'}`,
                        borderBottom: '1px solid #f5f5f5',
                        transition: 'background 0.15s',
                      }}
                    >
                      <div style={{ color: '#aaa', fontSize: 10, marginBottom: 3, display: 'flex', justifyContent: 'space-between' }}>
                        <span>路径 {viewIdx + 1}</span>
                        <span style={{ fontWeight: 600, color: path.length >= 5 ? '#d4380d' : path.length >= 3 ? '#fa8c16' : '#8c8c8c' }}>
                          {path.length} 步
                        </span>
                      </div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '2px 0', fontSize: 11, lineHeight: 1.5 }}>
                        {path.map((step, si) => (
                          <span key={si} style={{ display: 'flex', alignItems: 'center' }}>
                            <span style={{
                              color: si === 0 ? '#389e0d' : si === path.length - 1 ? '#d4380d' : '#1677ff',
                              fontWeight: si === 0 || si === path.length - 1 ? 600 : 400,
                              wordBreak: 'break-all',
                            }}>
                              {step.method.methodName}
                            </span>
                            {si < path.length - 1 && (
                              <span style={{ color: '#ccc', margin: '0 3px', flexShrink: 0 }}>→</span>
                            )}
                          </span>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* ── Right: graph ── */}
              <div style={{ flex: 1, border: '1px solid #f0f0f0', borderRadius: 8 }}>
                <ReactFlow
                  nodes={displayNodes}
                  edges={displayEdges}
                  onNodesChange={onNodesChange}
                  onEdgesChange={onEdgesChange}
                  fitView
                  fitViewOptions={{ padding: 0.3 }}
                  attributionPosition="bottom-left"
                  minZoom={0.1}
                  maxZoom={2}
                >
                  <Background />
                  <Controls />
                  <MiniMap nodeStrokeWidth={3} pannable zoomable />
                </ReactFlow>
              </div>
            </div>
          ) : <div style={{ color: '#999' }}>点击"调用链"搜索</div>,
        },
        {
          key: 'callers', label: `调用者 (${callers.length})`,
          children: <Table rowKey={(r,i) => (r.signature || r.methodName) + "|" + i} dataSource={callers} columns={edgeColumns} size="small" pagination={false} />,
        },
        {
          key: 'callees', label: `被调用者 (${callees.length})`,
          children: <Table rowKey={(r,i) => (r.signature || r.methodName) + "|" + i} dataSource={callees} columns={edgeColumns} size="small" pagination={false} />,
        },
        {
          key: 'trace', label: `调用轨迹 ${trace ? '(' + (trace.upstream.length + trace.downstream.length) + ')' : ''}`,
          children: trace ? (
            <div>
              <div style={{ marginBottom: 12, color: '#666', fontSize: 13 }}>
                方法 <strong>{trace.anchor.qualifiedClassName}.{trace.anchor.methodName}</strong> 的调用轨迹（深度={traceDepth}）
              </div>
              <div style={{ display: 'flex', gap: 16 }}>
                <div style={{ flex: 1 }}>
                  <h4 style={{ marginBottom: 8, color: '#1677ff' }}>上游调用者 ({trace.upstream.length})</h4>
                  <Table dataSource={trace.upstream} rowKey={(r,i)=>(r.signature||"")+"|"+i} size="small"
                    columns={edgeColumns} pagination={false} scroll={{ x: 'max-content' }} />
                </div>
                <div style={{ flex: 1 }}>
                  <h4 style={{ marginBottom: 8, color: '#52c41a' }}>下游被调用者 ({trace.downstream.length})</h4>
                  <Table dataSource={trace.downstream} rowKey={(r,i)=>(r.signature||"")+"|"+i} size="small"
                    columns={edgeColumns} pagination={false} scroll={{ x: 'max-content' }} />
                </div>
              </div>
            </div>
          ) : (
            <div style={{ color: '#999', padding: 24, textAlign: 'center' }}>点击"调用轨迹"按钮查询</div>
          ),
        },
      ]} />

      <Modal title={implModalTitle} open={implModalOpen} onCancel={() => setImplModalOpen(false)} footer={null} width={700}>
        <Table rowKey="qualifiedName" dataSource={implList} columns={implColumns} size="small"
          pagination={false} loading={implLoading} locale={{ emptyText: '未找到实现类（可能是外部库接口）' }} />
      </Modal>
    </div>
  );
}
