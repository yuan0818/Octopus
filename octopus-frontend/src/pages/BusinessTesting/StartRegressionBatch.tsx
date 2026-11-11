import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { App, Button, Select, Space, Typography } from "antd";
import { ThunderboltOutlined } from "@ant-design/icons";
import { regressionApi, errorMessage } from "../../api/regression";

/**
 * 「变更回归」结果 → 一键发起批量回归（Phase 1A），跳转到「回归批次」查看进度。
 * 目标版本应已部署到所选测试环境：批量回归测的是已部署的服务。
 */
export default function StartRegressionBatch({ projectIds, branch, baseCommitId, targetCommitId, affected }: {
  projectIds: number[]; branch: string; baseCommitId: string; targetCommitId: string; affected: number;
}) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [environment, setEnvironment] = useState("test");
  const [busy, setBusy] = useState(false);
  if (affected === 0) return null;
  const start = async () => {
    setBusy(true);
    try {
      const batch = await regressionApi.start({ projectIds, branch, baseCommitId, targetCommitId, environment });
      message.success("已发起批量回归");
      navigate(`/business-testing/regressions?id=${encodeURIComponent(batch.id)}`);
    } catch (e) {
      message.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Space wrap style={{ margin: "12px 0" }}>
      <Typography.Text>在</Typography.Text>
      <Select aria-label="执行环境" size="small" style={{ width: 120 }} value={environment} onChange={setEnvironment}
        options={["test", "staging"].map(v => ({ value: v, label: v }))} />
      <Typography.Text>环境</Typography.Text>
      <Button type="primary" icon={<ThunderboltOutlined />} loading={busy} onClick={() => void start()}>
        批量执行这 {affected} 个测试点
      </Button>
      <Typography.Text type="secondary">目标版本需已部署到该环境；无法自动执行的测试点会注明原因</Typography.Text>
    </Space>
  );
}
