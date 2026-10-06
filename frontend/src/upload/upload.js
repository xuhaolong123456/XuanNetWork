import { csrfHeaders } from '../api/auth.js'
import { quickCheckFile, uploadFile } from '../api/files.js'
import { MAX_FILE_SIZE, validateUpload } from '../preview/files.js'
import { fileMd5 } from './md5.js'
import { apiFetch, LONG_REQUEST_TIMEOUT_MS } from '../api/request.js'

function sessionKey(userId, parentId, file, hash) {
  return 'chunk-session:' + JSON.stringify([userId, parentId, file.name, file.size, hash])
}

function loadSession(key) {
  try {
    const session = JSON.parse(globalThis.localStorage?.getItem(key) || 'null')
    if (session?.uploadId) return session
    globalThis.localStorage?.removeItem(key)
  } catch { /* 浏览器存储不可用时仍允许新上传。 */ }
  return null
}

function removeSession(key) {
  try { globalThis.localStorage?.removeItem(key) }
  catch { /* 本地存储不可用时仍终止错误会话的后续传输。 */ }
}

export async function listUploadedParts(identifier) {
  const uploaded = new Set()
  let marker = 0
  let totalParts
  for (;;) {
    const params = new URLSearchParams({ identifier, maxParts: '1000', partNumberMarker: String(marker) })
    const response = await apiFetch(`/api/v1/files/file/chunk-upload?${params}`, {
      credentials: 'same-origin', cache: 'no-store', headers: await csrfHeaders(), timeoutMs: LONG_REQUEST_TIMEOUT_MS
    })
    const result = await response.json().catch(() => null)
    if (!response.ok || result?.code !== 200) {
      const error = new Error(result?.msg || '已上传分片查询失败，请稍后重试')
      error.status = response.status
      error.code = result?.code
      throw error
    }
    const data = result.data
    if (data.identifier !== identifier || !Number.isSafeInteger(data.totalParts) || data.totalParts < 1
        || !Array.isArray(data.uploadedChunks) || (totalParts !== undefined && totalParts !== data.totalParts)) {
      throw new Error('分片查询响应不合法')
    }
    totalParts = data.totalParts
    let previous = marker
    for (const part of data.uploadedChunks) {
      if (!Number.isSafeInteger(part.partNumber) || part.partNumber <= previous || part.partNumber > totalParts) {
        throw new Error('分片查询响应不合法')
      }
      previous = part.partNumber
      uploaded.add(part.partNumber)
    }
    if (!data.truncated) return { totalParts, uploadedParts: [...uploaded] }
    // 拒绝停滞或倒退的游标，避免异常响应引发无限翻页。
    if (!Number.isSafeInteger(data.nextPartNumberMarker) || data.nextPartNumberMarker <= marker
        || data.nextPartNumberMarker !== previous) throw new Error('分片分页游标不合法')
    marker = data.nextPartNumberMarker
  }
}

function saveSession(key, session) {
  try {
    globalThis.localStorage?.setItem(key, JSON.stringify(session))
  } catch { /* 存储配额或隐私模式限制不应中断当前传输。 */ }
}

export async function uploadChunk({ file, session, fileHash, partNumber }) {
  const form = new FormData()
  form.append('name', file.name)
  form.append('node_type', 'FILE')
  form.append('size_bytes', String(file.size))
  form.append('uploadId', session.uploadId)
  form.append('partNumber', String(partNumber))
  form.append('fileMd5', fileHash)
  const offset = (partNumber - 1) * session.chunkSize
  form.append('chunk', file.slice(offset, Math.min(offset + session.chunkSize, file.size)), file.name)
  const response = await apiFetch('/api/v1/files/file/chunk-upload', {
    method: 'POST', credentials: 'same-origin', headers: await csrfHeaders(), body: form, timeoutMs: LONG_REQUEST_TIMEOUT_MS
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || result?.code !== 200) {
    const error = new Error(result?.msg || '分片上传失败，请重新选择文件续传')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

async function retryChunk(request) {
  for (let attempt = 0; ; attempt++) {
    try { return await uploadChunk(request) }
    catch (error) {
      // 仅重试网络或服务端暂时失败，认证及业务校验错误立即停止。
      if (attempt >= 2 || (error.status && error.status < 500)) throw error
      await new Promise(resolve => setTimeout(resolve, 300 * (attempt + 1)))
    }
  }
}

export async function uploadParts({ file, session, fileHash, onProgress, uploadedParts = [] }) {
  if (!session?.uploadId || !Number.isSafeInteger(session.chunkSize) || session.chunkSize < 1
      || session.totalParts !== Math.ceil(file.size / session.chunkSize)) throw new Error('上传会话信息不合法')
  // 已完成集合仅由只读 List-Parts 提供；新会话从空集合开始。
  const finished = new Set(uploadedParts)
  let ready = finished.size === session.totalParts
  const progress = () => onProgress?.(Math.round(finished.size / session.totalParts * 100))
  progress()
  let nextPart = 1
  let failure = null
  async function worker() {
    while (!failure) {
      const partNumber = nextPart++
      if (partNumber > session.totalParts) return
      if (finished.has(partNumber)) continue
      try {
        const response = await retryChunk({ file, session, fileHash, partNumber })
        for (const part of response.finishedPartList) finished.add(part)
        ready ||= response.mergeFlag === 1
        progress()
      } catch (error) { failure ||= error }
    }
  }
  // 等待所有已发出的请求结束，失败后不再启动新分片，最多并发三个请求。
  await Promise.all([worker(), worker(), worker()])
  if (failure) throw failure
  if (!ready || finished.size !== session.totalParts) throw new Error('分片尚未全部就位，请重新选择文件续传')
  const response = await apiFetch(`/api/v1/files/file/chunk-upload/complete?uploadId=${encodeURIComponent(session.uploadId)}`, {
    method: 'POST', credentials: 'same-origin', headers: await csrfHeaders(), timeoutMs: LONG_REQUEST_TIMEOUT_MS
  })
  const result = await response.json().catch(() => null)
  if (!response.ok || result?.code !== 200 || !result.data?.id) {
    const error = new Error(result?.msg || '分片合并失败，请重新选择文件重试')
    error.status = response.status
    error.code = result?.code
    throw error
  }
  return result.data
}

export async function uploadSelectedFile({ file, parentId = null, userId, onProgress, onHashProgress }) {
  validateUpload(file, { direct: false })
  const hash = await fileMd5(file, onHashProgress)
  const key = sessionKey(userId, parentId, file, hash)
  let session = file.size > MAX_FILE_SIZE ? loadSession(key) : null
  let uploadedParts = []
  if (session) {
    try {
      const listed = await listUploadedParts(session.uploadId)
      if (listed.totalParts !== session.totalParts) throw new Error('上传会话分片数量不一致')
      uploadedParts = listed.uploadedParts
    } catch (error) {
      if ([401, 40301].includes(Number(error.code)) || error.status === 401) {
        removeSession(key)
        throw error
      }
      if (Number(error.code) !== 40001) throw error
      removeSession(key)
      session = null
    }
  }
  let check
  try {
    // 恢复会话也先检查秒传；已存在的完整文件不会触发任何分片传输。
    check = await quickCheckFile({ file, parentId, fileHash: hash, uploadId: session?.uploadId })
  } catch (error) {
    if ([401, 40301].includes(Number(error.code)) || error.status === 401) {
      removeSession(key)
      throw error
    }
    if (!session || Number(error.code) !== 40001 || error.message !== 'uploadId不存在或者非法') throw error
    session = null
    uploadedParts = []
    removeSession(key)
    check = await quickCheckFile({ file, parentId, fileHash: hash })
  }
  if (check.exist) {
    removeSession(key)
    return { instant: true, fileId: check.fileId }
  }
  if (file.size <= MAX_FILE_SIZE) {
    return await uploadFile({ file, parentId, onProgress })
  }
  session = { uploadId: check.uploadId, chunkSize: check.chunkSize, totalParts: check.totalParts,
    createdAt: session?.createdAt || Date.now(), fileHash: hash, fileSize: file.size, fileName: file.name, parentFolderId: parentId }
  saveSession(key, session)
  try {
    const result = await uploadParts({ file, session, fileHash: hash, onProgress, uploadedParts })
    removeSession(key)
    return result
  }
  catch (error) {
    if ([401, 40301].includes(Number(error.code)) || error.status === 401) removeSession(key)
    throw error
  }
}
