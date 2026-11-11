import test from 'node:test';
import assert from 'node:assert/strict';
import { projectOptions, repositoryModuleTree } from '../src/components/projectOptions.ts';

test('repository identities keep equally named modules separate and retain branch IDs', () => {
  const rows = [
    { id: 1, projectName: 'common', repositoryKey: 'repo:1', repositoryName: 'Platform', modulePath: 'parent/common', branch: 'main' },
    { id: 2, projectName: 'common', repositoryKey: 'repo:1', repositoryName: 'Platform', modulePath: 'parent/common', branch: 'release' },
    { id: 3, projectName: 'common', repositoryKey: 'repo:2', repositoryName: 'Platform', modulePath: 'common', branch: 'main' },
  ];
  const groups = projectOptions(rows);
  assert.equal(groups.length, 2);
  assert.deepEqual(groups[0].options.map(o => o.value), [1, 2]);
  assert.equal(groups[0].options[1].label, 'parent/common · release');
  assert.deepEqual(groups[1].options.map(o => o.value), [3]);
});

test('directory hierarchy retains nested modules and source at the root', () => {
  const tree = repositoryModuleTree([
    { modulePath: '.' }, { modulePath: 'parent/common' }, { modulePath: 'parent/api' },
    { modulePath: 'other/common' },
  ]);
  assert.equal(tree[0].title, '根目录源码');
  assert.deepEqual(tree[1].children.map(n => n.title), ['common', 'api']);
  assert.notEqual(tree[1].children[0].key, tree[2].children[0].key);
});
