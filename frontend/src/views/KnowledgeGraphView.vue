<template>
  <div class="knowledge-page">
    <header class="knowledge-header">
      <div class="knowledge-title">
        <button class="knowledge-back" type="button" aria-label="返回网盘" @click="router.push('/drive')">←</button>
        <div><p class="knowledge-eyebrow">PERSONAL KNOWLEDGE SPACE</p><h1>知识图谱</h1></div>
      </div>
      <div class="knowledge-header-actions">
        <span class="knowledge-count">{{ nodes.length }} 个知识点 <i></i> {{ edges.length }} 条关系</span>
        <button type="button" class="knowledge-refresh" :disabled="loading" @click="loadGraph">刷新</button>
        <button type="button" class="knowledge-create" @click="openCreate">＋ 新建知识</button>
      </div>
    </header>

    <main class="knowledge-workspace">
      <div class="knowledge-toolbar">
        <label class="knowledge-search"><span aria-hidden="true">⌕</span><input v-model="query" type="search" placeholder="搜索知识点…" aria-label="搜索知识点" /><kbd>⌘ K</kbd></label>
        <div class="knowledge-zoom"><button type="button" aria-label="缩小" @click="zoomBy(.85)">−</button><button type="button" aria-label="重置视图" @click="resetView">⌂</button><button type="button" aria-label="放大" @click="zoomBy(1.18)">＋</button></div>
      </div>

      <div v-if="loading" class="knowledge-state"><span class="knowledge-state-mark">◌</span><h2>正在整理知识网络</h2><p>马上就好。</p></div>
      <div v-else-if="errorMessage" class="knowledge-state"><span class="knowledge-state-mark">!</span><h2>图谱暂时无法打开</h2><p>{{ errorMessage }}</p><button type="button" @click="loadGraph">重新加载</button></div>
      <div v-else-if="!nodes.length" class="knowledge-state"><span class="knowledge-state-mark">✳</span><h2>从一个想法开始</h2><p>创建知识点，再把相关内容连成你的知识网络。</p><button type="button" @click="openCreate">创建第一个知识点</button></div>
      <div v-else-if="!visibleNodes.length" class="knowledge-state"><span class="knowledge-state-mark">⌕</span><h2>没有找到匹配的知识点</h2><p>试试更短的关键词。</p></div>

      <svg v-else ref="canvas" class="knowledge-canvas" viewBox="0 0 1200 760" role="img" aria-label="知识点关系图" @wheel.prevent="wheelZoom" @pointerdown="startPan" @pointermove="movePan" @pointerup="endPointer" @pointercancel="endPointer">
        <defs><filter id="node-shadow" x="-100%" y="-100%" width="300%" height="300%"><feDropShadow dx="0" dy="4" stdDeviation="6" flood-color="#263d30" flood-opacity=".12" /></filter></defs>
        <g :transform="`translate(${view.x} ${view.y}) scale(${view.scale})`">
          <g v-for="edge in visibleEdges" :key="edge.id" class="knowledge-edge-group">
            <line :x1="point(edge.source)?.x" :y1="point(edge.source)?.y" :x2="point(edge.target)?.x" :y2="point(edge.target)?.y" class="knowledge-edge" :class="{ focused: selectedId === edge.source || selectedId === edge.target }" />
            <text v-if="edge.relation" class="knowledge-edge-label" :x="midpoint(edge).x" :y="midpoint(edge).y - 5">{{ edge.relation }}</text>
          </g>
          <g v-for="node in visibleNodes" :key="node.id" class="knowledge-node" :class="{ selected: selectedId === node.id, matched: matchingIds.has(node.id) }" :transform="`translate(${point(node.id)?.x || 0} ${point(node.id)?.y || 0})`" tabindex="0" role="button" :aria-label="`查看 ${node.title}`" @click.stop="selectNode(node.id)" @keydown.enter.stop="selectNode(node.id)" @pointerdown.stop="startNodeDrag($event, node.id)">
            <circle class="knowledge-node-halo" r="35" />
            <circle class="knowledge-node-dot" :r="selectedId === node.id ? 22 : 17" filter="url(#node-shadow)" />
            <text class="knowledge-node-icon" y="5">{{ node.title.slice(0, 1) }}</text>
            <text class="knowledge-node-label" y="43">{{ shortTitle(node.title) }}</text>
            <text v-if="node.category" class="knowledge-node-category" y="59">{{ node.category }}</text>
          </g>
        </g>
      </svg>

      <aside v-if="selectedNode && !editorOpen" class="knowledge-detail">
        <button class="knowledge-detail-close" type="button" aria-label="关闭详情" @click="selectedId = null">×</button>
        <span class="knowledge-detail-tag">{{ selectedNode.category || '知识点' }}</span>
        <h2>{{ selectedNode.title }}</h2>
        <p class="knowledge-detail-content">{{ selectedNode.content || '还没有补充说明。' }}</p>
        <div class="knowledge-detail-meta">更新于 {{ formatDate(selectedNode.updatedTime) }}</div>
        <div class="knowledge-related">
          <div class="knowledge-related-heading"><strong>关联知识</strong><span>{{ selectedEdges.length }}</span></div>
          <div v-if="selectedEdges.length" class="knowledge-related-list">
            <div v-for="edge in selectedEdges" :key="edge.id" class="knowledge-related-row">
              <button type="button" @click="selectNode(otherNode(edge).id)"><span class="related-dot"></span>{{ otherNode(edge).title }}<small>{{ edge.relation || '关联' }}</small></button>
              <button type="button" class="related-remove" :aria-label="`删除与 ${otherNode(edge).title} 的关系`" @click="removeEdge(edge)">×</button>
            </div>
          </div>
          <p v-else class="knowledge-empty-related">还没有关联。添加另一知识点来连接它。</p>
          <div v-if="availableNodes.length" class="knowledge-link-form">
            <select v-model="edgeTarget" aria-label="选择关联知识点"><option value="">连接到…</option><option v-for="node in availableNodes" :key="node.id" :value="String(node.id)">{{ node.title }}</option></select>
            <input v-model="edgeRelation" maxlength="50" placeholder="关系（可选）" aria-label="关系名称" />
            <button type="button" :disabled="!edgeTarget || busy" @click="addEdge">连接</button>
          </div>
        </div>
        <div class="knowledge-detail-actions"><button type="button" @click="openEdit">编辑</button><button type="button" class="knowledge-delete" :disabled="busy" @click="removeNode">删除知识点</button></div>
      </aside>

      <aside v-if="editorOpen" class="knowledge-detail knowledge-editor">
        <button class="knowledge-detail-close" type="button" aria-label="关闭编辑" @click="closeEditor">×</button>
        <span class="knowledge-detail-tag">{{ editingId ? 'EDIT KNOWLEDGE' : 'NEW KNOWLEDGE' }}</span>
        <h2>{{ editingId ? '编辑知识点' : '新建知识点' }}</h2>
        <form class="knowledge-form" @submit.prevent="saveNode">
          <label>标题<input v-model="form.title" required maxlength="100" autofocus placeholder="例如：Redis 缓存" /></label>
          <label>分类<input v-model="form.category" maxlength="50" placeholder="例如：数据库" /></label>
          <label>内容<textarea v-model="form.content" rows="8" maxlength="20000" placeholder="写下摘要、想法或学习笔记…"></textarea></label>
          <p v-if="editorError" class="knowledge-form-error" role="alert">{{ editorError }}</p>
          <div class="knowledge-detail-actions"><button type="button" @click="closeEditor">取消</button><button class="knowledge-save" type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存知识点' }}</button></div>
        </form>
      </aside>

      <div class="knowledge-legend"><span><i></i> 知识点</span><span><b></b> 关联关系</span><small>拖动节点排列 · 拖动画布移动 · 滚轮缩放</small></div>
      <p v-if="notice" class="knowledge-notice" role="status">{{ notice }}</p>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createKnowledgeEdge, createKnowledgeNode, deleteKnowledgeEdge, deleteKnowledgeNode, listKnowledgeEdges, listKnowledgeNodes, updateKnowledgeNode } from '../api/knowledge.js'
import { layoutKnowledgeGraph } from '../graph/knowledgeLayout.js'

const router = useRouter()
const nodes = ref([])
const edges = ref([])
const positions = ref(new Map())
const loading = ref(true)
const busy = ref(false)
const errorMessage = ref('')
const notice = ref('')
const query = ref('')
const selectedId = ref(null)
const editorOpen = ref(false)
const editingId = ref(null)
const editorError = ref('')
const form = ref({ title: '', content: '', category: '' })
const edgeTarget = ref('')
const edgeRelation = ref('')
const canvas = ref(null)
const view = ref({ x: 0, y: 0, scale: 1 })
let activePointer = null
let dragMode = ''
let noticeTimer

const selectedNode = computed(() => nodes.value.find(node => node.id === selectedId.value))
const selectedEdges = computed(() => edges.value.filter(edge => edge.source === selectedId.value || edge.target === selectedId.value))
const availableNodes = computed(() => nodes.value.filter(node => node.id !== selectedId.value))
const matchingIds = computed(() => new Set(nodes.value.filter(node => `${node.title} ${node.content || ''} ${node.category || ''}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())).map(node => node.id)))
const visibleNodes = computed(() => {
  if (!query.value.trim()) return nodes.value
  const shown = new Set(matchingIds.value)
  for (const edge of edges.value) {
    if (shown.has(edge.source)) shown.add(edge.target)
    if (shown.has(edge.target)) shown.add(edge.source)
  }
  return nodes.value.filter(node => shown.has(node.id))
})
const visibleEdges = computed(() => edges.value.filter(edge => visibleNodes.value.some(node => node.id === edge.source) && visibleNodes.value.some(node => node.id === edge.target)))

async function loadGraph() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [nodeList, edgeList] = await Promise.all([listKnowledgeNodes(), listKnowledgeEdges()])
    nodes.value = nodeList
    edges.value = edgeList
    const oldPositions = positions.value
    const newPositions = new Map(layoutKnowledgeGraph(nodeList, edgeList, 1200, 760).map(node => [node.id, node]))
    for (const [id, point] of oldPositions) if (newPositions.has(id)) Object.assign(newPositions.get(id), { x: point.x, y: point.y })
    positions.value = newPositions
    if (!selectedNode.value) selectedId.value = nodeList[0]?.id ?? null
    if (!nodeList.some(node => node.id === editingId.value)) editorOpen.value = false
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    loading.value = false
  }
}

function point(id) { return positions.value.get(id) }
function midpoint(edge) {
  const source = point(edge.source)
  const target = point(edge.target)
  return { x: (source.x + target.x) / 2, y: (source.y + target.y) / 2 }
}
function shortTitle(title) { return [...title].length > 18 ? `${[...title].slice(0, 17).join('')}…` : title }
function otherNode(edge) { return nodes.value.find(node => node.id === (edge.source === selectedId.value ? edge.target : edge.source)) || {} }
function selectNode(id) { selectedId.value = id; editorOpen.value = false }

function openCreate() {
  editingId.value = null
  form.value = { title: '', content: '', category: '' }
  editorError.value = ''
  editorOpen.value = true
}
function openEdit() {
  editingId.value = selectedNode.value.id
  form.value = { title: selectedNode.value.title, content: selectedNode.value.content || '', category: selectedNode.value.category || '' }
  editorError.value = ''
  editorOpen.value = true
}
function closeEditor() { editorOpen.value = false; editorError.value = '' }

async function saveNode() {
  if (!form.value.title.trim() || busy.value) return
  busy.value = true
  editorError.value = ''
  try {
    const payload = { title: form.value.title.trim(), content: form.value.content, category: form.value.category }
    const saved = editingId.value ? await updateKnowledgeNode(editingId.value, payload) : await createKnowledgeNode(payload)
    await loadGraph()
    selectedId.value = saved.id
    editorOpen.value = false
    showNotice(editingId.value ? '知识点已更新' : '知识点已创建')
  } catch (error) {
    editorError.value = error.message
  } finally {
    busy.value = false
  }
}

async function removeNode() {
  if (!selectedNode.value || busy.value || !window.confirm(`删除“${selectedNode.value.title}”？与它相关的关系也会一起删除。`)) return
  busy.value = true
  try {
    await deleteKnowledgeNode(selectedId.value)
    selectedId.value = null
    await loadGraph()
    showNotice('知识点已删除')
  } catch (error) { showNotice(error.message) } finally { busy.value = false }
}

async function addEdge() {
  if (!selectedId.value || !edgeTarget.value || busy.value) return
  busy.value = true
  try {
    await createKnowledgeEdge({ sourceId: selectedId.value, targetId: Number(edgeTarget.value), relation: edgeRelation.value })
    edgeTarget.value = ''
    edgeRelation.value = ''
    await loadGraph()
    showNotice('关系已添加')
  } catch (error) { showNotice(error.message) } finally { busy.value = false }
}

async function removeEdge(edge) {
  if (busy.value) return
  busy.value = true
  try { await deleteKnowledgeEdge(edge.id); await loadGraph(); showNotice('关系已移除') }
  catch (error) { showNotice(error.message) }
  finally { busy.value = false }
}

function showNotice(message) {
  notice.value = message
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 2800)
}
function zoomBy(factor, anchor = { x: 600, y: 380 }) {
  const scale = Math.min(2.6, Math.max(.55, view.value.scale * factor))
  view.value = { x: anchor.x - (anchor.x - view.value.x) * scale / view.value.scale, y: anchor.y - (anchor.y - view.value.y) * scale / view.value.scale, scale }
}
function resetView() { view.value = { x: 0, y: 0, scale: 1 } }
function svgPoint(event) {
  const matrix = canvas.value?.getScreenCTM()
  if (!matrix) return null
  return new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse())
}
function wheelZoom(event) {
  const p = svgPoint(event)
  if (p) zoomBy(event.deltaY < 0 ? 1.1 : 1 / 1.1, p)
}
function startPan(event) {
  if (event.target.closest('.knowledge-node')) return
  dragMode = 'pan'
  activePointer = { id: event.pointerId, point: svgPoint(event) }
  canvas.value?.setPointerCapture(event.pointerId)
}
function startNodeDrag(event, id) {
  if (event.button !== 0) return
  selectNode(id)
  dragMode = 'node'
  activePointer = { id: event.pointerId, nodeId: id }
  canvas.value?.setPointerCapture(event.pointerId)
}
function movePan(event) {
  if (!activePointer || event.pointerId !== activePointer.id) return
  const p = svgPoint(event)
  if (!p) return
  if (dragMode === 'node') {
    const next = new Map(positions.value)
    const current = next.get(activePointer.nodeId)
    next.set(activePointer.nodeId, { ...current, x: (p.x - view.value.x) / view.value.scale, y: (p.y - view.value.y) / view.value.scale })
    positions.value = next
  } else if (activePointer.point) {
    view.value = { ...view.value, x: view.value.x + p.x - activePointer.point.x, y: view.value.y + p.y - activePointer.point.y }
    activePointer.point = p
  }
}
function endPointer(event) {
  if (activePointer?.id !== event.pointerId) return
  activePointer = null
  dragMode = ''
}
function formatDate(value) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '刚刚' }

watch(query, value => {
  if (value.trim() && matchingIds.value.size) selectedId.value = nodes.value.find(node => matchingIds.value.has(node.id))?.id ?? selectedId.value
})
onMounted(loadGraph)
onUnmounted(() => clearTimeout(noticeTimer))
</script>

<style scoped>
.knowledge-page { min-height: 100vh; color: #263b30; background: #f8f9f6; }
.knowledge-header { position: relative; z-index: 2; min-height: 96px; display: flex; align-items: center; justify-content: space-between; gap: 22px; border-bottom: 1px solid #e5eae3; padding: 18px clamp(22px, 3vw, 52px); background: #fdfefb; }
.knowledge-title { display: flex; align-items: center; gap: 17px; }
.knowledge-back { width: 40px; height: 40px; border: 1px solid #dce4da; border-radius: 50%; color: #355443; background: white; font-size: 20px; cursor: pointer; }
.knowledge-eyebrow { margin: 0 0 4px; color: #98a79b; font-family: var(--mono); font-size: 10px; letter-spacing: .17em; }
.knowledge-title h1 { margin: 0; font-family: var(--serif); font-size: 29px; font-weight: 600; letter-spacing: -.04em; }
.knowledge-header-actions { display: flex; align-items: center; gap: 12px; }
.knowledge-count { margin-right: 7px; color: #819287; font-size: 12px; white-space: nowrap; }
.knowledge-count i { display: inline-block; width: 3px; height: 3px; margin: 0 7px 3px; border-radius: 50%; background: #b6c2b5; }
.knowledge-refresh, .knowledge-create { min-height: 39px; border: 1px solid #dce4da; border-radius: 8px; padding: 0 14px; color: #496451; background: white; font-size: 12px; cursor: pointer; }
.knowledge-create { border-color: #284b38; color: #fbfaf6; background: #284b38; font-weight: 700; }
.knowledge-create:hover { background: #3a6248; }
.knowledge-workspace { position: relative; height: calc(100vh - 96px); min-height: 580px; overflow: hidden; background-color: #fbfcf9; background-image: radial-gradient(#b5c3b546 1px, transparent 1px); background-size: 24px 24px; }
.knowledge-toolbar { position: absolute; z-index: 3; top: 22px; right: clamp(22px, 3vw, 48px); display: flex; align-items: center; gap: 12px; }
.knowledge-search { height: 41px; width: min(300px, 33vw); display: flex; align-items: center; gap: 8px; border: 1px solid #dfe7dd; border-radius: 9px; padding: 0 11px; color: #78907d; background: #ffffffeb; box-shadow: 0 8px 24px #304d3710; }
.knowledge-search span { font-size: 21px; line-height: 1; }
.knowledge-search input { min-width: 0; width: 100%; border: 0; outline: 0; color: #2a4232; background: transparent; font-size: 12px; }
.knowledge-search kbd { flex: 0 0 auto; color: #8fa08f; font-family: var(--mono); font-size: 10px; }
.knowledge-zoom { display: flex; border: 1px solid #dfe7dd; border-radius: 9px; overflow: hidden; background: #ffffffeb; box-shadow: 0 8px 24px #304d3710; }
.knowledge-zoom button { width: 38px; height: 39px; border: 0; border-left: 1px solid #e6ece4; color: #4f6a55; background: transparent; font-size: 19px; cursor: pointer; }
.knowledge-zoom button:first-child { border-left: 0; }
.knowledge-zoom button:hover { background: #eef4ed; }
.knowledge-canvas { width: 100%; height: 100%; display: block; touch-action: none; cursor: grab; }
.knowledge-canvas:active { cursor: grabbing; }
.knowledge-edge { stroke: #b6c2b8; stroke-width: 1.3; opacity: .66; transition: stroke .2s, opacity .2s; }
.knowledge-edge.focused { stroke: #ad8048; stroke-width: 1.8; opacity: .9; }
.knowledge-edge-label { fill: #a4afa5; font-size: 10px; text-anchor: middle; paint-order: stroke; stroke: #fbfcf9; stroke-width: 4px; stroke-linejoin: round; }
.knowledge-node { cursor: grab; outline: none; }
.knowledge-node:active { cursor: grabbing; }
.knowledge-node-halo { fill: #dce8d9; opacity: 0; transition: opacity .2s; }
.knowledge-node-dot { fill: #51665a; stroke: #f7faf5; stroke-width: 3; transition: fill .2s, r .2s; }
.knowledge-node.selected .knowledge-node-dot, .knowledge-node:hover .knowledge-node-dot { fill: #ad7e41; }
.knowledge-node.selected .knowledge-node-halo, .knowledge-node:hover .knowledge-node-halo { opacity: .8; }
.knowledge-node.matched .knowledge-node-dot { fill: #315d43; }
.knowledge-node-icon { fill: white; text-anchor: middle; font-size: 12px; font-weight: 700; pointer-events: none; }
.knowledge-node-label { fill: #718176; text-anchor: middle; font-size: 12px; pointer-events: none; }
.knowledge-node-category { fill: #a0aaa0; text-anchor: middle; font-size: 10px; pointer-events: none; }
.knowledge-detail { position: absolute; z-index: 2; top: 22px; right: clamp(22px, 3vw, 48px); width: min(340px, calc(100% - 44px)); max-height: calc(100% - 100px); overflow-y: auto; border: 1px solid #e0e8de; border-radius: 13px; padding: 23px; background: #ffffffed; box-shadow: 0 16px 45px #31523a12; }
.knowledge-detail-close { position: absolute; top: 11px; right: 13px; width: 29px; height: 29px; border: 0; border-radius: 50%; color: #829282; background: transparent; font-size: 23px; cursor: pointer; }
.knowledge-detail-close:hover { background: #f0f4ee; }
.knowledge-detail-tag { color: #a78353; font-family: var(--mono); font-size: 10px; letter-spacing: .08em; }
.knowledge-detail h2 { margin: 9px 26px 10px 0; font-family: var(--serif); font-size: 24px; font-weight: 600; overflow-wrap: anywhere; }
.knowledge-detail-content { min-height: 45px; margin: 0; color: #728176; font-size: 12px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.knowledge-detail-meta { margin-top: 14px; color: #a0aaa0; font-size: 10px; }
.knowledge-related { margin-top: 20px; border-top: 1px solid #e7ede5; padding-top: 16px; }
.knowledge-related-heading { display: flex; justify-content: space-between; color: #52675a; font-size: 12px; }
.knowledge-related-heading span { color: #9aaa9a; }
.knowledge-related-list { display: grid; gap: 3px; margin-top: 8px; }
.knowledge-related-row { display: flex; align-items: center; justify-content: space-between; min-width: 0; }
.knowledge-related-row > button:first-child { min-width: 0; display: flex; align-items: center; gap: 8px; border: 0; padding: 7px 0; color: #58715e; background: transparent; font-size: 11px; cursor: pointer; }
.related-dot { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: #849987; }
.knowledge-related-row small { margin-left: 4px; color: #a3ada3; }
.related-remove { border: 0; color: #a6ada6; background: transparent; font-size: 18px; cursor: pointer; }
.related-remove:hover { color: #b85649; }
.knowledge-empty-related { color: #a0aaa0; font-size: 11px; line-height: 1.6; }
.knowledge-link-form { display: grid; grid-template-columns: 1fr 1fr auto; gap: 6px; margin-top: 11px; }
.knowledge-link-form select, .knowledge-link-form input { width: 100%; min-width: 0; height: 34px; border: 1px solid #e1e8df; border-radius: 6px; padding: 0 7px; color: #52675a; background: #fcfdfa; font-size: 10px; }
.knowledge-link-form button { border: 1px solid #d6e1d5; border-radius: 6px; padding: 0 9px; color: #45634b; background: #f6f9f4; font-size: 10px; cursor: pointer; }
.knowledge-detail-actions { display: flex; gap: 9px; margin-top: 20px; border-top: 1px solid #e7ede5; padding-top: 14px; }
.knowledge-detail-actions button { min-height: 35px; border: 1px solid #dce5da; border-radius: 7px; padding: 0 12px; color: #52675a; background: #fff; font-size: 11px; cursor: pointer; }
.knowledge-detail-actions .knowledge-delete { margin-left: auto; color: #a9574f; }
.knowledge-detail-actions .knowledge-save { margin-left: auto; border-color: #284b38; color: white; background: #284b38; }
.knowledge-form { display: grid; gap: 15px; margin-top: 21px; }
.knowledge-form label { display: grid; gap: 7px; color: #65766a; font-size: 11px; font-weight: 700; }
.knowledge-form input, .knowledge-form textarea { width: 100%; border: 1px solid #e1e8df; border-radius: 7px; padding: 10px 11px; color: #2d4235; background: #fcfdfa; font-size: 12px; font-weight: 400; outline: none; }
.knowledge-form textarea { resize: vertical; line-height: 1.7; }
.knowledge-form input:focus, .knowledge-form textarea:focus { border-color: #8da78d; box-shadow: 0 0 0 3px #6b8a7018; }
.knowledge-form-error { margin: 0; color: #a94d43; font-size: 11px; }
.knowledge-legend { position: absolute; left: clamp(22px, 3vw, 48px); bottom: 25px; display: flex; align-items: center; gap: 18px; color: #94a397; font-size: 10px; }
.knowledge-legend span { display: flex; align-items: center; gap: 7px; }
.knowledge-legend i { width: 7px; height: 7px; border-radius: 50%; background: #586c5d; }
.knowledge-legend b { width: 19px; height: 1px; background: #aab8ae; }
.knowledge-legend small { margin-left: 7px; font-size: 10px; }
.knowledge-state { position: absolute; z-index: 2; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px; text-align: center; }
.knowledge-state-mark { width: 64px; height: 64px; display: grid; place-items: center; border: 1px solid #d7e1d4; border-radius: 50%; color: #64816a; background: #fff; font-family: var(--serif); font-size: 32px; }
.knowledge-state h2 { margin: 18px 0 6px; font-family: var(--serif); font-size: 25px; font-weight: 600; }
.knowledge-state p { margin: 0; color: #91a194; font-size: 12px; }
.knowledge-state button { margin-top: 22px; border: 1px solid #9eb7a0; border-radius: 8px; padding: 10px 16px; color: #476b4b; background: #fff; font-size: 12px; cursor: pointer; }
.knowledge-notice { position: absolute; z-index: 4; right: 28px; bottom: 24px; margin: 0; border: 1px solid #dce7dc; border-radius: 8px; padding: 11px 15px; color: #45634b; background: #fff; box-shadow: 0 8px 24px #304d3710; font-size: 12px; }
@media (max-width: 760px) {
  .knowledge-header { align-items: flex-start; }
  .knowledge-header-actions { flex-wrap: wrap; justify-content: flex-end; }
  .knowledge-count { display: none; }
  .knowledge-toolbar { top: 15px; left: 16px; right: 16px; justify-content: flex-end; }
  .knowledge-search { width: min(58vw, 300px); }
  .knowledge-detail { top: auto; right: 14px; bottom: 60px; width: min(340px, calc(100% - 28px)); max-height: 56%; padding: 18px; }
  .knowledge-legend { bottom: 16px; left: 16px; }
  .knowledge-legend small { display: none; }
}
</style>
