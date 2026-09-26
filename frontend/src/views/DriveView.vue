<template>
  <div class="drive-layout">
    <aside class="drive-sidebar">
      <RouterLink class="drive-brand" to="/drive" aria-label="网盘首页">
        <span class="brand-mark">K</span><span>卡码网盘</span>
      </RouterLink>
      <nav class="drive-nav" aria-label="网盘导航">
        <RouterLink class="active" to="/drive"><span>▣</span> 我的文件</RouterLink>
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
            <h1>你好，{{ username }}</h1>
            <p class="welcome-subtitle">欢迎回到你的网盘，文件都在这里妥善保存。</p>
          </div>
          <div class="welcome-illustration" aria-hidden="true"><span class="cloud-shape">☁</span><span class="cloud-dot dot-one"></span><span class="cloud-dot dot-two"></span></div>
        </div>

        <section class="file-section">
          <div class="section-heading">
            <div><h2>{{ currentTitle }}</h2><p>个人网盘</p></div>
          </div>
          <nav class="file-breadcrumbs" aria-label="目录导航">
            <template v-for="(crumb, index) in breadcrumbs" :key="crumb.id ?? 'root'">
              <span v-if="index" class="breadcrumb-slash">/</span>
              <button type="button" :disabled="index === breadcrumbs.length - 1 || loading" @click="openDirectory(crumb.id)">{{ crumb.name }}</button>
            </template>
          </nav>
          <div class="file-table-head"><span>名称</span><span>修改时间</span><span>大小</span></div>

          <div v-if="loading" class="file-state" role="status">正在加载文件列表…</div>
          <div v-else-if="errorMessage" class="file-state error" role="alert">{{ errorMessage }}<button type="button" @click="loadFiles">重试</button></div>
          <div v-else-if="items.length" class="file-list">
            <button v-for="item in items" :key="item.id" class="file-row" :class="{ selected: selectedFileId === item.id }"
                    type="button" @click="activateItem(item)">
              <span class="file-name"><span class="file-icon" :class="item.type.toLowerCase()">{{ item.type === 'DIRECTORY' ? '📁' : '📄' }}</span><strong>{{ item.name }}</strong></span>
              <span class="file-date">{{ formatDate(item.updatedAt) }}</span>
              <span class="file-size">{{ item.type === 'DIRECTORY' ? '—' : formatSize(item.sizeBytes) }}</span>
            </button>
          </div>
          <div v-else class="file-state empty-state">
            <div class="empty-art"><div class="folder-back"></div><div class="folder-front"><span>＋</span></div><span class="empty-spark spark-a">✦</span><span class="empty-spark spark-b">✦</span></div>
            <h3>这个目录还没有文件</h3>
            <p>文件和文件夹会显示在这里。</p>
          </div>
          <div v-if="!loading && page.totalPages > 1" class="file-pagination">
            <button type="button" :disabled="page.number === 0" @click="changePage(page.number - 1)">上一页</button>
            <span>第 {{ page.number + 1 }} / {{ page.totalPages }} 页 · 共 {{ page.totalElements }} 项</span>
            <button type="button" :disabled="page.number + 1 >= page.totalPages" @click="changePage(page.number + 1)">下一页</button>
          </div>
          <p v-if="selectedFile" class="selected-file" role="status">已选择文件：{{ selectedFile.name }}（{{ formatSize(selectedFile.sizeBytes) }}）</p>
        </section>
        <p v-if="accountError" class="drive-notice error" role="alert">{{ accountError }}</p>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { changePassword, clearLoginSession, logout } from '../api/auth'
import { listFiles } from '../api/files'

const route = useRoute()
const router = useRouter()
const savedUser = JSON.parse(localStorage.getItem('current_user') || '{}')
const username = ref(savedUser.username || '网盘用户')
const initial = computed(() => [...username.value][0]?.toUpperCase() || '网')
const currentPassword = ref('')
const newPassword = ref('')
const busy = ref(false)
const accountError = ref('')
const data = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const selectedFileId = ref(null)
const items = computed(() => data.value?.items || [])
const breadcrumbs = computed(() => data.value?.breadcrumbs || [{ id: null, name: '我的文件' }])
const currentTitle = computed(() => data.value?.currentDirectory?.name || '全部文件')
const page = computed(() => data.value?.page || { number: 0, size: 50, totalElements: 0, totalPages: 0 })
const selectedFile = computed(() => items.value.find(item => item.id === selectedFileId.value && item.type === 'FILE'))

watch(() => [route.query.parentId, route.query.page], loadFiles, { immediate: true })

async function loadFiles() {
  loading.value = true
  errorMessage.value = ''
  try {
    data.value = await listFiles({
      parentId: route.query.parentId ?? null,
      page: Number(route.query.page || 0),
      size: 50
    })
    selectedFileId.value = null
  } catch (error) {
    errorMessage.value = error.message
    if (error.status === 401) {
      clearLoginSession()
      await router.replace('/login')
    }
  } finally {
    loading.value = false
  }
}

function openDirectory(id) {
  router.push({ path: '/drive', query: id == null ? {} : { parentId: String(id) } })
}

function activateItem(item) {
  if (item.type === 'DIRECTORY') openDirectory(item.id)
  else selectedFileId.value = item.id
}

function changePage(number) {
  router.push({ path: '/drive', query: { ...route.query, page: String(number) } })
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
