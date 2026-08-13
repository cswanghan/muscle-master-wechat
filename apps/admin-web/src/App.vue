<script setup lang="ts">
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <span class="brand">肌松大师</span>
      <span class="subtitle">管理后台</span>
      <nav class="nav">
        <router-link to="/health">健康</router-link>
        <router-link to="/catalog">目录</router-link>
      </nav>
    </el-header>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>
