# 压测工具（Load Test Toolkit）

本模块为 `server` 提供独立的性能压测工具，支持命令行和 Swing 图形界面两种方式。

## 场景说明

- `bedside`：创建多个 `/ws?bedId=...` 病床端连接，发送 256x256 全白图像帧。
- `nurse`：创建多个 `/ws/nurse` 护士站连接，订阅病区并统计推送性能。
- `bedside-matrix`：按床位并发阶梯压测，输出 CSV + Markdown。
- `nurse-matrix`：按护士站并发阶梯压测，输出 CSV + Markdown。
- `db`：仅压 `patient_vitals` 表的混合写入 + 聚合查询，输出并发-延迟数据。
- `smart-suite`：一次命令自动执行低/中低/中/高阶梯（病床端 + 护士站 + DB），输出 CSV/Markdown/SVG。
- `gui`：启动 Swing 图形界面，所有参数可视化配置。

默认情况下，`bedside` 与 `nurse` 也会生成单次结果文件：

- `./results/bedside-result.csv`
- `./results/bedside-result.md`
- `./results/nurse-result.csv`
- `./results/nurse-result.md`

`db` 默认开启清理（`--cleanup true`）：本次压测写入的假数据会在报告完成后自动删除。

## 前置条件

- Java 25
- Maven 3.9+
- Spring Boot 服务已启动（建议使用 `loadtest` profile）
- PostgreSQL/TimescaleDB 可连接（仅 `db` 场景必需）

## 快速开始（PowerShell）

```powershell
Set-Location "E:\Code\graduation project\server\tools\loadtest"
..\..\mvnw.cmd -f pom.xml compile
```

## JVM 基线建议

建议固定堆大小和 GC 参数，确保不同压测轮次可比。

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+AlwaysPreTouch"
```

可选的低延迟实验参数：

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch"
```

## 命令行示例

### 1) 病床端

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="bedside --baseUrl ws://localhost:8080 --beds 128 --fps 15 --warmupSec 120 --measureSec 180"
```

### 2) 护士站

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 1000 --warmupSec 120 --measureSec 180"
```

### 2.1) 病床端阶梯

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="bedside-matrix --baseUrl ws://localhost:8080 --bedsLevels 16,32,64,128,256 --fps 15 --warmupSec 120 --measureSec 180 --outputCsv .\results\bedside-matrix.csv --outputMd .\results\bedside-matrix.md"
```

### 2.2) 护士站阶梯

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="nurse-matrix --baseUrl ws://localhost:8080 --wardCode 内科一区 --stationsLevels 50,100,200,500,1000 --warmupSec 120 --measureSec 180 --outputCsv .\results\nurse-matrix.csv --outputMd .\results\nurse-matrix.md"
```

### 3) DB 混合负载

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --concurrencyLevels 16,32,64,128,256 --writeRatio 0.8 --warmupSec 120 --measureSec 180 --outputCsv .\results\db-latency.csv --outputMd .\results\db-latency.md"
```

仅在需要保留假数据时关闭清理：

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --concurrencyLevels 16,32,64 --writeRatio 0.8 --warmupSec 60 --measureSec 120 --cleanup false"
```

## 自动绘图

```powershell
python .\plot_latency.py --csv .\results\db-latency.csv --out .\results\db-latency.png
```

`*-matrix` 与 `smart-suite` 还会自动生成 SVG：

- `*-throughput.svg`
- `*-latency.svg`

## 一键智能阶梯压测

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="smart-suite --baseUrl ws://localhost:8080 --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --wardCode 内科一区 --profile balanced --outDir .\results --warmupSec 30 --measureSec 60 --cleanup true"
```

`--profile` 可选值：

- `quick`：快速验证（阶梯少、耗时短）
- `balanced`：默认方案
- `high`：更高上限压力

## GUI 启动（Swing）

通过默认入口启动：

```powershell
..\..\mvnw.cmd -f pom.xml exec:java "-Dexec.args=gui"
```

或直接指定 GUI 主类：

```powershell
..\..\mvnw.cmd -f pom.xml exec:java "-Dexec.mainClass=youzi.lin.loadtest.LoadTestGuiMain"
```

GUI 已支持：

- 场景切换（`bedside`、`nurse`、`bedside-matrix`、`nurse-matrix`、`db`、`smart-suite`）
- 按场景动态展示参数
- 后台运行 + 实时日志
- 报告路径列表 + 一键打开

## Spring Boot 侧建议开关

建议 `server` 使用 `loadtest` profile：

- `app.loadtest.grpc-mock.enabled=true`（用模拟生命体征替代 Python gRPC）
- `app.loadtest.nurse-pump.enabled=true`（持续产生护士站推送假数据）

可在 `src/main/resources/application-loadtest.yaml` 调整。

## 预热建议

- `warmupSec` 不建议为 0（建议 120-600 秒）。
- 同一轮矩阵压测保持 JVM 参数一致。
- 对瓶颈点重复至少 3 次，比较 p95/p99 中位值。

