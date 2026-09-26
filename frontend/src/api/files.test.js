import test from 'node:test'
import assert from 'node:assert/strict'
import { listFiles } from './files.js'

test('lists root without a parent id and sends pagination', async () => {
  let requestedUrl
  globalThis.fetch = async (url, options) => {
    requestedUrl = url
    assert.equal(options.credentials, 'same-origin')
    return Response.json({ success: true, data: { items: [] } })
  }

  const result = await listFiles({ page: 2, size: 25 })

  assert.equal(requestedUrl, '/api/v1/files?page=2&size=25')
  assert.deepEqual(result, { items: [] })
})

test('includes directory id and keeps API errors available to the view', async () => {
  let requestedUrl
  globalThis.fetch = async url => {
    requestedUrl = url
    return Response.json({ success: false, code: 'FILE_NOT_FOUND', message: 'Directory not found' }, { status: 404 })
  }

  await assert.rejects(listFiles({ parentId: 18 }), error => {
    assert.equal(error.status, 404)
    assert.equal(error.code, 'FILE_NOT_FOUND')
    return true
  })
  assert.equal(requestedUrl, '/api/v1/files?page=0&size=50&parentId=18')
})
