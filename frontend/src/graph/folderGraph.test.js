import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFolderGraph } from './folderGraph.js'

test('graph preserves folder parent relationships and positions every node', () => {
  const graph = buildFolderGraph([
    { id: 1, parentId: 0, name: '资料', children: [
      { id: 2, parentId: 1, name: '图片', children: [] }
    ] },
    { id: 3, parentId: 0, name: '工作', children: [] }
  ])
  assert.equal(graph.folderCount, 3)
  assert.deepEqual(graph.edges, [
    { from: 'root', to: '1' }, { from: '1', to: '2' }, { from: 'root', to: '3' }
  ])
  assert.deepEqual(graph.nodes.find(node => node.id === '2').path, ['我的文件', '资料', '图片'])
  for (const node of graph.nodes) {
    assert.equal(Number.isFinite(node.x), true)
    assert.equal(Number.isFinite(node.y), true)
  }
})

test('empty folder list leaves only the virtual root', () => {
  const graph = buildFolderGraph([])
  assert.equal(graph.folderCount, 0)
  assert.deepEqual(graph.edges, [])
})
