<template>
  <div class="auth-content">
    <div class="page-heading">
      <p class="eyebrow">CREATE ACCOUNT</p>
      <h2>创建你的网盘账号</h2>
      <p>注册后即可开始管理个人文件。</p>
    </div>

    <form class="auth-form" @submit.prevent="submit">
      <label>邮箱<input v-model.trim="form.email" type="email" autocomplete="email" placeholder="name@example.com" /></label>
      <span v-if="errors.email" class="field-error">{{ errors.email }}</span>

      <div class="code-section">
        <div class="code-section-heading">
          <span>邮箱验证码</span>
          <button type="button" class="code-send-button" :disabled="sendingCode || countdown > 0" @click="getEmailCode">
            {{ countdown ? countdown + 's 后重发' : (sendingCode ? '发送中...' : '获取验证码') }}
          </button>
        </div>
        <!-- 六格输入支持逐位输入、自动跳格和完整验证码粘贴。 -->
        <div class="code-inputs" @paste.prevent="pasteCode">
          <input v-for="(_, index) in codeDigits" :key="index"
                 :ref="element => { if (element) codeInputs[index] = element }"
                 v-model="codeDigits[index]" inputmode="numeric" maxlength="1"
                 :aria-label="'验证码第 ' + (index + 1) + ' 位'"
                 @input="onCodeInput(index, $event)" @keydown.backspace="onBackspace(index, $event)" />
        </div>
      </div>
      <span v-if="errors.emailCode" class="field-error">{{ errors.emailCode }}</span>

      <label>用户名<input v-model.trim="form.nickName" type="text" autocomplete="username" placeholder="请输入用户名" /></label>
      <span v-if="errors.nickName" class="field-error">{{ errors.nickName }}</span>
      <label>密码<input v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 6 位密码" /></label>
      <span v-if="errors.password" class="field-error">{{ errors.password }}</span>
      <label>确认密码<input v-model="form.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" /></label>
      <span v-if="errors.confirmPassword" class="field-error">{{ errors.confirmPassword }}</span>
      <button class="primary-button" :disabled="submitting" type="submit">{{ submitting ? '注册中...' : '注册' }}</button>
    </form>

    <p v-if="message" :class="['form-message', messageType]">{{ message }}</p>
    <p class="switch-page">已有账号？<RouterLink to="/login">立即登录</RouterLink></p>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { register, sendEmailCode } from '../api/auth'

const router = useRouter()
const form = reactive({ email: '', nickName: '', password: '', confirmPassword: '' })
const codeDigits = reactive(['', '', '', '', '', ''])
const emailCode = computed(() => codeDigits.join(''))
const errors = reactive({ email: '', emailCode: '', nickName: '', password: '', confirmPassword: '' })
const submitting = ref(false)
const message = ref('')
const messageType = ref('')
const sendingCode = ref(false)
const countdown = ref(0)
const codeInputs = ref([])
let countdownTimer

function focusCode(index) {
  codeInputs.value[index]?.focus()
}

function onCodeInput(index, event) {
  const digit = event.target.value.replace(/\D/g, '').slice(-1)
  codeDigits[index] = digit
  if (digit && index < codeDigits.length - 1) focusCode(index + 1)
}

function onBackspace(index, event) {
  if (!codeDigits[index] && index > 0) {
    event.preventDefault()
    codeDigits[index - 1] = ''
    focusCode(index - 1)
  }
}

function pasteCode(event) {
  const digits = event.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6)
  if (!digits) return
  for (let index = 0; index < codeDigits.length; index++) codeDigits[index] = digits[index] || ''
  focusCode(Math.min(digits.length, 6) - 1)
}

function startCountdown() {
  clearInterval(countdownTimer)
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(countdownTimer)
  }, 1000)
}

async function getEmailCode() {
  errors.email = ''
  if (!form.email) {
    errors.email = '请输入邮箱'
    return
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    errors.email = '邮箱格式错误'
    return
  }
  if (sendingCode.value || countdown.value > 0) return
  sendingCode.value = true
  try {
    await sendEmailCode(form.email)
    startCountdown()
    messageType.value = 'success'
    message.value = '验证码已发送，请查收邮件'
    focusCode(0)
  } catch (error) {
    messageType.value = 'error'
    message.value = error.message
  } finally {
    sendingCode.value = false
  }
}

function validate() {
  Object.keys(errors).forEach((key) => { errors[key] = '' })
  if (!form.email) errors.email = '请输入邮箱'
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) errors.email = '邮箱格式错误'
  if (!/^\d{6}$/.test(emailCode.value)) errors.emailCode = '请输入 6 位数字验证码'
  if (!form.nickName) errors.nickName = '请输入用户名'
  else if (form.nickName.length < 3) errors.nickName = '用户名长度不能少于 3 位'
  if (!form.password) errors.password = '请输入密码'
  else if (form.password.length < 6) errors.password = '密码长度不能少于 6 位'
  if (!form.confirmPassword) errors.confirmPassword = '请再次输入密码'
  else if (form.password !== form.confirmPassword) errors.confirmPassword = '两次密码不一致'
  return !Object.values(errors).some(Boolean)
}

async function submit() {
  message.value = ''
  if (!validate() || submitting.value) return
  submitting.value = true
  try {
    await register({ email: form.email, emailCode: emailCode.value, nickName: form.nickName, password: form.password })
    messageType.value = 'success'
    message.value = '注册成功，请登录'
    setTimeout(() => router.push('/login'), 700)
  } catch (error) {
    messageType.value = 'error'
    message.value = error.message
  } finally {
    submitting.value = false
  }
}
</script>
