import { useEffect, useState } from "react";
import { Alert, Button, Card, Descriptions, List, Space, Tag, Typography } from "antd";
import { RobotOutlined } from "@ant-design/icons";
import { regressionApi, errorMessage, type TriageView } from "../../api/regression";

const categoryLabel: Record<string, string> = {
  PRODUCT_DEFECT: "疑似产品缺陷",
  TEST_DATA: "测试数据 / 前置条件",
  ENVIRONMENT: "环境 / 基础设施",
  ORACLE_OUTDATED: "预期已过期",
  TEST_DEFINITION: "流程定义问题",
  FLAKY: "偶发不稳定",
  UNKNOWN: "证据不足",
};

/** Phase 1D：失败运行的 AI 分析（仅供参考，不改变判定）。 */
export default function RunTriagePanel({ runId, verdict, state }: { runId: string; verdict: string; state: string }) {
  const [view, setView] = useState<TriageView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const eligible = !["RUNNING", "QUEUED"].includes(state) && ["FAIL", "INCONCLUSIVE"].includes(verdict);

  useEffect(() => {
    let active = true;
    const load = eligible ? regressionApi.triage(runId) : Promise.resolve(null);
    load.then(v => { if (active) { setView(v); setError(null); } }).catch(() => undefined);
    return () => { active = false; };
  }, [runId, eligible]);

  if (!eligible) return null;
  const analyze = async () => {
    setLoading(true); setError(null);
    try { setView(await regressionApi.runTriage(runId)); } catch (e) { setError(errorMessage(e)); } finally { setLoading(false); }
  };
  return (
    <Card size="small" style={{ marginTop: 12 }}
      title={<Space><RobotOutlined />AI 失败分析<Tag>仅供参考，不改变判定</Tag></Space>}
      extra={<Button size="small" loading={loading} onClick={() => void analyze()}>{view ? "重新分析" : "分析失败原因"}</Button>}>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 8 }} />}
      {!view && !error && <Typography.Text type="secondary">根据采样摘要、断言定义与变更证据给出可能原因；不会发送请求/响应正文或凭据。</Typography.Text>}
      {view && (
        <>
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="类别">{categoryLabel[view.triage.category] || view.triage.category}</Descriptions.Item>
            <Descriptions.Item label="置信度">{view.triage.confidence}</Descriptions.Item>
          </Descriptions>
          <Typography.Paragraph>{view.triage.summary}</Typography.Paragraph>
          {view.triage.failedChecks.length > 0 && (
            <List size="small" header="涉及的检查项" dataSource={view.triage.failedChecks}
              renderItem={c => <List.Item><Tag>{c.stepId}{c.assertionId ? ` / ${c.assertionId}` : ""}</Tag>{c.explanation}</List.Item>} />
          )}
          {view.triage.suggestedActions.length > 0 && (
            <List size="small" header="建议下一步" dataSource={view.triage.suggestedActions} renderItem={a => <List.Item>{a}</List.Item>} />
          )}
          {view.triage.questions.length > 0 && (
            <List size="small" header="仍需确认" dataSource={view.triage.questions} renderItem={q => <List.Item>{q}</List.Item>} />
          )}
          {view.deterministicSignals.length > 0 && (
            <List size="small" header="平台规则信号" dataSource={view.deterministicSignals} renderItem={s => <List.Item><code>{s}</code></List.Item>} />
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>模型 {view.model || "-"} · {new Date(view.createdAt).toLocaleString()}</Typography.Text>
        </>
      )}
    </Card>
  );
}
