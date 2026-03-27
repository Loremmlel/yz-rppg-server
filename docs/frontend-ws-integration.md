# 前端 WebSocket 对接文档（病房端 + 护士站）

> 应用版本：2026-03-26 当前代码实现
>
> 后端端点定义见 `src/main/java/youzi/lin/server/config/WebSocketConfig.java`

## 1. 总览

当前后端提供两个 WebSocket 端点：

- 病房端（设备侧）：`/ws`
  - 用途：上行二进制图像帧；下行文本实时结果（`hr`、`sqi`）
- 护士站端（看板侧）：`/ws/nurse`
  - 用途：下行病区快照 + 病区增量更新；上行订阅控制消息

服务端配置为 `setAllowedOrigins("*")`（开发友好，生产建议收敛域名白名单）。

## 2. 连接地址

假设后端运行在 `http://localhost:8080`：

- 病房端：`ws://localhost:8080/ws?bedId={bedId}`
- 护士站：`ws://localhost:8080/ws/nurse`

> HTTPS 部署时使用 `wss://`。

## 3. 病房端协议（已存在能力）

### 3.1 上行（二进制）

每帧 payload 格式：

- 前 8 字节：`timestampMs`（大端 `int64`）
- 后续字节：图像编码数据（JPEG/WebP 等）

后端会按会话累计到 30 帧后批量 gRPC 分析。

### 3.2 下行（文本 JSON）

服务端返回简化实时结果：

```json
{"hr":82.26,"sqi":0.54}
```

字段说明：

- `hr`: number | null
- `sqi`: number | null

## 4. 护士站协议（新增）

处理器见 `src/main/java/youzi/lin/server/websocket/NurseStationWebSocketHandler.java`。

### 4.1 客户端 -> 服务端消息

#### 4.1.1 订阅病区

```json
{"type":"subscribe","requestId":"req-1","wardCode":"内科一区"}
```

#### 4.1.2 取消订阅病区

```json
{"type":"unsubscribe","requestId":"req-2","wardCode":"内科一区"}
```

#### 4.1.3 应用层心跳

```json
{"type":"ping","ts":1774500000000}
```

### 4.2 服务端 -> 客户端消息

#### 4.2.1 订阅成功

```json
{"type":"subscribed","requestId":"req-1","wardCode":"内科一区","serverTime":"2026-03-26T10:00:00Z"}
```

#### 4.2.2 取消订阅成功

```json
{"type":"unsubscribed","requestId":"req-2","wardCode":"内科一区","serverTime":"2026-03-26T10:00:05Z"}
```

#### 4.2.3 快照（订阅后立即返回）

```json
{
  "type":"snapshot",
  "wardCode":"内科一区",
  "version":0,
  "generatedAt":"2026-03-26T10:00:00Z",
  "patients":[
    {
      "patientId":1,
      "bedId":11,
      "roomNo":"101",
      "bedNo":"1",
      "hr":82.3,
      "sqi":0.91,
      "eventTime":"2026-03-26T09:59:59.800Z"
    }
  ]
}
```

字段说明：

- `version`: 当前病区版本号快照基线
- `patients`: 病区当前在院患者的最新值列表

#### 4.2.4 增量微批更新

```json
{
  "type":"vitals.batch_update",
  "wardCode":"内科一区",
  "fromVersion":1,
  "toVersion":3,
  "updates":[
    {
      "patientId":1,
      "bedId":11,
      "hr":83.0,
      "sqi":0.9,
      "eventTime":"2026-03-26T10:00:00.200Z",
      "seq":345901
    }
  ]
}
```

字段说明：

- `fromVersion` / `toVersion`: 该批次覆盖的版本区间
- `seq`: 服务端单调序号（用于同批排序与调试）

> 当前服务端每 **300ms** 刷一次批次（见 `NurseWardBroadcastService` 的 `FLUSH_INTERVAL_MS`）。

#### 4.2.5 错误消息

```json
{"type":"error","requestId":"req-1","code":"WARD_NOT_FOUND","message":"wardCode 不存在: xxx","serverTime":"2026-03-26T10:00:00Z"}
```

当前可能错误码：

- `BAD_JSON`: 非法 JSON
- `BAD_REQUEST`: 缺少字段或字段为空
- `WARD_NOT_FOUND`: 订阅的病区不存在
- `UNSUPPORTED_TYPE`: 不支持的消息类型

#### 4.2.6 pong

```json
{"type":"pong","ts":1774500000000}
```

## 5. 推荐前端状态机

建议每个 `wardCode` 维护独立状态：

- `idle`: 未订阅
- `subscribing`: 已发 `subscribe`，等待 `subscribed`
- `syncing`: 已收到 `subscribed`，等待 `snapshot`
- `ready`: 已有快照，接收 `vitals.batch_update`

状态切换建议：

1. 连接成功后发送 `subscribe`
2. 收到 `subscribed` -> `syncing`
3. 收到 `snapshot` -> 用快照覆盖本地病区缓存 -> `ready`
4. `ready` 状态下应用增量更新
5. 断线重连后重新订阅，并以新 `snapshot` 全量重建

## 6. 本地缓存与渲染建议

建议结构：

- `wardStore[wardCode].version`
- `wardStore[wardCode].patientsById: Map<patientId, Row>`

处理规则：

- `snapshot`：清空并重建 `patientsById`；记录 `version`
- `vitals.batch_update`：逐条 upsert（按 `patientId`）
- `eventTime` 为 `null` 时展示为“暂无实时数据”

## 7. 重连与容错建议

- 使用指数退避重连（例如 1s、2s、4s、8s，上限 30s）
- 重连后必须重新发送订阅，不依赖旧会话状态
- 若发现版本断档（可选判断：本地 `version + 1 != fromVersion`），主动重订阅并等待新 `snapshot`
- 应用层每 30~60s 发一次 `ping`

## 8. TypeScript 最小对接示例

```ts
type NurseMsg =
  | { type: 'subscribed'; requestId: string | null; wardCode: string; serverTime: string }
  | { type: 'unsubscribed'; requestId: string | null; wardCode: string; serverTime: string }
  | {
      type: 'snapshot';
      wardCode: string;
      version: number;
      generatedAt: string;
      patients: Array<{
        patientId: number;
        bedId: number;
        roomNo: string;
        bedNo: string;
        hr: number | null;
        sqi: number | null;
        eventTime: string | null;
      }>;
    }
  | {
      type: 'vitals.batch_update';
      wardCode: string;
      fromVersion: number;
      toVersion: number;
      updates: Array<{
        patientId: number;
        bedId: number;
        hr: number | null;
        sqi: number | null;
        eventTime: string | null;
        seq: number;
      }>;
    }
  | { type: 'error'; requestId: string | null; code: string; message: string; serverTime: string }
  | { type: 'pong'; ts: number };

const ws = new WebSocket('ws://localhost:8080/ws/nurse');

ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'subscribe', requestId: 'req-1', wardCode: '内科一区' }));
};

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data) as NurseMsg;

  switch (msg.type) {
    case 'snapshot':
      // 全量重建 ward 缓存
      break;
    case 'vitals.batch_update':
      // 增量 upsert
      break;
    case 'error':
      // toast + 业务处理
      break;
  }
};
```

## 9. 联调检查清单

- 能成功连接 `ws://{host}/ws/nurse`
- 发送 `subscribe` 后先收到 `subscribed`，再收到 `snapshot`
- 病区内患者有新数据时，能持续收到 `vitals.batch_update`
- 非法消息能收到 `error`
- 断开后重连，重新订阅可恢复数据

## 10. 已知边界（当前实现）

- 增量只包含 `hr`、`sqi`（不含完整 HRV）
- 订阅粒度当前是病区级 `wardCode`
- 当前文档基于后端内存订阅模型（单实例语义）


