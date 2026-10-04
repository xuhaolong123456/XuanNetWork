<template>
  <div class="preview-page">
    <header class="preview-header">
      <button type="button" class="preview-back" @click="goBack">← 返回文件列表</button>
      <span class="preview-download-wrap" :title="file?.downloadAllowed === false ? '该文件不支持下载' : '下载文件'"><button type="button" class="preview-download" :disabled="!file || file.downloadAllowed === false || downloading" @click="downloadCurrent">{{ downloading ? '下载中…' : '↓ 下载' }}</button></span>
      <div class="preview-heading">
        <p>文件预览 <span v-if="file">/ {{ file.format.toUpperCase() }}</span></p>
        <h1>{{ file?.name || '正在打开文件' }}</h1>
      </div>
    </header>
    <p v-if="downloadMessage" class="preview-download-message" :role="downloadError ? 'alert' : 'status'">{{ downloadMessage }}</p>
    <section class="preview-paper" :aria-busy="loading">
      <div v-if="loading" class="preview-state" role="status">正在读取并渲染文件…</div>
      <div v-else-if="errorMessage" class="preview-state" role="alert">
        <h2>{{ errorStatus === 404 ? '资源不存在' : '暂时无法预览' }}</h2>
        <p>{{ errorMessage }}</p>
        <button type="button" @click="loadPreview">重试</button>
      </div>
      <div v-else-if="file?.content === ''" class="preview-state">文件内容为空</div>
      <article v-else-if="file?.format === 'md'" class="markdown-content" v-html="html"></article>
      <pre v-else class="text-content">{{ file?.content }}</pre>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { downloadFile, previewFile } from '../api/files.js'
import { clearLoginSession } from '../api/auth.js'

const route = useRoute()
const router = useRouter()
const file = shallowRef(null)
const html = ref('')
const loading = ref(true)
const errorMessage = ref('')
const errorStatus = ref(null)
const downloading = ref(false)
const downloadMessage = ref('')
const downloadError = ref(false)
let controller
let worker

function goBack() {
  const query = {}
  for (const key of ['parentId', 'page']) {
    if (typeof route.query[key] === 'string') query[key] = route.query[key]
  }
  router.push({ path: '/drive', query })
}

async function downloadCurrent() {
  if (!file.value || file.value.downloadAllowed === false || downloading.value) return
  downloading.value = true
  downloadMessage.value = ''
  downloadError.value = false
  try {
    await downloadFile({ filename: file.value.name, fileId: file.value.fileId })
    downloadMessage.value = `已开始下载 ${file.value.name}`
  } catch (error) {
    downloadError.value = true
    downloadMessage.value = error.message
    if (error.status === 401 && error.code !== 'INVALID_PARAM') {
      clearLoginSession()
      router.replace('/login')
    }
  } finally {
    downloading.value = false
  }
}

function cancelPreview() {
  controller?.abort()
  worker?.terminate()
  worker = null
}

async function loadPreview() {
  cancelPreview()
  const current = new AbortController()
  controller = current
  loading.value = true
  errorMessage.value = ''
  errorStatus.value = null
  file.value = null
  html.value = ''
  try {
    const result = await previewFile(route.params.fileId, { signal: current.signal })
    if (current.signal.aborted) return
    file.value = result
    if (result.format === 'md' && result.content) {
      worker = new Worker(new URL('../preview/markdown.worker.js', import.meta.url), { type: 'module' })
      worker.onmessage = event => {
        if (current.signal.aborted) return
        if (event.data.error) errorMessage.value = event.data.error
        else html.value = event.data.html
        loading.value = false
        worker?.terminate()
        worker = null
      }
      worker.onerror = () => {
        if (current.signal.aborted) return
        errorMessage.value = 'Markdown 渲染失败，请重试'
        loading.value = false
        worker?.terminate()
        worker = null
      }
      worker.postMessage(result.content)
    } else {
      loading.value = false
    }
  } catch (error) {
    if (current.signal.aborted) return
    loading.value = false
    errorStatus.value = error.status
    errorMessage.value = error.status === 404 ? '文件不存在、已删除或无法访问。' : error.message
    if (error.status === 401) {
      clearLoginSession()
      router.replace('/login')
    }
  }
}

watch(() => route.params.fileId, loadPreview, { immediate: true })
onBeforeUnmount(cancelPreview)
</script>

<style scoped>
.preview-page { min-height: 100vh; padding: 32px clamp(16px, 5vw, 80px) 64px; background: #f4f4ee; color: #263d30; }
.preview-header { max-width: 1080px; margin: 0 auto 24px; }
.preview-back, .preview-state button { border: 1px solid #cbd5c7; border-radius: 8px; padding: 10px 16px; background: #fff; color: #36503d; cursor: pointer; }
.preview-download-wrap { float: right; }
.preview-download { border: 1px solid #9bb39a; border-radius: 8px; padding: 10px 16px; background: #eaf2e9; color: #36503d; cursor: pointer; }
.preview-download:disabled { opacity: .5; cursor: not-allowed; }
.preview-download-message { max-width: 1080px; margin: 0 auto 12px; color: #496957; font-size: 13px; }
.preview-heading { margin-top: 30px; }
.preview-heading p { color: #71816d; font-size: 13px; }
.preview-heading h1 { margin: 8px 0; font-size: clamp(22px, 3vw, 32px); overflow-wrap: anywhere; }
.preview-paper { max-width: 1080px; min-height: 55vh; margin: auto; padding: clamp(20px, 4vw, 56px); border: 1px solid #e1e5da; border-radius: 14px; background: #fff; box-shadow: 0 8px 30px #34483008; }
.preview-state { padding: 64px 0; text-align: center; color: #72816e; }
.preview-state h2 { color: #354a3b; }
.text-content { white-space: pre-wrap; overflow-wrap: anywhere; font: 15px/1.8 ui-monospace, monospace; margin: 0; color: #29382e; }
.markdown-content { line-height: 1.8; overflow-wrap: anywhere; color: #29382e; }
.markdown-content :deep(h1), .markdown-content :deep(h2), .markdown-content :deep(h3) { line-height: 1.35; margin: 1.2em 0 .6em; }
.markdown-content :deep(pre) { padding: 18px; border-radius: 8px; overflow-x: auto; background: #f3f5ef; }
.markdown-content :deep(code) { font-family: ui-monospace, monospace; background: #f3f5ef; }
.markdown-content :deep(blockquote) { margin-left: 0; padding-left: 20px; border-left: 3px solid #95ad89; color: #697d62; }
.markdown-content :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
.markdown-content :deep(th), .markdown-content :deep(td) { padding: 8px 14px; border: 1px solid #dbe2d6; }
.markdown-content :deep(a) { color: #386942; text-decoration: underline; }
.markdown-content :deep(img) { max-width: 100%; }
</style>
