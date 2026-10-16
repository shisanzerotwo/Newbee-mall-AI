<template>
  <div class="search">
    <div class="shead">
      <van-icon name="arrow-left" @click="$router.back()" />
      <van-search v-model="kw" placeholder="搜你想要的闲置" autofocus @search="doSearch" />
    </div>

    <template v-if="!searched">
      <div class="block" v-if="goods.history.length">
        <div class="btitle font-brand">搜索历史 <em @click="clearHistory">清空</em></div>
        <div class="kws"><span v-for="h in goods.history" :key="h" @click="kw = h; doSearch()">{{ h }}</span></div>
      </div>
      <div class="block">
        <div class="btitle font-brand">热门搜索</div>
        <div class="kws"><span v-for="h in hot" :key="h" class="hot" @click="kw = h; doSearch()">{{ h }}</span></div>
      </div>
    </template>

    <template v-else>
      <van-list v-model:loading="goods.loading" :finished="goods.finished" finished-text="没有更多了" @load="goods.loadMore">
        <div class="wf">
          <GoodsCard v-for="g in goods.list" :key="g.id" :goods="g" />
        </div>
      </van-list>
      <van-empty v-if="goods.finished && !goods.list.length" description="没有找到相关闲置，试试发布求购" />
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useGoodsStore } from '../stores/goods'
import GoodsCard from '../components/GoodsCard.vue'

const route = useRoute()
const goods = useGoodsStore()
const kw = ref('')
const searched = ref(false)
const hot = ['自行车', '考研资料', '台灯', '耳机', '洗衣机', 'iPad']

function doSearch() {
  if (!kw.value.trim()) return
  searched.value = true
  goods.addHistory(kw.value.trim())
  goods.reset(kw.value.trim())
  goods.loadMore()
}

function clearHistory() {
  goods.history = []
  localStorage.removeItem('search_history')
}

onMounted(() => {
  if (route.query.keyword) { kw.value = String(route.query.keyword); doSearch() }
})
</script>

<style scoped>
.shead { display: flex; align-items: center; padding: 4px 8px; background: var(--bg-card); border-bottom: 1px solid var(--border); }
.shead :deep(.van-search) { flex: 1; padding: 4px 0; background: transparent; }
.block { padding: 14px; }
.btitle { font-weight: 700; font-size: 14px; margin-bottom: 10px; display: flex; justify-content: space-between; }
.btitle em { font-style: normal; color: var(--text-3); font-weight: 400; font-size: 11px; }
.kws { display: flex; gap: 8px; flex-wrap: wrap; }
.kws span {
  background: var(--bg-card); border: 1px solid var(--border); color: var(--text-2);
  border-radius: 99px; padding: 5px 14px; font-size: 12px;
}
.kws .hot { color: var(--brand); border-color: var(--brand-chip); background: var(--brand-chip); }
.wf { column-count: 2; column-gap: 8px; padding: 12px; }
</style>
