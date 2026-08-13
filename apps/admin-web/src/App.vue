<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { clearSession, getStaffName, getToken, staffLogin } from './api'

const token = ref('')
const name = ref('')
const loginLoading = ref(false)
const loginError = ref('')

onMounted(() => {
  token.value = getToken()
  name.value = getStaffName()
})

async function login() {
  loginLoading.value = true
  loginError.value = ''
  try {
    const data = await staffLogin('dev-staff')
    token.value = data.token
    name.value = data.name
    window.location.reload()
  } catch (e) {
    loginError.value = e instanceof Error ? e.message : String(e)
  } finally {
    loginLoading.value = false
  }
}

function logout() {
  clearSession()
  token.value = ''
  name.value = ''
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <span class="brand">肌松大师</span>
      <span class="subtitle">管理后台</span>
      <nav class="nav">
        <router-link to="/health">健康</router-link>
        <router-link to="/catalog">目录</router-link>
        <router-link to="/orders">订单</router-link>
      </nav>
      <div class="auth">
        <span v-if="token" id="staff-name" class="staff-name">{{ name || '已登录' }}</span>
        <el-button
          v-if="!token"
          id="staff-login-btn"
          type="primary"
          size="small"
          :loading="loginLoading"
          @click="login"
        >
          超管登录
        </el-button>
        <el-button v-else size="small" @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-main class="main">
      <el-alert
        v-if="loginError"
        title="登录失败"
        type="error"
        :description="loginError"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />
      <router-view />
    </el-main>
  </el-container>
</template>
