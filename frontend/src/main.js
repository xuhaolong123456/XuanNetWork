import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import DriveView from './views/DriveView.vue'
import FilePreviewView from './views/FilePreviewView.vue'
import FolderGraphView from './views/FolderGraphView.vue'
import KnowledgeGraphView from './views/KnowledgeGraphView.vue'
import { clearLoginSession, getCurrentUser } from './api/auth'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/drive' },
    { path: '/drive', component: DriveView, meta: { requiresAuth: true, fullscreen: true } },
    { path: '/drive/tree', component: FolderGraphView, meta: { requiresAuth: true, fullscreen: true } },
    { path: '/drive/knowledge', component: KnowledgeGraphView, meta: { requiresAuth: true, fullscreen: true } },
    { path: '/drive/preview/:fileId', component: FilePreviewView, meta: { requiresAuth: true, fullscreen: true } },
    { path: '/login', component: LoginView, meta: { guestOnly: true } },
    { path: '/register', component: RegisterView, meta: { guestOnly: true } }
  ]
})

let currentUserRequest = null

async function resolveCurrentUser() {
  if (!currentUserRequest) {
    currentUserRequest = getCurrentUser()
      .then(user => {
        localStorage.setItem('current_user', JSON.stringify(user))
        return user
      })
      .catch(error => {
        if (error.status === 401) clearLoginSession()
        return null
      })
      .finally(() => { currentUserRequest = null })
  }
  return currentUserRequest
}

router.beforeEach(async to => {
  if (!to.meta.requiresAuth && !to.meta.guestOnly) return

  const user = await resolveCurrentUser()

  if (to.meta.requiresAuth && !user) return '/login'
  if (to.meta.guestOnly && user) return '/drive'
})

createApp(App).use(router).mount('#app')
