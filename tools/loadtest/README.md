# Load Test Toolkit

This module provides standalone performance tools for the `server` application.

## Scenarios

- `bedside`: Opens many `/ws?bedId=...` clients and sends 256x256 white image frames.
- `nurse`: Opens many `/ws/nurse` clients, subscribes to a ward, and measures push throughput.
- `bedside-matrix`: Sweeps multiple bedside concurrency levels and writes CSV + Markdown report.
- `nurse-matrix`: Sweeps multiple nurse-station concurrency levels and writes CSV + Markdown report.
- `db`: Runs mixed write + aggregate-query pressure on `patient_vitals` and exports CSV (`concurrency -> latency`).
- `smart-suite`: One command to run low -> medium-low -> medium -> high ladder for bedside + nurse + db and output CSV/Markdown/SVG charts.
- `gui`: Launches a Swing desktop app to configure and run all scenarios.

`bedside` and `nurse` now also write single-run result files by default:

- `./results/bedside-result.csv`
- `./results/bedside-result.md`
- `./results/nurse-result.csv`
- `./results/nurse-result.md`

`db` cleanup is enabled by default (`--cleanup true`): synthetic rows from this run are deleted automatically after reports are written.

## Prerequisites

- Java 25
- Maven 3.9+
- Running Spring Boot app (`loadtest` profile is recommended)
- PostgreSQL/TimescaleDB reachable (for `db` scenario)

## Quick Start (PowerShell)

```powershell
Set-Location "E:\Code\graduation project\server\tools\loadtest"
..\..\mvnw.cmd -f pom.xml compile
```

## JVM Baseline (Recommended)

Use fixed heap and explicit GC for comparable runs.

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+AlwaysPreTouch"
```

Optional low-latency experiment group:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch"
```

### 1) Bedside

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="bedside --baseUrl ws://localhost:8080 --beds 128 --fps 15 --warmupSec 120 --measureSec 180"
```

### 2) Nurse Station

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 1000 --warmupSec 120 --measureSec 180"
```

### 2.1) Bedside Matrix

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="bedside-matrix --baseUrl ws://localhost:8080 --bedsLevels 16,32,64,128,256 --fps 15 --warmupSec 120 --measureSec 180 --outputCsv .\results\bedside-matrix.csv --outputMd .\results\bedside-matrix.md"
```

### 2.2) Nurse Matrix

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="nurse-matrix --baseUrl ws://localhost:8080 --wardCode 内科一区 --stationsLevels 50,100,200,500,1000 --warmupSec 120 --measureSec 180 --outputCsv .\results\nurse-matrix.csv --outputMd .\results\nurse-matrix.md"
```

### 3) DB Mixed Workload

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --concurrencyLevels 16,32,64,128,256 --writeRatio 0.8 --warmupSec 120 --measureSec 180 --outputCsv .\results\db-latency.csv --outputMd .\results\db-latency.md"
```

Disable cleanup only when you need to keep generated rows:

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --concurrencyLevels 16,32,64 --writeRatio 0.8 --warmupSec 60 --measureSec 120 --cleanup false"
```

## Plot Concurrency -> Latency

```powershell
python .\plot_latency.py --csv .\results\db-latency.csv --out .\results\db-latency.png
```

`*-matrix` and `smart-suite` now also generate SVG charts automatically next to CSV files:

- `*-throughput.svg`
- `*-latency.svg`

## One-Command Smart Ladder

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="smart-suite --baseUrl ws://localhost:8080 --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --wardCode 内科一区 --profile balanced --outDir .\results --warmupSec 30 --measureSec 60 --cleanup true"
```

Available `--profile` values:

- `quick`: short ladder (fewer levels, fast sanity check)
- `balanced`: default ladder
- `high`: higher upper bound for stress runs

## GUI Runner (Swing)

Launch GUI from the default CLI entry:

```powershell
..\..\mvnw.cmd -f pom.xml exec:java "-Dexec.args=gui"
```

Or run GUI main class directly:

```powershell
..\..\mvnw.cmd -f pom.xml exec:java "-Dexec.mainClass=youzi.lin.loadtest.LoadTestGuiMain"
```

GUI features:

- scenario selection (`bedside`, `nurse`, `bedside-matrix`, `nurse-matrix`, `db`, `smart-suite`)
- all common options editable in one form (URL, DB, levels, warmup/measure, profile, cleanup)
- background execution with live log panel
- expected report path list and one-click open

## Spring Boot Side Flags

Use profile `loadtest` in `server` app:

- `app.loadtest.grpc-mock.enabled=true` (replace Python gRPC with synthetic vitals)
- `app.loadtest.nurse-pump.enabled=true` (continuously publish fake ward updates)

You can tune these in `src/main/resources/application-loadtest.yaml`.

## Warmup Discipline

- Keep `warmupSec` non-zero (typical 120-600 seconds).
- Keep JVM flags identical for all points in one matrix run.
- Re-run bottleneck points at least 3 times and compare median p95/p99.

