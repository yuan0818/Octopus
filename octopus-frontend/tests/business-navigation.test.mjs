import test from 'node:test';
import assert from 'node:assert/strict';
import { businessTestingPath, backgroundKnowledgePath, backgroundAnalysisPath, backgroundTasksPath, businessTestingPages, readCatalogView, writeCatalogView, readScope, writeScope, readImpactVersion, writeImpactVersion } from '../src/pages/BusinessTesting/navigationModel.ts';

test('catalog deep links retain scope, search, page and collapsed tree across sibling pages', () => {
  const params = new URLSearchParams('flowSearch=checkout&environment=qa');
  const view = { selection: { key: 'selected-api', rootKey: 'repo:example/platform', moduleKey: 'auth/api', branch: 'release/1', commitId: 'abc123' },
    search: '/users?name=中文 & enabled', source: 'code', page: 3, expanded: [] };
  writeCatalogView(params, view);
  const next = new URL(businessTestingPath('context', params.toString()), 'https://octopus.example');
  assert.deepEqual(readCatalogView(next.searchParams), view);
  assert.equal(next.searchParams.get('flowSearch'), 'checkout');
  assert.equal(next.searchParams.get('environment'), 'qa');
});

test('changing scope invalidates comparison and tree state but retains independent searches', () => {
  const params = new URLSearchParams('repository=r1&module=auth&branch=main&commit=old&baseCommit=base&targetCommit=target&impactModules=1,2&apiPage=4&apiNode=old-api&apiExpanded=[]&apiSearch=health&flowSearch=login');
  writeScope(params, { repositoryKey: 'r2', branch: 'release', commitId: '' });
  for (const key of ['baseCommit','targetCommit','impactModules','apiPage','apiNode','apiExpanded','commit','module']) assert.equal(params.has(key), false, key);
  assert.equal(params.get('apiSearch'), 'health');
  assert.equal(params.get('flowSearch'), 'login');
  assert.equal(readCatalogView(params).selection.rootKey, 'r2');
});

test('searching the same scope does not erase a prepared version comparison', () => {
  const params = new URLSearchParams('repository=r1&branch=main&baseCommit=base&targetCommit=target&impactModules=2,3');
  const before = readImpactVersion(params);
  writeCatalogView(params, { ...readCatalogView(params), search: 'health' });
  assert.deepEqual(readImpactVersion(params), before);
});

test('comparison deep links distinguish all modules from an explicit empty selection', () => {
  const params = new URLSearchParams('repository=r1&module=auth&branch=main&commit=old');
  const version = { projectId: null, repositoryKey: 'r2', branch: 'release', baseCommitId: 'base', targetCommitId: 'target', scopeProjectIds: [] };
  writeImpactVersion(params, version);
  assert.deepEqual(readImpactVersion(params), version);
  assert.deepEqual(readScope(params), { repositoryKey: 'r2', branch: 'release', commitId: '', modulePaths: undefined });
  writeImpactVersion(params, { ...version, scopeProjectIds: undefined });
  assert.equal(readImpactVersion(params).scopeProjectIds, undefined);
});

test('malformed navigation values have safe defaults and unrelated fields are not copied to links', () => {
  const params = new URLSearchParams('apiPage=-2&apiSource=unknown&apiExpanded=invalid&impactModules=1,bad&password=example&profile=example&flowSearch=login');
  const view = readCatalogView(params);
  assert.equal(view.page, 1);
  assert.equal(view.source, 'all');
  assert.equal(view.expanded, undefined);
  assert.equal(readImpactVersion(params).scopeProjectIds, undefined);
  const link = new URL(businessTestingPath('flows', params.toString()), 'https://octopus.example');
  assert.equal(link.searchParams.has('password'), false);
  assert.equal(link.searchParams.has('profile'), false);
  assert.equal(link.searchParams.get('flowSearch'), 'login');
});


test('repository analysis is a separate page and retains version without environment without exposing configuration', () => {
  const params = new URLSearchParams('repository=repo:platform&module=auth&branch=release/1&commit=abc123&environment=qa&password=private');
  const next = new URL(backgroundKnowledgePath(params.toString()), 'https://octopus.example');
  assert.equal(next.pathname, '/background/query');
  assert.equal(businessTestingPages.some(p => p.key === 'repository-analysis'), false);
  assert.deepEqual(readScope(next.searchParams), readScope(params));
  assert.equal(next.searchParams.has('environment'), false);
  assert.equal(next.searchParams.has('password'), false);
});


test('background child links preserve scope but isolate task details and discard private values', () => {
  const search='repository=repo:1&module=auth&branch=release&commit=abc&environment=qa&task=old&password=private&state=FAILED&page=2';
  const tasks=new URL(backgroundTasksPath(search,'task-new'),'https://example.test');
  assert.equal(tasks.pathname,'/background/tasks');assert.equal(tasks.searchParams.get('task'),'task-new');assert.equal(tasks.searchParams.get('state'),'FAILED');assert.equal(tasks.searchParams.has('password'),false);
  const query=new URL(backgroundKnowledgePath(tasks.search),'https://example.test');assert.equal(query.pathname,'/background/query');assert.equal(query.searchParams.has('task'),false);assert.equal(query.searchParams.has('state'),false);assert.equal(query.searchParams.get('module'),'auth');
  const analysis=new URL(backgroundAnalysisPath(search),'https://example.test');
  assert.equal(analysis.pathname,'/background/analysis');
  assert.deepEqual([...analysis.searchParams], [...query.searchParams]);
  assert.equal(backgroundAnalysisPath(),'/background/analysis');
});
