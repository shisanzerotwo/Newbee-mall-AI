import { defineStore } from 'pinia'
import { login, logout, getUserInfo } from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('campus_token') || '',
    info: null
  }),
  getters: {
    isLogin: (s) => !!s.token,
    isVerified: (s) => !!s.info?.campusVerified
  },
  actions: {
    /** 账号密码登录（后端 HttpSession + Cookie 鉴权，无 JWT） */
    async login(payload) {
      const data = await login(payload)
      this.info = data
      this.token = 'session'
      localStorage.setItem('campus_token', this.token)
      return data
    },
    /** 拉取当前用户资料；未登录（416）时清空本地态 */
    async fetchInfo() {
      try {
        this.info = await getUserInfo()
        this.token = 'session'
        localStorage.setItem('campus_token', this.token)
      } catch (e) {
        if (e.code === 416) {
          this.info = null
          if (!localStorage.getItem('campus_demo')) {
            this.token = ''
            localStorage.removeItem('campus_token')
          }
        }
      }
    },
    async logout() {
      try { await logout() } catch { /* 后端不可用时也允许本地登出 */ }
      this.token = ''
      this.info = null
      localStorage.removeItem('campus_token')
    }
  }
})
