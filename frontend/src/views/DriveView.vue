<template>
  <div class="drive-layout" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <aside id="drive-sidebar" class="drive-sidebar">
      <div class="sidebar-brand-row">
        <RouterLink class="drive-brand" to="/drive" aria-label="网盘首页" title="卡码网盘">
          <span class="brand-mark">K</span><span class="drive-brand-name">卡码网盘</span>
        </RouterLink>
        <button class="sidebar-toggle" type="button" :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
                :aria-expanded="!sidebarCollapsed" :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
                aria-controls="drive-sidebar"
                @click="sidebarCollapsed = !sidebarCollapsed">
          <span class="hamburger-icon" aria-hidden="true"><i></i><i></i><i></i></span>
        </button>
      </div>
      <nav class="drive-nav" aria-label="网盘导航">
        <div role="tree" aria-label="我的文件">
          <FileTreeNode :node="treeRoot" :selected-id="selectedTreeNodeId"
                        @toggle="toggleTreeNode" @activate="activateTreeNode" @retry="retryTreeNode" />
        </div>
        <button class="trash-nav" type="button" @click="router.push({ path: '/drive', query: { view: 'trash' } })">♲ 回收站</button>
        <button class="folder-tree-nav" type="button" @click="router.push('/drive/tree')">⌘ 文件夹树 <span aria-hidden="true">↗</span></button>
        <button class="folder-tree-nav knowledge-graph-nav" type="button" @click="router.push('/drive/knowledge')">✳ 知识图谱 <span aria-hidden="true">↗</span></button>
      </nav>
      <div class="storage-card">
        <div class="storage-title"><span>网盘状态</span></div>
        <p>文件与目录</p>
      </div>
      <div class="sidebar-user">
        <div class="user-avatar">{{ initial }}</div>
        <div class="user-copy"><strong>{{ username }}</strong><span>个人网盘</span></div>
        <details class="user-menu">
          <summary aria-label="账户设置">•••</summary>
          <div class="user-menu-panel">
            <strong>{{ username }}</strong>
            <form class="password-change" @submit.prevent="changeUserPassword">
              <input v-model="currentPassword" type="password" autocomplete="current-password" placeholder="当前密码" aria-label="当前密码" />
              <input v-model="newPassword" type="password" autocomplete="new-password" placeholder="新密码（6-64位）" aria-label="新密码" />
              <button type="submit" :disabled="busy">修改密码</button>
            </form>
            <button class="menu-logout" type="button" :disabled="busy" @click="signOut">退出登录</button>
          </div>
        </details>
      </div>
    </aside>

    <main class="drive-main">
      <header class="drive-topbar">
        <div class="breadcrumb"><span>我的网盘</span><span class="breadcrumb-slash">/</span><strong>{{ currentTitle }}</strong></div>
        <div class="top-account-name">{{ username }}</div>
        <button class="top-avatar" type="button" :aria-label="`${username} 的账户`">{{ initial }}</button>
      </header>

      <section class="drive-content" id="files">
        <div class="welcome-row">
          <div>
            <p class="eyebrow">YOUR CLOUD SPACE</p>
            <h1>{{ isTrash ? '回收站' : `你好，${username}` }}</h1>
            <p class="welcome-subtitle">{{ isTrash ? '误删的内容可以在这里恢复到原目录。' : '欢迎回到你的网盘，文件都在这里妥善保存。' }}</p>
          </div>
          <div class="welcome-illustration" aria-hidden="true"><span class="cloud-shape">☁</span><span class="cloud-dot dot-one"></span><span class="cloud-dot dot-two"></span></div>
        </div>

        <section class="file-section">
          <div class="section-heading">
            <div><h2>{{ currentTitle }}</h2><p>个人网盘</p></div>
            <div v-if="!isTrash" class="file-actions">
              <button class="folder-create-button" type="button" :disabled="fileOperationBusy" @click="openFolderDialog">新建文件夹</button>
              <button class="drive-upload" type="button" :disabled="fileOperationBusy" @click="chooseUpload"><span>＋</span>上传文件</button>
              <input ref="uploadInput" class="visually-hidden" type="file" accept=".txt,.docx,.csv,.xlsx,.pdf,.md,.html,.pptx" multiple @change="uploadSelectedFiles" />
            </div>
          </div>
          <nav v-if="!isTrash" class="file-breadcrumbs" aria-label="目录导航">
            <template v-for="(crumb, index) in breadcrumbs" :key="crumb.id ?? 'root'">
              <span v-if="index" class="breadcrumb-slash">/</span>
              <button type="button" :disabled="index === breadcrumbs.length - 1 || loading" @click="openDirectory(crumb.id)">{{ crumb.name }}</button>
            </template>
          </nav>
          <div v-if="!isTrash" class="upload-dropzone" :class="{ 'is-dragging': isDragging, disabled: fileOperationBusy }"
               role="button" tabindex="0" :aria-disabled="fileOperationBusy"
               @click="chooseUpload" @keydown.enter.prevent="chooseUpload" @keydown.space.prevent="chooseUpload"
               @dragenter.prevent="handleDragEnter" @dragover.prevent="handleDragOver"
               @dragleave.prevent="handleDragLeave" @drop.prevent="handleDrop">
            <section v-if="uploadItems.length" class="upload-progress-panel" aria-live="polite" aria-label="上传进度" @click.stop>
              <div class="upload-progress-heading">
                <strong>上传队列</strong>
                <span>{{ uploadCompletedCount }} / {{ uploadItems.length }} 已完成</span>
              </div>
              <div v-for="item in uploadItems" :key="item.id" class="upload-progress-item">
                <div class="upload-progress-meta">
                  <span class="upload-progress-name" :title="item.name">{{ item.name }}</span>
                  <span :class="'upload-progress-status ' + item.status">
                    {{ uploadStatusText(item.status) }}<template v-if="item.status === 'uploading' || item.status === 'hashing'"> {{ item.progress }}%</template>
                  </span>
                </div>
                <div class="upload-progress-track" role="progressbar" :aria-valuenow="item.progress" aria-valuemin="0" aria-valuemax="100" :aria-label="item.name + ' 上传进度'">
                  <span :class="item.status" :style="{ width: item.progress + '%' }"></span>
                </div>
                <small v-if="item.error" class="upload-progress-error">{{ item.error }}</small>
              </div>
            </section>
            <span class="upload-dropzone-icon" aria-hidden="true">↑</span>
            <strong>点击或拖动文件到此处上传</strong>
            <span>支持 .txt、.docx、.csv、.xlsx、.pdf、.md、.html、.pptx 类型文件</span>
            <small>不超过 2 GiB 直接上传，较大文件分片上传；分片到齐后自动合并。刷新后重新选择相同文件可续传。</small>
          </div>
          <div v-if="selectedIds.length" class="selection-toolbar"><strong>已选 {{ selectedIds.length }} 项</strong><button type="button" :disabled="fileOperationBusy" @click="selectedIds = []">取消选择</button><button v-if="!isTrash" type="button" :disabled="fileOperationBusy || !downloadableSelection.length" :title="downloadableSelection.length ? '下载所选的可下载文件' : '所选项目没有可下载的文件'" @click="downloadSelected">批量下载</button><button v-if="!isTrash" type="button" :disabled="fileOperationBusy" @click="openMoveDialog">移动到</button><button type="button" :disabled="fileOperationBusy" @click="openBatchDialog">{{ isTrash ? '恢复所选' : '删除所选' }}</button></div>
          <div class="file-table-head"><input type="checkbox" aria-label="选择本页全部项目" :checked="items.length > 0 && selectedIds.length === items.length" :indeterminate="selectedIds.length > 0 && selectedIds.length < items.length" :disabled="fileOperationBusy || !items.length" @change="selectedIds = $event.target.checked ? items.map(item => item.id) : []" /><span>名称</span><span>{{ isTrash ? '删除时间' : '修改时间' }}</span><span>大小</span><span class="folder-action-heading">操作</span></div>

          <div v-if="loading" class="file-state" role="status">正在加载文件列表…</div>
          <div v-else-if="errorMessage" class="file-state error" role="alert">{{ errorMessage }}<button type="button" @click="loadFiles">重试</button></div>
          <div v-else-if="items.length" class="file-list">
            <div v-for="item in items" :key="item.id" class="file-row" :class="{ selected: selectedIds.includes(item.id) }" @click="activateItem(item)">
              <input v-model="selectedIds" type="checkbox" :value="item.id" :aria-label="`选择 ${item.name}`" :disabled="fileOperationBusy" @click.stop />
              <button class="file-entry" type="button" @click.stop="activateItem(item)">
                <span class="file-name"><span class="file-icon" :class="item.type.toLowerCase()">{{ item.type === 'DIRECTORY' ? '📁' : '📄' }}</span><strong>{{ item.name }}<small v-if="isTrash" class="trash-origin">原目录：{{ item.parentName }}</small></strong></span>
                <span class="file-date">{{ formatDate(isTrash ? item.deletedAt : item.updatedAt) }}</span>
                <span class="file-size">{{ item.type === 'DIRECTORY' ? '—' : formatSize(item.sizeBytes) }}</span>
              </button>
              <div class="folder-row-actions" @click.stop>
                <span v-if="!isTrash && item.type === 'FILE'" :title="canDownload(item) ? '下载' : '该文件不支持下载'">
                  <button type="button" :aria-label="`下载 ${item.name}`" :disabled="fileOperationBusy || !canDownload(item)" @click="downloadOne(item)">↓</button>
                </span>
                <button v-if="!isTrash && item.type === 'DIRECTORY'" type="button" :aria-label="`重命名文件夹 ${item.name}`" title="重命名" :disabled="fileOperationBusy" @click="openRenameDialog(item)">✎</button>
                <button type="button" class="folder-delete-action" :aria-label="`${isTrash ? '恢复' : '删除'} ${item.name}`" :title="isTrash ? '恢复' : '删除'" :disabled="fileOperationBusy" @click="openDeleteDialog(item)">{{ isTrash ? '↶' : '×' }}</button>
              </div>
            </div>
          </div>
          <div v-else class="file-state empty-state">
            <div class="empty-art"><div class="folder-back"></div><div class="folder-front"><span>＋</span></div><span class="empty-spark spark-a">✦</span><span class="empty-spark spark-b">✦</span></div>
            <h3>{{ isTrash ? '回收站是空的' : '这个目录还没有文件' }}</h3>
            <p>文件和文件夹会显示在这里。</p>
          </div>
          <div v-if="!loading && page.totalPages > 1" class="file-pagination">
            <button type="button" :disabled="page.number === 0" @click="changePage(page.number - 1)">上一页</button>
            <span>第 {{ page.number + 1 }} / {{ page.totalPages }} 页 · 共 {{ page.totalElements }} 项</span>
            <button type="button" :disabled="page.number + 1 >= page.totalPages" @click="changePage(page.number + 1)">下一页</button>
          </div>
        </section>
        <div v-if="folderDialogOpen" class="modal-backdrop" @click.self="closeFolderDialog">
          <section class="folder-dialog" role="dialog" aria-modal="true" aria-labelledby="folder-dialog-title">
            <h2 id="folder-dialog-title">{{ folderDialogMode === 'create' ? '新建文件夹' : '重命名文件夹' }}</h2>
            <form @submit.prevent="saveFolderName">
              <label for="new-folder-name">文件夹名称</label>
              <input id="new-folder-name" v-model="folderName" type="text" maxlength="255" autofocus autocomplete="off" :aria-invalid="Boolean(folderDialogError || folderValidationError)" @input="folderDialogError = ''" />
              <p v-if="folderDialogError || folderValidationError" class="dialog-error" role="alert">{{ folderDialogError || folderValidationError }}</p>
              <div class="dialog-actions">
                <button type="button" :disabled="savingFolderName" @click="closeFolderDialog">取消</button>
                <button type="submit" :disabled="savingFolderName">{{ savingFolderName ? '保存中…' : (folderDialogMode === 'create' ? '创建' : '保存') }}</button>
              </div>
            </form>
          </section>
        </div>
        <div v-if="deleteTarget" class="modal-backdrop" @click.self="closeDeleteDialog">
          <section class="folder-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-folder-title">
            <h2 id="delete-folder-title">{{ deleteTarget.restore ? '恢复内容' : '移入回收站' }}</h2>
            <p class="delete-folder-copy">确定{{ deleteTarget.restore ? '恢复' : '删除' }}{{ deleteTarget.ids.length > 1 ? `这 ${deleteTarget.ids.length} 项` : `“${deleteTarget.name}”` }}吗？{{ deleteTarget.restore ? '内容将回到原目录，同名时自动编号。' : '文件夹内的内容会一起移入回收站，之后可以恢复。' }}</p>
            <p v-if="deleteFolderError" class="dialog-error" role="alert">{{ deleteFolderError }}</p>
            <div class="dialog-actions">
              <button type="button" :disabled="deletingFolder" @click="closeDeleteDialog">取消</button>
              <button class="danger-action" type="button" :disabled="deletingFolder" @click="confirmDeleteFolder">{{ deletingFolder ? '处理中…' : (deleteTarget.restore ? '确认恢复' : '确认删除') }}</button>
            </div>
          </section>
        </div>
        <div v-if="moveDialogOpen" class="modal-backdrop" @click.self="closeMoveDialog">
          <section class="folder-dialog" role="dialog" aria-modal="true" aria-labelledby="move-dialog-title">
            <h2 id="move-dialog-title">移动到</h2>
            <p class="move-target-label">{{ moveTarget ? `目标文件夹：${moveTarget.name}` : '请选择目标文件夹' }}</p>
            <div class="move-tree" role="tree" aria-label="目标文件夹">
              <FileTreeNode v-for="root in moveTreeRoots" :key="root.id" :node="root"
                            :selected-id="moveTarget?.id ?? ''" @toggle="toggleMoveNode"
                            @activate="activateMoveNode" />
              <p v-if="!moveTreeRoots.length && !moveError" class="tree-message">暂无可用目标文件夹</p>
            </div>
            <p v-if="moveError" class="dialog-error" role="alert">{{ moveError }}</p>
            <div class="dialog-actions">
              <button type="button" :disabled="moving" @click="closeMoveDialog">取消</button>
              <button class="primary-action" type="button" :disabled="moving || !moveTarget" @click="confirmMove">{{ moving ? '移动中…' : '确认移动' }}</button>
            </div>
          </section>
        </div>
        <p v-if="accountError" class="drive-notice error" role="alert">{{ accountError }}</p>
        <p v-if="fileOperationMessage" class="drive-notice" :class="{ error: fileOperationError }" :role="fileOperationError ? 'alert' : 'status'">{{ fileOperationMessage }}</p>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { changePassword, clearLoginSession, logout } from '../api/auth'
import FileTreeNode from '../components/FileTreeNode.vue'
import { createDirectory, deleteFile, deleteFiles, downloadFile, downloadFiles, listFolderTree, moveFiles, restoreFile, restoreFiles, listTrash, listFiles, renameDirectory } from '../api/files'
import { uploadSelectedFile } from '../upload/upload.js'
import { isLoginExpired } from '../preview/files.js'

const route = useRoute()
const router = useRouter()
const savedUser = JSON.parse(localStorage.getItem('current_user') || '{}')
const username = ref(savedUser.username || '网盘用户')
const initial = computed(() => [...username.value][0]?.toUpperCase() || '网')
const sidebarCollapsed = ref(false)
const treeRoot = ref({
  id: null,
  name: '我的文件',
  type: 'DIRECTORY',
  expanded: true,
  children: [],
  childrenLoaded: false,
  loading: false,
  error: ''
})
const selectedTreeNodeId = ref('root')
const currentPassword = ref('')
const newPassword = ref('')
const busy = ref(false)
const accountError = ref('')
const data = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const selectedIds = ref([])
const isTrash = computed(() => route.query.view === 'trash')
const uploadInput = ref(null)
const dragDepth = ref(0)
const isDragging = ref(false)
const folderDialogOpen = ref(false)
const folderDialogMode = ref('create')
const editingFolderId = ref(null)
const folderName = ref('')
const folderDialogError = ref('')
const folderValidationError = computed(() => {
  const name = folderName.value.trim()
  if (!name) return ''
  if (name.length > 255) return '文件夹名称不能超过 255 个字符'
  if (name === '.' || name === '..' || /[\u0000-\u001f<>:"/\\|?*]/.test(name)) {
    return '文件夹名称不能包含 < > : " / \\ | ? * 等字符'
  }
  return ''
})
const savingFolderName = ref(false)
const deleteTarget = ref(null)
const deleteFolderError = ref('')
const deletingFolder = ref(false)
const moving = ref(false)
const moveDialogOpen = ref(false)
const moveTreeRoots = ref([])
const moveTarget = ref(null)
const moveError = ref('')
const uploading = ref(false)
const downloading = ref(false)
const uploadItems = ref([])
const fileOperationMessage = ref('')
const fileOperationError = ref(false)
const items = computed(() => data.value?.items || [])
const downloadableSelection = computed(() => items.value.filter(item => selectedIds.value.includes(item.id) && canDownload(item)))
const breadcrumbs = computed(() => data.value?.breadcrumbs || [{ id: null, name: '我的文件' }])
const currentTitle = computed(() => isTrash.value ? '回收站' : (data.value?.currentDirectory?.name || '我的文件'))
const page = computed(() => data.value?.page || { number: 0, size: 50, totalElements: 0, totalPages: 0 })
const fileOperationBusy = computed(() => loading.value || savingFolderName.value || deletingFolder.value || moving.value || uploading.value || downloading.value)
const uploadCompletedCount = computed(() => uploadItems.value.filter(item => item.status === 'success').length)

function uploadStatusText(status) {
  return { queued: '等待中', hashing: '计算完整文件指纹', uploading: '上传中', success: '上传成功', error: '上传失败，可重新选择续传' }[status] || status
}

function updateUploadItem(id, patch) {
  const item = uploadItems.value.find(current => current.id === id)
  if (item) Object.assign(item, patch)
}

function canDownload(item) {
  return item.type === 'FILE' && item.downloadAllowed !== false && item.previewAllowed !== false
}

async function downloadOne(item) {
  if (!canDownload(item) || downloading.value) return
  downloading.value = true
  fileOperationMessage.value = ''
  fileOperationError.value = false
  try {
    await downloadFile({ filename: item.name, fileId: item.id })
    fileOperationMessage.value = `已开始下载 ${item.name}`
  } catch (error) {
    fileOperationError.value = true
    fileOperationMessage.value = error.message
    if (error.status === 401 && error.code !== 'INVALID_PARAM') handleFileMutationError(error)
  } finally {
    downloading.value = false
  }
}

async function downloadSelected() {
  if (downloading.value || !downloadableSelection.value.length) return
  const chosen = [...downloadableSelection.value]
  const skipped = selectedIds.value.length - chosen.length
  downloading.value = true
  fileOperationError.value = false
  fileOperationMessage.value = ''
  try {
    await downloadFiles(chosen.map(item => item.id))
    fileOperationMessage.value = `已开始下载 ${chosen.length} 个文件的 ZIP 压缩包${skipped ? `，跳过 ${skipped} 项不可下载内容` : ''}`
  } catch (error) {
    fileOperationError.value = true
    fileOperationMessage.value = error.message
    if (error.status === 401) handleFileMutationError(error)
  } finally {
    downloading.value = false
  }
}
let latestListRequest = 0

watch(() => [route.query.parentId, route.query.page, route.query.view], loadFiles, { immediate: true })
watch(() => route.query.parentId, parentId => {
  selectedTreeNodeId.value = typeof parentId === 'string' ? parentId : 'root'
}, { immediate: true })
watch(() => treeRoot.value.expanded, expanded => {
  if (expanded && !treeRoot.value.childrenLoaded) loadTreeChildren(treeRoot.value)
}, { immediate: true })

async function loadTreeChildren(node, force = false) {
  if (node.loading || (node.childrenLoaded && !force)) return
  node.loading = true
  node.error = ''
  try {
    const firstPage = await listFiles({ parentId: node.id, page: 0, size: 100 })
    const children = [...firstPage.items]
    for (let pageNumber = 1; pageNumber < firstPage.page.totalPages; pageNumber++) {
      const nextPage = await listFiles({ parentId: node.id, page: pageNumber, size: 100 })
      children.push(...nextPage.items)
    }
    const previous = new Map(node.children.map(child => [child.id, child]))
    node.children = children.map(child => ({
      ...child,
      expanded: false,
      children: [],
      childrenLoaded: false,
      loading: false,
      error: '',
      ...previous.get(child.id),
      ...child
    }))
    node.childrenLoaded = true
  } catch (error) {
    node.error = error.message
    if (error.status === 401) {
      clearLoginSession()
      await router.replace('/login')
    }
  } finally {
    node.loading = false
  }
}

function toggleTreeNode(node) {
  node.expanded = !node.expanded
  if (node.expanded && !node.childrenLoaded) loadTreeChildren(node)
}

function retryTreeNode(node) {
  loadTreeChildren(node, true)
}

function activateTreeNode(node) {
  if (fileOperationBusy.value) return
  selectedTreeNodeId.value = String(node.id ?? 'root')
  if (node.type === 'DIRECTORY') openDirectory(node.id)
}

async function loadFiles() {
  // 路由变化时可能同时存在多个请求，只允许最后发出的请求更新当前目录列表。
  const requestId = ++latestListRequest
  loading.value = true
  selectedIds.value = []
  errorMessage.value = ''
  try {
    const rawParentId = route.query.parentId
    const rawPage = route.query.page
    const parsedPage = Number(rawPage || 0)
    const result = await (isTrash.value ? listTrash : listFiles)({
      parentId: typeof rawParentId === 'string' ? rawParentId : null,
      page: Number.isSafeInteger(parsedPage) && parsedPage >= 0 ? parsedPage : 0,
      size: 50
    })
    if (requestId !== latestListRequest) return
    data.value = result
    selectedIds.value = []
  } catch (error) {
    if (requestId !== latestListRequest) return
    errorMessage.value = error.message
    if (error.status === 401) {
      clearLoginSession()
      await router.replace('/login')
    }
  } finally {
    if (requestId === latestListRequest) loading.value = false
  }
}

function openDirectory(id) {
  router.push({ path: '/drive', query: id == null ? {} : { parentId: String(id) } })
}

function activateItem(item) {
  if (isTrash.value || fileOperationBusy.value) return
  if (item.type === 'DIRECTORY') openDirectory(item.id)
  else router.push({ path: `/drive/preview/${item.id}`, query: { ...route.query } })
}

function changePage(number) {
  router.push({ path: '/drive', query: { ...route.query, page: String(number) } })
}

function openFolderDialog() {
  folderDialogMode.value = 'create'
  editingFolderId.value = null
  folderName.value = ''
  folderDialogError.value = ''
  fileOperationError.value = false
  fileOperationMessage.value = ''
  folderDialogOpen.value = true
}

function openRenameDialog(item) {
  folderDialogMode.value = 'rename'
  editingFolderId.value = item.id
  folderName.value = item.name
  folderDialogError.value = ''
  fileOperationError.value = false
  fileOperationMessage.value = ''
  folderDialogOpen.value = true
}

function closeFolderDialog() {
  if (savingFolderName.value) return
  folderDialogOpen.value = false
}

async function saveFolderName() {
  const name = folderName.value.trim()
  if (!name) {
    folderDialogError.value = '请输入文件夹名称'
    return
  }
  if (folderValidationError.value) {
    folderDialogError.value = folderValidationError.value
    return
  }
  savingFolderName.value = true
  folderDialogError.value = ''
  fileOperationMessage.value = ''
  try {
    let saved
    if (folderDialogMode.value === 'create') {
      const parentId = typeof route.query.parentId === 'string' ? route.query.parentId : null
      saved = await createDirectory({ folderName: name, parentId })
    } else {
      if (await hasDirectoryNameConflict(name, editingFolderId.value)) {
        folderDialogError.value = '同一目录中已存在相同名称'
        fileOperationError.value = true
        fileOperationMessage.value = folderDialogError.value
        return
      }
      saved = await renameDirectory(editingFolderId.value, name)
    }
    folderDialogOpen.value = false
    fileOperationMessage.value = saved.name === name
      ? `文件夹“${saved.name}”已${folderDialogMode.value === 'create' ? '创建' : '重命名'}`
      : `名称已存在，文件夹已${folderDialogMode.value === 'create' ? '创建' : '重命名'}为“${saved.name}”`
    await refreshFirstPage()
  } catch (error) {
    const action = folderDialogMode.value === 'create' ? '创建' : '重命名'
    folderDialogError.value = error.message
    fileOperationError.value = true
    fileOperationMessage.value = `${action}文件夹失败：${error.message}`
    handleFileMutationError(error)
  } finally {
    savingFolderName.value = false
  }
}

async function hasDirectoryNameConflict(name, excludedId) {
  const parentId = typeof route.query.parentId === 'string' ? route.query.parentId : null
  let currentPage = 0
  let totalPages = 1
  const normalizedName = name.trim().toLocaleLowerCase('zh-CN')
  while (currentPage < totalPages) {
    const result = await listFiles({ parentId, page: currentPage, size: 100 })
    if (result.items.some(item => String(item.id) !== String(excludedId)
      && item.name.trim().toLocaleLowerCase('zh-CN') === normalizedName)) return true
    totalPages = result.page.totalPages
    currentPage += 1
  }
  return false
}

function openDeleteDialog(item) {
  deleteTarget.value = { name: item.name, ids: [item.id], restore: isTrash.value }
  deleteFolderError.value = ''
  fileOperationError.value = false
  fileOperationMessage.value = ''
}

function openBatchDialog() {
  if (!selectedIds.value.length) return
  deleteTarget.value = { ids: [...selectedIds.value], restore: isTrash.value }
  deleteFolderError.value = ''
  fileOperationError.value = false
}

function mapMoveTreeNode(node) {
  return {
    id: node.id,
    name: node.name,
    type: 'DIRECTORY',
    expanded: false,
    children: (node.children || []).map(mapMoveTreeNode),
    childrenLoaded: true,
    loading: false,
    error: ''
  }
}

async function openMoveDialog() {
  if (!selectedIds.value.length || isTrash.value || moving.value) return
  moveTarget.value = null
  moveError.value = ''
  fileOperationError.value = false
  fileOperationMessage.value = ''
  moveDialogOpen.value = true
  try {
    moveTreeRoots.value = (await listFolderTree()).map(mapMoveTreeNode)
  } catch (error) {
    moveTreeRoots.value = []
    moveError.value = error.message
    handleFileMutationError(error)
  }
}

function toggleMoveNode(node) {
  node.expanded = !node.expanded
}

function activateMoveNode(node) {
  if (node.type === 'DIRECTORY') moveTarget.value = { id: node.id, name: node.name }
}

function closeMoveDialog() {
  if (moving.value) return
  moveDialogOpen.value = false
  moveError.value = ''
}

async function confirmMove() {
  if (!moveTarget.value || moving.value) return
  const ids = [...selectedIds.value]
  const target = { ...moveTarget.value }
  moving.value = true
  moveError.value = ''
  fileOperationMessage.value = ''
  fileOperationError.value = false
  try {
    await moveFiles(ids, target.id)
    moveDialogOpen.value = false
    fileOperationMessage.value = `已将 ${ids.length} 项移动到“${target.name}”`
    await refreshFirstPage()
  } catch (error) {
    moveError.value = error.message
    fileOperationError.value = true
    fileOperationMessage.value = `移动失败：${error.message}`
    handleFileMutationError(error)
  } finally {
    moving.value = false
  }
}

function closeDeleteDialog() {
  if (deletingFolder.value) return
  deleteTarget.value = null
  deleteFolderError.value = ''
}

async function confirmDeleteFolder() {
  if (!deleteTarget.value || deletingFolder.value) return
  const target = deleteTarget.value
  deletingFolder.value = true
  deleteFolderError.value = ''
  fileOperationMessage.value = ''
  try {
    const single = target.restore ? restoreFile : deleteFile
    const batch = target.restore ? restoreFiles : deleteFiles
    if (target.ids.length === 1) await single(target.ids[0])
    else await batch(target.ids)
    deleteTarget.value = null
    fileOperationMessage.value = target.restore ? '所选内容已恢复' : '所选内容已移入回收站'
    await refreshFirstPage()
  } catch (error) {
    deleteFolderError.value = error.message
    fileOperationError.value = true
    fileOperationMessage.value = `${target.restore ? '恢复' : '删除'}失败：${error.message}`
    handleFileMutationError(error)
  } finally {
    deletingFolder.value = false
  }
}

function chooseUpload() {
  if (fileOperationBusy.value) return
  uploadInput.value?.click()
}

function handleDragEnter(event) {
  if (fileOperationBusy.value || !Array.from(event.dataTransfer?.types || []).includes('Files')) return
  dragDepth.value += 1
  isDragging.value = true
}

function handleDragOver(event) {
  if (!fileOperationBusy.value && event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

function handleDragLeave() {
  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) isDragging.value = false
}

function handleDrop(event) {
  dragDepth.value = 0
  isDragging.value = false
  if (fileOperationBusy.value) return
  uploadSelectedFiles([...event.dataTransfer.files])
}

async function uploadSelectedFiles(source) {
  const selectedFiles = Array.isArray(source) ? source : [...(source.target?.files || [])]
  if (source.target) source.target.value = ''
  if (!selectedFiles.length) return

  uploading.value = true
  fileOperationMessage.value = ''
  fileOperationError.value = false
  const parentId = typeof route.query.parentId === 'string' ? route.query.parentId : null
  const batchId = Date.now()
  uploadItems.value = selectedFiles.map((file, index) => ({
    id: 'upload-' + batchId + '-' + index,
    name: file.name,
    progress: 0,
    status: 'queued',
    error: ''
  }))
  let uploadedCount = 0
  let failedCount = 0
  let instantCount = 0
  try {
    for (const [index, file] of selectedFiles.entries()) {
      const itemId = 'upload-' + batchId + '-' + index
      updateUploadItem(itemId, { status: 'hashing', progress: 0, error: '' })
      try {
        const result = await uploadSelectedFile({
          file, parentId, userId: savedUser.userId,
          onHashProgress: progress => updateUploadItem(itemId, { status: 'hashing', progress }),
          onProgress: progress => updateUploadItem(itemId, { status: 'uploading', progress })
        })
        if (result.instant) {
          instantCount += 1
        }
        updateUploadItem(itemId, { status: 'success', progress: 100 })
        uploadedCount += 1
      } catch (error) {
        updateUploadItem(itemId, { status: 'error', error: error.message })
        failedCount += 1
        fileOperationError.value = true
        fileOperationMessage.value = `${file.name} 上传失败：${error.message}`
        handleFileMutationError(error)
        // 登录失效后终止整个上传批次，避免跳转登录期间继续发送分片。
        if (isLoginExpired(error)) break
      }
    }
    if (uploadedCount) {
      if (!fileOperationError.value) fileOperationMessage.value = `已上传 ${uploadedCount} 个文件`
      fileOperationMessage.value = failedCount
        ? '上传完成：' + uploadedCount + ' 个成功，' + failedCount + ' 个失败'
        : instantCount === uploadedCount
          ? '上传成功：' + instantCount + ' 个文件已秒传'
          : instantCount
            ? '上传成功：' + instantCount + ' 个文件秒传，' + (uploadedCount - instantCount) + ' 个文件已上传'
            : '上传成功：已上传 ' + uploadedCount + ' 个文件'
      await refreshFirstPage()
    }
  } finally {
    uploading.value = false
  }
}

async function refreshFirstPage() {
  const query = isTrash.value ? { view: 'trash' } : {}
  if (typeof route.query.parentId === 'string') query.parentId = route.query.parentId
  if (route.query.page) await router.replace({ path: '/drive', query })
  await loadFiles()
  await refreshLoadedTree(treeRoot.value)
}

async function refreshLoadedTree(node) {
  if (!node.childrenLoaded) return
  await loadTreeChildren(node, true)
  for (const child of node.children) {
    if (child.type === 'DIRECTORY' && child.childrenLoaded) await refreshLoadedTree(child)
  }
}

function handleFileMutationError(error) {
  if (isLoginExpired(error)) {
    clearLoginSession()
    router.replace('/login')
  }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatSize(bytes = 0) {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = bytes / 1024
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit += 1 }
  return `${size.toFixed(size < 10 ? 1 : 0)} ${units[unit]}`
}

async function changeUserPassword() {
  accountError.value = ''
  if (!currentPassword.value || newPassword.value.length < 6 || newPassword.value.length > 64) {
    accountError.value = '请输入当前密码和 6-64 位新密码'
    return
  }
  busy.value = true
  try {
    await changePassword(currentPassword.value, newPassword.value)
    clearLoginSession()
    await router.replace('/login')
  } catch (error) {
    accountError.value = error.message
    if (error.status === 401) { clearLoginSession(); await router.replace('/login') }
  } finally { busy.value = false }
}

async function signOut() {
  busy.value = true
  accountError.value = ''
  try {
    await logout()
    clearLoginSession()
    await router.replace('/login')
  } catch (error) {
    if (error.status === 401) { clearLoginSession(); await router.replace('/login') }
    else accountError.value = `退出失败：${error.message}。当前登录状态仍保留，请重试。`
  } finally { busy.value = false }
}
</script>
