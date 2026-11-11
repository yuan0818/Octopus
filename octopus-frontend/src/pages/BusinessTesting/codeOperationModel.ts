import type { Flow, Operation, Port } from "../../api/businessTesting";
import type { CatalogItem } from "./apiCatalogModel";
import { defaultValue, refOf } from "./workbenchModel.ts";

export const httpMethods = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
const canonical = (value: unknown): string => {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.entries(value)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, entry]) => `${JSON.stringify(key)}:${canonical(entry)}`).join(",")}}`;
  return JSON.stringify(value);
};
const digest = async (value: unknown) => [...new Uint8Array(await crypto.subtle.digest(
  "SHA-256", new TextEncoder().encode(canonical(value)),
))].map((byte) => byte.toString(16).padStart(2, "0")).join("");
const identifier = (name: string) => {
  const value = name.replace(/[^A-Za-z0-9_.-]/g, "_").slice(0, 100);
  return /^[A-Za-z_]/.test(value) ? value : `project_${value}`;
};

/** A pinned reference to one known API, with no guessed request/response contract. */
export async function codeOperation(item: CatalogItem, method = item.method): Promise<Operation> {
  const endpoint = item.endpoint;
  if (!endpoint?.projectId || !endpoint.branch || !/^[a-fA-F0-9]{40}$/.test(endpoint.commitId || "") || !endpoint.signature)
    throw new Error("该 API 缺少完整分析版本，暂时无法固定步骤引用。");
  if (!httpMethods.includes(method) || (endpoint.httpMethod !== "*" && method !== endpoint.httpMethod))
    throw new Error("请选择该 API 声明支持的 HTTP 方法。");
  const system = identifier(item.rootName);
  const service = identifier(item.moduleName);
  const signature = endpoint.signature.replace(/\s+/g, "");
  const route = await digest([endpoint.projectId, method, endpoint.path, signature]);
  const revision = await digest([endpoint.branch, endpoint.commitId, system, service]);
  const inputs: Record<string, Port> = {};
  for (const match of endpoint.path.matchAll(/\{([^}]+)\}/g)) {
    inputs[`path.${match[1]}`] = { location: "path", name: match[1], type: "string", required: true, prefix: null };
  }
  return {
    operationId: `analysis.p${endpoint.projectId}.api_${route.slice(0, 24)}`,
    revision: `code_${revision.slice(0, 32)}`,
    system, service, method, path: endpoint.path, inputs,
    requestSchema: null, responseSchema: null,
    implementations: [{ projectId: endpoint.projectId, branch: endpoint.branch, commitId: endpoint.commitId!, signature }],
    source: { kind: "ANALYZED_CODE", location: signature, digest: await digest([route, revision]) },
    requiresCapabilities: ["http", "json"], securityProfile: null,
  };
}

/** Human-supplied test configuration is a new immutable revision of the same API. */
export async function configureOperation(base: Operation, inputs: Record<string, Port>, status: number): Promise<Operation> {
  if (!Number.isInteger(status) || status < 100 || status > 599) throw new Error("请填写预期响应状态码。");
  const configured = {
    ...base, inputs,
    requestSchema: { ...(base.requestSchema || { type: "object" }), "x-octopus-parameters": inputs },
    responseSchema: { ...(base.responseSchema || {}), [status]: base.responseSchema?.[status] || { type: "object" } },
    source: { kind: "HUMAN", location: "api-test-configuration", digest: "" },
  };
  const hash = await digest({ ...configured, revision: "" });
  return { ...configured, revision: `test_${hash.slice(0, 32)}`, source: { ...configured.source, digest: hash } };
}

/** Rebind the current draft, preserving its revision, business assertions and data dependencies. */
export function replaceFlowOperation(flow: Flow, previous: Operation, next: Operation): Flow {
  const affected = new Set(flow.nodes.filter((node) => node.operationRef === refOf(previous)).map((node) => node.id));
  if (!affected.size) return flow;
  const keepInputs = (inputs: Flow["nodes"][number]["inputs"]) => Object.fromEntries(
    Object.entries(inputs).filter(([key]) => key in next.inputs),
  );
  return {
    ...flow,
    nodes: flow.nodes.map((node) => !affected.has(node.id) ? node : {
      ...node, operationRef: refOf(next), inputs: {
        ...Object.fromEntries(Object.entries(next.inputs).filter(([, port]) => port.required)
          .map(([key, port]) => [key, { source: "literal", value: defaultValue(port.type) }])),
        ...keepInputs(node.inputs),
      },
    }),
    testPoints: flow.testPoints.map((point) => ({ ...point, inputOverrides: Object.fromEntries(
      Object.entries(point.inputOverrides).map(([id, values]) => [id, affected.has(id) ? keepInputs(values) : values]),
    ) })),
  };
}
