import { csrfHeaders } from './auth.js'

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

export async function createDirectory({ folderName, parentId = null }) {
  const response = await fetch('/api/v1/files/directories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    credentials: 'same-origin',
    body: JSON.stringify({ parentId, folderName })
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '文件夹创建失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function renameDirectory(directoryId, folderName) {
  const response = await fetch(`/api/v1/files/directories/${encodeURIComponent(directoryId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    credentials: 'same-origin',
    body: JSON.stringify({ folderName })
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '文件夹重命名失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function deleteDirectory(directoryId) {
  return metadataOperation(`/directories/${encodeURIComponent(directoryId)}`, 'DELETE')
}

export const deleteFile = id => metadataOperation(`/file/${encodeURIComponent(id)}`, 'PUT')
export const deleteFiles = ids => metadataOperation('/files', 'PUT', { ids })
export const restoreFile = id => metadataOperation(`/recover/${encodeURIComponent(id)}`, 'PUT')
export const restoreFiles = ids => metadataOperation('/recover/batch', 'PUT', { ids })

async function metadataOperation(path, method, body) {
  const response = await fetch(`/api/v1/files${path}`, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(await csrfHeaders()) },
    credentials: 'same-origin',
    ...(body ? { body: JSON.stringify(body) } : {})
  })
  if (response.status === 204) return null
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.msg || result?.message || '文件操作失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function listTrash({ page = 0, size = 50 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const response = await fetch(`/api/v1/files/recycle-bin?${params}`, { credentials: 'same-origin' })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.msg || result?.message || '回收站加载失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function uploadFile({ file, parentId = null }) {
  const params = new URLSearchParams()
  if (parentId !== null && parentId !== undefined && parentId !== '') {
    params.set('parentId', String(parentId))
  }
  const query = params.toString() ? `?${params}` : ''
  const form = new FormData()
  form.append('file', file)
  const response = await fetch(`/api/v1/files/upload${query}`, {
    method: 'POST',
    headers: await csrfHeaders(),
    credentials: 'same-origin',
    body: form
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '文件上传失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}
