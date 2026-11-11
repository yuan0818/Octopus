import RepositoryVersionPicker from "./RepositoryVersionPicker";
import { useEffect, useState } from "react";
import { Alert, Button, Drawer, Input, InputNumber, Select, Switch, Tabs, Tag, Upload } from "antd";
import { CloudUploadOutlined, DeleteOutlined, PlusOutlined, UploadOutlined } from "@ant-design/icons";
import { businessTesting as api, type Operation, type Port } from "../../api/businessTesting";
import { Field } from "./FormControls";
import { errorText, typeOptions, uid } from "./workbenchModel";
import { configureOperation } from "./codeOperationModel";

export interface VersionSelection {
  projectId: number | null;
  branch: string;
  baseCommitId: string;
  targetCommitId: string;
  repositoryKey: string;
  scopeProjectIds?: number[];
}
export function VersionPicker(props: {
  value: VersionSelection;
  onChange: (patch: Partial<VersionSelection>) => void;
  compare?: boolean;
}) {
  return <RepositoryVersionPicker value={props.value} onChange={props.onChange} />;
}
export default function ImportPanel({
  open,
  onClose,
  onImported,
  initialOperation,
  onConfigured,
}: {
  open: boolean;
  onClose: () => void;
  onImported: (count: number) => Promise<void>;
  initialOperation?: Operation | null;
  onConfigured?: (previous: Operation, next: Operation) => void;
}) {
  const [mode, setMode] = useState("file");
  const [system, setSystem] = useState("business");
  const [service, setService] = useState("api");
  const [document, setDocument] = useState<Record<string, unknown> | null>(
    null,
  );
  const [filename, setFilename] = useState("");
  const [raw, setRaw] = useState("");
  const [method, setMethod] = useState("GET");
  const [path, setPath] = useState("");
  const [status, setStatus] = useState<number | null>(200);
  const [params, setParams] = useState<(Port & { key: string })[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!open) return;
    setMode(initialOperation ? "manual" : "file");
    setSystem(initialOperation?.system || "business");
    setService(initialOperation?.service || "api");
    setMethod(initialOperation?.method || "GET");
    setPath(initialOperation?.path || "");
    const response = Object.keys(initialOperation?.responseSchema || {}).find((key) => /^\d{3}$/.test(key));
    setStatus(response ? Number(response) : initialOperation ? null : 200);
    setParams(Object.entries(initialOperation?.inputs || {}).map(([key, port]) => ({ ...port, key })));
    setError("");
  }, [open, initialOperation]);
  const preview = Object.entries(
    (document?.paths || {}) as Record<string, Record<string, unknown>>,
  ).flatMap(([p, entry]) =>
    Object.keys(entry && typeof entry === "object" ? entry : {})
      .filter((m) =>
        ["get", "post", "put", "patch", "delete", "head", "options"].includes(
          m,
        ),
      )
      .map((m) => ({ method: m.toUpperCase(), path: p })),
  );
  const parse = (text: string, name: string) => {
    try {
      const d = JSON.parse(text);
      if (
        !String(d.openapi).startsWith("3.") ||
        !d.paths ||
        typeof d.paths !== "object"
      )
        throw new Error("请选择 OpenAPI 3 格式的接口文档（JSON 文件）。");
      setDocument(d);
      setFilename(name);
      setError("");
    } catch (e) {
      setDocument(null);
      setError(errorText(e));
    }
  };
  const submit = async () => {
    setError("");
    setBusy(true);
    try {
      if (
        !/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(system) ||
        !/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(service)
      )
        throw new Error(
          "系统和服务标识请使用英文字母、数字、点、横线或下划线，并以字母开头。",
        );
      let imported: Operation[];
      if (mode === "manual") {
        if (status === null) throw new Error("请填写预期响应状态码");
        if (!path.startsWith("/") || /[?#]/.test(path))
          throw new Error("填写以 / 开头的接口路径，查询参数请在下面添加。");
        if (params.some((p) => !p.name.trim()))
          throw new Error("请填写每个参数的名称");
        if (new Set(params.map((p) => `${p.location}.${p.name}`)).size !== params.length)
          throw new Error("同一位置不能有重复参数");
        const inputs = Object.fromEntries(
          params.map((p) => [
            initialOperation?.inputs[p.key]?.name === p.name &&
            initialOperation.inputs[p.key].location === p.location
              ? p.key
              : `${p.location}.${p.name}`,
            {
              location: p.location,
              name: p.name,
              type: p.type,
              required: p.required,
              prefix: p.prefix || null,
            },
          ]),
        );
        if (Object.keys(inputs).length !== params.length)
          throw new Error("同一位置不能有重复参数");
        imported = [
          await api.saveOperation(initialOperation ? await configureOperation(initialOperation, inputs, status) : {
            operationId: `${system}.${service}.${uid("api")}`,
            revision: "v1",
            system,
            service,
            method,
            path,
            inputs,
            requestSchema: { type: "object" },
            responseSchema: { [status]: { type: "object" } },
            implementations: [],
            source: {
              kind: "HUMAN",
              location: "visual-api-editor",
              digest: "",
            },
            requiresCapabilities: ["http", "json"],
            securityProfile: null,
          }),
        ];
      } else {
        if (!document || !preview.length)
          throw new Error("先上传含有接口定义的 OpenAPI 3 文件");
        imported = await api.importOpenApi({
          system,
          service,
          document,
          implementations: {},
        });
      }
      if (initialOperation) onConfigured?.(initialOperation, imported[0]);
      await onImported(imported.length);
      onClose();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={680}
      title={initialOperation ? "接口测试配置" : "导入 / 新增外部 API"}
      destroyOnClose
      footer={
        <div className="bt-dialog-actions">
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={busy} onClick={() => void submit()}>
            {initialOperation ? "保存测试配置" : mode === "manual" ? "保存接口" : "导入接口"}
          </Button>
        </div>
      }
    >
      <p className="bt-lead">
        {initialOperation
          ? "为这个 API 补充本次测试需要的参数定义和预期响应。保存后应用到当前流程中引用此版本的步骤，已有业务断言保持不变。"
          : "已分析项目的 API 已自动进入目录，可直接添加步骤。这里只用于导入外部 OpenAPI 文档或手工新增接口。"}
      </p>
      <div className="bt-form-grid">
        <Field label="系统标识" hint="例如 account，用于归类接口">
          <Input disabled={!!initialOperation} value={system} onChange={(e) => setSystem(e.target.value)} />
        </Field>
        <Field label="服务标识" hint="例如 auth，运行时为此服务填写地址">
          <Input disabled={!!initialOperation} value={service} onChange={(e) => setService(e.target.value)} />
        </Field>
      </div>
      <Tabs
        activeKey={mode}
        onChange={(m) => {
          setMode(m);
          setError("");
        }}
        items={[
          {
            key: "file",
            label: (
              <>
                <UploadOutlined /> 上传接口文档
              </>
            ),
            children: (
              <>
                <Upload.Dragger
                  accept=".json,application/json"
                  showUploadList={false}
                  beforeUpload={(file) => {
                    void file.text().then((text) => parse(text, file.name));
                    return false;
                  }}
                >
                  <p className="ant-upload-drag-icon">
                    <CloudUploadOutlined />
                  </p>
                  <p className="ant-upload-text">拖入文件，或点击选择</p>
                  <p className="ant-upload-hint">
                    支持 OpenAPI 3 JSON · 自动读取请求参数和响应结构
                  </p>
                </Upload.Dragger>
                {document && (
                  <div className="bt-import-preview">
                    <strong>{filename}</strong>
                    <p>
                      识别到 {preview.length} 个接口，导入后可用于编排流程。
                    </p>
                    {preview.slice(0, 8).map((p) => (
                      <div key={p.method + p.path}>
                        <Tag color="blue">{p.method}</Tag>
                        <code>{p.path}</code>
                      </div>
                    ))}
                    {preview.length > 8 && (
                      <small>以及另外 {preview.length - 8} 个接口</small>
                    )}
                  </div>
                )}
                <details className="bt-advanced">
                  <summary>高级：粘贴文档内容</summary>
                  <Input.TextArea
                    aria-label="OpenAPI 文档内容"
                    rows={8}
                    value={raw}
                    onChange={(e) => setRaw(e.target.value)}
                  />
                  <Button onClick={() => parse(raw, "粘贴的接口文档")}>
                    解析文档
                  </Button>
                </details>
              </>
            ),
          },
          {
            key: "manual",
            label: initialOperation ? "测试配置" : "手动添加接口",
            children: (
              <>
                <div className="bt-form-grid">
                  <Field label="请求方法">
                    <Select
                      disabled={!!initialOperation}
                      value={method}
                      onChange={setMethod}
                      options={[
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "HEAD",
                        "OPTIONS",
                      ].map((value) => ({ value }))}
                    />
                  </Field>
                  <Field label="预期成功状态码">
                    <InputNumber
                      min={100}
                      max={599}
                      value={status}
                      onChange={setStatus}
                    />
                  </Field>
                </div>
                <Field
                  label="接口路径"
                  hint={initialOperation ? "已保留所选 API 的路径，服务地址在运行时设置" : "只填写路径，例如 /api/orders/{id}，服务地址在运行时设置"}
                >
                  <Input
                    placeholder="/api/orders/{id}"
                    disabled={!!initialOperation}
                    value={path}
                    onChange={(e) => setPath(e.target.value)}
                  />
                </Field>
                <h4>
                  请求参数 <small>（可选）</small>
                </h4>
                {params.map((p, i) => (
                  <div className="bt-param" key={p.key}>
                    <div className="bt-form-grid">
                      <Field label={`参数 ${i + 1} 名称`}>
                        <Input
                          value={p.name}
                          onChange={(e) =>
                            setParams((ps) =>
                              ps.map((v, j) =>
                                j === i ? { ...v, name: e.target.value } : v,
                              ),
                            )
                          }
                        />
                      </Field>
                      <Field label={`参数 ${i + 1} 位置`}>
                        <Select
                          value={p.location}
                          options={[
                            { value: "query", label: "查询参数" },
                            { value: "path", label: "路径参数" },
                            { value: "header", label: "请求头" },
                            { value: "body", label: "请求体字段" },
                          ]}
                          onChange={(location) =>
                            setParams((ps) =>
                              ps.map((v, j) =>
                                j === i
                                  ? {
                                      ...v,
                                      location,
                                      required:
                                        location === "path" || v.required,
                                    }
                                  : v,
                              ),
                            )
                          }
                        />
                      </Field>
                      <Field label={`参数 ${i + 1} 类型`}>
                        <Select
                          value={p.type}
                          options={typeOptions.filter(
                            (t) =>
                              p.location === "body" ||
                              !["object", "array"].includes(t.value),
                          )}
                          onChange={(type) =>
                            setParams((ps) =>
                              ps.map((v, j) => (j === i ? { ...v, type } : v)),
                            )
                          }
                        />
                      </Field>
                      <Field label={`参数 ${i + 1} 必填`}>
                        <Switch
                          checked={p.required}
                          disabled={p.location === "path"}
                          onChange={(required) =>
                            setParams((ps) =>
                              ps.map((v, j) =>
                                j === i ? { ...v, required } : v,
                              ),
                            )
                          }
                        />
                      </Field>
                    </div>
                    <Button
                      danger
                      type="text"
                      icon={<DeleteOutlined />}
                      onClick={() =>
                        setParams((ps) => ps.filter((_, j) => j !== i))
                      }
                    >
                      删除参数
                    </Button>
                  </div>
                ))}
                <Button
                  icon={<PlusOutlined />}
                  onClick={() =>
                    setParams((ps) => [
                      ...ps,
                      {
                        key: uid("param"),
                        name: "",
                        location: "query",
                        type: "string",
                        required: true,
                        prefix: null,
                      },
                    ])
                  }
                >
                  添加请求参数
                </Button>
                <p className="bt-muted">
                  {initialOperation?.implementations.length
                    ? "测试配置保留当前 API 的项目、分支、提交及实现关联，可继续用于变更回归。"
                    : "手工定义可以直接测试 HTTP / JSON 接口；与代码变更的关联需补充实现定位。"}
                </p>
              </>
            ),
          },
        ].filter((tab) => !initialOperation || tab.key === "manual")}
      />
      {error && (
        <Alert
          showIcon
          type="error"
          message={initialOperation ? "暂时无法保存测试配置" : "暂时无法导入接口"}
          description={error}
          style={{ marginTop: 16 }}
        />
      )}
    </Drawer>
  );
}
