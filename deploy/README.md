# AiTripPlan 云服务器部署指南（阿里云 ECS）

> 本目录是**部署资产**（可复现配置），不替代代码。服务器实际运行路径 `/opt/aitripplan`。

## 目录结构（服务器）
```
/opt/aitripplan/
├── project/          # 代码（backend/frontend/tests…，9/1 从 Gitee 拉的副本，非 git 仓库）
├── agents/           # 构建产物 jar（routeMaking/tripPlanner/manager _agent-1.0-SNAPSHOT.jar）
├── nacos/nacos/      # Nacos 3.2.4 单机
├── logs/             # route.log / trip.log / manager.log / nacos.log
├── rds.env           # 密钥环境变量（不入 git，从 deploy/rds.env.example 复制填值）
├── start-all.sh      # 一键启动（仓库 deploy/start-all.sh 的服务器版）
└── stop-all.sh       # pkill 全部服务
```

## 环境要求
- **JDK 17**（ECS 上 openjdk 17.0.x；jar 用 `--enable-preview` 编译/运行）
- 代码仓库根 pom 与模块 pom 的编译配置是 **JDK25 版**（source/target 25 + `-proc:full`）——
  在 JDK17 服务器上**自行 mvn 构建前**需把 source/target 临时改为 17 并去掉 `-proc:full`
  （服务器 9/1 的手工改动即此适配，属环境差异，未回推代码库以免破坏 JDK25 构建）。
  线上部署用的是**预构建 jar**（`java -jar` 直接跑），不依赖服务器编译。

## 首次部署步骤
```bash
# 1. 代码与构建产物
mkdir -p /opt/aitripplan/{agents,logs}
#   project/ 放代码副本；jar 拷入 agents/（在 JDK17 环境构建：mvn -DskipTests package，各模块 cd 后分别打）

# 2. 密钥配置（真实值绝不提交 git）
cp deploy/rds.env.example /opt/aitripplan/rds.env
vi /opt/aitripplan/rds.env        # 填 RDS/模型/高德 key/SKILL_NAMES

# 3. Nacos AI 注册中心一键初始化（幂等：20 提示词 + 2 技能 + 2 代理）
bash scripts/nacos-init/init-nacos.sh 8.156.73.44:8848 nacos nacos

# 4. 启动（Nacos 先起，Agent 注册名片需要它）
cd /opt/aitripplan && bash deploy/start-all.sh

# 5. 验证
tail -f /opt/aitripplan/logs/route.log      # 期待: ★Skill 已接线: 2 个skill
curl http://localhost:8081/                 # 网页 200
bash tests/smoke-test.sh                    # 全链路 15/15
```

## 关键配置清单（rds.env 变量）
| 变量 | 用途 | 备注 |
|---|---|---|
| RDS_HOST/USER/PASSWORD | 云 RDS（aitripplan 库） | 内网 endpoint 去 `rw-` 前缀 |
| DEEPSEEK_API_KEY / MODEL_* | java 主模型 | api.deepseek.com + deepseek-v4-flash |
| AMAP_KEY / AMAP_MCP_KEY | 高德采集 + MCP 查询 | 两值相同 |
| DASHSCOPE_API_KEY | fallback qwen（可选） | 空则跳过 fallback |
| SKILL_NAMES / SKILL_VERSION | 子 Agent 技能接线 | 与 Nacos 发布版本一致 |
| BAIDU_MCP_URL | 可选百度 MCP | 无则留空 |

## 已知环境差异（勿覆盖代码）
- 服务器 pom = JDK17 适配；GitHub develop pom = JDK25 适配 → **不要互相覆盖**，构建用各自环境
- 服务器 `project/` 落后 develop（9/1 拉取）→ 需要新功能时重新部署新 jar，不回推旧代码
- 网页登录: atestuser / test123456（admin）
