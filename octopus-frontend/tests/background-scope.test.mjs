import test from 'node:test';
import assert from 'node:assert/strict';
import { moduleOptions, scopeBranches, scopeVersions } from '../src/pages/BackgroundKnowledge/scopeModel.ts';
const repo = { key:'repo:1', modules:[{ projectId:1, modulePath:'auth', branch:'main', branches:['main','old'] },{ projectId:2, modulePath:'auth', branch:'release', branches:['release'] },{ projectId:3, modulePath:'gateway', branch:'main', branches:['main'] }] };
const versions = [{ commitId:'main-sha', projectIds:[1,3] }, { commitId:'release-sha', projectIds:[2] }];
test('background module filters merge paths but keep branch-specific project IDs', () => {
  assert.equal(moduleOptions(repo).length, 2);
  assert.deepEqual(scopeBranches(repo,'auth'), ['main','release']);
  assert.deepEqual(scopeVersions(versions,repo,'auth','release').map(v=>v.commitId), ['release-sha']);
  assert.deepEqual(scopeVersions(versions,repo,'gateway','release'), []);
});
test('local repositories retain historical branches and all-module query does not discard versions', () => {
  const local={ ...repo,key:'local:1' };
  assert.deepEqual(scopeBranches(local,'auth'), ['main','old','release']);
  assert.equal(scopeVersions(versions,local,'auth','old')[0].commitId, 'main-sha');
  assert.deepEqual(scopeVersions(versions,repo), versions);
});
