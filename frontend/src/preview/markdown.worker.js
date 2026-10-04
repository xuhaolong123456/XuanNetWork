import { renderMarkdown } from './markdown.js'

// 大文件解析在工作线程进行，用户仍可返回或取消预览。
self.onmessage = event => {
  try {
    self.postMessage({ html: renderMarkdown(event.data) })
  } catch {
    self.postMessage({ error: 'Markdown 渲染失败，请重试' })
  }
}
