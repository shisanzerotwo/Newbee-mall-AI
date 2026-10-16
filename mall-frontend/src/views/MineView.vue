<template>
  <div class="mine">
    <div class="mehead">
      <span class="avatar font-brand">{{ info?.nickname?.[0] || '友' }}</span>
      <div>
        <b class="font-brand">{{ info?.nickname || '未登录同学' }}</b>
        <p>🎓 {{ info?.campusVerified ? '已校园认证' : '未认证' }} · 信用分 {{ info?.creditScore ?? '--' }}</p>
      </div>
    </div>

    <div class="mestat">
      <div><b>{{ s.published }}</b><span>我发布的</span></div>
      <div><b>{{ s.bought }}</b><span>我买到的</span></div>
      <div @click="$router.push('/favorites')"><b>{{ s.wanted }}</b><span>我想要</span></div>
      <div><b>{{ s.seeking }}</b><span>求购中</span></div>
    </div>

    <van-cell-group inset class="mlist">
      <van-cell title="📋 我的订单" value="待支付/待自提" is-link @click="$router.push('/orders')" />
      <van-cell title="💰 我的钱包" :value="`余额 ¥${(info?.balance ?? 0).toFixed(2)}`" is-link />
      <van-cell title="🎫 卡券与补贴" value="2 张可用" is-link />
      <van-cell title="🤍 我想要的" :value="`${s.wanted} 件`" is-link @click="$router.push('/favorites')" />
      <van-cell title="🤖 AI 智能客服" value="7×24 在线" is-link @click="$router.push('/chat/ai')" />
      <van-cell title="⭐ 我的评价" :value="`好评率 ${info?.praiseRate ?? '--'}%`" is-link />
    </van-cell-group>

    <van-cell-group inset class="mlist">
      <van-cell title="📍 常用自提点" value="本部快递服务中心" is-link />
      <van-cell title="⚙️ 设置" is-link @click="onLogout" />
    </van-cell-group>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useUserStore } from '../stores/user'
import { useRouter } from 'vue-router'
import { Dialog } from 'vant'

const user = useUserStore()
const router = useRouter()
const info = computed(() => user.info)
const s = computed(() => info.value?.stats || { published: 0, bought: 0, wanted: 0, seeking: 0 })

onMounted(() => { user.fetchInfo() })

function onLogout() {
  Dialog.confirm({ title: '退出登录', message: '确定退出当前账号吗？' })
    .then(async () => { await user.logout(); router.push('/login') })
    .catch(() => {})
}
</script>

<style scoped>
.mehead {
  background: var(--brand-grad); padding: 26px 18px 34px; color: #fff;
  display: flex; gap: 12px; align-items: center;
}
.avatar {
  width: 56px; height: 56px; border-radius: 50%;
  background: rgba(255, 255, 255, .22); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 22px;
}
.mehead b { font-size: 17px; }
.mehead p { font-size: 11px; opacity: .88; margin-top: 4px; }
.mestat {
  display: flex; background: var(--bg-card); margin: -18px 12px 0;
  border-radius: 14px; border: 1px solid var(--border); padding: 14px 0;
  position: relative; box-shadow: var(--shadow-card);
}
.mestat div { flex: 1; text-align: center; }
.mestat b { display: block; font-size: 16px; color: var(--text-1); }
.mestat span { color: var(--text-3); font-size: 10.5px; }
.mlist { margin-top: 12px; }
.mlist :deep(.van-cell) { font-size: 13px; }
</style>
