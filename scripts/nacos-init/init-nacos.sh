#!/bin/bash
# Nacos AI 注册中心一键初始化（幂等可重跑）：20 提示词 + 2 技能 + 2 代理
# 用法: bash scripts/nacos-init/init-nacos.sh [NACOS_ADDR] [USER] [PASS]
#   默认 localhost:8848 nacos/nacos；远程示例: bash scripts/nacos-init/init-nacos.sh 8.156.73.44:8848 nacos nacos
cd "$(dirname "$0")/../.."
ADDR="${1:-localhost:8848}"
USER="${2:-nacos}"
PASS="${3:-nacos}"
echo "=== 初始化 Nacos AI 注册中心 ($ADDR) ==="
python3 scripts/nacos-init/init_nacos.py --addr "$ADDR" --user "$USER" --pass "$PASS"
