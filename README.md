# AiTripPlan-AgentScope

基于 **AgentScope + A2A 协议 + Nacos** 的多 Agent 旅行规划系统：输入一句旅行需求，系统自动拆解任务、协调多个专家 Agent，输出详细旅行计划（路线 / 景点 / 酒店 / 美食 / 天气 / 预算）。

> 慕课网《多Agent+Skills+SpringAI》课程实战项目

---

## ✨ 功能特性

- **多 Agent 协作**：主管 Agent 拆解任务 → A2A 协议远程调用路线 Agent / 行程 Agent → 整合输出
- **Skills 技能体系**：主管挂载"操作手册"（禁止跳步/编造/只写总结），子 Agent 挂载景点推荐/表格制作手册
- **实时过程展示**：前端轮询 `/events` 实时显示主管的思考过程与工具调用
- **百度地图 MCP**：自驾路线、天气、地点检索等 10 个地图能力（直连魔塔 MCP）
- **Web 化**：登录注册（BCrypt+Session）、行程保存到 MySQL（我的行程/删除）

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────┐
│ 用户浏览器 (index.html / login.html)          │
│  POST /app → 轮询 /events → GET /result      │
└──────────────────┬───────────────────────────┘
                   │ HTTP :8081
┌──────────────────▼───────────────────────────┐
│ manager_agent  主管 Agent（8081）             │
│  ReActAgent + PlanNotebook + Skills + Hook    │
│  工具：RemoteAgentTool（A2A远程调用）          │
└───────┬──────────────────────────┬───────────┘
        │ A2A 协议（经 Nacos 发现） │
┌───────▼─────────────┐  ┌────────▼────────────┐
│ routeMaking_agent   │  │ tripPlanner_agent   │
│ 路线Agent（8082）    │  │ 行程Agent（8085）    │
│ BaiduMapTool×10     │  │ 子Agent:景点推荐    │
└─────────────────────┘  └─────────────────────┘
        Nacos 注册中心（8848）：Agent 名片注册与发现
```

## 📦 项目结构

```
AiTripPlan-AgentScope/
├── 📦 backend/                # 后端大包（Java/Maven 代码）
│   ├── commons/               # 公共库（AgentUtils / ToolUtils / NacosUtil / LimitedMemory）
│   ├── manager_agent/         # 主管 Agent（8081）
│   ├── routeMaking_agent/     # 路线 Agent（8082）
│   └── tripPlanner_agent/     # 行程 Agent（8085）
├── 🎨 frontend/               # 前端大包（静态资源，打包时自动并入主管 jar）
│   ├── index.html / login.html
│   ├── css/ (style.css / print.css / login.css)
│   └── js/ (app.js / login.js)
├── 🧪 tests/                  # 测试相关（冒烟测试 smoke-test.sh）
├── 🗄️ database/               # 数据库脚本（schema.sql）
├── ⚙️ start-local.sh          # 一键启动脚本
└── pom.xml                    # 顶层聚合 pom（4 个后端模块）
```

| 模块 | 端口 | 角色 | 职责 |
|---|---|---|---|
| `commons` | - | 公共库 | AgentUtils(建Agent) / ToolUtils(装工具) / NacosUtil(注册中心) / LimitedMemory(限量记忆) |
| `manager_agent` | 8081 | 主管 Agent | 收请求→拆任务→A2A调远程Agent→整合；登录/行程保存 |
| `routeMaking_agent` | 8082 | 路线 Agent | 百度地图工具：自驾路线/天气/地点检索 |
| `tripPlanner_agent` | 8085 | 行程 Agent | 景点/美食/住宿推荐（含景点推荐子Agent） |

## 🚀 快速开始

### 1. 环境准备

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 编译运行环境 |
| Maven | 3.6+ | 构建工具 |
| Nacos | 3.x | 服务注册中心（本地启动，8848端口） |
| MySQL | 8.x | 登录/行程保存（可选：不用登录功能可跳过） |
| DashScope API Key | - | 阿里云百炼（通义千问 qwen3-max），**必须配置**（项目根目录 .env 文件，见第4节） |

### 2. 安装并启动 Nacos（★必须先装，否则3个Agent注册失败）

本项目3个Agent通过 Nacos 互相发现（主管在 Nacos 查"路线Agent/行程Agent"的名片再调用），**没有 Nacos 整个系统跑不起来**。

**① 下载 Nacos 3.x（约100MB）**

```bash
# Linux/macOS 直接下载
wget https://github.com/alibaba/nacos/releases/download/3.1.0/nacos-server-3.1.0.tar.gz
tar -zxvf nacos-server-3.1.0.tar.gz

# Windows 到官网手动下载：https://github.com/alibaba/nacos/releases
# 解压后得到 nacos/ 目录
```

> 下载慢/被墙时：用国内镜像 `https://mirrors.aliyun.com/` 搜 nacos，或本地 Maven 仓库已有则直接复制。

**② 启动 Nacos（单机模式）**

```bash
# Linux/macOS
cd nacos/bin
bash startup.sh -m standalone

# Windows
cd nacos\bin
startup.cmd -m standalone
```

**③ 验证 Nacos 已启动**（等30秒后）：

```bash
curl http://localhost:8848/nacos/v1/console/health/readiness
# 返回 {"status":"UP"} = 启动成功

# 或访问控制台（默认账号密码 nacos/nacos）
# http://localhost:8848/nacos
```

**④ 停止 Nacos**：

```bash
# Linux/macOS
cd nacos/bin && bash shutdown.sh
# Windows
cd nacos\bin && shutdown.cmd
```

> ⚠️ 注意：3个 Agent 的 `application.yml` 里 Nacos 地址写的是 `localhost:8848`，**Nacos 必须和 Agent 在同一台机器**（或改配置为服务器IP）。

### 3. 本地数据库建立自动脚本及连接配置

**① 建立数据库和表**：在项目根目录执行 `database/schema.sql`，自动创建 `aitripplan` 库及 `users` / `trip_plans` 表：

```bash
# Linux/macOS
mysql -u root -p < database/schema.sql

# Windows (cmd)
mysql -u root -p < database/schema.sql

# Windows (git-bash) 或密码不想交互输入：
export MYSQL_PWD='你的密码'
mysql -u root -h 127.0.0.1 < database/schema.sql
```

验证是否建好：

```bash
mysql -u root -p -e "SHOW TABLES FROM aitripplan;"
# 应看到 users、trip_plans 两张表
```

> 脚本使用 `IF NOT EXISTS`，重复执行不会报错、不会覆盖已有数据，可放心多次执行。

**② 连接配置**：`backend/manager_agent/src/main/resources/application.yml` 通过环境变量注入连接信息，默认连本地库（`127.0.0.1:3306`），建好库后直接启动即可自动对接：

```bash
export RDS_HOST="127.0.0.1:3306"      # 本地库：127.0.0.1:3306
export RDS_USER="root"
export RDS_PASSWORD="你的密码"
```

### 4. API Key（★必须配置，否则 Agent 启动报错）

API Key **只从项目根目录的 `.env` 文件读取**（不走系统环境变量，代码里无内置 key）：

在项目根目录创建 `.env` 文件（此文件已被 .gitignore 忽略，不会提交到 git）：

```bash
# 主模型：DeepSeek（必填，缺失启动报错）
DEEPSEEK_API_KEY=你的DeepSeek key
# 备用模型：Qwen/通义千问（选填，主模型熔断时自动降级用；不填则熔断后任务报错）
DASHSCOPE_API_KEY=你的DashScope key
```

一键启动脚本 `bash start-local.sh` 启动时会自动读取该文件；手动启动则需先 `source .env` 再运行 Agent。

**模型说明**：
- 主模型 `deepseek-v4-flash`（OpenAI 兼容接口，快、支持工具调用）
- 备用模型 `qwen3.7-max`：当 DeepSeek **连续失败触发熔断**（如 key 失效/服务端故障）时自动降级，熔断 60 秒后自动试探恢复

### 5. 打包

```bash
mvn clean package -DskipTests
```

产出 3 个 fat jar（含全部依赖，前端资源自动并入主管 jar）：
- `backend/manager_agent/target/manager_agent-1.0-SNAPSHOT.jar`
- `backend/routeMaking_agent/target/routeMaking_agent-1.0-SNAPSHOT.jar`
- `backend/tripPlanner_agent/target/tripPlanner_agent-1.0-SNAPSHOT.jar`

### 6. 启动

**方案 A：一键脚本（推荐）**

```bash
# 需要 Nacos 和 MySQL 已就绪；API Key 从项目根目录 .env 文件读取
bash start-local.sh          # 启动全部（Nacos → 路线 → 行程 → 主管）
bash start-local.sh stop     # 停止全部（只杀本项目进程）
bash start-local.sh status   # 查看运行状态
```

> 💡 脚本开箱即用：Nacos 自动探测常见路径（也可 `NACOS_HOME=路径` 指定）；MySQL 密码未设置时交互式询问；API Key 只读项目根目录 `.env` 文件。

**方案 B：手动启动（顺序：Nacos → 路线 → 行程 → 主管）**

```bash
# ① 启动 Nacos（Agent 要注册名片，必须先起）
cd nacos/bin && bash startup.sh -m standalone

# ② 路线 Agent（8082）
java --enable-preview -Xms256m -Xmx256m -jar backend/routeMaking_agent/target/routeMaking_agent-1.0-SNAPSHOT.jar

# ③ 行程 Agent（8085）（等路线 Agent 起来后再启）
java --enable-preview -Xms256m -Xmx256m -jar backend/tripPlanner_agent/target/tripPlanner_agent-1.0-SNAPSHOT.jar

# ④ 主管 Agent（8081）（等行程 Agent 起来后再启）
source rds.env   # 如配置了数据库
java --enable-preview -Xms256m -Xmx256m -jar backend/manager_agent/target/manager_agent-1.0-SNAPSHOT.jar
```

> `--enable-preview` 必须加（代码用到 Java 21 switch 模式匹配特性）。

### 7. 访问使用

浏览器打开 `http://localhost:8081` → 注册/登录 → 输入任务（如"深圳到惠州3日游自驾游计划"）→ 点【生成计划】→ 实时查看思考过程 → 得到完整计划。

### 8. 命令行直跑模式（测试用，不启 Web）

```bash
java --enable-preview -jar backend/manager_agent/target/manager_agent-1.0-SNAPSHOT.jar --run-now
```

### 9. 冒烟测试（改代码后建议跑一遍）

```bash
bash tests/smoke-test.sh
```

自动验证 7 项：端口 → 前端资源 → 注册登录 → 提交任务 → 思考过程事件流 → 最终结果 → A2A 子Agent调用。全部 ✅ = 系统正常（约 3-5 分钟）。

## 🔌 HTTP 接口

| 接口 | 方法 | 说明 | 需登录 |
|---|---|---|---|
| `/api/register` | POST | 注册（username/password） | 否 |
| `/api/login` | POST | 登录（写入 Session） | 否 |
| `/api/check-login` | GET | 检查登录态 | 否 |
| `/api/logout` | POST | 退出登录 | 是 |
| `/app` | POST | 提交任务（`?prompt=任务描述&sessionId=会话ID`，异步） | 是 |
| `/events` | GET | 轮询主管思考过程（`?sessionId=` 按会话隔离） | 是 |
| `/result` | GET | 获取最终计划（`?sessionId=` 按会话隔离） | 是 |
| `/cancel` | POST | 中断当前任务（`?sessionId=`，前端"停止"按钮调用） | 是 |
| `/api/save-plan` | POST | 保存行程到数据库 | 是 |
| `/api/my-plans` | GET | 我的行程列表 | 是 |
| `/api/delete-plan` | POST | 删除行程（`?id=`） | 是 |

## 🔒 稳定性机制（2026-08-12 新增）

系统内置 4 层 API 防护，无需手动干预：

| 机制 | 作用 | 配置 |
|------|------|------|
| **限速器** | 每秒最多 3 个 API 请求（令牌桶），防止撞 RPM 限流 | `SafeModel.java` 内 `RateLimiter.create(3)` |
| **429 重试** | 限流/5xx 指数退避重试（1s→2s→4s，最多3次），抖动自动消化 | `SafeModel.java` `MAX_RETRIES=3` |
| **熔断降级** | DeepSeek 连续失败（10秒内5次）或认证错误 → 自动切 Qwen；60秒后自动试探恢复 | `CircuitBreaker.java`（阈值/冷却可调） |
| **会话隔离** | 事件/结果按 sessionId 隔离，多窗口互不污染 | 前端 localStorage 自动生成，无需配置 |

**多线程模式**（默认关闭，保持"一次一个任务"）：
```bash
# 开启多线程并发（2=同时最多2个任务；先确认 DeepSeek 账号配额）
MAX_CONCURRENT_TASKS=2 bash start-local.sh
```

**任务中断**：任务执行中前端出现红色"⏹ 停止"按钮，点击即中断当前任务（不等排队，马上可提交新任务）。

## ⚠️ 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 模型只回"请稍等"不调工具 | 流式开关问题 | `DashScopeChatModel.builder().stream(false)`（代码已默认关闭） |
| 第二次请求起 500，`FileSystemAlreadyExistsException` | fat jar 里 zipfs 重复挂载 | Skills 仓库已改静态单例（代码已修复） |
| 保存长行程 500 | `TEXT` 字段 64KB 上限 | `ALTER TABLE trip_plans MODIFY plan_content MEDIUMTEXT;` |
| 登录/查库接口 500 | 数据库未建表/连不上 | 执行 `database/schema.sql`，检查 RDS_HOST/密码 |
| 登录接口报参数名错误 | 编译未开 `-parameters` | pom 已配置 `<parameters>true</parameters>` |
| **Agent 启动报错 "未找到 API Key"** | 代码无内置 key，且项目根目录无 .env 文件 | 在项目根目录创建 `.env`，内容 `DEEPSEEK_API_KEY=你的key`（见第4节） |
| **行程Agent启动失败 `Skill directory does not exist: Make-A-Table`** | `tripPlanner_agent/src/main/resources/skills/Make-A-Table` 目录缺失 | 从 demo 备份（如 `AiTripPlan-AgentScope-demo3/`）整个复制 `Make-A-Table` 目录到主项目，重新打包 |
| **Nacos 起不来，报 `module java.base does not "opens java.util"`** | JDK17 模块系统限制 | 启动命令必须带 `--add-opens=java.base/java.util=ALL-UNNAMED`（一键脚本已内置） |
| **思考过程区空白（/events 只有 Agent结果）** | planHook 的 EventStore 事件被删 | 恢复 gitee 原始版 planHook.java（含 EventStore.add 四处） |

## 📄 License

仅供学习交流使用。
