<template>
  <div class="page">
    <van-nav-bar title="确认订单" left-arrow @click-left="$router.back()" />

    <template v-if="order">
      <div class="card goods">
        <span class="thumb">{{ emoji }}</span>
        <div class="info">
          <b class="clamp-2">{{ order.goodsTitle }}</b>
          <i class="price">¥{{ order.price }}</i>
        </div>
      </div>

      <van-cell-group inset class="card">
        <van-cell title="订单编号" :value="order.orderNo" />
        <van-cell title="卖家" :value="`同学 #${order.sellerId}`" />
        <van-cell title="自提点" :value="order.pickupPoint" />
        <van-cell title="下单时间" :value="order.createTime" />
      </van-cell-group>

      <van-cell-group inset class="card">
        <van-cell title="支付方式">
          <template #value>
            <van-radio-group v-model="payType" direction="horizontal">
              <van-radio v-for="p in payTypes" :key="p" :name="p">{{ p }}</van-radio>
            </van-radio-group>
          </template>
        </van-cell>
      </van-cell-group>

      <div class="tip">
        校园闲置建议「校内自提 · 当面验货后确认」，付款后请到自提点联系卖家。
      </div>

      <div class="paybar safe-bottom">
        <div class="sum">
          应付 <b class="price">¥{{ order.price }}</b>
        </div>
        <van-button round class="btn" :loading="paying" @click="pay">立即支付</van-button>
      </div>
    </template>

    <van-loading v-else class="loading" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { getOrderDetail, payOrder } from '../api'
import { emojiOf } from '../utils/goodsImage'

const route = useRoute()
const router = useRouter()
const orderNo = String(route.params.orderNo)
const order = ref(null)
const payType = ref('微信支付')
const paying = ref(false)
const payTypes = ['微信支付', '支付宝', '校园卡']

const emoji = computed(() => emojiOf(order.value || {}))

async function load() {
  try {
    order.value = await getOrderDetail(orderNo)
  } catch (e) {
    if (e.code === 416) router.replace({ path: '/login', query: { redirect: route.fullPath } })
    else showToast(e.message || '订单加载失败')
  }
}

async function pay() {
  paying.value = true
  try {
    await payOrder(orderNo, payType.value)
    router.replace({ path: '/pay/success', query: { orderNo } })
  } catch (e) {
    showToast(e.message || '支付失败')
  } finally {
    paying.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { min-height: 100vh; background: var(--bg-page); padding-bottom: 90px; }
.card { margin-top: 12px; }
.goods { display: flex; gap: 10px; align-items: center; background: var(--bg-card); border: 1px solid var(--border); border-radius: 14px; padding: 14px; }
.thumb { width: 52px; height: 52px; border-radius: 10px; background: var(--bg-page); display: flex; align-items: center; justify-content: center; font-size: 26px; flex: none; }
.info { flex: 1; min-width: 0; }
.info b { display: block; font-size: 13px; color: var(--text-1); }
.info i { font-style: normal; font-size: 16px; }
.tip { margin: 12px 16px; font-size: 11px; color: var(--text-3); line-height: 1.6; }
.paybar {
  position: fixed; bottom: 0; left: 0; right: 0; display: flex; align-items: center; gap: 12px;
  padding: 10px 14px; background: var(--bg-card); border-top: 1px solid var(--border);
}
.sum { flex: 1; font-size: 12px; color: var(--text-2); }
.sum b { font-size: 19px; }
.btn { flex: none; width: 140px; background: var(--brand); border-color: var(--brand); color: #fff; font-weight: 700; }
.loading { margin-top: 80px; }
</style>