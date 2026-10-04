import { incrementalMd5 } from './incrementalMd5.js'

export function fileMd5(file, onProgress) {
  if (typeof Worker === 'undefined') return incrementalMd5(file, onProgress)
  return new Promise((resolve, reject) => {
    // 浏览器在独立线程计算指纹，超大文件不会阻塞界面交互。
    const worker = new Worker(new URL('./md5.worker.js', import.meta.url), { type: 'module' })
    worker.onmessage = ({ data }) => {
      if (data.progress !== undefined) onProgress?.(data.progress)
      else {
        worker.terminate()
        if (data.error) reject(new Error(data.error))
        else resolve(data.hash)
      }
    }
    worker.onerror = () => {
      worker.terminate()
      reject(new Error('文件指纹计算失败，请重新选择文件'))
    }
    worker.postMessage(file)
  })
}
