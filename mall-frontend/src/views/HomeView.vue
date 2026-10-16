<template>
  <div class="home">
    <div class="nav">
      <span class="loc font-brand">📍 伊师大本部</span>
      <div class="spill" @click="$router.push('/search')">🔍 搜“自行车 / 考研资料”</div>
    </div>

    <div class="banner font-brand">
      品正学实 · 校园闲置市集
      <small>校内自提 · 免运费 · 平台担保</small>
    </div>

    <van-grid :column-num="6" :border="false" class="cats">
      <van-grid-item v-for="c in cats" :key="c.name" :to="`/category?c=${c.name}`">
        <template #icon><span class="cico">{{ c.icon }}</span></template>
        <template #text><span class="cname">{{ c.name }}</span></template>
      </van-grid-item>
    </van-grid>

    <div class="sec">
      <span class="font-brand">为你推荐</span>
      <em>查看更多 ›</em>
    </div>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <van-list v-model:loading="goods.loading" :finished="goods.finished" finished-text="到底啦，去发布一件闲置吧" @load="goods.loadMore">
        <div class="wf">
          <GoodsCard v-for="g in goods.list" :key="g.id" :goods="g" />
        </div>
      </van-list>
    </van-pull-refresh>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useGoodsStore } from '../stores/goods'
import GoodsCard from '../components/GoodsCard.vue'

const goods = useGoodsStore()
const refreshing = ref(false)

const cats = [
  { icon: '📚', name: '教材书籍' }, { icon: '💻', name: '数码电子' },
  { icon: '🛏️', name: '生活宿舍' }, { icon: '👟', name: '服饰鞋包' },
  { icon: '🎫', name: '票券卡务' }, { icon: '📢', name: '求购专区' }
]

onMounted(() => { if (!goods.list.length) goods.loadMore() })

async function onRefresh() {
  goods.reset()
  await goods.loadMore()
  refreshing.value = false
}
</script>

<style scoped>
.home { padding-bottom: 8px; }
.nav { padding: 10px 12px; display: flex; align-items: center; gap: 8px; background: var(--bg-page); }
.loc { font-weight: 700; white-space: nowrap; color: var(--text-1); }
.spill {
  flex: 1; background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 99px; padding: 7px 12px; color: var(--text-3); font-size: 12px;
}
.banner {
  margin: 4px 12px 8px; border-radius: 12px; padding: 16px 14px;
  color: #fff; font-weight: 700; font-size: 16px; background: var(--brand-grad);
}
.banner small { display: block; font-weight: 400; font-size: 11px; opacity: .85; margin-top: 3px; }
.cats { background: transparent; }
.cats :deep(.van-grid-item__content) { background: transparent; padding: 8px 2px; }
.cico {
  display: flex; align-items: center; justify-content: center;
  width: 42px; height: 42px; border-radius: 12px; font-size: 18px;
  background: var(--bg-card); border: 1px solid var(--border);
}
.cname { font-size: 10px; color: var(--text-2); margin-top: 4px; }
.sec {
  padding: 10px 14px 8px; display: flex; justify-content: space-between; align-items: baseline;
  font-weight: 700; font-size: 15px; color: var(--text-1);
}
.sec em { font-style: normal; color: var(--text-3); font-weight: 400; font-size: 11px; }
.wf { column-count: 2; column-gap: 8px; padding: 0 12px; }
</style>
