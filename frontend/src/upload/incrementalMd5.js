import SparkMD5 from 'spark-md5'

export async function incrementalMd5(file, onProgress, blockSize = 8 * 1024 * 1024) {
  const hash = new SparkMD5.ArrayBuffer()
  try {
    // 分段读取并累计完整文件指纹，内存占用不随文件总大小增长。
    for (let offset = 0; offset < file.size; offset += blockSize) {
      hash.append(await file.slice(offset, Math.min(offset + blockSize, file.size)).arrayBuffer())
      onProgress?.(Math.round(Math.min(offset + blockSize, file.size) / file.size * 100))
    }
    return hash.end()
  } finally {
    hash.destroy()
  }
}
