import test from 'node:test'
import assert from 'node:assert/strict'
import { createDirectory, deleteDirectory, deleteFile, deleteFiles, downloadFile, downloadFiles, listFolderTree, moveFiles, restoreFile, restoreFiles, listTrash, listFiles, renameDirectory, quickCheckFile, uploadFile, previewFile } from './files.js'

test('folder tree reads current user hierarchy and handles 204 and 404', async () => {
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/files/file/tree')
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.cache, 'no-store')
    return Response.json({ success: true, data: [{ id: 1, parentId: 0, name: '根目录', children: [] }] })
  }
  assert.equal((await listFolderTree())[0].name, '根目录')
  globalThis.fetch = async () => new Response(null, { status: 204 })
  assert.deepEqual(await listFolderTree(), [])
  globalThis.fetch = async () => Response.json({ success: false, code: 'FILE_NOT_FOUND', message: '资源不存在' }, { status: 404 })
  await assert.rejects(listFolderTree(), error => error.status === 404 && error.code === 'FILE_NOT_FOUND')
})

test('download sends encoded name and exact file id, then saves returned bytes', async () => {
  const clicks = []
  const originalDocument = globalThis.document
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  globalThis.document = {
    body: { append(link) { clicks.push(link.download) } },
    createElement() { return { style: {}, click() { clicks.push('clicked') }, remove() {} } }
  }
  URL.createObjectURL = () => 'blob:test'
  URL.revokeObjectURL = () => {}
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/api/v1/files/file/download?filename=%E8%AF%B4%E6%98%8E.txt&fileId=12')
      assert.equal(options.credentials, 'same-origin')
      assert.equal(options.cache, 'no-store')
      return new Response('原文', { headers: { 'Content-Type': 'application/octet-stream' } })
    }
    await downloadFile({ filename: '说明.txt', fileId: 12 })
    assert.deepEqual(clicks, ['说明.txt', 'clicked'])
  } finally {
    globalThis.document = originalDocument
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  }
})

test('download maps parameter, missing resource and other failures', async () => {
  for (const [status, code, message] of [
    [401, 'INVALID_PARAM', '请求参数错误'],
    [404, 'FILE_NOT_FOUND', '资源不存在'],
    [503, 'FILE_STORAGE_UNAVAILABLE', '下载失败，请稍后重试']
  ]) {
    globalThis.fetch = async () => Response.json({ success: false, code }, { status })
    await assert.rejects(downloadFile({ filename: 'a.txt', fileId: 1 }), error =>
      error.status === status && error.code === code && error.message === message)
  }
})

test('batch download posts selected ids with CSRF and saves one ZIP', async () => {
  const originalDocument = globalThis.document
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  const saved = []
  globalThis.document = {
    cookie: '',
    body: { append(link) { saved.push(link.download) } },
    createElement() { return { style: {}, click() {}, remove() {} } }
  }
  URL.createObjectURL = () => 'blob:zip'
  URL.revokeObjectURL = () => {}
  try {
    globalThis.fetch = async (url, options = {}) => {
      if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
      assert.equal(url, '/api/v1/files/files/download')
      assert.equal(options.method, 'POST')
      assert.equal(options.headers['X-XSRF-TOKEN'], 'csrf-value')
      assert.deepEqual(JSON.parse(options.body), { ids: [12, 13], downloadName: 'files' })
      return new Response('archive')
    }
    await downloadFiles([12, 13])
    assert.deepEqual(saved, ['files.zip'])
  } finally {
    globalThis.document = originalDocument
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  }
})

test('move posts selected ids and target folder with CSRF protection', async () => {
  const requests = []
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    requests.push({ url, options })
    return Response.json({ success: true, data: [{ id: 12, name: 'a（1）.md' }] })
  }

  const result = await moveFiles([12, 13], 5)

  assert.deepEqual(result, [{ id: 12, name: 'a（1）.md' }])
  assert.equal(requests[0].url, '/api/v1/files/move')
  assert.equal(requests[0].options.method, 'POST')
  assert.equal(requests[0].options.credentials, 'same-origin')
  assert.equal(requests[0].options.headers['Content-Type'], 'application/json')
  assert.equal(requests[0].options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.deepEqual(JSON.parse(requests[0].options.body), { fileIds: [12, 13], targetParentId: 5 })
})

test('move preserves msg error text from the API', async () => {
  globalThis.fetch = async url => url === '/api/v1/auth/csrf'
    ? Response.json({ success: true, data: 'csrf-value' })
    : Response.json({ success: false, code: 'INVALID_PARAM', msg: '目标节点必须是文件夹' }, { status: 400 })

  await assert.rejects(moveFiles([12], 5), error =>
    error.status === 400 && error.code === 'INVALID_PARAM' && error.message === '目标节点必须是文件夹')
})

test('preview sends fileId and credentials without caching and preserves complete content', async () => {
  const signal = new AbortController().signal
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/files/file/preview?fileId=12')
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.signal, signal)
    return Response.json({ success: true, data: { fileId: 12, name: 'a.txt', format: 'txt', content: '中文\n  <script>' } })
  }
  assert.equal((await previewFile('12', { signal })).content, '中文\n  <script>')
})

test('preview preserves errors and rejects interrupted or malformed responses', async () => {
  for (const status of [400, 401, 404, 413, 415, 503]) {
    globalThis.fetch = async () => Response.json({ success: false, code: 'FAIL', message: '失败' }, { status })
    await assert.rejects(previewFile(12), error => error.status === status && error.code === 'FAIL')
  }
  globalThis.fetch = async () => new Response('{"success":true,')
  await assert.rejects(previewFile(12), /预览失败/)
})

test('empty upload reaches server and keeps EMPTY_FILE distinct from expired authentication', async () => {
  globalThis.fetch = async url => url === '/api/v1/auth/csrf'
    ? Response.json({ success: true, data: 'csrf-value' })
    : Response.json({ success: false, code: 'EMPTY_FILE', message: '不能上传空文件' }, { status: 401 })
  await assert.rejects(uploadFile({ file: new File([], 'empty.txt') }), e => e.status === 401 && e.code === 'EMPTY_FILE')
})

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

  const request = requests.find(entry => entry.url === '/api/v1/files/directories')
  assert.equal(request.options.method, 'POST')
  assert.equal(request.options.credentials, 'same-origin')
  assert.equal(request.options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.deepEqual(JSON.parse(request.options.body), { parentId: 18, folderName: 'Reports' })
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

test('quick-check sends only file metadata and returns the server decision', async () => {
  const requests = []
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/v1/auth/csrf') return Response.json({ success: true, data: 'csrf-value' })
    requests.push({ url, options })
    return Response.json({ success: true, data: { exist: true, fileId: 20086 } })
  }

  const result = await quickCheckFile({ file: new File(['hello'], 'notes.txt'), parentId: 1001 })

  assert.equal(result.exist, true)
  assert.equal(result.fileId, 20086)
  assert.equal(requests[0].url, '/api/v1/files/quick-check')
  assert.equal(requests[0].options.method, 'POST')
  assert.equal(requests[0].options.headers['Content-Type'], 'application/json')
  assert.equal(requests[0].options.headers['X-XSRF-TOKEN'], 'csrf-value')
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    fileHash: '5d41402abc4b2a76b9719d911017c592',
    fileSize: 5,
    fileName: 'notes.txt',
    parentFolderId: 1001
  })
})

test('reports browser upload progress through XMLHttpRequest', async () => {
  const originalXHR = globalThis.XMLHttpRequest
  const progress = []
  class FakeXMLHttpRequest {
    constructor() {
      this.upload = { addEventListener: (event, handler) => { this.progressHandler = handler } }
      this.handlers = {}
      this.status = 0
      this.responseText = ''
    }

    open(method, url) {
      assert.equal(method, 'POST')
      assert.equal(url, '/api/v1/files/upload')
    }

    setRequestHeader() {}

    addEventListener(event, handler) {
      this.handlers[event] = handler
    }

    send(body) {
      assert.equal(body instanceof FormData, true)
      this.progressHandler({ lengthComputable: true, loaded: 5, total: 10 })
      this.status = 201
      this.responseText = JSON.stringify({ success: true, data: { id: 10, name: 'notes.txt' } })
      this.handlers.load()
    }
  }

  globalThis.XMLHttpRequest = FakeXMLHttpRequest
  globalThis.fetch = async url => url === '/api/v1/auth/csrf'
    ? Response.json({ success: true, data: 'csrf-value' })
    : Response.json({ success: true, data: {} })
  try {
    const result = await uploadFile({
      file: new File(['hello'], 'notes.txt', { type: 'text/plain' }),
      onProgress: value => progress.push(value)
    })
    assert.deepEqual(progress, [50, 100])
    assert.deepEqual(result, { id: 10, name: 'notes.txt' })
  } finally {
    globalThis.XMLHttpRequest = originalXHR
  }
})
