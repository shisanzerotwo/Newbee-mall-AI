<template>
  <div class="chat-page">
    <div class="chead">
      <van-icon name="arrow-left" @click="$router.back()" />
      <div class="ctitle">
        <span class="font-brand">🤖 AI 智能客服</span>
        <small>在线 · 基于平台知识库回答 · 可转人工</small>
      </div>
    </div>

    <div ref="scrollRef" class="chat">
      <template v-for="m in chat.messages" :key="m.id">
        <div class="msg" :class="m.role === 'user' ? 'r' : 'l'">
          <span v-if="m.text">{{ m.text }}</span>
          <span v-else-if="chat.streaming && m.role === 'assistant'" class="typing">正在输入…</span>
          <ChatGoodsCard v-for="(c, i) in m.cards" :key="i" :card="c" />
        </div>
      </template>

      <div class="qr">
        <span v-for="q in quick" :key="q" @click="ask(q)">{{ q }}</span>
        <span class="human" @click="toHuman">转人工客服</span>
      </div>
    </div>

    <div class="inputbar safe-bottom">
      <input v-model="draft" placeholder="请输入您的问题…" @keyup.enter="ask(draft)">
      <button v-if="chat.streaming" class="send stop" @click="chat.stop">停止</button>
      <button v-else class="send" @click="ask(draft)">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Toast } from 'vant'
import { useChatStore } from '../stores/chat'
import ChatGoodsCard from '../components/ChatGoodsCard.vue'

const chat = useChatStore()
const route = useRoute()
const draft = ref('')
const scrollRef = ref(null)
const quick = ['如何发布闲置？', '交易被骗怎么办？', '帮我找 300 元以内的自行车']

const goodsId = route.query.goodsId ? Number(route.query.goodsId) : undefined

function ask(text) {
  const t = (text || '').trim()
  if (!t) return
  chat.send(t, goodsId)
  draft.value = ''
}

function toHuman() {
  Toast('人工客服工作时间为 9:00-21:00，已为您排队')
}

watch(() => chat.messages.map(m => m.text).join(''), async () => {
  await nextTick()
  scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight })
})

onMounted(() => {
  if (goodsId) chat.send('我想了解这件商品', goodsId)
})
</script>

<style scoped>
.chat-page { height: 100vh; display: flex; flex-direction: column; background: var(--bg-page); }
.chead {
  display: flex; align-items: center; gap: 10px; padding: 10px 14px;
  background: var(--bg-card); border-bottom: 1px solid var(--border);
}
.ctitle { font-weight: 700; font-size: 14px; }
.ctitle small { display: block; color: var(--text-3); font-weight: 400; font-size: 10px; }
.chat { flex: 1; overflow-y: auto; padding: 14px 12px; display: flex; flex-direction: column; gap: 10px; }
.msg { max-width: 80%; padding: 9px 12px; border-radius: 14px; font-size: 12.5px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.msg.l { align-self: flex-start; background: var(--bg-card); border: 1px solid var(--border); border-top-left-radius: 4px; }
.msg.r { align-self: flex-end; background: var(--brand); color: #fff; border-top-right-radius: 4px; }
.typing { color: var(--text-3); }
.qr { display: flex; gap: 6px; flex-wrap: wrap; }
.qr span {
  border: 1px solid var(--brand); color: var(--brand); background: var(--bg-card);
  border-radius: 99px; padding: 4px 12px; font-size: 11px; cursor: pointer;
}
.qr .human { border-style: dashed; color: var(--text-2); border-color: var(--text-3); }
.inputbar {
  display: flex; gap: 8px; padding: 8px 12px;
  background: var(--bg-card); border-top: 1px solid var(--border); align-items: center;
}
.inputbar input {
  flex: 1; border: 1px solid var(--border); background: var(--bg-page);
  border-radius: 99px; padding: 9px 14px; font-size: 12.5px; outline: none;
}
.inputbar input:focus { border-color: var(--brand); }
.send { border: 0; background: var(--brand); color: #fff; border-radius: 99px; padding: 9px 18px; font-weight: 700; font-size: 12px; }
.send.stop { background: var(--danger); }
</style>
