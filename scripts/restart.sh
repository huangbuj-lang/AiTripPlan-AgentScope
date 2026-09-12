#!/bin/bash
# 服务重启（就绪探测版，不盲等）2026-09-11
# 用法: bash scripts/restart.sh manager|nacos|all
# 对比旧做法：盲等 45s×N → 现在起来就返回，每次省 40~70s
set -u
APP=/e/gitee/aitripplan-agentscope
NACOS=/d/Apps/Nacos/nacos
MVN17=/d/Apps/Maven/mvn17.cmd

wait_port() { # $1=port $2=超时秒
  for i in $(seq 1 "${2:-90}"); do
    code=$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://localhost:$1/" 2>/dev/null)
    [ -n "$code" ] && [ "$code" != "000" ] && { echo "  ✓ $1 就绪（${i}s，HTTP $code）"; return 0; }
    sleep 1
  done
  echo "  ✗ $1 超时 ${2:-90}s"; return 1
}

kill_port() {
  local pid
  pid=$(netstat -ano 2>/dev/null | grep ":$1 " | grep LISTENING | awk '{print $5}' | head -1)
  [ -n "$pid" ] && { powershell -Command "Stop-Process -Id $pid -Force" 2>/dev/null; echo "  已停 $1 (pid=$pid)"; sleep 3; }
  return 0
}

start_nacos() {
  echo "[Nacos] 启动…"
  kill_port 8848
  cd "$NACOS" || return 1
  # ★关键参数（踩过的坑）：--spring.* 必须在 -jar 之后；高版 JDK 必须 --add-opens java.util
  nohup java -Dnacos.standalone=true -Dnacos.home=D:/Apps/Nacos/nacos \
    -Dloader.path=D:/Apps/Nacos/nacos/plugins \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
    -jar target/nacos-server.jar \
    --spring.config.additional-location=file:D:/Apps/Nacos/nacos/conf/ > nacos_manual.log 2>&1 &
  wait_port 8848 90
}

start_manager() {
  echo "[manager] 启动…"
  kill_port 8081
  cd "$APP" || return 1
  set -a; . ./.env; set +a
  export PATH="/d/Apps/MySQL/mysql-8.0.45-winx64/bin:$PATH"
  nohup java --enable-preview -Xms256m -Xmx256m \
    -jar run/manager_agent.jar > logs/manager_gitee.log 2>&1 &
  wait_port 8081 120
  curl -s -m 6 http://localhost:8081/health | head -c 200; echo
}

start_agents() {
  # ★2026-09-12 补：原来 all 只起 Nacos+manager，两个子Agent(8082/8085)不会起，
  #   导致"重启后 A2A 调用全失败"却以为是代码问题（排查时被坑过）。all 必须含它们。
  cd "$APP" || return 1
  set -a; . ./.env; set +a
  export PATH="/d/Apps/MySQL/mysql-8.0.45-winx64/bin:$PATH"
  echo "[tripPlanner] 启动…"
  kill_port 8085
  nohup java --enable-preview -Xms256m -Xmx256m \
    -jar run/tripPlanner_agent.jar > logs/trip_gitee.log 2>&1 &
  echo "[routeMaking] 启动…"
  kill_port 8082
  nohup java --enable-preview -Xms256m -Xmx256m \
    -jar run/routeMaking_agent.jar > logs/route_gitee.log 2>&1 &
  wait_port 8085 120 && wait_port 8082 120
  curl -s -m 6 http://localhost:8085/health | head -c 160; echo
  curl -s -m 6 http://localhost:8082/health | head -c 160; echo
}

case "${1:-manager}" in
  nacos)   start_nacos ;;
  manager) start_manager ;;
  agents)  start_agents ;;
  all)     start_nacos && start_agents && start_manager ;;
  *) echo "用法: bash scripts/restart.sh manager|nacos|agents|all"; exit 2 ;;
esac

echo "=== 端口总览 ==="
for p in 8848 8081 8082 8085; do
  echo -n "$p=$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://localhost:$p/ 2>/dev/null) "
done; echo
