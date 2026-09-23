export async function register(payload) {
  // 验证码随注册请求提交，最终以服务端校验结果为准。
  const response = await fetch('/api/v1/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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
  const response = await fetch('/api/v1/auth/email-code', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email })
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) throw new Error(result?.message || '验证码发送失败')
  return result
}

export async function login(payload) {
  const response = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '登录失败，请稍后重试')
    error.code = result?.code
    error.status = response.status
    error.retryAfter = Number(response.headers.get('Retry-After') || 0)
    throw error
  }
  return result.data
}

export async function getCaptcha(username) {
  const response = await fetch(`/api/v1/auth/captcha?username=${encodeURIComponent(username)}`)
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '验证码加载失败，请稍后重试')
    error.code = result?.code
    error.status = response.status
    throw error
  }
  return result.data
}
