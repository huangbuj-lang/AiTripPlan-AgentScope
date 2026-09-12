#!/bin/bash
# ★2026-09-11 服务守护脚本（评审点：13:16 三服务同时消失、无人拉起；此前清 java 进程还误杀过 Nacos）。
#
# 用法：
#   ./scripts/watchdog.sh            # 单次体检 + 拉起不健康的服务
#   ./scripts/watchdog.sh --watch    # 持续守护（每 60s 一轮，Ctrl+C 退出）
#   ./scripts/watchdog.sh --watch 30 # 自定义间隔秒数
#
# 判断依据：各服务的 /health 端点（200 = 健康），而不是进程名 —— 避免误杀/误判其他 java 进程（如 Nacos）。
# 依赖顺序：Nacos(8848) → 子Agent(8082/8085) → 主管(8081)，逐个拉起后等端口就绪再下一个。

cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
set -a; [ -f .env ] && source .env; set +a
export PATH="D:/Apps/MySQL/mysql-8.0.45-winx64/bin:$PATH"

JAVA_OPTS="--enable-preview -Xms256m -Xmx256m"
NACOS_START="D:/Apps/Nacos/nacos/bin/startup.cmd -m standalone"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 等待端口返回预期 HTTP 码（默认等 /health 200），最多 wait 秒
wait_health() {
  local port="$1" want="$2" wait="${3:-60}" i=0
  while [ "$i" -lt "$wait" ]; do
    code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://localhost:${port}/health" 2>/dev/null)
    [ "$code" = "$want" ] && return 0
    sleep 2; i=$((i + 2))
  done
  return 1
}

port_code() { curl -s -m 5 -o /dev/null -w '%{http_code}' "http://localhost:$1/health" 2>/dev/null; }

start_agent() {
  local port="$1" jar="$2" logf="$3" name="$4"
  if [ "$(port_code "$port")" = "200" ]; then
    log "$name 健康 (:$port)"
    return 0
  fi
  log "$name 不健康 (:$port 返回 '$(port_code "$port")')，正在拉起…"
  nohup java $JAVA_OPTS -jar "$jar" > "$logf" 2>&1 &
  if wait_health "$port" 200 90; then
    log "$name 已恢复 (:$port)"
  else
    log "⚠️ $name 拉起后仍未健康，请查看 $logf"
  fi
}

check_nacos() {
  # Nacos 控制台 8080；不健康则按标准方式拉起（standalone）
  code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://localhost:8080/" 2>/dev/null)
  if [ "$code" = "200" ] || [ "$code" = "302" ]; then
    log "Nacos 健康 (:8080)"
    return 0
  fi
  log "Nacos 不健康 (:$8080 返回 '$code')，正在拉起…"
  (cd /d/Apps/Nacos/nacos/bin && cmd //c startup.cmd -m standalone >/dev/null 2>&1) &
  sleep 25
  log "Nacos 拉起等待结束（:8080 返回 '$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://localhost:8080/ 2>/dev/null)'）"
}

run_once() {
  log "===== 守护体检：$ROOT ====="
  check_nacos
  start_agent 8082 "run/routeMaking_agent.jar" "logs/route_gitee.log" "路线Agent"
  start_agent 8085 "run/tripPlanner_agent.jar" "logs/trip_gitee.log" "行程Agent"
  start_agent 8081 "run/manager_agent.jar" "logs/manager_gitee.log" "主管服务(前端)"
  log "===== 体检结束 ====="
}

case "$1" in
  --watch)
    interval="${2:-60}"
    log "进入持续守护模式，间隔 ${interval}s（Ctrl+C 退出）"
    while true; do run_once; sleep "$interval"; done
    ;;
  *)
    run_once
    ;;
esac
