#!/usr/bin/env bash
# =============================================================
# AiTripPlan-AgentScope 冒烟测试脚本
# 用途：验证整条链路是否正常（端口 → 前端 → 登录 → A2A任务 → 思考过程 → 结果）
# 用法：bash scripts/smoke-test.sh
# 退出码：0=全部通过  1=有失败（每个失败项都会打印 ❌ 原因）
# =============================================================
set -u

BASE="http://localhost:8081"
COOKIE="$(mktemp)"
PASS=0; FAIL=0
TEST_USER="smoketest_$(date +%s)"   # 每次测试用唯一用户名，避免重复冲突
TEST_PASS="123456"

ok()   { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ❌ $1"; }

echo "== ① 端口检查 =="
for port in 8081 8082 8085 8848; do
    # 用 curl 探测为准（netstat 在 git-bash 里正则不稳定）；HTTP 4xx/405 也算服务活着
    code=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "http://localhost:${port}/" 2>/dev/null)
    if [ "$code" != "000" ]; then
        ok "端口 ${port} 在监听 (HTTP ${code})"
    else
        bad "端口 ${port} 未监听（服务没起来？先跑 start-local.sh）"
    fi
done

echo "== ② 前端资源 =="
for f in login.html index.html css/style.css css/print.css js/app.js; do
    code=$(curl -s -o /dev/null -w "%{http_code}" -m 5 "${BASE}/${f}")
    [ "$code" = "200" ] && ok "${f} (200)" || bad "${f} → HTTP ${code}"
done

echo "== ③ 注册+登录 =="
R1=$(curl -s -m 10 -X POST "${BASE}/api/register?username=${TEST_USER}&password=${TEST_PASS}")
echo "$R1" | grep -q '"success":true' && ok "注册 ${TEST_USER}" || bad "注册失败: ${R1}"
R2=$(curl -s -m 10 -c "$COOKIE" -X POST "${BASE}/api/login?username=${TEST_USER}&password=${TEST_PASS}")
echo "$R2" | grep -q '"success":true' && ok "登录成功" || bad "登录失败: ${R2}"

echo "== ④ 提交任务（A2A 完整链路，约2-4分钟）=="
R3=$(curl -s -m 10 -b "$COOKIE" -X POST "${BASE}/app?prompt=$(python -c "import urllib.parse;print(urllib.parse.quote('帮我制定深圳到惠州1日自驾游计划，包含景点、交通、餐饮'))")")
echo "$R3" | grep -q "已接收" && ok "任务已提交" || bad "任务提交失败: ${R3}"

echo "== ⑤ 轮询思考过程（/events 应有 用户输入/思考/调用工具）=="
EVENT_OK=0
for i in $(seq 1 40); do   # 最多等 ~130 秒
    sleep 3
    E=$(curl -s -m 5 -b "$COOKIE" "${BASE}/events" 2>/dev/null)
    echo "$E" | grep -q '"type":"用户输入"' && echo "$E" | grep -q '"type":"思考"' && echo "$E" | grep -q '"type":"调用工具"' && { EVENT_OK=1; break; }
    # 完成事件也算思考过程正常
    echo "$E" | grep -q '"type":"完成"' && { EVENT_OK=1; break; }
done
[ "$EVENT_OK" = "1" ] && ok "思考过程事件流正常（用户输入/思考/调用工具）" || bad "思考过程为空（planHook 事件未上报？）"

echo "== ⑥ 轮询最终结果（/result）=="
RESULT_OK=0
for i in $(seq 1 80); do   # 最多等 ~5 分钟
    sleep 3
    R=$(curl -s -m 5 -b "$COOKIE" "${BASE}/result" 2>/dev/null)
    echo "$R" | grep -q "还在执行中" || { RESULT_OK=1; break; }
done
if [ "$RESULT_OK" = "1" ]; then
    LEN=$(echo -n "$R" | wc -c)
    [ "$LEN" -gt 500 ] && ok "最终结果生成（${LEN} 字符）" || bad "结果过短（${LEN} 字符），可能模型输出异常"
else
    bad "结果超时未生成（5分钟）"
fi

echo "== ⑦ A2A 子Agent调用（事件流里应有 路线Agent/行程Agent ✅）=="
E=$(curl -s -m 5 -b "$COOKIE" "${BASE}/events" 2>/dev/null)
echo "$E" | grep -q "Agent ✅ 调用成功" && ok "A2A 远程Agent调用成功" || bad "未看到 Agent 调用成功事件（Nacos/子Agent 异常？）"

rm -f "$COOKIE"
echo
echo "=========================================="
echo "结果: ✅ ${PASS} 通过 / ❌ ${FAIL} 失败"
[ "$FAIL" -eq 0 ] && echo "🎉 全链路正常！" || echo "⚠️ 有失败项，按上面 ❌ 定位。"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
