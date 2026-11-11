import { useMemo, useRef, useState, type PointerEvent } from "react";
import { Button, Modal, Tag, Tooltip } from "antd";
import { ApiOutlined, ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, HolderOutlined, PlusOutlined, ThunderboltOutlined } from "@ant-design/icons";
import type { Flow, Operation } from "../../api/businessTesting";
import type { ContextScope } from "../../api/projectContext";
import type { CatalogItem } from "./apiCatalogModel";
import ApiCatalog from "./ApiCatalog";
import { movePending, pendingCounts, pendingStep, type PendingStep } from "./stepPickerModel";

export default function StepPicker({ open, flow, operations, initialScope, onClose, onAppend, onOperation, onConfigure, onResolveCode, onImport }: {
  open: boolean; flow: Flow; operations: Operation[]; initialScope?: ContextScope; onClose: () => void;
  onAppend: (steps: PendingStep[], fill: boolean) => void; onOperation: (operation: Operation) => void;
  onConfigure: (operation: Operation) => void; onResolveCode: (item: CatalogItem, method: string) => Promise<Operation>; onImport: () => void;
}) {
  const [pending, setPending] = useState<PendingStep[]>([]);
  const [resolving, setResolving] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const [dragged, setDragged] = useState<string | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const pointerDrag = useRef<{ id: string; x: number; y: number; moved: boolean } | null>(null);
  const committed = useRef(false);
  const queueEnd = useRef<HTMLDivElement>(null);
  const counts = useMemo(() => pendingCounts(pending), [pending]);
  const room = 50 - flow.nodes.length;
  const clearDrag = () => { pointerDrag.current = null; setDragged(null); setDropTarget(null); };
  const close = () => { if (resolving) return; setPending([]); setAnnouncement(""); clearDrag(); onClose(); };
  const add = (operation: Operation) => {
    committed.current = false;
    setPending(current => current.length < room ? [...current, pendingStep(operation)] : current);
    setAnnouncement(`已选择 ${operation.method} ${operation.path}，可继续添加接口`);
    requestAnimationFrame(() => queueEnd.current?.scrollIntoView({ block: "nearest" }));
  };
  const move = (from: string, to: string) => {
    setPending(current => movePending(current, from, to));
    setAnnouncement("已调整本次选择的执行顺序");
  };
  const targetAt = (event: PointerEvent<HTMLSpanElement>) => event.currentTarget.ownerDocument
    .elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>("[data-pending-id]")?.dataset.pendingId;
  const dragMove = (event: PointerEvent<HTMLSpanElement>) => {
    const current = pointerDrag.current;
    if (!current || (!current.moved && Math.hypot(event.clientX - current.x, event.clientY - current.y) < 5)) return;
    current.moved = true; setDragged(current.id); setDropTarget(targetAt(event) || null);
  };
  const dragEnd = (event: PointerEvent<HTMLSpanElement>) => {
    const current = pointerDrag.current, target = targetAt(event);
    if (current?.moved && target && target !== current.id) move(current.id, target);
    clearDrag();
  };
  const apply = (fill: boolean) => {
    if (committed.current || resolving || !pending.length || pending.length > room) return;
    committed.current = true; onAppend(pending, fill); setPending([]); setAnnouncement("");
  };
  const resolve = async (item: CatalogItem, method: string) => {
    setResolving(true); try { return await onResolveCode(item, method); } finally { setResolving(false); }
  };
  return <Modal open={open} onCancel={close} closable={!resolving} maskClosable={false} keyboard={!resolving}
    width="min(1240px, calc(100vw - 48px))" className="bt-step-picker-modal" style={{ top: 24 }}
    title={<div className="bt-step-picker-title"><span className="bt-step-picker-icon"><ApiOutlined /></span>
      <div><strong>编排接口步骤</strong><p>连续选择接口，排好调用顺序，再统一填写测试数据。</p></div></div>}
    footer={<div className="bt-step-picker-footer"><div><strong>{pending.length ? `本次选择 ${pending.length} 步` : "先从左侧添加接口"}</strong>
      <span>{flow.nodes.length ? `将接在现有 ${flow.nodes.length} 步之后` : "参数和检查项可在下一步补全"}</span></div>
      <div className="bt-step-picker-footer-actions"><Button disabled={resolving} onClick={close}>取消</Button>
        <Button disabled={resolving || !pending.length || pending.length > room} onClick={() => apply(false)}>加入画布</Button>
        <Button type="primary" icon={<ThunderboltOutlined />} disabled={resolving || !pending.length || pending.length > room}
          onClick={() => apply(true)}>加入并 AI 补全</Button></div></div>}>
    <div className="bt-step-picker-body">
      <section className="bt-step-picker-catalog" aria-label="选择接口目录">
        <ApiCatalog initialScope={initialScope} operations={operations} onPick={add} pickedCounts={counts}
          pickDisabled={resolving || pending.length >= room} onOperation={onOperation} onConfigure={onConfigure} onResolveCode={resolve} />
        <div className="bt-step-picker-external"><span>没找到需要的接口？</span><Button type="link" size="small" disabled={resolving} onClick={onImport}>补充外部 API</Button></div>
      </section>
      <aside className="bt-step-picker-path" aria-label="本次选择的执行顺序">
        <div className="bt-step-picker-path-heading"><div><h3>本次调用顺序 <Tag color="blue">{pending.length}</Tag></h3><p>拖动调序，也可用上下按钮移动</p></div></div>
        {!!flow.nodes.length && <div className="bt-step-picker-existing"><small>接在现有流程之后</small><strong>{flow.nodes.length}. {flow.nodes.at(-1)?.label}</strong></div>}
        <div className="bt-step-picker-queue" tabIndex={0} aria-label="已选步骤列表">
          {!pending.length ? <div className="bt-step-picker-empty"><span><PlusOutlined /></span><h4>从第一个接口开始</h4><p>点击左侧「加入」后，<br />这里会按你的选择排列步骤。</p><small>同一个接口可以添加多次</small></div>
            : <ol>{pending.map((step, i) => <li key={step.id} data-pending-id={step.id}
              className={dragged === step.id ? "dragging" : dropTarget === step.id ? "drop-target" : ""}>
              <div className="bt-step-picker-step-top"><span className="bt-step-picker-grip" title="拖动调整顺序"
                onPointerDown={event => { if (event.button !== 0) return; event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId);
                  pointerDrag.current = { id: step.id, x: event.clientX, y: event.clientY, moved: false }; }}
                onPointerMove={dragMove} onPointerUp={dragEnd} onPointerCancel={clearDrag} onLostPointerCapture={clearDrag}><HolderOutlined /></span>
                <span className="bt-step-picker-number">{String(flow.nodes.length + i + 1).padStart(2, "0")}</span><span className={`bt-method ${step.operation.method.toLowerCase()}`}>{step.operation.method}</span>
                <div className="bt-step-picker-move"><Tooltip title="上移"><Button size="small" type="text" icon={<ArrowUpOutlined />} disabled={i === 0}
                  aria-label={`上移第 ${i + 1} 个已选步骤`} onClick={() => move(step.id, pending[i - 1].id)} /></Tooltip>
                  <Tooltip title="下移"><Button size="small" type="text" icon={<ArrowDownOutlined />} disabled={i === pending.length - 1}
                    aria-label={`下移第 ${i + 1} 个已选步骤`} onClick={() => move(step.id, pending[i + 1].id)} /></Tooltip>
                  <Tooltip title="移除"><Button size="small" type="text" icon={<DeleteOutlined />} aria-label={`移除第 ${i + 1} 个已选步骤`}
                    onClick={() => { setPending(items => items.filter(s => s.id !== step.id)); setAnnouncement(`已移除第 ${i + 1} 个步骤`); }} /></Tooltip></div></div>
              <strong title={step.operation.path}>{step.operation.path}</strong><small title={`${step.operation.system} / ${step.operation.service}`}>{step.operation.system} / {step.operation.service}</small>
              {!!step.operation.implementations.length && <small>{step.operation.implementations[0].branch} · {step.operation.implementations[0].commitId.slice(0, 8)}</small>}
            </li>)}</ol>}
          <div ref={queueEnd} />
        </div>
        <div className="bt-step-picker-path-note">{pending.length >= room ? "已达到 50 步上限，请移除步骤后继续" : "先确定业务路径，AI 再补全参数和数据传递。"}</div>
      </aside>
    </div>
    <span className="bt-sr-only" role="status" aria-live="polite">{announcement}</span>
  </Modal>;
}
