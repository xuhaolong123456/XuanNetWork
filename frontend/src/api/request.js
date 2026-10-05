export const API_TIMEOUT_MS = 15000
export const LONG_REQUEST_TIMEOUT_MS = 10 * 60 * 1000

export async function apiFetch(input, options = {}) {
  if (options.timeoutMs === null) {
    const { timeoutMs: _timeoutMs, ...fetchOptions } = options
    return fetch(input, fetchOptions)
  }

  const controller = new AbortController()
  const timeoutMs = options.timeoutMs ?? API_TIMEOUT_MS
  const externalSignal = options.signal
  const abortFromExternal = () => controller.abort(externalSignal.reason)

  if (externalSignal?.aborted) abortFromExternal()
  else externalSignal?.addEventListener('abort', abortFromExternal, { once: true })

  const timeout = setTimeout(() => controller.abort(new DOMException('请求超时', 'TimeoutError')), timeoutMs)
  try {
    const { timeoutMs: _timeoutMs, signal: _signal, ...fetchOptions } = options
    return await fetch(input, { ...fetchOptions, signal: controller.signal })
  } catch (error) {
    if (controller.signal.aborted && !externalSignal?.aborted) {
      const timeoutError = new Error('服务器响应超时，请稍后重试')
      timeoutError.name = 'TimeoutError'
      throw timeoutError
    }
    throw error
  } finally {
    clearTimeout(timeout)
    externalSignal?.removeEventListener('abort', abortFromExternal)
  }
}
