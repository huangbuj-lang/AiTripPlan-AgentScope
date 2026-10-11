#!/bin/bash
# ★2026-09-11 本地 CI（评审点：无 CI，改动没有自动化验证；且此前"111/111 通过"实测是
#   111 run / 1 failure —— 测试依赖环境变量、静态状态跨测试残留，本地跑不出来）。
#
# 用法:
#   ./scripts/ci.sh                        # 编译 + 全部单测（默认，1-2 分钟）
#   ./scripts/ci.sh --package              # 额外打三个服务 jar（需先停 8091/8082/8085，否则 jar 被锁）
#   ./scripts/ci.sh --e2e "成都5天自驾游"   # 额外跑端到端（需服务已启动 + E2E_USER/E2E_PASS）
#
# 退出码：0=全绿，非 0=有失败（可接 CI 或提交前钩子）。

cd "$(dirname "$0")/.." || exit 1
MVN="/d/Apps/Maven/mvn17.cmd"
FAILED=0
TMP="$LOCALAPPDATA/Temp/ci_$$.log"
mkdir -p "$(dirname "$TMP")"

step() { echo; echo "==================== $* ===================="; }
verdict() { if [ "$1" -eq 0 ]; then echo "✅ $2"; else echo "❌ $2"; FAILED=1; fi; }

step "1/3 编译全部模块（含测试代码）"
"$MVN" -q -pl backend/manager_agent,backend/tripPlanner_agent,backend/routeMaking_agent -am test-compile > "$TMP" 2>&1
verdict $? "test-compile"
grep -aE "ERROR" "$TMP" | head -6

step "2/3 commons 单测（SafeModel / ContractChecker / 解析层回归）"
"$MVN" -pl backend/commons test > "$TMP" 2>&1
verdict $? "commons 单测"
grep -aE "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$" "$TMP" | tail -1
grep -aE "FAILURE!|expected:" "$TMP" | head -6

step "3/3 manager 解析层测试（RoutePlanParserTest）"
"$MVN" -pl backend/manager_agent -am test -Dtest='RoutePlanParserTest' -Dsurefire.failIfNoSpecifiedTests=false > "$TMP" 2>&1
verdict $? "manager 解析层测试"
grep -aE "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$" "$TMP" | tail -1

if [ "$1" = "--package" ]; then
  step "附加：打包三服务（jar 被占用会失败，请先停服务）"
  "$MVN" -q -pl backend/routeMaking_agent,backend/tripPlanner_agent,backend/manager_agent -am package -DskipTests > "$TMP" 2>&1
  verdict $? "package"
  grep -aE "ERROR" "$TMP" | head -5
fi

if [ "$1" = "--e2e" ]; then
  step "附加：端到端（$2）"
  python scripts/e2e_test.py "$2"
  verdict $? "e2e"
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "==================== ✅ CI 全绿 ===================="
  exit 0
else
  echo "==================== ❌ CI 有失败，见上 ===================="
  exit 1
fi
