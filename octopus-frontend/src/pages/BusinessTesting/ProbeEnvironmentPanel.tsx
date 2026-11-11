import { useEffect, useState } from "react";
import { Alert, App, Button, Checkbox, Input, InputNumber, Radio, Space, Switch, Tag } from "antd";
import { caseGeneration, type ProbeEnvironmentRequest, type ProbeEnvironmentView } from "../../api/businessTesting";

/**
 * 生成探测授权（ENVG-001～004）：只有配置的管理员可以开启或修改；开启前必须声明非生产隔离环境与数据策略。
 * 开启后该环境只供生成探测使用，正式运行会被拒绝（ADR-CG-05）。
 */
export default function ProbeEnvironmentPanel({ environment }: { environment: string }) {
  const { message } = App.useApp();
  const [view, setView] = useState<ProbeEnvironmentView | null>(null);
  const [admin, setAdmin] = useState(false);
  const [draft, setDraft] = useState<ProbeEnvironmentRequest>({ enabled: false, dataPolicy: "CLEANUP_REQUIRED", resetCycleHours: null, nonProductionDeclared: false, reason: "" });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    void caseGeneration.probeAccess().then(a => { if (alive) setAdmin(a.admin); }).catch(() => { if (alive) setAdmin(false); });
    void caseGeneration.probeEnvironments().then(list => {
      if (!alive) return;
      const current = list.find(e => e.environment === environment) ?? null;
      setView(current);
      setDraft(current ? { enabled: current.enabled, dataPolicy: current.dataPolicy, resetCycleHours: current.resetCycleHours, nonProductionDeclared: current.nonProductionDeclared, reason: "" }
        : { enabled: false, dataPolicy: "CLEANUP_REQUIRED", resetCycleHours: null, nonProductionDeclared: false, reason: "" });
    }).catch(() => { if (alive) setView(null); });
    return () => { alive = false; };
  }, [environment]);

  const save = async () => {
    setSaving(true);
    try { setView(await caseGeneration.putProbeEnvironment(environment, draft)); setDraft(d => ({ ...d, reason: "" })); message.success("生成探测授权已更新"); }
    catch (error) { message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || "保存失败"); }
    finally { setSaving(false); }
  };

  return <div className="bt-probe-environment" style={{ marginTop: 12 }}>
    <Space wrap>
      <strong>生成探测</strong>
      {view?.enabled ? <Tag color="green">已开启 · 修订 {view.revision}</Tag> : <Tag>未开启</Tag>}
      {view?.enabled && <span className="bt-muted">{view.dataPolicy === "DISPOSABLE" ? `定期重置（${view.resetCycleHours} 小时）` : "每次探测执行清理"} · {view.updatedBy}</span>}
    </Space>
    {view?.enabled && <Alert type="warning" showIcon style={{ marginTop: 8 }} message="该环境只供用例生成探测使用，已确认用例的正式运行会被拒绝。" />}
    {admin ? <Space direction="vertical" style={{ marginTop: 8 }}>
      <Space wrap>
        <Switch checked={draft.enabled} onChange={v => setDraft({ ...draft, enabled: v })} /><span>允许生成探测</span>
        <Radio.Group value={draft.dataPolicy} onChange={e => setDraft({ ...draft, dataPolicy: e.target.value })}>
          <Radio value="CLEANUP_REQUIRED">每次探测执行已审阅的清理</Radio>
          <Radio value="DISPOSABLE">环境定期整体重置</Radio>
        </Radio.Group>
        {draft.dataPolicy === "DISPOSABLE" && <><span>重置周期（小时）</span><InputNumber min={1} max={2160} value={draft.resetCycleHours} onChange={v => setDraft({ ...draft, resetCycleHours: v })} /></>}
      </Space>
      <Checkbox checked={draft.nonProductionDeclared} onChange={e => setDraft({ ...draft, nonProductionDeclared: e.target.checked })}>我确认这是非生产的隔离测试环境，探测可能写入测试数据</Checkbox>
      <Space><Input placeholder="变更理由（必填，进入审计）" value={draft.reason} onChange={e => setDraft({ ...draft, reason: e.target.value })} style={{ width: 360 }} />
        <Button loading={saving} disabled={!draft.reason.trim()} onClick={() => void save()}>保存授权</Button></Space>
    </Space> : <div className="bt-muted" style={{ marginTop: 8 }}>只有环境管理员可以修改生成探测授权。</div>}
  </div>;
}
