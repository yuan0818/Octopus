import { useCallback, useMemo, useRef, useState, useEffect } from 'react';
import {
  ReactFlow, Background, BackgroundVariant, Controls, MiniMap,
  addEdge, useNodesState, useEdgesState, Handle, Position, ReactFlowProvider,
  useReactFlow, type Node, type Edge, type Connection, type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  Button, Card, Drawer, Form, Input, Select, Space, Tag, Typography, Empty, Badge,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ApiOutlined, BranchesOutlined,
  SettingOutlined, SaveOutlined,
} from '@ant-design/icons';
import { palette } from '../../theme';

const { Text } = Typography;

/* ═══ 数据类型 ═══ */
export interface ApiCall {
  api: string;
  label?: string;
  request?: {
    method?: string;
    path?: string;
    headers?: Record<string, string>;
    body?: any;
    query?: Record<string, string>;
  };
  pre?: Array<{ type: string; args?: any }>;
  extract?: Array<{ as: string; expr: string }>;
  assert?: Array<{ path: string; op?: string; expected?: string }>;
}

export interface StepData {
  id: string;
  label?: string;
  calls: ApiCall[];
  dependsOn?: string[];
}

/* ═══ 左侧组件面板的节点类型 ═══ */
const NODE_TYPES = [
  { type: 'apiNode', label: 'API 调用', icon: '🔗', color: palette.primary, desc: '单个 HTTP 调用' },
  { type: 'compositeNode', label: '组合步骤', icon: '🔀', color: palette.purple, desc: '多 API 串行（如查→改→确认）' },
] as const;

/* ═══ 自定义节点渲染 ═══ */
function ApiNodeView({ data, selected }: NodeProps) {
  const step = data.step as StepData;
  const call = step.calls[0];
  return (
    <div style={{
      border: selected ? `2px solid ${palette.primary}` : `1px solid ${palette.border}`,
      borderRadius: 8, padding: '8px 12px', minWidth: 170, maxWidth: 240,
      background: palette.bgContainer, boxShadow: selected ? '0 2px 8px rgba(22,119,255,0.15)' : '0 1px 3px rgba(0,0,0,0.06)',
    }}>
      <Handle type="target" position={Position.Top} style={{ background: palette.border, width: 10, height: 10, border: `2px solid ${palette.primary}` }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
        <ApiOutlined style={{ color: palette.primary, fontSize: 14 }} />
        <Text strong style={{ fontSize: 13 }}>{step.label || call?.request?.path || 'API'}</Text>
      </div>
      <div style={{ fontSize: 11, color: palette.textSecondary }}>
        <Tag style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{call?.request?.method || 'GET'}</Tag>
        <Text code style={{ fontSize: 10 }}>{call?.request?.path || ''}</Text>
      </div>
      {(call?.extract || []).length > 0 && (
        <div style={{ marginTop: 2, fontSize: 10, color: palette.purple }}>→ {(call.extract || []).map(e => e.as).join(', ')}</div>
      )}
      <Handle type="source" position={Position.Bottom} style={{ background: palette.success, width: 10, height: 10 }} />
    </div>
  );
}

function CompositeNodeView({ data, selected }: NodeProps) {
  const step = data.step as StepData;
  return (
    <div style={{
      border: selected ? `2px solid ${palette.purple}` : `1px solid ${palette.border}`,
      borderRadius: 8, padding: '8px 12px', minWidth: 170, maxWidth: 240,
      background: palette.purpleBg, boxShadow: selected ? '0 2px 8px rgba(114,46,209,0.15)' : '0 1px 3px rgba(0,0,0,0.06)',
    }}>
      <Handle type="target" position={Position.Top} style={{ background: palette.border, width: 10, height: 10, border: `2px solid ${palette.purple}` }} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
        <BranchesOutlined style={{ color: palette.purple, fontSize: 14 }} />
        <Text strong style={{ fontSize: 13 }}>{step.label || '组合步骤'}</Text>
      </div>
      {step.calls.map((c, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11 }}>
          <Badge count={i + 1} style={{ fontSize: 9, height: 14, minWidth: 14, lineHeight: '14px' }} />
          <Text code style={{ fontSize: 10 }}>{c.request?.method} {c.request?.path}</Text>
        </div>
      ))}
      <Handle type="source" position={Position.Bottom} style={{ background: palette.success, width: 10, height: 10 }} />
    </div>
  );
}

const nodeViews = { apiNode: ApiNodeView, compositeNode: CompositeNodeView };

/* ═══ 左侧组件面板（拖拽源） ═══ */
function ToolboxPanel() {
  return (
    <div style={{ width: 180, borderRight: `1px solid ${palette.borderSubtle}`, padding: 12, background: palette.bgSubtle, overflowY: 'auto' }}>
      <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 12 }}>
        <SettingOutlined /> 组件
      </Text>
      {NODE_TYPES.map(nt => (
        <div
          key={nt.type}
          draggable
          onDragStart={(e) => e.dataTransfer.setData('application/reactflow', nt.type)}
          style={{
            border: `1px solid ${palette.border}`, borderRadius: 8, padding: '8px 10px',
            marginBottom: 8, background: palette.bgContainer, cursor: 'grab',
            transition: 'all 0.2s', userSelect: 'none',
          }}
          onMouseEnter={e => { e.currentTarget.style.borderColor = nt.color; e.currentTarget.style.boxShadow = `0 2px 6px ${nt.color}22`; }}
          onMouseLeave={e => { e.currentTarget.style.borderColor = palette.border; e.currentTarget.style.boxShadow = 'none'; }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 18 }}>{nt.icon}</span>
            <div>
              <Text strong style={{ fontSize: 12 }}>{nt.label}</Text>
              <div><Text type="secondary" style={{ fontSize: 10 }}>{nt.desc}</Text></div>
            </div>
          </div>
        </div>
      ))}
      <Text type="secondary" style={{ fontSize: 10, display: 'block', marginTop: 12, textAlign: 'center' }}>
        拖拽组件到画布
      </Text>
    </div>
  );
}

/* ═══ API 调用列表编辑器 ═══ */
function CallListEditor({ value = [], onChange }: { value?: ApiCall[]; onChange?: (v: ApiCall[]) => void }) {
  const calls = value || [];
  const update = (idx: number, field: string, v: any) => {
    const next = [...calls];
    next[idx] = { ...next[idx], [field]: v };
    onChange?.(next);
  };
  return (
    <div>
      {calls.map((c: ApiCall, i: number) => (
        <Card key={i} size="small" style={{ marginBottom: 8 }} title={
          <Space>
            <Badge count={i + 1} style={{ backgroundColor: palette.primary }} />
            <Text code style={{ fontSize: 11 }}>{c.request?.method} {c.request?.path}</Text>
          </Space>
        } extra={
          <Button size="small" type="text" danger icon={<DeleteOutlined />}
            onClick={() => onChange?.(calls.filter((_, j) => j !== i))} />
        }>
          <Space direction="vertical" style={{ width: '100%' }} size={6}>
            <Space.Compact style={{ width: '100%' }}>
              <Select style={{ width: 90 }} value={c.request?.method || 'GET'}
                onChange={v => update(i, 'request', { ...c.request, method: v })}
                options={['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map(m => ({ value: m, label: m }))} />
              <Input style={{ flex: 1 }} placeholder="/api/orders" value={c.request?.path || ''}
                onChange={e => update(i, 'request', { ...c.request, path: e.target.value })} />
            </Space.Compact>
            <Input placeholder="Body JSON（支持 ${var} 引用）" value={c.request?.body ? JSON.stringify(c.request.body) : ''}
              onChange={e => { try { update(i, 'request', { ...c.request, body: e.target.value ? JSON.parse(e.target.value) : undefined }); } catch { /* incomplete */ } }} />
            {(c.extract || []).map((ex, j) => (
              <Space.Compact key={j} style={{ width: '100%' }}>
                <Input style={{ width: 100 }} placeholder="变量名" value={ex.as}
                  onChange={e => { const next = [...(c.extract || [])]; next[j] = { ...ex, as: e.target.value }; update(i, 'extract', next); }} />
                <Input style={{ flex: 1 }} placeholder="$.data.orderId" value={ex.expr}
                  onChange={e => { const next = [...(c.extract || [])]; next[j] = { ...ex, expr: e.target.value }; update(i, 'extract', next); }} />
              </Space.Compact>
            ))}
            <Button size="small" type="dashed" block icon={<PlusOutlined />} style={{ fontSize: 11 }}
              onClick={() => update(i, 'extract', [...(c.extract || []), { as: '', expr: '' }])}>提取</Button>
            {(c.assert || []).map((a, j) => (
              <Space.Compact key={`a${j}`} style={{ width: '100%' }}>
                <Input style={{ flex: 1 }} placeholder="$.code" value={a.path}
                  onChange={e => { const next = [...(c.assert || [])]; next[j] = { ...a, path: e.target.value }; update(i, 'assert', next); }} />
                <Select style={{ width: 80 }} value={a.op || 'eq'}
                  onChange={v => { const next = [...(c.assert || [])]; next[j] = { ...a, op: v }; update(i, 'assert', next); }}
                  options={[{ value: 'eq', label: '=' }, { value: 'notNull', label: '≠null' }, { value: 'contains', label: '∋' }]} />
                <Input style={{ width: 100 }} placeholder="期望值" value={a.expected || ''}
                  onChange={e => { const next = [...(c.assert || [])]; next[j] = { ...a, expected: e.target.value }; update(i, 'assert', next); }} />
              </Space.Compact>
            ))}
            <Button size="small" type="dashed" block icon={<PlusOutlined />} style={{ fontSize: 11 }}
              onClick={() => update(i, 'assert', [...(c.assert || []), { path: '', op: 'eq', expected: '' }])}>断言</Button>
          </Space>
        </Card>
      ))}
      <Button size="small" type="dashed" block icon={<PlusOutlined />}
        onClick={() => onChange?.([...calls, { api: '', request: { method: 'GET', path: '' } }])}>添加 API 调用</Button>
    </div>
  );
}

/* ═══ 右侧属性面板（Drawer） ═══ */
function StepPropertyDrawer({ step, open, onClose, onSave, onDelete }: {
  step: StepData | null; open: boolean; onClose: () => void;
  onSave: (s: StepData) => void; onDelete: (id: string) => void;
}) {
  const [form] = Form.useForm();
  useEffect(() => {
    if (step && open) form.setFieldsValue({ label: step.label, calls: step.calls });
  }, [step, open, form]);
  if (!step) return null;
  const handleSave = async () => {
    const values = await form.validateFields();
    onSave({ ...step, label: values.label, calls: values.calls });
  };
  return (
    <Drawer
      title={
        <Space>
          <Tag color={step.calls.length > 1 ? 'purple' : 'blue'}>
            {step.calls.length > 1 ? '组合步骤' : 'API 调用'}
          </Tag>
          <Text strong>{step.label || step.id}</Text>
        </Space>
      }
      open={open} onClose={onClose} width={420}
      extra={
        <Space>
          <Button danger size="small" icon={<DeleteOutlined />} onClick={() => onDelete(step.id)}>删除</Button>
          <Button type="primary" size="small" icon={<SaveOutlined />} onClick={handleSave}>保存</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" size="small">
        <Form.Item label="步骤名称" name="label">
          <Input placeholder="如：登录 / 创建订单" />
        </Form.Item>
        <Form.Item label={<Text strong>API 调用列表</Text>} name="calls" rules={[{ required: true, message: '至少一个 API' }]}>
          <CallListEditor />
        </Form.Item>
      </Form>
    </Drawer>
  );
}

/* ═══ 主编辑器（Dolphin 风格三栏） ═══ */
export interface DagEditorProps {
  initialDag?: StepData[];
  onChange: (dag: StepData[]) => void;
  height?: number | string;
}

function DagEditorInner({ initialDag = [], onChange, height = 480 }: DagEditorProps) {
  const [steps, setSteps] = useState<StepData[]>(initialDag);
  const [selectedStep, setSelectedStep] = useState<StepData | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const { screenToFlowPosition } = useReactFlow();

  const { initialNodes, initialEdges } = useMemo(() => {
    const depthMap = new Map<string, number>();
    const computeDepth = (id: string, visited: Set<string>): number => {
      if (depthMap.has(id)) return depthMap.get(id)!;
      if (visited.has(id)) return 0;
      visited.add(id);
      const step = steps.find(s => s.id === id);
      if (!step || !step.dependsOn?.length) { depthMap.set(id, 0); return 0; }
      const max = Math.max(...step.dependsOn.map(d => computeDepth(d, visited)));
      depthMap.set(id, max + 1);
      return max + 1;
    };
    steps.forEach(s => computeDepth(s.id, new Set()));

    const nodes: Node[] = steps.map(s => {
      const depth = depthMap.get(s.id) || 0;
      const sameDepth = steps.filter(x => (depthMap.get(x.id) || 0) === depth);
      const colIdx = sameDepth.findIndex(x => x.id === s.id);
      return {
        id: s.id,
        type: s.calls.length > 1 ? 'compositeNode' : 'apiNode',
        position: { x: colIdx * 260 + 60, y: depth * 160 + 40 },
        data: { step: s },
      };
    });
    const edges: Edge[] = [];
    steps.forEach(s => (s.dependsOn || []).forEach(dep => {
      if (steps.some(x => x.id === dep)) {
        edges.push({ id: `${dep}->${s.id}`, source: dep, target: s.id, animated: true, style: { strokeWidth: 2 } });
      }
    }));
    return { initialNodes: nodes, initialEdges: edges };
  }, [steps]);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  useEffect(() => { setNodes(initialNodes); setEdges(initialEdges); }, [initialNodes, initialEdges, setNodes, setEdges]);

  const syncDag = (newSteps: StepData[]) => { setSteps(newSteps); onChange(newSteps); };

  const onDrop = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    const nodeType = event.dataTransfer.getData('application/reactflow');
    if (!nodeType) return;
    const id = `step-${Date.now()}`;
    const newStep: StepData = {
      id, label: '', calls: [],
    };
    syncDag([...steps, newStep]);
    setSelectedStep(newStep);
    setDrawerOpen(true);
  }, [steps, screenToFlowPosition]);

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onConnect = useCallback((conn: Connection) => {
    setEdges(eds => addEdge({ ...conn, animated: true, style: { strokeWidth: 2 } }, eds));
    setSteps(prev => {
      const next = prev.map(s => {
        if (s.id === conn.target) {
          const deps = new Set(s.dependsOn || []);
          deps.add(conn.source);
          return { ...s, dependsOn: Array.from(deps) };
        }
        if (s.id === conn.source) {
          return { ...s, dependsOn: (s.dependsOn || []).filter(d => d !== conn.target) };
        }
        return s;
      });
      onChange(next);
      return next;
    });
  }, [setEdges, onChange]);

  const onNodeClick = useCallback((_: any, node: Node) => {
    const step = steps.find(s => s.id === node.id);
    if (step) { setSelectedStep(step); setDrawerOpen(true); }
  }, [steps]);

  const onEdgesDelete = useCallback((deletedEdges: Edge[]) => {
    setSteps(prev => {
      const next = prev.map(s => {
        const removedDeps = deletedEdges.filter(e => e.target === s.id).map(e => e.source);
        if (removedDeps.length > 0) {
          return { ...s, dependsOn: (s.dependsOn || []).filter(d => !removedDeps.includes(d)) };
        }
        return s;
      });
      onChange(next);
      return next;
    });
  }, [onChange]);

  const handleSaveStep = (updated: StepData) => {
    syncDag(steps.map(s => s.id === updated.id ? updated : s));
    setDrawerOpen(false);
  };

  const handleDeleteStep = (id: string) => {
    syncDag(steps.filter(s => s.id !== id).map(s => ({
      ...s, dependsOn: (s.dependsOn || []).filter(d => d !== id),
    })));
    setDrawerOpen(false);
  };

  return (
    <div style={{ display: 'flex', border: `1px solid ${palette.border}`, borderRadius: 8, overflow: 'hidden', height }}>
      <ToolboxPanel />
      <div ref={wrapperRef} style={{ flex: 1, position: 'relative' }}>
        <ReactFlow
          nodes={nodes} edges={edges}
          onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
          onConnect={onConnect} onNodeClick={onNodeClick} onEdgesDelete={onEdgesDelete}
          onDrop={onDrop} onDragOver={onDragOver}
          nodeTypes={nodeViews}
          fitView deleteKeyCode={['Backspace', 'Delete']}
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} />
          <Controls showInteractive={false} />
          <MiniMap nodeStrokeWidth={3} zoomable pannable />
        </ReactFlow>
        {steps.length === 0 && (
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
            <Empty description="从左侧拖拽组件到画布" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        )}
      </div>
      <StepPropertyDrawer
        step={selectedStep} open={drawerOpen} onClose={() => setDrawerOpen(false)}
        onSave={handleSaveStep} onDelete={handleDeleteStep}
      />
    </div>
  );
}

export default function DagEditor(props: DagEditorProps) {
  return <ReactFlowProvider><DagEditorInner {...props} /></ReactFlowProvider>;
}
