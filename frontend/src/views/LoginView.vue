<template>
  <div class="auth-content">
    <div class="page-heading">
      <p class="eyebrow">WELCOME BACK</p>
      <h2>登录卡码网盘</h2>
      <p>登录后访问你的文件和目录。</p>
    </div>
    <form class="auth-form" @submit.prevent="submit">
      <label>用户名<input v-model.trim="username" type="text" autocomplete="username" placeholder="请输入用户名" /></label>
      <span v-if="errors.username" class="field-error">{{ errors.username }}</span>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" /></label>
      <span v-if="errors.password" class="field-error">{{ errors.password }}</span>
      <div class="captcha-section">
        <label>图片验证码
          <div class="captcha-row">
            <input v-model.trim="captchaCode" inputmode="numeric" maxlength="4" placeholder="请输入4位数字" />
            <button class="captcha-image-button" type="button" :disabled="captchaLoading" @click="refreshCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="图片验证码" />
              <span v-else>点击加载</span>
            </button>
          </div>
        </label>
      </div>
      <button class="primary-button" :disabled="submitting || lockedSeconds > 0" type="submit">{{ submitting ? '登录中...' : '登录' }}</button>
    </form>
    <p v-if="message || lockedSeconds > 0" :class="['form-message', messageType]">
      {{ lockedSeconds > 0 ? `账号已锁定，还剩 ${Math.ceil(lockedSeconds / 60)} 分钟` : message }}
    </p>
    <p class="switch-page">还没有账号？<RouterLink to="/register">立即注册</RouterLink></p>
  </div>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getCaptcha, login } from '../api/auth'

const router = useRouter()
const username = ref('')
const password = ref('')
const captchaCode = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)
const lockedSeconds = ref(0)
const message = ref('')
const messageType = ref('error')
const submitting = ref(false)
const errors = reactive({ username: '', password: '' })
let lockTimer

watch([username, password], () => {
  captchaCode.value = ''
  captchaImage.value = ''
})

async function refreshCaptcha() {
  if (captchaLoading.value) return
  if (!username.value || !password.value) {
    messageType.value = 'error'
    message.value = '请先输入用户名和密码'
    return
  }
  message.value = ''
  captchaLoading.value = true
  try {
    const data = await getCaptcha(username.value)
    captchaImage.value = data.image
    captchaCode.value = ''
  } catch (error) {
    messageType.value = 'error'
    message.value = error.message
  } finally {
    captchaLoading.value = false
  }
}

function startLockCountdown(seconds) {
  clearInterval(lockTimer)
  lockedSeconds.value = Math.max(0, Math.ceil(seconds || 0))
  lockTimer = setInterval(() => {
    lockedSeconds.value -= 1
    if (lockedSeconds.value <= 0) clearInterval(lockTimer)
  }, 1000)
}

async function submit() {
  errors.username = ''
  errors.password = ''
  message.value = ''
  if (!username.value) errors.username = '请输入用户名'
  else if (username.value.length < 3 || username.value.length > 32) errors.username = '用户名长度为3-32位'
  if (!password.value) errors.password = '请输入密码'
  else if (password.value.length < 6 || password.value.length > 64) errors.password = '密码长度为6-64位'
  if (!/^\d{4}$/.test(captchaCode.value)) message.value = '请输入图片中的4位验证码'
  if (errors.username || errors.password || !/^\d{4}$/.test(captchaCode.value) || submitting.value) return

  submitting.value = true
  try {
    const data = await login({ username: username.value, password: password.value, captchaCode: captchaCode.value })
    localStorage.setItem('current_user', JSON.stringify({ userId: data.userId, username: data.username }))
    password.value = ''
    await router.push('/drive')
  } catch (error) {
    messageType.value = 'error'
    if (error.code === 'CAPTCHA_REQUIRED') {
      message.value = '请先完成图片验证码'
      await refreshCaptcha()
    } else if (error.code === 'CAPTCHA_INVALID') {
      message.value = '验证码错误或已过期，请刷新后重试'
      await refreshCaptcha()
    } else if (error.code === 'ACCOUNT_LOCKED') {
      startLockCountdown(error.retryAfter)
      message.value = ''
    } else if (error.code === 'IP_RATE_LIMITED') message.value = '请求过于频繁，请稍后重试'
    else if (error.code === 'DEVICE_RATE_LIMITED') message.value = '当前设备登录请求过于频繁，请稍后重试'
    else if (error.code === 'LOGIN_FAILED') {
      message.value = error.message
      await refreshCaptcha()
    } else message.value = error.message
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => clearInterval(lockTimer))
</script>
