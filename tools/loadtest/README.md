# Load Test Toolkit

This module provides standalone performance tools for the `server` application.

## Scenarios

- `bedside`: Opens many `/ws?bedId=...` clients and sends 256x256 white image frames.
- `nurse`: Opens many `/ws/nurse` clients, subscribes to a ward, and measures push throughput.
- `db`: Runs mixed write + aggregate-query pressure on `patient_vitals` and exports CSV (`concurrency -> latency`).

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

### 1) Bedside

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="bedside --baseUrl ws://localhost:8080 --beds 128 --fps 15 --warmupSec 120 --measureSec 180"
```

### 2) Nurse Station

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 1000 --warmupSec 120 --measureSec 180"
```

### 3) DB Mixed Workload

```powershell
..\..\mvnw.cmd -f pom.xml exec:java -Dexec.args="db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 --concurrencyLevels 16,32,64,128,256 --writeRatio 0.8 --warmupSec 120 --measureSec 180 --outputCsv .\results\db-latency.csv"
```

## Plot Concurrency -> Latency

```powershell
python .\plot_latency.py --csv .\results\db-latency.csv --out .\results\db-latency.png
```

## Spring Boot Side Flags

Use profile `loadtest` in `server` app:

- `app.loadtest.grpc-mock.enabled=true` (replace Python gRPC with synthetic vitals)
- `app.loadtest.nurse-pump.enabled=true` (continuously publish fake ward updates)

You can tune these in `src/main/resources/application-loadtest.yaml`.

