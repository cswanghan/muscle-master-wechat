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
  <div class="shell">
    <aside class="side">
      <div class="side-brand">
        <span class="side-mark">松</span>
        <div>
          <div class="side-name">肌松大师</div>
          <div class="side-sub">运营管理后台</div>
        </div>
      </div>
      <nav class="side-nav">
        <router-link to="/">数据看板</router-link>
        <router-link to="/schedule">排班中心</router-link>
        <router-link to="/orders">订单中心</router-link>
        <router-link to="/catalog">项目 SKU</router-link>
        <router-link to="/frontdesk">前台收银</router-link>
        <router-link to="/mini">小程序预览</router-link>
        <a href="/walkthrough/index.html">流程验收</a>
        <router-link to="/health">系统管理</router-link>
      </nav>
      <div class="side-foot">
        <div class="side-user">{{ name || '未登录' }}</div>
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
    </aside>
    <div class="workspace">
      <el-alert
        v-if="loginError"
        title="登录失败"
        type="error"
        :description="loginError"
        show-icon
        :closable="false"
        style="margin: 16px 20px 0"
      />
      <router-view />
    </div>
  </div>
</template>
