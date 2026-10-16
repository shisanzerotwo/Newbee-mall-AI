import { defineStore } from 'pinia'
import { chatWithAI, getGoodsDetail } from '../api'

let seq = 0

// 会话 ID 持久化在 localStorage（后端按 conversationId 做记忆与限流维度）
function conversationId() {
  let id = localStorage.getItem('ai_conversation')
  if (!id) {
    id = 'web_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
    localStorage.setItem('ai_conversation', id)
  }
  return id
}

// ===== 演示模式：后端未就绪时的本地模拟流式回答 =====
const DEMO_RULES = [
  {
    match: /自行车|单车|代步/,
    text: '为您找到 2 件符合条件的商品，均支持校内自提和平台担保：',
    cards: [1, 6]
  },
  {
    match: /发布|上架|卖/,
    text: '发布闲置只需 4 步：\n1. 点击底部中间「＋」按钮\n2. 上传实拍照片（最多 9 张）\n3. 点「✨ AI 生成」自动生成标题和描述，「✨ AI 估价」获取参考价\n4. 选择交易方式后点「立即发布」\n\n发布后约 10 分钟内完成审核上架。'
  },
  {
    match: /骗|担保|安全|退款/,
    text: '平台交易保障说明：\n· 所有卖家均通过校园实名认证（学号核验）\n· 建议选择「校内自提」当面验货后付款\n· 异地交易请使用平台担保：款项先由平台托管，确认收货后打给卖家\n· 如遇纠纷，保留聊天记录并在「我的-AI 智能客服」申请仲裁\n\n切勿脱离平台私下转账！'
  },
  {
    match: /估价|多少钱|价格/,
    text: '估价小技巧：\n· 发布页点击「✨ AI 估价」，系统会结合成色和同类成交价给出参考区间\n· 一般规律：九成新约为原价的 3~5 折，教材资料按笔记质量 10~50 元\n· 急出可适当低于参考价 10% 更容易成交'
  }
]
const DEMO_DEFAULT = '收到您的问题啦～我可以帮您：\n🔍 找商品（如"帮我找 300 元以内的自行车"）\n💰 估价与发布指导\n🛡️ 交易安全与担保规则咨询\n\n点击下方的快捷问题也可以直接问我！'

function demoStream(msg, userText, store) {
  const rule = DEMO_RULES.find(r => r.match.test(userText))
  const full = rule ? rule.text : DEMO_DEFAULT
  let i = 0
  store._demoTimer = setInterval(() => {
    i += 2
    msg.text = full.slice(0, i)
    if (i >= full.length) {
      clearInterval(store._demoTimer)
      rule?.cards?.forEach(gid => attachGoodsCard(msg, gid))
      store.streaming = false
      store.abort = null
    }
  }, 30)
  store.abort = () => { clearInterval(store._demoTimer); store.streaming = false }
}

// 商品卡片数据来自工具事件 args.goodsId（非模型文本，防幻觉）→ 查详情渲染
async function attachGoodsCard(msg, goodsId) {
  try {
    const g = await getGoodsDetail(goodsId)
    if (g && !msg.cards.some(c => c.goodsId === g.id || c.goodsId === g.goodsId)) {
      msg.cards.push({ goodsId: g.id ?? g.goodsId, title: g.title ?? g.goodsName, price: g.price ?? g.sellingPrice / 100, cover: g.cover ?? g.goodsCoverImg })
    }
  } catch {
    msg.cards.push({ goodsId: goodsId, title: `商品 #${goodsId}`, cover: '📦' })
  }
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    conversationId: conversationId(),
    messages: [
      { id: ++seq, role: 'assistant', text: '同学您好！我是校园交易小助手 🤖，可以帮您找商品、估价、解答交易规则～', cards: [] }
    ],
    streaming: false,
    abort: null,
    unread: 2,
    _demoTimer: null
  }),
  actions: {
    send(text, goodsId) {
      if (this.streaming || !text.trim()) return
      this.messages.push({ id: ++seq, role: 'user', text, cards: [] })
      const aiMsg = { id: ++seq, role: 'assistant', text: '', cards: [] }
      this.messages.push(aiMsg)
      this.streaming = true
      this.abort = chatWithAI(
        { conversationId: this.conversationId, question: text, goodsId },
        {
          onDelta: (t) => { aiMsg.text += t },
          onTool: (evt) => {
            const gid = evt?.args?.goodsId
            if (gid != null) attachGoodsCard(aiMsg, Number(gid))
          },
          onError: (msg) => {
            if (msg !== '网络有点不稳定，请稍后重试') {
              aiMsg.text = msg
              this.streaming = false
              this.abort = null
            } else {
              // 后端未启动：进入演示模式，本地模拟流式回答
              aiMsg.text = ''
              demoStream(aiMsg, text, this)
            }
          },
          onFinish: () => {
            if (!aiMsg.text && !aiMsg.cards.length) {
              demoStream(aiMsg, text, this)
              return
            }
            this.streaming = false
            this.abort = null
          }
        }
      )
    },
    stop() {
      if (this._demoTimer) clearInterval(this._demoTimer)
      this.abort?.()
      this.streaming = false
    }
  }
})
