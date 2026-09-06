# AiTripPlan-AgentScope

基于 **AgentScope + A2A 协议 + Nacos + 高德地图** 的多 Agent 旅行规划系统：输入一句旅行需求，系统自动拆解任务、协调多个专家 Agent，输出详细结构化旅行计划（路线 / 景点 / 酒店 / 美食 / 天气 / 预算），支持多方案对比、日历改期、打印导出、行程分享与社区论坛。

> 慕课网《多Agent+Skills+SpringAI》课程实战项目（持续演进版）

> ⚠️ **开发状态（2026-09-06）**：当前 `develop` 分支为**未完成版**——天骨架并行定稿、REVISE 天级重编、9/6 批次等均在开发验证中，表结构待办与云端部署未完，勿按完成版使用。

---

## ✨ 功能特性

- **多 Agent 协作**：主管 Agent 拆解任务 → A2A 协议远程调用路线 Agent / 行程 Agent → 结构化整合输出
- **三域并行候选**（架构方案 §一/§八）：行程服务内并行调度 景点/酒店/餐饮 三个域专家 Agent，各返回 Top-3 候选（含来源/等级/置信度），主管全局权衡
- **增量修正**（架构方案 §十三）：结果页一句话修改（如"第三天改去四姑娘山"）→ 影响分析 → 只重跑受影响域与受影响天 → 重校验重审 → 合并，不推翻重来
- **确定性编排**：解析 → 并行 DAG（跨城：路线先行；市内：行程先行）→ 聚合，流程由 Java 决定，LLM 只负责内容
- **结构化行程**：轨迹路线 + 每日详情卡片（时间 / 景点 / 交通 / 费用）+ 概览 / 每日详情双分区 + 打印 / 导出 PDF / Word
- **多方案对比**：一次生成 3 套差异化方案（美食向 / 出片向 / 省钱向），前端胶囊切换对比，一键采用
- **高德地图**：驾车路线（多途经点）、景点定位（geocode 代理 + 目的地限定 + 150km 围栏校验防同名错配）、分享页按天路线图
- **行程编辑**：单日重排（LLM）、改期（日历联动）、保存到"我的行程"、分享链接（免登录查看）
- **实时过程展示**：事件流实时显示主管思考过程、子 Agent 调用链（调用链明细五列表格）
- **管理端控制台**：系统状态 / 任务监控 / Agent 健康 / 模型监控（熔断）/ 业务看板 / 数据管理 / 内容审核 / 公告发布 / 数据中心 / 模型与凭证配置
- **社区论坛**：发帖 / 评论 / 点赞 / 收藏 / AI 回答 / 采纳 / 举报 / 行程一键转载
- **稳定性工程**：QPS 限速、指数退避重试、熔断降级（主→备用模型）、聚合缺块自动重试、结果缓存、天气清洗（剔除 LLM 表格残留）、输入侧缺失如实标注
- **Web 化**：登录注册（BCrypt+Session）、个人中心（改密 / 出行偏好沉淀注入行程）、通知中心

## 🏗️ 系统架构

> 2026-09 架构升级：按《旅行规划 Multi-Agent 完整架构方案》落地「统一状态中心 + 三域并行候选 + 确定性校验 + 独立 Reviewer + 局部增量修正」主线。

```
┌───────────────────────────────────────────────────────┐
│ 用户浏览器 (index.html / forum.html / admin.html ...)  │
│  POST /app → 轮询 /events → GET /result                │
│  POST /app/revise（会话式增量修改：只重跑受影响部分）      │
└─────────────────────────┬─────────────────────────────┘
                          │ HTTP :8081
┌─────────────────────────▼─────────────────────────────┐
│ manager_agent  主管/编排 CEO Agent（8081）              │
│  ①需求解析强化：旅行类型(单城/跨城/环线/自驾)·日期·同行人  │
│    预算·P0~P3约束（RequirementNormalizer 兜底）          │
│  ②TravelState 统一状态中心：解析/专家/候选/约束唯一事实源  │
│    状态简报随 A2A 下发，子Agent 围绕状态决策不各自解读     │
│  ③聚合(Itinerary) ← 三域Top3候选(结构化) + 专家原文       │
│  ④ConstraintEngine 确定性校验(普通代码)：天数/预算/P0约束/ │
│    时间顺序/重复/交通时长/营业时间/酒店位置（缺数据跳过）   │
│  ⑤Reviewer 独立审查：十项检查单+质量分，REVISE→按域定向    │
│    重跑受影响Agent(不推翻重来)→再审，≤2轮                │
│  ⑥PlanScorer 六维评分(0.30/0.25/0.15/0.15/0.10/0.05)    │
│  ⑦证据链：来源/等级(S/A/B/C)/抓取时间/置信度，<0.70标注   │
│    "建议出发前确认"（后端留存 plan.evidence + 事件流）     │
└────────┬──────────────────────────────┬───────────────┘
         │ A2A 协议（经 Nacos 发现）      │
┌────────▼─────────────┐   ┌────────────▼──────────────┐
│ routeMaking_agent    │   │ tripPlanner_agent          │
│ 路线Agent（8082）     │   │ 行程门面Agent（8085）        │
│ 高德驾车/市内衔接      │   │ 内部并行三域专家Agent：       │
│                      │   │  景点/酒店/餐饮 各自工具+     │
│                      │   │  Top3候选JSON+置信度         │
│                      │   │  (query_trip_candidates 一站式│
│                      │   │   调度；query_trip 兜底)     │
└──────────────────────┘   └───────────────────────────┘
        Nacos 注册中心（8848）：Agent 名片注册与发现 + 运行配置（aitripplan-agent-config.json）
        MySQL（aitripplan 库）：用户 / 行程 / 分享 / 论坛 / 公告 / 通知 / 审核队列 + 特征知识库
```

**评测（Evals）**：`tests/evals/cases.json`（36 条典型用例：单城/跨城/环线/自驾/亲子/情侣/穷游/高预算/硬约束/特殊偏好）+ `python tests/evals/run_evals.py`，自动产出目的地符合率/天数符合率/预算符合率/需求满足率/结构完整率/耗时报告。

## 📦 项目结构

```
AiTripPlan-AgentScope/
├── 📦 backend/                # 后端大包（Java/Maven 代码）
│   ├── commons/               # 公共库（SafeModel熔断限速 / JsonUtils / Nacos / MCP 工具封装）
│   ├── manager_agent/         # 主管 Agent（8081）+ 全部 Web 接口
│   ├── routeMaking_agent/     # 路线 Agent（8082）
│   └── tripPlanner_agent/     # 行程 Agent（8085）
├── 🎨 frontend/               # 前端静态资源（打包时自动并入主管 jar）
│   ├── index.html / login.html / forum.html / share.html / profile.html / post.html ...
│   ├── admin.html             # 管理控制台
│   ├── css/ (style.css / admin.css / print.css / login.css)
│   └── js/ (app.js / admin.js / forum.js ...)
├── 📊 data-pipeline/          # 数据流水线（Python：采集/清洗/LLM 增强/客流预测/定价）
├── 🧪 tests/                  # 冒烟测试 smoke-test.sh
├── 🗄️ database/               # 数据库脚本（schema.sql）
├── ⚙️ start-local.sh          # 一键启动脚本
└── pom.xml                    # 顶层聚合 pom（4 个后端模块）
```

| 模块 | 端口 | 角色 | 职责 |
|---|---|---|---|
| `commons` | - | 公共库 | SafeModel(限速/熔断/重试) / JsonUtils / Nacos / 高德 MCP 封装 |
| `manager_agent` | 8081 | 主管 Agent | 编排 + 全部 Web/管理/论坛/分享接口 |
| `routeMaking_agent` | 8082 | 路线 Agent | 高德驾车路线 / 途经点串联 |
| `tripPlanner_agent` | 8085 | 行程 Agent | 景点/美食/住宿/天气推荐 |

## 🚀 快速开始

### 1. 环境准备

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 编译运行环境 |
| Maven | 3.6+ | 构建工具 |
| Nacos | 3.x | 服务注册中心（本地启动，8848端口），**必须** |
| MySQL | 8.x | 用户/行程/论坛等数据（可选：不用登录功能可跳过） |
| DeepSeek API Key | - | 主模型（必填，缺失启动报错） |
| 高德开放平台 Key | - | Web 服务 Key（**必填**，地图/定位功能） |

### 2. 安装并启动 Nacos（★必须先装，否则3个Agent注册失败）

```bash
# 下载 Nacos 3.x（约100MB），解压后：
cd nacos/bin
bash startup.sh -m standalone        # Linux/macOS
startup.cmd -m standalone            # Windows

# 验证（等30秒后）：
curl http://localhost:8848/nacos/v1/console/health/readiness
# {"status":"UP"} = 启动成功（控制台 http://localhost:8848/nacos 默认 nacos/nacos）
```

> ⚠️ 3 个 Agent 的 `application.yml` 里 Nacos 地址写 `localhost:8848`，**Nacos 必须和 Agent 在同一台机器**（或改配置为服务器IP）。

### 3. 建立数据库

```bash
mysql -u root -p < database/schema.sql   # 创建 aitripplan 库及全部表
# 脚本使用 IF NOT EXISTS，重复执行不会覆盖已有数据
```

连接配置由环境变量注入（默认连本地 `127.0.0.1:3306`）：

```bash
export RDS_HOST="127.0.0.1:3306"
export RDS_USER="root"
export RDS_PASSWORD="你的密码"
```

### 4. API Key 配置（★必须，否则功能缺失/启动报错）

API Key **只从项目根目录的 `.env` 文件读取**（已被 .gitignore 忽略，不会提交）。在项目根目录创建 `.env`：

```bash
# 主模型（必填）
DEEPSEEK_API_KEY=你的DeepSeek key
# 备用模型（选填，主模型熔断时自动降级）
FALLBACK_MODEL_API_KEY=你的备用key
FALLBACK_MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
FALLBACK_MODEL_NAME=qwen3.7-max

# 高德地图 Web 服务 Key（★必填，地图/定位/路线图功能）
AMAP_KEY=你的高德Web服务key

# 数据库（与第3节一致）
RDS_HOST=127.0.0.1:3306
RDS_USER=root
RDS_PASSWORD=你的密码
```

一键脚本 `bash start-local.sh` 启动时自动读取 `.env`；手动启动需先 `source .env`。

**模型说明**：主模型 `deepseek-v4-flash`（OpenAI 兼容接口）；备用模型在主模型连续失败触发熔断时自动接管，熔断 60 秒后自动试探恢复。

### 5. Nacos 运行配置（可选但推荐：管理端保存功能的落点）

管理端"保存运行配置 / 保存高德 Key"写入 Nacos 配置 `aitripplan-agent-config.json`（默认组），结构：

```json
{
  "services": { "amapMcpUrl": "...", "amapPlaceSearchUrl": "..." },
  "credentials": { "amapKey": "高德key", "amapMcpKey": "高德MCP key" },
  "tools": { "amapEnabled": true, "localKnowledgeEnabled": true, ... }
}
```

不建该配置时，高德 Key 回退读取环境变量 `AMAP_KEY`（见第4节），模型配置使用内置默认——**基础功能不受影响**，仅管理端在线保存不生效。

### 6. 打包

```bash
mvn clean package -DskipTests
```

产出 3 个 fat jar（前端静态资源自动并入主管 jar）：
- `backend/manager_agent/target/manager_agent-1.0-SNAPSHOT.jar`
- `backend/routeMaking_agent/target/routeMaking_agent-1.0-SNAPSHOT.jar`
- `backend/tripPlanner_agent/target/tripPlanner_agent-1.0-SNAPSHOT.jar`

### 7. 启动

**方案 A：一键脚本（推荐）**

```bash
bash start-local.sh          # 启动全部（Nacos → 路线 → 行程 → 主管）
bash start-local.sh stop     # 停止全部（只杀本项目进程）
bash start-local.sh status   # 查看运行状态
```

**方案 B：手动启动（顺序：Nacos → 路线 → 行程 → 主管）**

```bash
java --enable-preview -Xms256m -Xmx256m -jar backend/routeMaking_agent/target/routeMaking_agent-1.0-SNAPSHOT.jar
java --enable-preview -Xms256m -Xmx256m -jar backend/tripPlanner_agent/target/tripPlanner_agent-1.0-SNAPSHOT.jar
source .env && java --enable-preview -Xms256m -Xmx256m -jar backend/manager_agent/target/manager_agent-1.0-SNAPSHOT.jar
```

> `--enable-preview` 必须加（代码用到 Java 21 switch 模式匹配特性）。

### 8. 冒烟测试（改代码后建议跑一遍）

```bash
bash tests/smoke-test.sh
```

自动验证 7 项：端口 → 前端资源 → 注册登录 → 提交任务 → 思考过程事件流 → 最终结果 → A2A 子Agent调用。全部 ✅ = 系统正常（约 3-5 分钟）。

## 🖥️ 访问使用

| 入口 | 地址 | 说明 |
|---|---|---|
| 前台首页 | `http://localhost:8081` | 输入目的地/出行人数 → 生成行程 |
| 论坛 | `http://localhost:8081/forum.html` | 发帖/评论/点赞/AI 回答 |
| 管理端 | `http://localhost:8081/admin.html` | 管理员（观测/运营/配置） |
| 分享页 | 行程分享链接 `share/{code}` | 免登录查看 |

**生成流程**：输入如"九寨沟 3 日游" → 点【探索】→ 实时进度条（解析→路线→行程→整合）→ 结构化行程（轨迹 + 每日详情 + 概览 + 其它区块）→ 可切换 3 套方案（美食/出片/省钱）→ 日历改期 / 单日重排 / 打印 / 导出 → 保存 / 分享 / 转载到论坛。

**管理端功能**：
- 观测：系统状态（进程/注册中心）、任务监控（运行中+历史+调用链明细）、Agent 健康（A2A 成功率）、模型监控（熔断状态）、业务看板（用户/行程/论坛数据）
- 运营：数据管理（用户/行程维护）、内容审核（帖子队列）、公告发布、数据中心
- 配置：模型运行配置（主/备用模型、超时、限流熔断参数）、Agent 工具开关、高德凭证（普通 Key 热生效、MCP Key 自动重启子 Agent）、Agent 提示词在线编辑

## 🔌 HTTP 接口

### 用户 / 行程
| 接口 | 方法 | 说明 | 需登录 |
|---|---|---|---|
| `/api/register` `/api/login` `/api/logout` `/api/check-login` | POST/POST/POST/GET | 注册/登录/退出/检查 | -/否/是/否 |
| `/app` `/events` `/result` `/cancel` | POST/GET/GET/POST | 提交任务/思考事件流/获取结果/中断 | 是 |
| `/api/save-plan` `/api/my-plans` `/api/delete-plan` | POST/GET/POST | 保存行程/我的行程/删除 | 是 |
| `/api/trips/{id}/edit` | GET | 行程编辑数据（含单日明细） | 是 |
| `/api/trips/{id}` | PUT | 更新行程（改期/内容） | 是 |
| `/api/trips/{id}/regenerate-day` | POST | 单日行程 LLM 重排 | 是 |
| `/api/profile/password` `/api/profile/preferences` | PUT/GET/PUT | 改密/出行偏好（沉淀注入行程） | 是 |

### 高德地图
| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/amap/personal-map` | POST | 多天行程→多途经点驾车路线（含目的地围栏校验） |
| `/api/amap/static-map` | GET | 静态地图 |

### 分享
| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/trips/{id}/share` | POST/DELETE/GET | 生成/取消/查询分享 |
| `/api/share/{code}` | GET | 免登录查看分享行程（计浏览量） |
| `/api/share/{code}/day-map` | GET | 分享页按天生成路线图 |

### 论坛
| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/forum/boards` `/api/forum/posts` | GET/GET+POST | 版块/帖子列表（hot=点赞+评论排序）与发帖 |
| `/api/forum/posts/{id}` `/comments` `/like` `/favorite` | 多 | 帖子详情/评论/点赞/收藏 |
| `/api/forum/answers/ai` | POST | AI 回答 |
| `/api/forum/comments/{id}/adopt` `/reports` | POST | 采纳/举报 |
| `/api/forum/posts/{id}/copy-trip` | POST | 行程一键转载 |
| `/api/notifications/*` | GET/PUT | 通知未读数/已读 |

### 管理端（`/api/admin/*`）
| 接口 | 说明 |
|---|---|
| `GET /status` `GET /tasks` `POST /tasks/cancel` `GET /tasks/trace` | 系统状态/任务监控/中断/调用链 |
| `GET /agents/health` `GET /breaker` `GET /costs` | Agent 健康/熔断状态/成本 |
| `GET|POST /model-config` `GET|POST /agent-config` | 模型运行配置/工具开关 |
| `GET|POST /amap-credentials` | 高德凭证（写 Nacos，MCP key 触发重启 8082/8085） |
| `GET|POST /agent-prompts` | Agent 提示词在线编辑 |
| `GET|DELETE /users` `GET|DELETE /plans` | 用户/行程数据管理 |
| `GET /moderation/queue` `PUT /moderation/resolve` `POST /moderation/action` | 内容审核 |
| `GET /stats/business` | 业务看板 |

## 🔒 稳定性机制

| 机制 | 作用 |
|---|---|
| **限速器** | 每秒最多 3 个 LLM API 请求（令牌桶），防撞 RPM 限流 |
| **429/5xx 重试** | 指数退避（1s→2s→4s，最多3次） |
| **熔断降级** | 主模型连续失败（10秒5次）或认证错误 → 自动切备用模型；60秒后试探恢复 |
| **聚合缺块重试** | 结构化结果缺 sections/timeline/stay → 带缺失提示重试1次 → 仍缺降级 markdown 兜底 |
| **天气清洗** | 透传天气前剔除 LLM 输出的 markdown 表格残留（如 `\| 白天/夜间温度 \| 风力 \|`） |
| **坐标去歧义** | geocode 关键词带目的地限定 + 坐标须落在目的地中心 150km 围栏内（防"五彩池"被解析到外市同名景点） |
| **结果缓存** | 专家产物按 目的地+天数+出发地+偏好+需求指纹(预算/人数/类型/约束) 进程内缓存（PlanCache，TTL 可配），LLM 聚合每次仍执行 |
| **输入缺失如实标注** | 路线/行程数据缺失时聚合前亮红灯，结果如实标注缺失，不静默产出半成品 |
| **会话隔离** | 事件/结果按 sessionId 隔离，多窗口互不污染 |
| **任务中断** | 前端"⏹ 停止"按钮立即中断当前任务 |

## ⚠️ 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 模型只回"请稍等"不调工具 | 流式开关问题 | `SafeModel` 已默认关闭流式（stream=false） |
| Agent 启动报错"未找到 API Key" | 项目根目录无 `.env` | 创建 `.env`，配 `DEEPSEEK_API_KEY`（见第4节） |
| 地图/路线图功能失效 | 缺高德 key | `.env` 配 `AMAP_KEY`，或在管理端"高德凭证"保存 |
| 行程图景点错位（如五彩池跑到黄龙） | 高德同名景点歧义 | 已内置目的地限定+150km 围栏，重新生成即可 |
| 天气显示"\| 白天/夜间温度 \| 风力 \|" | LLM 输出表格残留 | 已内置天气清洗，重新生成即生效 |
| 管理端保存配置不生效 | Nacos 无 `aitripplan-agent-config.json` | 见第5节创建配置 |
| 保存长行程 500 | `TEXT` 字段 64KB 上限 | `ALTER TABLE trip_plans MODIFY plan_content MEDIUMTEXT;` |
| 登录/查库接口 500 | 数据库未建表/连不上 | 执行 `database/schema.sql`，检查 RDS_HOST/密码 |
| Nacos 起不来报 `module java.base does not "opens java.util"` | JDK17 模块限制 | 启动命令带 `--add-opens=java.base/java.util=ALL-UNNAMED`（一键脚本已内置） |
| 修改高德 Key 后不生效 | 只改了输入框没点保存 | 点"保存高德 Key"按钮（普通 Key 热生效） |

## 📄 License

仅供学习交流使用。
