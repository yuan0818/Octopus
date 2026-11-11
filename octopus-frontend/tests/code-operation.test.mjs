import test from 'node:test';
import assert from 'node:assert/strict';
import { codeOperation, configureOperation, replaceFlowOperation } from '../src/pages/BusinessTesting/codeOperationModel.ts';
import { appendOperation, emptyFlow, flowIssues, refOf } from '../src/pages/BusinessTesting/workbenchModel.ts';
import { buildCatalog, filterCatalog } from '../src/pages/BusinessTesting/apiCatalogModel.ts';

const item = {
  rootName:'Platform', moduleName:'auth/client', method:'POST',
  endpoint:{projectId:52,branch:'master',commitId:'a'.repeat(40),httpMethod:'POST',path:'/users/{userId}',signature:'Auth.find( String ):User'},
};
test('one known API yields a stable pinned reference without guessing its contract', async () => {
  const operation = await codeOperation(item);
  assert.deepEqual(operation, await codeOperation(structuredClone(item)));
  assert.deepEqual(operation.implementations,[{projectId:52,branch:'master',commitId:'a'.repeat(40),signature:'Auth.find(String):User'}]);
  assert.equal(operation.requestSchema,null);
  assert.equal(operation.responseSchema,null);
  assert.equal(operation.source.kind,'ANALYZED_CODE');
  assert.deepEqual(Object.keys(operation.inputs),['path.userId']);
  assert.equal(operation.inputs['path.userId'].required,true);
  const draft=appendOperation({...emptyFlow(),name:'直接引用'},operation);
  assert.equal(draft.nodes[0].operationRef,refOf(operation));
  assert.ok(flowIssues(draft,[operation]).some(issue=>issue.includes('响应定义')));
});
test('module, branch, commit and implementation identities cannot be collapsed', async () => {
  const references = await Promise.all([item,
    {...item,endpoint:{...item.endpoint,projectId:53}},
    {...item,endpoint:{...item.endpoint,branch:'release'}},
    {...item,endpoint:{...item.endpoint,commitId:'b'.repeat(40)}},
    {...item,endpoint:{...item.endpoint,signature:'Other.find(String):User'}},
  ].map(value=>codeOperation(value)));
  assert.equal(new Set(references.map(refOf)).size,5);
});
test('missing snapshots and ambiguous HTTP methods need an explicit choice', async () => {
  await assert.rejects(codeOperation({...item,endpoint:{...item.endpoint,commitId:'short'}}),/完整分析版本/);
  await assert.rejects(codeOperation(item,'GET'),/HTTP 方法/);
  const wildcard={...item,method:'*',endpoint:{...item.endpoint,httpMethod:'*'}};
  await assert.rejects(codeOperation(wildcard),/HTTP 方法/);
  assert.equal((await codeOperation(wildcard,'PATCH')).method,'PATCH');
});
test('test configuration creates a stable new revision and preserves the code snapshot', async () => {
  const base=await codeOperation(item);
  const inputs={...base.inputs,'query.limit':{location:'query',name:'limit',type:'integer',required:true,prefix:null}};
  const configured=await configureOperation(base,inputs,201);
  assert.equal(configured.operationId,base.operationId);
  assert.notEqual(configured.revision,base.revision);
  assert.deepEqual(configured.implementations,base.implementations);
  assert.equal(configured.source.kind,'HUMAN');
  assert.deepEqual(configured,await configureOperation(base,inputs,201));
  assert.equal(base.requestSchema,null);
  await assert.rejects(configureOperation(base,inputs,0),/响应状态码/);
});
test('applying configuration keeps draft revision and business assertions while updating input bindings', async () => {
  const base=await codeOperation(item);
  const draft=appendOperation({...emptyFlow(),name:'草稿',revision:3},base);
  const node=draft.nodes[0];
  node.inputs['path.userId']={source:'literal',value:'user-7'};
  node.inputs.obsolete={source:'literal',value:'old'};
  draft.testPoints[0].inputOverrides[node.id]={obsolete:{source:'literal',value:'override'}};
  const configured=await configureOperation(base,{...base.inputs,'query.limit':{location:'query',name:'limit',type:'integer',required:true,prefix:null}},201);
  const updated=replaceFlowOperation(draft,base,configured);
  assert.equal(updated.revision,3);
  assert.equal(updated.nodes[0].operationRef,refOf(configured));
  assert.equal(updated.nodes[0].inputs['path.userId'].value,'user-7');
  assert.equal(updated.nodes[0].inputs['query.limit'].value,0);
  assert.equal(updated.nodes[0].inputs.obsolete,undefined);
  assert.deepEqual(updated.testPoints[0].assertions,draft.testPoints[0].assertions);
  assert.deepEqual(updated.testPoints[0].inputOverrides[node.id],{});
  assert.equal(draft.nodes[0].operationRef,refOf(base));
});
test('capturing a code reference does not turn it into an externally imported API', async () => {
  const operation=await codeOperation(item);
  const scopes=[{key:'repo:1',name:'Platform',modules:[{projectId:52,modulePath:'auth/client'}]}];
  const roots=buildCatalog(scopes,[item.endpoint],[operation]);
  assert.equal(roots[0].items.length,1);
  assert.equal(roots[0].items[0].operations.length,1);
  assert.equal(filterCatalog(roots,{search:'',branch:'',commitId:'',source:'imported'})[0].items.length,0);
});
