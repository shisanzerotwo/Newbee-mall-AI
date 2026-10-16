<template>
  <van-tabbar v-model="active" route fixed placeholder safe-area-inset-bottom class="app-tabbar">
    <van-tabbar-item to="/" icon="wap-home-o">首页</van-tabbar-item>
    <van-tabbar-item to="/category" icon="apps-o">分类</van-tabbar-item>
    <van-tabbar-item to="/publish" class="fab-item">
      <template #icon>
        <span class="fab">＋</span>
      </template>
      发布
    </van-tabbar-item>
    <van-tabbar-item to="/messages" icon="chat-o" :badge="chat.unread || ''">消息</van-tabbar-item>
    <van-tabbar-item to="/mine" icon="user-o">我的</van-tabbar-item>
  </van-tabbar>
</template>

<script setup>
import { ref } from 'vue'
import { useChatStore } from '../stores/chat'
const chat = useChatStore()
const active = ref(0)
</script>

<style scoped>
.app-tabbar :deep(.van-tabbar-item--active) { color: var(--brand); }
.fab {
  display: inline-flex; align-items: center; justify-content: center;
  width: 44px; height: 44px; border-radius: 50%;
  background: var(--brand); color: #fff; font-size: 24px; font-weight: 300;
  margin-top: -22px; box-shadow: 0 5px 12px rgba(10, 92, 70, .35);
  border: 3px solid #fff;
}
</style>
