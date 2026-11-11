import { useEffect, useMemo, useState } from "react";
import { Alert, App, Button, Card, Col, Descriptions, Empty, Form, Input, InputNumber, List, Row, Space, Table, Tag, Typography } from "antd";
import { CheckCircleOutlined, ExportOutlined, PlayCircleOutlined, SaveOutlined } from "@ant-design/icons";
import { dslApi, errorMessage, sourceBadge, type Diagnostic, type LintResult, type ScenarioResult, type Scope, type Translation } from "../../api/dsl";
import { palette } from "../../theme";

const SAMPLE = `feature: 退款
scenarios:
  - name: 已支付订单可以申请退款
    id: refund-paid-order
    tags: [refund, smoke]
    steps:
      - call: POST /orders
        with:
          amount: 100
        as: order
      - call: POST /orders/{id}/pay
        with:
          id: \${order.id}
      - call: POST /refunds
        with:
          orderId: \${order.id}
          reason: "质量问题"
        headers:
          X-Request-Id: \${fn.uuid()}
        expect:
          - status 200
          - $.state == "REFUND_PENDING"
          - $.id exists
`;

const severityColor: Record<string, string> = { ERROR: "red", WARNING: "orange", INFO: "blue" };
const roleColor: Record<string, string> = { GIVEN: "default", WHEN: "blue", THEN: "green" };

function DiagnosticList({ items }: { items: Diagnostic[] }) {
  if (items.length === 0) return <Typography.Text type="success"><CheckCircleOutlined /> 没有问题</Typography.Text>;
  return (
    <List size="small" dataSource={items} renderItem={d => (
      <List.Item>
        <Space direction="vertical" size={0} style={{ width: "100%" }}>
          <Space wrap>
            <Tag color={severityColor[d.severity]}>{d.severity}</Tag>
            <Typography.Text code>行 {d.line}:{d.column}</Typography.Text>
            <Typography.Text type="secondary">{d.code}</Typography.Text>
          </Space>
          <Typography.Text>{d.message}</Typography.Text>
          {d.hint && <Typography.Text type="secondary" style={{ fontSize: 12 }}>建议：{d.hint}</Typography.Text>}
        </Space>
      </List.Item>
    )} />
  );
}

function describeValue(v: { source: string; value: unknown }): string {
  if (v.source === "context") return `\${${String(v.value)}}`;
  if (v.source === "secret") return `\${secret.${String(v.value)}}`;
  if (v.source === "environment") return `\${env.${String(v.value)}}`;
  return JSON.stringify(v.value);
}

/** 技术视图：每个步骤的实际 API、绑定、提取、断言与来源（FR-CONV-02、FR-UI-07 的 I1 子集）。 */
function ScenarioView({ result }: { result: ScenarioResult }) {
  const flow = result.flow;
  const errors = result.diagnostics.filter(d => d.severity === "ERROR");
  const point = flow?.testPoints[0];
  const rows = (flow?.nodes ?? []).map((node, index) => ({ node, index }));
  return (
    <Card size="small" style={{ marginBottom: 12 }}
      title={<Space>{result.name}<Typography.Text type="secondary" style={{ fontSize: 12 }}>{result.scenarioId}</Typography.Text>
        {flow ? <Tag color="green">已转换为草稿</Tag> : <Tag color="red">未能转换</Tag>}
        {result.pending.length > 0 && <Tag color="orange">待补充 {result.pending.length}</Tag>}</Space>}>
      {flow && (
        <Descriptions size="small" column={2} style={{ marginBottom: 8 }}>
          <Descriptions.Item label="Flow">{flow.flowId}</Descriptions.Item>
          <Descriptions.Item label="执行模型">严格线性 · {flow.nodes.length} 个串行步骤</Descriptions.Item>
        </Descriptions>
      )}
      {errors.length > 0 && <DiagnosticList items={errors} />}
      {result.pending.length > 0 && (
        <Alert type="warning" showIcon style={{ marginBottom: 8 }} message="以下内容尚未确定，平台不会猜测；补充后再确认流程"
          description={<ul style={{ margin: 0, paddingLeft: 18 }}>{result.pending.map((p, i) => <li key={i}>{p.nodeId} · {p.item}：{p.reason}（行 {p.line}）</li>)}</ul>} />
      )}
      {flow && (
        <Table size="small" rowKey={r => r.node.id} pagination={false} dataSource={rows} columns={[
          { title: "#", width: 70, render: (_: unknown, r) => <Space direction="vertical" size={0}><span>{r.node.id}</span>
            <Tag color={roleColor[result.roles[r.index]] ?? "default"}>{result.roles[r.index]}</Tag></Space> },
          { title: "API", render: (_: unknown, r) => <Space direction="vertical" size={0}><Typography.Text strong>{r.node.label}</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.node.operationRef}</Typography.Text>
            {r.node.waitMillis > 0 && <Tag>等待 {r.node.waitMillis} ms</Tag>}
            {r.node.poll && <Tag color="purple">轮询 每 {r.node.poll.intervalMillis} ms · ≤ {r.node.poll.timeoutMillis} ms · 授权 {r.node.poll.authorizationRef}</Tag>}</Space> },
          { title: "请求绑定", render: (_: unknown, r) => <Space direction="vertical" size={2}>
            {Object.entries(r.node.inputs).map(([port, v]) => {
              const p = result.provenance.find(x => x.nodeId === r.node.id && x.item === port);
              const badge = p ? sourceBadge(p.source) : null;
              return <span key={port}><Typography.Text code>{port}</Typography.Text> ← {describeValue(v)} {badge && <Tag color={badge.color}>{badge.text}</Tag>}</span>;
            })}
            {r.node.preparation && <span><Tag color="cyan">预处理</Tag>{r.node.preparation.tools.map(t => `${t.tool}@${t.version}`).join(" → ")}</span>}
          </Space> },
          { title: "输出", render: (_: unknown, r) => <Space direction="vertical" size={2}>{r.node.outputs.map(o =>
            <span key={o.key}><Typography.Text code>{o.key}</Typography.Text> = {o.path}（{o.type}）</span>)}</Space> },
          { title: "验证", render: (_: unknown, r) => <Space direction="vertical" size={2}>
            {(point?.assertions[r.node.id] ?? []).map(a => {
              const p = result.provenance.find(x => x.nodeId === r.node.id && (x.item === "status" ? a.kind === "STATUS_EQUALS" : x.item === `assertion.${a.id}`));
              const badge = p ? sourceBadge(p.source) : null;
              const text = a.kind === "STATUS_EQUALS" ? `status ${String(a.expected)}` : a.kind === "JSON_EXISTS" ? `${a.path} exists`
                : a.kind === "SCHEMA_CONFORMS" ? "符合回应契约" : `${a.path} == ${JSON.stringify(a.expected)}`;
              return <span key={a.id}>{text} {badge && <Tag color={badge.color}>{badge.text}</Tag>}</span>;
            })}
          </Space> },
        ]} />
      )}
    </Card>
  );
}

/**
 * DSL 工作台（I1）：编写 YAML 场景 → 实时 lint → 确定性转换预览（含来源）→ 保存为 Flow 草稿；
 * 也可以把已有有序流程导出为规范 DSL。转换不调用 LLM，也不会自动确认或执行。
 */
export default function DslWorkbench() {
  const { message } = App.useApp();
  const [source, setSource] = useState(SAMPLE);
  const [lint, setLint] = useState<LintResult | null>(null);
  const [translation, setTranslation] = useState<Translation | null>(null);
  const [busy, setBusy] = useState(false);
  const [scopeForm] = Form.useForm();
  const [exportForm] = Form.useForm();

  useEffect(() => {
    const timer = window.setTimeout(() => {
      dslApi.lint(source).then(setLint).catch(e => message.error(errorMessage(e)));
    }, 500);
    return () => window.clearTimeout(timer);
  }, [source, message]);

  const errors = useMemo(() => (lint?.diagnostics ?? []).filter(d => d.severity === "ERROR").length, [lint]);

  const scope = async (): Promise<Scope> => {
    const v = await scopeForm.validateFields();
    return {
      projectIds: String(v.projectIds ?? "").split(",").map((s: string) => Number(s.trim())).filter(Boolean),
      branch: v.branch.trim(), commitId: v.commitId.trim(), system: v.system?.trim() || undefined, service: v.service?.trim() || undefined,
    };
  };

  const translate = async (save: boolean) => {
    setBusy(true);
    try {
      const result = await dslApi.translate(source, await scope(), save);
      setTranslation(result);
      if (save) {
        const created = result.saved.filter(s => !s.unchanged).length, unchanged = result.saved.filter(s => s.unchanged).length;
        message.success(`已保存 ${created} 个草稿${unchanged ? `，${unchanged} 个未变化（未产生新修订版）` : ""}；请在「业务流程」中审阅并确认`);
      }
    } catch (e) { message.error(errorMessage(e)); } finally { setBusy(false); }
  };

  const exportFlow = async () => {
    const v = await exportForm.validateFields();
    try {
      const rendered = await dslApi.render(v.flowId.trim(), v.revision);
      if (!rendered.text) { message.error(rendered.diagnostics.map(d => d.message).join("；") || "该流程不能用 DSL 表达"); return; }
      setSource(rendered.text);
      setTranslation(null);
      if (rendered.diagnostics.length) message.warning(rendered.diagnostics.map(d => d.message).join("；"));
    } catch (e) { message.error(errorMessage(e)); }
  };

  return (
    <Space direction="vertical" style={{ width: "100%" }} size="middle">
      <Typography.Title level={4} style={{ margin: 0 }}>DSL 工作台</Typography.Title>
      <Alert type="info" showIcon message="只声明调用的 API、依赖与验证；测试序列严格线性（不支持分支、循环、重试或并行）。"
        description="本版本以确定性规则转换：显式的值、引用、断言原样锁定；同名同类型依赖与唯一成功状态码由规则推导并标注来源；其余缺失项列为待补充，不会猜测。自然语言与数据层验证将在下一增量支持。轮询只允许用于在「JMeter 扩展与函数 → 轮询授权」中显式授权的 API。" />
      <Row gutter={16}>
        <Col xs={24} xl={12}>
          <Card size="small" title={<Space>场景 YAML {lint && <Tag color={errors ? "red" : "green"}>{errors ? `${errors} 个错误` : "语法通过"}</Tag>}</Space>}>
            <Input.TextArea value={source} onChange={e => setSource(e.target.value)} autoSize={{ minRows: 24, maxRows: 48 }} spellCheck={false}
              style={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", fontSize: 13, background: palette.bgSubtle }} />
            {lint && lint.scenarios.length > 0 && (
              <div style={{ marginTop: 8 }}>
                {lint.scenarios.map(s => (
                  <div key={s.id}><Typography.Text strong>{s.name}</Typography.Text> · {s.steps} 步 · 角色{" "}
                    {s.roles.map((r, i) => <Tag key={i} color={roleColor[r]}>{r}</Tag>)}</div>
                ))}
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card size="small" title="诊断" style={{ marginBottom: 12 }}>
            {lint ? <DiagnosticList items={lint.diagnostics} /> : <Empty description="正在检查…" />}
          </Card>
          <Card size="small" title="解析范围（固定分析版本）" style={{ marginBottom: 12 }}>
            <Form form={scopeForm} layout="vertical">
              <Row gutter={8}>
                <Col span={12}><Form.Item name="projectIds" label="项目 ID（逗号分隔，可空）"><Input placeholder="1,2" /></Form.Item></Col>
                <Col span={12}><Form.Item name="branch" label="分支" rules={[{ required: true }]}><Input placeholder="main" /></Form.Item></Col>
              </Row>
              <Form.Item name="commitId" label="提交（40 位）" rules={[{ required: true, pattern: /^[0-9a-fA-F]{40}$/, message: "需要完整提交号" }]}><Input /></Form.Item>
              <Row gutter={8}>
                <Col span={12}><Form.Item name="system" label="系统标识（可选）"><Input placeholder="shop" /></Form.Item></Col>
                <Col span={12}><Form.Item name="service" label="服务标识（可选）"><Input placeholder="order" /></Form.Item></Col>
              </Row>
              <Space>
                <Button icon={<PlayCircleOutlined />} loading={busy} disabled={errors > 0} onClick={() => void translate(false)}>转换预览</Button>
                <Button type="primary" icon={<SaveOutlined />} loading={busy} disabled={errors > 0} onClick={() => void translate(true)}>保存为草稿</Button>
              </Space>
            </Form>
          </Card>
          <Card size="small" title="从已有流程导出 DSL">
            <Form form={exportForm} layout="inline">
              <Form.Item name="flowId" rules={[{ required: true }]}><Input placeholder="flowId" style={{ width: 220 }} /></Form.Item>
              <Form.Item name="revision" rules={[{ required: true }]}><InputNumber min={1} placeholder="修订号" /></Form.Item>
              <Button icon={<ExportOutlined />} onClick={() => void exportFlow()}>导出</Button>
            </Form>
          </Card>
        </Col>
      </Row>
      {translation && (
        <Card size="small" title={<Space>转换结果 <Typography.Text type="secondary" style={{ fontSize: 12 }}>DSL 哈希 {translation.sourceHash.slice(0, 12)}</Typography.Text></Space>}>
          {translation.scenarios.length === 0 && <DiagnosticList items={translation.diagnostics} />}
          {translation.scenarios.map(s => <ScenarioView key={s.scenarioId} result={s} />)}
          {translation.saved.length > 0 && (
            <Alert type="success" showIcon message="已保存的草稿" description={translation.saved.map(s =>
              <div key={s.flowId}>{s.flowId} @ {s.revision}{s.unchanged ? "（未变化）" : ""}</div>)} />
          )}
        </Card>
      )}
    </Space>
  );
}
