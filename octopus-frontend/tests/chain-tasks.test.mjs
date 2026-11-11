import test from 'node:test';
import assert from 'node:assert/strict';
import { filterTasks, readTaskFilters, taskGroup, taskProgress } from '../src/pages/BusinessTesting/chainTasksModel.ts';
import { businessTestingPath } from '../src/pages/BusinessTesting/navigationModel.ts';

const tasks = [
  {id:'task-login',flowId:'login',name:'用户登录',state:'RUNNING',environment:'test',currentNodeLabel:'读取资料'},
  {id:'task-order',flowId:'order',name:'创建订单',state:'FAILED',environment:'qa',currentNodeLabel:'支付订单'},
  {id:'task-review',flowId:'order',name:'订单查询',state:'READY',environment:'qa',currentNodeLabel:null},
  {id:'task-cancelled',flowId:'order',name:'旧订单任务',state:'CANCELLED',environment:'test',currentNodeLabel:null},
];
test('task filters combine lifecycle state, environment and case-insensitive node or task search', () => {
  const filters = readTaskFilters(new URLSearchParams('taskStatus=attention&taskEnvironment=qa&taskSearch=支付'));
  assert.deepEqual(filterTasks(tasks,filters).map(t=>t.id),['task-order']);
  assert.deepEqual(filterTasks(tasks,{...filters,search:'TASK-ORDER'}).map(t=>t.id),['task-order']);
  assert.equal(filterTasks(tasks,{...filters,environment:'test'}).length,0);
  assert.equal(filterTasks(tasks,readTaskFilters(new URLSearchParams())).length,4);
});
test('paused and confirming tasks remain distinct from reviewed results and cancelled history', () => {
  for(const state of ['QUEUED','RUNNING','PAUSE_REQUESTED','ACCEPTING'])assert.equal(taskGroup(state),'active');
  for(const state of ['PAUSED','FAILED'])assert.equal(taskGroup(state),'attention');
  assert.equal(taskGroup('READY'),'review');assert.equal(taskGroup('ACCEPTED'),'accepted');assert.equal(taskGroup('CANCELLED'),'cancelled');
  assert.deepEqual(filterTasks(tasks,{...readTaskFilters(new URLSearchParams()),group:'cancelled'}).map(t=>t.id),['task-cancelled']);
});
test('task deep links preserve filters and detail across workbench pages without copying private context', () => {
  const query = new URLSearchParams('fillTask=task-order&taskStatus=attention&taskEnvironment=qa&taskPage=2&taskSearch=支付&repository=repo1&contextValues=secret');
  const link = new URL(businessTestingPath('tasks',query.toString()),'https://octopus.example');
  assert.equal(link.pathname,'/business-testing/tasks');assert.equal(link.searchParams.get('fillTask'),'task-order');assert.equal(link.searchParams.has('contextValues'),false);
  assert.deepEqual(readTaskFilters(link.searchParams),{group:'attention',environment:'qa',page:2,search:'支付'});
  assert.equal(new URL(businessTestingPath('flows',link.search),'https://octopus.example').searchParams.get('fillTask'),'task-order');
});
test('malformed task filters and unavailable snapshot counts have safe defaults', () => {
  const filters=readTaskFilters(new URLSearchParams('taskStatus=bogus&taskPage=-5'));
  assert.equal(filters.group,'all');assert.equal(filters.page,1);
  assert.equal(taskProgress({completed:0,total:0}),0);assert.equal(taskProgress({completed:1,total:3}),33);assert.equal(taskProgress({completed:4,total:3}),100);
});
