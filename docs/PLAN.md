# AIChatSDK-Android 实现方案

## Context

`AIChatServer` 已完成基于 v4 协议的 AI 服务端（语音输入 / Agent / 语音输出），`AIChatSDK-iOS` 提供了对应的 iOS SDK 与 Demo。当前缺少 Android 端实现，影响家庭多端协同（HomeHub / 家庭成员 App）。本方案在新仓库 `AIChatSDK-Android` 中，**完整复刻** iOS SDK 的全部能力（v4 WebSocket 协议、REST 回退、6 种会话模态、PTT、全双工通话、Opus/PCM 编解码、附件、历史持久化、Mock Transport、Debug 事件日志），保持 API 语义与 iOS 一致，便于跨端心智复用。

协议参考：`/Users/madongpeng/work/AIChatSDK-iOS/docs/protocol-v4.md`
iOS 源参考：`/Users/madongpeng/work/AIChatSDK-iOS/Sources/AIChatSDK/` 与 `/Demo/`

---

## 技术选型（已确认）

| 项目 | 选择 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose |
| minSdk / targetSdk | 24 / 34 |
| 模块 | 单仓 Gradle 多模块：`:aisdk` + `:demo` |
| WebSocket | OkHttp 4.x（`WebSocket` + `WebSocketListener`） |
| JSON | Moshi（KotlinJsonAdapterFactory + 自定义 `AnyValue` 适配器）|
| Opus | **libopus 1.4 JNI**，与 WebRTC APM 同 CMake 工程编进 `libaichat_native.so`（双 ABI），20ms 帧 |
| 音频 I/O | AudioRecord（VOICE_COMMUNICATION）+ AudioTrack（VOICE_CALL usage）|
| AEC/NS/AGC | **WebRTC `AudioProcessing` (APM)** — AEC3 + NS + AGC2，JNI 加载 `libwebrtc_apm.so` |
| 异步 | Kotlin Coroutines + Flow / SharedFlow |
| 持久化 | DataStore Preferences（JSON 序列化消息列表）|
| 图片选择 | ActivityResult API + Coil（缩略图 + 1024px/70% 压缩）|

---

## 仓库结构

```
AIChatSDK-Android/
├── settings.gradle.kts
├── build.gradle.kts                 # 根：Kotlin/AGP 版本目录
├── gradle/libs.versions.toml        # version catalog
├── README.md
├── docs/
│   └── protocol-v4.md               # 从 iOS 仓拷贝，保持单一事实
├── aisdk/                           # 库模块（aar）
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/aichatsdk/
│       ├── config/AIChatConfig.kt
│       ├── models/
│       │   ├── ChatEvent.kt
│       │   ├── ChatMessage.kt
│       │   ├── ChatTurn.kt
│       │   ├── SpeechTypes.kt
│       │   ├── ChatError.kt
│       │   └── AnyValue.kt          # ↔ AnyCodableValue
│       ├── protocols/
│       │   ├── ChatEventTransport.kt
│       │   ├── ChatContextProvider.kt
│       │   ├── ChatHistoryStore.kt
│       │   └── TokenProvider.kt
│       ├── services/
│       │   ├── ChatService.kt       # 中央编排器
│       │   ├── ChatRestClient.kt
│       │   └── SpeechManager.kt
│       ├── audio/
│       │   ├── AudioCaptureEngine.kt
│       │   ├── AudioPlaybackEngine.kt
│       │   ├── FullDuplexAudioEngine.kt
│       │   └── OpusCodec.kt          # 封装 Concentus
│       └── persistence/
│           └── DataStoreChatStore.kt
├── demo/                             # 应用模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml      # RECORD_AUDIO/INTERNET/CAMERA/READ_MEDIA_IMAGES
│       └── kotlin/com/aichatsdk/demo/
│           ├── DemoApp.kt
│           ├── MainActivity.kt
│           ├── ui/
│           │   ├── DemoChatScreen.kt
│           │   ├── DemoSettingsScreen.kt
│           │   ├── DebugEventLogScreen.kt
│           │   ├── AttachmentBar.kt
│           │   ├── MessageBubble.kt
│           │   └── VoiceInputBar.kt   # PTT + 通话切换
│           ├── transport/
│           │   ├── DemoEventTransport.kt   # OkHttp WS + 重连
│           │   └── MockEventTransport.kt   # 离线仿真
│           ├── providers/
│           │   ├── DemoTokenProvider.kt
│           │   └── DemoChatContext.kt
│           ├── settings/DemoSettings.kt    # DataStore
│           └── util/AttachmentSupport.kt   # 压缩 + base64
```

---

## 关键模块设计

### 1. 协议层 (`models/`)

- `ChatEvent(type: String, id: String?, payload: Map<String, AnyValue>, ts: Long?)`
- `AnyValue` 密封类（`Str/Int/Dbl/Bool/Arr/Obj/Null`）+ Moshi `JsonAdapter` 双向转换。**关键**：iOS 用 `AnyCodableValue` 兼容动态 payload，Android 必须等价以承载所有 v4 事件。
- `ChatMessage(id: String, text: String, role: Role, timestamp: Long, attachments: List<ChatAttachment>)`
- `ChatTurn`：内部维护 `MutableStateFlow<ChatTurnState>`（`Started → AsrListening / AiThinking / AiStreaming / TtsStreaming → Completed / Cancelled`）
- `SpeechConfig(mode: SpeechMode, inputCodec: CodecType, outputCodec: CodecType, ttsEnabled: Boolean)`
- `ChatError(code: String, message: String, turnId: String?, fatal: Boolean)`

### 2. 接口 (`protocols/`)

```kotlin
interface ChatEventTransport {
    val isConnected: StateFlow<Boolean>
    var onEvent: ((ChatEvent) -> Unit)?
    var onAudioData: ((ByteArray) -> Unit)?
    fun sendEvent(event: ChatEvent)
    fun sendAudioData(data: ByteArray)
}
interface TokenProvider { suspend fun currentAccessToken(): String? }
interface ChatContextProvider { val contextParameters: Map<String, String> }
interface ChatHistoryStore {
    suspend fun load(): List<ChatMessage>
    suspend fun save(messages: List<ChatMessage>)
    suspend fun clear()
}
```

### 3. ChatService（核心 FSM）

对齐 iOS `Services/ChatService.swift:1-290`：

- 暴露 `StateFlow`：`messages`、`isStreaming`、`isTransportConnected`、`sessionReady`、`sessionId`、`currentTurn`、`lastError`
- 公开方法：`setEventTransport / initiateSession / closeSession / send(text, attachments) / sendViaRest / startSpeech / stopSpeech(action) / appendMessage / clearHistory / loadHistory`
- 事件分派表对齐 iOS（`session.ready / turn.started / turn.done / turn.cancelled / ai.thinking / ai.text.delta / ai.text.done / asr.partial / asr.final / tts.start / tts.done / speech.started / speech.stopped / error`）
- 内存历史上限 200，落盘前 trim 到 `historyLimit`（默认 100）
- 协程作用域：`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`，状态变更全部在主线程

### 4. SpeechManager + 音频管线

复刻 iOS `SpeechManager.swift:1-379` + `FullDuplexAudioEngine.swift:1-375`：

- **AudioCaptureEngine**：`AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, MONO, PCM_16BIT)`，按 20ms（320 样本）切帧；可选 Opus 编码（**libopus JNI**，CBR 32kbps，application=`OPUS_APPLICATION_RESTRICTED_LOWDELAY`，complexity=0）
- **AudioPlaybackEngine**：`AudioTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioFormat.ENCODING_PCM_16BIT, 24000)`，MODE_STREAM；使用 `setVolume(0f)+flush()+setVolume(1f)` 复刻 iOS clearBuffers 不弹爆音技巧
- **FullDuplexAudioEngine**：采集线程读 AudioRecord（16kHz/mono/10ms=160 样本）→ 送入 `WebRtcApm.processStream(nearEnd)`；播放线程每次 `AudioTrack.write` 前先把同一块 PCM 送 `WebRtcApm.processReverseStream(farEnd)` 做远端参考，保证 AEC3 的时序对齐；`stopCapture()` 仅释放 AudioRecord，AudioTrack 保持，避免 TTS 拖尾被截断
- **APM 配置**：`aec_enabled=true`（移动端开 AEC3）、`ns_enabled=true`（中等强度）、`agc_enabled=true`（AGC2 adaptive digital）、`highpass_filter=true`；采样率固定 16kHz
- **JNI 层**：`aisdk/src/main/cpp/` 单 CMake 工程同时编 WebRTC APM + libopus 为一个 `libaichat_native.so`；导出 JNI：APM 侧 `apmCreate/apmProcessStream/apmProcessReverseStream/apmDestroy`，Opus 侧 `opusEncCreate/opusEncode/opusEncDestroy/opusDecCreate/opusDecode/opusDecDestroy`；armeabi-v7a + arm64-v8a 双 ABI；libopus 以 git submodule（`xiph/opus` v1.4）接入
- **打断逻辑**：`startSpeech` 若处于 TTS 播放中，先 `speech.stop(action=cancel)` + `clearBuffers()` 再启动新会话
- **预缓冲**：在 `speech.started` ACK 到达前，将 mic 帧暂存队列，ACK 后冲刷
- **drain 检测**：维护 `pendingFrames` 计数 + `markEndOfStream()`，归零时回调 `onPlaybackDrained`

### 5. ChatRestClient

OkHttp `Call` POST 到 `${baseURL}${chatEndpoint}`（默认 `/api/chat/message`），头部 `Authorization: Bearer ${token}`，请求体 = `contextParameters + {text, attachments?}`，30s 超时，响应解析 `{"text": "..."}`。

### 6. 持久化

`DataStoreChatStore(context, suiteName, storageKey="chat_messages", limit=100)`：Moshi 序列化 `List<ChatMessage>` 到 Preferences DataStore；保存前 trim 末尾 N 条。

---

## Demo 功能清单（与 iOS 对齐）

1. **主聊天页**：6 种模态切换（Text→Text / Text→TTS / Pure TTS / Pure ASR / Voice→Text / Voice→Voice）+ WS/REST 切换 + 状态条（连接/会话/Turn 状态颜色）
2. **文本输入栏**：多行输入 + 附件展开（相机/相册，最多 3 张，1024px/70% JPEG，base64）
3. **语音输入栏**：PTT 按住-松开（300ms 防抖）+ 全双工"通话"开关（仅 Voice→Voice）
4. **设置页**：服务器 URL、JWT、Family ID、Codec 选择（PCM/Opus）、重置
5. **Debug 事件日志**：500 条循环缓冲，方向箭头 + 时间戳，点击查看完整 JSON
6. **实时 ASR 气泡**：partial 文本流式渲染
7. **图片全屏预览**：点开放大、捏合缩放、点击关闭
8. **MockEventTransport**：完整离线仿真（字符级 30ms 流式 AI 文本、ASR partial→final、TTS 时长按文本长度）
9. **DemoEventTransport**：OkHttp WS，URL 形如 `ws(s)://{host}/ws?token=...&device_id=sdk-demo&family_id=...`，30s ping，断线 1s 重连后自动 `session.init`
10. **历史持久化**：DataStore 跨进程复活；"清空"按钮一键擦除

---

## 待修改/新建关键文件

全部为新建（新仓 `/Users/madongpeng/work/AIChatSDK-Android/`）。优先级实现顺序：

1. `gradle/libs.versions.toml` + 两个模块的 `build.gradle.kts`
2. `aisdk` 协议层（`models/` + `protocols/`）→ 可与 iOS 互发 JSON 验证
3. `aisdk` `OpusCodec.kt` + `AudioCaptureEngine.kt` + `AudioPlaybackEngine.kt`
4. `aisdk/src/main/cpp/` WebRTC APM JNI 封装 + `CMakeLists.txt` + 预编译 `libwebrtc_audio_processing.a`（先以源码 git submodule 方式接入 `webrtc-audio-processing` 上游，本地 NDK 构建）
5. `aisdk` `FullDuplexAudioEngine.kt`（远端参考注入）+ `SpeechManager.kt`
5. `aisdk` `ChatService.kt` + `ChatRestClient.kt` + `DataStoreChatStore.kt`
6. `demo` 传输层（`DemoEventTransport.kt` + `MockEventTransport.kt`）
7. `demo` Compose UI（`DemoChatScreen` → `DemoSettingsScreen` → `DebugEventLogScreen`）
8. `demo` `AttachmentSupport.kt`（图片选择/压缩）
9. `README.md`（构建说明 + 与 iOS 的差异点）

---

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| WebRTC APM 的 far/near 时序错位导致 AEC 失效 | 统一以 AudioTrack 的 `getPlaybackHeadPosition()` 校准 far-end 注入时刻；单元测试用固定正弦波回环验证 ERLE ≥ 20dB |
| `libaichat_native.so` 包体积 +3~5MB（APM + libopus） | 仅打 armeabi-v7a + arm64-v8a；发布启用 `android.bundle.abi.enableSplit=true`，单 ABI 下发约 +1.5MB |
| .so 在极少数设备加载失败（SELinux / 非标内核） | JNI 加载失败时回落："PTT-only"模式（禁全双工）+ 强制 `inputCodec=PCM`；UI 提示，不静默降级 |
| 协议演进与 iOS 不同步 | `docs/protocol-v4.md` 由 iOS 仓单向同步；CI 加 hash 校验脚本（后续） |
| WebSocket 二进制帧/文本帧分发 | OkHttp 的 `onMessage(ByteString)` vs `onMessage(String)` 自然分离，对齐协议规范 |

---

## 验证方案（端到端）

1. **单元**：`ChatEvent` ↔ JSON 双向序列化用 iOS 协议文档中的样例做 fixture（含 `attachments`、`null`、嵌套对象）
2. **集成**：以 `MockEventTransport` 跑通 6 种模态的完整 Turn 生命周期断言
3. **真机**：
   - 连 `AIChatServer`（`conda activate homehub` 启动）走 WS：发文本→收 `ai.text.delta` 流式渲染→`turn.done`
   - PTT 录 5 秒"今天天气怎么样"→断言 `asr.partial` 至少 1 次、`asr.final` 出现、`tts` 音频可听
   - Voice→Voice 通话：播放 TTS 时按 PTT 触发打断，验证 `clearBuffers` 不爆音、新 Turn 立即开始
   - 切 Bluetooth 头戴 → 引擎自动重启（监听 `AudioManager.ACTION_AUDIO_BECOMING_NOISY`）
4. **持久化**：发 5 条消息→杀进程→冷启动验证历史回放
5. **Mock 离线**：飞行模式下 `MockEventTransport` 全模态自检
6. **Debug 日志**：所有发送/接收事件带方向、时间戳、JSON 详情
