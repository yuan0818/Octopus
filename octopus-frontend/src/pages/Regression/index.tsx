import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Alert, App, Button, Card, Col, Descriptions, Form, Input, InputNumber, Row, Select, Space, Statistic, Table, Tabs, Tag, Typography } from "antd";
import { ReloadOutlined, StopOutlined, ThunderboltOutlined } from "@ant-design/icons";
import { regressionApi, errorMessage, type BatchItem, type MetricsRow, type RegressionBatch } from "../../api/regression";
import { palette } from '../../theme';

const verdictColor: Record<string, string> = {
  PASS: "green", FAIL: "red", INCONCLUSIVE: "orange", NO_AFFECTED_TESTS: "default", PENDING: "blue",
};
const verdictText: Record<string, string> = {
  PASS: "通过", FAIL: "失败", INCONCLUSIVE: "无法判定", NO_AFFECTED_TESTS: "无受影响测试", PENDING: "进行中",
};
const itemColor: Record<string, string> = { PENDING: "default", SUBMITTED: "blue", SKIPPED: "orange", ERROR: "red" };

function short(sha: string) { return sha ? sha.slice(0, 8) : ""; }

/**
 * Phase 1A / 1E：变更触发的批量回归与 LLM 建议度量。
 * 批次由 CI、Gerrit 变更审查或本页手动发起；后台按全局并发上限逐个执行受影响的已确认测试点。
 */
export default function RegressionPage() {
  const { message } = App.useApp();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get("id");
  const [batches, setBatches] = useState<RegressionBatch[]>([]);
  const [selected, setSelected] = useState<RegressionBatch | null>(null);
  const [metrics, setMetrics] = useState<MetricsRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const refresh = useCallback(async () => {
    try {
      setBatches(await regressionApi.list());
      if (selectedId) setSelected(await regressionApi.get(selectedId));
    } catch (e) { message.error(errorMessage(e)); }
  }, [selectedId, message]);

  useEffect(() => {
    const timer = window.setTimeout(() => void refresh(), 0);
    return () => window.clearTimeout(timer);
  }, [refresh]);
  useEffect(() => {
    // 有进行中的批次时每 5 秒刷新
    if (!batches.some(b => b.status === "OPEN") && selected?.status !== "OPEN") return;
    const timer = window.setInterval(() => void refresh(), 5000);
    return () => window.clearInterval(timer);
  }, [batches, selected, refresh]);

  const start = async () => {
    const v = await form.validateFields();
    setLoading(true);
    try {
      const batch = await regressionApi.start({
        projectIds: String(v.projectIds).split(",").map((s: string) => Number(s.trim())).filter(Boolean),
        branch: v.branch, baseCommitId: v.baseCommitId.trim(), targetCommitId: v.targetCommitId.trim(),
        environment: v.environment, timeoutSeconds: v.timeoutSeconds,
      });
      message.success("已提交批量回归");
      setParams({ id: batch.id });
    } catch (e) { message.error(errorMessage(e)); } finally { setLoading(false); }
  };

  const columns = useMemo(() => [
    { title: "流程 / 测试点", render: (_: unknown, i: BatchItem) => <span>{i.flowId}@{i.flowRevision} · {i.testPointId}</span> },
    { title: "状态", render: (_: unknown, i: BatchItem) => i.state === "DONE"
        ? <Tag color={verdictColor[i.verdict || "INCONCLUSIVE"]}>{verdictText[i.verdict || "INCONCLUSIVE"] || i.verdict}</Tag>
        : <Tag color={itemColor[i.state]}>{i.state}</Tag> },
    { title: "原因", dataIndex: "reason", render: (r: string | null) => r ? <Typography.Text type="secondary">{r}</Typography.Text> : "-" },
    { title: "运行", dataIndex: "runId", render: (r: string | null) => r ? <code>{r.slice(0, 8)}</code> : "-" },
  ], []);

  return (
    <Space direction="vertical" style={{ width: "100%" }} size="middle">
      <Typography.Title level={4} style={{ margin: 0 }}>变更回归批次</Typography.Title>
      <Tabs items={[
        {
          key: "batches", label: "批次", children: (
            <Row gutter={16}>
              <Col xs={24} lg={9}>
                <Card size="small" title="发起回归" style={{ marginBottom: 12 }}>
                  <Form form={form} layout="vertical" initialValues={{ environment: "test", timeoutSeconds: 600 }}>
                    <Form.Item name="projectIds" label="项目 ID（逗号分隔）" rules={[{ required: true }]}><Input placeholder="1,2" /></Form.Item>
                    <Form.Item name="branch" label="分支" rules={[{ required: true }]}><Input placeholder="main" /></Form.Item>
                    <Form.Item name="baseCommitId" label="基线提交（40 位）" rules={[{ required: true, pattern: /^[0-9a-fA-F]{40}$/ }]}><Input /></Form.Item>
                    <Form.Item name="targetCommitId" label="目标提交（40 位，已部署到测试环境）" rules={[{ required: true, pattern: /^[0-9a-fA-F]{40}$/ }]}><Input /></Form.Item>
                    <Space>
                      <Form.Item name="environment" label="环境" rules={[{ required: true }]}><Select style={{ width: 140 }} options={["test", "staging"].map(v => ({ value: v, label: v }))} /></Form.Item>
                      <Form.Item name="timeoutSeconds" label="单个运行超时（秒）"><InputNumber min={1} max={3600} /></Form.Item>
                    </Space>
                    <Button type="primary" icon={<ThunderboltOutlined />} loading={loading} onClick={() => void start()}>开始</Button>
                  </Form>
                </Card>
                <Card size="small" title="最近批次" extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => void refresh()} />}>
                  <Table size="small" rowKey="id" pagination={{ pageSize: 10 }} dataSource={batches}
                    onRow={b => ({ onClick: () => setParams({ id: b.id }), style: { cursor: "pointer", background: b.id === selectedId ? palette.primaryBg : undefined } })}
                    columns={[
                      { title: "创建", dataIndex: "createdAt", render: (t: string) => new Date(t).toLocaleString() },
                      { title: "来源", dataIndex: "triggerSource" },
                      { title: "判定", dataIndex: "verdict", render: (v: string) => <Tag color={verdictColor[v]}>{verdictText[v] || v}</Tag> },
                    ]} />
                </Card>
              </Col>
              <Col xs={24} lg={15}>
                {!selected ? <Alert type="info" showIcon message="选择左侧批次查看详情；CI 也可以调用 POST /api/business-testing/regression-batches 发起。" /> : (
                  <Card size="small" title={<Space>批次 <code>{selected.id.slice(0, 8)}</code><Tag color={verdictColor[selected.verdict]}>{verdictText[selected.verdict] || selected.verdict}</Tag></Space>}
                    extra={selected.status === "OPEN" && <Button danger size="small" icon={<StopOutlined />} onClick={() => void regressionApi.cancel(selected.id).then(setSelected)}>取消</Button>}>
                    <Row gutter={12} style={{ marginBottom: 12 }}>
                      <Col span={4}><Statistic title="受影响" value={selected.counts.total} /></Col>
                      <Col span={4}><Statistic title="通过" value={selected.counts.pass} valueStyle={{ color: palette.successText }} /></Col>
                      <Col span={4}><Statistic title="失败" value={selected.counts.fail} valueStyle={{ color: palette.errorText }} /></Col>
                      <Col span={4}><Statistic title="无法判定" value={selected.counts.inconclusive} /></Col>
                      <Col span={4}><Statistic title="跳过/错误" value={selected.counts.skipped + selected.counts.error} /></Col>
                      <Col span={4}><Statistic title="进行中" value={selected.counts.pending + selected.counts.running} /></Col>
                    </Row>
                    <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
                      <Descriptions.Item label="状态">{selected.status}</Descriptions.Item>
                      <Descriptions.Item label="环境">{selected.environment}</Descriptions.Item>
                      <Descriptions.Item label="版本">{selected.branch} · {short(selected.baseCommitId)} → {short(selected.targetCommitId)}</Descriptions.Item>
                      <Descriptions.Item label="外部引用">{selected.externalRef || "-"}</Descriptions.Item>
                      <Descriptions.Item label="影响分析">{String(selected.impact?.codeImpactStatus ?? "分析中")}</Descriptions.Item>
                      <Descriptions.Item label="回调">{selected.callbackStatus}</Descriptions.Item>
                    </Descriptions>
                    {selected.error && <Alert type="error" showIcon message={selected.error} style={{ marginBottom: 12 }} />}
                    {Array.isArray(selected.impact?.unmappedEndpoints) && (selected.impact!.unmappedEndpoints as string[]).length > 0 && (
                      <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                        message={`有 ${(selected.impact!.unmappedEndpoints as string[]).length} 个变更入口尚无测试覆盖`}
                        description={(selected.impact!.unmappedEndpoints as string[]).slice(0, 10).join("，")} />
                    )}
                    <Table size="small" rowKey={(i: BatchItem) => `${i.flowId}@${i.flowRevision}#${i.testPointId}`} dataSource={selected.items} columns={columns} pagination={{ pageSize: 20 }} />
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>失败或无法判定的运行可在「运行结果」中查看报告并使用 AI 失败分析。</Typography.Text>
                  </Card>
                )}
              </Col>
            </Row>
          ),
        },
        {
          key: "metrics", label: "AI 建议度量", children: <MetricsTab rows={metrics} onLoad={async () => {
            try { setMetrics((await regressionApi.metrics(30)).rows); } catch (e) { message.error(errorMessage(e)); }
          }} />,
        },
      ]} />
    </Space>
  );
}

function pct(v: number | null) { return v == null ? "-" : `${Math.round(v * 100)}%`; }

function MetricsTab({ rows, onLoad }: { rows: MetricsRow[]; onLoad: () => Promise<void> }) {
  useEffect(() => { void onLoad(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Card size="small" title="最近 30 天" extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => void onLoad()} />}>
      <Table size="small" rowKey={r => `${r.kind}:${r.model}`} dataSource={rows} pagination={false} columns={[
        { title: "类型", dataIndex: "kind" },
        { title: "模型", dataIndex: "model", render: (m: string | null) => m || "默认" },
        { title: "调用", render: (_: unknown, r: MetricsRow) => r.generated + r.failed },
        { title: "失败率", dataIndex: "failureRate", render: pct },
        { title: "采纳率", dataIndex: "acceptanceRate", render: pct },
        { title: "P50 / P95 耗时", render: (_: unknown, r: MetricsRow) => r.p50LatencyMs == null ? "-" : `${(r.p50LatencyMs / 1000).toFixed(1)}s / ${((r.p95LatencyMs ?? 0) / 1000).toFixed(1)}s` },
        { title: "主要失败", dataIndex: "failureCodes", render: (c: Record<string, number>) => Object.entries(c).map(([k, v]) => <Tag key={k}>{k} × {v}</Tag>) },
      ]} />
    </Card>
  );
}
