import test from 'node:test';
import assert from 'node:assert/strict';
import { acceptFill, defaultFillInputs, fillChanges, snapshot } from '../src/pages/BusinessTesting/flowFillModel.ts';
import { appendOperation, emptyFlow, flowIssues } from '../src/pages/BusinessTesting/workbenchModel.ts';
const op={operationId:'orders',revision:'v2',system:'shop',service:'orders',method:'POST',path:'/orders',inputs:{userId:{location:'query',name:'userId',type:'string',required:true}},requestSchema:{type:'object'},responseSchema:{201:{type:'object'}},implementations:[],source:{kind:'HUMAN',location:'test',digest:''},requiresCapabilities:['http','json'],securityProfile:null};
const draft=()=>appendOperation(appendOperation({...emptyFlow(false),name:'Human path'},op),op);
test('new steps leave required inputs visibly unfilled instead of inventing empty test values',()=>{
 const f=draft(); assert.deepEqual(f.nodes[0].inputs,{}); assert.ok(flowIssues(f,[op]).some(v=>v.includes('userId')));
 assert.deepEqual(defaultFillInputs(f,[op])[f.nodes[0].id],['userId']);
 f.nodes[0].inputs.userId={source:'literal',value:''}; assert.deepEqual(defaultFillInputs(f,[op])[f.nodes[0].id],[]);
});
test('applying a fill rejects changes made during generation, including only changing an existing parameter',()=>{
 const base=draft(),current=structuredClone(base),filled=structuredClone(base);
 filled.nodes[0].inputs.userId={source:'literal',value:'candidate'};
 current.nodes[0].inputs.userId={source:'literal',value:'human edit'};
 assert.throws(()=>acceptFill(current,base,base,filled),/生成期间已修改/);
 assert.equal(current.nodes[0].inputs.userId.value,'human edit');
});
test('an accepted fill preserves flow identity and accepts only explicitly prepared variable declarations',()=>{
 const original=draft(), prepared=structuredClone(original); prepared.initialContext['test.user']={type:'string',sensitive:false};
 const result=structuredClone(prepared); result.nodes[0].inputs.userId={source:'context',value:'test.user'};
 assert.deepEqual(acceptFill(original,original,prepared,result),result);
 for (const mutate of [f=>f.nodes.reverse(),f=>f.nodes[0].operationRef='other@v1',f=>f.dependencies=[],f=>f.testPoints[0].title='replanned',f=>f.initialContext['invented']={type:'string',sensitive:false}]) {
  const invalid=structuredClone(result);mutate(invalid);assert.throws(()=>acceptFill(original,original,prepared,invalid),/改变了既定路径/);
 }
});
test('preview identifies bindings, extracted variables and new assertions without listing unchanged configuration',()=>{
 const base=draft(),filled=structuredClone(base);
 filled.initialContext['user.id']={type:'string',sensitive:false};
 filled.nodes[0].inputs.userId={source:'context',value:'user.id'};
 filled.nodes[0].outputs.push({key:'order.id',path:'$.body.id',type:'string',sensitive:false});
 filled.testPoints[0].assertions[filled.nodes[1].id].push({id:'created',kind:'JSON_EXISTS',path:'$.body.id',expected:true});
 const changes=fillChanges(base,filled);assert.equal(changes[0].changes.length,2);assert.equal(changes[1].changes.length,1);
 assert.ok(changes[0].changes.some(v=>v.includes('引用上下文 user.id')));assert.ok(changes[1].changes[0].includes('应存在'));
 assert.equal(snapshot({b:1,a:2}),snapshot({a:2,b:1}));
});

test('contract preparation preserves the selected API snapshot and exposes newly discovered editable inputs', async()=>{
 const {acceptPreparedContracts}=await import('../src/pages/BusinessTesting/flowFillModel.ts');
 const base=draft(),before=structuredClone(op),next={...structuredClone(op),revision:'contract_read',inputs:{...op.inputs,'body.email':{location:'body',name:'email',type:'string',required:true}}};
 const prepared=structuredClone(base); prepared.nodes.forEach(n=>n.operationRef='orders@contract_read');
 assert.deepEqual(acceptPreparedContracts(base,prepared,[before],[next]),prepared);
 assert.ok(defaultFillInputs(prepared,[next])[prepared.nodes[0].id].includes('body.email'));
 assert.ok(fillChanges(base,prepared)[0].changes.some(s=>s.includes('契约')));
 for(const change of [f=>f.nodes.reverse(), f=>f.dependencies=[], f=>f.nodes[0].inputs.userId={source:'literal',value:'overwrite'}]){
  const changed=structuredClone(prepared);change(changed);assert.throws(()=>acceptPreparedContracts(base,changed,[before],[next]),/改变了原有流程/);
 }
 assert.throws(()=>acceptPreparedContracts(base,prepared,[before],[{...next,path:'/different'}]),/切换了所选 API/);
 assert.throws(()=>acceptPreparedContracts(base,prepared,[before],[{...next,implementations:[{projectId:1,branch:'other',commitId:'b'.repeat(40),signature:'other'}]}]),/切换了所选 API/);
});

test('step acceptance rejects changes to another node or its assertions',async()=>{
 const {acceptStepFill}=await import('../src/pages/BusinessTesting/flowFillModel.ts');
 const base=draft(),filled=structuredClone(base),id=base.nodes[0].id;
 filled.nodes[0].inputs.userId={source:'literal',value:'current'};
 assert.deepEqual(acceptStepFill(base,base,base,filled,id),filled);
 filled.nodes[1].inputs.userId={source:'literal',value:'other'};
 assert.throws(()=>acceptStepFill(base,base,base,filled,id),/其他步骤/);
 const other=structuredClone(base);other.testPoints[0].assertions[base.nodes[1].id].push({id:'unexpected',kind:'JSON_EXISTS',path:'$.body.id',expected:true});
 assert.throws(()=>acceptStepFill(base,base,base,other,id),/其他步骤/);
});
test('step navigation follows dependencies instead of presentation order and detects cycles',async()=>{
 const {orderedFillNodes}=await import('../src/pages/BusinessTesting/flowFillModel.ts');
 const base=draft(),ids=base.nodes.map(n=>n.id);base.nodes.reverse();
 assert.deepEqual(orderedFillNodes(base).map(n=>n.id),ids);
 base.dependencies.push({from:ids[1],to:ids[0],kind:'ORDERING_CONSTRAINT',reason:'invalid'});
 assert.throws(()=>orderedFillNodes(base),/循环/);
});

test('whole-chain acceptance admits contract hydration and additions while rejecting path edits and stale canvases',async()=>{
 const {acceptChainFill}=await import('../src/pages/BusinessTesting/flowFillModel.ts');
 const base=draft(),filled=structuredClone(base),hydrated={...op,revision:'hydrated'};
 filled.nodes.forEach(n=>{n.operationRef='orders@hydrated';n.inputs.userId={source:'literal',value:'candidate'};});
 assert.deepEqual(acceptChainFill(base,base,filled,[op],[hydrated]),filled);
 const changed=structuredClone(base);changed.nodes[0].label='human change';
 assert.throws(()=>acceptChainFill(changed,base,filled,[op],[hydrated]),/生成期间/);
 const rerouted=structuredClone(filled);rerouted.dependencies=[];
 assert.throws(()=>acceptChainFill(base,base,rerouted,[op],[hydrated]),/既定路径/);
 assert.throws(()=>acceptChainFill(base,base,filled,[op],[{...hydrated,path:'/other'}]),/切换了所选/);
});
