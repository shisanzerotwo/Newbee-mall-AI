<template>
  <div class="page">
    <div class="result">
      <div class="icon" :class="failed ? 'fail' : 'ok'">{{ failed ? '✕' : '✓' }}</div>
      <h2 class="font-brand">{{ failed ? '支付未完成' : '支付成功' }}</h2>
      <p v-if="order">订单 {{ order.orderNo }}<br>金额 ¥{{ order.price }} · {{ order.payType || '—' }}</p>
      <p v-if="order && order.status === 1" class="next">请到「{{ order.pickupPoint }}」联系卖家当面验货</p>

      <div class="acts">
        <van-button round plain class="btn" @click="$router.replace('/mine')">查看订单</van-button>
        <van-button round class="btn primary" @click="afterConfirm">确认完成交易</van-button>
      </div>
      <van-button round plain class="btn home" @click="$router.replace('/')">回首页逛逛</van-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { getOrderDetail, confirmOrder } from '../api'

const route = useRoute()
const router = useRouter()
const orderNo = String(route.query.orderNo || '')
const order = ref(null)
const failed = ref(false)

async function load() {
  if (!orderNo) { failed.value = true; return }
  try {
    order.value = await getOrderDetail(orderNo)
  } catch {
    failed.value = true
  }
}

async function afterConfirm() {
  try {
    await confirmOrder(orderNo)
    showToast('交易已完成')
    await load()
  } catch (e) {
    showToast(e.message || '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page { min-height: 100vh; background: var(--bg-page); display: flex; align-items: center; justify-content: center; }
.result { text-align: center; padding: 32px 24px; }
.icon {
  width: 68px; height: 68px; border-radius: 50%; margin: 0 auto 18px;
  display: flex; align-items: center; justify-content: center; font-size: 34px; color: #fff;
}
.icon.ok { background: var(--success); }
.icon.fail { background: var(--danger); }
h2 { font-size: 19px; color: var(--text-1); margin-bottom: 10px; }
p { font-size: 12.5px; color: var(--text-2); line-height: 1.8; }
.next { margin-top: 10px; color: var(--brand); font-weight: 600; }
.acts { display: flex; gap: 12px; justify-content: center; margin: 24px 0 12px; }
.btn { flex: 1; max-width: 150px; }
.btn.primary { background: var(--brand); border-color: var(--brand); color: #fff; }
.home { margin-top: 8px; }
</style>