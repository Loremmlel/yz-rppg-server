# rPPG 医疗监护后端（server）

这是一个基于 **Spring Boot + WebSocket + gRPC + TimescaleDB** 的后端服务：接收病床端视频帧，调用 Python 分析服务得到 HR/SQI/HRV，实时推送到病床端与护士站，并持久化时序数据与报警事件。

## 1. 项目功能

1. **病床端实时链路**：`/ws?bedId=...` 接收二进制帧（前 8 字节时间戳 + 图像数据），按 30 帧分批送 gRPC 分析。
2. **生命体征入库与查询**：分析结果批量写入 `patient_vitals`（TimescaleDB 超表），提供实时明细、趋势聚合、最新值查询。
3. **护士站实时订阅**：`/ws/nurse` 支持 `subscribe/unsubscribe/ping`，返回 `snapshot` 与 `vitals.batch_update` 微批数据。
4. **报警状态机**：支持 `TACHYCARDIA / BRADYCARDIA / LOW_SQI / DEVICE_OFFLINE`，带触发/恢复防抖，推送 `alarm.trigger / alarm.resolve`。
5. **健康报告生成**：基于规则分析 + LLM 生成 HTML 报告，LLM 失败自动降级为模板报告。
6. **压测模式（loadtest）**：可启用 mock gRPC、护士站数据泵、运行时采样接口与场景注入接口。

## 2. 项目结构与文件用途

> 下列为主干结构与关键文件（非每个文件逐一展开）。

```text
server
├─ src
│  ├─ main
│  │  ├─ java\youzi\lin\server
│  │  │  ├─ config\                     # WebSocket、LoadTest、ChatClient 配置
│  │  │  ├─ controller\                 # REST API（病区、vitals、报告、loadtest）
│  │  │  ├─ dto\                        # 接口与链路 DTO
│  │  │  ├─ entity\                     # JPA 实体（patient/bed/visit/alarm_event/patient_vitals）
│  │  │  ├─ enums\                      # 业务枚举（报警、床位、住院状态等）
│  │  │  ├─ grpc\GrpcFrameAnalysisClient.java   # gRPC 调用 + 结果分发 + 批量入库
│  │  │  ├─ repository\                 # JPA/原生 SQL 查询（含 Timescale 聚合）
│  │  │  ├─ service\                    # 业务服务（缓冲、报警、病区、报告、loadtest）
│  │  │  ├─ service\report\             # 报告分析与 Prompt 组装
│  │  │  ├─ websocket\                  # WS 处理器、会话管理、护士站广播
│  │  │  └─ ServerApplication.java      # Spring Boot 启动入口
│  │  ├─ proto\frame_analysis.proto     # 与 Python 分析服务的 gRPC 协议
│  │  └─ resources\
│  │     ├─ application.yaml            # 默认配置
│  │     ├─ application-loadtest.yaml   # loadtest 覆盖配置
│  │     ├─ application.yaml.example    # 配置模板（建议从这里拷贝）
│  │     ├─ init-data.sql               # 初始化表结构 + 模拟业务数据 + 超表创建
│  │     ├─ prompts\report-prompt.txt   # 报告提示词
│  │     └─ templates\                  # 报告降级模板
│  └─ test\java\...\ServerApplicationTests.java  # 基础启动测试
├─ docs\
│  ├─ frontend-ws-integration.md        # 前端 WS 协议对接（病床端 + 护士站）
│  └─ alarm-feature-frontend-integration.md      # 报警推送对接说明
├─ tools\
│  ├─ loadtest\                         # Java 压测工具（CLI + Swing）
│  └─ nurse_scenario_tk\                # Python/Tk 场景联调工具
├─ pom.xml                              # 主 Maven 构建与依赖
├─ mvnw.cmd                             # Windows Maven Wrapper
├─ HELP.md                              # Spring 模板初始说明
└─ AGENTS.md                            # 维护者约定/架构要点
```

## 3. 部署指南（重点）

### 3.1 依赖准备

1. **JDK 25**
2. **PostgreSQL + TimescaleDB**
3. **Python 分析服务（gRPC）**，默认地址 `localhost:50051`

### 3.2 TimescaleDB 安装与数据库准备

官方安装文档（已检索）：

- https://docs.timescale.com/self-hosted/latest/install/installation-linux/
- https://docs.timescale.com/self-hosted/latest/install/installation-windows/

#### 3.2.1 Ubuntu / Debian 安装（官方流程精简版）

```bash
sudo apt install gnupg postgresql-common apt-transport-https lsb-release wget
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh
echo "deb https://packagecloud.io/timescale/timescaledb/debian/ $(lsb_release -c -s) main" | sudo tee /etc/apt/sources.list.d/timescaledb.list
wget --quiet -O - https://packagecloud.io/timescale/timescaledb/gpgkey | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/timescaledb.gpg
sudo apt update
sudo apt install timescaledb-2-postgresql-18 postgresql-client-18
sudo timescaledb-tune
sudo systemctl restart postgresql
```

> 若你的 PostgreSQL 主版本不是 18，请把包名中的 `18` 替换为对应版本。

#### 3.2.2 Windows 本地开发建议（Docker）

Windows 生产场景建议使用 Linux 服务器部署 PostgreSQL+TimescaleDB；本地开发可用 Docker：

```powershell
docker run -d --name timescaledb `
  -p 5432:5432 `
  -e POSTGRES_PASSWORD=1234 `
  -e POSTGRES_DB=rppg `
  timescale/timescaledb:latest-pg16
```

#### 3.2.3 创建扩展并校验

```sql
CREATE DATABASE rppg;
\c rppg
CREATE EXTENSION IF NOT EXISTS timescaledb;
\dx
```

` \dx ` 输出中看到 `timescaledb` 即表示安装成功。应用启动后会执行 `init-data.sql`（`spring.sql.init.mode=always`）自动建表并写入模拟数据。

### 3.3 配置文件

建议先复制模板：

```powershell
Copy-Item .\src\main\resources\application.yaml.example .\src\main\resources\application.yaml
```

然后重点修改数据库、gRPC、AI 配置（见下一节参数表）。

### 3.4 启动服务

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

loadtest 模式：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=loadtest"
```

### 3.5 连通性验证（最小）

1. 病床端连接：`ws://localhost:8080/ws?bedId=1`
2. 护士站连接：`ws://localhost:8080/ws/nurse`
3. 病区接口：`GET http://localhost:8080/api/wards/list`

## 4. 可修改参数与作用

| 配置项 | 默认值 | 作用 | 常见调整场景 |
|---|---:|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/rppg` | 数据库连接 | 切换到测试/生产库 |
| `spring.datasource.username/password` | `postgres/1234` | DB 凭据 | 按环境替换 |
| `spring.datasource.hikari.maximum-pool-size` | `10` | 连接池上限 | 并发写入较高时增大 |
| `spring.grpc.client.channels.frame-analysis.address` | `static://localhost:50051` | Python gRPC 地址 | 分析服务部署到远端时修改 |
| `spring.grpc.client.channels.frame-analysis.negotiation-type` | `PLAINTEXT` | gRPC 传输方式 | 上 TLS 时改为安全配置 |
| `spring.ai.openai.base-url` | 见配置 | LLM 网关地址 | 切换到自有/代理模型服务 |
| `spring.ai.openai.api-key` | 见配置 | LLM 密钥 | 必须改为真实密钥，建议走环境变量 |
| `spring.ai.openai.chat.options.model` | 见配置 | 报告生成模型 | 调整模型能力/成本 |
| `spring.sql.init.mode` | `always` | 启动初始化 SQL | 生产通常改为 `never` + 迁移工具 |
| `app.loadtest.grpc-mock.enabled` | `false` | 是否用 mock 分析结果 | 压测或脱离 Python 服务时启用 |
| `app.loadtest.grpc-mock.min-latency-ms/max-latency-ms` | `2/8` | mock 分析延迟范围 | 模拟推理耗时 |
| `app.loadtest.nurse-pump.enabled` | `false` | 是否注入护士站假数据 | 护士站压测时启用 |
| `app.loadtest.nurse-pump.ward-code` | `WARD-A` | 假数据病区 | 与前端订阅病区对齐 |
| `app.loadtest.nurse-pump.patients-per-tick` | `8` | 每 tick 更新人数 | 控制护士站负载 |
| `app.loadtest.nurse-pump.interval-ms` | `50` | tick 周期 | 控制推送频率 |

## 5. 业务说明：当前“病区/病床/入院记录”为模拟数据

`init-data.sql` 中的 `patient/bed/visit` 为**模拟医院业务数据**，用于本地开发与联调。实际落地时，应接入医院业务系统（HIS/EMR/ADT）获取真实病区、床位、患者、在院关系。

## 6. 改进方向与实施指南

### 6.1 方向 A：会话状态映射改造为 Redis（支持多实例）

当前 `WebSocketSessionManager` 与订阅关系主要在进程内存。单实例简单可靠，但多实例下会出现会话状态分散、跨实例广播困难的问题。

**建议改造路径：**

1. 抽象 `SessionRegistry` 接口（注册/移除/查询 bedId/patientId、会话活跃时间）。
2. 保留本地实现作为 fallback，同时新增 Redis 实现（如 `HSET session:{sessionId}`、`SETEX session:alive:{sessionId}`）。
3. 护士站订阅关系改为 Redis Set（`ward:{wardCode}:sessions`），并设置过期/心跳续期。
4. 下行发送仍由“本机持有的真实 WebSocket 会话”执行，Redis 只存映射与路由元数据。
5. 增加实例 ID 与会话归属字段（`ownerInstanceId`），避免跨实例误发。
6. 先灰度：单机启用 Redis 存储验证一致性，再扩容多实例。

### 6.2 方向 B：将部分事件消费改造为消息队列（支持微服务水平扩展）

当前 gRPC 回包后在同进程内串行执行推送、入库、报警评估、护士站广播。随着吞吐上升，耦合链路会放大尾延迟。

**建议改造路径：**

1. 定义事件模型（如 `VitalsAnalyzedEvent`），包含 `sessionId/bedId/patientId/hr/sqi/hrv/timestamp`。
2. 在 gRPC 回包处只做轻量解析 + 投递 MQ（Kafka/RabbitMQ 任一）。
3. 拆分消费者：
   - `vitals-persist-consumer`：只负责批量入库；
   - `alarm-consumer`：只负责状态机评估 + `alarm_event`；
   - `nurse-broadcast-consumer`：只负责病区增量广播。
4. 引入幂等键（建议 `sessionId + timestamp + seq`）与重试/死信队列。
5. 对报警事件和护士站增量设分区键（建议按 `bedId` 或 `wardCode`）保证有序性。
6. 逐链路迁移：先“入库异步化”，稳定后再拆报警与广播。

### 6.3 方向 C：接入医院业务系统（替换模拟住院业务）

**目标**：病区、床位、患者、在院状态不再由本服务本地维护，而是以 HIS 为主数据源。

**建议改造路径：**

1. 明确主数据边界：HIS 提供 `Ward/Room/Bed/Visit(ADMITTED)`，本服务仅缓存与查询。
2. 设计 `HisGateway`（HTTP/FHIR/HL7/DB-View 任一方式），统一封装鉴权、重试、超时、降级。
3. `WardService` 与 `VisitRepository` 查询逻辑改为“优先 HIS，同步到本地缓存表”。
4. `bedId -> patientId` 绑定改为实时查询 HIS 或读取短 TTL 缓存，避免换床串号。
5. 引入定时全量同步 + 增量订阅（消息/CDC），确保床位占用状态及时更新。
6. 将 `init-data.sql` 的模拟业务数据拆分为开发 profile 专用，生产禁用。
7. 增加审计字段（数据来源、同步时间、版本号）便于排障与追踪。

## 7. 常用接口与端点

- WebSocket
  - `ws://{host}/ws?bedId={bedId}`（病床端）
  - `ws://{host}/ws/nurse`（护士站）
- REST
  - `GET /api/wards/list`
  - `GET /api/wards`
  - `GET /api/vitals/realtime`
  - `GET /api/vitals/trend`
  - `GET /api/vitals/latest`
  - `POST /api/report/generate`
  - `GET /api/loadtest/runtime-snapshot`（仅 `loadtest`）

## 8. 相关文档

- `docs/frontend-ws-integration.md`：前端 WebSocket 对接
- `docs/alarm-feature-frontend-integration.md`：报警事件对接
- `tools/loadtest/README.md`：压测工具
- `tools/nurse_scenario_tk/README.md`：场景联调工具
