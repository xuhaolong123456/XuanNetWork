import { apiFetch } from './request.js'

let csrfToken = ''
let csrfRequest = null

// 清理迁移到 HttpOnly Cookie 认证前遗留的、可被 JavaScript 读取的令牌。
if (typeof localStorage !== 'undefined') localStorage.removeItem('access_token')

async function getCsrfToken(forceRefresh = false) {
  // 此接口会让 Spring 写入可读取的 XSRF-TOKEN Cookie；认证 Cookie 仍保持 HttpOnly。
  if (csrfToken && !forceRefresh) return csrfToken
  // 多个组件同时请求时共用一次签发，避免响应先后覆盖 Cookie。
  if (!csrfRequest) {
    csrfRequest = (async () => {
      const response = await apiFetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
      const result = await response.json().catch(() => null)
      if (!response.ok || !result?.success) throw new Error(result?.message || '安全令牌获取失败，请刷新页面')
      csrfToken = result.data
      return csrfToken
    })().finally(() => { csrfRequest = null })
  }
  return csrfRequest
}

export async function csrfHeaders() {
  // Cookie 可能在退出登录或其他标签页操作后变化，请求头必须使用当前值。
  if (typeof document !== 'undefined') {
    const cookie = document.cookie.split(';').map(value => value.trim())
      .find(value => value.startsWith('XSRF-TOKEN='))
    csrfToken = cookie ? decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) : ''
  }
  return { 'X-XSRF-TOKEN': csrfToken || await getCsrfToken() }
}

export async function register(payload) {
  // 验证码随注册请求提交，最终以服务端校验结果为准。
  const response = await apiFetch('/api/v1/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    credentials: 'same-origin',
    body: JSON.stringify(payload)
  })

  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    throw new Error(result?.message || '网络异常，请稍后重试')
  }
  return result
}

export async function sendEmailCode(email) {
  // 前端不生成验证码，只负责调用后端发送接口。
  const response = await apiFetch('/api/v1/auth/email-code', {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    credentials: 'same-origin', body: JSON.stringify({ email })
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) throw new Error(result?.message || '验证码发送失败')
  return result
}

const DEVICE_ID_STORAGE_KEY = 'login_device_id'

function createDeviceId() {
  // 优先使用浏览器 UUID API；不支持时按 UUID v4 位规则生成随机标识。
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function deviceId() {
  // 同一浏览器复用设备标识，使服务端可以跨请求累计设备级登录频率。
  let value = localStorage.getItem(DEVICE_ID_STORAGE_KEY)
  if (!value) {
    value = createDeviceId()
    localStorage.setItem(DEVICE_ID_STORAGE_KEY, value)
  }
  return value
}

export async function login(payload) {
  const sendLogin = async () => apiFetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Device-Id': deviceId(), ...(await csrfHeaders()) },
    credentials: 'same-origin',
    body: JSON.stringify(payload)
  })
  let response = await sendLogin()
  let result = await response.json().catch(() => null)

  // 退出登录或安全过滤器的响应可能使内存中的 CSRF 令牌失效。
  // 登录处理器尚未执行时，安全过滤器可能先返回不带业务错误码的 403。
  if (response.status === 403 && !result?.code) {
    await getCsrfToken(true)
    response = await sendLogin()
    result = await response.json().catch(() => null)
  }
  if (!response.ok || !result?.success) {
    const fallback = response.status === 403
      ? '安全校验失败，请刷新页面后重试'
      : response.status >= 500
        ? '登录服务暂不可用，请稍后重试'
        : `登录失败（HTTP ${response.status}），请检查输入后重试`
    const error = new Error(result?.message || fallback)
    error.code = result?.code
    error.status = response.status
    error.retryAfter = Number(response.headers.get('Retry-After') || 0)
    throw error
  }
  return result.data
}

export async function getCurrentUser() {
  const response = await apiFetch('/api/v1/auth/me', { credentials: 'same-origin' })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '登录已失效，请重新登录')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function getCaptcha(username) {
  const response = await apiFetch(`/api/v1/auth/captcha?username=${encodeURIComponent(username)}`, {
    headers: { 'X-Device-Id': deviceId() }
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '验证码加载失败，请稍后重试')
    error.code = result?.code
    error.status = response.status
    throw error
  }
  return result.data
}

async function authenticatedPost(path, payload) {
  const response = await apiFetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(await csrfHeaders())
    },
    credentials: 'same-origin',
    ...(payload ? { body: JSON.stringify(payload) } : {})
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '操作失败，请稍后重试')
    error.code = result?.code
    error.status = response.status
    throw error
  }
  return result.data
}

export function changePassword(currentPassword, newPassword) {
  return authenticatedPost('/api/v1/auth/change-password', { currentPassword, newPassword })
}

export function logout() {
  return authenticatedPost('/api/v1/auth/logout')
}

export function clearLoginSession() {
  csrfToken = ''
  localStorage.removeItem('access_token')
  localStorage.removeItem('current_user')
}
