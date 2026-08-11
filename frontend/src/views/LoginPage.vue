<template>
  <div class="login-container">
    <div class="background-particles">
      <div v-for="n in 30" :key="n" class="particle" :style="getParticleStyle()"></div>
    </div>

    <div class="login-card glass-effect">
      <div class="card-decoration">
        <div class="decoration-line"></div>
        <div class="corner-accent left"></div>
        <div class="corner-accent right"></div>
      </div>

      <div class="logo-section">
        <div class="logo-icon">
          <div class="icon-circle">
            <i class="icon-stock"></i>
          </div>
        </div>
        <h1 class="system-title">股票决策可视化系统</h1>
        <p class="system-subtitle">Stock Vision Analytics</p>
      </div>

      <div class="form-section">
        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" class="login-form">
          <el-form-item prop="username">
            <el-input
              v-model="loginForm.username"
              placeholder="请输入账号"
              class="custom-input"
              size="large"
            />
          </el-form-item>

          <el-form-item prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              show-password
              placeholder="请输入密码"
              class="custom-input"
              size="large"
              @keyup.enter="handleLogin"
            />
          </el-form-item>

          <div class="form-actions">
            <el-checkbox v-model="rememberPassword">保存密码</el-checkbox>
            <button class="register-link" type="button" @click="registerDialogVisible = true">
              账号注册
            </button>
          </div>

          <el-form-item>
            <el-button
              type="primary"
              class="login-btn"
              size="large"
              :loading="loading"
              @click="handleLogin"
            >
              登录
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <el-dialog
      v-model="registerDialogVisible"
      title="账号注册"
      width="440px"
      append-to-body
      class="register-dialog"
      destroy-on-close
    >
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-position="top"
        class="register-form"
      >
        <el-form-item label="账号" prop="username">
          <el-input
            v-model="registerForm.username"
            placeholder="请输入账号"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="registerForm.password"
            type="password"
            show-password
            placeholder="请输入密码"
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            show-password
            placeholder="请再次输入密码"
            @keyup.enter="handleRegister"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="closeRegisterDialog">取消</el-button>
          <el-button type="primary" :loading="registerLoading" @click="handleRegister">
            确认注册
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import {
  clearRememberedLogin,
  getRememberedLogin,
  loginWithPassword,
  registerWithPassword,
  saveRememberedLogin
} from '@/api/authApi.js'

const router = useRouter()
const loginFormRef = ref()
const registerFormRef = ref()
const loading = ref(false)
const registerLoading = ref(false)
const rememberPassword = ref(false)
const registerDialogVisible = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const loginRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const registerRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_, value, callback) => {
        if (!value) {
          callback(new Error('请再次输入密码'))
          return
        }
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

const getParticleStyle = () => {
  const size = Math.random() * 3 + 1
  const animationDuration = Math.random() * 20 + 10
  const left = Math.random() * 100
  const animationDelay = Math.random() * 20

  return {
    width: `${size}px`,
    height: `${size}px`,
    left: `${left}%`,
    animationDuration: `${animationDuration}s`,
    animationDelay: `${animationDelay}s`
  }
}

const getRequestErrorMessage = (error, fallback) => {
  const fieldErrors = error?.details?.fieldErrors
  if (fieldErrors) {
    const firstFieldError = ['username', 'password', 'confirmPassword', 'displayName']
      .map((field) => fieldErrors[field])
      .find(Boolean)
    if (firstFieldError) return firstFieldError
  }
  return error?.message || fallback
}

const closeRegisterDialog = () => {
  registerDialogVisible.value = false
  registerLoading.value = false
  registerForm.username = ''
  registerForm.password = ''
  registerForm.confirmPassword = ''
  registerFormRef.value?.clearValidate()
}

const handleRegister = async () => {
  try {
    await registerFormRef.value.validate()
    registerLoading.value = true

    const result = await registerWithPassword(
      registerForm.username,
      registerForm.password,
      registerForm.confirmPassword
    )

    if (result?.success) {
      ElMessage.success(result.message || '注册成功')
      loginForm.username = registerForm.username
      loginForm.password = registerForm.password
      rememberPassword.value = true
      closeRegisterDialog()
      return
    }

    ElMessage.error(result?.message || '注册失败')
  } catch (error) {
    ElMessage.error(getRequestErrorMessage(error, '注册失败'))
  } finally {
    registerLoading.value = false
  }
}

const handleLogin = async () => {
  try {
    await loginFormRef.value.validate()
    loading.value = true
    const result = await loginWithPassword(loginForm.username, loginForm.password)

    if (result?.success) {
      if (rememberPassword.value) {
        saveRememberedLogin(loginForm.username, loginForm.password, true)
      } else {
        clearRememberedLogin()
      }

      ElMessage.success('登录成功')
      await router.push('/display')
      return
    }

    ElMessage.error(result?.message || '登录失败')
  } catch (error) {
    ElMessage.error(error?.details?.message || error?.message || '登录失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const remembered = getRememberedLogin()
  if (remembered?.rememberPassword) {
    loginForm.username = remembered.username || ''
    loginForm.password = remembered.password || ''
    rememberPassword.value = true
  }
})
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0c1426 0%, #1a1f3a 25%, #16213e 50%, #0f1419 75%, #000814 100%);
  position: fixed;
  inset: 0;
  overflow: hidden;
}

.background-particles {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 1;
}

.particle {
  position: absolute;
  background: rgba(64, 224, 255, 0.6);
  border-radius: 50%;
  animation: float linear infinite;
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.8);
}

@keyframes float {
  0% {
    transform: translateY(100vh) rotate(0deg);
    opacity: 0;
  }
  10% {
    opacity: 1;
  }
  90% {
    opacity: 1;
  }
  100% {
    transform: translateY(-100vh) rotate(360deg);
    opacity: 0;
  }
}

.login-card {
  position: relative;
  width: 420px;
  padding: 40px;
  border-radius: 20px;
  z-index: 10;
  overflow: hidden;
  transform: translateY(-20px);
  box-shadow:
    0 0 0 1px rgba(64, 224, 255, 0.1),
    0 8px 32px rgba(0, 0, 0, 0.3),
    0 16px 64px rgba(64, 224, 255, 0.1),
    0 32px 128px rgba(64, 224, 255, 0.05),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
  border: 1px solid transparent;
  background:
    linear-gradient(rgba(255, 255, 255, 0.05), rgba(255, 255, 255, 0.02)),
    linear-gradient(45deg, rgba(64, 224, 255, 0.1), rgba(30, 144, 255, 0.1));
  background-clip: padding-box, border-box;
  background-origin: padding-box, border-box;
  animation: cardGlow 4s ease-in-out infinite;
}

@keyframes cardGlow {
  0%,
  100% {
    box-shadow:
      0 0 0 1px rgba(64, 224, 255, 0.1),
      0 8px 32px rgba(0, 0, 0, 0.3),
      0 16px 64px rgba(64, 224, 255, 0.1),
      0 32px 128px rgba(64, 224, 255, 0.05),
      inset 0 1px 0 rgba(255, 255, 255, 0.1);
  }
  50% {
    box-shadow:
      0 0 0 1px rgba(64, 224, 255, 0.2),
      0 8px 32px rgba(0, 0, 0, 0.3),
      0 16px 64px rgba(64, 224, 255, 0.2),
      0 32px 128px rgba(64, 224, 255, 0.1),
      inset 0 1px 0 rgba(255, 255, 255, 0.15);
  }
}

.card-decoration {
  position: relative;
  height: 3px;
  margin-bottom: 30px;
}

.decoration-line {
  width: 100%;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(64, 224, 255, 0.6) 20%,
    rgba(64, 224, 255, 1) 50%,
    rgba(64, 224, 255, 0.6) 80%,
    transparent 100%
  );
  animation: pulse 3s ease-in-out infinite;
}

.corner-accent {
  position: absolute;
  top: 0;
  width: 50px;
  height: 3px;
  background: linear-gradient(45deg, #40e0ff, #1e90ff);
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.8);
}

.corner-accent.left {
  left: 0;
}

.corner-accent.right {
  right: 0;
}

.logo-section {
  text-align: center;
  margin-bottom: 40px;
}

.logo-icon {
  display: inline-block;
  margin-bottom: 20px;
}

.icon-circle {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: linear-gradient(135deg, #40e0ff, #1e90ff);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 30px rgba(64, 224, 255, 0.6), inset 0 0 20px rgba(255, 255, 255, 0.2);
  animation: logoAnimation 6s ease-in-out infinite;
}

.icon-stock {
  width: 40px;
  height: 40px;
  background: white;
  clip-path: polygon(0% 100%, 25% 0%, 50% 50%, 75% 0%, 100% 100%);
}

@keyframes logoAnimation {
  0%,
  100% {
    transform: rotate(0deg) scale(1);
  }
  25% {
    transform: rotate(90deg) scale(1.05);
  }
  50% {
    transform: rotate(180deg) scale(1);
  }
  75% {
    transform: rotate(270deg) scale(1.05);
  }
}

.system-title {
  font-size: 28px;
  font-weight: bold;
  margin: 0 0 10px 0;
  background: linear-gradient(135deg, #40e0ff, #ffffff, #1e90ff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.system-subtitle {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
  margin: 0;
  letter-spacing: 2px;
}

.form-section {
  margin-top: 30px;
}

.custom-input :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(64, 224, 255, 0.3);
  border-radius: 12px;
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.1);
  transition: all 0.3s ease;
}

.custom-input :deep(.el-input__wrapper:hover),
.custom-input :deep(.el-input__wrapper.is-focus) {
  border-color: rgba(64, 224, 255, 0.8);
  box-shadow: 0 0 20px rgba(64, 224, 255, 0.25);
}

.custom-input :deep(.el-input__inner) {
  color: white;
}

.form-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: -6px 0 22px;
  color: rgba(255, 255, 255, 0.82);
}

.form-actions :deep(.el-checkbox__label) {
  color: rgba(255, 255, 255, 0.82);
}

.register-link {
  min-width: 96px;
  height: 36px;
  padding: 0 16px;
  color: #dff7ff;
  background: linear-gradient(135deg, rgba(64, 224, 255, 0.18), rgba(30, 144, 255, 0.22));
  border: 1px solid rgba(64, 224, 255, 0.35);
  border-radius: 10px;
  box-shadow:
    0 0 12px rgba(64, 224, 255, 0.15),
    inset 0 0 12px rgba(64, 224, 255, 0.08);
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.25s ease;
}

.register-link:hover {
  color: #ffffff;
  border-color: rgba(64, 224, 255, 0.55);
  box-shadow:
    0 0 18px rgba(64, 224, 255, 0.28),
    inset 0 0 12px rgba(64, 224, 255, 0.12);
  transform: translateY(-1px);
}

.login-btn {
  width: 100%;
  background: linear-gradient(135deg, #40e0ff, #1e90ff);
  border: none;
  border-radius: 12px;
  color: white;
  font-size: 18px;
  font-weight: 600;
  padding: 15px;
  transition: all 0.3s ease;
}

.login-btn:hover {
  background: linear-gradient(135deg, #1e90ff, #40e0ff);
  transform: translateY(-3px);
  box-shadow: 0 10px 30px rgba(64, 224, 255, 0.4);
}

.login-btn:active {
  transform: translateY(-1px);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

:deep(.register-dialog .el-dialog) {
  background: linear-gradient(135deg, rgba(18, 33, 61, 0.96), rgba(18, 28, 48, 0.98));
  border: 1px solid rgba(64, 224, 255, 0.28);
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.45);
}

:deep(.register-dialog .el-dialog__title),
:deep(.register-dialog .el-form-item__label) {
  color: #e6f7ff;
}

:deep(.register-dialog .el-dialog__body) {
  padding-top: 14px;
}

:deep(.register-dialog .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(64, 224, 255, 0.25);
  box-shadow: none;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.7;
    transform: scale(1.1);
  }
}

:deep(.el-form-item) {
  margin-bottom: 24px;
}

:deep(.el-form-item__error) {
  color: #ff6b6b;
}
</style>
