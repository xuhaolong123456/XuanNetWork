import test, { afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { incrementalMd5 } from './incrementalMd5.js'
import { uploadParts, uploadSelectedFile, listUploadedParts } from './upload.js'
import { MAX_FILE_SIZE } from '../preview/files.js'

const originalFetch = globalThis.fetch
const originalStorage = globalThis.localStorage
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalStorage === undefined) delete globalThis.localStorage
  else globalThis.localStorage = originalStorage
})

const session = { uploadId: 'upload-test', chunkSize: 4, totalParts: 3 }
const file = () => new File(['abcdefghi'], 'large.txt')
function respond(parts, partNumber, total = 3) {
  return Response.json({ code: 200, msg: '分片上传成功', data: {
    partNumber, finishedPartList: parts, mergeFlag: parts.length === total ? 1 : 0
  } })
}
function mockFetch(handler) {
  globalThis.fetch = async (url, options) => url === '/api/v1/auth/csrf'
    ? Response.json({ success: true, data: 'csrf-value' }) : handler(url, options)
}

test('incremental MD5 agrees with full MD5 across block and padding boundaries without reading the whole file', async () => {
  for (const size of [0, 1, 55, 56, 63, 64, 65, 10000]) {
    const bytes = new Uint8Array(size).map((_, index) => index % 251)
    const blob = new Blob([bytes])
    blob.arrayBuffer = () => { throw new Error('不能整体读取文件') }
    assert.equal(await incrementalMd5(blob, null, 17), createHash('md5').update(bytes).digest('hex'))
  }
})

test('instant hit sends only full MD5 metadata, with no chunk requests or direct transfer', async () => {
  const calls = []
  mockFetch((url, options) => {
    calls.push(url)
    assert.equal(JSON.parse(options.body).fileHash, createHash('md5').update('abcdefghi').digest('hex'))
    return Response.json({ success: true, data: { exist: true, fileId: 99 } })
  })
  assert.deepEqual(await uploadSelectedFile({ file: file(), userId: 7 }), { instant: true, fileId: 99 })
  assert.deepEqual(calls, ['/api/v1/files/quick-check'])
})

test('small miss uses direct multipart upload and never the chunk API', async () => {
  const calls = []
  mockFetch((url, options) => {
    calls.push(url)
    return url.endsWith('quick-check')
      ? Response.json({ success: true, data: { exist: false } })
      : Response.json({ success: true, data: { id: 8 } })
  })
  assert.deepEqual(await uploadSelectedFile({ file: file(), userId: 7 }), { id: 8 })
  assert.deepEqual(calls, ['/api/v1/files/quick-check', '/api/v1/files/upload'])
})

test('resume trusts server part list, skips saved parts, and accepts the small last part', async () => {
  const calls = []
  const progress = []
  mockFetch((url, options) => {
    assert.equal(url, '/api/v1/files/file/chunk-upload')
    assert.equal(options.headers['X-XSRF-TOKEN'], 'csrf-value')
    const number = Number(options.body.get('partNumber'))
    calls.push(number)
    assert.equal(options.body.get('fileMd5'), 'a'.repeat(32))
    assert.equal(options.body.get('size_bytes'), '9')
    if (number === 1) return respond([1, 2], 1)
    assert.equal(options.body.get('chunk').size, 1)
    return respond([1, 2, 3], 3)
  })
  assert.deepEqual(await uploadParts({ file: file(), session, fileHash: 'a'.repeat(32), uploadedParts: [1, 2], onProgress: value => progress.push(value) }),
    { pendingMerge: true, uploadId: session.uploadId })
  assert.deepEqual(calls, [3])
  assert.equal(progress.at(-1), 100)
})

test('chunk workers cap parallelism and retry only failed requests', async () => {
  const attempts = new Map()
  const finished = new Set()
  let active = 0
  let peak = 0
  mockFetch(async (url, options) => {
    const number = Number(options.body.get('partNumber'))
    attempts.set(number, (attempts.get(number) || 0) + 1)
    if (number === 2 && attempts.get(number) === 1) throw new Error('网络抖动')
    peak = Math.max(peak, ++active)
    await new Promise(resolve => setTimeout(resolve, 5))
    active--
    finished.add(number)
    return respond([...finished], number, 8)
  })
  await uploadParts({ file: new File(['a'.repeat(32)], 'large.txt'), session: { ...session, totalParts: 8 }, fileHash: 'a'.repeat(32) })
  assert.ok(peak <= 3)
  assert.equal(attempts.get(2), 2)
  assert.equal(attempts.get(1), 1)
})

test('authentication failure is propagated immediately and does not retry', async () => {
  let calls = 0
  mockFetch(() => { calls++; return Response.json({ code: 401, msg: '用户身份校验失败', data: null }, { status: 401 }) })
  await assert.rejects(uploadParts({ file: new File(['abcd'], 'large.txt'), session: { ...session, totalParts: 1 }, fileHash: 'a'.repeat(32) }), error => error.status === 401)
  assert.equal(calls, 1)
})

test('large file miss persists backend session; reselection resumes and account switch cannot reuse it', async () => {
  const saved = new Map()
  globalThis.localStorage = { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) }
  // 用虚拟文件验证阈值与会话编排，哈希正确性由独立边界测试覆盖。
  const large = { name: 'large.txt', size: MAX_FILE_SIZE + 1, slice: () => new Blob(['x']) }
  let checks = 0
  const resumed = []
  mockFetch((url, options) => {
    if (url.includes('?identifier=')) return Response.json({ code: 200, data: {
      identifier: 'upload-large', totalParts: 2, uploadedChunks: [{ partNumber: 1 }, { partNumber: 2 }], truncated: false, nextPartNumberMarker: 0
    } })
    if (url.endsWith('quick-check')) {
      checks++
      resumed.push(JSON.parse(options.body).uploadId)
      return Response.json({ success: true, data: { exist: false, uploadId: 'upload-large', chunkSize: MAX_FILE_SIZE, totalParts: 2 } })
    }
    assert.equal(url, '/api/v1/files/file/chunk-upload')
    return respond([1, 2], Number(options.body.get('partNumber')), 2)
  })
  assert.equal((await uploadSelectedFile({ file: large, userId: 7 })).pendingMerge, true)
  await uploadSelectedFile({ file: large, userId: 7 })
  assert.equal(checks, 2)
  assert.equal(resumed[1], 'upload-large')
  await uploadSelectedFile({ file: large, userId: 8 })
  assert.equal(checks, 3)
  assert.equal(resumed[2], undefined)
})

test('a resumed file that now hits instant upload sends no additional chunk', async () => {
  const saved = new Map()
  globalThis.localStorage = { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) }
  const large = { name: 'large.txt', size: MAX_FILE_SIZE + 1, slice: () => new Blob(['x']) }
  let hit = false
  let chunks = 0
  mockFetch((url, options) => {
    if (url.includes('?identifier=')) return Response.json({ code: 200, data: {
      identifier: 'upload-large', totalParts: 2, uploadedChunks: [{ partNumber: 1 }, { partNumber: 2 }], truncated: false, nextPartNumberMarker: 0
    } })
    if (url.endsWith('quick-check')) return Response.json({ success: true, data: hit
      ? { exist: true, fileId: 88 } : { exist: false, uploadId: 'upload-large', chunkSize: MAX_FILE_SIZE, totalParts: 2 } })
    chunks++
    return respond([1, 2], Number(options.body.get('partNumber')), 2)
  })
  await uploadSelectedFile({ file: large, userId: 7 })
  const previousChunks = chunks
  hit = true
  assert.equal((await uploadSelectedFile({ file: large, userId: 7 })).instant, true)
  assert.equal(chunks, previousChunks)
})

test('list parts follows exclusive cursor pages using read-only GET without binary bodies', async () => {
  const markers = []
  mockFetch((url, options) => {
    assert.equal(options.body, undefined)
    assert.equal(options.method, undefined)
    assert.equal(options.cache, 'no-store')
    assert.equal(options.credentials, 'same-origin')
    const marker = Number(new URL(url, 'http://localhost').searchParams.get('partNumberMarker'))
    markers.push(marker)
    return Response.json({ code: 200, data: { identifier: 'upload-test', totalParts: 6,
      uploadedChunks: marker === 0 ? [{ partNumber: 1 }, { partNumber: 4 }] : [{ partNumber: 6 }],
      truncated: marker === 0, nextPartNumberMarker: marker === 0 ? 4 : 0 } })
  })
  assert.deepEqual(await listUploadedParts('upload-test'), { totalParts: 6, uploadedParts: [1, 4, 6] })
  assert.deepEqual(markers, [0, 4])
})

test('invalid pagination cursor stops without endlessly fetching pages', async () => {
  let calls = 0
  mockFetch(() => {
    calls++
    return Response.json({ code: 200, data: { identifier: 'upload-test', totalParts: 6,
      uploadedChunks: [{ partNumber: 1 }], truncated: true, nextPartNumberMarker: 0 } })
  })
  await assert.rejects(listUploadedParts('upload-test'), /游标/)
  assert.equal(calls, 1)
})

test('expired resume starts fresh; auth and ownership errors clear cache; 503 preserves cache', async () => {
  for (const code of [40001, 401, 40301, 503]) {
    const saved = new Map()
    globalThis.localStorage = { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) }
    const large = { name: 'large.txt', size: MAX_FILE_SIZE + 1, slice: () => new Blob(['x']) }
    let checks = 0
    let chunks = 0
    let failing = false
    mockFetch((url, options) => {
      if (url.includes('?identifier=')) return Response.json({ code, msg: '测试查询失败', data: null }, { status: code === 40001 ? 400 : code === 40301 ? 403 : code })
      if (url.endsWith('quick-check')) {
        checks++
        if (failing) assert.equal(JSON.parse(options.body).uploadId, undefined)
        return Response.json({ success: true, data: { exist: false, uploadId: 'upload-large', chunkSize: MAX_FILE_SIZE, totalParts: 2 } })
      }
      chunks++
      return respond([1, 2], Number(options.body.get('partNumber')), 2)
    })
    await uploadSelectedFile({ file: large, userId: 7 })
    const previousChunks = chunks
    failing = true
    if (code === 40001) {
      await uploadSelectedFile({ file: large, userId: 7 })
      assert.equal(checks, 2)
    } else {
      await assert.rejects(uploadSelectedFile({ file: large, userId: 7 }), error => error.code === code)
      assert.equal(checks, 1)
      assert.equal(chunks, previousChunks)
      assert.equal(saved.size, code === 503 ? 1 : 0)
    }
  }
})
