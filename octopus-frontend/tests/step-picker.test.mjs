import test from 'node:test';
import assert from 'node:assert/strict';
import { appendPending, movePending, pendingCounts, pendingStep } from '../src/pages/BusinessTesting/stepPickerModel.ts';
import { appendOperation, emptyFlow, refOf } from '../src/pages/BusinessTesting/workbenchModel.ts';

const operation = revision => ({operationId:'orders',revision,system:'shop',service:'orders',method:'POST',path:'/orders',inputs:{},requestSchema:{type:'object'},responseSchema:{201:{type:'object'}},implementations:[],source:{kind:'HUMAN',location:'test',digest:''},requiresCapabilities:['http','json'],securityProfile:null});

test('repeated calls move independently while keeping their selected API revisions', () => {
  const v1 = operation('v1'), v2 = operation('v2');
  const first = pendingStep(v1), second = pendingStep(v2), repeated = pendingStep(v1);
  assert.notEqual(first.id, repeated.id);
  const selected = [first, second, repeated];
  const reordered = movePending(selected, repeated.id, first.id);
  assert.deepEqual(reordered.map(s => s.id), [repeated.id, first.id, second.id]);
  assert.deepEqual(selected.map(s => s.id), [first.id, second.id, repeated.id]);
  assert.deepEqual(pendingCounts(reordered), {'orders@v1':2, 'orders@v2':1});
  assert.deepEqual(appendPending(emptyFlow(false), reordered).nodes.map(n => n.operationRef), ['orders@v1', 'orders@v1', 'orders@v2']);
});

test('appending a batch preserves existing business rules and chains the reviewed order after the current last step', () => {
  const v1 = operation('v1'), v2 = operation('v2');
  const original = appendOperation(appendOperation({...emptyFlow(false), revision:7, name:'Reviewed path'}, v1), v1);
  original.nodes[0].inputs.userId = {source:'context', value:'user.id'};
  original.testPoints[0].assertions[original.nodes[0].id].push({id:'business-rule', kind:'JSON_EQUALS', path:'$.body.status', expected:'created'});
  const before = structuredClone(original);
  const result = appendPending(original, [pendingStep(v2), pendingStep(v1)]);
  assert.deepEqual(original, before);
  assert.equal(result.flowId, original.flowId);
  assert.equal(result.revision, 7);
  assert.deepEqual(result.nodes.slice(0,2), original.nodes);
  assert.deepEqual(result.dependencies.slice(0,1), original.dependencies);
  assert.deepEqual(result.testPoints[0].assertions[original.nodes[0].id], original.testPoints[0].assertions[original.nodes[0].id]);
  assert.deepEqual(result.nodes.slice(2).map(n => n.operationRef), [refOf(v2), refOf(v1)]);
  assert.deepEqual(result.dependencies.slice(1).map(d => [d.from,d.to]), [[result.nodes[1].id,result.nodes[2].id],[result.nodes[2].id,result.nodes[3].id]]);
  assert.equal(new Set(result.nodes.map(n => n.id)).size, 4);
  assert.deepEqual(result.testPoints[0].covers, result.nodes.map(n => n.id));
});

test('a full flow rejects the entire oversized batch without changing the draft', () => {
  const op = operation('v1');
  const flow = appendPending(emptyFlow(false), Array.from({length:49}, () => pendingStep(op)));
  assert.equal(appendPending(flow, [pendingStep(op)]).nodes.length, 50);
  assert.throws(() => appendPending(flow, [pendingStep(op),pendingStep(op)]), /最多添加 50/);
  assert.equal(flow.nodes.length,49);
  assert.equal(appendPending(flow, []),flow);
});
