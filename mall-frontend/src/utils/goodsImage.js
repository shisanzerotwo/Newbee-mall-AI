/**
 * 商品封面兜底：后端 seed 的 cover 是「规划路径」（/campus/*.jpg），实际文件不存在，
 * 直接用 <img src> 会 404/拿到 HTML。因此统一走这里：
 *   1. cover 是 http(s) 绝对地址 → 直接用（外部图床/上传图）
 *   2. 否则按标题关键词给出 emoji 占位，视觉稳定且零请求
 */

const KEYWORD_EMOJI = [
  [/自行车|单车|山地车|折叠车/, '🚲'],
  [/考研|教材|高数|高等数学|四六级|真题|英语真题|资料|书籍|笔记/, '📚'],
  [/耳机|音箱|降噪/, '🎧'],
  [/台灯|灯|护眼/, '💡'],
  [/篮球|球|运动|健身/, '🏀'],
  [/羽毛球|网球|足球/, '🏸'],
  [/电脑|笔记本|macbook|平板|ipad|键盘|鼠标/, '💻'],
  [/手机|iphone|华为|小米|充电器|数据线/, '📱'],
  [/电煮锅|锅|小家电|风扇|吹风机/, '🍲'],
  [/伞|雨具/, '☂️'],
  [/羽绒服|外套|上衣|卫衣|衣/, '🧥'],
  [/鞋|球鞋|运动鞋/, '👟'],
  [/箱包|背包|书包/, '🎒'],
  [/手表|手环|智能/, '⌚'],
  [/相机|摄像/, '📷'],
  [/化妆品|护肤|口红/, '💄'],
  [/水杯|保温杯|杯/, '🥤'],
  [/折叠桌|桌|椅|收纳/, '🪑'],
  [/票|卡券|会员/, '🎫'],
  [/吉他|乐器|琴/, '🎸'],
  [/眼镜/, '👓']
]

const DEFAULT_EMOJI = '📦'

/** 依据商品标题/分类推断 emoji */
export function emojiOf(goods = {}) {
  const text = `${goods.title || ''} ${goods.categoryName || ''} ${(goods.tags || []).join(',')}`
  for (const [re, emoji] of KEYWORD_EMOJI) {
    if (re.test(text)) return emoji
  }
  return DEFAULT_EMOJI
}

/** 真实图片地址（仅 http/https 视为可用），否则返回空串表示用 emoji */
export function realCoverOf(goods = {}) {
  const cover = goods.cover || ''
  return /^https?:\/\//i.test(cover) ? cover : ''
}