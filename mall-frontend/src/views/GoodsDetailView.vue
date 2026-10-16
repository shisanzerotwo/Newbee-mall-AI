<template>
  <div class="detail" v-if="g">
    <div class="dimg">
      <img v-if="coverUrl" :src="coverUrl" :alt="g.title" @error="coverUrl = ''">
      <span v-else>{{ emoji }}</span>
      <van-icon name="arrow-left" class="back" @click="$router.back()" />
    </div>

    <div class="dcard">
      <div class="dprice price">¥{{ g.price }}
        <s v-if="g.originalPrice">¥{{ g.originalPrice }}</s>
        <span class="tag-guarantee">平台担保</span>
      </div>
      <div class="dname">{{ g.title }}</div>
      <div class="dmeta">
        <span>成色 {{ g.condition }}</span><span>浏览 {{ g.views }}</span>
      </div>
    </div>

    <div class="seller">
      <span class="avatar">{{ g.seller?.name?.[0] }}</span>
      <div class="sinfo">
        <b>{{ g.seller?.name }} ⭐{{ g.seller?.credit }}</b>
        <p>🎓 已校园认证 · 伊犁师范大学 · 在售 6 件</p>
      </div>
      <span class="sbtn">进店逛逛</span>
    </div>

    <div class="dsec font-brand">商品描述</div>
    <div class="ddesc">{{ g.desc || '卖家很懒，还没有填写描述，可私聊询问细节。' }}</div>

    <div class="dact safe-bottom">
      <div class="ic" :class="{ on: wanted }" @click="want">🤍<span>想要</span></div>
      <div class="ic" @click="chat">💬<span>私聊</span></div>
      <van-button round class="btn-s" @click="askAi">问 AI 客服</van-button>
      <van-button round class="btn-p" :loading="buying" @click="buy">立即购买</van-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Toast } from 'vant'
import { getGoodsDetail, toggleWant, buildConversationId, createOrder } from '../api'
import { useGoodsStore } from '../stores/goods'
import { useUserStore } from '../stores/user'
import { emojiOf, realCoverOf } from '../utils/goodsImage'

const route = useRoute()
const router = useRouter()
const store = useGoodsStore()
const user = useUserStore()
const g = ref(null)
const wanted = ref(false)
const buying = ref(false)
const coverUrl = ref('')
const emoji = ref('📦')

onMounted(async () => {
  const id = Number(route.params.id)
  try {
    g.value = await getGoodsDetail(id)
  } catch {
    g.value = store.list.find(x => x.id === id) || {
      id, title: '九成新山地自行车 可校内试骑', price: 260, originalPrice: 899,
      cover: '🚲', condition: '九成新', views: 342, seller: { name: '王同学', credit: 4.9 },
      desc: '大三购入，平时代步用，保养良好。附赠车锁和打气筒，校内自提，支持当面验货。'
    }
  }
  coverUrl.value = realCoverOf(g.value)
  emoji.value = emojiOf(g.value)
})

function want() {
  toggleWant(g.value.id)
    .then(r => {
      wanted.value = r.wanted
      Toast(r.wanted ? '已加入「我想要」' : '已取消想要')
    })
    .catch(err => {
      if (err.code === 416) router.push({ path: '/login', query: { redirect: route.fullPath } })
    })
}
function chat() {
  if (!user.isLogin) {
    Toast('请先登录后再与卖家沟通')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  const sellerId = g.value?.sellerId
  if (!sellerId) {
    Toast('该商品为演示数据，暂不支持私聊')
    return
  }
  const conv = buildConversationId(user.info.userId, sellerId, g.value.id)
  router.push(`/chat/user/${sellerId}/${g.value.id}/${encodeURIComponent(conv)}`)
}
function askAi() { router.push({ path: '/chat/ai', query: { goodsId: g.value.id } }) }
async function buy() {
  if (!user.isLogin) {
    Toast('请先登录后再购买')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  buying.value = true
  try {
    const order = await createOrder(g.value.id)
    router.push(`/pay/${order.orderNo}`)
  } catch (e) {
    Toast(e.message || '下单失败')
  } finally {
    buying.value = false
  }
}
</script>

<style scoped>
.detail { min-height: 100vh; background: var(--bg-page); padding-bottom: 70px; }
.dimg {
  height: 300px; background: var(--border); position: relative;
  display: flex; align-items: center; justify-content: center; font-size: 72px; color: var(--text-3);
}
.dimg img { width: 100%; height: 100%; object-fit: cover; }
.back { position: absolute; top: 14px; left: 14px; background: rgba(255,255,255,.85); border-radius: 50%; padding: 6px; }
.dcard {
  background: var(--bg-card); margin: -16px 12px 0; border-radius: 14px;
  border: 1px solid var(--border); padding: 14px; position: relative;
}
.dprice { font-size: 24px; }
.dprice s { color: var(--text-3); font-weight: 400; font-size: 12px; margin-left: 6px; }
.tag-guarantee {
  font-size: 10px; font-weight: 400; background: rgba(201,143,27,.12); color: var(--accent);
  border-radius: 99px; padding: 2px 8px; vertical-align: 4px; margin-left: 8px;
}
.dname { font-weight: 700; font-size: 15px; margin-top: 8px; line-height: 1.5; }
.dmeta { color: var(--text-3); font-size: 11px; margin-top: 8px; display: flex; gap: 12px; }
.seller {
  display: flex; align-items: center; gap: 10px; margin: 10px 12px; padding: 12px;
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 14px;
}
.avatar {
  width: 40px; height: 40px; border-radius: 50%; background: var(--brand-2); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 15px; flex: none;
}
.sinfo { flex: 1; }
.sinfo b { font-size: 13px; }
.sinfo p { color: var(--text-3); font-size: 10.5px; margin-top: 2px; }
.sbtn { background: var(--brand-chip); color: var(--brand); border-radius: 99px; padding: 5px 12px; font-weight: 700; font-size: 11px; }
.dsec { padding: 10px 14px 6px; font-weight: 700; font-size: 14px; }
.ddesc { padding: 0 14px 14px; color: var(--text-2); font-size: 12.5px; line-height: 1.7; }
.dact {
  position: fixed; bottom: 0; left: 0; right: 0; display: flex; gap: 10px; align-items: center;
  padding: 10px 12px; background: var(--bg-card); border-top: 1px solid var(--border);
}
.ic { text-align: center; color: var(--text-2); font-size: 17px; width: 40px; flex: none; }
.ic.on { color: var(--accent); font-weight: 700; }
.ic span { display: block; font-size: 9px; margin-top: 1px; }
.btn-s { flex: 1; background: var(--brand-chip); color: var(--brand); border: 1px solid var(--brand-chip); font-weight: 700; }
.btn-p { flex: 1; background: var(--brand); color: #fff; border: 1px solid var(--brand); font-weight: 700; }
</style>
