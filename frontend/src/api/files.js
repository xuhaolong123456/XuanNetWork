export async function listFiles({ parentId = null, page = 0, size = 50 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (parentId !== null && parentId !== undefined && parentId !== '') {
    params.set('parentId', String(parentId))
  }
  const response = await fetch(`/api/v1/files?${params}`, { credentials: 'same-origin' })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '文件列表加载失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}
