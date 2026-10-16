import axios from 'axios'
import { Toast } from 'vant'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('campus_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  res => {
    const data = res.data
    // 后端统一返回 { resultCode, message, data }（200 成功 / 416 未登录）
    if (data && typeof data === 'object' && 'resultCode' in data) {
      if (data.resultCode === 200) return data.data
      if (data.resultCode === 416) {
        const err = new Error(data.message || '未登录')
        err.code = 416
        return Promise.reject(err)
      }
      const err = new Error(data.message || '请求失败')
      err.code = data.resultCode
      Toast(data.message || '请求失败')
      return Promise.reject(err)
    }
    return data
  },
  err => {
    if (err.response?.status === 429) {
      Toast('操作太频繁，请稍后再试')
    } else if (err.response?.data?.message) {
      Toast(err.response.data.message)
    } else if (err.response?.status !== 401) {
      Toast('网络异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

export default request
