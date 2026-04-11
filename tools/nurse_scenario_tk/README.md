# Nurse Scenario Tk Tool

A small Python 3.11 + tkinter desktop tool for thesis experiments around nurse-station micro-batch updates and alarm transitions.

## What it does

- Starts Spring Boot with `loadtest` profile and controlled flags.
- Opens nurse WebSocket (`/ws/nurse`) and sends `subscribe`.
- Triggers a controlled HR scenario:
  - `115 bpm` baseline,
  - jump to `125 bpm` and hold,
  - drop to `105 bpm` for resolve hysteresis.
- Tracks `snapshot` / `vitals.batch_update` version continuity.
- Simulates packet loss by dropping one local batch and auto re-subscribing on version gap.
- Queries scenario status (`AlarmStateTracker` debug view) and recent `alarm_event` rows.

## Prerequisites

- Windows + PowerShell
- Python 3.11 (global install)
- Project database available (PostgreSQL + TimescaleDB)

## Install

```powershell
python -m pip install -r tools/nurse_scenario_tk/requirements.txt
```

## Run

```powershell
python tools/nurse_scenario_tk/main.py
```

## Typical flow

1. Click `Start Server` (default uses `loadtest`, `grpc-mock=true`, `nurse-pump=false`).
2. Set `wardCode` and `bedId`.
3. Click `Connect Nurse WS and Subscribe`.
4. Click `Start HR Jump Scenario`.
5. Watch logs for:
   - `snapshot version`
   - `batch_update from/to version`
   - `alarm.trigger` and `alarm.resolve`
6. Click `Simulate Packet Loss` once, then verify auto re-subscribe after a detected version gap.
7. Click `Query State and Events` to inspect state-machine debug fields and persisted `alarm_event` rows.

