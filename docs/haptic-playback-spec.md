# 音频耦合触觉播放器 · 实现规格书（AI 构建蓝图）

> **本文档定位**：面向开发者和 AI 的**完整构建蓝图**，目标是「仅凭本文件即可从零构建出完整项目」。
> 若你只想**快速理解原理**（不打算动手实现），请先读通俗版：
> [实现原理（通俗版）](implementation.html)。本规格书亦提供网页版：
> [haptic-playback-spec.html](haptic-playback-spec.html)。
>
> 本文件是一份**面向 AI 的完整技术规格书**。目标是：任何具备 Android 开发能力的 AI
> 或工程师，**仅凭本文件**即可从零构建出一个功能完整的「把系统声音变成马达振动」的应用。
>
> 阅读方式：先读第 0 章理解目标与约束，再按第 2 章架构搭建骨架，
> 然后逐模块（第 3~9 章）实现，最后按第 10~12 章构建、验证、排错。
>
> 术语：本文中"触觉声道 / haptic channel"指多声道音频中被送去驱动振动马达的最后一条声道；
> "内录 / playback capture"指通过 MediaProjection 抓取系统正在播放的音频。

---

## 0. 目标、约束与前置知识

### 0.1 要实现的最终效果

用户在手机上点击"开始"并授权后，**任何正在播放的音乐/视频/游戏声音，都会同步驱动手机振动马达**，
且：
- 端到端延迟低（约 550~650ms，可调对齐）；
- 调整媒体音量时，振动**依然有效**（不会随音量变小而消失）；
- 低音不会"轰"，安静段落不会"丢失"；
- 可消除"原声"，只听重放（避免回声）。

### 0.2 核心原理（一句话）

Android 12（API 31）起支持「**音频耦合触觉**」：
若一段音频被标记为"带触觉声道"，系统音频 HAL 会把该声道单独取出、映射为振动电机的驱动波形。
因此实现路径是：

> **抓取系统声音 → 计算一条"振感波形"作为第 3 声道 → 编码为合法的多声道 Ogg/Vorbis（打标记）→ 交回系统播放 → HAL 自动驱动马达。**

**关键点：不需要自己调用 `Vibrator`。振感是被"当成音频播出去"的。**

### 0.3 硬性约束（必须满足，否则功能退化）

| 约束 | 说明 | 不满足的后果 |
|---|---|---|
| 系统版本 | Android 12+（API 31） | 无此机制，无法实现 |
| 设备支持触觉播放 | 见 §9.1 探测方法 | "能播不震" |
| 马达支持振幅控制 | `hasAmplitudeControl()` | 振感只有开/关 |
| 屏幕录制授权 | 内录系统声音的前提，每次开始都需确认 | 抓不到音频 |
| 特权 shell（可选） | 静音原声需要读 `dumpsys audio` | 会有 ~600ms 回声（预期降级） |

### 0.4 前置知识

- Android 音频：`AudioRecord`、`AudioPlaybackCaptureConfiguration`、`MediaProjection`、
  `MediaPlayer`、`AudioAttributes`、`AudioManager.setCommunicationDevice`。
- 音频编解码基础：PCM、采样率、声道、Vorbis 有损编码、Ogg 封装。
- 数字信号处理基础：高通滤波、RMS、动态压缩、削波。
- JNI / NDK 基础：C++ 编译 `.so` 并被 Java 调用。

---

## 1. 全局设计与关键决策（AI 请先读这里）

这些决策是本项目"能用/不能用"的分水岭，**改变其中任何一条都可能导致功能失效**。

### 决策 1：为什么必须用 3 声道 + `ANDROID_HAPTIC=1` 标记

- 输出音频固定为 **3 声道**：`[L][R][haptic]`，最后一条是振感。
- 必须在 Vorbis 注释头写入**一条恰好等于** `ANDROID_HAPTIC=1` 的 comment 条目。
- 平台匹配的是**完整独立的一条**，不是子串拼接。

> ⚠️ **致命坑**：若用 `vorbis_comment_add_tag(&vc, "ANDROID_HAPTIC", "1")` 之类的
> 键值拼接 API，写出的注释可能粘连成 `ENCODER=ENCODERANDROID_HAPTIC=1…`，
> 平台匹配失败 → **能播但完全不震动**。
> **正确做法**：用 `vorbis_comment_add(&vc, "ANDROID_HAPTIC=1")` 写入完整字符串。

### 决策 2：为什么必须把声音路由到"听筒"

- 在部分（尤其高通平台）设备上，音频 HAL **只在音频流走听筒通话路径时**才把第 3 声道转发给马达；
  走默认扬声器路径时，该声道被静默丢弃 → **无震动**。
- 因此播放时必须：
  1. `AudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION)`；
  2. 找到内置听筒设备 `TYPE_BUILTIN_EARPIECE`；
  3. `AudioManager.setCommunicationDevice(earpiece)`；
  4. 播放器的 `AudioAttributes` 使用 `USAGE_VOICE_COMMUNICATION`
     （这是 `setCommunicationDevice()` 生效的前提）。
- 副作用：声音从听筒出来、音量偏小，属正常。
- 路由必须**周期巡检自愈**：一旦流中断，系统会清掉通信设备，需重新设置。

### 决策 3：为什么无缝播放必须"共享同一个 audio session"

- 用两个 `MediaPlayer` 接力（`cur.setNextMediaPlayer(next)`）实现 gapless。
- **两个播放器必须使用同一个 audio session id**，否则框架会拆掉旧 track，
  表现为"播约 90ms 就被 stop"，链条断裂、震动消失。
- 实现：维护一个 `sharedSessionId`，每个播放器 `setAudioSessionId(sharedSessionId)`，
  `prepare()` 后用 `getAudioSessionId()` 回读真实 id 并复用。
- 完成回调需兼容 `completed == cur` 和 `completed == next` 两种情况。

### 决策 4：为什么要"起播预缓冲"

- 编码器（Vorbis）通常跟不上 1:1 实时速度，若只攒 1 段就起播，
  在第一个分片边界必然断流 → 路由被回收 → 震动消失。
- 正确做法：**攒够至少 2 段再起播**（`START_PREBUFFER = 2`）。

### 决策 5：如何让"调音量不失效 + 安静不丢 + 响段不炸"

三件事用一个"触觉声道生成器"解决（详见 §5）：

1. **高通塑形**：滤掉低频，避免低音轰击马达；
2. **动态压缩**（关键）：RMS 低于阈值原样通过（安静段不丢），高于阈值按比例压缩（响段不炸）；
3. **软削波**：平滑限制峰值，避免硬削平的刺耳失真。

> 不要用"硬阈值门"来降噪——那会让安静段落整段丢失振动。

### 决策 6：延迟上限的保证

- 采集块 → 编码队列 → 播放队列，**每一级都是有界队列**；
- **队列满时丢弃最旧的元素**，而不是阻塞等待；
- 这样即使某环节变慢，端到端延迟也不会无限增长。

---

## 2. 系统架构与数据流

### 2.1 全链路图

```
┌─────────────────────────────────────────────────────────────┐
│ 系统正在播放的音频（音乐/视频/游戏…）                          │
└───────────────────────────┬─────────────────────────────────┘
                            │ MediaProjection 授权
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ CaptureEngine                                                │
│  AudioRecord(内录) 48kHz/16bit/立体声                        │
│  线程优先级 URGENT_AUDIO                                     │
│  每 4800 帧(=100ms) 一块，整块读取(readFully)                │
│  有界队列(4)，满则丢最旧                                     │
└───────────────────────────┬─────────────────────────────────┘
                            │ 100ms 块
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ OggSink（流水线编排）                                        │
│  累积块 → 满 24000 帧(=500ms) 成一段                         │
│  每段调用 HapticMixer 生成 [L][R][haptic]                    │
│  有界编码队列，编码线程消费                                  │
└───────────────────────────┬─────────────────────────────────┘
                            │ 500ms 段（3声道 PCM）
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ HapticMixer（触觉声道生成）                                  │
│  mono = (L+R) * OUTPUT_SCALE                                 │
│  → 级联高通滤波 → 混回少量低频(LOW_SHELF)                    │
│  → 计算 RMS → 动态压缩 → 软削波                              │
│  out = [L, R, haptic]                                        │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ libhapticogg.so（JNI + libVorbis + libogg）                  │
│  vorbis_encode_init_vbr(3声道, 48000, quality)               │
│  vorbis_comment_add("ANDROID_HAPTIC=1")  ← 关键标记          │
│  写出 seg_%06d.ogg                                           │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ SegmentRing（环形文件槽）   seg_%06d.ogg，槽位复用           │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ SegmentPlayer（无缝播放 + 路由）                             │
│  双 MediaPlayer 接力(setNextMediaPlayer)                     │
│  共享 audio session                                          │
│  USAGE_VOICE_COMMUNICATION + 路由到听筒                      │
│  权限策略：ALLOW_CAPTURE_BY_NONE（防回环）                   │
│  50ms 巡检路由，掉了自动重设                                 │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 音频 HAL：分离第 3 声道 → 驱动振动马达                       │
└─────────────────────────────────────────────────────────────┘

旁路：SessionMuter 周期性把"外部原声会话"音量压到 -80dB（消除回声）
```

### 2.2 线程模型

| 线程 | 职责 | 优先级 |
|---|---|---|
| capture-read | AudioRecord 读取 + 整块组装 | URGENT_AUDIO |
| （调用线程）| `onBlock` → 混音 → 累积成段 | 继承 capture |
| ogg-encode | 取段 → 调 JNI 编码 → 入播放队列 | 默认 |
| main/UI Handler | 50ms 播放泵、路由巡检、状态刷新 | UI |
| muteTicker (main) | 每 1500ms 扫描并静音外部会话 | UI |

### 2.3 类清单（建议的模块划分）

| 类 | 职责 |
|---|---|
| `EngineConfig` | 全部不可变常量（参数总表） |
| `EngineSettings` | 运行期可调参数（SharedPreferences 持久化） |
| `CaptureEngine` | MediaProjection + AudioRecord 采集 |
| `HapticMixer` | 触觉声道生成（高通+压缩+削波） |
| `AudioDelayLine` | 音/触觉对齐的环形缓冲 |
| `OggSink` | 流水线编排（累积→混音→编码→入队） |
| `SegmentRing` | 环形文件槽位管理 |
| `SegmentPlayer` | 双 MediaPlayer 接力播放 + 听筒路由 |
| `SessionMuter` | 原声静音（消除回声） |
| `PrivilegedShell` | su / sh 特权命令封装 |
| `Haptics` | 设备能力探测 |
| `HapticOgg` | JNI 声明（加载 `libhapticogg`） |
| `EngineService` | 前台服务，总控全链路 |
| `MainActivity` | 控制面板 UI + 权限请求 |

---

## 3. 模块 1：采集层 CaptureEngine

### 3.1 职责
通过 MediaProjection 获取内录凭据，用 AudioRecord 抓取系统音频，切成固定大小块交给下游。

### 3.2 API 调用序列

```java
// 1) 构造内录配置（只抓媒体类用途）
AudioPlaybackCaptureConfiguration config =
    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        .addMatchingUsage(AudioAttributes.USAGE_GAME)
        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
        .build();

// 2) 计算缓冲
int minBuf = AudioRecord.getMinBufferSize(48000,
        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
int blockBytes = CAPTURE_BLOCK_FRAMES * 2 /*ch*/ * 2 /*bytes*/;
int bufSize = max(minBuf * 2, blockBytes * 4);

// 3) 创建 AudioRecord
AudioRecord record = new AudioRecord.Builder()
        .setAudioPlaybackCaptureConfig(config)
        .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build())
        .setBufferSizeInBytes(bufSize)
        .build();

// 4) 校验状态
if (record.getState() != AudioRecord.STATE_INITIALIZED) { /* 失败处理 */ }

// 5) 读取线程
Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
record.startRecording();
```

### 3.3 关键实现细节

- **整块读取 `readFully`**：`AudioRecord.read` 可能只返回部分数据，
  必须循环补齐，直到取满 `frames * 2` 个 short，否则下游帧数漂移、段长不准。
- **块大小**：`CAPTURE_BLOCK_FRAMES = 4800`（48000Hz 下 = 100ms）。
- **队列背压**：`ArrayBlockingQueue(CAPTURE_QUEUE=4)`；`offer` 失败时 `poll()` 丢最旧再 `offer`
  （**不阻塞**，保证延迟有界）。
- **数据拷贝**：块数组会被复用，必须 `System.arraycopy` 出副本再入队。
- **失败回调**：`onCaptureError(msg)` 由上层停止整条流水线。

### 3.4 为什么按 Usage 过滤

从 Android 10 起录制其它应用音频受管控，`AudioPlaybackCaptureConfiguration` 允许按
Usage 白名单筛选。**只匹配 media/game/unknown/assistant，刻意排除通话、通知等隐私音频**，
既是合规要求，也避免把系统提示音也变成振动。

---

## 4. 模块 2：流水线编排 OggSink

### 4.1 职责
把 100ms 小块累积成 500ms 段，调用混音器生成 3 声道，再交给编码线程。

### 4.2 数据累积逻辑

```
segStereo : short[SEGMENT_FRAMES * 2]     // 原始立体声缓冲
segOut3   : short[SEGMENT_FRAMES * 3]     // 3声道输出缓冲
segFill   : 当前已累积帧数

onBlock(pcm, frames):
    for i in frames:                      // 把本块原始立体声暂存
        segStereo[...] = pcm[i*2], pcm[i*2+1]
    block3 = new short[frames * 3]
    mixer.mix(pcm, frames, block3)        // 生成触觉声道
    copy block3 → segOut3[segFill*3 ..]
    segFill += frames
    if segFill >= SEGMENT_FRAMES: flushSegment()   // 满 500ms 出段
```

### 4.3 编码线程

```
encodeLoop():
    while running:
        data = encodeQueue.take()                 // 阻塞取段
        seq  = segmentSeq.getAndIncrement()
        out  = ring.fileFor(seq)                  // 环形槽位文件名
        t0   = nanoTime()
        rc   = HapticOgg.nativeEncode(out.path, data,
                    SEGMENT_FRAMES, 3, 48000, quality)
        encodeUs = (nanoTime()-t0)/1000
        if rc == 0: player.enqueue(out)
```

- 编码队列同样有界并丢最旧。
- 记录 `encodeUs`（单段编码耗时）供 UI 显示——它是判断"机器能否实时跟得上"的关键指标：
  **若 `encodeUs` 接近或超过 500000（即 500ms），必然断震，需降低 quality 或增大分片。**

### 4.4 参数

| 参数 | 值 | 含义 |
|---|---|---|
| `SEGMENT_MS` | 500 | 分片时长 |
| `SEGMENT_FRAMES` | 24000 | = 48000 * 500/1000 |
| `OUT_CHANNELS` | 3 | L / R / haptic |
| `ENCODE_QUALITY` | 0.5 | Vorbis VBR 质量（0.05~1.0） |
| `MAX_PENDING` | 6 | 播放队列上限 |

---

## 5. 模块 3：触觉声道生成 HapticMixer（最关键算法模块）

### 5.1 职责
把一段立体声 `[L,R]` 转成 3 声道 `[L,R,haptic]`，其中 haptic 是驱动马达的波形。

### 5.2 完整算法（按顺序，逐帧先做 shape、再按块算压缩）

```
输入: stereo[frames*2], frames
输出: out3[frames*3], 返回 haptic 峰值(0..32767)

=== 阶段 A：逐帧塑形 ===
for i in frames:
    l = stereo[i*2]; r = stereo[i*2+1]
    mono = (l + r) * OUTPUT_SCALE                    // 立体声求和 + 固定增益

    if HAPTIC_HIGHPASS:
        x = mono
        for stage in 0..HIGHPASS_ORDER-1:            // 级联一阶高通
            y = hpA * (prevOut[stage] + x - prevIn[stage])
            prevIn[stage] = x; prevOut[stage] = y
            x = y
        mono = x + LOW_SHELF * (l + r) * OUTPUT_SCALE // 混回少量原始低频

    shaped[i] = mono
    sumSq += mono * mono

=== 阶段 B：按块计算压缩增益 ===
rms = sqrt(sumSq / frames) / 32768.0

if HAPTIC_COMPRESS and rms > COMPRESS_THRESHOLD and rms > 1e-6:
    over       = COMPRESS_THRESHOLD / rms
    target     = pow(over, 1 - 1/COMPRESS_RATIO)      // 目标增益 < 1
    coef       = (target < compGain) ? COMPRESS_ATTACK : COMPRESS_RELEASE
    compGain  += coef * (target - compGain)           // 平滑
else:
    compGain  += COMPRESS_RELEASE * (1 - compGain)    // 缓慢恢复到 1.0

gated = rms < HAPTIC_GATE_RMS                          // 近静音

=== 阶段 C：逐帧输出 ===
for i in frames:
    raw = shaped[i] * compGain
    if gated:      h = 0
    else if soft:  h = softClip(raw)
    else:          h = hardClip((int)raw)

    out3[i*3+0] = muteLr ? 0 : l
    out3[i*3+1] = muteLr ? 0 : r
    out3[i*3+2] = h
    hapticPeak = max(hapticPeak, abs(h))
```

### 5.3 高通滤波系数

```
R = 1 / (2π * fc / fs)
a = R / (R + 1)
```
- `fc = HAPTIC_HIGHPASS_HZ`（默认 250）
- `fs = 48000`
- 级联 N 阶（默认 2）→ 滚降 N×6 dB/倍频。

### 5.4 软削波（有理函数近似 tanh）

```
f  = clamp(x/32768, -LIMIT, +LIMIT)      // LIMIT = SOFT_CLIP_LIMIT (0.99)
f2 = f*f
y  = f * (f2 + 27) / (9*f2 + 27)
out= clamp((int)(y*32767), -32768, 32767)
```

### 5.5 参数表（默认值，可按听感调整）

| 参数 | 默认 | 作用 | 调大 | 调小 |
|---|---|---|---|---|
| `HAPTIC_OUTPUT_SCALE` | 0.65 | 触觉总电平 | 更强（易打底） | 更弱 |
| `HAPTIC_GATE_RMS` | 0.02 | 静音门槛 | 安静段易丢 | 底噪可能微震 |
| `HAPTIC_HIGHPASS_HZ` | 250 | 高通截止 | 更低频、更"脆" | 低频更多、更"轰" |
| `HAPTIC_HIGHPASS_ORDER` | 2 | 高通阶数 | 低频更干净 | 低频残留更多 |
| `HAPTIC_LOW_SHELF` | 0.12 | 低频混回量 | 力度更强 | 更"脆" |
| `SOFT_CLIP_LIMIT` | 0.99 | 软削波上限 | 更响（易失真） | 更保守 |
| `COMPRESS_THRESHOLD` | 0.35 | 压缩阈值 | 更少压缩（易打底） | 更早压缩 |
| `COMPRESS_RATIO` | 3.0 | 压缩比 | 压得更狠 | 压得更轻 |
| `COMPRESS_ATTACK` | 0.4 | 压缩响应 | 更快（可能抽吸） | 更平滑 |
| `COMPRESS_RELEASE` | 0.08 | 恢复响应 | 恢复更快 | 更平滑 |

### 5.6 为什么要这套算法（给 AI 的设计意图）

- **单纯复制原声**：低音能量最大 → 马达被低音独占、轰击感强。
- **只用硬门槛降噪**：安静段落（尤其古典、民谣）整段没有振动 → "丢音频"。
- **只用硬削波**：峰值截断 → 刺耳"打底"失真。
- **本方案**：高通去掉低音霸占 + 压缩平衡动态（安静保留、响段压住）+ 软削波平滑峰值，
  最终得到"均衡、连续、不打底"的振感，适合手机放桌面时的共振体验。

---

## 6. 模块 4：原生编码 libhapticogg.so

### 6.1 依赖
- libogg 1.3.5（Xiph.Org，BSD）
- libvorbis 1.3.7（Xiph.Org，BSD）

两者源码可直接内置于 `third_party/`。

### 6.2 JNI 契约

```java
// Java 侧
public static native int nativeEncode(String path, short[] pcm,
        int frames, int channels, int sampleRate, float quality);
// 返回 0 成功，负数失败
// pcm 长度必须 >= frames * channels，交错排列
```

C++ 侧函数名（严格按包名映射）：
```
Java_com_gaolou_boneconduction_nativebridge_HapticOgg_nativeEncode
```
（若你的包名不同，函数名前缀必须相应改变。）

### 6.3 编码流程（决定性细节已标注）

```cpp
vorbis_info_init(&vi);
vorbis_encode_init_vbr(&vi, channels, sampleRate, quality);   // VBR

vorbis_comment_init(&vc);
vorbis_comment_add(&vc, "ENCODER=vorbis");
if (channels == 3)
    vorbis_comment_add(&vc, "ANDROID_HAPTIC=1");   // ★★ 必须完整独立一条

vorbis_analysis_init(&vd, &vi);
vorbis_block_init(&vd, &vb);
ogg_stream_init(&os, rand() & 0x7fffffff);

// 写三个头页，用 flush 立即刷盘（降低首播延迟）
vorbis_analysis_headerout(&vd, &vc, &h0, &h1, &h2);
ogg_stream_packetin(&os, &h0); ...packetin(&os, &h2);
flush all pages → file

// 分块喂入：每块 1024 帧，归一化到 float[-1,1]
while (remaining > 0) {
    chunk = min(remaining, 1024);
    float **buffer = vorbis_analysis_buffer(&vd, chunk);
    for i in chunk:
        for c in channels:
            buffer[c][i] = (float)pcm[(pos+i)*channels + c] / 32768.0f;
    vorbis_analysis_wrote(&vd, chunk);
    // 排空已就绪的包写文件
    while (vorbis_analysis_blockout(&vd, &vb) == 1) {
        vorbis_analysis(&vb, nullptr);
        vorbis_bitrate_addblock(&vb);
        while (vorbis_bitrate_flushpacket(&vd, &op)) {
            ogg_stream_packetin(&os, &op);
            pageout → file
        }
    }
}
vorbis_analysis_wrote(&vd, 0);   // 结束流
drain + flush final page
```

### 6.4 线程安全
用全局 `std::mutex` 包住整个 `nativeEncode`（本项目单编码线程，加锁更稳）。

### 6.5 资源释放顺序（勿漏）
```
fclose(f);
ogg_stream_clear(&os);
vorbis_block_clear(&vb);
vorbis_dsp_clear(&vd);
vorbis_comment_clear(&vc);
vorbis_info_clear(&vi);
```

### 6.6 链接注意
若用 C++ 运行时，务必静态链接 C++ 库（如 `-static-libstdc++`），
否则 `.so` 会依赖 `libc++_shared.so`，而 APK 未打包该库时运行期报
`UnsatisfiedLinkError: library "libc++_shared.so" not found`。

---

## 7. 模块 5：播放与路由 SegmentPlayer（另一个关键模块）

### 7.1 状态
```
pending      : ArrayDeque<File>        // 待播段队列
cur, next    : MediaPlayer             // 当前 / 下一个
sharedSessionId : int                  // 全段共享的 audio session
earpiece     : AudioDeviceInfo         // 内置听筒
running      : boolean
playedCount, droppedCount : long
```

### 7.2 播放泵（每 50ms 执行）
```
pump():
    ensureEarpieceRouting()   // 路由掉了就重设（自愈）
    advanceIfNeeded()         // 需要则启动新段
    postDelayed(pump, 50)
```

### 7.3 启动一段（含预缓冲）
```
advanceIfNeeded():
    if cur != null: return
    if pending.size() < START_PREBUFFER(=2): return   // ★ 预缓冲
    f = pending.poll()
    cur = open(f)
    cur.setOnCompletionListener(onSegmentCompleted)
    cur.start()
    playedCount++
    preloadNext()
```

### 7.4 预挂下一段（gapless 接力）
```
preloadNext():
    if next != null: return
    f = pending.poll(); if f == null: return
    next = open(f)
    next.setOnCompletionListener(onSegmentCompleted)
    cur.setNextMediaPlayer(next)     // ★ 平台负责无缝切换
```

### 7.5 完成回调（必须兼容两种身份）
```
onSegmentCompleted(completed):
    if completed == cur:  release(cur); cur = next; next = null
    else if completed == next: release(cur); cur = next; next = null
    else: return
    if cur != null:
        cur.setOnCompletionListener(onSegmentCompleted)
        playedCount++
        preloadNext()
    // 若队列空且无后继，释放并置空，等待新数据时重启
    if cur != null and next == null and pending.isEmpty():
        release(cur); cur = null
```

### 7.6 打开一个播放器
```
open(file):
    mp = new MediaPlayer()
    attrs = new AudioAttributes.Builder()
        .setUsage(USAGE_VOICE_COMMUNICATION)        // ★ 听筒路由前提
        .setContentType(CONTENT_TYPE_SPEECH)
        .setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)  // ★ 防回环
        .setHapticChannelsMuted(false)              // ★ 允许触觉声道输出
        .build()
    mp.setAudioAttributes(attrs)
    mp.setAudioSessionId(sharedSessionId)           // ★ 共享 session
    mp.setDataSource(file.path)
    mp.prepare()
    sharedSessionId = mp.getAudioSessionId()        // 回读并复用
    return mp
```

### 7.7 听筒路由
```
routeToEarpiece():
    audioManager.setMode(MODE_IN_COMMUNICATION)

ensureEarpieceRouting():                            // 周期调用，自愈
    if earpiece == null:
        for d in audioManager.getDevices(GET_DEVICES_OUTPUTS):
            if d.type == TYPE_BUILTIN_EARPIECE: earpiece = d; break
    if earpiece == null: return
    active = audioManager.getCommunicationDevice()
    if active == null or active.id != earpiece.id:
        audioManager.setCommunicationDevice(earpiece)
```

### 7.8 入队与背压
```
enqueue(file):
    while pending.size() >= MAX_PENDING(=6):
        dropped = pending.poll(); droppedCount++; dropped.delete()
    pending.offer(file)
```

### 7.9 停止
释放两个播放器、清空队列、`clearCommunicationDevice()`、`setMode(MODE_NORMAL)`。

---

## 8. 模块 6：消除回声 SessionMuter + 特权 shell

### 8.1 问题
"抓取 → 重放"意味着用户会同时听到：原始声 + 约 500ms 延迟的重放声 → 回声。
需要在**会话层**把外部应用的原声压掉。

### 8.2 实现方式

```
poll(ownSessionSupplier):
    foreign = discoverForeignSessions()   // 读 dumpsys audio 解析会话 id
    if foreign == null: active = false; return   // 无特权 → 降级
    释放已消失的会话效果
    对每个新外部会话 attachMute(sessionId)
    active = 是否挂上了至少一个
```

**静音手段（按优先级）**：
1. `DynamicsProcessing`：`Config.setInputGainAllChannelsTo(-80f)`，`setEnabled(true)`。
2. 回退：反射构造隐藏的 `android.media.audiofx.Volume`，`setParameter(PARAM_MUTE=2, 1)` +
   `setParameter(PARAM_LEVEL=0, -9600)`（-96dB）。

**自我保护**：绝不静音自己的播放会话（用 `ownSessionIds` 排除）。

### 8.3 特权 shell 探测

```
detect():
    尝试 su -c id          → 输出含 "uid=0"  → 机制 = "su"
    否则 sh -c "dumpsys audio | head -n 1" → 含 "Audio" → 机制 = "shizuku"
    否则 unavailable（降级：静音不生效、会有回声，属预期）
```

- 会话解析用正则从 `dumpsys audio` 输出里提取 sessionId 与 uid。
- 轮询周期 `MUTE_POLL_MS = 1500`。

---

## 9. 模块 7：设备能力探测与总控/UI

### 9.1 设备能力探测（Haptics）

```java
// 触觉播放是否支持（不支持则"能播不震"）
boolean hapticSupported = vibrator.areAllPrimitivesSupported();
// 马达是否支持振幅控制（不支持则只有开/关）
boolean amplitudeControl = vibrator.hasAmplitudeControl();
```
Android 12+ 通过 `VibratorManager.getDefaultVibrator()` 取 Vibrator。

**启动时探测并提示用户**，避免用户误以为软件故障。

### 9.2 前台服务 EngineService

- 使用 `startForeground`，类型包含 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`
  （Android 14+ 需显式声明，否则崩溃）。
- 持有 `PARTIAL_WAKE_LOCK`，息屏继续运行。
- 注册 `MediaProjection.Callback.onStop()`：系统停止录屏时要优雅收尾。
- 启动顺序：`ring.clean()` → `capture.start()` → `sink.start()` → 启动静音 ticker。
- 通过 `StatusListener` 把状态（是否运行、能力、队列、编码耗时、触觉电平）推给 UI。

### 9.3 UI（MainActivity）建议控件

- 开始/停止按钮；首次开始请求 **录音权限** + **通知权限**，再请求 **MediaProjection**。
- 开关：静音原声、软削波。
- 滑杆：对齐延迟（±2000ms）。
- 状态区：能力探测结果、队列长度、编码耗时、触觉电平。
- 建议提供"参数总表"显示，便于调音。

---

## 10. 构建配置

### 10.1 Android 工程

```groovy
android {
    namespace 'com.gaolou.boneconduction'
    compileSdk 34
    defaultConfig {
        applicationId "com.gaolou.boneconduction"
        minSdk 31                      // ★ Android 12 起
        targetSdk 34
        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
```

### 10.2 原生库构建（两种方式）

**方式 A：标准 CMake（正常环境推荐）**

`app/src/main/cpp/CMakeLists.txt` 大致：
```cmake
cmake_minimum_required(VERSION 3.22)
project(hapticogg)
add_library(hapticogg SHARED haptic_ogg.cpp
    ${OGG_SRC}/bitwise.c ${OGG_SRC}/framing.c
    ${VORBIS_SRC}/... /* libvorbis 全部 .c */)
target_include_directories(hapticogg PRIVATE
    ${OGG_INC} ${VORBIS_INC})
find_library(log-lib log)
target_link_libraries(hapticogg ${log-lib})
```
并在 `build.gradle` 开启：
```groovy
externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }
```

**方式 B：离线 clang 直编（CMake 不可用时）**
写脚本直接用 NDK clang 编译 libogg + libvorbis + JNI，产出
`app/src/main/jniLibs/<abi>/libhapticogg.so`，Gradle 直接打包。

**务必静态链接 C++ 运行时**（`-static-libstdc++`），详见 §6.6。

### 10.3 权限（AndroidManifest）

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<!-- 触觉相关（系统能力，无需自定义权限） -->
```

### 10.4 环境版本参考

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 34 |
| NDK | r26/r27（任一可用版本） |
| Gradle | 8.x |

---

## 11. 验证方法（构建后如何确认可用）

### 11.1 分阶段验证

1. **编译验证**：`./gradlew :app:assembleDebug` 成功，产出 APK。
2. **SO 自包含验证**：确认 `.so` 不依赖 `libc++_shared.so`
   （用 `llvm-readelf -d libhapticogg.so | grep NEEDED`，应只剩系统库）。
3. **Comment 验证**：编码一段后，检查生成的 `.ogg` 的 Vorbis comment 里
   存在**独立的一条 `ANDROID_HAPTIC=1`**（可用 `ffprobe`/自写解析）。
4. **采集验证**：日志确认 `AudioRecord` 初始化成功、块持续到来。
5. **编码耗时验证**：UI/日志里 `encodeUs` 应稳定 < 500000（500ms）。
6. **端到端验证**：播放音乐，用手感 + 日志（played 计数持续增长）确认震动连续。

### 11.2 关键日志锚点（建议）

- `capture started, bufSize=...`
- `media routed to earpiece for haptic output`
- `encode failed rc=...`（出现即编码异常）
- `muted session <id> via DynamicsProcessing`（静音生效）
- `no privileged shell available`（静音降级，正常）

---

## 12. 故障排查手册（症状 → 原因 → 处置）

| 症状 | 最可能原因 | 处置 |
|---|---|---|
| **能播不震** | ① comment 不是独立完整的一条 `ANDROID_HAPTIC=1`；② 设备不支持触觉播放；③ 未走听筒路由 | 用 `vorbis_comment_add` 写完整串；探测设备能力；强制路由到听筒 + `USAGE_VOICE_COMMUNICATION` |
| **完全无声** | 未授权屏幕录制 / DRM 保护内容 / 采集失败 | 检查授权；换普通音频源 |
| **震动只持续约 1 秒或几段后消失** | 接力链断裂 / 队列抽空 / 路由被回收 | 共享 audio session；完成回调兼容 cur/next；起播预缓冲 ≥2；50ms 路由自愈 |
| **播约 90ms 就被 stop** | 两段播放器 session 不同，框架拆 track | 统一 `sharedAudioSessionId` |
| **震动断续、周期卡顿** | 编码耗时 ≥ 分片时长，实时性跟不上 | 降低 `ENCODE_QUALITY`；增大 `SEGMENT_MS`；检查 `encodeUs` |
| **有回声（原声+延迟声）** | 无特权，原声未被静音 | 授权 root/Shizuku；或接受降级 |
| **低音太轰** | 低频未滤除 | 提高 `HAPTIC_HIGHPASS_HZ`、增加 `HIGHPASS_ORDER`、降低 `LOW_SHELF` |
| **安静段落无振动** | 门槛过高 | 降低 `HAPTIC_GATE_RMS` |
| **响段"打底"（刺耳）** | 电平过高/削波过载 | 降低 `HAPTIC_OUTPUT_SCALE`、降低 `COMPRESS_THRESHOLD`、启用软削波 |
| **调音量后震动变弱消失** | 未做动态处理，触觉随绝对电平走 | 引入压缩器（§5.2），使振动跟随"相对能量" |
| **`UnsatisfiedLinkError: libc++_shared.so`** | C++ 运行时未静态链接 | 链接加 `-static-libstdc++` |
| **Android 14+ 启动前台服务崩溃** | 未声明 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` | 显式声明服务类型 |
| **息屏后失效** | 无 wake lock | 持 `PARTIAL_WAKE_LOCK` |

---

## 13. 参数总表（可调项汇总）

| 分组 | 参数 | 建议默认 | 备注 |
|---|---|---|---|
| 格式 | SAMPLE_RATE | 48000 | 固定 |
| 格式 | CAPTURE_BLOCK_FRAMES | 4800 | ~100ms |
| 分片 | SEGMENT_MS / FRAMES | 500 / 24000 | |
| 分片 | OUT_CHANNELS | 3 | L/R/haptic |
| 编码 | ENCODE_QUALITY | 0.5 | 权衡清晰度与耗时 |
| 队列 | MAX_PENDING / CAPTURE_QUEUE | 6 / 4 | 有界，丢最旧 |
| 队列 | START_PREBUFFER | 2 | 防抽空 |
| 触觉 | HAPTIC_OUTPUT_SCALE | 0.65 | 总电平 |
| 触觉 | HAPTIC_GATE_RMS | 0.02 | 静音门槛 |
| 触觉 | HAPTIC_HIGHPASS_HZ / ORDER | 250 / 2 | 低频压制 |
| 触觉 | HAPTIC_LOW_SHELF | 0.12 | 低频混回 |
| 触觉 | SOFT_CLIP_LIMIT | 0.99 | 软削波上限 |
| 压缩 | COMPRESS_THRESHOLD | 0.35 | |
| 压缩 | COMPRESS_RATIO | 3.0 | |
| 压缩 | COMPRESS_ATTACK / RELEASE | 0.4 / 0.08 | |
| 静音 | MUTE_GAIN_DB / MUTE_POLL_MS | -80 / 1500 | |
| 对齐 | DELAY_MS_MIN / MAX | -2000 / 2000 | |

---

## 14. 给 AI 的"从零构建"执行清单

按此顺序推进，每步可独立验证：

1. 建 Android 工程（minSdk 31），配置权限与前台服务类型。
2. 实现 `Haptics` 探测并显示能力。
3. 实现 `CaptureEngine`：能抓到系统音频并打出块日志。
4. 引入 libogg + libvorbis，写 JNI `nativeEncode`，**先做 2 声道**验证编码可用、
   能被播放器播放。
5. 改为 3 声道并写入 `ANDROID_HAPTIC=1`，检查 comment 正确。
6. 实现 `HapticMixer`（先只做 `(L+R)*scale`，再逐步加高通/压缩/软削波）。
7. 实现 `SegmentRing` + `SegmentPlayer`：先单播放器顺序播放，确认有声音；
   再改双播放器接力 + 共享 session + 听筒路由，确认震动连续。
8. 加预缓冲、路由自愈、背压丢最旧。
9. 实现 `SessionMuter` + `PrivilegedShell`（可选，消回声）。
10. 做 UI 与参数持久化，按 §11 逐项验证，按 §12 排错。

---

## 附录 A：签名的数学定义

- 一阶高通：`y[n] = a*(y[n-1] + x[n] - x[n-1])`，`a = R/(R+1)`，`R = 1/(2π·fc/fs)`
- RMS：`sqrt(Σx²/N)/32768`
- 压缩目标增益：`(threshold/rms)^(1 - 1/ratio)`
- 软削波：`y = f·(f²+27)/(9f²+27)`，`f = clamp(x/32768, ±limit)`

## 附录 B：许可

本文档为原创技术规格，可自由使用与再分发（建议 CC BY 4.0 或 MIT）。
libogg / libvorbis 遵循 Xiph.Org 的 BSD 3-Clause 许可。
