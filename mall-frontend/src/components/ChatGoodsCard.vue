<template>
  <div class="mini-card" @click="$router.push(`/goods/${card.goodsId}`)">
    <div class="thumb">
      <img v-if="isRealImg" :src="card.cover" alt="">
      <span v-else>{{ card.cover || '📦' }}</span>
    </div>
    <div class="info">
      <b class="clamp-2">{{ card.title }}</b>
      <i class="price">¥{{ card.price }}</i>
    </div>
    <button class="go">去看看</button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ card: { type: Object, required: true } })
const isRealImg = computed(() => /^https?:|^\//.test(props.card.cover || ''))
</script>

<style scoped>
.mini-card {
  margin-top: 8px; display: flex; align-items: center; gap: 8px;
  background: var(--bg-page); border: 1px solid var(--border);
  border-radius: 8px; padding: 8px;
}
.thumb {
  width: 38px; height: 38px; border-radius: 6px; flex: none;
  background: var(--border); display: flex; align-items: center; justify-content: center;
  font-size: 18px; overflow: hidden;
}
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.info { flex: 1; min-width: 0; }
.info b { display: block; font-size: 11px; color: var(--text-1); }
.info i { font-style: normal; font-size: 12px; }
.go {
  border: 0; background: var(--brand); color: #fff;
  border-radius: 99px; padding: 5px 12px; font-size: 10.5px; font-weight: 700;
  flex: none; cursor: pointer;
}
</style>
