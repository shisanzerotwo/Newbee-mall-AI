<template>
  <div class="msg">
    <div class="phead">
      <span class="font-brand">消息</span>
      <em @click="readAll">全部已读</em>
    </div>

    <!-- AI 智能客服置顶（核心能力，保留） -->
    <div class="mitem" @click="$router.push('/chat/ai')">
      <span class="avatar ai">🤖</span>
      <div class="mbody">
        <b>AI 智能客服 <span class="official">官方</span></b>
        <p>您好，我是校园交易小助手，买卖问题都可以问我~</p>
      </div>
      <div class="mtime">在线</div>
    </div>

    <template v-if="!loading">
      <div v-for="s in sessions" :key="s.conversationId" class="mitem" @click="openChat(s)">
        <span class="avatar">{{ (s.peerName || '同')[0] }}</span>
        <div class="mbody">
          <b>{{ s.peerName }}
            <span v-if="s.goodsTitle" class="goods-chip">{{ s.goodsTitle }}</span>
          </b>
          <p>{{ s.lastContent || '（暂无消息）' }}</p>
        </div>
        <div class="mtime">{{ s.lastTime }}<br>
          <span v-if="s.unread" class="badge">{{ s.unread }}</span>
        </div>
      </div>
      <van-empty v-if="!sessions.length" description="还没有聊天，去商品详情页点「私聊」试试" />
    </template>
    <van-loading v-else class="loading" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { getSessions, markRead } from '../api'

const router = useRouter()
const sessions = ref([])
const loading = ref(true)
let timer = null

async function load() {
  try {
    const data = await getSessions()
    sessions.value = data.list || []
  } catch (e) {
    if (e.code === 416) router.replace({ path: '/login', query: { redirect: '/messages' } })
  } finally {
    loading.value = false
  }
}

function openChat(s) {
  router.push({
    path: `/chat/user/${s.peerId}/${s.goodsId ?? 0}/${encodeURIComponent(s.conversationId)}`
  })
}

async function readAll() {
  for (const s of sessions.value) {
    if (s.unread) {
      try { await markRead(s.conversationId); s.unread = 0 } catch { /* 忽略单条失败 */ }
    }
  }
  showToast('已全部标记为已读')
}

onMounted(() => {
  load()
  timer = setInterval(load, 5000)   // 一期短轮询；二期改 WebSocket 推送
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.phead { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; font-weight: 700; font-size: 16px; background: var(--bg-card); }
.phead em { font-style: normal; color: var(--text-3); font-weight: 400; font-size: 12px; }
.mitem {
  display: flex; gap: 10px; padding: 12px 14px; align-items: center;
  background: var(--bg-card); border-bottom: 1px solid var(--border);
}
.avatar {
  width: 42px; height: 42px; border-radius: 50%; flex: none;
  background: var(--brand-2); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 15px;
}
.avatar.ai { background: var(--brand-grad); font-size: 19px; }
.mbody { flex: 1; min-width: 0; }
.mbody b { font-size: 13px; color: var(--text-1); display: flex; align-items: center; gap: 6px; }
.goods-chip {
  font-size: 9.5px; background: var(--brand-chip); color: var(--brand);
  border-radius: 99px; padding: 1px 6px; font-weight: 400;
  max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mbody p { color: var(--text-3); font-size: 11px; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.official { font-size: 9px; background: var(--brand-chip); color: var(--brand); border-radius: 99px; padding: 1px 6px; font-weight: 400; }
.mtime { color: var(--text-3); font-size: 10px; text-align: right; flex: none; }
.badge { background: var(--accent); color: #fff; border-radius: 99px; font-size: 9px; padding: 1px 7px; display: inline-block; margin-top: 4px; }
.loading { margin-top: 50px; }
</style>