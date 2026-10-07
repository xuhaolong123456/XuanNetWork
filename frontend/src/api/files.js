import { csrfHeaders } from './auth.js'
import { validateUpload } from '../preview/files.js'
import { fileMd5 } from '../upload/md5.js'
import { apiFetch, LONG_REQUEST_TIMEOUT_MS } from './request.js'

export async function listFolderTree() {
  const response = await apiFetch('/api/v1/files/file/tree', {
    credentials: 'same-origin', cache: 'no-store'
  })
  if (response.status === 204) return []
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success || !Array.isArray(result.data)) {
    const error = new Error(result?.message || '文件夹树加载失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function uploadFile({ file, parentId = null, onProgress }) {
  validateUpload(file)
  if (typeof XMLHttpRequest === 'undefined') {
    return uploadFileWithFetch({ file, parentId })
  }

  const params = new URLSearchParams()
  if (parentId !== null && parentId !== undefined && parentId !== '') {
    params.set('parentId', String(parentId))
  }
  const query = params.toString() ? `?${params}` : ''
  const form = new FormData()
  form.append('file', file)
  const headers = await csrfHeaders()

  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('POST', `/api/v1/files/upload${query}`)
    request.withCredentials = true
    Object.entries(headers).forEach(([name, value]) => request.setRequestHeader(name, value))
    request.upload.addEventListener('progress', event => {
      if (event.lengthComputable) onProgress?.(Math.round((event.loaded / event.total) * 100))
    })
    request.addEventListener('load', () => {
      let result = null
      try {
        result = request.responseText ? JSON.parse(request.responseText) : null
      } catch {
        result = null
      }
      if (request.status < 200 || request.status >= 300 || !result?.success) {
        const error = new Error(result?.message || '文件上传失败，请稍后重试')
        error.status = request.status
        error.code = result?.code
        reject(error)
        return
      }
      onProgress?.(100)
      resolve(result.data)
    })
    request.addEventListener('error', () => reject(new Error('网络异常，文件上传失败')))
    request.addEventListener('abort', () => reject(new Error('文件上传已取消')))
    request.send(form)
  })
}

export async function quickCheckFile({ file, parentId = null, fileHash: suppliedHash, onHashProgress, uploadId }) {
  validateUpload(file, { direct: false })
  const fileHash = suppliedHash || await fileMd5(file, onHashProgress)
  const response = await apiFetch('/api/v1/files/quick-check', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    credentials: 'same-origin',
    body: JSON.stringify({
      fileHash,
      fileSize: file.size,
      fileName: file.name,
      ...(uploadId ? { uploadId } : {}),
      parentFolderId: parentId === null || parentId === '' ? null : Number(parentId)
    })
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '文件秒传预校验失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return { ...result.data, fileHash }
}

export async function downloadFile({ filename, fileId }) {
  const params = new URLSearchParams({ filename })
  if (fileId != null) params.set('fileId', String(fileId))
  const response = await apiFetch(`/api/v1/files/file/download?${params}`, {
    credentials: 'same-origin', cache: 'no-store', timeoutMs: LONG_REQUEST_TIMEOUT_MS
  })
  if (!response.ok) {
    const result = await response.json().catch(() => null)
    const error = new Error(response.status === 401 && result?.code === 'INVALID_PARAM'
      ? '请求参数错误' : response.status === 404 ? '资源不存在' : '下载失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  const blob = await response.blob()
  saveBlob(blob, filename)
}

export async function downloadFiles(ids, downloadName = 'files') {
  const response = await apiFetch('/api/v1/files/files/download', {
    method: 'POST', credentials: 'same-origin', cache: 'no-store',
    timeoutMs: LONG_REQUEST_TIMEOUT_MS,
    headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    body: JSON.stringify({ ids, downloadName })
  })
  if (!response.ok) {
    const result = await response.json().catch(() => null)
    const error = new Error(result?.msg || result?.message || (response.status === 404 ? '资源不存在' : '下载失败，请稍后重试'))
    error.status = response.status
    error.code = result?.code
    throw error
  }
  saveBlob(await response.blob(), `${downloadName || 'files'}.zip`)
}

export async function moveFiles(ids, targetParentId) {
  const response = await apiFetch('/api/v1/files/move', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) },
    credentials: 'same-origin',
    body: JSON.stringify({ fileIds: ids, targetParentId })
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.msg || result?.message || '文件移动失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    link.style.display = 'none'
    document.body.append(link)
    link.click()
    link.remove()
  } finally {
    const timer = setTimeout(() => URL.revokeObjectURL(url), 60000)
    timer.unref?.()
  }
}

export async function previewFile(fileId, { signal } = {}) {
  const params = new URLSearchParams({ fileId: String(fileId) })
  const response = await apiFetch(`/api/v1/files/file/preview?${params}`, {
    credentials: 'same-origin', cache: 'no-store', signal, timeoutMs: null
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.message || '文件预览失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function listFiles({ parentId = null, page = 0, size = 50 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (parentId !== null && parentId !== undefined && parentId !== '') {
    params.set('parentId', String(parentId))
  }
  const response = await apiFetch(`/api/v1/files?${params}`, { credentials: 'same-origin' })
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
  const response = await apiFetch('/api/v1/files/directories', {
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
  const response = await apiFetch(`/api/v1/files/directories/${encodeURIComponent(directoryId)}`, {
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
  const response = await apiFetch(`/api/v1/files${path}`, {
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
  const response = await apiFetch(`/api/v1/files/recycle-bin?${params}`, { credentials: 'same-origin' })
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) {
    const error = new Error(result?.msg || result?.message || '回收站加载失败，请稍后重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

async function uploadFileWithFetch({ file, parentId = null }) {
  validateUpload(file)
  const params = new URLSearchParams()
  if (parentId !== null && parentId !== undefined && parentId !== '') {
    params.set('parentId', String(parentId))
  }
  const query = params.toString() ? `?${params}` : ''
  const form = new FormData()
  form.append('file', file)
  const response = await apiFetch(`/api/v1/files/upload${query}`, {
    method: 'POST',
    timeoutMs: LONG_REQUEST_TIMEOUT_MS,
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
