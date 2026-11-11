import { projectOptions } from '../../components/projectOptions';
import LegacyNotice from '../../components/LegacyNotice';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, Form, Input, Select, Space, Typography, App, Tag } from 'antd';
import { SaveOutlined, ArrowLeftOutlined, ExperimentOutlined } from '@ant-design/icons';
import { scenarioApi, type RunReport } from '../../api/scenario';
import { queryApi } from '../../api/query';
import DagEditor, { type StepData } from './DagEditor';
import { stepsToDagJson, dagJsonToSteps } from './dagJson';
import { palette } from '../../theme';

const { Text } = Typography;

/**
 * 全屏场景编辑页（Dolphin 风格）：
 * 顶部 toolbar（名称/环境/保存） + 左组件面板 + 中画布（满高） + 右属性 Drawer
 */
export default function ScenarioEdit() {
  const { id } = useParams<{ id: string }>();
  const isNew = !id;
  const navigate = useNavigate();
  const { message } = App.useApp();

  const [form] = Form.useForm();
  const [dagSteps, setDagSteps] = useState<StepData[]>([]);
  const [projects, setProjects] = useState<any[]>([]);
  const [envProfiles, setEnvProfiles] = useState<any[]>([]);
  const [saving, setSaving] = useState(false);
  const [runReport, setRunReport] = useState<RunReport | null>(null);
  const [running, setRunning] = useState(false);
  const [scenarioId, setScenarioId] = useState<number | null>(id ? Number(id) : null);

  useEffect(() => {
    queryApi.projects().then(r => setProjects(Array.isArray(r?.data) ? r.data : [])).catch(() => {});
    scenarioApi.envProfiles().then(d => setEnvProfiles(Array.isArray(d) ? d : [])).catch(() => {});
  }, []);

  useEffect(() => {
    if (scenarioId) {
      scenarioApi.get(scenarioId).then(d => {
        form.setFieldsValue({
          name: d.name,
          projectId: d.projectId,
          envProfileId: d.envProfileId,
        });
        setDagSteps(dagJsonToSteps(d.dagJson));
      }).catch(() => {
        message.error('场景加载失败');
        navigate('/scenarios');
      });
    }
  }, [scenarioId]);

  const handleSave = async () => {
    const values = await form.validateFields();
    if (dagSteps.length === 0) { message.error('请至少添加一个步骤'); return; }
    const dagJson = stepsToDagJson(dagSteps);
    setSaving(true);
    try {
      const result = await scenarioApi.save({ id: scenarioId ?? undefined, ...values, dagJson });
      setScenarioId(Number(result.id));
      message.success('场景已保存');
    } catch (e: any) {
      const errors = e?.response?.data?.errors;
      if (Array.isArray(errors)) message.error(errors.join('; '));
      else message.error(e?.response?.data?.error || '保存失败');
    } finally { setSaving(false); }
  };

  const handleRun = async (mode: 'record' | 'execute') => {
    if (!scenarioId) { message.warning('请先保存场景'); return; }
    if (dagSteps.length === 0) { message.warning('请先添加步骤'); return; }
    setRunning(true);
    setRunReport(null);
    try {
      const report = await scenarioApi.run(scenarioId, mode);
      setRunReport(report);
    } catch (e: any) {
      message.error(e?.response?.data?.error || '执行失败');
    } finally { setRunning(false); }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 56px - 32px)', gap: 0 }}>
      <LegacyNotice />
      {/* 顶部 Toolbar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 12, padding: '8px 16px',
        background: palette.bgContainer, borderBottom: `1px solid ${palette.borderSubtle}`, borderRadius: '8px 8px 0 0',
      }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/scenarios')} type="text" />
        <Text strong style={{ fontSize: 15 }}>{isNew ? '新建场景' : `编辑场景 #${id}`}</Text>
        {runReport && (
          <Tag color={runReport.success ? 'green' : 'red'} style={{ marginLeft: 4 }}>
            {runReport.success ? '执行成功' : '有失败'} · {runReport.totalMs}ms
          </Tag>
        )}
        <div style={{ flex: 1 }} />
        <Space size={8}>
          <Form form={form} layout="inline" style={{ gap: 8 }}>
            <Form.Item name="name" rules={[{ required: true, message: '名称' }]} style={{ marginBottom: 0 }}>
              <Input placeholder="场景名称" style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="projectId" style={{ marginBottom: 0 }}>
              <Select allowClear placeholder="项目" style={{ width: 140 }}
                options={projectOptions(projects)} />
            </Form.Item>
            <Form.Item name="envProfileId" style={{ marginBottom: 0 }}>
              <Select allowClear placeholder="环境" style={{ width: 120 }}
                options={envProfiles.map(p => ({ value: p.id, label: p.name }))} />
            </Form.Item>
          </Form>
          <Button icon={<ExperimentOutlined />} loading={running} onClick={() => handleRun('record')}>试运行</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>保存</Button>
        </Space>
      </div>

      {/* 画布（满高，含左侧组件面板 + 右侧属性 Drawer） */}
      <div style={{ flex: 1, minHeight: 0 }}>
        <DagEditor
          initialDag={dagSteps}
          onChange={setDagSteps}
          height="100%"
        />
      </div>

      {/* 执行报告（底部抽屉式） */}
      {runReport && (
        <div style={{
          maxHeight: 220, overflowY: 'auto', padding: '8px 16px',
          background: palette.bgSubtle, borderTop: `1px solid ${palette.border}`, borderRadius: '0 0 8px 8px',
        }}>
          <Space size={8} wrap style={{ marginBottom: 6 }}>
            <Text strong>执行报告</Text>
            <Tag color={runReport.success ? 'green' : 'red'}>{runReport.success ? '成功' : '失败'}</Tag>
            <Text type="secondary">{runReport.totalMs}ms</Text>
          </Space>
          {runReport.steps.map((s, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 8, padding: '2px 0', fontSize: 12,
            }}>
              <Tag color={s.success ? 'green' : 'red'} style={{ fontSize: 10 }}>{s.stepId}</Tag>
              <Text style={{ fontSize: 11 }}>{s.callLabel}</Text>
              <Tag style={{ fontSize: 10 }}>{s.status}</Tag>
              <Text type="secondary" style={{ fontSize: 10 }}>{s.elapsedMs}ms</Text>
              {Object.entries(s.extracted).map(([k, v]) => (
                <Tag key={k} color="cyan" style={{ fontSize: 9 }}>{k}={String(v).substring(0, 30)}</Tag>
              ))}
              {s.error && <Text type="danger" style={{ fontSize: 10 }}>{s.error}</Text>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
