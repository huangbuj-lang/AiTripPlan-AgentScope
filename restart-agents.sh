#!/usr/bin/env bash
# Restart only the remote agents. The manager (8081), Nacos, and MySQL are untouched.
set -u

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="${PROJECT_DIR}/logs"
mkdir -p "$LOG_DIR"

if [ -f "${PROJECT_DIR}/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "${PROJECT_DIR}/.env"
    set +a
fi

is_listening() {
    netstat -ano 2>/dev/null | grep -qE ":${1}\b.*LISTENING" || \
        curl -s -o /dev/null -m 2 "http://localhost:${1}/" 2>/dev/null
}

wait_port() {
    local i
    for i in $(seq 1 "$2"); do
        is_listening "$1" && return 0
        sleep 1
    done
    return 1
}

wait_port_closed() {
    local i
    for i in $(seq 1 "$2"); do
        is_listening "$1" || return 0
        sleep 1
    done
    return 1
}

find_jar() {
    local module="$1"
    local configured="$2"
    local candidates=(
        "$configured"
        "${PROJECT_DIR}/backend/${module}/target/${module}-1.0-SNAPSHOT.jar"
        "${PROJECT_DIR}/agents/${module}-1.0-SNAPSHOT.jar"
    )
    local candidate
    for candidate in "${candidates[@]}"; do
        [ -n "$candidate" ] && [ -f "$candidate" ] && {
            echo "$candidate"
            return 0
        }
    done
    return 1
}

stop_agent() {
    local jar="$1"
    local jar_name
    jar_name="$(basename "$jar")"

    if command -v powershell >/dev/null 2>&1; then
        powershell -NoProfile -Command \
            "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match '$jar_name' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" \
            >/dev/null 2>&1 || true
    else
        # [x]name avoids pkill matching this shell's own command line.
        local pattern="[${jar_name:0:1}]${jar_name:1}"
        pkill -f -- "$pattern" >/dev/null 2>&1 || true
    fi
}

start_agent() {
    local jar="$1" port="$2" label="$3" permits="$4" log_name="$5"
    if is_listening "$port"; then
        echo "❌ ${label} 端口仍被占用 (${port})，未重新启动"
        return 1
    fi

    echo "🚀 启动 ${label}..."
    local jar_path="$jar"
    if command -v cygpath >/dev/null 2>&1; then
        jar_path="$(cygpath -m "$jar")"
    fi
    nohup env AMAP_SEM_PERMITS="$permits" \
        java --enable-preview -Xms256m -Xmx256m -jar "$jar_path" \
        > "${LOG_DIR}/${log_name}.log" 2>&1 &

    if wait_port "$port" 90; then
        echo "✅ ${label} 就绪 (${port})"
        return 0
    fi

    echo "❌ ${label} 启动超时 (${port})，日志：${LOG_DIR}/${log_name}.log"
    return 1
}

ROUTE_JAR="$(find_jar routeMaking_agent "${ROUTE_AGENT_JAR:-}")"
TRIP_JAR="$(find_jar tripPlanner_agent "${TRIP_AGENT_JAR:-}")"
if [ -z "${ROUTE_JAR:-}" ] || [ ! -f "$ROUTE_JAR" ]; then
    echo "❌ 找不到路线Agent jar；可设置 ROUTE_AGENT_JAR"
    exit 1
fi
if [ -z "${TRIP_JAR:-}" ] || [ ! -f "$TRIP_JAR" ]; then
    echo "❌ 找不到行程Agent jar；可设置 TRIP_AGENT_JAR"
    exit 1
fi

echo "⛔ 停止路线Agent与行程Agent（不影响8081/Nacos）..."
stop_agent "$ROUTE_JAR"
stop_agent "$TRIP_JAR"
wait_port_closed 8082 15 || true
wait_port_closed 8085 15 || true

start_agent "$ROUTE_JAR" 8082 "路线Agent" 2 "routeMaking_agent" || exit 1
start_agent "$TRIP_JAR" 8085 "行程Agent" 1 "tripPlanner_agent" || exit 1

echo "✅ 子Agent重启完成；主管Agent未重启"
