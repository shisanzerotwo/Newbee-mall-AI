<template>
  <div class="chat-page">
    <div class="chead">
      <van-icon name="arrow-left" @click="$router.back()" />
      <div class="ctitle">
        <span class="font-brand">{{ peerName }}</span>
        <small v-if="goods">正在沟通：{{ goods.title }}</small>
      </div>
    </div>

    <!-- 关联商品卡片（点击回详情） -->
    <div v-if="goods" class="gbar" @click="$router.push(`/goods/${goods.id}`)">
      <span class="gthumb">{{ emoji }}</span>
      <div class="ginfo">
        <b class="clamp-2">{{ goods.title }}</b>
        <i class="price">¥{{ goods.price }}</i>
      </div>
      <van-icon name="arrow" />
    </div>

    <div ref="scrollRef" class="chat">
      <template v-for="m in messages" :key="m.id">
        <div class="msg" :class="m.senderId === myId ? 'r' : 'l'">
          <span>{{ m.content }}</span>
          <em class="t">{{ m.createTime }}</em>
        </div>
      </template>
      <van-empty v-if="!messages.length && !loading" description="还没有消息，发一句开始沟通吧" />
    </div>

    <div class="inputbar safe-bottom">
      <input v-model="draft" placeholder="输入消息…" @keyup.enter="send" :disabled="!myId">
      <button class="send" :disabled="sending || !draft.trim()" @click="send">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { getMessages, sendMessage, markRead, getGoodsDetail } from '../api'
import { useUserStore } from '../stores/user'
import { emojiOf } from '../utils/goodsImage'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const peerId = Number(route.params.peerId)
const goodsId = Number(route.params.goodsId) || null
const conversationId = decodeURIComponent(String(route.params.conversationId))

const messages = ref([])
const draft = ref('')
const sending = ref(false)
const loading = ref(true)
const goods = ref(null)
const scrollRef = ref(null)
let timer = null

const myId = computed(() => user.info?.userId ?? null)
const peerName = computed(() => `同学 #${peerId}`)
const emoji = computed(() => goods.value ? emojiOf(goods.value) : '📦')

async function scrollBottom() {
  await new Promise(r => setTimeout(r, 30))
  scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight })
}

async function load() {
  try {
    messages.value = await getMessages(conversationId)
    markRead(conversationId).catch(() => {})
    scrollBottom()
  } catch (e) {
    if (e.code === 416) router.replace({ path: '/login', query: { redirect: route.fullPath } })
  } finally {
    loading.value = false
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || sending.value || !myId.value) return
  sending.value = true
  try {
    await sendMessage({ peerId, goodsId, content: text })
    draft.value = ''
    await load()
  } catch (e) {
    showToast(e.message || '发送失败')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  await user.fetchInfo()
  await load()
  if (goodsId) {
    getGoodsDetail(goodsId).then(g => goods.value = g).catch(() => {})
  }
  timer = setInterval(load, 4000)   // 一期短轮询
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.chat-page { height: 100vh; display: flex; flex-direction: column; background: var(--bg-page); }
.chead { display: flex; align-items: center; gap: 10px; padding: 10px 14px; background: var(--bg-card); border-bottom: 1px solid var(--border); }
.ctitle { font-weight: 700; font-size: 14px; }
.ctitle small { display: block; color: var(--text-3); font-weight: 400; font-size: 10px; }
.gbar {
  display: flex; align-items: center; gap: 10px; padding: 10px 14px;
  background: var(--brand-chip); border-bottom: 1px solid var(--border); cursor: pointer;
}
.gthumb { width: 40px; height: 40px; border-radius: 8px; background: #fff; display: flex; align-items: center; justify-content: center; font-size: 20px; flex: none; }
.ginfo { flex: 1; min-width: 0; }
.ginfo b { display: block; font-size: 11.5px; color: var(--text-1); }
.ginfo i { font-style: normal; font-size: 13px; }
.chat { flex: 1; overflow-y: auto; padding: 14px 12px; display: flex; flex-direction: column; gap: 10px; }
.msg { max-width: 78%; padding: 8px 12px; border-radius: 14px; font-size: 12.5px; line-height: 1.55; word-break: break-word; }
.msg.l { align-self: flex-start; background: var(--bg-card); border: 1px solid var(--border); border-top-left-radius: 4px; }
.msg.r { align-self: flex-end; background: var(--brand); color: #fff; border-top-right-radius: 4px; }
.msg .t { display: block; font-size: 9px; opacity: .6; margin-top: 3px; font-style: normal; }
.inputbar { display: flex; gap: 8px; padding: 8px 12px; background: var(--bg-card); border-top: 1px solid var(--border); align-items: center; }
.inputbar input { flex: 1; border: 1px solid var(--border); background: var(--bg-page); border-radius: 99px; padding: 9px 14px; font-size: 12.5px; outline: none; }
.inputbar input:focus { border-color: var(--brand); }
.send { border: 0; background: var(--brand); color: #fff; border-radius: 99px; padding: 9px 18px; font-weight: 700; font-size: 12px; }
.send:disabled { opacity: .5; }
</style>