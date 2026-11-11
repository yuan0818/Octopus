import test from 'node:test';
import assert from 'node:assert/strict';
import {chainCanvasMismatch,retryLabel} from '../src/pages/BusinessTesting/chainRecoveryModel.ts';

test('reconnected task accepts original canvas and only accepted candidate, never unrelated new edits',()=>{
 const original={name:'human path',nodes:[]}, candidate={name:'human path',nodes:[{id:'step',inputs:{a:1}}]};
 const task={state:'READY',original,candidate};assert.equal(chainCanvasMismatch(original,task),false);assert.equal(chainCanvasMismatch(candidate,task),true);
 task.state='ACCEPTED';assert.equal(chainCanvasMismatch(candidate,task),false);assert.equal(chainCanvasMismatch({...candidate,name:'new edit'},task),true);
});
test('retry guidance distinguishes configuration repair from transient retry',()=>{
 assert.equal(retryLabel({retryable:false}),'修复后重试并继续');assert.equal(retryLabel({retryable:true}),'重试失败节点并继续');
});
