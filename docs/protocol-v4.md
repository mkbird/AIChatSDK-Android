# Protocol v4

服务端与客户端 SDK 的 WebSocket 通信协议规范。

## 1. 现有协议问题

1. **meta 设计混乱**
2. **类型不严谨** 
3. **事件语义重叠**
4. **缺少 turn 关联**
5. **错误信息缺少分类** 

---

## 2. 设计原则

- 统一 JSON 消息格式，字段类型严格
- 每轮对话用服务端分配的 `turn_id` 贯穿 ASR -> AI -> TTS 全流程
- 事件命名使用 `<domain>.<action>` 层级结构
- 客户端只发控制指令 + 音频，不发无意义的元数据
- 错误有明确分类和 code
- 关键控制指令有 ACK 确认

---

## 3. 消息格式

所有文本消息使用统一 JSON 结构：

```json
{
  "type": "<domain>.<action>",
  "id": "<可选, 请求 ID, 用于 request-response 关联>",
  "payload": { ... },
  "ts": 1711360000000
}
```

| 字段 | 类型 | 必选 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 事件类型，`<domain>.<action>` 格式 |
| `id` | string | 否 | 消息 ID。客户端请求携带时，服务端对应响应回传同一 ID |
| `payload` | object | 是 | 业务数据，不同事件有不同结构 |
| `ts` | int | 否 | 毫秒时间戳。服务端下行消息必带，客户端可选 |

去掉 `meta` 层。连接级信息（session_id 等）在握手阶段一次性协商完成，无需每条消息重复携带。

音频数据使用 **WebSocket 二进制帧** 传输，不经过 JSON 编码。

---

## 4. 连接生命周期

### 4.1 握手 (客户端 -> 服务端)

```
type: "session.init"
```

```json
{
  "type": "session.init",
  "id": "req_001",
  "payload": {
    "protocol_version": 4,
    "user_id": 123456,
    "device_id": "watch_001",
    "client_version": "1.2.0"
  }
}
```

WebSocket 连接成功后客户端主动发送。替代原来语义不清的 `client_ready`。

### 4.2 握手响应 (服务端 -> 客户端)

```
type: "session.ready"
```

```json
{
  "type": "session.ready",
  "id": "req_001",
  "payload": {
    "session_id": "s_a1b2c3d4",
    "server_version": "4.0.0",
    "protocol_version": 4,
    "capabilities": {
      "tts": true,
      "asr": true,
      "opus": true,
      "conversation": true
    }
  },
  "ts": 1711360000000
}
```

服务端分配 `session_id`，声明能力。客户端收到后才能发送业务指令。

### 4.3 关闭会话

```
type: "session.close"
```

```json
{ "type": "session.close", "payload": { "reason": "user_disconnect" } }
```

任意一方发送。

### 4.4 握手拒绝

如果服务端无法接受 `session.init`（如协议版本不兼容、认证失败），回复 `error` 并标记 `fatal: true`，随后关闭 WebSocket 连接：

```json
{
  "type": "error",
  "id": "req_001",
  "payload": {
    "code": "protocol_unsupported",
    "message": "Unsupported protocol version: 3, server requires >= 4",
    "turn_id": null,
    "fatal": true
  },
  "ts": 1711360000000
}
```

客户端收到 `fatal: true` 的 error 后不应重试，应提示用户升级。

---

## 5. 语音通话控制

### 5.1 开始语音 (客户端 -> 服务端)

```
type: "speech.start"
```

```json
{
  "type": "speech.start",
  "id": "req_002",
  "payload": {
    "mode": "full_duplex",
    "input_codec": "opus",
    "output_codec": "pcm"
  }
}
```

| 字段 | 类型 | 可选值 | 说明 |
|---|---|---|---|
| `mode` | string | `"full_duplex"`, `"asr_only"` | 统一入口，替代原来的 `start_speech` + `start_pure_asr` 两个事件 |
| `input_codec` | string | `"pcm"`, `"opus"` | 输入音频编码 |
| `output_codec` | string | `"pcm"`, `"opus"` | 输出音频编码（`asr_only` 模式下忽略） |

### 5.2 语音启动确认 (服务端 -> 客户端)

```
type: "speech.started"
```

```json
{
  "type": "speech.started",
  "id": "req_002",
  "payload": {
    "mode": "full_duplex",
    "input_codec": "opus",
    "output_codec": "pcm",
    "input_sample_rate": 16000,
    "output_sample_rate": 24000
  },
  "ts": 1711360002000
}
```

明确的 ACK，客户端收到后再开始发送音频。

### 5.3 停止语音 (客户端 -> 服务端)

```
type: "speech.stop"
```

```json
{
  "type": "speech.stop",
  "id": "req_003",
  "payload": {
    "action": "stop"
  }
}
```

| 字段 | 类型 | 可选值 | 说明 |
|---|---|---|---|
| `action` | string | `"stop"`, `"cancel"` | `stop` = 停止并保留/处理已识别的文本；`cancel` = 取消并丢弃文本 |

各模式下 `stop` 与 `cancel` 的行为差异：

| 模式 | `action=stop` | `action=cancel` |
|---|---|---|
| **full_duplex** | 停止 ASR/DM（不再接收新语音），但**等待活跃 Turn 自然完成**（AI 回答播放完毕后再发 `speech.stopped`） | 立即取消一切（ASR、活跃 Turn、TTS），立即发 `speech.stopped` |
| **asr_only** | 等待当前句子结束并发送 `asr.final`，然后发 `speech.stopped` | 立即丢弃未完成的识别结果（不发 `asr.final`），立即发 `speech.stopped` |

默认值为 `"stop"`。

> 注意：`action=stop` 时 `speech.stopped` 可能延迟发送（等待活跃 Turn 完成）。客户端在此期间仍可能收到该 Turn 的 `ai.text.delta`、`tts.start`、二进制音频帧、`tts.done`、`turn.done` 事件。

### 5.4 语音停止确认 (服务端 -> 客户端)

```
type: "speech.stopped"
```

```json
{
  "type": "speech.stopped",
  "id": "req_003",
  "payload": {},
  "ts": 1711360010000
}
```

---

## 6. Turn（对话轮次）

核心改进：**服务端分配 `turn_id`，贯穿 ASR -> AI -> TTS 全流程**。客户端不再需要自行维护 Turn FIFO 队列。

### 6.1 Turn 开始 (服务端 -> 客户端)

```
type: "turn.started"
```

```json
{
  "type": "turn.started",
  "payload": {
    "turn_id": "t_001",
    "trigger": "voice"
  },
  "ts": 1711360003000
}
```

| `trigger` 值 | 说明 |
|---|---|
| `"voice"` | 语音触发（检测到用户说话） |
| `"text"` | 文本输入触发 |
| `"tts_only"` | 纯 TTS 合成触发（不经 AI） |

服务端检测到用户开始说话时创建 Turn 并通知客户端。

### 6.2 Turn 完成 (服务端 -> 客户端)

```
type: "turn.done"
```

```json
{
  "type": "turn.done",
  "payload": { "turn_id": "t_001" },
  "ts": 1711360009100
}
```

由服务端统一判定 Turn 结束，触发时机取决于模式和是否启用 TTS：

| 场景 | `turn.done` 发送时机 |
|---|---|
| full_duplex（语音对话） | `tts.done` 之后 |
| asr_only（纯语音识别） | `asr.final` 之后 |
| text.send(tts=true) | `tts.done` 之后 |
| text.send(tts=false) | `ai.text.done` 之后 |
| tts.synthesize | `tts.done` 之后 |

### 6.3 Turn 取消 (服务端 -> 客户端)

```
type: "turn.cancelled"
```

```json
{
  "type": "turn.cancelled",
  "payload": {
    "turn_id": "t_001",
    "reason": "interrupted_by_user"
  },
  "ts": 1711360009200
}
```

| `reason` 值 | 说明 |
|---|---|
| `"interrupted_by_user"` | 用户新一轮语音打断了上一轮 |
| `"speech_stopped"` | 用户主动停止语音通话 |
| `"error"` | 处理过程中发生错误 |

---

## 7. ASR（语音识别）

### 7.1 ASR 中间结果 (服务端 -> 客户端)

```
type: "asr.partial"
```

```json
{
  "type": "asr.partial",
  "payload": {
    "turn_id": "t_001",
    "text": "今天天气"
  },
  "ts": 1711360004000
}
```

实时识别的中间结果，文本会随着识别进展不断更新覆盖。

### 7.2 ASR 最终结果 (服务端 -> 客户端)

```
type: "asr.final"
```

```json
{
  "type": "asr.final",
  "payload": {
    "turn_id": "t_001",
    "text": "今天天气怎么样",
    "reason": "intent_complete"
  },
  "ts": 1711360005000
}
```

| `reason` 值 | 说明 | 触发场景 |
|---|---|---|
| `"intent_complete"` | DM 判定意图完整 | full_duplex 模式 |
| `"interrupt"` | 用户打断了前一轮回复 | full_duplex 模式 |
| `"silence_timeout"` | 长静音超时 | full_duplex 模式 |
| `"sentence_end"` | ASR 引擎检测到句子结束 | asr_only 模式 |

关键改进：明确告知客户端 `asr.final` 的触发原因。full_duplex 模式下由 Conversation DM 触发，asr_only 模式下由 ASR 引擎的 `is_sentence_end` 直接触发。客户端可据此做差异化 UI。

---

## 8. AI（大模型推理）

### 8.1 AI 思考开始 (服务端 -> 客户端)

```
type: "ai.thinking"
```

```json
{
  "type": "ai.thinking",
  "payload": { "turn_id": "t_001" },
  "ts": 1711360005100
}
```

补齐原来服务端有但客户端未处理的事件。客户端可据此显示"思考中"动画。

### 8.2 AI 文本增量 (服务端 -> 客户端)

```
type: "ai.text.delta"
```

```json
{
  "type": "ai.text.delta",
  "payload": {
    "turn_id": "t_001",
    "delta": "今天北京天气晴朗，"
  },
  "ts": 1711360006000
}
```

只传增量文本片段。

### 8.3 AI 文本完成 (服务端 -> 客户端)

```
type: "ai.text.done"
```

```json
{
  "type": "ai.text.done",
  "payload": {
    "turn_id": "t_001",
    "full_text": "今天北京天气晴朗，最高温度25度。"
  },
  "ts": 1711360007000
}
```

独立的完成事件，替代原来在 `ai_text_chunk` 中用 `is_final` 标志的方式。语义更清晰，客户端不需要在每个 chunk 里检查布尔字段。

---

## 9. TTS（语音合成）

### 9.1 TTS 开始 (服务端 -> 客户端)

```
type: "tts.start"
```

```json
{
  "type": "tts.start",
  "payload": {
    "turn_id": "t_001",
    "content_type": "audio/pcm",
    "sample_rate": 24000
  },
  "ts": 1711360007100
}
```

关键改进：**TTS 重新关联 `turn_id`**。原来解耦导致客户端无法确定"哪段语音对应哪个回答"。

### 9.2 TTS 音频数据

WebSocket 二进制帧传输。`tts.start` 和 `tts.done` 之间的所有二进制帧归属于该 Turn。

### 9.3 TTS 结束 (服务端 -> 客户端)

```
type: "tts.done"
```

```json
{
  "type": "tts.done",
  "payload": {
    "turn_id": "t_001",
    "audio_bytes_total": 192000
  },
  "ts": 1711360009000
}
```

### 9.4 纯 TTS 合成请求 (客户端 -> 服务端)

```
type: "tts.synthesize"
```

```json
{
  "type": "tts.synthesize",
  "id": "req_005",
  "payload": {
    "text": "你好，欢迎回家"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | string | 需要合成的文本内容 |

客户端发送文本直接合成语音，不经过 AI 大模型推理。
服务端收到后创建 Turn，通过 `turn.started(trigger=tts_only)` 通知客户端，
随后启动 TTS 合成并通过二进制帧发送音频，最终发送 `tts.done` + `turn.done`。

---

## 10. 文本输入

### 10.1 发送文本 (客户端 -> 服务端)

```
type: "text.send"
```

```json
{
  "type": "text.send",
  "id": "req_004",
  "payload": {
    "text": "今天天气怎么样",
    "tts": true
  }
}
```

支持多模态附件（v4.1 扩展）：

```json
{
  "type": "text.send",
  "id": "req_005",
  "payload": {
    "text": "这张图片里是什么？",
    "tts": false,
    "attachments": [
      {
        "kind": "image",
        "data": "<base64 encoded>",
        "mime_type": "image/jpeg"
      }
    ]
  }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `text` | string | - | 文本内容（可为空，仅发附件时） |
| `tts` | bool | `true` | 是否需要 TTS 语音回复 |
| `attachments` | array | `[]` | 附件列表（可选，向后兼容） |

`attachments` 数组元素：

| 字段 | 类型 | 必选 | 说明 |
|---|---|---|---|
| `kind` | string | 是 | `"image"`, `"video"`, `"url"` |
| `data` | string | 是 | base64 数据（image/video）或 URL 字符串（url） |
| `mime_type` | string | 否 | MIME 类型，如 `"image/jpeg"` |
| `name` | string | 否 | 文件名 |

统一替代原来的 `send_text` / `send_pure_text`，用 `tts` 布尔字段区分。

服务端收到后创建 Turn，通过 `turn.started(trigger=text)` 通知客户端。

> **SPEECH_ACTIVE 状态下的 text.send**：如果当前有活跃的 voice Turn（如用户正在说话），`text.send` 会**取消该 voice Turn**（发送 `turn.cancelled`），然后创建新的 text Turn。客户端应避免在用户说话时发送 `text.send`，除非确实需要打断当前对话。

---

## 11. 错误

```
type: "error"
```

```json
{
  "type": "error",
  "payload": {
    "code": "asr_decode_failed",
    "message": "Opus 解码失败: invalid frame",
    "turn_id": "t_001",
    "fatal": false
  },
  "ts": 1711360008000
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | string | 错误码枚举 |
| `message` | string | 可读描述 |
| `turn_id` | string \| null | 关联的 Turn，连接级错误为 null |
| `fatal` | bool | true 表示连接将断开 |

### 错误码枚举

| code | 说明 |
|---|---|
| `protocol_unsupported` | 协议版本不兼容 |
| `auth_failed` | 认证失败 |
| `invalid_message` | 消息格式错误 |
| `invalid_state` | 状态不正确（如未握手就发 speech.start） |
| `asr_decode_failed` | 音频解码失败 |
| `asr_error` | ASR 服务错误 |
| `llm_error` | LLM 推理错误 |
| `tts_error` | TTS 合成错误 |
| `rate_limited` | 请求过于频繁 |
| `session_expired` | 会话过期 |
| `internal_error` | 服务端内部错误 |

---

## 12. 完整时序图

### 12.1 全双工语音对话

```
客户端                                      服务端
  |                                           |
  |===== WebSocket Connect ==================>|
  |                                           |
  |-- session.init -------------------------->|  (protocol_version, user_id, device_id)
  |<--------------------------- session.ready |  (session_id, capabilities)
  |                                           |
  |-- speech.start (mode=full_duplex) ------->|
  |<------------------------- speech.started  |  (ACK, 确认编码和采样率)
  |                                           |
  |-- [二进制音频帧] ------------------------>|  (持续发送)
  |                                           |
  |<------------------------- turn.started    |  (turn_id=t_001, trigger=voice)
  |<------------------------- asr.partial     |  (turn_id=t_001, "今天")
  |<------------------------- asr.partial     |  (turn_id=t_001, "今天天气")
  |<------------------------- asr.final       |  (turn_id=t_001, reason=intent_complete)
  |<------------------------- ai.thinking     |  (turn_id=t_001)
  |<------------------------- ai.text.delta   |  (turn_id=t_001, "今天北京")
  |<------------------------- tts.start       |  (turn_id=t_001, sample_rate=24000)
  |<------------------------- ai.text.delta   |  (turn_id=t_001, "天气晴朗，")
  |<------------------------- [二进制音频帧]   |  (TTS 与 LLM 并行输出)
  |<------------------------- ai.text.done    |  (turn_id=t_001, full_text)
  |<------------------------- [二进制音频帧]   |  (TTS 剩余音频)
  |<------------------------- tts.done        |  (turn_id=t_001)
  |<------------------------- turn.done       |  (turn_id=t_001)
  |                                           |
  |-- speech.stop --------------------------->|
  |<------------------------ speech.stopped   |
  |                                           |
  |-- session.close ------------------------->|
```

### 12.2 纯 ASR 模式 + 文本发送

```
客户端                                      服务端
  |                                           |
  |  (握手完成后...)                            |
  |                                           |
  |-- speech.start (mode=asr_only) ---------->|
  |<------------------------- speech.started  |
  |                                           |
  |-- [二进制音频帧] ------------------------>|
  |                                           |
  |<------------------------- turn.started    |  (turn_id=t_001, trigger=voice)
  |<------------------------- asr.partial     |  (turn_id=t_001, "设个闹钟")
  |<------------------------- asr.final       |  (turn_id=t_001, reason=sentence_end)
  |<------------------------- turn.done       |  (turn_id=t_001, asr_only 模式直接结束)
  |                                           |
  |-- speech.stop --------------------------->|
  |<------------------------ speech.stopped   |
  |                                           |
  |-- text.send (tts=false) ---------------->|  (客户端决定发送文本)
  |<------------------------- turn.started    |  (turn_id=t_002, trigger=text)
  |<------------------------- ai.thinking     |  (turn_id=t_002)
  |<------------------------- ai.text.delta   |  (turn_id=t_002)
  |<------------------------- ai.text.done    |  (turn_id=t_002)
  |<------------------------- turn.done       |  (turn_id=t_002)
```

### 12.3 用户打断

```
客户端                                      服务端
  |                                           |
  |  (全双工模式, t_001 正在 TTS 播放中...)     |
  |                                           |
  |<------------------------- tts.start       |  (turn_id=t_001)
  |<------------------------- [二进制音频帧]   |  (t_001 的 TTS)
  |                                           |
  |  (用户开始说新的话)                         |
  |-- [二进制音频帧] ------------------------>|
  |                                           |
  |<---------------------- turn.cancelled     |  (turn_id=t_001, reason=interrupted_by_user)
  |<------------------------- turn.started    |  (turn_id=t_002, trigger=voice)
  |<------------------------- asr.partial     |  (turn_id=t_002)
  |<------------------------- asr.final       |  (turn_id=t_002, reason=interrupt)
  |<------------------------- ai.thinking     |  (turn_id=t_002)
  |  ...                                      |
```

### 12.4 纯 TTS 合成

```
客户端                                      服务端
  |                                           |
  |  (握手完成后...)                            |
  |                                           |
  |-- tts.synthesize ----------------------->|  (text="你好，欢迎回家")
  |<------------------------- turn.started    |  (turn_id=t_003, trigger=tts_only)
  |<------------------------- tts.start       |  (turn_id=t_003)
  |<------------------------- [二进制音频帧]   |  (TTS 合成音频)
  |<------------------------- tts.done        |  (turn_id=t_003)
  |<------------------------- turn.done       |  (turn_id=t_003)
```

---

## 13. 新旧协议对照

| 旧协议 (v3) | 新协议 (v4) | 变更说明 |
|---|---|---|
| `client_ready` | `session.init` | 改为主动握手，携带客户端信息 |
| `session_ready` | `session.ready` | 回传 session_id 和能力声明 |
| `start_speech` | `speech.start` | mode/codec 用字符串枚举替代 "0"/"1" |
| `start_pure_asr` | `speech.start(mode=asr_only)` | 合并为同一事件 |
| `stop_speech` / `stop_pure_asr` / `cancel_pure_asr` | `speech.stop` | 统一为一个停止事件 |
| `send_text` / `send_pure_text` | `text.send(tts=bool)` | 合并，用 tts 字段区分 |
| `asr_partial_result` | `asr.partial` | 增加 turn_id |
| `asr_final_result` | `asr.final` | 增加 turn_id + reason |
| _(缺失)_ | `ai.thinking` | 补齐 AI 思考状态 |
| `ai_text_chunk(is_final)` | `ai.text.delta` + `ai.text.done` | 拆为两个独立事件 |
| `ai_audio_begin` | `tts.start` | 重新关联 turn_id |
| `ai_audio_end` | `tts.done` | 重新关联 turn_id |
| _(缺失)_ | `turn.started` / `turn.done` / `turn.cancelled` | 服务端管理 Turn 生命周期 |
| `error(message)` | `error(code, message, turn_id, fatal)` | 结构化错误 |
| `close_session` | `session.close` | 加 reason |
| 每条消息携带 `meta` | 去掉 `meta` | 连接级信息在握手时一次性协商 |
| _(缺失)_ | `speech.started` / `speech.stopped` | 关键控制指令有 ACK 确认 |

---

## 14. 事件总览

### 客户端 -> 服务端

| type | 说明 | 需要 ACK |
|---|---|---|
| `session.init` | 握手初始化 | 是 (`session.ready`) |
| `session.close` | 关闭会话 | 否 |
| `speech.start` | 开始语音 | 是 (`speech.started`) |
| `speech.stop` | 停止语音 | 是 (`speech.stopped`) |
| `text.send` | 发送文本 | 否（通过 `turn.started` 隐式确认） |
| `tts.synthesize` | 纯 TTS 合成 | 否（通过 `turn.started` 隐式确认） |
| 二进制帧 | 音频数据 | 否 |

### 服务端 -> 客户端

| type | 说明 |
|---|---|
| `session.ready` | 握手响应 |
| `speech.started` | 语音启动确认 |
| `speech.stopped` | 语音停止确认 |
| `turn.started` | Turn 开始 |
| `turn.done` | Turn 完成 |
| `turn.cancelled` | Turn 取消 |
| `asr.partial` | ASR 中间结果 |
| `asr.final` | ASR 最终结果 |
| `ai.thinking` | AI 开始思考 |
| `ai.text.delta` | AI 文本增量 |
| `ai.text.done` | AI 文本完成 |
| `tts.start` | TTS 开始 |
| `tts.done` | TTS 结束 |
| `error` | 错误 |
| 二进制帧 | TTS 音频数据 |
