import request from './request'

// ===== 用户（后端为 HttpSession 鉴权，cookie 同源携带） =====
export const login = (data) => request.post('/user/login', data)
export const logout = () => request.post('/user/logout')
export const getUserInfo = () => request.get('/user/info')

// ===== 商品 =====
export const getRecommend = (params) => request.get('/goods/recommend', { params })
export const getGoodsDetail = (id) => request.get(`/goods/${id}`)
export const searchGoods = (params) => request.get('/goods/search', { params })
export const publishGoods = (data) => request.post('/goods', data)
export const getCategories = () => request.get('/goods/categories')

// ===== 想要（收藏） =====
export const toggleWant = (goodsId) => request.post(`/goods/${goodsId}/want`)
export const getMyWants = () => request.get('/goods/wants/mine')

// ===== 订单与支付（M5） =====
export const createOrder = (goodsId, pickupPoint) => request.post('/order/create', { goodsId, pickupPoint })
export const getOrderDetail = (orderNo) => request.get(`/order/${orderNo}`)
export const payOrder = (orderNo, payType) => request.post(`/order/${orderNo}/pay`, { payType })
export const cancelOrder = (orderNo) => request.post(`/order/${orderNo}/cancel`)
export const confirmOrder = (orderNo) => request.post(`/order/${orderNo}/confirm`)
export const getMyOrders = () => request.get('/order/mine')

// ===== AI 能力（非流式 REST） =====
export const aiEstimate = (data) => request.post('/ai/estimate', data)
export const aiTitle = (data) => request.post('/ai/title', data)

// ===== 消息（一期 REST 短轮询；二期 WebSocket） =====
export const getSessions = () => request.get('/im/sessions')
export const getMessages = (conversationId) => request.get('/im/messages', { params: { conversationId } })
export const sendMessage = (data) => request.post('/im/messages', data)
export const markRead = (conversationId) => request.post('/im/read', null, { params: { conversationId } })

/** 会话 id 生成规则（与后端 ChatApiController.conversationId 一致） */
export const buildConversationId = (a, b, goodsId) => {
  const min = Math.min(a, b), max = Math.max(a, b)
  return `c_${min}_${max}_${goodsId || 0}`
}

/**
 * AI 智能客服（SSE 流式，POST）—— 与后端 cs-core.js 契约对齐：
 *   请求：{ question, conversationId, goodsId, orderNo }
 *   帧：`event: stage|rag|tool|delta|review|done|error` + `data: <json>`
 *   - delta: { text } 增量文本
 *   - tool:  { name, args, ... }，商品卡片来自 args.goodsId（非模型文本，防幻觉）
 *   - review 是终止事件（gate 模式先于 done；audit 模式 done 非终止）
 *   - error: { message }
 * handlers: { onDelta(text), onTool(evt), onReview(), onDone(), onError(msg), onFinish(reason) }
 * 返回 abort 函数。
 */
export function chatWithAI({ conversationId, question, goodsId }, handlers = {}) {
  const controller = new AbortController()
  let finished = false
  const emit = (name, arg) => typeof handlers[name] === 'function' && handlers[name](arg)
  const finish = reason => { if (!finished) { finished = true; emit('onFinish', reason) } }

  fetch('/api/cs/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
    body: JSON.stringify({
      question,
      conversationId: conversationId || '',
      goodsId: goodsId ?? null,
      orderNo: null
    }),
    signal: controller.signal
  }).then(async res => {
    if (!res.ok || !res.body) {
      // 429 时后端仍返回一帧可读中文原因的 SSE error
      if (res.status === 429) {
        const raw = await res.text()
        let msg = '请求过于频繁，请稍后再试'
        const m = /"message"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(raw || '')
        if (m) { try { msg = JSON.parse('"' + m[1] + '"') } catch { /* 兜底文案 */ } }
        emit('onError', msg)
      } else {
        emit('onError', 'HTTP ' + res.status)
      }
      finish('http_error')
      return
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) { finish('closed'); break }
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        dispatch(parseFrame(frame))
        if (finished) return
        boundary = buffer.indexOf('\n\n')
      }
    }

    function parseFrame(frame) {
      let eventName = 'message'
      const dataLines = []
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
      }
      let data = null
      try { data = JSON.parse(dataLines.join('\n')) } catch { /* data:null 由调用方忽略 */ }
      return { event: eventName, data }
    }

    function dispatch({ event, data }) {
      switch (event) {
        case 'tool': emit('onTool', data || {}); break
        case 'delta': emit('onDelta', (data && data.text) || ''); break
        case 'review': emit('onReview', data || {}); finish('review'); controller.abort(); break
        case 'done': emit('onDone', data || {}); break // audit 模式下 review 在后，非终止
        case 'error': emit('onError', (data && data.message) || '服务暂时不可用'); break
        case 'rag': emit('onRag', (data && data.sources) || []); break
        default: break // 未知事件忽略（向后兼容）
      }
    }
  }).catch(err => {
    if (err.name !== 'AbortError') {
      emit('onError', '网络有点不稳定，请稍后重试')
      finish('fetch_error')
    }
  })
  return () => controller.abort()
}
