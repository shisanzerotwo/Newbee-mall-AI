<template>
  <div class="fav">
    <div class="phead">
      <van-icon name="arrow-left" @click="$router.back()" />
      <span class="font-brand">我想要的</span>
      <em>{{ list.length }} 件</em>
    </div>

    <van-loading v-if="loading" class="loading" />
    <van-empty v-else-if="!list.length" description="还没有想要的闲置，去首页逛逛吧">
      <van-button round type="primary" class="go" @click="$router.push('/')">去逛逛</van-button>
    </van-empty>

    <div v-else class="wf">
      <GoodsCard v-for="g in list" :key="g.id" :goods="g" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Toast } from 'vant'
import { getMyWants } from '../api'
import GoodsCard from '../components/GoodsCard.vue'

const router = useRouter()
const list = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    list.value = await getMyWants()
  } catch (e) {
    if (e.code === 416) {
      Toast('请先登录')
      router.replace({ path: '/login', query: { redirect: '/favorites' } })
    }
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.fav { min-height: 100vh; background: var(--bg-page); }
.phead {
  display: flex; align-items: center; gap: 10px; padding: 12px 14px;
  background: var(--bg-card); border-bottom: 1px solid var(--border); font-weight: 700; font-size: 16px;
}
.phead em { margin-left: auto; font-style: normal; color: var(--text-3); font-weight: 400; font-size: 12px; }
.loading { margin-top: 60px; }
.go { background: var(--brand); border-color: var(--brand); }
.wf { column-count: 2; column-gap: 8px; padding: 12px; }
</style>
