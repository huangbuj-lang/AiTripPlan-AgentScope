#!/usr/bin/env bash
# ==========================================================
# AiTripPlan 一键后台启动（不需要交互式询问密码）
# ==========================================================
set -u
PROJECT_DIR="E:/gitee/aitripplan-agentscope"
cd "$PROJECT_DIR" || exit 1
mkdir -p logs
set -a; source .env; set +a
# RDS_PASSWORD 已在 .env 里提供，不需交互

LOG_DIR="$PROJECT_DIR/logs"

is_listening() {
    netstat -ano 2>/dev/null | grep -qE ":${1}\b.*LISTENING"
}

wait_port() {
    local i
    for i in $(seq 1 "$3"); do
        is_listening "$1" && { echo "  ✅ $2 就绪 (${i}s)"; return 0; }
        sleep 1
    done
    echo "  ❌ $2 未就绪（等 $3 秒超时）"
    return 1
}

# 1. 启动 Nacos
NACOS_HOME="${NACOS_HOME:-D:/Apps/Nacos/nacos}"
if is_listening 8848; then
    echo "  ♻️  Nacos 已在 8848 端口"
else
    if [ ! -f "$NACOS_HOME/target/nacos-server.jar" ]; then
        echo "❌ Nacos jar 未找到：$NACOS_HOME/target/nacos-server.jar"
        exit 1
    fi
    echo "▶ 启动 Nacos: $NACOS_HOME"
    nohup java -Dnacos.standalone=true \
        -Dnacos.home="$NACOS_HOME" \
        -Dloader.path="$NACOS_HOME/plugins,$NACOS_HOME/plugins/health,$NACOS_HOME/plugins/cmdb,$NACOS_HOME/plugins/selector" \
        -Dnacos.deployment.type=merged \
        --add-opens=java.base/java.lang=ALL-UNNAMED \
        --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
        --add-opens=java.base/java.util=ALL-UNNAMED \
        --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
        --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
        -jar "$NACOS_HOME/target/nacos-server.jar" \
        "--spring.config.additional-location=file:$NACOS_HOME/conf/" \
        "--logging.config=file:$NACOS_HOME/conf/nacos-logback.xml" \
        > "$LOG_DIR/nacos.log" 2>&1 &
    echo $! > "$LOG_DIR/nacos.pid"
    wait_port 8848 "Nacos" 90 || exit 1
fi

# 2. 启动 3 个 Agent
start_agent() {
    local name=$1 port=$2 label=$3
    if is_listening "$port"; then
        echo "  ♻️  $label 已在 $port 端口"
        return 0
    fi
    echo "▶ 启动 $label :$port"
    nohup java -jar "$PROJECT_DIR/backend/${name}/target/${name}-1.0-SNAPSHOT.jar" \
        > "$LOG_DIR/${name}.log" 2>&1 &
    echo $! > "$LOG_DIR/${name}.pid"
    wait_port "$port" "$label" 60 || return 1
}

start_agent routeMaking_agent 8082 "路线Agent" || exit 1
start_agent tripPlanner_agent 8085 "行程Agent" || exit 1
start_agent manager_agent 8081 "主管Agent" || exit 1

echo ""
echo "=========================================="
echo "  ✅  全部服务已启动"
echo "  项目页面: http://localhost:8081"
echo "  Nacos 控制台: http://localhost:8080 (nacos/nacos)"
echo "  日志: $LOG_DIR/*.log"
echo "=========================================="
