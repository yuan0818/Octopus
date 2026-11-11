import test from 'node:test';
import assert from 'node:assert/strict';
import { platformPreparation, preparationIssues } from '../src/pages/BusinessTesting/preparationModel.ts';
import { acceptStepFill, fillChanges, snapshot } from '../src/pages/BusinessTesting/flowFillModel.ts';
import { appendOperation, emptyFlow, executionProfile, flowEdges, flowIssues } from '../src/pages/BusinessTesting/workbenchModel.ts';
const operation={operationId:'login',revision:'v1',system:'platform',service:'auth',method:'POST',path:'/login',inputs:{cipher:{location:'body',name:'cipher',type:'string',required:true}},requestSchema:{type:'object'},responseSchema:{200:{type:'object'}},implementations:[],source:{kind:'HUMAN',location:'test',digest:''},requiresCapabilities:['http','json'],securityProfile:null};
const flow=()=>appendOperation({...emptyFlow(),name:'Preparation'},operation);
test('encrypted wire body satisfies transport fields and exposes credential references for execution',()=>{
 const f=flow(), node=f.nodes[0]; node.preparation=platformPreparation(true,node);
 assert.ok(!flowIssues(f,[operation]).some(v=>v.includes('必填参数 cipher')));
 assert.ok(preparationIssues(node.preparation).some(v=>v.includes('appid')));
 assert.ok(preparationIssues(node.preparation).some(v=>v.includes('明文业务字段')));
 const profile=executionProfile({irJson:JSON.stringify({operations:[operation],steps:[node],initialContext:{}})});
 assert.deepEqual(Object.keys(profile.secretEnvironmentNames).sort(),['platform.publicKey','platform.signingSecret']);
 const sign=node.preparation.tools.find(t=>t.tool==='platform.sign');
 assert.deepEqual(sign.inputs.body,node.preparation.body);
 for(const key of ['appid','requestid','timestamp']) assert.deepEqual(sign.inputs[key],node.preparation.headers[key]);
});
test('AI preparation changes are reviewable and cannot silently touch another node',()=>{
 const f=appendOperation(flow(),operation), next=structuredClone(f);
 next.nodes[0].preparation=platformPreparation(true,next.nodes[0]);
 assert.equal(acceptStepFill(f,f,f,next,f.nodes[0].id).nodes[0].preparation.tools.length,6);
 assert.ok(fillChanges(f,next)[0].changes.some(v=>v.includes('请求预处理')));
 next.nodes[1].preparation=platformPreparation(false,next.nodes[1]);
 assert.throws(()=>acceptStepFill(f,f,f,next,f.nodes[0].id),/其他步骤/);
 assert.equal(snapshot(f),snapshot({...f,nodes:f.nodes.map(n=>({...n,preparation:null}))}));
});
test('preparation references participate in dependency display and removed actions leave visible errors',()=>{
 const f=appendOperation(flow(),operation), [first,second]=f.nodes;
 first.outputs=[{key:'auth.flToken',path:'$.token',type:'string',sensitive:true}];
 second.preparation=platformPreparation(false,second);
 second.preparation.headers.flToken={source:'context',value:'auth.flToken'};
 assert.ok(flowEdges(f).some(e=>e.inferred&&e.source===first.id&&e.target===second.id));
 second.preparation.tools=second.preparation.tools.filter(t=>t.id!=='timestamp');
 assert.ok(preparationIssues(second.preparation).some(v=>v.includes('前置动作不存在')));
});
