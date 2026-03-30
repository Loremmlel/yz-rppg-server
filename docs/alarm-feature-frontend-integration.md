# 护士站报警功能前端对接文档（独立版）

> 文档范围：仅覆盖“新增报警功能”对接，不重复说明病房帧流与普通生命体征增量协议。
>
> 对应后端实现：
> - `src/main/java/youzi/lin/server/service/AlarmService.java`
> - `src/main/java/youzi/lin/server/service/AlarmStateTracker.java`
> - `src/main/java/youzi/lin/server/websocket/NurseWardBroadcastService.java`

## 1. 功能目标

当后端判定患者满足报警规则时，通过护士站 WebSocket 实时推送：

- 报警触发事件：`alarm.trigger`
- 报警解除事件：`alarm.resolve`

前端职责：

- 实时展示当前活跃报警
- 收到解除事件后及时清除或转为已恢复状态
- 处理重连、乱序和少量缺失场景

## 2. WebSocket 端点

护士站端点：`/ws/nurse`

示例：

- `ws://{host}:{port}/ws/nurse`
- HTTPS 环境：`wss://{host}:{port}/ws/nurse`

> 报警事件只会推送给已订阅对应 `wardCode` 的护士站会话。

## 3. 报警事件消息格式

### 3.1 报警触发 `alarm.trigger`

```json
{
  "type": "alarm.trigger",
  "wardCode": "内科一区",
  "bedId": 11,
  "patientId": 123,
  "alarmType": "TACHYCARDIA",
  "status": "ACTIVE",
  "message": "心率过速 (>120bpm)",
  "timestamp": "2026-03-30T10:01:00.000Z",
  "alarmEventId": 1001
}
```

### 3.2 报警解除 `alarm.resolve`

```json
{
  "type": "alarm.resolve",
  "wardCode": "内科一区",
  "bedId": 11,
  "patientId": 123,
  "alarmType": "TACHYCARDIA",
  "status": "RESOLVED",
  "message": "心率过速已恢复",
  "timestamp": "2026-03-30T10:01:20.000Z",
  "alarmEventId": 1001
}
```

### 3.3 字段说明

- `type`: `alarm.trigger` 或 `alarm.resolve`
- `wardCode`: 病区编码
- `bedId`: 床位 ID
- `patientId`: 患者 ID，少数情况下可为 `null`（例如离线恢复边界）
- `alarmType`: 报警类型枚举（见下一节）
- `status`: `ACTIVE` 或 `RESOLVED`
- `message`: 人类可读提示文案（可直接展示）
- `timestamp`: 事件时间（ISO-8601）
- `alarmEventId`: 报警事件主键；用于触发/解除配对，极少数场景可能为 `null`

## 4. 报警类型与含义

后端当前支持 4 种报警类型：

- `TACHYCARDIA`：心动过速
- `BRADYCARDIA`：心动过缓
- `LOW_SQI`：信号质量差
- `DEVICE_OFFLINE`：设备离线 / 数据超时

> 触发与解除均已由后端做持续时长防抖，前端不需要自行做阈值判定。

## 5. 建议的前端状态模型

建议按病区维护活跃报警表：

- `activeAlarmsByWard: Map<wardCode, Map<alarmKey, AlarmItem>>`

建议 `alarmKey` 规则：

- 优先：`alarmEventId`
- 兜底：`${bedId}:${alarmType}`

处理逻辑：

1. 收到 `alarm.trigger`：写入/覆盖 `activeAlarms`
2. 收到 `alarm.resolve`：按同 key 删除，或标记为已恢复
3. `patientId == null` 时，按床位维度展示（例如“11床设备离线”）

## 6. 与普通生命体征消息的关系

报警消息与 `vitals.batch_update` 并行，不互相替代：

- `vitals.batch_update`：用于更新数值展示（HR/SQI）
- `alarm.*`：用于更新报警状态（是否告警）

推荐 UI：

- 数值区照常显示实时 HR/SQI
- 报警区独立渲染活跃告警列表

## 7. 重连与容错建议

- 重连后必须重新发送 `subscribe`（病区订阅）
- 连接恢复后，以服务端新事件流为准重建本地报警状态
- 若担心漏事件，可在重连后先清空本地活跃报警，再等待新触发事件
- 前端可做轻量去重：同 `alarmKey` + `type` + `timestamp` 重复消息忽略

## 8. TypeScript 类型定义（最小可用）

```ts
export type AlarmType =
  | 'TACHYCARDIA'
  | 'BRADYCARDIA'
  | 'LOW_SQI'
  | 'DEVICE_OFFLINE';

export type AlarmStatus = 'ACTIVE' | 'RESOLVED';

export type NurseAlarmEvent = {
  type: 'alarm.trigger' | 'alarm.resolve';
  wardCode: string;
  bedId: number;
  patientId: number | null;
  alarmType: AlarmType;
  status: AlarmStatus;
  message: string;
  timestamp: string;
  alarmEventId: number | null;
};
```

## 9. 联调检查清单

- 已成功连接 `ws://{host}/ws/nurse`
- 已订阅目标病区并可收到常规消息
- 模拟高心率/低心率/低 SQI/离线后，可收到 `alarm.trigger`
- 恢复条件满足后，可收到 `alarm.resolve`
- 相同 `alarmEventId` 的触发与解除可正确配对
- 重连后报警 UI 可自恢复并继续更新

## 10. 当前边界说明

- 报警推送范围是病区订阅维度，不是全院广播
- 报警推送为实时事件流，不含历史分页查询接口
- 多实例部署时，若未做外部消息总线，需要额外设计跨实例广播一致性

