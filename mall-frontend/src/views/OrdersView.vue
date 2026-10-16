<template>
  <div class="page">
    <van-nav-bar title="我的订单" left-arrow @click="$router.back()" />
    <van-tabs v-model:active="tab" sticky>
      <van-tab title="全部" />
      <van-tab title="待支付" />
      <van-tab title="待自提" />
      <van-tab title="已完成" />
    </van-tabs>

    <van-loading v-if="loading" class="loading" />
    <van-empty v-else-if="!filtered.length" description="暂无相关订单" />

    <div v-for="o in filtered" :key="o.orderNo" class="order">
      <div class="ohead">
        <span class="ono">订单 {{ o.orderNo }}</span>
        <span class="ost" :class="'st' + o.status">{{ o.statusText }}</span>
      </div>
      <div class="obody" @click="$router.push(`/goods/${o.goodsId}`)">
        <b class="clamp-2">{{ o.goodsTitle }}</b>
        <span class="price">¥{{ o.price }}</span>
      </div>
      <div class="oacts">
        <van-button size="small" round plain class="ob" @click="chatSeller(o)">联系卖家</van-button>
        <van-button v-if="o.status === 0" size="small" round plain class="ob" @click="cancel(o)">取消订单</van-button>
        <van-button v-if="o.status === 0" size="small" round class="ob primary" @click="$router.push(`/pay/${o.orderNo}`)">去支付</van-button>
        <van-button v-if="o.status === 1" size="small" round class="ob primary" @click="confirm(o)">确认完成</van-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { getMyOrders, cancelOrder, confirmOrder, buildConversationId } from '../api'
import { useUserStore } from '../stores/user'

const router = useRouter()
const user = useUserStore()
const orders = ref([])
const loading = ref(true)
const tab = ref(0)

const filtered = computed(() => {
  if (tab.value === 1) return orders.value.filter(o => o.status === 0)
  if (tab.value === 2) return orders.value.filter(o => o.status === 1)
  if (tab.value === 3) return orders.value.filter(o => o.status === 2)
  return orders.value
})

async function load() {
  try {
    orders.value = await getMyOrders()
  } catch (e) {
    if (e.code === 416) router.replace({ path: '/login', query: { redirect: '/orders' } })
  } finally {
    loading.value = false
  }
}

function chatSeller(o) {
  const myId = user.info?.userId
  if (!myId) { router.push({ path: '/login', query: { redirect: '/orders' } }); return }
  const conv = buildConversationId(myId, o.sellerId, o.goodsId)
  router.push(`/chat/user/${o.sellerId}/${o.goodsId}/${encodeURIComponent(conv)}`)
}

async function cancel(o) {
  try { await cancelOrder(o.orderNo); showToast('订单已取消'); await load() }
  catch (e) { showToast(e.message || '取消失败') }
}

async function confirm(o) {
  try { await confirmOrder(o.orderNo); showToast('交易已完成'); await load() }
  catch (e) { showToast(e.message || '操作失败') }
}

onMounted(async () => { await user.fetchInfo(); await load() })
</script>

<style scoped>
.page { min-height: 100vh; background: var(--bg-page); }
.loading { margin-top: 60px; }
.order { background: var(--bg-card); border: 1px solid var(--border); border-radius: 14px; margin: 12px; overflow: hidden; }
.ohead { display: flex; justify-content: space-between; padding: 10px 14px; border-bottom: 1px solid var(--border); font-size: 11px; color: var(--text-3); }
.ost { font-weight: 700; }
.st0 { color: var(--accent); }
.st1 { color: var(--brand); }
.st2 { color: var(--success); }
.st3 { color: var(--text-3); }
.obody { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 14px; }
.obody b { font-size: 13px; color: var(--text-1); flex: 1; }
.oacts { display: flex; gap: 8px; justify-content: flex-end; padding: 0 14px 12px; }
.ob.primary { background: var(--brand); border-color: var(--brand); color: #fff; }
</style>