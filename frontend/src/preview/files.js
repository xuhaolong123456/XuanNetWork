export const MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024
export const SUPPORTED_UPLOAD_EXTENSIONS = ['txt', 'docx', 'csv', 'xlsx', 'pdf', 'md', 'html', 'pptx']

export function validateUpload(file, { direct = true } = {}) {
  if (!Number.isSafeInteger(file.size) || file.size < 0) throw new Error('文件大小不合法')
  if (direct && file.size > MAX_FILE_SIZE) throw new Error('直传文件大小不能超过 2 GiB')
  const lastDot = file.name?.lastIndexOf('.') ?? -1
  const extension = lastDot >= 0 ? file.name.slice(lastDot + 1).toLowerCase() : ''
  if (!SUPPORTED_UPLOAD_EXTENSIONS.includes(extension)) {
    throw new Error('支持 txt、docx、csv、xlsx、pdf、md、html、pptx 文件')
  }
}

export function isLoginExpired(error) {
  return error.status === 401 && error.code !== 'EMPTY_FILE'
}
