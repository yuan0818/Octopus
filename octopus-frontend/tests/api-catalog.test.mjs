import test from 'node:test';
import assert from 'node:assert/strict';
import { buildCatalog, filterCatalog, catalogTree, catalogBranches, catalogModuleIds, selectCatalogScope, UNASSIGNED } from '../src/pages/BusinessTesting/apiCatalogModel.ts';
import { appendOperation, emptyFlow, hasOperationSchemas } from '../src/pages/BusinessTesting/workbenchModel.ts';
const module = (projectId, modulePath) => ({ projectId, name: modulePath, modulePath, branch: 'main', branches: ['main','release'] });
const scopes = [{ key: 'r1', name: 'Platform', modules: [module(1, 'auth/api'),module(2, 'device/api')] },
  { key: 'r2', name: 'Other', modules: [module(3, '.')] }];
const endpoint = (projectId, branch='main', commitId='base') => ({ projectId, branch, commitId,
  httpMethod: 'GET', path: '/health', signature: 'Controller.health()', controllerSimpleName:'Controller', methodName:'health' });
const operation = (implementations=[]) => ({ operationId:'test.health', revision:'v1', system:'Platform', service:'api',
  method:'GET', path:'/health', inputs:{}, implementations });
test('project and branch identity prevent same route from being collapsed', () => {
  const roots = buildCatalog(scopes, [endpoint(1),endpoint(2),endpoint(3),endpoint(1,'release','new')], []);
  assert.equal(roots[0].items.length, 3);
  assert.equal(roots[1].items.length, 1);
  assert.equal(new Set(roots.flatMap(r=>r.items.map(i=>i.key))).size,4);
});
test('single-module projects list APIs directly while multi-module projects retain modules', () => {
  const tree = catalogTree(buildCatalog(scopes,[endpoint(1),endpoint(2),endpoint(3)],[]));
  assert.equal(tree[0].children[0].kind,'module');
  assert.equal(tree[0].children[0].children[0].kind,'api');
  assert.equal(tree[1].children[0].kind,'api');
});
test('only exact implementation version links an asset; route or service name alone does not', () => {
  const linked = operation([{ projectId:1, branch:'main', commitId:'base', signature:'Controller.health()' }]);
  const roots = buildCatalog(scopes,[endpoint(1),endpoint(2),endpoint(1,'release','new')],[linked]);
  assert.equal(roots[0].items.filter(i=>i.operations.length).length,1);
  const unlinked = buildCatalog(scopes,[endpoint(1)],[operation()]);
  assert.equal(unlinked.find(r=>r.key===UNASSIGNED).items.length,1);
  assert.equal(unlinked[0].items[0].operations.length,0);
});
test('imported historical API remains visible under its actual module', () => {
  const roots = buildCatalog(scopes,[endpoint(1)],[operation([{projectId:1,branch:'main',commitId:'older',signature:'Controller.health()'}])]);
  const historical = filterCatalog(roots,{search:'auth health',branch:'main',commitId:'older',source:'imported'});
  assert.equal(historical[0].items.length,1);
  assert.equal(historical[0].items[0].endpoint,undefined);
});
test('unknown project mappings stay unassigned instead of being guessed from system names', () => {
  const roots=buildCatalog(scopes,[],[operation([{projectId:999,branch:'main',commitId:'base',signature:'Controller.health()'}])]);
  assert.equal(roots.find(r=>r.key===UNASSIGNED).items.length,1);
  assert.equal(roots[0].items.length,0);
});
test('branch, version and search filters do not mix records and retain empty project context', () => {
  const roots=buildCatalog(scopes,[endpoint(1),endpoint(1,'release','new')],[]);
  const selected=filterCatalog(roots,{search:'Platform /health',branch:'release',commitId:'new',source:'code'});
  assert.equal(selected[0].items.length,1);
  assert.equal(selected[0].items[0].branch,'release');
  assert.equal(selected[1].items.length,0);
});

const linkedScopes = [
  { key: 'local:one', modules: [module(1, 'auth'), { ...module(2, 'device'), branches: ['release'] }] },
  { key: 'repo:two', modules: [
    { ...module(3, 'api'), branch: 'main', branches: ['main', 'legacy'] },
    { ...module(4, 'api'), branch: 'release', branches: ['release'] },
  ] },
];
const selected = { key: 'local:one', rootKey: 'local:one', branch: 'main', commitId: 'old-commit' };

test('project and module choices narrow branches and keep branch-specific module IDs isolated', () => {
  assert.deepEqual(catalogBranches(linkedScopes, 'local:one', 'device'), ['release']);
  assert.deepEqual(catalogBranches(linkedScopes, 'repo:two', 'api'), ['main', 'release']);
  assert.deepEqual(catalogModuleIds(linkedScopes[1], 'api', 'release'), [4]);
  assert.deepEqual(catalogModuleIds(linkedScopes[0], 'auth', 'release'), [1]);
  assert.deepEqual(catalogModuleIds(linkedScopes[0], 'device', 'main'), []);
});

test('changing project or module clears its previous commit and preserves only an available branch', () => {
  const auth = selectCatalogScope(linkedScopes, selected, {rootKey: 'local:one', moduleKey: 'auth'});
  assert.equal(auth.branch, 'main');
  assert.equal(auth.commitId, '');
  const device = selectCatalogScope(linkedScopes, {...auth, commitId:'auth-commit'}, {rootKey:'local:one', moduleKey:'device'});
  assert.equal(device.branch, '');
  assert.equal(device.commitId, '');
  const anotherProject = selectCatalogScope(linkedScopes, {...auth, commitId:'auth-commit'}, {rootKey:'repo:two'});
  assert.equal(anotherProject.moduleKey, undefined);
  assert.equal(anotherProject.branch, 'main');
  assert.equal(anotherProject.commitId, '');
});

test('branch changes invalidate commits, while selecting the same scope keeps the version', () => {
  const same = selectCatalogScope(linkedScopes, selected, {rootKey:'local:one'});
  assert.equal(same.commitId, 'old-commit');
  const branch = selectCatalogScope(linkedScopes, selected, {rootKey:'local:one', branch:'release'});
  assert.equal(branch.branch, 'release');
  assert.equal(branch.commitId, '');
  const manual = selectCatalogScope(linkedScopes, selected, {rootKey:UNASSIGNED});
  assert.equal(manual.branch, '');
  assert.equal(manual.commitId, '');
  assert.deepEqual(catalogBranches(linkedScopes, UNASSIGNED), []);
});

test('step selection retains contract revisions and adds the chosen version without changing the draft', () => {
  const implementation = {projectId:1,branch:'main',commitId:'base',signature:'Controller.health()'};
  const incomplete = operation([implementation]);
  const ready = {...incomplete, revision:'v2', service:'auth-service', requestSchema:{type:'object'}, responseSchema:{201:{type:'object'}}};
  const roots = buildCatalog(scopes,[endpoint(1),endpoint(1,'release','new')],[incomplete,ready]);
  const item = filterCatalog(roots,{search:'auth-service',branch:'main',commitId:'base',source:'all'})[0].items[0];
  assert.equal(item.operations.length,2);
  assert.equal(hasOperationSchemas(item.operations[0]),false);
  const chosen = item.operations.find(hasOperationSchemas);
  const draft = {...emptyFlow(), name:'已有草稿',revision:4};
  const next = appendOperation(draft,chosen);
  assert.equal(next.nodes[0].operationRef,'test.health@v2');
  assert.equal(next.testPoints[0].assertions[next.nodes[0].id][0].expected,201);
  assert.equal(next.name,draft.name);
  assert.equal(next.revision,4);
  assert.equal(draft.nodes.length,0);
});
