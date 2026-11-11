import { Alert, Space, Tag, Typography } from "antd";
import type { ChainFailure } from "../../api/projectContext";
import { failureStages } from "./chainRecoveryModel";

export default function ChainFailureNotice({ failure }: { failure: ChainFailure }) {
  return <Alert type={failure.code === "WORKER_INTERRUPTED" ? "warning" : "error"} showIcon message={failure.message} description={<>
    <Space wrap><Tag>{failureStages[failure.stage] || failure.stage}</Tag><Tag>{failure.code}</Tag>{failure.upstreamStatus && <Tag>上游 HTTP {failure.upstreamStatus}</Tag>}</Space>
    {failure.bindingIssue && <div>
      <p>参数 <Typography.Text code>{failure.bindingIssue.input}</Typography.Text> 需要 {failure.bindingIssue.expectedType}
        {failure.bindingIssue.contextKey && <>；引用 <Typography.Text code>{failure.bindingIssue.contextKey}</Typography.Text></>}
        {failure.bindingIssue.actualType ? `，类型为 ${failure.bindingIssue.actualType}` : "；模型引用的上下文未声明"}</p>
      <p>类型匹配的可用上下文：{failure.bindingIssue.availableContextKeys.length
        ? failure.bindingIssue.availableContextKeys.map(key => <Tag key={key}>{key}</Tag>) : "暂无，请补充业务数据或使用预处理组装请求"}</p>
    </div>}
    <p>{failure.suggestion}</p>
    <Space wrap><span>发生于 {new Date(failure.occurredAt).toLocaleString()}</span><Typography.Text copyable={{ text: failure.diagnosticId }}>诊断编号 {failure.diagnosticId}</Typography.Text></Space>
  </>} />;
}
