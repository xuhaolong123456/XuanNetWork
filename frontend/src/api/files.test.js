import test from 'node:test'
import assert from 'node:assert/strict'
import { createDirectory, deleteDirectory, deleteFile, deleteFiles, restoreFile, restoreFiles, listTrash, listFiles, renameDirectory, uploadFile } from './files.js'

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

test('creates a directory in the requested parent with CSRF protection', async () => {
  const requests = []
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options })
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    return Response.json({ success: true, data: { id: 5, name: 'Reports', type: 'DIRECTORY' } })
  }

  const result = await createDirectory({ folderName: 'Reports', parentId: 18 })

  assert.equal(requests[1].url, '/api/v1/files/directories')
  assert.equal(requests[1].options.method, 'POST')
  assert.equal(requests[1].options.credentials, 'same-origin')
  assert.equal(requests[1].options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.deepEqual(JSON.parse(requests[1].options.body), { parentId: 18, folderName: 'Reports' })
  assert.deepEqual(result, { id: 5, name: 'Reports', type: 'DIRECTORY' })
})

test('renames a directory with CSRF protection', async () => {
  const requests = []
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options })
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    return Response.json({ success: true, data: { id: 18, name: 'Archive', type: 'DIRECTORY' } })
  }

  const result = await renameDirectory(18, 'Archive')
  const request = requests.find(entry => entry.url !== '/api/v1/auth/csrf')

  assert.equal(request.url, '/api/v1/files/directories/18')
  assert.equal(request.options.method, 'PATCH')
  assert.equal(request.options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.deepEqual(JSON.parse(request.options.body), { folderName: 'Archive' })
  assert.equal(result.name, 'Archive')
})

test('deletes a directory with CSRF protection', async () => {
  const requests = []
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url, options })
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    return new Response(null, { status: 204 })
  }

  const result = await deleteDirectory(18)
  const request = requests.find(entry => entry.url !== '/api/v1/auth/csrf')

  assert.equal(request.url, '/api/v1/files/directories/18')
  assert.equal(request.options.method, 'DELETE')
  assert.equal(request.options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.equal(result, null)
})

test('metadata operations accept empty 204 responses and send batch ids with CSRF', async () => {
  const requests = []
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    requests.push({ url, options })
    return { status: 204, json() { throw new Error('204 has no JSON') } }
  }
  await deleteFile(7)
  await deleteFiles([7, 8])
  await restoreFile(7)
  await restoreFiles([7, 8])
  assert.deepEqual(requests.map(r => r.url), ['/api/v1/files/file/7', '/api/v1/files/files', '/api/v1/files/recover/7', '/api/v1/files/recover/batch'])
  for (const r of requests) {
    assert.equal(r.options.method, 'PUT')
    assert.equal(r.options.headers['X-XSRF-TOKEN'], 'csrf-value')
  }
  assert.deepEqual(JSON.parse(requests[1].options.body), { ids: [7, 8] })
})

test('metadata errors preserve numeric code and msg for toast', async () => {
  globalThis.fetch = async url => url === '/api/v1/auth/csrf'
    ? Response.json({ success: true, data: 'csrf-value' })
    : Response.json({ code: 403, msg: '只能操作自己的文件', data: null }, { status: 403 })
  await assert.rejects(deleteFiles([8]), e => e.status === 403 && e.code === 403 && e.message === '只能操作自己的文件')
})

test('recycle list returns wrapped paginated metadata', async () => {
  globalThis.fetch = async url => {
    assert.equal(url, '/api/v1/files/recycle-bin?page=1&size=25')
    return Response.json({ success: true, data: { items: [], page: { number: 1 } } })
  }
  assert.deepEqual(await listTrash({ page: 1, size: 25 }), { items: [], page: { number: 1 } })
})

test('uploads a file as multipart data without overriding its boundary header', async () => {
  let request
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    request = { url, options }
    return Response.json({ success: true, data: { id: 9, name: 'notes.txt', type: 'FILE' } })
  }
  const file = new File(['hello'], 'notes.txt', { type: 'text/plain' })

  const result = await uploadFile({ file, parentId: 18 })

  assert.equal(request.url, '/api/v1/files/upload?parentId=18')
  assert.equal(request.options.method, 'POST')
  assert.equal(request.options.credentials, 'same-origin')
  assert.equal(request.options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.equal(Object.hasOwn(request.options.headers, 'Content-Type'), false)
  assert.equal(request.options.body instanceof FormData, true)
  assert.equal(request.options.body.get('file').name, 'notes.txt')
  assert.deepEqual(result, { id: 9, name: 'notes.txt', type: 'FILE' })
})
