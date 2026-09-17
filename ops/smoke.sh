#!/usr/bin/env bash
# ops/smoke.sh —— 升级前后对照用的冒烟回归脚本
#
# ⚠️ 环境约束（本机实测）：
#   本机 WSL 无法访问 Windows 侧的 127.0.0.1:28089 —— NAT 隔离，
#   实测 WSL 直连与「网关 IP」两种方式均返回 000，只有 Windows 自带的
#   curl.exe 能拿到 200。因此本脚本优先使用 /mnt/c/Windows/System32/curl.exe，
#   并将响应体落到仓库内临时文件（WSL 与 Windows 双端可读）。
#
# ⚠️ 断言强度说明（2026-09-17 补强，起因见 docs/UPGRADE-BOOT3.md）：
#   初版只断言「状态码 + 一两个静态关键字」，结果在【应用连不上 MySQL】
#   的真实故障下仍然全绿（首页查询异常被吞、页面用空数据渲染，仍是 200）。
#   现补两道闸门：
#     ① 首页必须含数据库驱动的内容（goods/detail/ 链接）——DB 失联即无链接；
#     ② 任何 200 响应都不得含错误页特征。
#
# 用法：bash ops/smoke.sh [base_url]      默认 http://127.0.0.1:28089
set -u
BASE="${1:-http://127.0.0.1:28089}"
PASS=0
FAIL=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BODY_FILE="$SCRIPT_DIR/.smoke_body.tmp"

if [ -x /mnt/c/Windows/System32/curl.exe ]; then
  CURL=/mnt/c/Windows/System32/curl.exe
  BODY_OUT="$(wslpath -w "$BODY_FILE" 2>/dev/null || echo "$BODY_FILE")"
else
  CURL=curl
  BODY_OUT="$BODY_FILE"
fi

# 错误页特征：命中任一即判失败
# （含项目自定义错误页用词；2026-09-17 补全 —— 初版只覆盖 Whitelabel/系统异常，
#   漏掉了“页面不存在/请求错误/服务异常”，导致 /search/ 的错误页没被抓住）
ERROR_RE='Whitelabel Error|Internal Server Error|系统异常|系统错误|出错了|页面不存在|请求错误|服务异常|NOT_FOUND'

check() {  # check <名称> <路径> <期望状态码> [必须包含的关键字]
  local name="$1" path="$2" want="$3" kw="${4:-}"
  local code
  code=$("$CURL" -s -o "$BODY_OUT" -w "%{http_code}" --max-time 10 "$BASE$path" 2>/dev/null || echo "000")

  if [ "$code" != "$want" ]; then
    echo "❌ $name  $path  期望 $want 实得 $code"
    FAIL=$((FAIL+1)); return
  fi
  # 闸门②：200 响应不得是错误页
  if [ "$code" = "200" ] && grep -qE "$ERROR_RE" "$BODY_FILE" 2>/dev/null; then
    echo "❌ $name  $path  返回 200 但响应体含【错误页特征】"
    FAIL=$((FAIL+1)); return
  fi
  if [ -n "$kw" ] && ! grep -q "$kw" "$BODY_FILE" 2>/dev/null; then
    echo "❌ $name  $path  状态码 $code 但缺少关键字「$kw」"
    FAIL=$((FAIL+1)); return
  fi
  echo "✅ $name  $path  ($code)"
  PASS=$((PASS+1))
}

echo "=== 冒烟回归 @ $BASE （curl: $CURL）==="
echo "--- 前台 ---"
# 闸门①：首页必须含 DB 驱动的商品链接（DB 失联时首页仍 200，但没有商品链接）
check "首页"          "/"                        200 "goods/detail/"
check "首页·站点名"   "/"                        200 "新蜂商城"
# 中文关键字需 URL 编码（直接传中文会被 Windows curl.exe 的 codepage 破坏，实测 400）
check "商品搜索"      "/search?keyword=%E5%8C%96%E5%A6%86%E6%B0%B4" 200
check "商品搜索·html" "/search.html?keyword=phone" 200
check "商品详情"      "/goods/detail/10003"      302   # 受登录拦截器保护 → 跳 /login
check "购物车页"      "/shop-cart"               302   # 同上
check "登录页"        "/login"                   200
check "注册页"        "/register"                200
check "个人中心"      "/personal"                302   # 同上
echo "--- 后台 ---"
check "后台登录"      "/admin/login"             200
check "后台首页"      "/admin/index"             302
echo "--- 基础设施 ---"
check "验证码图片"    "/common/kaptcha"          200
check "静态资源·CSS"  "/mall/styles/header.css"  200
check "静态资源·JS"   "/mall/js/index.js"        200

echo "--- 尾斜杠规范化（Spring 6 默认不匹配，由 TrailingSlashNormalizeFilter 重定向恢复）---"
# 这些路径在修复前会返回【200 + 错误页内容】，单看状态码抓不到
check "尾斜杠·搜索"  "/search/?keyword=phone"     302
check "尾斜杠·登录"  "/login/"                    302

echo "=== 通过 $PASS / 失败 $FAIL ==="
rm -f "$BODY_FILE"
[ "$FAIL" -eq 0 ]
