# Project Guidelines

## 做方案
1. 每次做方案时，都要深度思考
2. 做完方案后，要再次思考有没有更好的方案

## 文档维护

- 功能变更后，必须同步更新对应仓库的 README.md：
  - Android SDK + Demo: `/Users/madongpeng/work/AIChatSDK-Android/README.md`
  - iOS SDK + Demo: `/Users/madongpeng/work/AIChatSDK-iOS/README.md`
  - Server: `/Users/madongpeng/work/AIChatServer/README.md`
- 协议文件是三端共同真源，任一端修改协议时必须同步三处：
  - `/Users/madongpeng/work/AIChatSDK-iOS/docs/protocol-v4.md`（权威）
  - `/Users/madongpeng/work/AIChatSDK-Android/docs/protocol-v4.md`（从 iOS 单向拷贝）
  - `/Users/madongpeng/work/AIChatServer/` 中的服务端实现
- 有大的架构或方案更新时，如有必要，同步写入本文件（`AIChatSDK-Android/CLAUDE.md`），便于后续会话快速了解系统现状
- 实现方案完整文档：`/Users/madongpeng/work/AIChatSDK-Android/docs/PLAN.md`

## 环境配置

- Android Studio Hedgehog+ / NDK 26.1+（`ndkVersion = "26.1.10909125"`）
- JDK 17（`kotlinOptions.jvmTarget = "17"`）
- 首次构建前必须拉取原生依赖：
  ```bash
  cd /Users/madongpeng/work/AIChatSDK-Android/aisdk/src/main/cpp
  mkdir -p third_party && cd third_party
  git clone --depth 1 --branch v1.4 https://github.com/xiph/opus.git opus
  git clone --depth 1 --branch v1.3 https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git webrtc-apm
  ```
- 缺少 `third_party/opus` → 无 Opus 能力，降级 PCM
- 缺少 `third_party/webrtc-apm` → 无 WebRTC APM，全双工 AEC 降级（回声会泄入 mic）
- 服务端联调：`conda activate homehub` 启动 AIChatServer

## Repository

- Android SDK + Demo git root: `/Users/madongpeng/work/AIChatSDK-Android`
- iOS 参考实现: `/Users/madongpeng/work/AIChatSDK-iOS`
- 服务端: `/Users/madongpeng/work/AIChatServer`

## 测试规范

每完成一个功能点后，必须：

1. 编写完整的测试用例
2. 自动运行所有测试，确保全部通过

### 测试用例要求

测试必须覆盖以下两个维度：

**代码层面测试（Unit Tests）**
- 正常输入的返回值验证
- 边界条件（空值、最大值、最小值等）
- 异常输入的错误处理
- 关键函数的单元测试

**功能层面测试（Functional Tests）**
- 完整业务流程的端到端验证（如 session → turn.started → delta → turn.done）
- 用户场景模拟（文本/语音 6 模态、打断、重连）
- 与其他模块的集成测试（ChatService ↔ Transport ↔ SpeechManager）

### 运行测试命令

```bash
# JVM 单元测试（无需设备，覆盖协议/FSM/REST/Mock Transport）
cd /Users/madongpeng/work/AIChatSDK-Android && ./gradlew :aisdk:testDebugUnitTest :demo:testDebugUnitTest

# Android instrumented 测试（需设备或模拟器，覆盖 DataStore/Opus/AudioRecord/AudioTrack）
cd /Users/madongpeng/work/AIChatSDK-Android && ./gradlew :aisdk:connectedDebugAndroidTest
```

## 文件操作

删除文件时使用完整路径命令：`/bin/rm`

## 依赖管理

新增任何第三方依赖后，必须同步更新 `gradle/libs.versions.toml`（version catalog 单一真源）：

- 新增版本号 → `[versions]` 段
- 新增 library 坐标 → `[libraries]` 段
- 模块 `build.gradle.kts` 仅通过 `libs.xxx` 引用，不得写死坐标

## 回复语言

所有回复、测试报告均使用**中文**。

---

## 已实现方案说明

### 整体架构

**通信协议：** v4 WebSocket 协议（文本事件 JSON + 二进制音频帧），`docs/protocol-v4.md` 与 iOS/Server 对齐。

```
用户 → DemoChatScreen → ChatService.send()/startSpeech()
    → ChatEventTransport (DemoEventTransport / MockEventTransport)
    → OkHttp WebSocket ↔ AIChatServer
    ← ChatService.handleEvent() 事件分派
    ← SpeechManager（ASR/TTS）+ messages StateFlow → Compose 重绘
```

### 模块划分

| 模块 | 路径 | 职责 |
|---|---|---|
| `:aisdk` | `aisdk/src/main/kotlin/com/aichatsdk/` | 协议 + 编排 + 音频 + 持久化（作为 aar 分发） |
| `:aisdk` 原生 | `aisdk/src/main/cpp/` | JNI 单库 `libaichat_native.so` = libopus 1.4 + WebRTC APM（AEC3+NS+AGC2） |
| `:demo` | `demo/src/main/kotlin/com/aichatsdk/demo/` | Compose UI + 传输层实现 + 设置页 + Debug 日志 |

### SDK 关键类

| 文件 | 职责 |
|---|---|
| `models/ChatEvent.kt` | v4 envelope + `ChatEventCodec` 双向 JSON + `EventType` 常量 |
| `models/AnyValue.kt` | 密封类 + Moshi 适配器，承载动态 payload（对标 iOS `AnyCodableValue`） |
| `models/ChatTurn.kt` | Turn FSM：`Started → AsrListening / AiThinking / AiStreaming / TtsStreaming → Completed / Cancelled` |
| `protocols/Protocols.kt` | 4 个接口：`ChatEventTransport` / `TokenProvider` / `ChatContextProvider` / `ChatHistoryStore` |
| `services/ChatService.kt` | 中央编排器。StateFlow 暴露 `messages / sessionReady / currentTurn / isStreaming / lastError`；事件分派 + 消息持久化 + 历史 200 上限 trim |
| `services/SpeechManager.kt` | 语音会话：mic 预缓冲（等待 `speech.started` ACK 冲刷）+ TTS 期间打断（cancel + clearPlayback）+ ASR 文本同步 |
| `services/ChatRestClient.kt` | REST fallback（POST `/api/chat/message`，bearer auth，30s 超时） |
| `audio/FullDuplexAudioEngine.kt` | 组合 AudioRecord + AudioTrack + APM，跨引擎共享 APM 句柄以保证 far/near 时序对齐 |
| `audio/AudioCaptureEngine.kt` | `VOICE_COMMUNICATION` 采集，20ms（320 样本@16kHz）切帧，可选 Opus 编码 |
| `audio/AudioPlaybackEngine.kt` | `VOICE_COMMUNICATION` 播放，`mute→flush→unmute` 避免爆音，drain 检测 |
| `audio/WebRtcApm.kt` | WebRTC AudioProcessing JNI 封装：10ms 帧 `processStream` + `processReverseStream` |
| `audio/OpusCodec.kt` | libopus 1.4 JNI 封装（`OPUS_APPLICATION_RESTRICTED_LOWDELAY`，CBR 32kbps） |
| `audio/NativeBridge.kt` | JNI 入口。`System.loadLibrary` 失败时标记 `isAvailable=false`，上层自动降级 |
| `persistence/DataStoreChatStore.kt` | DataStore Preferences + Moshi 序列化 `List<ChatMessage>`，保存前 trim 到 limit |
| `config/AIChatConfig.kt` | SDK 配置：服务器 URL、endpoint、providers、historyStore、deviceId、userId、protocolVersion |

### Demo 关键类

| 文件 | 职责 |
|---|---|
| `DemoApp.kt` | Application 单例，持有 `DemoSettings` + `ChatService`；`useRealTransport()` / `useMockTransport()` 切换 |
| `MainActivity.kt` | 入口 Activity，申请 RECORD_AUDIO/CAMERA/READ_MEDIA_IMAGES 权限，加载 `DemoChatScreen` |
| `ui/DemoChatScreen.kt` | 主聊天页：状态栏 + Transport 切换 + 6 模态 picker + 消息列表 + 动态输入栏 |
| `ui/VoiceInputBar.kt` | PTT（pressed 状态反馈）+ 全双工"通话"开关（仅 Voice→Voice 模态） |
| `ui/AttachmentBar.kt` | 文本输入 + 相机/相册选择（最多 3 张，1024px/70% JPEG/base64） |
| `ui/MessageBubble.kt` | 用户右助手左气泡，图片可点开全屏预览，实时 ASR 黄色气泡 |
| `ui/DemoSettingsScreen.kt` | 服务器 URL / JWT / Family ID / Codec 选择 / 重置默认 |
| `ui/DebugEventLogScreen.kt` | 500 条循环缓冲 + 方向箭头 + 时间戳 + 点击查看完整 JSON |
| `transport/DemoEventTransport.kt` | OkHttp WS：`ws(s)://{host}/ws?token=...&device_id=...&family_id=...`，30s ping，断线 1s 重连后回调 `session.init` |
| `transport/MockEventTransport.kt` | 完整离线仿真：字符级 AI 流式、ASR partial→final、TTS 按文本长度计时 |
| `transport/DebugEventLog.kt` | 循环缓冲 + listener 通知（Compose 使用 `DisposableEffect` 订阅） |
| `settings/DemoSettings.kt` | DataStore 持久化 server/token/familyId/codec |
| `util/AttachmentSupport.kt` | 图片压缩（1024px 长边 + 70% JPEG + base64） |

### 6 种会话模态

| 模态 | Transport 事件 | SpeechMode | TTS | 用途 |
|---|---|---|---|---|
| 文字→文字 | `text.send(tts=false)` | - | false | 纯文本对话 |
| 文字→TTS | `text.send(tts=true)` | - | true | 文本发送，朗读回复 |
| 纯 TTS | `tts.synthesize(text)` | - | true | 直接朗读（`trigger=tts_only`） |
| 纯 ASR | `speech.start(mode=asr_only)` | AsrOnly | false | 仅语音识别，无 AI 回复 |
| 语音→文字 | `speech.start(mode=asr_only)` | AsrOnly | false | 语音输入 + AI 文本回复 |
| 语音→语音 | `speech.start(mode=full_duplex)` | FullDuplex | true | 完整对话 + 全双工 barge-in |

### AEC 架构

iOS 使用系统 VPIO（`setVoiceProcessingEnabled(true)`）在同一 AVAudioEngine 上实现硬件 AEC。Android 无对等 API，方案为：

1. `FullDuplexAudioEngine` 内部实例化单个 `WebRtcApm`（16kHz，10ms 帧）
2. 采集线程在 `AudioRecord.read` 后立即 `apm.processStream(mic_10ms)` 去回声/降噪/AGC
3. 播放线程在 `AudioTrack.write` 前同步 `apm.processReverseStream(speaker_10ms)` 注入远端参考
4. 两路共享同一 APM 句柄，AEC3 自动学习时延

**降级链：**
- `libaichat_native.so` 加载成功 + APM 编入 → 最佳 AEC
- `libaichat_native.so` 加载成功但无 APM（submodule 未拉） → libopus 可用，AEC 失效（需避免外放全双工）
- `libaichat_native.so` 加载失败 → Opus/APM 均不可用，强制 PCM + 建议 PTT-only

### Turn FSM 关键细节

- `turn.started(trigger=text)` → 立即 append 空 assistant message（供 delta 累积）
- `turn.started(trigger=voice)` → 等 `asr.final` 到达后才 append user + 空 assistant
- `turn.done` / `turn.cancelled` → 若最后一条 assistant 为空则丢弃（mirror iOS 清理逻辑）
- `ai.text.delta` 增量拼接到 `messages.last()`；`ai.text.done` 提供 `full_text` 作权威覆盖
- `isStreaming` 在 `ai.text.delta` 时 true，在 `ai.text.done` / `turn.done` 时 false

### 测试覆盖

参见 `README.md` 的"测试"章节。10 个测试文件，覆盖协议双向编解码、ChatService 全 FSM、REST MockWebServer、SpeechManager 事件路径、Mock Transport 6 模态、DebugEventLog 循环缓冲、DataStore 持久化、Opus 编解 roundtrip、AudioRecord/Track 烟雾测试。

---

## 与 iOS SDK 的差异点

| 维度 | iOS | Android |
|---|---|---|
| AEC | 系统 VPIO（AVAudioEngine `setVoiceProcessingEnabled`） | WebRTC APM（JNI） |
| Opus | Vendored libopus（XCFramework） | libopus 1.4 JNI（与 APM 同 .so） |
| 异步 | Combine `@Published` + `@MainActor` | Kotlin `StateFlow` + `Dispatchers.Main.immediate` |
| 持久化 | UserDefaults | DataStore Preferences |
| WebSocket | URLSessionWebSocketTask | OkHttp `WebSocket` |
| UI | SwiftUI | Jetpack Compose |
| 引擎复用 | `rearmCapture()` 不停 AVAudioEngine，避免 3 次启动后 0Hz 报错 | AudioRecord 直接 stop/start（Android 无此限制） |
| 打断技巧 | player node `setVolume(0)` + buffer queue reset + `setVolume(1)` | AudioTrack `setVolume(0f) + pause + flush + play + setVolume(1f)` |

协议层面与事件语义完全一致，详见 `docs/protocol-v4.md`。
