#!/usr/bin/env bash
# ops/smoke.sh —— 升级前后对照用的冒烟回归脚本
#
# ⚠️ 环境约束（本机实测）：
#   本机 WSL 无法访问 Windows 侧的 127.0.0.1:28089 —— NAT 隔离，
#   实测 WSL 直连与「网关 IP」两种方式均返回 000，只有 Windows 自带的
#   curl.exe 能拿到 200。因此本脚本优先使用 /mnt/c/Windows/System32/curl.exe，
#   并将响应体落到仓库内临时文件（WSL 与 Windows 双端可读）。
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
  BODY_OUT="$(wslpath -w "$BODY_FILE")"
else
  CURL=curl
  BODY_OUT="$BODY_FILE"
fi

check() {  # check <名称> <路径> <期望状态码> [关键字]
  local name="$1" path="$2" want="$3" kw="${4:-}"
  local code
  code=$("$CURL" -s -o "$BODY_OUT" -w "%{http_code}" --max-time 10 "$BASE$path" 2>/dev/null || echo "000")
  if [ "$code" != "$want" ]; then
    echo "❌ $name  $path  期望 $want 实得 $code"
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
check "首页"        "/"                      200 "新蜂商城"
# 中文关键字需 URL 编码（直接传中文会被 Windows curl.exe 的 codepage 破坏，实测 400）
check "商品搜索"    "/search?keyword=%E5%8C%96%E5%A6%86%E6%B0%B4" 200
check "商品详情"    "/goods/detail/10003"    302   # 受登录拦截器保护 → 跳 /login
check "购物车页"    "/shop-cart"             302   # 同上
check "登录页"      "/login"                 200
check "注册页"      "/register"              200
check "个人中心"    "/personal"              302   # 同上
echo "--- 后台 ---"
check "后台登录"    "/admin/login"           200
check "后台首页"    "/admin/index"           302
echo "--- 基础设施 ---"
check "验证码图片"  "/common/kaptcha"        200
check "静态资源"    "/mall/styles/header.css" 200

echo "=== 通过 $PASS / 失败 $FAIL ==="
rm -f "$BODY_FILE"
[ "$FAIL" -eq 0 ]
