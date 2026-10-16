<template>
  <div class="cate">
    <div class="spill-wrap"><div class="spill" @click="$router.push('/search')">🔍 搜你想要的闲置</div></div>
    <div class="main">
      <aside class="side">
        <div v-for="(c, i) in list" :key="c.name" class="side-item font-brand"
             :class="{ on: i === active }" @click="active = i">{{ c.name }}</div>
      </aside>
      <section class="content">
        <div class="ctitle font-brand">{{ list[active].name }}</div>
        <div class="sub-grid">
          <div v-for="s in list[active].subs" :key="s.name" class="sub-item" @click="goSearch(s.name)">
            <span class="sico">{{ s.icon }}</span>
            <span>{{ s.name }}</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
const router = useRouter()
const active = ref(0)

const list = [
  { name: '教材书籍', subs: [{ icon: '📖', name: '考研资料' }, { icon: '📕', name: '专业教材' }, { icon: '📗', name: '四六级' }, { icon: '📘', name: '文学小说' }, { icon: '📙', name: '教资公考' }, { icon: '📓', name: '笔记本' }] },
  { name: '数码电子', subs: [{ icon: '🎧', name: '耳机音箱' }, { icon: '⌚', name: '智能穿戴' }, { icon: '💻', name: '电脑平板' }, { icon: '📱', name: '手机配件' }, { icon: '⌨️', name: '键鼠外设' }, { icon: '🔌', name: '充电设备' }] },
  { name: '生活宿舍', subs: [{ icon: '💡', name: '台灯灯具' }, { icon: '🧺', name: '收纳清洁' }, { icon: '🛏️', name: '床上用品' }, { icon: '♨️', name: '小家电' }, { icon: '🪑', name: '桌椅家具' }, { icon: '🚲', name: '代步出行' }] },
  { name: '服饰鞋包', subs: [{ icon: '🧥', name: '外套' }, { icon: '👟', name: '鞋子' }, { icon: '🎒', name: '箱包' }, { icon: '👕', name: 'T恤卫衣' }, { icon: '🧢', name: '配饰' }] },
  { name: '票券卡务', subs: [{ icon: '🎫', name: '演出票' }, { icon: '🏋️', name: '健身卡' }, { icon: '🍔', name: '餐饮券' }, { icon: '🚌', name: '交通卡' }] },
  { name: '求购专区', subs: [{ icon: '📢', name: '最新求购' }, { icon: '⏰', name: '急求' }, { icon: '✅', name: '我发布的' }] }
]

function goSearch(kw) { router.push({ path: '/search', query: { keyword: kw } }) }
</script>

<style scoped>
.cate { height: 100vh; display: flex; flex-direction: column; }
.spill-wrap { padding: 10px 12px; background: var(--bg-card); border-bottom: 1px solid var(--border); }
.spill { background: var(--bg-page); border-radius: 99px; padding: 7px 12px; color: var(--text-3); font-size: 12px; }
.main { flex: 1; display: flex; overflow: hidden; }
.side { width: 92px; flex: none; overflow-y: auto; background: var(--bg-page); }
.side-item { padding: 14px 8px; text-align: center; font-size: 12.5px; color: var(--text-2); }
.side-item.on { background: var(--bg-card); color: var(--brand); font-weight: 700; border-left: 3px solid var(--brand); }
.content { flex: 1; overflow-y: auto; padding: 14px; background: var(--bg-card); }
.ctitle { font-weight: 700; font-size: 15px; margin-bottom: 12px; }
.sub-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px 8px; }
.sub-item { text-align: center; font-size: 11px; color: var(--text-2); }
.sico {
  display: flex; align-items: center; justify-content: center; margin: 0 auto 5px;
  width: 48px; height: 48px; border-radius: 12px; font-size: 20px;
  background: var(--bg-page); border: 1px solid var(--border);
}
</style>
