<template>
  <div class="login">
    <div class="brand font-brand">
      <div class="logo">伊</div>
      <h1>伊师大校园闲置</h1>
      <p>品正学实 · 校内闲置放心淘</p>
    </div>

    <div class="form">
      <van-field v-model="form.loginName" label="账号" placeholder="请输入学号 / 手机号" />
      <van-field v-model="form.password" type="password" label="密码" placeholder="请输入密码" />
      <van-button block round class="login-btn" :loading="loading" @click="onLogin">登 录</van-button>
      <p class="tip">登录后即可发布闲置、收藏想要、与卖家沟通</p>
      <p class="demo" @click="quickDemo">暂不登录，先逛逛 →</p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Toast } from 'vant'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const form = reactive({ loginName: '', password: '' })
const loading = ref(false)

async function onLogin() {
  if (!form.loginName || !form.password) return Toast('请输入账号和密码')
  loading.value = true
  try {
    await user.login({ loginName: form.loginName.trim(), password: form.password })
    Toast('登录成功')
    router.replace(route.query.redirect || '/')
  } catch (e) {
    Toast(e.message || '登录失败，请检查账号密码')
  } finally { loading.value = false }
}

/** 游客模式：不进登录态，仅跳转浏览（受限操作会再引导登录） */
function quickDemo() {
  router.replace(route.query.redirect || '/')
}
</script>

<style scoped>
.login { min-height: 100vh; background: var(--bg-page); padding: 60px 28px 0; }
.brand { text-align: center; margin-bottom: 36px; }
.logo {
  width: 72px; height: 72px; margin: 0 auto 14px; border-radius: 50%;
  background: var(--brand-grad); color: #fff; font-size: 34px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: var(--shadow-card);
}
.brand h1 { font-size: 22px; color: var(--brand); }
.brand p { font-size: 12px; color: var(--text-3); margin-top: 6px; }
.form { background: var(--bg-card); border: 1px solid var(--border); border-radius: 14px; padding: 18px 14px; }
.form :deep(.van-field) { margin-bottom: 10px; background: var(--bg-page); border-radius: 8px; }
.login-btn { margin-top: 8px; background: var(--brand); border-color: var(--brand); color: #fff; font-weight: 700; }
.tip { text-align: center; font-size: 10.5px; color: var(--text-3); margin-top: 12px; }
.demo { text-align: center; font-size: 12px; color: var(--brand); margin-top: 8px; font-weight: 700; }
</style>
