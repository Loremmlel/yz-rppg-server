# Copilot Instructions

## Build, test, and run commands

Run from repository root (`server`) using PowerShell:

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd test
.\mvnw.cmd -Dtest=ServerApplicationTests test
.\mvnw.cmd -Dtest=ServerApplicationTests#contextLoads test
.\mvnw.cmd spring-boot:run
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=loadtest"
```

Loadtest toolkit (`tools\loadtest`) commands:

```powershell
Set-Location .\tools\loadtest
..\..\mvnw.cmd -f pom.xml compile
..\..\mvnw.cmd -f pom.xml exec:java "-Dexec.args=nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 100"
```

Linting: no dedicated linter plugin is configured in root `pom.xml`; `compile` and `test` are the project validation commands.

## High-level architecture

- Bedside realtime pipeline: `/ws?bedId=...` binary frames are handled by `BinaryFrameWebSocketHandler`, buffered in `FrameBufferService` (30-frame batches), then sent to Python analysis over gRPC by `GrpcFrameAnalysisClient`.
- gRPC result handling fan-out: each analysis result is parsed once, then used to (1) push minimal `{hr,sqi}` JSON to the bedside WebSocket session, (2) batch-persist `patient_vitals` rows to TimescaleDB, (3) publish nurse-station ward deltas, and (4) evaluate alarm transitions.
- Nurse station pipeline: `/ws/nurse` uses text protocol (`subscribe`, `unsubscribe`, `ping`). On subscribe, server sends `subscribed` first, then a `snapshot`; realtime updates are sent as `vitals.batch_update` micro-batches.
- Alarm pipeline: `AlarmStateTracker` maintains per-bed state with trigger/resolve debounce windows and offline timeout behavior; `AlarmService` persists `alarm_event` state transitions and publishes `alarm.trigger` / `alarm.resolve` events to subscribed nurse sessions.
- Time-series storage/query model: startup SQL creates TimescaleDB hypertable `patient_vitals`; trend queries use native SQL in `PatientVitalsRepository` with `time_bucket` and median-style `percentile_cont` aggregations.
- Report generation path: `HealthReportService` builds report content via Spring AI chat completion; on LLM failure or timeout it intentionally falls back to Thymeleaf HTML (no hard failure to clients).
- Loadtest mode boundary: `loadtest` profile enables mock gRPC latency and synthetic nurse update pumping, plus runtime/scenario APIs under `/api/loadtest/**`.

## Key repository conventions

- Bed binding is query-parameter based: bedside clients must connect with `bedId`; patient association is resolved from current `VisitStatus.ADMITTED` visit records.
- Bedside binary frame wire format is fixed: first 8 bytes are big-endian `timestampMs` (`int64`), remaining bytes are encoded image payload.
- Session shutdown order is important: flush gRPC session buffer (`flushAndRemoveSession`) before removing local session mappings/buffers.
- Nurse updates are intentionally ward-scoped, deduplicated by patient within each ward, and flushed every 300ms; preserve this micro-batch behavior when changing realtime flows.
- Alarm thresholds, hysteresis durations, and offline behavior are centralized in `AlarmStateTracker`; keep policy changes there rather than scattering checks.
- Keep Timescale-native SQL style in `PatientVitalsRepository` for aggregation/reporting paths instead of rewriting these queries into generic JPQL.
- Loadtest-only behavior stays profile-gated (`@Profile("loadtest")`) and should not leak into default runtime code paths.
