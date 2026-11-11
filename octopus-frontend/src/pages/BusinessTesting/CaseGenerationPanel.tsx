import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, App, Button, Checkbox, Collapse, Descriptions, Drawer, Empty, Input, InputNumber, Radio, Select, Space, Switch, Table, Tag, Timeline, Tooltip } from "antd";
import { caseGeneration, type Flow, type FlowRevision, type GenerationCandidate, type GenerationTaskView, type IntentDetail, type ProbeEnvironmentView } from "../../api/businessTesting";
import { applyToFlow, budgetText, buildRequest, CONCLUSION_LABELS, defaultForm, END_REASONS, groupCandidates, isTerminal, newRequestId, PHASE_LABELS, selectionIssues, type GenerationForm } from "./caseGenerationModel";

type Decision = "DEFECT" | "SOURCE_WRONG";
const errorText = (error: unknown, fallback: string) => (error as { response?: { data?: { message?: string } } }).response?.data?.message || fallback;

/**
 * 三角色用例生成（Planner → Operator → Observer）。替换原“生成用例候选”抽屉：
 * 探测默认关闭；开启时只能选择已授权的隔离环境；观测只作为证据与问题，不会改写预期。
 */
export default function CaseGenerationPanel({ flow, saved, pointId, onApply }: { flow: Flow; saved: FlowRevision | null; pointId: string; onApply: (flow: Flow) => void }) {
  const { message } = App.useApp();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<GenerationForm>(defaultForm());
  const [environments, setEnvironments] = useState<ProbeEnvironmentView[]>([]);
  const [task, setTask] = useState<GenerationTaskView | null>(null);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [decisions, setDecisions] = useState<Record<string, Decision>>({});
  const [details, setDetails] = useState<Record<string, IntentDetail>>({});
  const confirmed = !!flow.steps && saved?.status === "CONFIRMED" && JSON.stringify(saved.definition) === JSON.stringify(flow);
  const enabled = environments.filter(e => e.enabled);

  useEffect(() => {
    if (!open) return;
    let alive = true;
    void caseGeneration.probeEnvironments().then(list => { if (alive) setEnvironments(list); }).catch(() => { if (alive) setEnvironments([]); });
    void caseGeneration.recent(flow.flowId).then(list => {
      const active = list.find(t => !isTerminal(t.state));
      if (alive && active) void caseGeneration.get(active.id).then(v => { if (alive) setTask(v); });
    }).catch(() => undefined);
    return () => { alive = false; };
  }, [open, flow.flowId]);

  // 任务进行中时轮询进度
  useEffect(() => {
    if (!task || isTerminal(task.state)) return;
    const timer = setInterval(() => { void caseGeneration.get(task.id).then(setTask).catch(() => undefined); }, 2000);
    return () => clearInterval(timer);
  }, [task]);

  const start = async () => {
    if (!saved || !confirmed) return;
    const built = buildRequest(form, saved.definition, pointId, environments, newRequestId());
    if (!built.request) { message.error(built.errors.join("；")); return; }
    setBusy(true); setSelected([]); setDecisions({}); setDetails({});
    try { setTask(await caseGeneration.create(built.request)); }
    catch (error) { message.error(errorText(error, "生成任务创建失败")); }
    finally { setBusy(false); }
  };

  const loadDetail = useCallback(async (intentId: string) => {
    if (!task || details[intentId]) return;
    try { const d = await caseGeneration.intent(task.id, intentId); setDetails(prev => ({ ...prev, [intentId]: d })); }
    catch (error) { message.error(errorText(error, "读取候选详情失败")); }
  }, [task, details, message]);

  const apply = async () => {
    if (!task || !saved || saved.hash !== task.flowHash) { message.error("流程已改变，请基于重新确认的版本生成候选"); return; }
    const issues = selectionIssues(task, selected, decisions);
    if (issues.length) { message.error(issues.join("；")); return; }
    setBusy(true);
    try {
      const result = await caseGeneration.apply(task.id, { intentIds: selected, decisions, expectationSources: null });
      const applied = applyToFlow(flow, result);
      applied.messages.forEach(m => message.warning(m));
      if (applied.added > 0) {
        onApply(applied.flow); setOpen(false);
        message.success(`已加入 ${applied.added} 个草稿测试点，请补全预期并保存、确认新版本`);
      }
    } catch (error) { message.error(errorText(error, "应用候选失败")); }
    finally { setBusy(false); }
  };

  const groups = useMemo(() => groupCandidates(task?.candidates ?? []), [task]);
  const running = !!task && !isTerminal(task.state);

  return <>
    <Button disabled={!confirmed} title={confirmed ? undefined : "先保存并确认当前流程"} onClick={() => setOpen(true)}>生成用例候选</Button>
    <Drawer title="三角色用例生成：Planner → Operator → Observer" open={open} width={1040} onClose={() => setOpen(false)}
      extra={<Space>
        {running && <Button danger onClick={() => task && void caseGeneration.cancel(task.id).then(setTask)}>取消任务</Button>}
        {task?.state === "FAILED" && task.errorCode !== "TASK_TIMEOUT" && <Button onClick={() => void caseGeneration.resume(task.id).then(setTask)}>从检查点继续</Button>}
        <Button type="primary" loading={busy} disabled={!task || !isTerminal(task.state) || !selected.length} onClick={() => void apply()}>加入草稿</Button>
      </Space>}>
      <Alert type="info" showIcon message="Planner 决定测什么，Operator 物化并在授权环境中探测，Observer 记录发生了什么"
        description="观测结果只作为证据和问题，不会建立或修改预期；探测一致也仍需人工确认。探测只能在管理员开启的隔离环境中进行，默认关闭。" />
      {!task || isTerminal(task.state) ? <GenerationFormView form={form} setForm={setForm} environments={enabled} busy={busy} onStart={() => void start()} /> : null}
      {task && <TaskProgress task={task} />}
      {task && isTerminal(task.state) && <>
        {task.warnings.map(w => <Alert key={w} type="warning" message={w} style={{ marginBottom: 8 }} />)}
        {task.questions.map(q => <Alert key={q} type="info" message={"待确认：" + q} style={{ marginBottom: 8 }} />)}
        {groups.length === 0 && <Empty description="没有候选" />}
        {groups.map(g => <section key={g.key} style={{ marginTop: 16 }}>
          <h4><Tag color={g.color}>{g.title}</Tag> {g.items.length} 个</h4>
          <p className="bt-muted">{g.hint}</p>
          <Table<GenerationCandidate> rowKey="intentId" size="small" dataSource={g.items} pagination={g.items.length > 10 ? { pageSize: 10 } : false}
            rowSelection={{ selectedRowKeys: selected.filter(id => g.items.some(c => c.intentId === id)), getCheckboxProps: c => ({ disabled: c.group === "SIDE_EFFECT_UNKNOWN" }),
              onChange: keys => setSelected(prev => [...prev.filter(id => !g.items.some(c => c.intentId === id)), ...keys.map(String)]) }}
            expandable={{ onExpand: (expanded, c) => { if (expanded) void loadDetail(c.intentId); }, expandedRowRender: c => <IntentDetailView detail={details[c.intentId]} /> }}
            columns={[
              { title: "用例", render: (_, c) => <><strong>{c.title}</strong><div className="bt-muted">{c.source === "MODEL" ? "模型规划" : "规则规划"} · {c.sourceRule}</div>
                <div>{c.point.endAtStepRef ? `执行至 ${c.point.endAtStepRef}` : "完整流程"}</div></> },
              { title: "观察", width: 150, render: (_, c) => <>{c.conclusion ? <Tag>{CONCLUSION_LABELS[c.conclusion] ?? c.conclusion}</Tag> : null}
                {c.skippedReason && <div className="bt-muted">{c.skippedReason}</div>}</> },
              { title: "原因 / 审阅事项", render: (_, c) => c.reasons.join("；") || "核对数据和独立业务预期" },
              ...(g.key === "CONTRADICTED" ? [{ title: "判断", width: 210, render: (_: unknown, c: GenerationCandidate) =>
                <Radio.Group size="small" value={decisions[c.intentId]} onChange={e => setDecisions(prev => ({ ...prev, [c.intentId]: e.target.value as Decision }))}>
                  <Tooltip title="保留原预期加入草稿，并标记为生成阶段发现的疑似缺陷"><Radio.Button value="DEFECT">缺陷</Radio.Button></Tooltip>
                  <Tooltip title="不应用；先修正契约或业务规则再重新生成"><Radio.Button value="SOURCE_WRONG">依据错误</Radio.Button></Tooltip>
                </Radio.Group> }] : []),
            ]} />
        </section>)}
      </>}
    </Drawer>
  </>;
}

function GenerationFormView({ form, setForm, environments, busy, onStart }: { form: GenerationForm; setForm: (f: GenerationForm) => void; environments: ProbeEnvironmentView[]; busy: boolean; onStart: () => void }) {
  const set = (patch: Partial<GenerationForm>) => setForm({ ...form, ...patch });
  return <Space direction="vertical" style={{ width: "100%", margin: "20px 0" }}>
    <Space wrap>
      <span>Planner 来源</span>
      <Checkbox.Group value={form.strategies} onChange={v => set({ strategies: v as GenerationForm["strategies"] })}
        options={[{ label: "规则（契约约束）", value: "RULE" }, { label: "模型（负向意图）", value: "MODEL" }]} />
      <span>覆盖</span>
      <Select mode="multiple" allowClear placeholder="全部" style={{ minWidth: 220 }} value={form.coverage} onChange={v => set({ coverage: v })}
        options={["BOUNDARY", "ENUM", "NEGATIVE", "AUTH", "STATE", "IDEMPOTENT"].map(v => ({ label: v, value: v }))} />
      <span>候选上限</span><InputNumber aria-label="候选上限" min={1} max={100} value={form.limit} onChange={v => set({ limit: v || 20 })} />
    </Space>
    <Space wrap>
      <span>有依据的拒绝状态</span><InputNumber aria-label="拒绝状态码" min={400} max={599} value={form.rejectionStatus} onChange={v => set({ rejectionStatus: v })} />
      <Input aria-label="拒绝预期依据" placeholder="业务规则或文档依据" value={form.rejectionEvidence} onChange={e => set({ rejectionEvidence: e.target.value })} style={{ width: 260 }} />
      <Input aria-label="补充说明" placeholder="给模型的补充说明（可选）" value={form.goal} onChange={e => set({ goal: e.target.value })} style={{ width: 300 }} />
    </Space>
    <Space wrap align="start">
      <Switch checked={form.probe} onChange={v => set({ probe: v })} disabled={!environments.length} />
      <span>在隔离环境中探测</span>
      {form.probe ? <>
        <Select aria-label="探测环境" placeholder="选择已开启生成探测的环境" style={{ width: 240 }} value={form.environment ?? undefined} onChange={v => set({ environment: v })}
          options={environments.map(e => ({ label: `${e.environment}（${e.dataPolicy === "DISPOSABLE" ? `定期重置 ${e.resetCycleHours}h` : "每次清理"}）`, value: e.environment }))} />
        <Input.TextArea aria-label="探测初始上下文" placeholder='探测用初始上下文（JSON，可选），例如 {"credentials":{"user":"probe"}}；任务快照加密保存' autoSize={{ minRows: 1, maxRows: 4 }}
          style={{ width: 380 }} value={form.initialContextJson} onChange={e => set({ initialContextJson: e.target.value })} />
      </> : <span className="bt-muted">{environments.length ? "关闭时不发送任何业务请求" : "没有已开启生成探测的环境，只能静态生成"}</span>}
    </Space>
    <Button type="primary" loading={busy} onClick={onStart}>开始生成</Button>
  </Space>;
}

function TaskProgress({ task }: { task: GenerationTaskView }) {
  const terminal = isTerminal(task.state);
  return <Collapse style={{ margin: "16px 0" }} defaultActiveKey={terminal ? [] : ["progress"]} items={[{
    key: "progress",
    label: <Space wrap>
      <Tag color={task.state === "FAILED" ? "red" : terminal ? "green" : "processing"}>{task.state}</Tag>
      {task.phase && <span>{PHASE_LABELS[task.phase] ?? task.phase}</span>}
      <span className="bt-muted">{budgetText(task)}</span>
      {task.endReason && <span className="bt-muted">结束：{END_REASONS[task.endReason] ?? task.endReason}</span>}
      {task.summary.suspectedDefects > 0 && <Tag color="red">疑似缺陷 {task.summary.suspectedDefects}</Tag>}
      {task.summary.sideEffectUnknown > 0 && <Tag color="volcano">副作用未知 {task.summary.sideEffectUnknown}</Tag>}
    </Space>,
    children: <>
      {task.error && <Alert type="error" message={task.error} style={{ marginBottom: 8 }} />}
      <Descriptions size="small" column={3} items={[
        { key: "probe", label: "探测", children: task.probeMode === "ON" ? task.probeEnvironment : "关闭" },
        { key: "ready", label: "可审阅", children: task.summary.ready },
        { key: "blocked", label: "待处理", children: task.summary.blocked },
      ]} />
      <Timeline style={{ marginTop: 12 }} items={task.events.slice(-12).map(e => ({ children: `第 ${e.round} 轮 · ${e.message}` }))} />
    </>,
  }]} />;
}

function IntentDetailView({ detail }: { detail?: IntentDetail }) {
  if (!detail) return <span className="bt-muted">加载中…</span>;
  return <Space direction="vertical" style={{ width: "100%" }}>
    {detail.intent.rationale && <div><strong>规划理由：</strong>{detail.intent.rationale}</div>}
    <Table size="small" pagination={false} rowKey={e => e.callId + "/" + e.assertion.id} dataSource={detail.intent.expectations}
      columns={[
        { title: "预期", render: (_, e) => `${e.callId} · ${e.assertion.kind}${e.assertion.path ? " " + e.assertion.path : ""}` },
        { title: "期望值", render: (_, e) => e.sourceType === "MISSING" ? <span className="bt-muted">缺少依据{e.proposed != null ? `（建议 ${JSON.stringify(e.proposed)}）` : ""}</span> : JSON.stringify(e.assertion.expected) },
        { title: "来源", render: (_, e) => <Tag>{e.sourceType}</Tag> },
      ]} />
    {detail.rounds.map(r => <section key={r.round}>
      <h5>第 {r.round} 轮{r.carried ? "（沿用上一轮）" : ""}</h5>
      {r.candidate && !r.candidate.staticResult.ok && <Alert type="warning" message={"静态校验：" + r.candidate.staticResult.errors.join("；")} />}
      {r.skippedReason && <div className="bt-muted">未探测：{r.skippedReason}</div>}
      {r.probe && <div className="bt-muted">探测 {r.probe.id.slice(0, 8)} · {r.probe.state} · 环境 {r.probe.environment}#{r.probe.environmentRevision} · 命名空间 {r.probe.namespace}
        {r.probe.cleanupStatus ? ` · 清理 ${r.probe.cleanupStatus}` : ""}{r.probe.error ? ` · ${r.probe.error}` : ""}</div>}
      {r.observation && <>
        <div><Tag>{CONCLUSION_LABELS[r.observation.conclusion] ?? r.observation.conclusion}</Tag></div>
        {r.observation.evidence.length > 0 && <Table size="small" pagination={false} rowKey={(row) => row.callId + "/" + row.assertionId}
          dataSource={r.observation.evidence.flatMap(c => c.assertions.map(a => ({ ...a, callId: c.callId, httpStatus: c.httpStatus })))}
          columns={[
            { title: "调用", dataIndex: "callId" },
            { title: "断言", render: (_, a) => `${a.assertionId}${a.path ? " " + a.path : ""}` },
            { title: "预期", render: (_, a) => JSON.stringify(a.expected) },
            { title: "实际（已脱敏）", render: (_, a) => a.actual ?? "—" },
            { title: "分类", dataIndex: "classification" },
          ]} />}
        {r.observation.feedback.length > 0 && <ul>{r.observation.feedback.map(f =>
          <li key={f.feedbackId}><Tag color={f.route === "PLANNER" ? "blue" : "orange"}>{f.route === "PLANNER" ? "交给 Planner" : "需要人工"}</Tag>{f.message}
            {f.callId ? ` · ${f.callId}` : ""}{f.assertionId ? `/${f.assertionId}` : ""}{f.httpStatus ? ` · HTTP ${f.httpStatus}` : ""}{f.platformCode ? ` · ${f.platformCode}` : ""}</li>)}</ul>}
      </>}
    </section>)}
  </Space>;
}
