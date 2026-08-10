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

## 📦 模块说明

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
| DashScope API Key | - | 阿里云百炼（通义千问 qwen3-max） |

### 2. 初始化数据库（可选）

执行 `database/schema.sql` 创建 `aitripplan` 库及 `users` / `trip_plans` 表。

### 3. 配置 API Key（必须）

方式一（推荐）：设置环境变量

```bash
# Linux/macOS
export DASHSCOPE_API_KEY="你的key"
# Windows
set DASHSCOPE_API_KEY=你的key
```

方式二：修改 `commons/src/main/java/utils/AgentUtils.java` 中的兜底 key（⚠️ 请勿提交真实 key 到公开仓库）。

### 4. 数据库连接配置（可选）

`manager_agent/src/main/resources/application.yml` 通过环境变量注入：

```bash
export RDS_HOST="127.0.0.1:3306"      # 或云RDS内网地址
export RDS_USER="trip_user"
export RDS_PASSWORD="你的密码"
```

### 5. 打包

```bash
mvn clean package -DskipTests
```

产出 3 个 fat jar（含全部依赖）：
- `manager_agent/target/manager_agent-1.0-SNAPSHOT.jar`
- `routeMaking_agent/target/routeMaking_agent-1.0-SNAPSHOT.jar`
- `tripPlanner_agent/target/tripPlanner_agent-1.0-SNAPSHOT.jar`

### 6. 启动（★顺序很重要：Nacos → 路线 → 行程 → 主管）

```bash
# ① 启动 Nacos（Agent 要注册名片，必须先起）
cd nacos/bin && bash startup.sh -m standalone

# ② 路线 Agent（8082）
java --enable-preview -Xms256m -Xmx256m -jar routeMaking_agent-1.0-SNAPSHOT.jar

# ③ 行程 Agent（8085）（等路线 Agent 起来后再启）
java --enable-preview -Xms256m -Xmx256m -jar tripPlanner_agent-1.0-SNAPSHOT.jar

# ④ 主管 Agent（8081）（等行程 Agent 起来后再启）
source rds.env   # 如配置了数据库
java --enable-preview -Xms256m -Xmx256m -jar manager_agent-1.0-SNAPSHOT.jar
```

> `--enable-preview` 必须加（代码用到 Java 21 switch 模式匹配特性）。

### 7. 访问使用

浏览器打开 `http://localhost:8081` → 注册/登录 → 输入任务（如"深圳到惠州3日游自驾游计划"）→ 点【生成计划】→ 实时查看思考过程 → 得到完整计划。

### 8. 命令行直跑模式（测试用，不启 Web）

```bash
java --enable-preview -jar manager_agent-1.0-SNAPSHOT.jar --run-now
```

## 🔌 HTTP 接口

| 接口 | 方法 | 说明 | 需登录 |
|---|---|---|---|
| `/api/register` | POST | 注册（username/password） | 否 |
| `/api/login` | POST | 登录（写入 Session） | 否 |
| `/api/check-login` | GET | 检查登录态 | 否 |
| `/api/logout` | POST | 退出登录 | 是 |
| `/app` | POST | 提交任务（`?prompt=任务描述`，异步） | 是 |
| `/events` | GET | 轮询主管思考过程 | 是 |
| `/result` | GET | 获取最终计划 | 是 |
| `/api/save-plan` | POST | 保存行程到数据库 | 是 |
| `/api/my-plans` | GET | 我的行程列表 | 是 |
| `/api/delete-plan` | POST | 删除行程（`?id=`） | 是 |

## ⚠️ 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 模型只回"请稍等"不调工具 | 流式开关问题 | `DashScopeChatModel.builder().stream(false)`（代码已默认关闭） |
| 第二次请求起 500，`FileSystemAlreadyExistsException` | fat jar 里 zipfs 重复挂载 | Skills 仓库已改静态单例（代码已修复） |
| 保存长行程 500 | `TEXT` 字段 64KB 上限 | `ALTER TABLE trip_plans MODIFY plan_content MEDIUMTEXT;` |
| 登录/查库接口 500 | 数据库未建表/连不上 | 执行 `database/schema.sql`，检查 RDS_HOST/密码 |
| 登录接口报参数名错误 | 编译未开 `-parameters` | pom 已配置 `<parameters>true</parameters>` |

## 📄 License

仅供学习交流使用。
