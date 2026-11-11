import { useCallback, useEffect, useState } from "react";
import { Alert, App, Button, Card, Col, Descriptions, Drawer, Form, Input, InputNumber, Popconfirm, Row, Select, Space, Switch, Table, Tabs, Tag, Typography, Upload } from "antd";
import { ReloadOutlined, UploadOutlined } from "@ant-design/icons";
import {
  extensionApi, functionApi, pollAuthorizationApi, errorMessage,
  type ExtensionSummary, type FunctionBrief, type PollAuthorization, type PollAuthorizationRequest,
} from "../../api/dsl";

const statusColor: Record<string, string> = { REJECTED: "red", VALIDATED: "blue", PUBLISHED: "green", DISABLED: "default" };
const statusText: Record<string, string> = { REJECTED: "未通过校验", VALIDATED: "已通过校验", PUBLISHED: "已发布", DISABLED: "已停用" };
const kindText: Record<string, string> = { PLATFORM: "平台工具", JMETER: "JMeter 内置", EXTENSION: "用户扩展" };
const determinismColor: Record<string, string> = { PURE: "green", CLOCK: "gold", RANDOM: "volcano" };

/** 扩展：上传 → 自动校验（JMeter 规范、元数据契约、安全扫描、隔离测试）→ 上传者直接发布（无人工审核）。 */
function ExtensionsTab() {
  const { message, modal } = App.useApp();
  const [items, setItems] = useState<ExtensionSummary[]>([]);
  const [publish, setPublish] = useState(true);
  const [docs, setDocs] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { setItems(await extensionApi.list()); } catch (e) { message.error(errorMessage(e)); }
  }, [message]);
  useEffect(() => { const t = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(t); }, [load]);

  const act = async (fn: () => Promise<ExtensionSummary>, ok: string) => {
    try { await fn(); message.success(ok); await load(); } catch (e) { message.error(errorMessage(e)); }
  };

  const deprecate = (id: string) => {
    let note = "";
    modal.confirm({
      title: "标记弃用", content: <Input.TextArea placeholder="弃用原因或替代版本" onChange={e => { note = e.target.value; }} />,
      onOk: () => act(() => extensionApi.deprecate(id, note), "已标记弃用"),
    });
  };

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      <Alert type="info" showIcon message="扩展必须是 JMeter 函数（实现 Function，类名含 .functions. 与版本段），并使用 octopus-jmeter-sdk 的 @OctopusTool 等注解声明元数据。"
        description="上传时只读取字节码与注解，不执行代码；示例在隔离的 JMeter 进程中执行。调用网络、执行进程等危险 API 会被拒绝；文件、反射等风险调用作为警告展示。运行时扩展与 JMeter 同进程，本期不做运行时隔离。" />
      <Space>
        <Upload accept=".jar" showUploadList={false} customRequest={({ file, onSuccess, onError }) => {
          extensionApi.upload(file as File, publish).then(r => {
            onSuccess?.(r);
            if (r.status === "REJECTED") message.error(`未通过校验：${r.issues.filter(i => i.severity === "ERROR").length} 个问题`);
            else message.success(r.status === "PUBLISHED" ? "已发布，可被 LLM 发现" : "已通过校验，可发布");
            void load();
          }).catch(e => { onError?.(e); message.error(errorMessage(e)); });
        }}>
          <Button type="primary" icon={<UploadOutlined />}>上传 JAR</Button>
        </Upload>
        <Space><Switch checked={publish} onChange={setPublish} /> 通过校验后直接发布</Space>
        <Button icon={<ReloadOutlined />} onClick={() => void load()} />
      </Space>
      <Table size="small" rowKey="id" dataSource={items} pagination={{ pageSize: 20 }}
        expandable={{ expandedRowRender: r => (
          <Space direction="vertical" style={{ width: "100%" }}>
            {r.tools.map(t => (
              <Descriptions key={t.className} size="small" bordered column={2} title={<Space><Typography.Text code>x.{t.name}@{t.version}</Typography.Text>{t.displayName}</Space>}>
                <Descriptions.Item label="用途" span={2}>{t.summary}</Descriptions.Item>
                <Descriptions.Item label="确定性"><Tag color={determinismColor[t.determinism]}>{t.determinism}</Tag></Descriptions.Item>
                <Descriptions.Item label="阶段">{t.stages.join("、")}</Descriptions.Item>
                <Descriptions.Item label="参数" span={2}>{t.params.map(p => <Tag key={p.name}>{p.name}: {p.type}{p.required ? "" : "?"}{p.sensitive ? "（敏感）" : ""}</Tag>)}</Descriptions.Item>
                <Descriptions.Item label="类" span={2}><Typography.Text type="secondary">{t.className} · {t.referenceKey}</Typography.Text></Descriptions.Item>
              </Descriptions>
            ))}
            {r.issues.length > 0 && <Table size="small" rowKey={(i, idx) => `${i.code}-${idx}`} pagination={false} dataSource={r.issues} columns={[
              { title: "级别", dataIndex: "severity", width: 90, render: (s: string) => <Tag color={s === "ERROR" ? "red" : "orange"}>{s}</Tag> },
              { title: "代码", dataIndex: "code", width: 200 },
              { title: "说明", dataIndex: "message" },
            ]} />}
          </Space>
        ) }}
        columns={[
          { title: "文件", dataIndex: "fileName" },
          { title: "状态", dataIndex: "status", render: (s: string, r) => <Space><Tag color={statusColor[s]}>{statusText[s] ?? s}</Tag>{r.deprecated && <Tag color="gold">已弃用</Tag>}</Space> },
          { title: "工具", render: (_: unknown, r) => r.tools.map(t => <Tag key={t.name + t.version}>{t.name}@{t.version}</Tag>) },
          { title: "问题", render: (_: unknown, r) => {
            const e = r.issues.filter(i => i.severity === "ERROR").length, w = r.issues.length - e;
            return <Space>{e > 0 && <Tag color="red">{e} 错误</Tag>}{w > 0 && <Tag color="orange">{w} 警告</Tag>}{e + w === 0 && "-"}</Space>;
          } },
          { title: "上传", render: (_: unknown, r) => <span>{r.uploadedBy} · {new Date(r.uploadedAt).toLocaleString()}</span> },
          { title: "操作", render: (_: unknown, r) => (
            <Space>
              {r.status === "VALIDATED" && <Button size="small" type="primary" onClick={() => void act(() => extensionApi.publish(r.id), "已发布")}>发布</Button>}
              {r.status === "PUBLISHED" && <Popconfirm title="停用后新的推导不再使用；已确认流程仍按固定版本运行" onConfirm={() => void act(() => extensionApi.disable(r.id), "已停用")}>
                <Button size="small">停用</Button></Popconfirm>}
              {r.status === "DISABLED" && <Button size="small" onClick={() => void act(() => extensionApi.enable(r.id), "已启用")}>启用</Button>}
              {(r.status === "PUBLISHED" || r.status === "DISABLED") && !r.deprecated && <Button size="small" onClick={() => deprecate(r.id)}>弃用</Button>}
              <Button size="small" onClick={() => extensionApi.docs(r.id).then(setDocs).catch(e => message.error(errorMessage(e)))}>文档</Button>
            </Space>
          ) },
        ]} />
      <Drawer open={docs != null} onClose={() => setDocs(null)} width={720} title="扩展文档（与 LLM 工具定义同源）">
        <pre style={{ whiteSpace: "pre-wrap", fontSize: 13 }}>{docs}</pre>
      </Drawer>
    </Space>
  );
}

/** 统一函数目录：平台工具、JMeter 内置函数白名单、已发布扩展；与 LLM 的 search/describe/try 工具同一数据源。 */
function CatalogTab() {
  const { message } = App.useApp();
  const [query, setQuery] = useState("");
  const [stage, setStage] = useState<string | undefined>();
  const [items, setItems] = useState<FunctionBrief[]>([]);
  const [detail, setDetail] = useState<{ name: string; describe: Record<string, unknown>; docs: string } | null>(null);
  const [trial, setTrial] = useState("{}");
  const [trialResult, setTrialResult] = useState<string | null>(null);

  const search = useCallback(async () => {
    try { setItems(await functionApi.search(query || undefined, stage)); } catch (e) { message.error(errorMessage(e)); }
  }, [query, stage, message]);
  useEffect(() => { const t = window.setTimeout(() => void search(), 300); return () => window.clearTimeout(t); }, [search]);

  const open = async (name: string) => {
    try {
      const [describe, docs] = await Promise.all([functionApi.describe(name), functionApi.docs(name)]);
      setDetail({ name, describe, docs });
      setTrial("{}");
      setTrialResult(null);
    } catch (e) { message.error(errorMessage(e)); }
  };

  const tryCall = async () => {
    if (!detail) return;
    try {
      const result = await functionApi.tryCall(detail.name, JSON.parse(trial));
      setTrialResult(JSON.stringify(result, null, 2));
    } catch (e) { setTrialResult(errorMessage(e)); }
  };

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      <Space wrap>
        <Input.Search placeholder="按用途检索，例如 签名、时间、编码" allowClear value={query} onChange={e => setQuery(e.target.value)} style={{ width: 320 }} />
        <Select allowClear placeholder="阶段" style={{ width: 200 }} value={stage} onChange={setStage}
          options={["REQUEST_VALUE", "REQUEST_TRANSFORM", "RESPONSE_DECODE", "EXPECTED_VALUE"].map(s => ({ value: s, label: s }))} />
      </Space>
      <Table size="small" rowKey="name" dataSource={items} pagination={{ pageSize: 20 }} onRow={r => ({ onClick: () => void open(r.name), style: { cursor: "pointer" } })}
        columns={[
          { title: "名称", dataIndex: "name", render: (n: string, r) => <Space><Typography.Text code>{n}</Typography.Text>{r.deprecated && <Tag color="gold">已弃用</Tag>}</Space> },
          { title: "类型", dataIndex: "kind", render: (k: string) => kindText[k] ?? k },
          { title: "用途", dataIndex: "summary" },
          { title: "确定性", dataIndex: "determinism", render: (d: string) => <Tag color={determinismColor[d]}>{d}</Tag> },
          { title: "阶段", dataIndex: "stages", render: (s: string[]) => s.map(x => <Tag key={x}>{x}</Tag>) },
        ]} />
      <Drawer open={detail != null} onClose={() => setDetail(null)} width={760} title={detail?.name}>
        {detail && (
          <Tabs items={[
            { key: "docs", label: "文档", children: <pre style={{ whiteSpace: "pre-wrap", fontSize: 13 }}>{detail.docs}</pre> },
            { key: "llm", label: "LLM 工具定义", children: <pre style={{ whiteSpace: "pre-wrap", fontSize: 12 }}>{JSON.stringify(detail.describe, null, 2)}</pre> },
            { key: "try", label: "试算", children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                <Typography.Text type="secondary">只能试算 PURE 条目，参数为普通字面量（不能使用敏感参数）。用户扩展在隔离进程中执行。</Typography.Text>
                <Input.TextArea rows={6} value={trial} onChange={e => setTrial(e.target.value)} style={{ fontFamily: "monospace" }} />
                <Button onClick={() => void tryCall()}>试算</Button>
                {trialResult && <pre style={{ whiteSpace: "pre-wrap" }}>{trialResult}</pre>}
              </Space>
            ) },
          ]} />
        )}
      </Drawer>
    </Space>
  );
}

/** FR-POLL：按 API 显式授权轮询；不提供批量授权，平台不推断可轮询性。 */
function PollAuthorizationTab() {
  const { message } = App.useApp();
  const [items, setItems] = useState<PollAuthorization[]>([]);
  const [form] = Form.useForm<PollAuthorizationRequest>();

  const load = useCallback(async () => {
    try { setItems(await pollAuthorizationApi.list()); } catch (e) { message.error(errorMessage(e)); }
  }, [message]);
  useEffect(() => { const t = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(t); }, [load]);

  const submit = async () => {
    const v = await form.validateFields();
    try { await pollAuthorizationApi.authorize(v); message.success("已授权该 API 可轮询"); form.resetFields(); await load(); }
    catch (e) { message.error(errorMessage(e)); }
  };

  return (
    <Row gutter={16}>
      <Col xs={24} lg={9}>
        <Card size="small" title="授权一个 API 可轮询">
          <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="仅对专门用于查询异步任务状态的 API 授权。"
            description="平台不会根据 HTTP 方法、只读标注或文档推断可轮询性。重复请求可能产生副作用；其他业务请求永远不会自动重试。" />
          <Form form={form} layout="vertical" initialValues={{ httpMethod: "GET", intervalMillis: 2000, maxAttempts: 30, timeoutMillis: 60000 }}>
            <Row gutter={8}>
              <Col span={10}><Form.Item name="projectId" label="项目 ID" rules={[{ required: true }]}><InputNumber min={1} style={{ width: "100%" }} /></Form.Item></Col>
              <Col span={14}><Form.Item name="httpMethod" label="方法" rules={[{ required: true }]}>
                <Select options={["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"].map(m => ({ value: m, label: m }))} /></Form.Item></Col>
            </Row>
            <Form.Item name="path" label="路径模板" rules={[{ required: true, pattern: /^\//, message: "以 / 开头" }]}><Input placeholder="/exports/{id}" /></Form.Item>
            <Form.Item name="signature" label="方法签名" rules={[{ required: true }]}><Input placeholder="com.shop.ExportController.status(long)" /></Form.Item>
            <Row gutter={8}>
              <Col span={8}><Form.Item name="intervalMillis" label="间隔 ms" rules={[{ required: true }]}><InputNumber min={200} max={30000} style={{ width: "100%" }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="maxAttempts" label="最多次数" rules={[{ required: true }]}><InputNumber min={2} max={120} style={{ width: "100%" }} /></Form.Item></Col>
              <Col span={8}><Form.Item name="timeoutMillis" label="总超时 ms" rules={[{ required: true }]}><InputNumber min={1000} max={300000} style={{ width: "100%" }} /></Form.Item></Col>
            </Row>
            <Form.Item name="reason" label="授权理由" rules={[{ required: true, max: 500 }]}><Input.TextArea rows={2} placeholder="例如：导出任务状态查询接口，只读且幂等" /></Form.Item>
            <Button type="primary" onClick={() => void submit()}>授权</Button>
          </Form>
        </Card>
      </Col>
      <Col xs={24} lg={15}>
        <Table size="small" rowKey="id" dataSource={items} pagination={{ pageSize: 20 }} columns={[
          { title: "API", render: (_: unknown, a: PollAuthorization) => <Space direction="vertical" size={0}><span>{a.httpMethod} {a.path}</span>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>项目 {a.projectId} · {a.signature}</Typography.Text></Space> },
          { title: "边界", render: (_: unknown, a: PollAuthorization) => `每 ${a.intervalMillis} ms · ≤ ${a.maxAttempts} 次 · ≤ ${a.timeoutMillis} ms` },
          { title: "修订", render: (_: unknown, a: PollAuthorization) => `${a.id.slice(0, 8)}@${a.revision}` },
          { title: "授权人", render: (_: unknown, a: PollAuthorization) => <span>{a.updatedBy} · {new Date(a.updatedAt).toLocaleString()}</span> },
          { title: "状态", render: (_: unknown, a: PollAuthorization) => a.revoked ? <Tag>已撤销</Tag> : <Tag color="green">有效</Tag> },
          { title: "", render: (_: unknown, a: PollAuthorization) => !a.revoked && (
            <Popconfirm title="撤销后，引用该授权的已确认流程将无法运行，需重新确认" onConfirm={() => pollAuthorizationApi.revoke(a.id).then(load).catch(e => message.error(errorMessage(e)))}>
              <Button size="small" danger>撤销</Button></Popconfirm>) },
        ]} />
      </Col>
    </Row>
  );
}

export default function ExtensionsPage() {
  return (
    <Space direction="vertical" style={{ width: "100%" }} size="middle">
      <Typography.Title level={4} style={{ margin: 0 }}>JMeter 扩展与函数</Typography.Title>
      <Tabs items={[
        { key: "extensions", label: "扩展", children: <ExtensionsTab /> },
        { key: "catalog", label: "函数目录", children: <CatalogTab /> },
        { key: "poll", label: "轮询授权", children: <PollAuthorizationTab /> },
      ]} />
    </Space>
  );
}
