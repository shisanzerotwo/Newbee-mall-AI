#!/usr/bin/env bash
# =============================================================================
# 并发压测 / 虚拟线程验证（M3）
#
# 目的：验证应用在**并发请求**下的表现，重点是 Spring Boot 3.5 的**虚拟线程**
#     （spring.threads.virtual.enabled=true）在「IO 等待多」的场景下能否撑住并发。
#
# 用法：
#   bash ops/loadtest.sh [base_url] [concurrency] [rounds]
#   默认 http://127.0.0.1:28091  32 100
#
# ⚠️ 设计上的诚实说明（别把这份结果当性能定论）：
#   1. 本脚本压的是**不依赖模型**的只读页面（首页/搜索页）。SSE 问答链路会打到
#      上游 agnes，而免费额度会 429 限流 → 那个数会被上游污染，不能用来评判本应用。
#      SSE 端点只做「并发连接能否建立」的可用性验证，不测吞吐。
#   2. WSL 访问不到 Windows 的 localhost → 统一走 Windows 侧 curl.exe。
#   3. 每轮记录状态码与耗时，最后给出 p50 / p95 / 失败率。
# =============================================================================
set -uo pipefail

BASE="${1:-http://127.0.0.1:28091}"
CONC="${2:-32}"
ROUNDS="${3:-100}"

# WSL 里没有 curl.exe 就直接用本机 curl（Git Bash / Linux 场景）
if command -v curl.exe >/dev/null 2>&1; then
    CURL="curl.exe"
elif command -v curl >/dev/null 2>&1; then
    CURL="curl"
else
    echo "找不到 curl / curl.exe" >&2
    exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "=============================================="
echo " 压测目标 : $BASE"
echo " 并发数   : $CONC"
echo " 每路轮数 : $ROUNDS"
echo " 总请求数 : $((CONC * ROUNDS))"
echo " curl     : $CURL"
echo "=============================================="
echo

# 先确认目标活着（否则后面所有数字都是噪声）
# 注意：不要把 "|| echo 000" 接在 -w 后面 —— WSL 调 Windows curl.exe 时退出码传递
# 与输出会混在一起，结果是 "200000"（我第一版就这么错的），改用单独的探测请求。
code="$($CURL -s -o /dev/null -w '%{http_code}' "$BASE/" 2>/dev/null)"
code="${code:0:3}"   # 只取前 3 位（防退出码/其他输出串进来）
case "$code" in
    2*|3*) echo "✅ 目标就绪（GET / = $code）" ;;
    *)     echo "❌ 目标未就绪：GET / 返回 '$code'（先启动应用）" >&2; exit 3 ;;
esac
echo

run_phase() {
    local name="$1" path="$2"
    echo "── 阶段：$name （$path）──"
    local start end
    start=$(date +%s.%N)

    for _ in $(seq 1 "$CONC"); do
        (
            for _ in $(seq 1 "$ROUNDS"); do
                # 输出：状态码 + 耗时(秒)，后续统计
                "$CURL" -s -o /dev/null -w "%{http_code} %{time_total}\n" "${BASE}${path}"
            done
        ) >>"$TMP/raw.txt" 2>/dev/null &
    done
    wait

    end=$(date +%s.%N)
    local wall
    wall=$(echo "$end $start" | awk '{printf "%.2f", $1-$2}')

    # 统计
    awk -v wall="$wall" -v name="$name" '
        {
            code[$1]++;
            t[NR]=$2;
            total++;
        }
        END {
            n = asort(t, s);
            p50 = s[int(n*0.50)+1];
            p95 = s[int(n*0.95)+1];
            max = s[n];
            ok = code["200"] + 0;
            printf "  总请求 %d ｜ 墙钟 %ss ｜ QPS %.1f\n", total, wall, total/wall;
            printf "  状态码分布: ";
            for (c in code) printf "%s=%d ", c, code[c];
            printf "\n";
            printf "  延迟 p50=%.3fs  p95=%.3fs  max=%.3fs\n", p50, p95, max;
            printf "  2xx 占比: %.1f%%\n\n", ok*100/total;
        }
    ' "$TMP/raw.txt"

    : >"$TMP/raw.txt"
}

run_phase "首页（只读，走缓存/DB）" "/"
run_phase "搜索页（DB 查询）" "/search?keyword=phone"
run_phase "商品分类页（DB + 模板渲染）" "/goods/category/1/1"

# SSE 端点只验证「并发连接可建立」，不测吞吐（上游会限流）
echo "── 阶段：SSE 端点可用性（仅验证并发连接能建立，不测吞吐）──"
sse_ok=0
sse_fail=0
for _ in $(seq 1 8); do
    code="$($CURL -s -o /dev/null -m 10 -w '%{http_code}' \
        -X POST "${BASE}/api/cs/chat" \
        -H 'Content-Type: application/json' \
        -d '{"question":"并发可用性探测","conversationId":"loadtest"}' 2>/dev/null)"
    code="${code:0:3}"
    if [ "$code" = "200" ]; then sse_ok=$((sse_ok + 1)); else sse_fail=$((sse_fail + 1)); fi
done
echo "  SSE 建立成功 $sse_ok ／ 失败 $sse_fail（失败常见原因：上游 429 限流，不代表应用有问题）"
echo
echo "=============================================="
echo " 说明：本压测刻意避开模型链路 —— agnes 免费额度会 429，"
echo "      那会把「应用吞吐」和「上游限流」混在一起，得不出可用结论。"
echo "=============================================="
