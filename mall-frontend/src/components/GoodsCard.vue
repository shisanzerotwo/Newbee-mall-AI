<template>
  <div class="gcard" @click="$router.push(`/goods/${goods.id}`)">
    <div class="gimg" :style="{ height: imgHeight + 'px' }">
      <img v-if="coverUrl" :src="coverUrl" :alt="goods.title" loading="lazy" @error="coverUrl = ''">
      <span v-else class="emoji">{{ emoji }}</span>
    </div>
    <div class="gbody">
      <div class="gname clamp-2">{{ goods.title }}</div>
      <div class="tags">
        <span v-if="goods.condition" class="tag tag-brand">{{ goods.condition }}</span>
        <span v-if="goods.urgent" class="tag tag-accent">急出</span>
      </div>
      <div class="gprice price">¥{{ goods.price }}
        <s v-if="goods.originalPrice">¥{{ goods.originalPrice }}</s>
      </div>
      <div class="gseller">
        <span class="avatar">{{ goods.seller?.name?.[0] || '友' }}</span>
        <span class="sname">{{ goods.seller?.name }}</span>
        <span v-if="goods.seller?.credit" class="credit">⭐{{ goods.seller.credit }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { emojiOf, realCoverOf } from '../utils/goodsImage'
const props = defineProps({ goods: { type: Object, required: true } })
const emoji = computed(() => emojiOf(props.goods))
const coverUrl = ref(realCoverOf(props.goods))
// 演示数据用伪随机高度模拟瀑布流错落感
const imgHeight = computed(() => 110 + (Number(props.goods.id) % 3) * 28)
</script>

<style scoped>
.gcard {
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 10px; overflow: hidden; margin-bottom: 8px;
  break-inside: avoid; cursor: pointer;
  transition: box-shadow .15s;
}
.gcard:active { box-shadow: var(--shadow-card); }
.gimg {
  width: 100%; display: flex; align-items: center; justify-content: center;
  background: var(--border); overflow: hidden;
}
.gimg img { width: 100%; height: 100%; object-fit: cover; }
.emoji { font-size: 40px; opacity: .55; }
.gbody { padding: 8px 10px 10px; }
.gname { font-weight: 600; font-size: 12.5px; line-height: 1.4; color: var(--text-1); }
.tags { margin-top: 5px; display: flex; gap: 4px; flex-wrap: wrap; }
.tag { font-size: 9.5px; padding: 1px 6px; border-radius: 99px; }
.tag-brand { background: var(--brand-chip); color: var(--brand); }
.tag-accent { background: rgba(201, 143, 27, .12); color: var(--accent); }
.gprice { margin-top: 5px; font-size: 15px; }
.gprice s { color: var(--text-3); font-weight: 400; font-size: 10px; margin-left: 4px; }
.gseller { display: flex; align-items: center; gap: 4px; margin-top: 6px; color: var(--text-3); font-size: 10px; }
.avatar {
  width: 16px; height: 16px; border-radius: 50%; flex: none;
  background: var(--brand-2); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-size: 9px;
}
.sname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.credit { margin-left: auto; flex: none; }
</style>
