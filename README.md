# AIChatSDK-Android

Android 端 SDK 与 Demo，基于 v4 协议（与 [AIChatSDK-iOS](../AIChatSDK-iOS) 对齐），连接 [AIChatServer](../AIChatServer)。

## 模块

- `aisdk/` — 库模块（aar），生成 `libaichat_native.so`（libopus + WebRTC APM）
- `demo/` — 应用模块，含 6 种会话模态、PTT、全双工通话、Mock/真实双 Transport、Debug 事件日志

## 一次性环境准备

1. 安装 Android Studio Hedgehog+ / NDK 26.1+
2. 拉取原生依赖（首次构建前必做）：
   ```bash
   cd aisdk/src/main/cpp
   mkdir -p third_party && cd third_party
   git clone --depth 1 --branch v1.4 https://github.com/xiph/opus.git opus
   git clone --depth 1 --branch v1.3 https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git webrtc-apm
   ```
   未拉取 `webrtc-apm` 时仍可构建——`libaichat_native.so` 仍包含 libopus，AEC 退化为系统 `AcousticEchoCanceler`（在 [SpeechManager](aisdk/src/main/kotlin/com/aichatsdk/services/SpeechManager.kt) 内自动检测）。
3. 在 `local.properties` 配置 `sdk.dir` 与 `ndk.dir`（Android Studio 会自动写入）。

## 构建

```bash
./gradlew :aisdk:assembleRelease
./gradlew :demo:installDebug
```

## 运行 Demo

1. 在 Demo 设置页填入：
   - 服务器 URL：`http://192.168.31.83:8000`（默认）
   - JWT Token
   - Family ID
2. 顶部切换 `WS 真实` 即可连接 [AIChatServer](../AIChatServer)；`Mock 离线` 在飞行模式下也能跑通完整 Turn。
3. 模态切换：6 种（文字↔文字 / 文字→TTS / 纯 TTS / 纯 ASR / 语音→文字 / 语音→语音）。
4. 语音→语音模式下，`通话` 按钮启动全双工，TTS 播放期间按 PTT 触发 barge-in。

## 测试

```bash
# JVM 单元测试（aisdk + demo），无需设备
./gradlew :aisdk:testDebugUnitTest :demo:testDebugUnitTest

# Android instrumented 测试（需连接真机或模拟器，且授予 RECORD_AUDIO）
./gradlew :aisdk:connectedDebugAndroidTest
```

测试覆盖：

| 层 | 文件 | 验证点 |
|---|---|---|
| 协议 | `aisdk/src/test/.../models/AnyValueTest.kt` | AnyValue 7 种类型双向 JSON、嵌套对象、null、helper 访问器 |
| 协议 | `.../models/ChatEventCodecTest.kt` | v4 真实 sample（session.ready/asr.final/error）解码、attachments 编码、ts/id 缺省 |
| 协议 | `.../models/SpeechTypesTest.kt` | wire 字符串映射、SpeechConfig 默认值、ChatMessage/ChatTurn FSM |
| 编排 | `.../services/ChatServiceTest.kt` (Robolectric) | session 生命周期、文本 turn delta 累积、语音 turn 用户/助手消息生成、turn.cancelled 清理空消息、错误传播、200 上限 trim、currentTurn FSM 全状态 |
| REST | `.../services/ChatRestClientTest.kt` (MockWebServer) | bearer 头、context 注入 body、attachments 序列化、未授权/HTTP 错误码 |
| 语音 | `.../services/SpeechManagerEventTest.kt` | ASR 文本更新、speech.stopped 清理、空引擎 feedRemoteAudio 不崩 |
| Mock | `demo/src/test/.../MockEventTransportTest.kt` | session.init→ready、text.send 完整 turn、tts=true 含 TTS 阶段、tts.synthesize 触发 tts_only、speech stop/cancel 行为 |
| 调试 | `demo/src/test/.../DebugEventLogTest.kt` | 监听器通知、500 上限循环缓冲、clear 通知 |
| 持久化 | `aisdk/src/androidTest/.../PersistenceAndNativeInstrumentedTest.kt` | DataStore 双向、limit trim、clear；NativeBridge 加载非崩溃 |
| 音频 | `aisdk/src/androidTest/.../AudioInstrumentedTest.kt` | Opus 编/解 roundtrip、AudioRecord 20ms 帧大小（640B@16k）、AudioTrack feed 不崩 |

## 与 iOS SDK 的差异点

| 维度 | iOS | Android |
|---|---|---|
| AEC | 系统 VPIO（AVAudioEngine setVoiceProcessingEnabled） | WebRTC APM（JNI），未编译时回退系统 AudioEffect |
| Opus | Vendored libopus（XCFramework） | libopus 1.4 JNI（与 APM 同 .so） |
| 异步原语 | Combine `@Published` + `@MainActor` | Kotlin `StateFlow` + `Dispatchers.Main.immediate` |
| 持久化 | UserDefaults | DataStore Preferences |
| WebSocket | URLSessionWebSocketTask | OkHttp `WebSocket` |
| UI | SwiftUI | Jetpack Compose |

协议层面完全一致，详见 [`docs/protocol-v4.md`](docs/protocol-v4.md)。

## 关键文件索引

- 协议事件：`aisdk/src/main/kotlin/com/aichatsdk/models/ChatEvent.kt`
- 中央编排：`aisdk/src/main/kotlin/com/aichatsdk/services/ChatService.kt`
- 全双工音频：`aisdk/src/main/kotlin/com/aichatsdk/audio/FullDuplexAudioEngine.kt`
- WebRTC APM 封装：`aisdk/src/main/kotlin/com/aichatsdk/audio/WebRtcApm.kt`
- JNI：`aisdk/src/main/cpp/jni_bridge.cpp`
- Demo 入口：`demo/src/main/kotlin/com/aichatsdk/demo/MainActivity.kt`

## 实现方案完整文档

参见 [`docs/PLAN.md`](docs/PLAN.md)。
