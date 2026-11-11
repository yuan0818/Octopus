import { projectOptions } from '../../components/projectOptions';
import { useState, useRef, useEffect } from 'react';
import {
  Card, Input, Button, Tag, Typography, Space, Divider, List, Collapse, Select,
  Row, Col, Alert, Spin, App, Upload, Empty, Badge,
} from 'antd';
import {
  RobotOutlined, UserOutlined,
  CheckCircleOutlined, CloseCircleOutlined,
  CodeOutlined, UploadOutlined,
  WarningOutlined, InfoCircleOutlined, ThunderboltOutlined,
  ApiOutlined, FilterOutlined, HistoryOutlined, ClockCircleOutlined,
  FileTextOutlined, SendOutlined, CommentOutlined, CloudDownloadOutlined,
} from '@ant-design/icons';
import type { UploadProps } from 'antd';
import { reviewApi } from '../../api/review';
import { adminApi } from '../../api/admin';
import { queryApi } from '../../api/query';
import { useBranchCommit } from '../../components/branchCommitState';
import BranchCommitInlineSelector from '../../components/BranchCommitInlineSelector';
import type { ReviewResponse, RequirementCheck, CodeIssue, RequirementItem } from '../../types';
import { palette, severityColor } from '../../theme';

const { Text, Paragraph } = Typography;
const { Panel } = Collapse;


export default function ReviewPage() {
  const { message } = App.useApp();
  const { branch, commitId, setBranch, setCommitId } = useBranchCommit();
  const [projectId, setProjectId] = useState<number>();
  const [classId, setClassId] = useState<number>();
  const [className, setClassName] = useState('');
  const [methodName, setMethodName] = useState('');
  const [strategy, setStrategy] = useState('BFS');
  const [requirements, setRequirements] = useState<RequirementItem[]>([]);
  const [reqForm, setReqForm] = useState({ id: '', title: '', desc: '' });
  const [docPath, setDocPath] = useState<string | undefined>();
  const [loading, setLoading] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'system', content: '欢迎使用 Code Review Agent。请选择项目和入口方法；填写需求时按需求审查，不填写需求时按链路风险审查。', timestamp: new Date() },
  ]);
  const [historyFindings, setHistoryFindings] = useState<any[]>([]);
  const [historySessions, setHistorySessions] = useState<any[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyExpanded, setHistoryExpanded] = useState(false);

  // ── Dynamic data ──
  const [projects, setProjects] = useState<any[]>([]);
  const [classes, setClasses] = useState<any[]>([]);
  const [methods, setMethods] = useState<any[]>([]);
  const [entryMode, setEntryMode] = useState<'endpoints' | 'entries' | 'all'>('endpoints');

  // ── Config state ──
  const [skills, setSkills] = useState<any[]>([]);
  const [selectedSkillId, setSelectedSkillId] = useState<string>('');
  const [models, setModels] = useState<any[]>([]);
  const [selectedModelName, setSelectedModelName] = useState<string>('');

  // ── 追问（混合形态对话层）──
  const [chatInput, setChatInput] = useState('');
  const [chatThreadId, setChatThreadId] = useState<string>('');
  const [chatLoading, setChatLoading] = useState(false);
  const chatInputRef = useRef<any>(null);
  /** 最近一次审查结果（作为追问上下文传给后端） */
  const lastReviewRef = useRef<any>(null);

  // ── 需求源（Jira 等工单拉取）──
  const [reqSources, setReqSources] = useState<any[]>([]);
  const [reqSource, setReqSource] = useState<string>('jira');
  const [reqTicket, setReqTicket] = useState('');
  const [reqFetching, setReqFetching] = useState(false);
  const [reqOrigin, setReqOrigin] = useState<string>('');

  useEffect(() => {
    reviewApi.fetchRequirementSources()
      .then(d => setReqSources(Array.isArray(d) ? d : []))
      .catch(() => setReqSources([]));
  }, []);

  const chatEndRef = useRef<HTMLDivElement>(null);
  useEffect(() => { chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  // ── Load on mount ──
  useEffect(() => {
    Promise.all([
      queryApi.projects().then(r => r.data).catch(() => []),
      adminApi.getSkills().catch(() => []),
      adminApi.getModelConfigs().catch(() => []),
    ]).then(([p, s, m]) => {
      setProjects(Array.isArray(p) ? p : []);
      setSkills(Array.isArray(s) ? s : []);
      if (Array.isArray(s) && s.length > 0) setSelectedSkillId(s[0].id);
      const enabledModels = Array.isArray(m) ? m.filter((x: any) => x.enabled) : [];
      setModels(enabledModels);
      if (enabledModels.length > 0) setSelectedModelName(enabledModels[0].name);
    });
  }, []);

  // ── Load history sessions when project changes ──
  useEffect(() => {
    if (!projectId) { setHistorySessions([]); return; }
    setHistoryLoading(true);
    reviewApi.getSessions(String(projectId))
      .then(d => setHistorySessions(Array.isArray(d) ? d : []))
      .catch(() => setHistorySessions([]))
      .finally(() => setHistoryLoading(false));
  }, [projectId]);

  // ── Load classes when project or mode changes ──
  useEffect(() => {
    if (!projectId) return;
    const loader = entryMode === 'endpoints'
      ? reviewApi.listEndpointClasses(projectId, undefined, branch, commitId)
      : entryMode === 'entries'
        ? reviewApi.listEntryClasses(projectId, undefined, branch, commitId)
      : reviewApi.listClasses(projectId, undefined, branch, commitId);
    loader.then(d => setClasses(Array.isArray(d) ? d : [])).catch(() => {});
    setClassId(undefined); setClassName(''); setMethods([]); setMethodName('');
     
  }, [projectId, entryMode, branch, commitId]);

  // ── Load methods when class changes ──
  useEffect(() => {
    if (!classId) return;
    const cls = classes.find(c => c.id === classId);
    setClassName(cls?.qualified_name || '');
    const loader = entryMode === 'endpoints'
      ? reviewApi.listEndpointMethods(classId, undefined, branch, commitId)
      : entryMode === 'entries'
        ? reviewApi.listEntryMethods(classId, undefined, branch, commitId)
      : reviewApi.listMethods(classId, undefined, branch, commitId);
    loader.then(d => setMethods(Array.isArray(d) ? d : [])).catch(() => {});
    setMethodName('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId, entryMode, branch, commitId]);

  // ── Add requirement ──
  const addRequirement = () => {
    if (!reqForm.id.trim() || !reqForm.title.trim()) { message.warning('请输入编号和标题'); return; }
    setRequirements([...requirements, { id: reqForm.id.trim(), title: reqForm.title.trim(), description: reqForm.desc.trim() }]);
    setReqForm({ id: '', title: '', desc: '' });
  };

  // ── Upload doc to backend, use docPath in review ──
  const handleFileUpload: UploadProps['beforeUpload'] = async (file) => {
    try {
      const { savedPath, originalName } = await reviewApi.upload(projectId || 0, file);
      setDocPath(savedPath);
      message.success(`文档已上传: ${originalName}`);
    } catch (e: any) {
      message.error('上传失败: ' + (e.message || '网络错误'));
    }
    return false;
  };

  const reviewMode = requirements.length > 0 || docPath ? 'REQUIREMENT_AWARE' : 'RISK_BASED';

  // ── Check readiness ──
  const isReady = className && methodName && selectedSkillId && selectedModelName && projectId;

  // ── Validate then directly review (no preview) ──
  const startReview = async () => {
    if (!isReady) { message.warning('请填写完整信息'); return; }

    // Validate first
    try {
      const validation = await reviewApi.validate({ projectId, className, methodName, strategy, requirements, skillId: selectedSkillId, modelName: selectedModelName, docPath, reviewMode, branch, commitId });
      if (!validation.valid) {
        message.error(validation.errors.join('; '));
        return;
      }
      if (validation.warnings?.length) {
        validation.warnings.forEach(w => message.info(w));
      }
    } catch (e: any) {
      message.error(e.message);
      return;
    }

    // Directly start review
    await handleReview();
  };

  // ── Submit review ──
  const handleReview = async () => {
    setLoading(true);
    setMessages(prev => [...prev, {
      role: 'user',
      content: `审查 ${className}.${methodName} (${reviewMode === 'REQUIREMENT_AWARE' ? '按需求审查' : '无需求风险审查'}，使用 ${skills.find(s => s.id === selectedSkillId)?.name || selectedSkillId})`,
      timestamp: new Date(),
    }]);

    try {
      const result = await reviewApi.review({
        projectId: projectId!, className, methodName, strategy, requirements, mode: 'SYNC', skillId: selectedSkillId, modelName: selectedModelName, docPath, reviewMode, branch, commitId,
      });
      lastReviewRef.current = result;
      // 新审查开启新追问线程
      setChatThreadId(`chat-${Date.now()}`);

      setMessages(prev => [...prev, {
        role: 'assistant',
        content: reviewMode === 'REQUIREMENT_AWARE'
          ? `审查完成！通过 ${result.summary.passed}/${result.summary.totalRequirements} 项需求`
          : `审查完成！发现 ${result.issues?.length ?? 0} 个链路风险`,
        timestamp: new Date(),
        data: result,
      }]);

      reviewApi.getFindings(String(projectId), className).then(setHistoryFindings).catch(() => {});
    } catch (e: any) {
      setMessages(prev => [...prev, { role: 'error', content: `审查失败: ${e.message || '未知错误'}`, timestamp: new Date() }]);
    } finally {
      setLoading(false);
    }
  };

  // ── 追问发送 ──
  const canChat = !loading && !chatLoading && !!lastReviewRef.current && !!projectId && !!className && !!methodName;

  const sendChat = async (overrideQuestion?: string) => {
    const question = (overrideQuestion ?? chatInput).trim();
    if (!question || !canChat) return;
    setChatInput('');
    setChatLoading(true);
    setMessages(prev => [...prev, { role: 'user', content: question, timestamp: new Date() }]);
    try {
      const last = lastReviewRef.current;
      const reviewResult = JSON.stringify({
        summary: last?.summary,
        issues: (last?.issues ?? []).slice(0, 10).map((i: any) => ({
          id: i.id, title: i.title, category: i.category, severity: i.severity,
          confidence: i.confidence, summary: i.summary, suggestion: i.suggestion,
        })),
      });
      const resp = await reviewApi.chat({
        threadId: chatThreadId || undefined,
        question,
        projectId, className, methodName, strategy,
        branch, commitId,
        skillId: selectedSkillId, modelName: selectedModelName,
        reviewResult,
      });
      setChatThreadId(resp.threadId);
      setMessages(prev => [...prev, { role: 'assistant', content: resp.answer, timestamp: new Date() }]);
    } catch (e: any) {
      setMessages(prev => [...prev, { role: 'error', content: `追问失败: ${e.message || '未知错误'}`, timestamp: new Date() }]);
    } finally {
      setChatLoading(false);
    }
  };

  /** issue 卡片的「追问」：带该 issue 上下文预填输入框 */
  const askAboutIssue = (issue: any) => {
    setChatInput(`关于 issue ${issue?.id ?? ''}「${issue?.title ?? ''}」：`);
    setTimeout(() => {
      chatInputRef.current?.focus?.();
    }, 50);
  };

  /** 从工单系统拉取需求并填充（requirements 非空后自动切换按需求审查） */
  const fetchRequirements = async () => {
    const ticket = reqTicket.trim();
    if (!reqSource || !ticket) return;
    setReqFetching(true);
    try {
      const resp = await reviewApi.fetchRequirement(reqSource, ticket);
      setRequirements(resp.items ?? []);
      setDocPath(undefined);
      setReqOrigin(`${reqSource.toUpperCase()} ${resp.ticketKey}（${resp.items?.length ?? 0} 条）`);
    } catch (e: any) {
      setReqOrigin('');
      setMessages(prev => [...prev, { role: 'error', content: `需求拉取失败: ${e.message || '未知错误'}`, timestamp: new Date() }]);
    } finally {
      setReqFetching(false);
    }
  };

  // ── Render helpers ──
  const renderCheck = (check: RequirementCheck) => (
    <div key={check.requirementId} style={{ background: check.passed ? palette.successBg : palette.errorBg, borderRadius: 8, padding: '12px 16px', marginBottom: 8, border: `1px solid ${check.passed ? palette.successBorder : palette.errorBorder}` }}>
      <Space>
        {check.passed ? <CheckCircleOutlined style={{ color: palette.success, fontSize: 18 }} /> : <CloseCircleOutlined style={{ color: palette.error, fontSize: 18 }} />}
        <Text strong>{check.requirementId}: {check.requirementTitle}</Text>
        <Tag color={check.confidence === 'HIGH' ? 'green' : check.confidence === 'MEDIUM' ? 'orange' : 'default'}>{check.confidence}</Tag>
      </Space>
      <Paragraph style={{ margin: '8px 0 0', color: palette.textSecondary }}>{check.detail}</Paragraph>
      {check.highlightedCode && (
        <pre className="code-block">
          <code>{check.highlightedCode}</code>
        </pre>
      )}
      {check.codeReferences?.map((ref, i) => <Tag key={i} icon={<CodeOutlined />}>{ref}</Tag>)}
      {check.suggestion && <Alert type="info" showIcon icon={<InfoCircleOutlined />} message={check.suggestion} style={{ marginTop: 8, background: palette.primaryBg, border: 'none' }} />}
    </div>
  );

  const renderIssue = (issue: CodeIssue) => (
    <div key={issue.description} style={{ background: palette.bgContainer, borderRadius: 8, padding: '12px 16px', marginBottom: 8, border: `1px solid ${severityColor[issue.severity] || palette.border}44` }}>
      <Space><Badge color={severityColor[issue.severity] || palette.textTertiary} /><Tag color={severityColor[issue.severity] || 'default'}>{issue.severity}</Tag><Tag>{issue.category}</Tag></Space>
      <Paragraph style={{ margin: '8px 0 0', color: palette.text }}>{issue.description}</Paragraph>
      {issue.highlightedCode && (
        <pre className="code-block">
          <code>{issue.highlightedCode}</code>
        </pre>
      )}
      {issue.codeReferences?.map((ref, i) => <Tag key={i} icon={<CodeOutlined />}>{ref}</Tag>)}
      {issue.suggestion && <Alert type="success" showIcon icon={<CheckCircleOutlined />} message={issue.suggestion} style={{ marginTop: 8, background: palette.successBg, border: 'none' }} />}
      <Button
        size="small"
        type="text"
        icon={<CommentOutlined />}
        style={{ marginTop: 8, color: palette.primary, padding: 0 }}
        onClick={() => askAboutIssue({ id: issue.codeReferences?.[0] ?? '', title: issue.description })}
      >
        追问此问题
      </Button>
    </div>
  );

  return (
    <div className="page-container">
    <Row gutter={16} style={{ height: 'calc(100vh - 100px)' }}>
      {/* ── Left panel ── */}
      <Col xs={24} lg={8} style={{ height: '100%' }}>
        <Card size="small" style={{ height: '100%', overflow: 'auto' }} styles={{ body: { padding: 20 } }}>

          {/* Agent 配置：Skill + 模型 */}
          <div className="section-title no-divider"><RobotOutlined /> Agent 配置</div>
          <div className="form-field">
            <span className="form-label">审查 Skill</span>
            <Select style={{ width: '100%' }} value={selectedSkillId} onChange={setSelectedSkillId}
              options={skills.map(s => ({ value: s.id, label: `${s.name} (${s.id})` }))}
              placeholder="选择审查 Skill"
              notFoundContent={<span>暂无 Skill，请先在 Agent 配置中创建 <Button size="small" type="link" onClick={() => window.location.href = '/admin'}>去配置</Button></span>}
            />
          </div>
          <div className="form-field">
            <span className="form-label">模型</span>
            <Select style={{ width: '100%' }} value={selectedModelName || undefined} onChange={setSelectedModelName}
              options={models.map((m: any) => ({ value: m.name, label: `${m.name} (${m.provider} / ${m.modelId})` }))}
              placeholder="选择模型"
              notFoundContent={<span>暂无模型，请先在 Agent 配置中添加 <Button size="small" type="link" onClick={() => window.location.href = '/admin'}>去配置</Button></span>}
            />
          </div>

          <div className="section-title"><ApiOutlined /> 审查入口</div>

          {/* Mode toggle: API-first entry selection */}
          <div style={{ marginBottom: 12 }}>
            <Space.Compact size="small" style={{ width: '100%' }}>
              <Button type={entryMode === 'endpoints' ? 'primary' : 'default'} style={{ flex: 1 }}
                icon={<ApiOutlined />} onClick={() => setEntryMode('endpoints')}>
                API 端点
              </Button>
              <Button type={entryMode === 'entries' ? 'primary' : 'default'} style={{ flex: 1 }}
                icon={<ClockCircleOutlined />} onClick={() => setEntryMode('entries')}>
                业务入口
              </Button>
              <Button type={entryMode === 'all' ? 'primary' : 'default'} style={{ flex: 1 }}
                icon={<FilterOutlined />} onClick={() => setEntryMode('all')}>
                任意方法
              </Button>
            </Space.Compact>
          </div>

          {/* Project selector */}
          <div className="form-field">
            <span className="form-label">项目</span>
            <Select style={{ width: '100%' }} value={projectId}
              onChange={(v) => {
                setProjectId(v);
                const p = projects.find((x) => x.id === v);
                if (p) {
                  // 切项目时用项目自带 branch/commitId 作初值，避免残留上一项目的版本选择
                  setBranch(p.branch);
                  setCommitId(p.commitId);
                }
              }}
              placeholder="选择项目"
              options={projectOptions(entryMode === 'endpoints' ? projects.filter(p => p.isApiService) : projects)}
              showSearch optionFilterProp="label"
              notFoundContent={
                entryMode === 'endpoints'
                  ? '暂无 API 服务项目'
                  : entryMode === 'entries'
                    ? '暂无包含业务入口的已分析项目'
                    : '暂无已分析的项目'
              }
              allowClear
            />
            <div style={{ marginTop: 6 }}>
              <BranchCommitInlineSelector
                projectId={projectId ?? null}
                branch={branch}
                commitId={commitId}
                onBranchChange={setBranch}
                onCommitChange={setCommitId}
                fallbackBranch={projects.find(p => p.id === projectId)?.branch}
                fallbackCommitId={projects.find(p => p.id === projectId)?.commitId}
                width={140}
              />
            </div>
          </div>

          {/* Class selector (depends on project) */}
          <div className="form-field">
            <span className="form-label">类名</span>
            <Select style={{ width: '100%' }} value={classId} onChange={(v) => setClassId(v)}
              placeholder={projectId ? "选择类" : "请先选择项目"}
              options={classes.map(c => ({ value: c.id, label: `${c.simple_name} (${c.qualified_name})` }))}
              showSearch optionFilterProp="label"
              notFoundContent={projectId ? "未找到类" : ""}
              disabled={!projectId}
              allowClear
            />
          </div>

          {/* Method selector (depends on class) */}
          <div className="form-field">
            <span className="form-label">方法名</span>
            <Space.Compact style={{ width: '100%' }}>
              <Select style={{ width: 'calc(100% - 80px)' }} value={methodName || undefined} onChange={(v) => setMethodName(v || '')}
                placeholder={classId ? "选择方法" : "请先选择类"}
                options={methods.map(m => ({ value: m.method_name, label: `${m.method_name}(...) → ${m.return_type || 'void'}` }))}
                showSearch optionFilterProp="label"
                notFoundContent="未找到方法"
                disabled={!classId}
                allowClear
              />
              <Select value={strategy} onChange={setStrategy} style={{ width: 80 }}
                options={[{ value: 'BFS', label: 'BFS' }, { value: 'DFS', label: 'DFS' }]} />
            </Space.Compact>
          </div>

          {/* Requirements */}
          <div className="section-title">
            <FileTextOutlined /> 需求来源
            <Tag color={reviewMode === 'REQUIREMENT_AWARE' ? 'blue' : 'gold'} style={{ marginLeft: 'auto' }}>
              {reviewMode === 'REQUIREMENT_AWARE' ? '按需求审查' : '无需求风险审查'}
            </Tag>
          </div>

          {/* 工单拉取：选工具 + 填单号（未配置的源禁用并提示去配置） */}
          <Space.Compact style={{ width: '100%' }}>
            <Select size="small" style={{ width: 110 }} value={reqSource} onChange={setReqSource}
              options={reqSources.map(s => ({
                value: s.sourceType,
                label: s.configured ? s.displayName : `${s.displayName}（未配置）`,
                disabled: !s.configured,
              }))}
              notFoundContent="无已注册需求源" />
            <Input size="small" placeholder="工单号，如 PROJ-123" value={reqTicket}
              onChange={e => setReqTicket(e.target.value)} onPressEnter={fetchRequirements} />
            <Button size="small" icon={<CloudDownloadOutlined />} loading={reqFetching}
              onClick={fetchRequirements} disabled={!reqSource || !reqTicket.trim()}>
              拉取需求
            </Button>
          </Space.Compact>
          {reqOrigin && (
            <Alert type="success" showIcon style={{ marginTop: 6 }}
              message={`已从 ${reqOrigin} 拉取，按需求审查`}
              closable onClose={() => setReqOrigin('')} />
          )}

          <Space style={{ justifyContent: 'space-between', width: '100%', margin: '8px 0' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {requirements.length > 0 || docPath ? (docPath ? '📄 已上传需求文档' : `已添加 ${requirements.length} 条需求`) : '未填写需求，将按链路风险审查'}
            </Text>
            <Upload beforeUpload={handleFileUpload} showUploadList={false} accept=".md,.txt,.pdf">
              <Button size="small" icon={<UploadOutlined />}>{docPath ? '重新上传' : '上传文档'}</Button>
            </Upload>
          </Space>

          {docPath ? (
            <Alert type="success" showIcon message="已上传需求文档，审查时将自动解析"
              closable onClose={() => { setDocPath(undefined); }}
              style={{ margin: '8px 0' }} />
          ) : (
            <>
              <Input size="small" placeholder="编号 REQ-001" value={reqForm.id}
                onChange={e => setReqForm({ ...reqForm, id: e.target.value })} style={{ marginTop: 6 }} />
              <Input size="small" placeholder="标题" value={reqForm.title}
                onChange={e => setReqForm({ ...reqForm, title: e.target.value })} style={{ marginTop: 4 }} />
              <Input size="small" placeholder="描述（可选）" value={reqForm.desc}
                onChange={e => setReqForm({ ...reqForm, desc: e.target.value })} style={{ marginTop: 4 }} />
              <Button size="small" block onClick={addRequirement} style={{ margin: '4px 0 8px' }}>+ 添加需求</Button>
            </>
          )}

          {requirements.length > 0 && !docPath ? (
            <List size="small" dataSource={requirements} renderItem={(item, idx) => (
              <List.Item actions={[<Button key="del" size="small" danger type="text" onClick={() => setRequirements(requirements.filter((_, i) => i !== idx))}>删除</Button>]}>
                <List.Item.Meta title={<Text code>{item.id}: {item.title}</Text>} description={item.description || <Text type="secondary">无描述</Text>} />
              </List.Item>
            )} style={{ maxHeight: 150, overflow: 'auto', marginBottom: 12 }} />
          ) : !docPath && (
            <Empty description="暂无需求，将按当前入口链路做风险审查" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ margin: '8px 0' }} />
          )}

          <Button type="primary" size="large" icon={<ThunderboltOutlined />} block onClick={startReview} loading={loading} disabled={!isReady} style={{ marginTop: 12 }}>
            {loading ? '审查中...' : '开始审查'}
          </Button>
        </Card>
      </Col>

      {/* ── Right panel: Chat area ── */}
      <Col xs={24} lg={16} style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        <Card size="small" style={{ flex: 1, display: 'flex', flexDirection: 'column' }} styles={{ body: { flex: 1, padding: 0, display: 'flex', flexDirection: 'column' } }}>

          {/* History sessions panel */}
          {projectId && (
            <Collapse
              activeKey={historyExpanded ? ['history'] : []}
              onChange={(keys) => setHistoryExpanded(keys.includes('history'))}
              ghost size="small"
              style={{ borderBottom: `1px solid ${palette.borderSubtle}` }}
              items={[{
                key: 'history',
                label: <Space><HistoryOutlined /><Text type="secondary" style={{ fontSize: 13 }}>审查历史 ({historySessions.length})</Text></Space>,
                children: historyLoading ? (
                  <div style={{ textAlign: 'center', padding: 16 }}><Spin size="small" /></div>
                ) : historySessions.length === 0 ? (
                  <Empty description="暂无审查记录" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ margin: '8px 0' }} />
                ) : (
                  <div style={{ maxHeight: 240, overflow: 'auto' }}>
                    {historySessions.map((s: any) => (
                      <div
                        key={s.id}
                        style={{
                          padding: '8px 12px', marginBottom: 4, borderRadius: 6,
                          border: `1px solid ${palette.borderSubtle}`, cursor: 'pointer',
                          background: palette.bgSubtle, transition: 'background 0.15s',
                        }}
                        onClick={() => {
                          // Load session report into chat
                          try {
                            const report = typeof s.fullReport === 'string' ? JSON.parse(s.fullReport) : s.fullReport;
                            if (report) {
                              setMessages(prev => [...prev, {
                                role: 'assistant',
                                content: `[历史] ${s.anchorClass?.split('.').pop() || ''}#${s.anchorMethod} — ${s.summary || ''}`,
                                timestamp: new Date(s.createdAt),
                                data: report,
                              }]);
                            }
                          } catch { /* 历史会话读取失败时不打断当前对话 */ }
                        }}
                        onMouseEnter={e => (e.currentTarget.style.background = palette.primaryBg)}
                        onMouseLeave={e => (e.currentTarget.style.background = palette.bgSubtle)}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 2 }}>
                          <Text strong style={{ fontSize: 13 }}>
                            {s.anchorClass?.split('.').pop() || '?'}#{s.anchorMethod}
                          </Text>
                          <Text type="secondary" style={{ fontSize: 11 }}>
                            <ClockCircleOutlined /> {s.createdAt ? new Date(s.createdAt).toLocaleString() : '-'}
                          </Text>
                        </div>
                        <Space size="small" style={{ fontSize: 12 }}>
                          <Tag color={s.status === 'COMPLETED' ? 'green' : s.status === 'FAILED' ? 'red' : 'default'}>{s.status}</Tag>
                          <Text type="secondary">{s.summary || '-'}</Text>
                          {s.model && <Tag>{s.model}</Tag>}
                          {s.durationMs > 0 && <Text type="secondary">{(s.durationMs / 1000).toFixed(1)}s</Text>}
                        </Space>
                      </div>
                    ))}
                  </div>
                ),
              }]}
            />
          )}

          <div style={{ flex: 1, overflow: 'auto', padding: 20 }}>
            {messages.map((msg, idx) => {
              const isAssistant = msg.role === 'assistant';
              const avatarStyle = isAssistant ? { background: `linear-gradient(135deg, ${palette.primary}, ${palette.primaryLight})` }
                : msg.role === 'user' ? { background: `linear-gradient(135deg, ${palette.success}, ${palette.successLight})` }
                : msg.role === 'error' ? { background: palette.error }
                : { background: palette.textTertiary };
              const avatarIcon = isAssistant ? <RobotOutlined />
                : msg.role === 'user' ? <UserOutlined />
                : msg.role === 'error' ? <WarningOutlined />
                : <InfoCircleOutlined />;
              const roleLabel = isAssistant ? 'Review Agent' : msg.role === 'user' ? '你' : msg.role === 'error' ? '错误' : '系统';
              return (
                <div key={idx} className="chat-message">
                  <div className="chat-avatar" style={avatarStyle}>{avatarIcon}</div>
                  <div className={`chat-bubble ${msg.role}`}>
                    <div className="chat-meta">
                      <span style={{ fontWeight: 500, color: 'rgba(0,0,0,0.65)' }}>{roleLabel}</span>
                      <span>{msg.timestamp.toLocaleTimeString()}</span>
                    </div>
                    {msg.role === 'error' && <Alert type="error" message={msg.content} showIcon style={{ marginBottom: msg.data ? 12 : 0 }} />}
                    {msg.role !== 'assistant' && msg.role !== 'error' && <Text>{msg.content}</Text>}

                    {msg.data && (
                      <div>
                        <Alert type={msg.data.summary.failed > 0 ? 'warning' : 'success'} showIcon
                          message={<Space wrap>
                            <Text strong>审查摘要</Text>
                            <Tag color={msg.data.reviewMode === 'RISK_BASED' ? 'gold' : 'blue'}>
                              {msg.data.reviewMode === 'RISK_BASED' ? '无需求风险审查' : '按需求审查'}
                            </Tag>
                            {msg.data.reviewMode !== 'RISK_BASED' && <Tag color="green">通过 {msg.data.summary.passed}/{msg.data.summary.totalRequirements}</Tag>}
                            {msg.data.reviewMode !== 'RISK_BASED' && msg.data.summary.failed > 0 && <Tag color="red">失败 {msg.data.summary.failed}</Tag>}
                            {msg.data.reviewMode !== 'RISK_BASED' && msg.data.summary.uncertain > 0 && <Tag color="orange">待确认 {msg.data.summary.uncertain}</Tag>}
                            {msg.data.reviewMode === 'RISK_BASED' && <Tag color="orange">风险 {msg.data.issues?.length ?? 0}</Tag>}
                            <Tag color={severityColor[msg.data.summary.highestSeverity]}>最高风险: {msg.data.summary.highestSeverity}</Tag>
                          </Space>}
                          style={{ marginBottom: 12 }}
                        />
                        {msg.data.requirementChecks?.length > 0 && (
                          <Collapse ghost defaultActiveKey={['checks']} style={{ marginBottom: 12 }}>
                            <Panel header={`需求检查 (${msg.data.requirementChecks.length})`} key="checks">
                              {msg.data.requirementChecks.map(renderCheck)}
                            </Panel>
                          </Collapse>
                        )}
                        {msg.data.issues?.length > 0 && (
                          <Collapse ghost defaultActiveKey={['issues']}>
                            <Panel header={`代码问题 (${msg.data.issues.length})`} key="issues">
                              {msg.data.issues.map(renderIssue)}
                            </Panel>
                          </Collapse>
                        )}
                        <div style={{ marginTop: 12, fontSize: 12, color: palette.textTertiary }}>
                          <Space split={<Divider type="vertical" />}>
                            <span>会话: {msg.data.sessionId}</span>
                            <span>涉及: {msg.data.involvedClasses} 类 / {msg.data.totalMethods} 方法</span>
                            {msg.data.truncated && <Tag color="orange">已截断</Tag>}
                          </Space>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
            <div ref={chatEndRef} />
          </div>

          {/* 追问输入区：结构化审查完成后激活（混合形态的对话层） */}
          <div style={{ borderTop: `1px solid ${palette.borderSubtle}`, padding: '10px 16px', background: palette.bgSubtle }}>
            <Input.TextArea
              ref={chatInputRef}
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); sendChat(); } }}
              placeholder={lastReviewRef.current ? '基于本次审查追问，如：详细解释第二个风险的影响路径？Enter 发送，Shift+Enter 换行' : '完成一次审查后可在此追问'}
              autoSize={{ minRows: 1, maxRows: 4 }}
              disabled={!canChat}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {chatThreadId ? `追问线程 ${chatThreadId}` : '首条追问将开启新线程'}
              </Text>
              <Button
                type="primary"
                size="small"
                icon={<SendOutlined />}
                loading={chatLoading}
                disabled={!canChat || !chatInput.trim()}
                onClick={() => sendChat()}
              >
                追问
              </Button>
            </div>
          </div>

          {historyFindings.length > 0 && (
            <div style={{ borderTop: `1px solid ${palette.borderSubtle}`, padding: '8px 16px', background: palette.bgSubtle, maxHeight: 80, overflow: 'auto' }}>
              <Text type="secondary" style={{ fontSize: 12 }}>📋 该类历史审查发现: {historyFindings.slice(-3).map((f: any) => f.findingText).join('; ')}</Text>
            </div>
          )}
        </Card>
      </Col>
    </Row>
    </div>
  );
}

interface ChatMessage {
  role: 'user' | 'assistant' | 'system' | 'error';
  content: string;
  timestamp: Date;
  data?: ReviewResponse;
}
