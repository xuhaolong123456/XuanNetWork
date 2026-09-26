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
              <input ref="uploadInput" class="visually-hidden" type="file" multiple @change="uploadSelectedFiles" />
            </div>
          </div>
          <nav v-if="!isTrash" class="file-breadcrumbs" aria-label="目录导航">
            <template v-for="(crumb, index) in breadcrumbs" :key="crumb.id ?? 'root'">
              <span v-if="index" class="breadcrumb-slash">/</span>
              <button type="button" :disabled="index === breadcrumbs.length - 1 || loading" @click="openDirectory(crumb.id)">{{ crumb.name }}</button>
            </template>
          </nav>
          <div v-if="selectedIds.length" class="selection-toolbar"><strong>已选 {{ selectedIds.length }} 项</strong><button type="button" :disabled="fileOperationBusy" @click="selectedIds = []">取消选择</button><button type="button" :disabled="fileOperationBusy" @click="openBatchDialog">{{ isTrash ? '恢复所选' : '删除所选' }}</button></div>
          <div class="file-table-head"><input type="checkbox" aria-label="选择本页全部项目" :checked="items.length > 0 && selectedIds.length === items.length" :indeterminate="selectedIds.length > 0 && selectedIds.length < items.length" :disabled="fileOperationBusy || !items.length" @change="selectedIds = $event.target.checked ? items.map(item => item.id) : []" /><span>名称</span><span>{{ isTrash ? '删除时间' : '修改时间' }}</span><span>大小</span><span class="folder-action-heading">操作</span></div>

          <div v-if="loading" class="file-state" role="status">正在加载文件列表…</div>
          <div v-else-if="errorMessage" class="file-state error" role="alert">{{ errorMessage }}<button type="button" @click="loadFiles">重试</button></div>
          <div v-else-if="items.length" class="file-list">
            <div v-for="item in items" :key="item.id" class="file-row" :class="{ selected: selectedIds.includes(item.id) || selectedFileId === item.id }">
              <input v-model="selectedIds" type="checkbox" :value="item.id" :aria-label="`选择 ${item.name}`" :disabled="fileOperationBusy" />
              <button class="file-entry" type="button" @click="activateItem(item)">
                <span class="file-name"><span class="file-icon" :class="item.type.toLowerCase()">{{ item.type === 'DIRECTORY' ? '📁' : '📄' }}</span><strong>{{ item.name }}<small v-if="isTrash" class="trash-origin">原目录：{{ item.parentName }}</small></strong></span>
                <span class="file-date">{{ formatDate(isTrash ? item.deletedAt : item.updatedAt) }}</span>
                <span class="file-size">{{ item.type === 'DIRECTORY' ? '—' : formatSize(item.sizeBytes) }}</span>
              </button>
              <div class="folder-row-actions">
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
          <p v-if="selectedFile" class="selected-file" role="status">已选择文件：{{ selectedFile.name }}（{{ formatSize(selectedFile.sizeBytes) }}）</p>
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
import { createDirectory, deleteFile, deleteFiles, restoreFile, restoreFiles, listTrash, listFiles, renameDirectory, uploadFile } from '../api/files'

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
const selectedFileId = ref(null)
const selectedIds = ref([])
const isTrash = computed(() => route.query.view === 'trash')
const uploadInput = ref(null)
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
const uploading = ref(false)
const fileOperationMessage = ref('')
const fileOperationError = ref(false)
const items = computed(() => data.value?.items || [])
const breadcrumbs = computed(() => data.value?.breadcrumbs || [{ id: null, name: '我的文件' }])
const currentTitle = computed(() => isTrash.value ? '回收站' : (data.value?.currentDirectory?.name || '我的文件'))
const page = computed(() => data.value?.page || { number: 0, size: 50, totalElements: 0, totalPages: 0 })
const selectedFile = computed(() => items.value.find(item => item.id === selectedFileId.value && item.type === 'FILE'))
const fileOperationBusy = computed(() => loading.value || savingFolderName.value || deletingFolder.value || uploading.value)
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
    selectedFileId.value = null
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
  else selectedFileId.value = item.id
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
  uploadInput.value?.click()
}

async function uploadSelectedFiles(event) {
  const selectedFiles = [...(event.target.files || [])]
  event.target.value = ''
  if (!selectedFiles.length) return

  uploading.value = true
  fileOperationMessage.value = ''
  fileOperationError.value = false
  const parentId = typeof route.query.parentId === 'string' ? route.query.parentId : null
  let uploadedCount = 0
  try {
    for (const file of selectedFiles) {
      try {
        await uploadFile({ file, parentId })
        uploadedCount += 1
      } catch (error) {
        fileOperationError.value = true
        fileOperationMessage.value = `${file.name} 上传失败：${error.message}`
        handleFileMutationError(error)
        break
      }
    }
    if (uploadedCount) {
      if (!fileOperationError.value) fileOperationMessage.value = `已上传 ${uploadedCount} 个文件`
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
  if (error.status === 401) {
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
