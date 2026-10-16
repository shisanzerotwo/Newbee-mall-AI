import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
  { path: '/', name: 'home', component: () => import('../views/HomeView.vue'), meta: { title: '首页', tab: true } },
  { path: '/category', name: 'category', component: () => import('../views/CategoryView.vue'), meta: { title: '分类', tab: true } },
  { path: '/publish', name: 'publish', component: () => import('../views/PublishView.vue'), meta: { title: '发布闲置', tab: true, auth: true } },
  { path: '/messages', name: 'messages', component: () => import('../views/MessagesView.vue'), meta: { title: '消息', tab: true, auth: true } },
  { path: '/mine', name: 'mine', component: () => import('../views/MineView.vue'), meta: { title: '我的', tab: true, auth: true } },
  { path: '/goods/:id', name: 'goods-detail', component: () => import('../views/GoodsDetailView.vue'), meta: { title: '商品详情' } },
  { path: '/search', name: 'search', component: () => import('../views/SearchView.vue'), meta: { title: '搜索' } },
  { path: '/favorites', name: 'favorites', component: () => import('../views/FavoritesView.vue'), meta: { title: '我想要的', auth: true } },
  { path: '/orders', name: 'orders', component: () => import('../views/OrdersView.vue'), meta: { title: '我的订单', auth: true } },
  { path: '/pay/:orderNo', name: 'pay', component: () => import('../views/PayView.vue'), meta: { title: '订单收银台', auth: true } },
  { path: '/pay/success', name: 'pay-success', component: () => import('../views/PaySuccessView.vue'), meta: { title: '支付结果', auth: true } },
  { path: '/chat/ai', name: 'ai-chat', component: () => import('../views/AiChatView.vue'), meta: { title: 'AI 智能客服' } },
  {
    path: '/chat/user/:peerId/:goodsId/:conversationId',
    name: 'user-chat',
    component: () => import('../views/UserChatView.vue'),
    meta: { title: '私聊', auth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '伊师大校园闲置'
  const token = localStorage.getItem('campus_token')
  if (to.meta.auth && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
