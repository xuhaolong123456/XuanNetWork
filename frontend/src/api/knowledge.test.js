import test from 'node:test'
import assert from 'node:assert/strict'
import { createKnowledgeEdge, createKnowledgeNode, deleteKnowledgeNode, listKnowledgeEdges, listKnowledgeNodes } from './knowledge.js'

test('reads owner graph and sends CSRF protected knowledge mutations', async () => {
  const previousFetch = globalThis.fetch
  const previousDocument = globalThis.document
  const calls = []
  globalThis.document = { cookie: 'XSRF-TOKEN=graph-token' }
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, options })
    if (url.endsWith('/nodes')) return Response.json({ success: true, data: [{ id: 1, title: 'Java' }] })
    if (url.endsWith('/edges')) return Response.json({ success: true, data: [{ id: 2, source: 1, target: 3 }] })
    if (url.endsWith('/node')) return Response.json({ success: true, data: { id: 4, title: 'Redis' } })
    if (url.endsWith('/edge')) return Response.json({ success: true, data: { id: 5, source: 1, target: 3 } })
    return new Response(null, { status: 204 })
  }
  try {
    assert.equal((await listKnowledgeNodes())[0].title, 'Java')
    assert.equal((await listKnowledgeEdges())[0].target, 3)
    await createKnowledgeNode({ title: 'Redis', content: '缓存' })
    await createKnowledgeEdge({ sourceId: 1, targetId: 3, relation: '使用' })
    await deleteKnowledgeNode(4)

    assert.deepEqual(calls.slice(2).map(call => [call.url, call.options.method, call.options.headers['X-XSRF-TOKEN']]), [
      ['/api/knowledge/node', 'POST', 'graph-token'],
      ['/api/knowledge/edge', 'POST', 'graph-token'],
      ['/api/knowledge/node/4', 'DELETE', 'graph-token']
    ])
    assert.deepEqual(JSON.parse(calls[2].options.body), { title: 'Redis', content: '缓存' })
  } finally {
    globalThis.fetch = previousFetch
    globalThis.document = previousDocument
  }
})

test('preserves permission and validation errors for the graph UI', async () => {
  const previousFetch = globalThis.fetch
  globalThis.fetch = async () => Response.json({ success: false, code: 'NOT_FOUND', message: '资源不存在' }, { status: 404 })
  try {
    await assert.rejects(listKnowledgeNodes(), error => error.status === 404 && error.message === '资源不存在')
  } finally {
    globalThis.fetch = previousFetch
  }
})
