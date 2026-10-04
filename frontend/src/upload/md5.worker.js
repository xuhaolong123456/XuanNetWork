import { incrementalMd5 } from './incrementalMd5.js'

self.onmessage = async ({ data: file }) => {
  try {
    const hash = await incrementalMd5(file, progress => self.postMessage({ progress }))
    self.postMessage({ hash })
  } catch {
    self.postMessage({ error: '文件读取失败，请重新选择文件' })
  }
}
