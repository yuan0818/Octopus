import test from 'node:test';
import assert from 'node:assert/strict';
import {applyToFlow, buildRequest, defaultForm, groupCandidates, isTerminal, selectionIssues} from '../src/pages/BusinessTesting/caseGenerationModel.ts';

const flow = {flowId:'orders',revision:3,name:'下单',initialContext:{},nodes:[],dependencies:[],stateModels:[],testPoints:[{id:'happy',title:'正常',covers:[],assertions:{},inputOverrides:{},intentionallyViolatedRule:null,generatedFrom:{flowHash:'h',sourceRule:'r',fingerprint:'fp-existing',sourcePointId:'happy'}}],steps:[]};
const env = {environment:'gen-sandbox',enabled:true,dataPolicy:'DISPOSABLE',resetCycleHours:24,nonProductionDeclared:true,reason:'x',revision:1,updatedBy:'a',updatedAt:null};

test('probing is off by default and only opted-in environments can be chosen', () => {
  const off = buildRequest(defaultForm(), flow, 'happy', [env], 'casegen_0123456789abcdef');
  assert.deepEqual(off.errors, []);
  assert.equal(off.request.probe.mode, 'OFF');
  assert.equal(off.request.probe.environment, null);
  const wrong = buildRequest({...defaultForm(), probe:true, environment:'staging'}, flow, 'happy', [env], 'id');
  assert.ok(wrong.errors.some(e => e.includes('已开启生成探测')));
  const revoked = buildRequest({...defaultForm(), probe:true, environment:'gen-sandbox'}, flow, 'happy', [{...env, enabled:false}], 'id');
  assert.ok(revoked.errors.length > 0);
  const on = buildRequest({...defaultForm(), probe:true, environment:'gen-sandbox', initialContextJson:'{"credentials":{"user":"u"}}'}, flow, 'happy', [env], 'id');
  assert.equal(on.request.probe.mode, 'ON');
  assert.deepEqual(on.request.probe.initialContext, {credentials:{user:'u'}});
  assert.ok(buildRequest({...defaultForm(), probe:true, environment:'gen-sandbox', initialContextJson:'[1]'}, flow, 'happy', [env], 'id').errors.length);
});

test('negative expectations need evidence and strategies are required', () => {
  assert.ok(buildRequest({...defaultForm(), rejectionStatus:422}, flow, 'happy', [], 'id').errors.some(e => e.includes('依据')));
  assert.ok(buildRequest({...defaultForm(), strategies:[]}, flow, 'happy', [], 'id').errors.length);
  const ok = buildRequest({...defaultForm(), rejectionStatus:422, rejectionEvidence:'错误码规范'}, flow, 'happy', [], 'id');
  assert.deepEqual(ok.request.rejection, {status:422, evidence:'错误码规范'});
});

const candidate = (intentId, group) => ({intentId, title:intentId, category:'NEGATIVE', source:'MODEL', sourceRule:'r', targetCallIds:['create'], status:'BLOCKED', group, flags:[], reasons:[], conclusion:null, round:1, fingerprint:'fp-'+intentId, probeRunId:null, skippedReason:null, rationale:null, point:{id:'case_'+intentId, title:intentId, covers:[], assertions:{}, inputOverrides:{}, intentionallyViolatedRule:null, generatedFrom:{flowHash:'h',sourceRule:'r',fingerprint:'fp-'+intentId,sourcePointId:'happy'}}});

test('groups follow review priority and contradictions need a decision', () => {
  const task = {candidates:[candidate('a','READY'), candidate('b','CONTRADICTED'), candidate('c','SIDE_EFFECT_UNKNOWN')]};
  assert.deepEqual(groupCandidates(task.candidates).map(g => g.key), ['CONTRADICTED','SIDE_EFFECT_UNKNOWN','READY']);
  assert.equal(selectionIssues(task, ['a','b','c'], {}).length, 2);
  assert.equal(selectionIssues(task, ['a','b'], {b:'DEFECT'}).length, 0);
  assert.equal(isTerminal('NEEDS_INPUT'), true);
  assert.equal(isTerminal('RUNNING'), false);
});

test('applied points are deduplicated by fingerprint and capped', () => {
  const result = {flowId:'orders',flowRevision:3,flowHash:'h',points:[candidate('x','READY').point, {...candidate('y','READY').point, generatedFrom:{...candidate('y','READY').point.generatedFrom, fingerprint:'fp-existing'}}],skipped:[{intentId:'z',reason:'副作用未知'}]};
  const applied = applyToFlow(flow, result);
  assert.equal(applied.added, 1);
  assert.equal(applied.flow.testPoints.length, 2);
  assert.ok(applied.messages.includes('副作用未知'));
  const capped = applyToFlow(flow, result, 1);
  assert.equal(capped.added, 0);
  assert.equal(capped.flow, flow);
});
