<template>
  <div class="topology-page">
    <header class="topology-header">
      <div class="topology-heading">
        <button class="topology-back" type="button" @click="router.push('/drive')" aria-label="返回网盘">←</button>
        <div>
          <p class="topology-eyebrow">NETWORK DISK / DIRECTORY MAP</p>
          <h1>文件夹关系图</h1>
        </div>
      </div>
      <div class="topology-header-right">
        <span class="topology-count">{{ graph.folderCount }} 个文件夹 <i></i> {{ graph.edges.length }} 条关系</span>
        <button class="topology-refresh" type="button" :disabled="loading" @click="loadTree">↻ 刷新</button>
      </div>
    </header>

    <section class="topology-workspace" aria-label="文件夹拓扑视图">
      <div class="topology-tools">
        <label class="topology-search">
          <span aria-hidden="true">⌕</span>
          <input v-model="query" type="search" placeholder="查找文件夹" aria-label="查找文件夹" @keydown.enter="selectFirstMatch" />
          <kbd v-if="query">{{ matches.length }} 项</kbd>
        </label>
        <div class="topology-zoom" aria-label="画布缩放">
          <button type="button" aria-label="放大" title="放大" @click="zoomBy(1.2)">＋</button>
          <button type="button" aria-label="缩小" title="缩小" @click="zoomBy(1 / 1.2)">－</button>
          <button type="button" aria-label="适应画布" title="适应画布" @click="resetView">⌗</button>
        </div>
      </div>

      <div v-if="loading" class="topology-state" role="status">
        <span class="topology-state-mark">◌</span>
        <h2>正在绘制关系图</h2>
        <p>读取你的文件夹层级…</p>
      </div>
      <div v-else-if="errorMessage" class="topology-state" role="alert">
        <span class="topology-state-mark">!</span>
        <h2>{{ errorStatus === 404 ? '资源不存在' : '关系图加载失败' }}</h2>
        <p>{{ errorMessage }}</p>
        <button type="button" @click="loadTree">重新加载</button>
      </div>
      <div v-else-if="!graph.folderCount" class="topology-state">
        <span class="topology-state-mark">◇</span>
        <h2>无文件</h2>
        <p>创建文件夹后，这里会出现它们的关系谱系。</p>
        <button type="button" @click="router.push('/drive')">返回我的文件</button>
      </div>
      <svg v-else ref="canvas" class="topology-svg" :viewBox="`0 0 ${graph.width} ${graph.height}`"
           aria-label="文件夹关系拓扑图" @pointerdown="startPan" @pointermove="movePan"
           @pointerup="endPan" @pointercancel="endPan" @wheel.prevent="wheelZoom">
        <g :transform="`translate(${view.x} ${view.y}) scale(${view.scale})`">
          <line v-for="edge in graph.edges" :key="`${edge.from}-${edge.to}`"
                :data-from="edge.from" :data-to="edge.to"
                class="topology-edge" :class="{ focused: touchesSelected(edge), faded: edgeFaded(edge) }"
                :x1="positions.get(edge.from).x" :y1="positions.get(edge.from).y"
                :x2="positions.get(edge.to).x" :y2="positions.get(edge.to).y" />
          <g v-for="node in graph.nodes" :key="node.id" class="topology-node"
             :class="{ selected: selectedId === node.id, root: node.id === 'root', faded: nodeFaded(node) }"
             tabindex="0" role="button" :aria-label="node.id === 'root' ? '我的文件' : `选择文件夹 ${node.name}`"
             @click.stop="selectNode(node)" @keydown.enter.prevent="selectNode(node)"
             @keydown.space.prevent="selectNode(node)">
            <title>{{ node.path.join(' / ') }}</title>
            <circle class="topology-node-halo" :cx="node.x" :cy="node.y" :r="node.id === 'root' ? 25 : 22" />
            <circle class="topology-node-dot" :cx="node.x" :cy="node.y" :r="node.id === 'root' ? 11 : 7" />
            <text :x="node.x" :y="node.y + 27" text-anchor="middle">{{ shortName(node.name) }}</text>
          </g>
        </g>
      </svg>

      <div v-if="!loading && !errorMessage && graph.folderCount" class="topology-footer">
        <span><i class="legend-dot"></i> 文件夹</span>
        <span><i class="legend-line"></i> 父子关系</span>
        <span class="topology-hint">拖动画布移动 · 滚轮缩放 · 点击节点查看路径</span>
      </div>

      <aside v-if="!loading && !errorMessage && graph.folderCount && selectedNode" class="topology-detail" aria-label="节点详情">
        <span class="topology-detail-tag">{{ selectedNode.id === 'root' ? '根节点' : `第 ${selectedNode.depth} 层` }}</span>
        <h2>{{ selectedNode.name }}</h2>
        <p>{{ selectedNode.path.join(' / ') }}</p>
        <div class="topology-detail-bottom">
          <span>{{ selectedNode.children.length }} 个直属子文件夹</span>
          <button type="button" @click="openSelected">进入目录 ↗</button>
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listFolderTree } from '../api/files.js'
import { buildFolderGraph } from '../graph/folderGraph.js'

const router = useRouter()
const folders = ref([])
const loading = ref(true)
const errorMessage = ref('')
const errorStatus = ref(null)
const query = ref('')
const selectedId = ref('root')
const canvas = ref(null)
const view = ref({ x: 0, y: 0, scale: 1 })
const graph = computed(() => buildFolderGraph(folders.value))
const positions = computed(() => new Map(graph.value.nodes.map(node => [node.id, node])))
const selectedNode = computed(() => positions.value.get(selectedId.value) || graph.value.nodes[0])
const matches = computed(() => graph.value.nodes.filter(node => node.id !== 'root'
  && node.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))
let panStart = null

async function loadTree() {
  loading.value = true
  errorMessage.value = ''
  errorStatus.value = null
  try {
    folders.value = await listFolderTree()
    selectedId.value = 'root'
    resetView()
  } catch (error) {
    errorMessage.value = error.message
    errorStatus.value = error.status
  } finally {
    loading.value = false
  }
}

function shortName(name) {
  const characters = [...name]
  return characters.length > 17 ? `${characters.slice(0, 16).join('')}…` : name
}

function nodeFaded(node) {
  return Boolean(query.value.trim()) && node.id !== 'root'
    && !node.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())
}

function edgeFaded(edge) {
  return Boolean(query.value.trim()) && nodeFaded(positions.value.get(edge.to))
}

function touchesSelected(edge) {
  return edge.from === selectedId.value || edge.to === selectedId.value
}

function selectNode(node) {
  selectedId.value = node.id
}

function selectFirstMatch() {
  if (!query.value.trim() || !matches.value.length) return
  const node = matches.value[0]
  selectNode(node)
  view.value = { ...view.value,
    x: graph.value.width / 2 - node.x * view.value.scale,
    y: graph.value.height / 2 - node.y * view.value.scale }
}

function openSelected() {
  const id = selectedNode.value.folderId
  router.push(id == null ? '/drive' : { path: '/drive', query: { parentId: String(id) } })
}

function resetView() {
  view.value = { x: 0, y: 0, scale: 1 }
}

function zoomBy(factor, anchor = { x: graph.value.width / 2, y: graph.value.height / 2 }) {
  const old = view.value
  const scale = Math.min(3, Math.max(0.55, old.scale * factor))
  view.value = {
    x: anchor.x - (anchor.x - old.x) * scale / old.scale,
    y: anchor.y - (anchor.y - old.y) * scale / old.scale,
    scale
  }
}

function svgPoint(event) {
  const matrix = canvas.value?.getScreenCTM()
  if (!matrix) return null
  return new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse())
}

function wheelZoom(event) {
  const point = svgPoint(event)
  if (point) zoomBy(event.deltaY < 0 ? 1.12 : 1 / 1.12, point)
}

function startPan(event) {
  if (event.target.closest('.topology-node')) return
  panStart = { id: event.pointerId, point: svgPoint(event) }
  canvas.value?.setPointerCapture(event.pointerId)
}

function movePan(event) {
  if (!panStart || event.pointerId !== panStart.id) return
  const point = svgPoint(event)
  if (!point || !panStart.point) return
  view.value = { ...view.value,
    x: view.value.x + point.x - panStart.point.x,
    y: view.value.y + point.y - panStart.point.y }
  panStart.point = point
}

function endPan(event) {
  if (panStart?.id !== event.pointerId) return
  panStart = null
  if (canvas.value?.hasPointerCapture(event.pointerId)) canvas.value.releasePointerCapture(event.pointerId)
}

onMounted(loadTree)
</script>

<style scoped>
.topology-page { min-height: 100vh; color: #273b30; background: #f8f9f6; }
.topology-header { position: relative; z-index: 2; min-height: 96px; display: flex; align-items: center; justify-content: space-between; gap: 24px; border-bottom: 1px solid #e5eae3; padding: 20px clamp(24px, 3.2vw, 56px); background: #fdfefb; }
.topology-heading { display: flex; align-items: center; gap: 20px; }
.topology-back { width: 42px; height: 42px; border: 1px solid #dce4da; border-radius: 50%; color: #355443; background: #fff; font-size: 21px; cursor: pointer; }
.topology-back:hover, .topology-refresh:hover { border-color: #8ea996; background: #f1f6ef; }
.topology-eyebrow { margin: 0 0 4px; color: #98a79b; font-family: var(--mono); font-size: 10px; letter-spacing: .18em; }
.topology-heading h1 { margin: 0; font-family: var(--serif); font-size: clamp(23px, 2.4vw, 32px); font-weight: 600; letter-spacing: -.045em; }
.topology-header-right { display: flex; align-items: center; gap: 22px; }
.topology-count { color: #819287; font-size: 12px; white-space: nowrap; }
.topology-count i { display: inline-block; width: 3px; height: 3px; margin: 0 9px 3px; border-radius: 50%; background: #b6c2b5; }
.topology-refresh { border: 1px solid #dce4da; border-radius: 8px; padding: 9px 13px; color: #496451; background: #fff; font-size: 12px; cursor: pointer; }
.topology-refresh:disabled { opacity: .5; cursor: wait; }
.topology-workspace { position: relative; height: calc(100vh - 96px); min-height: 580px; overflow: hidden; background-color: #fbfcf9; background-image: radial-gradient(#b5c3b546 1px, transparent 1px); background-size: 24px 24px; }
.topology-workspace::before { position: absolute; inset: 0; pointer-events: none; background: radial-gradient(ellipse at center, #ffffff00 32%, #f6f8f3d9 100%); content: ''; }
.topology-tools { position: absolute; z-index: 3; top: 24px; right: clamp(22px, 3vw, 48px); display: flex; align-items: center; gap: 12px; }
.topology-search { height: 41px; min-width: 230px; display: flex; align-items: center; gap: 8px; border: 1px solid #dfe7dd; border-radius: 9px; padding: 0 11px; color: #78907d; background: #ffffffeb; box-shadow: 0 8px 24px #304d3710; }
.topology-search span { font-size: 21px; line-height: 1; }
.topology-search input { width: 100%; border: 0; outline: 0; color: #2a4232; background: transparent; font-size: 12px; }
.topology-search input::placeholder { color: #9baa9d; }
.topology-search kbd { flex: 0 0 auto; color: #8fa08f; font-family: var(--mono); font-size: 10px; }
.topology-zoom { display: flex; border: 1px solid #dfe7dd; border-radius: 9px; overflow: hidden; background: #ffffffeb; box-shadow: 0 8px 24px #304d3710; }
.topology-zoom button { width: 37px; height: 39px; border: 0; border-left: 1px solid #e6ece4; color: #4f6a55; background: transparent; font-size: 19px; cursor: pointer; }
.topology-zoom button:first-child { border-left: 0; }
.topology-zoom button:hover { background: #eef4ed; }
.topology-svg { position: relative; width: 100%; height: 100%; display: block; touch-action: none; cursor: grab; }
.topology-svg:active { cursor: grabbing; }
.topology-edge { stroke: #abb9ae; stroke-width: 1.25; opacity: .62; transition: opacity .2s, stroke .2s; }
.topology-edge.focused { stroke: #b08348; stroke-width: 1.8; opacity: .9; }
.topology-edge.faded { opacity: .1; }
.topology-node { cursor: pointer; transition: opacity .2s; }
.topology-node.faded { opacity: .18; }
.topology-node-halo { fill: #d9e6d7; opacity: 0; transition: opacity .2s; }
.topology-node-dot { fill: #58675c; stroke: #f7faf5; stroke-width: 3; transition: fill .2s, r .2s; }
.topology-node.root .topology-node-dot { fill: #284b38; }
.topology-node.selected .topology-node-dot, .topology-node:hover .topology-node-dot { fill: #ad7e41; }
.topology-node.selected .topology-node-halo, .topology-node:hover .topology-node-halo { opacity: .9; }
.topology-node text { fill: #849388; font-family: 'DM Sans', 'Microsoft YaHei', sans-serif; font-size: 13px; pointer-events: none; }
.topology-node.root text { fill: #405a48; font-weight: 700; }
.topology-node.selected text, .topology-node:hover text { fill: #3f5a43; font-weight: 700; }
.topology-node:focus-visible { outline: none; }
.topology-node:focus-visible .topology-node-halo { opacity: 1; }
.topology-footer { position: absolute; left: clamp(22px, 3vw, 48px); bottom: 27px; display: flex; align-items: center; gap: 20px; color: #94a397; font-size: 11px; }
.topology-footer span { display: inline-flex; align-items: center; gap: 7px; }
.legend-dot { width: 7px; height: 7px; border-radius: 50%; background: #58675c; }
.legend-line { width: 19px; height: 1px; background: #aab8ae; }
.topology-detail { position: absolute; z-index: 2; left: clamp(22px, 3vw, 48px); top: 24px; width: min(290px, calc(100% - 44px)); border: 1px solid #e0e8de; border-radius: 13px; padding: 20px; background: #ffffffed; box-shadow: 0 16px 45px #31523a12; }
.topology-detail-tag { color: #a78353; font-family: var(--mono); font-size: 10px; letter-spacing: .08em; }
.topology-detail h2 { margin: 9px 0 7px; font-family: var(--serif); font-size: 22px; font-weight: 600; overflow-wrap: anywhere; }
.topology-detail p { margin: 0; color: #8b9c8e; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.topology-detail-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 20px; border-top: 1px solid #e7ede5; padding-top: 15px; color: #829282; font-size: 10px; }
.topology-detail-bottom button { flex: 0 0 auto; border: 0; color: #3c6b47; background: transparent; font-size: 11px; font-weight: 700; cursor: pointer; }
.topology-detail-bottom button:hover { text-decoration: underline; }
.topology-state { position: absolute; z-index: 2; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.topology-state-mark { width: 66px; height: 66px; display: grid; place-items: center; border: 1px solid #d7e1d4; border-radius: 50%; color: #64816a; background: #fff; font-family: var(--serif); font-size: 34px; }
.topology-state h2 { margin: 21px 0 5px; font-family: var(--serif); font-size: 27px; font-weight: 600; }
.topology-state p { margin: 0; color: #91a194; font-size: 13px; }
.topology-state button { margin-top: 25px; border: 1px solid #9eb7a0; border-radius: 8px; padding: 10px 17px; color: #476b4b; background: #fff; cursor: pointer; }
@media (max-width: 800px) {
  .topology-header { align-items: flex-start; }
  .topology-header-right { flex-direction: column; align-items: flex-end; gap: 7px; }
  .topology-workspace { min-height: 650px; }
  .topology-tools { top: 18px; left: 20px; right: 20px; justify-content: flex-end; }
  .topology-search { min-width: 0; width: min(50vw, 220px); }
  .topology-detail { top: auto; bottom: 75px; }
  .topology-footer { bottom: 19px; }
  .topology-hint { display: none !important; }
}
</style>
