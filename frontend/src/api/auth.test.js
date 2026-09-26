import test from 'node:test'
import assert from 'node:assert/strict'

test('uses the current CSRF cookie after another tab rotates it', async () => {
  const { csrfHeaders } = await import('./auth.js?test=rotation')
  globalThis.document = { cookie: 'XSRF-TOKEN=old-token' }
  globalThis.fetch = async () => { throw new Error('Existing cookie needs no request') }
  try {
    assert.equal((await csrfHeaders())['X-XSRF-TOKEN'], 'old-token')
    document.cookie = 'other=value; XSRF-TOKEN=new-token'
    assert.equal((await csrfHeaders())['X-XSRF-TOKEN'], 'new-token')
  } finally {
    delete globalThis.document
  }
})

test('fetches a fresh token when the cookie disappears instead of reusing memory', async () => {
  const { csrfHeaders } = await import('./auth.js?test=missing')
  globalThis.document = { cookie: 'XSRF-TOKEN=old-token' }
  let requests = 0
  globalThis.fetch = async (url, options) => {
    requests++
    assert.equal(url, '/api/v1/auth/csrf')
    assert.equal(options.credentials, 'same-origin')
    document.cookie = 'XSRF-TOKEN=fresh-token'
    return Response.json({ success: true, data: 'fresh-token' })
  }
  try {
    await csrfHeaders()
    document.cookie = ''
    assert.equal((await csrfHeaders())['X-XSRF-TOKEN'], 'fresh-token')
    assert.equal(requests, 1)
  } finally {
    delete globalThis.document
  }
})

test('concurrent token requests share issuance and recover after a failed request', async () => {
  const { csrfHeaders } = await import('./auth.js?test=concurrent')
  globalThis.document = { cookie: '' }
  let requests = 0
  globalThis.fetch = async () => {
    requests++
    if (requests === 1) return Response.json({ success: false, message: '暂不可用' }, { status: 503 })
    return Response.json({ success: true, data: 'new-token' })
  }
  try {
    const failures = await Promise.allSettled([csrfHeaders(), csrfHeaders()])
    assert.ok(failures.every(result => result.status === 'rejected'))
    assert.equal(requests, 1)
    const headers = await Promise.all([csrfHeaders(), csrfHeaders()])
    assert.ok(headers.every(value => value['X-XSRF-TOKEN'] === 'new-token'))
    assert.equal(requests, 2)
  } finally {
    delete globalThis.document
  }
})
