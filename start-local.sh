#!/usr/bin/env bash
# =============================================================
# AiTripPlan-AgentScope 本地一键启动脚本（Windows git-bash / Linux/macOS 通用）
# 用法：
#   bash start-local.sh            # 启动全部（Nacos → 路线 → 行程 → 主管）
#   bash start-local.sh stop       # 停止全部（只杀本项目进程）
#   bash start-local.sh status     # 查看运行状态
#
# 开箱即用（自动探测/交互，无需改脚本）：
#   1. Nacos：自动探测常见路径；也可用环境变量 NACOS_HOME 指定
#   2. MySQL 密码：未设置 RDS_PASSWORD 时交互式询问
#   3. API Key：读项目根目录 .env 或环境变量 DASHSCOPE_API_KEY
#
# 前置要求：
#   1. 已打包：mvn clean package -DskipTests
#   2. 已初始化数据库：mysql -u root -p < database/schema.sql
#   3. 已安装 Nacos（3.x）和 MySQL，MySQL 服务已启动
# =============================================================
set -u

# ---------- 可配置项 ----------
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="${PROJECT_DIR}/logs"
# 本地数据库（默认 root；可改环境变量覆盖）
RDS_HOST="${RDS_HOST:-127.0.0.1:3306}"
RDS_USER="${RDS_USER:-root}"

# ---------- API Key：只从项目根目录 .env 文件读取（不走系统环境变量） ----------
# （stop/status 不需要 key，先处理这两个命令）
if [ "${1:-}" = "stop" ] || [ "${1:-}" = "status" ]; then
    : # 跳过 key 检查
else
    if [ -f "${PROJECT_DIR}/.env" ]; then
        set -a; source "${PROJECT_DIR}/.env"; set +a
    fi
    if [ -z "${DASHSCOPE_API_KEY:-}" ]; then
        echo "❌ 未找到 API Key！请在项目根目录创建 .env 文件，内容："
        echo "   DASHSCOPE_API_KEY=你的key"
        exit 1
    fi
fi

mkdir -p "$LOG_DIR"

# ---------- 工具函数 ----------
is_listening() {  # $1=端口
    # netstat 行形如: TCP 0.0.0.0:8848 0.0.0.0:0 LISTENING 7652（端口在 LISTENING 前面）
    netstat -ano 2>/dev/null | grep -qE ":${1}\b.*LISTENING" || \
    curl -s -o /dev/null -m 2 "http://localhost:${1}/" 2>/dev/null
}

wait_port() {  # $1=端口 $2=名称 $3=最大等待秒
    local i
    for i in $(seq 1 "$3"); do
        is_listening "$1" && { echo "  ✅ $2 就绪 (${i}s)"; return 0; }
        sleep 1
    done
    echo "  ❌ $2 未就绪（等 $3 秒超时）"
    return 1
}

# 查找 Nacos 安装目录：环境变量 NACOS_HOME 优先，其次自动探测常见路径
find_nacos() {
    if [ -n "${NACOS_HOME:-}" ]; then
        [ -f "$NACOS_HOME/target/nacos-server.jar" ] && { echo "$NACOS_HOME"; return 0; }
        echo "⚠️  NACOS_HOME=$NACOS_HOME 下找不到 nacos-server.jar" >&2
    fi
    # 常见安装位置（Windows / Linux / macOS / 用户目录）
    local candidates=(
        "D:/Software/nacos" "/d/Software/nacos" "C:/nacos" "/c/nacos"
        "/opt/nacos" "/usr/local/nacos" "$HOME/nacos" "$HOME/software/nacos"
    )
    local c
    for c in "${candidates[@]}"; do
        if [ -f "$c/target/nacos-server.jar" ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

# ---------- stop ----------
if [ "${1:-}" = "stop" ]; then
    echo "🛑 停止本项目的 Agent + Nacos（只杀本项目进程，不影响其他 Java 程序）..."
    # Windows：按命令行包含 jar 名精确匹配；Linux/macOS：pkill -f
    if command -v powershell >/dev/null 2>&1; then
        for p in manager_agent routeMaking_agent tripPlanner_agent; do
            powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match '$p' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null && echo "  ⛔ $p" || true
        done
        powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'nacos-server' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null && echo "  ⛔ nacos-server" || true
    else
        for p in manager_agent routeMaking_agent tripPlanner_agent; do
            pkill -f "$p" 2>/dev/null && echo "  ⛔ $p" || true
        done
        pkill -f "nacos-server" 2>/dev/null && echo "  ⛔ nacos-server" || true
    fi
    echo "✅ 已停止（只杀本项目进程）"
    exit 0
fi

# ---------- status ----------
if [ "${1:-}" = "status" ]; then
    echo "📊 运行状态："
    for pair in "8848 Nacos" "8082 路线Agent" "8085 行程Agent" "8081 主管Agent"; do
        port="${pair%% *}"; name="${pair##* }"
        is_listening "$port" && echo "  ✅ ${name} (${port}) 运行中" || echo "  ⛔ ${name} (${port}) 未运行"
    done
    exit 0
fi

# ---------- 1. Nacos（自动探测） ----------
if is_listening 8848; then
    echo "✅ Nacos 已在运行（跳过启动）"
else
    NACOS_HOME="$(find_nacos)"
    if [ -z "$NACOS_HOME" ]; then
        echo "❌ 未找到 Nacos！请先下载解压 Nacos 3.x，然后："
        echo "   方法1：export NACOS_HOME=\"你的nacos路径\""
        echo "   方法2：把 nacos 放到以下任一位置：D:/Software/nacos、C:/nacos、/opt/nacos、~/nacos"
        exit 1
    fi
    echo "🚀 启动 Nacos（$NACOS_HOME）..."
    nohup java -Dnacos.standalone=true -Dnacos.home="$NACOS_HOME" \
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
    wait_port 8848 "Nacos" 90 || exit 1
fi

# ---------- 2. 打包检查 ----------
# MSYS 路径(/c/...) Windows java 不认，统一转成 C:/ 格式
PROJECT_DIR_WIN="$(cygpath -m "$PROJECT_DIR" 2>/dev/null || echo "$PROJECT_DIR")"
JAR() { ls "$PROJECT_DIR/backend/$1/target/$1-1.0-SNAPSHOT.jar" 2>/dev/null; }
if [ -z "$(JAR routeMaking_agent)" ] || [ -z "$(JAR tripPlanner_agent)" ] || [ -z "$(JAR manager_agent)" ]; then
    echo "❌ 缺少打包产物，先执行: cd $PROJECT_DIR && mvn clean package -DskipTests"
    exit 1
fi

# ---------- 3. 启动 Agent（顺序：路线 → 行程 → 主管） ----------
start_agent() {  # $1=模块 $2=端口 $3=名称
    if is_listening "$2"; then
        echo "✅ ${3} 已在运行（跳过）"
        return 0
    fi
    echo "🚀 启动 ${3}（$2）..."
    nohup java --enable-preview -Xms256m -Xmx256m -jar \
        "$PROJECT_DIR_WIN/backend/$1/target/$1-1.0-SNAPSHOT.jar" > "$LOG_DIR/$1.log" 2>&1 &
    wait_port "$2" "$3" 90 || return 1
}

export DASHSCOPE_API_KEY
start_agent routeMaking_agent 8082 "路线Agent" || exit 1
start_agent tripPlanner_agent 8085 "行程Agent" || exit 1

# 主管需要数据库：RDS_PASSWORD 未设置时交互式询问（不写死在脚本里）
if [ -z "${RDS_PASSWORD:-}" ]; then
    echo "💬 主管 Agent 需要连接本地 MySQL（$RDS_HOST，用户 $RDS_USER）"
    read -r -s -p "    请输入 MySQL 密码: " RDS_PASSWORD
    echo
    [ -z "$RDS_PASSWORD" ] && { echo "❌ 密码不能为空，启动中止"; exit 1; }
fi
export RDS_HOST RDS_USER RDS_PASSWORD
start_agent manager_agent 8081 "主管Agent" || exit 1

echo
echo "=========================================="
echo "🎉 全部启动完成！"
echo "   项目页面: http://localhost:8081"
echo "   Nacos控制台: http://localhost:8080  (nacos/nacos)"
echo "   日志目录: $LOG_DIR"
echo "   冒烟测试: bash tests/smoke-test.sh"
echo "=========================================="
