import { projectOptions } from '../../components/projectOptions';
import { useState, useEffect } from 'react';
import {
  Card, Table, Tag, Space, Select, App, Typography, Drawer, Descriptions,
  Collapse, Alert, Badge, Divider, Row, Col, Statistic, Tooltip,
} from 'antd';
import {
  HistoryOutlined, CheckCircleOutlined, CloseCircleOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { reviewApi } from '../../api/review';
import { queryApi } from '../../api/query';
import { useBranchCommit } from '../../components/branchCommitState';
import BranchCommitInlineSelector from '../../components/BranchCommitInlineSelector';
import type { ReviewResponse, RequirementCheck, CodeIssue } from '../../types';
import { palette, severityColor } from '../../theme';

const { Text, Paragraph, Title } = Typography;
const { Panel } = Collapse;


const statusColor: Record<string, string> = {
  COMPLETED: 'green', FAILED: 'red', PENDING: 'default', IN_PROGRESS: 'processing',
};

export default function ReviewHistoryPage() {
  const { message } = App.useApp();
  const { branch, commitId, setBranch, setCommitId } = useBranchCommit();
  const [projects, setProjects] = useState<any[]>([]);
  const [projectId, setProjectId] = useState<string>();
  const [statusFilter, setStatusFilter] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [sessions, setSessions] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const [classes, setClasses] = useState<any[]>([]);
  const [selectedClassId, setSelectedClassId] = useState<number>();
  const [selectedClassName, setSelectedClassName] = useState<string>();
  const [methods, setMethods] = useState<any[]>([]);
  const [selectedMethod, setSelectedMethod] = useState<string>();

  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailSession, setDetailSession] = useState<any>(null);
  const [detailReport, setDetailReport] = useState<ReviewResponse | null>(null);
  const [detailFindings, setDetailFindings] = useState<any[]>([]);

  useEffect(() => {
    queryApi.projects().then(r => setProjects(Array.isArray(r.data) ? r.data : [])).catch(() => {});
  }, []);

  useEffect(() => {
    if (!projectId) { setClasses([]); setSelectedClassId(undefined); setSelectedClassName(undefined); setMethods([]); setSelectedMethod(undefined); return; }
    reviewApi.listClasses(Number(projectId), undefined, branch, commitId).then(d => setClasses(Array.isArray(d) ? d : [])).catch(() => setClasses([]));
    setSelectedClassId(undefined); setSelectedClassName(undefined); setMethods([]); setSelectedMethod(undefined);
     
  }, [projectId, branch, commitId]);

  useEffect(() => {
    if (!selectedClassId) { setMethods([]); setSelectedMethod(undefined); return; }
    reviewApi.listMethods(selectedClassId, undefined, branch, commitId).then(d => setMethods(Array.isArray(d) ? d : [])).catch(() => setMethods([]));
    setSelectedMethod(undefined);
     
  }, [selectedClassId, branch, commitId]);

  const loadSessions = () => {
    if (!projectId) return;
    setLoading(true);
    reviewApi.getSessionsPaged(projectId, {
      status: statusFilter || undefined,
      anchorClass: selectedClassName || undefined,
      page,
      size: pageSize,
      commitId,
    })
      .then(d => {
        setSessions(d.content || []);
        setTotal(d.totalElements || 0);
      })
      .catch(() => message.error('加载审查历史失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { loadSessions(); }, [projectId, statusFilter, selectedClassName, page, pageSize, branch, commitId]);

  const columns: ColumnsType<any> = [
    {
      title: '会话 ID', dataIndex: 'id', width: 220, ellipsis: true,
      render: (v: string) => <Text code style={{ fontSize: 11 }}>{v}</Text>,
    },
    {
      title: '入口', dataIndex: 'anchorClass', ellipsis: true,
      render: (v: string, r: any) => (
        <Tooltip title={`${v}#${r.anchorMethod}`}>
          <Text style={{ fontSize: 13 }}>
            <Text strong>{v?.split('.').pop() || v}</Text>
            <Text type="secondary">#{r.anchorMethod}</Text>
          </Text>
          <br />
          <Text type="secondary" style={{ fontSize: 11 }}>{v}</Text>
        </Tooltip>
      ),
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => <Tag color={statusColor[v] || 'default'}>{v}</Tag>,
    },
    {
      title: '摘要', dataIndex: 'summary', width: 140, ellipsis: true,
      render: (v: string) => <Text type="secondary">{v || '-'}</Text>,
    },
    {
      title: '模型', dataIndex: 'model', width: 120, ellipsis: true,
      render: (v: string) => v ? <Tag>{v}</Tag> : '-',
    },
    {
      title: '耗时', dataIndex: 'durationMs', width: 80,
      render: (v: number) => v > 0 ? `${(v / 1000).toFixed(1)}s` : '-',
    },
    {
      title: 'Tokens', width: 120,
      render: (_: any, r: any) => r.totalTokens > 0 ? (
        <Text type="secondary" style={{ fontSize: 12 }}>{r.promptTokens}+{r.completionTokens}</Text>
      ) : '-',
    },
    {
      title: '时间', dataIndex: 'createdAt', width: 170,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    {
      title: '操作', width: 80, fixed: 'right',
      render: (_: any, r: any) => (
        <a onClick={() => openDetail(r)}>详情</a>
      ),
    },
  ];

  const openDetail = async (session: any) => {
    setDetailOpen(true);
    setDetailLoading(true);
    setDetailSession(session);
    try {
      const full = await reviewApi.getSessionDetail(session.id);
      const report = typeof full.fullReport === 'string' ? JSON.parse(full.fullReport) : full.fullReport;
      setDetailReport(report);
      const findings = await reviewApi.getSessionFindings(session.id);
      setDetailFindings(Array.isArray(findings) ? findings : []);
    } catch {
      message.error('加载会话详情失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const renderCheck = (check: RequirementCheck) => (
    <div key={check.requirementId} style={{ background: check.passed ? palette.successBg : palette.errorBg, borderRadius: 8, padding: '10px 14px', marginBottom: 8, border: `1px solid ${check.passed ? palette.successBorder : palette.errorBorder}` }}>
      <Space>
        {check.passed ? <CheckCircleOutlined style={{ color: palette.success }} /> : <CloseCircleOutlined style={{ color: palette.error }} />}
        <Text strong>{check.requirementId}: {check.requirementTitle}</Text>
        <Tag color={check.confidence === 'HIGH' ? 'green' : check.confidence === 'MEDIUM' ? 'orange' : 'default'}>{check.confidence}</Tag>
      </Space>
      <Paragraph style={{ margin: '6px 0 0', color: palette.textSecondary }}>{check.detail}</Paragraph>
      {check.highlightedCode && (
        <pre style={{ margin: '6px 0', padding: '8px 12px', background: palette.codeBg, color: palette.codeText, borderRadius: 6, fontSize: 12, lineHeight: 1.5, overflowX: 'auto', fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          <code>{check.highlightedCode}</code>
        </pre>
      )}
      {check.suggestion && <Alert type="info" showIcon message={check.suggestion} style={{ marginTop: 6 }} />}
    </div>
  );

  const renderIssue = (issue: CodeIssue) => (
    <div key={issue.description} style={{ background: palette.bgContainer, borderRadius: 8, padding: '10px 14px', marginBottom: 8, border: `1px solid ${severityColor[issue.severity] || palette.border}44` }}>
      <Space><Badge color={severityColor[issue.severity] || palette.textTertiary} /><Tag color={severityColor[issue.severity] || 'default'}>{issue.severity}</Tag><Tag>{issue.category}</Tag></Space>
      <Paragraph style={{ margin: '6px 0 0', color: palette.text }}>{issue.description}</Paragraph>
      {issue.highlightedCode && (
        <pre style={{ margin: '6px 0', padding: '8px 12px', background: palette.codeBg, color: palette.codeText, borderRadius: 6, fontSize: 12, lineHeight: 1.5, overflowX: 'auto', fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          <code>{issue.highlightedCode}</code>
        </pre>
      )}
      {issue.suggestion && <Alert type="success" showIcon message={issue.suggestion} style={{ marginTop: 6 }} />}
    </div>
  );

  return (
    <div className="page-container">
      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            style={{ width: 240 }}
            placeholder="选择项目"
            value={projectId}
            onChange={(v) => {
              setProjectId(v);
              setPage(0);
              const p = projects.find((x) => String(x.id) === v);
              if (p) {
                // 切项目时用项目自带 branch/commitId 作初值，避免残留上一项目的版本选择
                setBranch(p.branch);
                setCommitId(p.commitId);
              }
            }}
            showSearch
            optionFilterProp="label"
            options={projectOptions(projects).map(group => ({ ...group, options: group.options.map(option => ({ ...option, value: String(option.value) })) }))}
            allowClear
            onClear={() => setProjectId(undefined)}
          />
          <BranchCommitInlineSelector
            projectId={projectId ? Number(projectId) : null}
            branch={branch}
            commitId={commitId}
            onBranchChange={setBranch}
            onCommitChange={setCommitId}
            fallbackBranch={projects.find(p => String(p.id) === projectId)?.branch}
            fallbackCommitId={projects.find(p => String(p.id) === projectId)?.commitId}
          />
          <Select
            style={{ width: 320 }}
            placeholder="选择类"
            value={selectedClassId}
            onChange={(v) => {
              setSelectedClassId(v);
              const cls = classes.find(c => c.id === v);
              setSelectedClassName(cls?.qualified_name || undefined);
              setPage(0);
            }}
            showSearch
            optionFilterProp="label"
            options={classes.map(c => ({ value: c.id, label: `${c.simple_name} (${c.qualified_name})` }))}
            disabled={!projectId}
            allowClear
            onClear={() => { setSelectedClassId(undefined); setSelectedClassName(undefined); setMethods([]); setSelectedMethod(undefined); }}
          />
          <Select
            style={{ width: 200 }}
            placeholder="选择方法"
            value={selectedMethod}
            onChange={(v) => { setSelectedMethod(v); setPage(0); }}
            showSearch
            optionFilterProp="label"
            options={methods.map(m => ({ value: m.method_name, label: `${m.method_name}(...)` }))}
            disabled={!selectedClassId}
            allowClear
            onClear={() => setSelectedMethod(undefined)}
          />
          <Select
            style={{ width: 130 }}
            placeholder="状态"
            value={statusFilter}
            onChange={(v) => { setStatusFilter(v); setPage(0); }}
            allowClear
            options={[
              { value: 'COMPLETED', label: '已完成' },
              { value: 'FAILED', label: '失败' },
              { value: 'PENDING', label: '等待中' },
              { value: 'IN_PROGRESS', label: '进行中' },
            ]}
          />
        </Space>
      </Card>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={sessions}
        loading={loading}
        size="small"
        scroll={{ x: 1400 }}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPage(p - 1); if (s) setPageSize(s); },
        }}
        onRow={(record) => ({
          onClick: () => openDetail(record),
          style: { cursor: 'pointer' },
        })}
      />

      <Drawer
        title={detailSession ? (
          <Space>
            <HistoryOutlined />
            <span>{detailSession.anchorClass?.split('.').pop() || '?'}#{detailSession.anchorMethod}</span>
            <Tag color={statusColor[detailSession.status]}>{detailSession.status}</Tag>
          </Space>
        ) : '会话详情'}
        open={detailOpen}
        onClose={() => { setDetailOpen(false); setDetailReport(null); setDetailFindings([]); }}
        width={720}
        destroyOnHidden
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Text type="secondary">加载中...</Text></div>
        ) : (
          <>
            <Descriptions column={2} size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="会话 ID" span={2}><Text code style={{ fontSize: 11 }}>{detailSession?.id}</Text></Descriptions.Item>
              <Descriptions.Item label="入口类">{detailSession?.anchorClass}</Descriptions.Item>
              <Descriptions.Item label="入口方法">{detailSession?.anchorMethod}</Descriptions.Item>
              <Descriptions.Item label="策略">{detailSession?.strategy}</Descriptions.Item>
              <Descriptions.Item label="模型">{detailSession?.model || '-'}</Descriptions.Item>
              <Descriptions.Item label="耗时">{detailSession?.durationMs > 0 ? `${(detailSession.durationMs / 1000).toFixed(1)}s` : '-'}</Descriptions.Item>
              <Descriptions.Item label="时间">{detailSession?.createdAt ? new Date(detailSession.createdAt).toLocaleString() : '-'}</Descriptions.Item>
              <Descriptions.Item label="Tokens">{detailSession?.totalTokens > 0 ? `Prompt: ${detailSession.promptTokens}, Completion: ${detailSession.completionTokens}, Total: ${detailSession.totalTokens}` : '-'}</Descriptions.Item>
              <Descriptions.Item label="摘要" span={2}>{detailSession?.summary || '-'}</Descriptions.Item>
            </Descriptions>

            {detailReport && (
              <>
                {detailReport.summary && (
                  <Row gutter={16} style={{ marginBottom: 16 }}>
                    <Col span={6}><Statistic title="总需求" value={detailReport.summary.totalRequirements} /></Col>
                    <Col span={6}><Statistic title="通过" value={detailReport.summary.passed} valueStyle={{ color: palette.success }} /></Col>
                    <Col span={6}><Statistic title="失败" value={detailReport.summary.failed} valueStyle={{ color: palette.error }} /></Col>
                    <Col span={6}><Statistic title="待确认" value={detailReport.summary.uncertain} valueStyle={{ color: palette.warning }} /></Col>
                  </Row>
                )}

                {detailReport.requirementChecks?.length > 0 && (
                  <Collapse ghost defaultActiveKey={['checks']} style={{ marginBottom: 16 }}>
                    <Panel header={<Text strong>需求检查 ({detailReport.requirementChecks.length})</Text>} key="checks">
                      {detailReport.requirementChecks.map(renderCheck)}
                    </Panel>
                  </Collapse>
                )}

                {detailReport.issues?.length > 0 && (
                  <Collapse ghost defaultActiveKey={['issues']} style={{ marginBottom: 16 }}>
                    <Panel header={<Text strong>代码问题 ({detailReport.issues.length})</Text>} key="issues">
                      {detailReport.issues.map(renderIssue)}
                    </Panel>
                  </Collapse>
                )}

                <div style={{ fontSize: 12, color: palette.textTertiary }}>
                  <Space split={<Divider type="vertical" />}>
                    <span>涉及: {detailReport.involvedClasses} 类 / {detailReport.totalMethods} 方法</span>
                    {detailReport.truncated && <Tag color="orange">已截断</Tag>}
                  </Space>
                </div>
              </>
            )}

            {!detailReport && detailSession?.fullReport && (
              <pre style={{ background: palette.bgHover, padding: 12, borderRadius: 6, fontSize: 12, maxHeight: 400, overflow: 'auto' }}>
                {typeof detailSession.fullReport === 'string' ? detailSession.fullReport : JSON.stringify(detailSession.fullReport, null, 2)}
              </pre>
            )}

            {!detailReport && !detailSession?.fullReport && (
              <Alert type="info" showIcon message="该会话没有详细的审查报告" />
            )}

            {detailFindings.length > 0 && (
              <>
                <Divider />
                <Title level={5}>审查发现 ({detailFindings.length})</Title>
                {detailFindings.map((f: any, i: number) => (
                  <div key={i} style={{ padding: '8px 12px', marginBottom: 6, background: palette.bgSubtle, borderRadius: 6, border: `1px solid ${palette.borderSubtle}` }}>
                    <Space>
                      {f.passed === true ? <CheckCircleOutlined style={{ color: palette.success }} /> :
                       f.passed === false ? <CloseCircleOutlined style={{ color: palette.error }} /> :
                       <InfoCircleOutlined style={{ color: palette.textTertiary }} />}
                      {f.severity && <Tag color={severityColor[f.severity] || 'default'}>{f.severity}</Tag>}
                      {f.category && <Tag>{f.category}</Tag>}
                    </Space>
                    <Paragraph style={{ margin: '4px 0 0', fontSize: 13 }}>{f.findingText}</Paragraph>
                    {f.suggestion && <Text type="secondary" style={{ fontSize: 12 }}>建议: {f.suggestion}</Text>}
                  </div>
                ))}
              </>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
