# AGENTS.md

## Big Picture
- Stack: Spring Boot 4.0.3, Java 25, JPA + PostgreSQL/TimescaleDB, Spring WebSocket, Spring gRPC client, Spring AI (`pom.xml`, `src/main/resources/application.yaml`).
- Runtime boundaries: this server receives camera frames over WebSocket, delegates signal analysis to an external Python gRPC service, persists vitals to TimescaleDB, and pushes realtime updates/alarms back over WebSocket.
- Core flow (bedside): `/ws` -> `BinaryFrameWebSocketHandler` -> `FrameBufferService` (30-frame batches) -> `GrpcFrameAnalysisClient` -> DB + downstream pushes (`src/main/java/youzi/lin/server/websocket/BinaryFrameWebSocketHandler.java`, `src/main/java/youzi/lin/server/service/FrameBufferService.java`, `src/main/java/youzi/lin/server/grpc/GrpcFrameAnalysisClient.java`).
- Core flow (nurse station): `/ws/nurse` subscribe/unsubscribe -> snapshot + micro-batch updates + alarm events (`src/main/java/youzi/lin/server/websocket/NurseStationWebSocketHandler.java`, `src/main/java/youzi/lin/server/websocket/NurseWardBroadcastService.java`).

## External Integrations You Must Respect
- gRPC dependency: `frame-analysis` channel points to `localhost:50051` by default; protobuf contract is in `src/main/proto/frame_analysis.proto`.
- DB dependency: app expects PostgreSQL with TimescaleDB extension; startup SQL creates hypertable `patient_vitals` (`src/main/resources/init-data.sql`).
- LLM dependency: report generation uses Spring AI OpenAI-compatible endpoint; failures intentionally degrade to Thymeleaf fallback HTML, not HTTP 500 (`src/main/java/youzi/lin/server/service/HealthReportService.java`).

## Project-Specific Patterns (Non-Generic)
- Bed binding is query-param based: bedside clients must connect as `ws://host/ws?bedId=...`; patient is resolved from current `VisitStatus.ADMITTED` record.
- Binary frame wire format is fixed: first 8 bytes big-endian `timestampMs`, remaining bytes image payload.
- Session cleanup order matters: flush gRPC write buffer before removing session state (`GrpcFrameAnalysisClient.flushAndRemoveSession` is called during close/error).
- Alarm logic is state-machine + debounce durations (trigger/resolve windows per alarm type), with offline timeout sweep every 5s (`AlarmStateTracker`, `AlarmService`).
- Nurse updates are intentionally micro-batched every 300ms and deduped per patient within a ward before broadcast.
- Timeseries queries use Timescale `time_bucket` and median (`percentile_cont`) for HRV robustness; keep native SQL style in `PatientVitalsRepository`.

## Where To Edit For Common Tasks
- Add/modify REST APIs: `src/main/java/youzi/lin/server/controller/` + corresponding service in `src/main/java/youzi/lin/server/service/`.
- Change WebSocket protocol: `WebSocketConfig`, `BinaryFrameWebSocketHandler`, `NurseStationWebSocketHandler`, and docs under `docs/`.
- Change alarm thresholds/debounce/offline behavior: `src/main/java/youzi/lin/server/service/AlarmStateTracker.java`.
- Change DB schema or seed data: `src/main/resources/init-data.sql` and related entities/repositories.
- Change report prompt/policy: `src/main/resources/prompts/report-prompt.txt` + `service/report/*` + `HealthReportService`.

## Developer Workflow (Windows/PowerShell)
- Use Maven wrapper from repo root: `./mvnw.cmd`.
- Typical commands:
  - `./mvnw.cmd clean compile`
  - `./mvnw.cmd test`
  - `./mvnw.cmd spring-boot:run`
- Before expecting full functionality, ensure PostgreSQL/TimescaleDB is reachable and Python gRPC analyzer is running at configured address.

## Existing AI Guidance Files
- One glob search was run for common AI instruction files (`copilot-instructions`, `AGENTS.md`, `CLAUDE.md`, cursor/windsurf/cline rules, `README.md`); no matching files were found in this repository snapshot.

