import { defineStore } from 'pinia'
import { getRecommend, searchGoods } from '../api'

// M1 演示数据（后端就绪前兜底）
const MOCK = [
  { id: 1, title: '九成新山地自行车 可校内试骑', price: 260, originalPrice: 899, cover: '🚲', condition: '九成新', urgent: true, seller: { name: '王同学', credit: 4.9 }, views: 342 },
  { id: 2, title: '考研数学全套资料 笔记工整 附赠错题本', price: 45, originalPrice: 180, cover: '📖', condition: '几乎全新', seller: { name: '李同学', credit: 5.0 }, views: 128 },
  { id: 3, title: '蓝牙耳机 降噪款 续航正常', price: 120, cover: '🎧', condition: '轻微使用', urgent: true, seller: { name: '张同学', credit: 4.7 }, views: 96 },
  { id: 4, title: '宿舍护眼台灯 三档调光', price: 18, cover: '💡', condition: '八成新', seller: { name: '刘同学', credit: 4.8 }, views: 45 },
  { id: 5, title: '高等数学教材 同济第七版 上下册', price: 25, originalPrice: 78, cover: '📕', condition: '明显痕迹', seller: { name: '赵同学', credit: 4.6 }, views: 67 },
  { id: 6, title: '篮球 斯伯丁 室外场用球', price: 39, cover: '🏀', condition: '八成新', seller: { name: '阿同学', credit: 4.9 }, views: 88 },
  { id: 7, title: 'iPad 电容笔 平替款 几乎全新', price: 55, originalPrice: 129, cover: '✏️', condition: '几乎全新', seller: { name: '古丽同学', credit: 5.0 }, views: 156 },
  { id: 8, title: '冬季羽绒服 黑色 M 码 仅穿一季', price: 99, originalPrice: 399, cover: '🧥', condition: '九成新', seller: { name: '马同学', credit: 4.8 }, views: 203 }
]

export const useGoodsStore = defineStore('goods', {
  state: () => ({
    list: [],
    loading: false,
    finished: false,
    page: 1,
    keyword: '',
    history: JSON.parse(localStorage.getItem('search_history') || '[]')
  }),
  actions: {
    async loadMore() {
      if (this.loading || this.finished) return
      this.loading = true
      try {
        const res = this.keyword
          ? await searchGoods({ keyword: this.keyword, page: this.page })
          : await getRecommend({ page: this.page })
        const items = res?.list || res || []
        this.list.push(...items)
        this.finished = items.length === 0
        this.page++
      } catch {
        // 后端未就绪：首页使用演示数据
        if (this.page === 1) this.list.push(...MOCK)
        this.finished = true
      } finally {
        this.loading = false
      }
    },
    reset(keyword = '') {
      this.list = []
      this.page = 1
      this.finished = false
      this.keyword = keyword
    },
    addHistory(kw) {
      if (!kw) return
      this.history = [kw, ...this.history.filter(h => h !== kw)].slice(0, 10)
      localStorage.setItem('search_history', JSON.stringify(this.history))
    }
  }
})
