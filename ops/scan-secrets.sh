#!/usr/bin/env bash
# ops/scan-secrets.sh —— 推送前的**全历史**密钥扫描（DEVELOPMENT.md §8 的固定动作）
#
# 为什么存在：本项目真踩过 —— docs/STATUS.md 的示例命令带明文 MySQL 密码，
# 从 5be5b05 起被之后**所有**提交继承（19 个历史版本），最后只能靠
# `git filter-branch --tree-filter` 重写历史才清掉。
# 「推送前扫全历史」是固定动作，不该依赖"想起来才做" —— 所以固化成一条命令。
#
# 用法：bash ops/scan-secrets.sh
# 退出码：0 = 未发现疑似泄漏；1 = 有命中（先人工核对再 push）；
#         2 = 扫描**自身没跑成**（脚本报错/环境问题）—— **这不代表仓库干净**
#
# ⚠️ 退出码 2 是特意分开的：一个自己跑不起来却报“干净”的扫描脚本，
#    比没有脚本更危险（本项目已经吃过多次“观察手段失效”的亏）。
#
# 三个设计取舍：
#   ① 一次性导出全部对象再扫（`--batch --buffer`），而不是逐对象 `cat-file` ——
#      1221 个对象 × N 个模式逐对象跑要等数分钟，导出一次是秒级。
#   ② 只报「模式 / 对象 / 文件路径」，**不打印命中内容** —— 万一真泄漏，
#      把内容打到终端就等于二次泄漏（终端历史、CI 日志都会留）。要看得手工查。
#   ③ 白名单过滤**已知无害**命中：占位符（`<你的MySQL密码>`）、省略号、`.env.example`
#      的空值、`$VAR` 引用，以及 `agent-demo-key` 这个固定演示字面量（它只出现在
#     描述历史的文档文本里，代码中早已不存在）。
#      白名单是**启发式**的：它只能降噪，真值判断必须人工。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v python3 >/dev/null 2>&1; then
  echo "❌ 需要 python3（解析 git 对象流）" >&2
  exit 2
fi

IDS="$(mktemp)"; DUMP="$(mktemp)"
trap 'rm -f "$IDS" "$DUMP"' EXIT

git rev-list --objects --all | awk '{print $1}' > "$IDS"
echo "→ 全历史：$(git rev-list --all | wc -l | tr -d ' ') 个提交 / $(wc -l < "$IDS" | tr -d ' ') 个对象"

git cat-file --batch --buffer < "$IDS" > "$DUMP"
echo "→ 已导出 $(du -h "$DUMP" | cut -f1)，开始扫描…"
echo

set +e   # 允许 python 以非 0 退出，由下面根据退出码区分「有命中」与「没跑成」
python3 - "$DUMP" <<'PY'
import re
import subprocess
import sys

dump = open(sys.argv[1], 'rb').read()

# 解析 `git cat-file --batch` 流：<oid> <type> <size>\n<body>\n
hdr = re.compile(rb'(?m)^([0-9a-f]{40}) (blob|tree|commit|tag) (\d+)\n')
items, pos = [], 0
while True:
    m = hdr.match(dump, pos)
    if not m:
        nxt = hdr.search(dump, pos)
        if not nxt:
            break
        pos = nxt.start()
        continue
    oid, typ, size = m.group(1).decode(), m.group(2).decode(), int(m.group(3))
    items.append((oid, typ, dump[m.end():m.end() + size]))
    pos = m.end() + size + 1

oid2path = {}
for line in subprocess.run(['git', 'rev-list', '--objects', '--all'],
                           capture_output=True, text=True).stdout.splitlines():
    parts = line.split(' ', 1)
    if len(parts) == 2:
        oid2path[parts[0]] = parts[1]

# 「值」形态的无害白名单：空值 / 占位符 / 省略号 / 示例词 / $VAR 引用 / 中文占位说明
# 「值」形态的无害白名单：空值 / 占位符 / 文档标点 / 省略号 / 示例词 / $VAR 引用 / 中文占位说明
# ⚠️ 中文**不能放字符类**（`[...]`）—— UTF-8 多字节会被拆成单字节导致误匹配，必须用 `|` 分支。
CN_FILLER = "您的|你的|索取|占位|密码|（|）|，|。|、".encode('utf-8')
BENIGN = re.compile(
    rb"^$"                        # 空值（如文档示例的 `-p` 后面什么都没写）
    rb"|^[<\x27\"\x60)\]}"
    rb"|^[;, (]"
    rb"|\.\.\.|xxx|redacted"
    rb"|^x$|^\$|^\\"              # 字面测试值 x / $VAR 引用 / 反斜杠转义
    rb"|"
    + CN_FILLER
)
# 固定演示字面量：只出现在「描述历史」的文档文本里，代码中已不存在
KNOWN_HARMLESS = re.compile(rb"^agent-demo-key$")

PATTERNS = [
    ('API key 片段 (sk-)', re.compile(rb'sk-[A-Za-z0-9_-]{16,}'), None),
    ('历史演示 key', re.compile(rb'agent-demo-key'), KNOWN_HARMLESS),
    ('MySQL 密码赋值', re.compile(rb'DB_PASSWORD=([^\s]{0,40})'), BENIGN),
    ('命令行密码 (-p)', re.compile(rb'mysql[^\n]{0,25}-p([^\s]{0,25})'), BENIGN),
]

failed = False
for label, pattern, benign in PATTERNS:
    hits = {}
    for oid, _typ, body in items:
        for m in pattern.finditer(body):
            key = m.group(1) if m.groups() else m.group(0)
            if benign is not None and benign.search(key):
                continue
            hits[oid] = hits.get(oid, 0) + 1

    print(f"{'✅' if not hits else '❌'} {label}: {len(hits)} 个命中对象")
    for oid in list(hits)[:10]:
        print(f"     {oid[:12]}  {oid2path.get(oid, '<commit/tree>')}")
    if hits:
        failed = True

print()
if failed:
    print('❌ 发现疑似命中 —— **先人工核对再 push**。')
    print('   查看内容（确认是泄漏后请勿把内容外传）：')
    print('     git cat-file -p <完整 oid> | grep -n "<模式>"')
    print('   确认为泄漏后的清理流程见 docs/DEVELOPMENT.md §8（filter-branch + 复扫确认 0）。')
    sys.exit(10)   # 10 = 有命中；与“脚本自身失败”的普通非 0 退出码区分
print('✅ 未发现疑似泄漏（已过滤占位符/示例值；白名单是启发式的，真值仍需人工判断）')
sys.exit(0)
PY
rc=$?
set -e

case "$rc" in
  0)  echo "✅ 扫描完成：未发现疑似泄漏"; exit 0 ;;
  10) echo "❌ 扫描完成：有疑似命中 —— 先人工核对再 push"; exit 1 ;;
  *)  echo "❌ 扫描**没跑成**（退出码 $rc）—— 这不代表仓库干净，请先修脚本" >&2; exit 2 ;;
esac
