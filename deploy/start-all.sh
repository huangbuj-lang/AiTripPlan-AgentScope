#!/bin/bash
# ========== AiTripPlan 云服务器一键启动 ==========
# 云端真实路径: /opt/aitripplan（代码 project/、jar agents/、Nacos nacos/、日志 logs/）
# 用法: 在 /opt/aitripplan 下执行  bash deploy/start-all.sh   （需先配好同目录 rds.env）
# 原理: source rds.env → 顺序启动 Nacos → 三 Agent（jar 必须带 --enable-preview，代码用 JDK17 preview 特性编译）

cd "$(dirname "$0")"
# 若从仓库 deploy/ 调用，回退到仓库根；服务器上则位于 /opt/aitripplan/deploy
BASE="$(pwd)/.."
[ -f "$BASE/rds.env" ] || { echo "❌ 未找到 $BASE/rds.env（先 cp deploy/rds.env.example rds.env 并填值）"; exit 1; }

# 加载全部密钥（RDS + 模型key + 高德key），所有 nohup 子进程继承
source "$BASE/rds.env"

echo "=== 1/4 启动 Nacos (8848) ==="
cd "$BASE/nacos/nacos/bin" 2>/dev/null || cd /opt/aitripplan/nacos/nacos/bin
bash startup.sh -m standalone
sleep 30

echo "=== 2/4 启动 路线Agent (8082) ==="
cd "$BASE/agents"
nohup java --enable-preview -Xms256m -Xmx256m -jar routeMaking_agent-1.0-SNAPSHOT.jar > "$BASE/logs/route.log" 2>&1 &
sleep 40

echo "=== 3/4 启动 行程Agent (8085) ==="
nohup java --enable-preview -Xms256m -Xmx256m -jar tripPlanner_agent-1.0-SNAPSHOT.jar > "$BASE/logs/trip.log" 2>&1 &
sleep 40

echo "=== 4/4 启动 主管Agent (8081) ==="
nohup java --enable-preview -Xms256m -Xmx256m -jar manager_agent-1.0-SNAPSHOT.jar > "$BASE/logs/manager.log" 2>&1 &
sleep 40

echo ""
echo "======================================"
echo "🎉 全部启动完成！浏览器访问 http://<服务器IP>:8081"
echo "======================================"
