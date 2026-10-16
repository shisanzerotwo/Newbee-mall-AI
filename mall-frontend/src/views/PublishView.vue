<template>
  <div class="pub">
    <div class="phead"><span class="font-brand">发布闲置</span><em @click="saveDraft">存草稿</em></div>

    <van-uploader v-model="photos" :max-count="9" class="up" upload-text="拍照/相册" />

    <div class="field">
      <label>标题
        <button class="ai-btn" :disabled="aiLoading" @click="genTitle">✨ AI 生成</button>
      </label>
      <van-field v-model="form.title" placeholder="一句话描述你的宝贝" :border="false" />
    </div>

    <div class="field">
      <label>分类</label>
      <div class="chips">
        <span v-for="c in categories" :key="c" class="chip" :class="{ on: form.category === c }" @click="form.category = c">{{ c }}</span>
      </div>
    </div>

    <div class="field">
      <label>成色</label>
      <div class="chips">
        <span v-for="c in conditions" :key="c" class="chip" :class="{ on: form.condition === c }" @click="form.condition = c">{{ c }}</span>
      </div>
    </div>

    <div class="field">
      <label>价格
        <button class="ai-btn" :disabled="aiLoading" @click="estimate">✨ AI 估价</button>
        <em v-if="estRange" class="est">参考价 {{ estRange }}</em>
      </label>
      <van-field v-model="form.price" type="number" placeholder="¥ 0.00" :border="false" />
    </div>

    <div class="field">
      <label>交易方式</label>
      <div class="chips">
        <span v-for="t in tradeTypes" :key="t" class="chip" :class="{ on: form.tradeType === t }" @click="form.tradeType = t">{{ t }}</span>
      </div>
    </div>

    <div class="field">
      <label>描述</label>
      <van-field v-model="form.desc" type="textarea" rows="3" autosize
                 placeholder="品牌、购入时间、使用情况、出手原因…" :border="false" />
    </div>

    <van-button block round class="pubbtn" :loading="submitting" @click="submit">立即发布</van-button>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { Toast } from 'vant'
import { aiEstimate, aiTitle, publishGoods } from '../api'
import { useRouter } from 'vue-router'

const router = useRouter()
const photos = ref([])
const categories = ['教材书籍', '数码电子', '生活宿舍', '服饰鞋包', '票券卡务', '运动出行']
const conditions = ['全新', '几乎全新', '九成新', '八成新', '轻微使用', '明显痕迹']
const tradeTypes = ['校内自提', '送货上门', '邮寄']

const form = reactive({ title: '', category: '', condition: '九成新', price: '', tradeType: '校内自提', desc: '' })
const estRange = ref('')
const aiLoading = ref(false)
const submitting = ref(false)

async function genTitle() {
  aiLoading.value = true
  try {
    const r = await aiTitle({ category: form.category, images: photos.value.length })
    form.title = r.title
    if (r.desc) form.desc = r.desc
  } catch {
    // AI 接口未就绪：本地兜底文案
    form.title = form.title || `${form.condition}${form.category || '闲置'}，校内出`
    Toast('AI 服务未就绪，已生成基础标题')
  } finally { aiLoading.value = false }
}

async function estimate() {
  aiLoading.value = true
  try {
    const r = await aiEstimate({ category: form.category, condition: form.condition, title: form.title })
    estRange.value = `¥${r.min} ~ ¥${r.max}`
  } catch {
    estRange.value = '¥20 ~ ¥100'
    Toast('AI 服务未就绪，展示经验估价')
  } finally { aiLoading.value = false }
}

async function submit() {
  if (!form.title || !form.price || !form.category) return Toast('请补全标题、分类和价格')
  submitting.value = true
  try {
    const r = await publishGoods({
      title: form.title,
      desc: form.desc,
      category: form.category,
      condition: form.condition,
      price: Number(form.price),
      tradeType: form.tradeType,
      urgent: false
    })
    Toast('发布成功，已上架')
    router.push(`/goods/${r.goodsId}`)
  } catch (e) {
    if (e.code === 416) {
      Toast('请先登录后再发布')
      router.push({ path: '/login', query: { redirect: '/publish' } })
    }
  } finally { submitting.value = false }
}

function saveDraft() {
  localStorage.setItem('publish_draft', JSON.stringify(form))
  Toast('草稿已保存')
}
</script>

<style scoped>
.pub { padding: 12px 14px 24px; }
.phead { display: flex; justify-content: space-between; align-items: center; font-weight: 700; font-size: 16px; padding: 4px 2px 12px; }
.phead em { font-style: normal; color: var(--text-3); font-weight: 400; font-size: 12px; }
.up { margin-bottom: 12px; }
.up :deep(.van-uploader__upload), .up :deep(.van-uploader__preview-image) { border-radius: 10px; }
.field {
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 10px; padding: 10px 12px; margin-bottom: 10px;
}
.field label { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 11px; margin-bottom: 4px; }
.field :deep(.van-field) { padding: 0; background: transparent; }
.field :deep(.van-field__control) { font-size: 13.5px; font-weight: 600; color: var(--text-1); }
.field :deep(textarea.van-field__control) { font-weight: 400; }
.ai-btn {
  border: 1px solid var(--brand); color: var(--brand); background: var(--brand-chip);
  border-radius: 99px; padding: 2px 10px; font-size: 10.5px; font-weight: 700; cursor: pointer;
}
.ai-btn:disabled { opacity: .5; }
.est { font-style: normal; color: var(--accent); font-weight: 700; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; padding-top: 4px; }
.chip {
  padding: 4px 12px; border-radius: 99px; font-size: 11px;
  background: var(--bg-page); border: 1px solid var(--border); color: var(--text-2);
}
.chip.on { background: var(--brand-chip); color: var(--brand); border-color: var(--brand); font-weight: 700; }
.pubbtn { margin-top: 16px; background: var(--brand); border-color: var(--brand); color: #fff; font-weight: 700; font-size: 15px; }
</style>
