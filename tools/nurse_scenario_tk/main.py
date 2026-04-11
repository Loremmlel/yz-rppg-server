import json
import queue
import subprocess
import threading
import time
import tkinter as tk
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from tkinter import ttk, messagebox

import websocket


class NurseScenarioApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Nurse Scenario Simulator")
        self.root.geometry("1150x760")

        self.log_queue = queue.Queue()
        self.server_process = None
        self.ws_app = None
        self.ws_thread = None
        self.ws_connected = False

        self.current_version = None
        self.snapshot_version = None
        self.drop_next_batch = False
        self.last_subscribe_request_id = None

        self._build_ui()
        self.root.after(200, self._drain_log_queue)

    def _build_ui(self):
        project_root = str(Path(__file__).resolve().parents[2])

        frm = ttk.Frame(self.root, padding=10)
        frm.pack(fill=tk.BOTH, expand=True)

        launch_group = ttk.LabelFrame(frm, text="1) Spring Boot 启动参数")
        launch_group.pack(fill=tk.X, pady=6)

        self.project_dir_var = tk.StringVar(value=project_root)
        self.port_var = tk.StringVar(value="8080")
        self.jvm_args_var = tk.StringVar(value="")

        self.grpc_mock_var = tk.BooleanVar(value=True)
        self.nurse_pump_var = tk.BooleanVar(value=False)

        self.ward_code_var = tk.StringVar(value="内科一区")
        self.bed_id_var = tk.StringVar(value="1")
        self.patient_id_var = tk.StringVar(value="")

        self.baseline_sec_var = tk.StringVar(value="2")
        self.high_sec_var = tk.StringVar(value="20")
        self.recovery_sec_var = tk.StringVar(value="12")

        self.alarm_limit_var = tk.StringVar(value="10")

        row0 = ttk.Frame(launch_group)
        row0.pack(fill=tk.X, padx=8, pady=4)
        ttk.Label(row0, text="项目根目录").pack(side=tk.LEFT)
        ttk.Entry(row0, textvariable=self.project_dir_var, width=85).pack(side=tk.LEFT, padx=6)

        row1 = ttk.Frame(launch_group)
        row1.pack(fill=tk.X, padx=8, pady=4)
        ttk.Label(row1, text="端口").pack(side=tk.LEFT)
        ttk.Entry(row1, textvariable=self.port_var, width=8).pack(side=tk.LEFT, padx=6)
        ttk.Checkbutton(row1, text="grpc-mock.enabled", variable=self.grpc_mock_var).pack(side=tk.LEFT, padx=6)
        ttk.Checkbutton(row1, text="nurse-pump.enabled", variable=self.nurse_pump_var).pack(side=tk.LEFT, padx=6)
        ttk.Label(row1, text="JVM 额外参数").pack(side=tk.LEFT, padx=(16, 4))
        ttk.Entry(row1, textvariable=self.jvm_args_var, width=30).pack(side=tk.LEFT)

        row2 = ttk.Frame(launch_group)
        row2.pack(fill=tk.X, padx=8, pady=6)
        ttk.Button(row2, text="启动主应用", command=self.start_server).pack(side=tk.LEFT, padx=4)
        ttk.Button(row2, text="停止主应用", command=self.stop_server).pack(side=tk.LEFT, padx=4)
        ttk.Button(row2, text="检查 /api/loadtest/runtime-snapshot", command=self.check_runtime_snapshot).pack(side=tk.LEFT, padx=8)

        scenario_group = ttk.LabelFrame(frm, text="2) 护士站订阅 + 场景注入")
        scenario_group.pack(fill=tk.X, pady=6)

        row3 = ttk.Frame(scenario_group)
        row3.pack(fill=tk.X, padx=8, pady=4)
        ttk.Label(row3, text="病区 wardCode").pack(side=tk.LEFT)
        ttk.Entry(row3, textvariable=self.ward_code_var, width=18).pack(side=tk.LEFT, padx=6)
        ttk.Label(row3, text="床位 bedId").pack(side=tk.LEFT)
        ttk.Entry(row3, textvariable=self.bed_id_var, width=8).pack(side=tk.LEFT, padx=6)
        ttk.Label(row3, text="患者 patientId(可空)").pack(side=tk.LEFT)
        ttk.Entry(row3, textvariable=self.patient_id_var, width=10).pack(side=tk.LEFT, padx=6)
        ttk.Button(row3, text="查询 targets", command=self.fetch_targets).pack(side=tk.LEFT, padx=8)

        row4 = ttk.Frame(scenario_group)
        row4.pack(fill=tk.X, padx=8, pady=4)
        ttk.Button(row4, text="连接护士站 WS 并 subscribe", command=self.connect_and_subscribe_ws).pack(side=tk.LEFT, padx=4)
        ttk.Button(row4, text="断开护士站 WS", command=self.disconnect_ws).pack(side=tk.LEFT, padx=4)
        ttk.Button(row4, text="模拟丢包(丢弃下一条 batch)", command=self.simulate_packet_loss).pack(side=tk.LEFT, padx=4)
        ttk.Button(row4, text="手动重订阅", command=self.resubscribe).pack(side=tk.LEFT, padx=4)

        row5 = ttk.Frame(scenario_group)
        row5.pack(fill=tk.X, padx=8, pady=4)
        ttk.Label(row5, text="baseline秒").pack(side=tk.LEFT)
        ttk.Entry(row5, textvariable=self.baseline_sec_var, width=6).pack(side=tk.LEFT, padx=4)
        ttk.Label(row5, text="high秒").pack(side=tk.LEFT)
        ttk.Entry(row5, textvariable=self.high_sec_var, width=6).pack(side=tk.LEFT, padx=4)
        ttk.Label(row5, text="recovery秒").pack(side=tk.LEFT)
        ttk.Entry(row5, textvariable=self.recovery_sec_var, width=6).pack(side=tk.LEFT, padx=4)
        ttk.Button(row5, text="启动 HR 115->125(20s)->105 场景", command=self.start_hr_jump_scenario).pack(side=tk.LEFT, padx=8)
        ttk.Button(row5, text="查询状态机/报警事件", command=self.query_state_and_events).pack(side=tk.LEFT, padx=4)

        log_group = ttk.LabelFrame(frm, text="3) 观测日志")
        log_group.pack(fill=tk.BOTH, expand=True, pady=6)

        row6 = ttk.Frame(log_group)
        row6.pack(fill=tk.X, padx=8, pady=4)
        ttk.Label(row6, text="alarm_event 条数").pack(side=tk.LEFT)
        ttk.Entry(row6, textvariable=self.alarm_limit_var, width=6).pack(side=tk.LEFT, padx=4)
        ttk.Button(row6, text="清空日志", command=self.clear_log).pack(side=tk.LEFT, padx=8)

        self.log_text = tk.Text(log_group, height=28)
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)

    def log(self, msg: str):
        self.log_queue.put(msg)

    def _drain_log_queue(self):
        while True:
            try:
                msg = self.log_queue.get_nowait()
            except queue.Empty:
                break
            ts = time.strftime("%H:%M:%S")
            self.log_text.insert(tk.END, f"[{ts}] {msg}\n")
            self.log_text.see(tk.END)
        self.root.after(200, self._drain_log_queue)

    def clear_log(self):
        self.log_text.delete("1.0", tk.END)

    def base_http_url(self) -> str:
        return f"http://127.0.0.1:{self.port_var.get().strip()}"

    def base_ws_url(self) -> str:
        return f"ws://127.0.0.1:{self.port_var.get().strip()}/ws/nurse"

    def _http_json(self, method: str, path: str, body=None):
        url = self.base_http_url() + path
        data = None
        headers = {"Content-Type": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url=url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                payload = resp.read().decode("utf-8")
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="ignore")
            raise RuntimeError(f"HTTP {e.code}: {detail}") from e
        except Exception as e:
            raise RuntimeError(str(e)) from e

    def start_server(self):
        if self.server_process and self.server_process.poll() is None:
            self.log("主应用已在运行")
            return

        project_dir = Path(self.project_dir_var.get().strip())
        if not project_dir.exists():
            messagebox.showerror("错误", f"项目目录不存在: {project_dir}")
            return

        arguments = [
            f"--server.port={self.port_var.get().strip()}",
            f"--app.loadtest.grpc-mock.enabled={'true' if self.grpc_mock_var.get() else 'false'}",
            f"--app.loadtest.nurse-pump.enabled={'true' if self.nurse_pump_var.get() else 'false'}",
        ]

        cmd = [
            "powershell.exe",
            "-NoProfile",
            "-Command",
            "./mvnw.cmd spring-boot:run \"-Dspring-boot.run.profiles=loadtest\" "
            + f"\"-Dspring-boot.run.arguments={' '.join(arguments)}\" "
            + self.jvm_args_var.get().strip(),
        ]

        self.log("启动主应用: loadtest profile + 可控参数")
        self.server_process = subprocess.Popen(
            cmd,
            cwd=str(project_dir),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="ignore",
            bufsize=1,
        )
        threading.Thread(target=self._read_server_output, daemon=True).start()

    def _read_server_output(self):
        proc = self.server_process
        if not proc or not proc.stdout:
            return
        for line in proc.stdout:
            line = line.rstrip()
            if line:
                self.log(f"[SERVER] {line}")

    def stop_server(self):
        if not self.server_process or self.server_process.poll() is not None:
            self.log("主应用未运行")
            return
        self.server_process.terminate()
        self.log("已发送停止信号给主应用")

    def check_runtime_snapshot(self):
        def run():
            try:
                data = self._http_json("GET", "/api/loadtest/runtime-snapshot")
                self.log(f"runtime-snapshot: uptimeMs={data.get('uptimeMs')} heap={data.get('heapUsedBytes')}")
            except Exception as e:
                self.log(f"检查 runtime-snapshot 失败: {e}")

        threading.Thread(target=run, daemon=True).start()

    def fetch_targets(self):
        ward = self.ward_code_var.get().strip()

        def run():
            try:
                path = f"/api/loadtest/scenario/targets?wardCode={urllib.parse.quote(ward)}" if ward else "/api/loadtest/scenario/targets"
                data = self._http_json("GET", path)
                self.log(f"targets 数量={len(data)}")
                for item in data[:10]:
                    self.log(
                        f"target bedId={item.get('bedId')} ward={item.get('wardCode')} room={item.get('roomNo')}"
                        f" bedNo={item.get('bedNo')} admittedPatientId={item.get('admittedPatientId')}"
                    )
            except Exception as e:
                self.log(f"查询 targets 失败: {e}")

        threading.Thread(target=run, daemon=True).start()

    def connect_and_subscribe_ws(self):
        if self.ws_connected:
            self.log("护士站 WS 已连接")
            return

        ws_url = self.base_ws_url()
        ward = self.ward_code_var.get().strip()
        if not ward:
            messagebox.showerror("错误", "wardCode 不能为空")
            return

        self.current_version = None
        self.snapshot_version = None

        def on_open(ws):
            self.ws_connected = True
            self.log(f"WS 已连接: {ws_url}")
            self._send_subscribe(ws, ward)

        def on_message(ws, message: str):
            try:
                data = json.loads(message)
            except Exception:
                self.log(f"WS 原始消息: {message}")
                return
            self._handle_ws_message(ws, data)

        def on_error(_ws, error):
            self.log(f"WS 错误: {error}")

        def on_close(_ws, _status, _msg):
            self.ws_connected = False
            self.log("WS 已断开")

        self.ws_app = websocket.WebSocketApp(
            ws_url,
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close,
        )

        self.ws_thread = threading.Thread(target=self.ws_app.run_forever, daemon=True)
        self.ws_thread.start()

    def _send_subscribe(self, ws, ward_code: str):
        request_id = str(uuid.uuid4())
        self.last_subscribe_request_id = request_id
        payload = {
            "type": "subscribe",
            "requestId": request_id,
            "wardCode": ward_code,
        }
        ws.send(json.dumps(payload))
        self.log(f"发送 subscribe, wardCode={ward_code}, requestId={request_id}")

    def _handle_ws_message(self, ws, data: dict):
        msg_type = data.get("type")

        if msg_type == "subscribed":
            self.log(f"收到 subscribed, requestId={data.get('requestId')}, ward={data.get('wardCode')}")
            return

        if msg_type == "snapshot":
            self.snapshot_version = data.get("version")
            self.current_version = self.snapshot_version
            patient_count = len(data.get("patients", []))
            self.log(f"收到 snapshot: version={self.snapshot_version}, patients={patient_count}")
            return

        if msg_type == "vitals.batch_update":
            from_v = data.get("fromVersion")
            to_v = data.get("toVersion")

            if self.drop_next_batch:
                self.drop_next_batch = False
                self.log(f"[模拟丢包] 本地丢弃 batch from={from_v} to={to_v}")
                return

            if self.current_version is not None and from_v != self.current_version + 1:
                self.log(
                    f"[版本断档] expected from={self.current_version + 1}, actual from={from_v}, to={to_v} -> 自动重订阅"
                )
                self._send_subscribe(ws, self.ward_code_var.get().strip())
                return

            updates = len(data.get("updates", []))
            self.current_version = to_v
            self.log(f"收到 batch_update: from={from_v}, to={to_v}, updates={updates}")
            return

        if msg_type in {"alarm.trigger", "alarm.resolve"}:
            self.log(
                f"收到 {msg_type}: bed={data.get('bedId')} patient={data.get('patientId')} "
                f"type={data.get('alarmType')} status={data.get('status')} alarmEventId={data.get('alarmEventId')}"
            )
            return

        if msg_type == "error":
            self.log(f"收到 WS error: code={data.get('code')} message={data.get('message')}")
            return

        self.log(f"收到 WS 未处理消息: {data}")

    def disconnect_ws(self):
        if self.ws_app:
            self.ws_app.close()
            self.ws_app = None
            self.log("已请求关闭 WS")

    def simulate_packet_loss(self):
        self.drop_next_batch = True
        self.log("下一条 batch_update 将被本地丢弃，用于验证断档重订阅")

    def resubscribe(self):
        if not self.ws_app or not self.ws_connected:
            self.log("WS 未连接，无法重订阅")
            return
        self._send_subscribe(self.ws_app, self.ward_code_var.get().strip())

    def start_hr_jump_scenario(self):
        bed_id_raw = self.bed_id_var.get().strip()
        if not bed_id_raw:
            messagebox.showerror("错误", "bedId 不能为空")
            return

        patient_id_raw = self.patient_id_var.get().strip()
        body = {
            "bedId": int(bed_id_raw),
            "patientId": int(patient_id_raw) if patient_id_raw else None,
            "baselineSeconds": int(self.baseline_sec_var.get().strip()),
            "highSeconds": int(self.high_sec_var.get().strip()),
            "recoverySeconds": int(self.recovery_sec_var.get().strip()),
        }

        def run():
            try:
                data = self._http_json("POST", "/api/loadtest/scenario/hr-jump", body)
                self.log(
                    "已启动场景: "
                    f"runId={data.get('runId')} bedId={data.get('bedId')} patientId={data.get('patientId')} "
                    f"expectedTriggerAt={data.get('expectedTriggerAt')} expectedResolveAt={data.get('expectedResolveAt')}"
                )
            except Exception as e:
                self.log(f"启动场景失败: {e}")

        threading.Thread(target=run, daemon=True).start()

    def query_state_and_events(self):
        bed_id_raw = self.bed_id_var.get().strip()
        if not bed_id_raw:
            self.log("请先输入 bedId")
            return
        bed_id = int(bed_id_raw)
        limit = int(self.alarm_limit_var.get().strip())

        def run():
            try:
                status = self._http_json("GET", f"/api/loadtest/scenario/status?bedId={bed_id}")
                if status:
                    self.log(
                        f"status runId={status.get('runId')} finished={status.get('finished')} "
                        f"tick={status.get('lastTickIndex')}/{status.get('totalTicks')} lastHr={status.get('lastHr')}"
                    )
                    alarm_state = status.get("alarmState") or {}
                    by_type = alarm_state.get("byType") or {}
                    tachy = by_type.get("TACHYCARDIA") or {}
                    self.log(
                        "state[TACHYCARDIA] "
                        f"active={tachy.get('active')} triggerStartedAtMs={tachy.get('triggerStartedAtMs')} "
                        f"resolveStartedAtMs={tachy.get('resolveStartedAtMs')}"
                    )
                else:
                    self.log("status: no running scenario for current bedId")
            except Exception as e:
                self.log(f"查询场景状态失败: {e}")

            try:
                events = self._http_json("GET", f"/api/loadtest/scenario/alarm-events?bedId={bed_id}&limit={limit}")
                self.log(f"alarm_event 最近 {len(events)} 条:")
                for event in events:
                    self.log(
                        f"  id={event.get('id')} type={event.get('alarmType')} status={event.get('status')} "
                        f"trigger={event.get('triggerTime')} resolve={event.get('resolveTime')}"
                    )
            except Exception as e:
                self.log(f"查询 alarm_event 失败: {e}")

        threading.Thread(target=run, daemon=True).start()


def main():
    root = tk.Tk()
    app = NurseScenarioApp(root)

    def on_close():
        app.disconnect_ws()
        app.stop_server()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    root.mainloop()


if __name__ == "__main__":
    main()


