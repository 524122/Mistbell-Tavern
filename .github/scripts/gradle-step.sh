#!/usr/bin/env bash
# CI 里执行一条 Gradle 命令：完整输出落到日志文件；失败时把关键错误行转成注解。
#
# 为什么要这么做：Actions 的日志需要登录才能读，而注解（::error::）在公开仓库的运行页上
# 匿名可见。项目维护者在没有 gh / 令牌的机器上也能据此定位失败原因。
#
# 用法：bash ./.github/scripts/gradle-step.sh <日志文件名> <gradle 参数...>
#   例：bash ./.github/scripts/gradle-step.sh assemble.log :app:assembleDebug
# 退出码与 gradlew 一致。

set -uo pipefail

name="$1"
shift

log="$RUNNER_TEMP/${name}"
start=$(date +%s)

./gradlew "$@" --console=plain > "$log" 2>&1
code=$?

echo "----- ${name}：退出码 ${code}，耗时 $(( $(date +%s) - start )) 秒，日志末尾 -----"
tail -n 30 "$log" | tr -d '\r' | awk '{ print substr($0, 1, 400) }'

if [ "$code" -ne 0 ]; then
  echo "::error::gradle $* 失败，退出码 ${code}"
  # 关键错误行：任务失败、Kotlin 编译错误、Gradle 的 What went wrong、常见环境错误
  awk '/FAILED|Build failed|^e: |What went wrong|Execution failed for task|Caused by:|Could not|> Cannot find|OutOfMemory|exceeded|No such file|not found| missing|SDK location/ { print substr($0, 1, 360) }' "$log" \
    | awk '!seen[$0]++' | head -n 9 \
    | while IFS= read -r line; do echo "::error title=${name%.log}::${line}"; done
fi

exit "$code"
