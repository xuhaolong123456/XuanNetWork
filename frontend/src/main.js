import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import DriveView from './views/DriveView.vue'
import { clearLoginSession, getCurrentUser } from './api/auth'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/drive' },
    { path: '/drive', component: DriveView, meta: { requiresAuth: true, fullscreen: true } },
    { path: '/login', component: LoginView, meta: { guestOnly: true } },
    { path: '/register', component: RegisterView, meta: { guestOnly: true } }
  ]
})

router.beforeEach(async to => {
  let user = null
  try {
    user = await getCurrentUser()
    localStorage.setItem('current_user', JSON.stringify(user))
  } catch (error) {
    if (error.status === 401) clearLoginSession()
  }

  if (to.meta.requiresAuth && !user) return '/login'
  if (to.meta.guestOnly && user) return '/drive'
})

createApp(App).use(router).mount('#app')
