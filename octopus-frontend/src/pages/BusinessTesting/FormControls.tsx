import {
  cloneElement,
  useId,
  useState,
  type ReactElement,
  type ReactNode,
} from "react";
import { Button, Input, InputNumber, Select, Switch } from "antd";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { defaultValue, typeOptions, valueType } from "./workbenchModel";

export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactElement;
}) {
  const id = useId();
  return (
    <div className="bt-field">
      <label htmlFor={id}>{label}</label>
      {cloneElement(children as ReactElement<Record<string, unknown>>, {
        id,
        "aria-label": label,
      })}
      {hint && <small>{hint}</small>}
    </div>
  );
}
export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description: string;
  action?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="bt-empty">
      <div className="bt-empty-icon">{icon}</div>
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </div>
  );
}
export function ValueInput({
  value,
  onChange,
  type = valueType(value),
  label = "测试值",
  depth = 0,
}: {
  value: unknown;
  onChange: (v: unknown) => void;
  type?: string;
  label?: string;
  depth?: number;
}) {
  const [newKey, setNewKey] = useState("");
  const [newType, setNewType] = useState("string");
  if (type === "boolean")
    return (
      <Switch
        aria-label={label}
        checked={value === true}
        checkedChildren="是"
        unCheckedChildren="否"
        onChange={onChange}
      />
    );
  if (type === "number" || type === "integer")
    return (
      <InputNumber
        aria-label={label}
        style={{ width: "100%" }}
        precision={type === "integer" ? 0 : undefined}
        value={typeof value === "number" ? value : 0}
        onChange={(v) => onChange(v ?? 0)}
      />
    );
  if (["object", "array"].includes(type)) {
    if (depth > 8) return <small>嵌套较深，请在高级定义中编辑。</small>;
    const arr = type === "array";
    const entries = arr
      ? (Array.isArray(value) ? value : []).map(
          (v, i) => [String(i), v] as const,
        )
      : Object.entries(
          value && typeof value === "object" && !Array.isArray(value)
            ? value
            : {},
        );
    const update = (k: string, v: unknown) =>
      onChange(
        arr
          ? entries.map(([key, old]) => (key === k ? v : old))
          : { ...(value as object), [k]: v },
      );
    return (
      <div className="bt-object">
        {entries.map(([k, v]) => (
          <div className="bt-object-row" key={k}>
            <span className="bt-field-key">{k}</span>
            <div>
              <ValueInput
                value={v}
                label={`${label}.${k}`}
                onChange={(next) => update(k, next)}
                depth={depth + 1}
              />
            </div>
            <Button
              type="text"
              danger
              aria-label={`删除字段 ${k}`}
              icon={<DeleteOutlined />}
              onClick={() =>
                onChange(
                  arr
                    ? entries.filter(([key]) => key !== k).map(([, old]) => old)
                    : Object.fromEntries(entries.filter(([key]) => key !== k)),
                )
              }
            />
          </div>
        ))}
        <div className="bt-inline">
          {!arr && (
            <Input
              aria-label={`${label}新增字段名`}
              placeholder="字段名"
              value={newKey}
              onChange={(e) => setNewKey(e.target.value)}
            />
          )}
          <Select
            aria-label={`${label}新增字段类型`}
            value={newType}
            options={typeOptions}
            onChange={setNewType}
            style={{ minWidth: 92 }}
          />
          <Button
            aria-label={`添加${label}字段`}
            icon={<PlusOutlined />}
            disabled={
              !arr &&
              (!newKey.trim() || entries.some(([k]) => k === newKey.trim()))
            }
            onClick={() => {
              onChange(
                arr
                  ? [...entries.map(([, v]) => v), defaultValue(newType)]
                  : {
                      ...(value as object),
                      [newKey.trim()]: defaultValue(newType),
                    },
              );
              setNewKey("");
            }}
          >
            添加{arr ? "项" : "字段"}
          </Button>
        </div>
      </div>
    );
  }
  return (
    <Input
      aria-label={label}
      value={typeof value === "string" ? value : String(value ?? "")}
      onChange={(e) => onChange(e.target.value)}
      placeholder="填写测试值"
    />
  );
}
