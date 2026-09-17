#!/usr/bin/env bash
# ops/mvn.sh —— 在 Windows 侧调用 Maven
#
# 背景：本机 WSL 内没有 Linux 版 Maven/JDK（只有 Windows 侧 JDK 25 与
#       D:\tools\apache-maven-3.9.16），因此所有 Maven 调用必须经 PowerShell
#       转发到 Windows 侧执行。
#
# 用法：bash ops/mvn.sh <maven 参数...>
#   例：bash ops/mvn.sh -v
#       bash ops/mvn.sh clean compile
#       bash ops/mvn.sh spring-boot:run
#       bash ops/mvn.sh dependency:tree -Dincludes=org.springframework.boot:spring-boot
# 注意：参数会被逐个加单引号后传给 PowerShell（防二次解析拆散含 ':' 的参数）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/mall-backend"
MVN_WIN='D:\tools\apache-maven-3.9.16\bin\mvn.cmd'
JAVA_HOME_WIN='C:\Users\22421\.jdks\openjdk-25'

if [ ! -d "$APP" ]; then
  echo "❌ 找不到 $APP（需先完成 PLAN.md 的 Task 3：拷入源码）" >&2
  exit 1
fi

# 注意：DB_PASSWORD 由 PowerShell 从父进程环境变量自动继承，
# 切勿把密码插值到命令行字符串里（含单引号会被撑破，形成注入面）。
# 用法：export DB_PASSWORD='...' 后再调用本脚本。

# 逐参数用单引号包裹后再拼接：PowerShell 会对命令行做二次解析，
# 形如 -Dincludes=a:b 的参数若不加引号会在 ':' 处被拆散
# （实测症状：No plugin found for prefix '.springframework.boot'）。
QUOTED_ARGS=""
for arg in "$@"; do
  QUOTED_ARGS="$QUOTED_ARGS '$arg'"
done

APP_WIN="$(wslpath -w "$APP")"
powershell.exe -NoProfile -Command "cd '$APP_WIN'; \$env:JAVA_HOME='$JAVA_HOME_WIN'; & '$MVN_WIN'$QUOTED_ARGS"
