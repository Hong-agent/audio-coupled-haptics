# 音频耦合触觉播放器（Audio-coupled Haptics）

> 让 **系统正在播放的任何声音**（音乐 / 视频 / 游戏）实时驱动手机振动马达。
> 不需要你自己调用 `Vibrator` —— 振感是被「当成音频播出去」的。

本仓库是一份**完整的实现规格书（AI 构建蓝图）**：目标是「仅凭本仓库即可从零构建出
一个功能完整的 Android 应用」。规格书把「能用 / 不能用」的分水岭级细节全部写明，
包括 3 声道编码、听筒路由、共享 audio session、起播预缓冲、动态压缩算法与故障排查表。

---

## 核心原理

Android 12（API 31）起，系统音频 HAL 支持**音频耦合触觉**：若一段音频被标记为
「带触觉声道」，HAL 会把该声道单独取出，映射为振动电机的驱动波形。

于是实现路径是：

> **抓取系统声音 → 计算一条「振感波形」作为第 3 声道 → 编码为合法的多声道 Ogg/Vorbis（打标记）→ 交回系统播放 → HAL 自动驱动马达。**

## 全链路数据流

```
┌─────────────────────────────────────────────────────────────┐
│ 系统正在播放的音频（音乐 / 视频 / 游戏…）                      │
└───────────────────────────┬─────────────────────────────────┘
                            │ MediaProjection 授权
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ CaptureEngine     AudioRecord 内录 48kHz/16bit/立体声        │
│                   URGENT_AUDIO 优先级，100ms 一块，有界队列   │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ OggSink           累积 500ms 成一段 → 混音 → 编码 → 入队      │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ HapticMixer       高通滤波 → 动态压缩 → 软削波 → [L][R][haptic]│
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ libhapticogg.so   Vorbis 3 声道编码                           │
│                   vorbis_comment_add("ANDROID_HAPTIC=1") ← 关键标记 │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ SegmentPlayer     双 MediaPlayer 接力 + 共享 session + 听筒路由 │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 音频 HAL：分离第 3 声道 → 驱动振动马达                        │
└─────────────────────────────────────────────────────────────┘

旁路：SessionMuter 周期性把「外部原声会话」音量压到 -80dB（消除回声）
```

## 六个决定性设计

| # | 决策 | 一句话原因 |
|---|---|---|
| 1 | 固定 3 声道 + 写入**独立完整**的 `ANDROID_HAPTIC=1` comment | 平台匹配完整条目，键值拼接 API 会写坏 → 能播但**不震** |
| 2 | 播放路由到**听筒** + `USAGE_VOICE_COMMUNICATION` | 部分平台只在通话路径才把第 3 声道转发给马达 |
| 3 | 接力播放器**共享同一个 audio session id** | 否则框架拆 track，播 ~90ms 就被 stop |
| 4 | 起播**预缓冲 ≥ 2 段** | Vorbis 编码常跟不上实时，1 段起播必断流 |
| 5 | 触觉声道 = 高通塑形 + 动态压缩 + 软削波 | 调音量不失效、安静不丢、响段不炸 |
| 6 | 每级**有界队列**，满则丢最旧 | 保证端到端延迟有上限（约 550~650ms） |

## 仓库内容

| 文档 | 说明 |
|---|---|
| [docs/haptic-playback-spec.md](docs/haptic-playback-spec.md) | 实现规格书正文（Markdown，14 章 + 2 附录） |
| [docs/haptic-playback-spec.html](docs/haptic-playback-spec.html) | 规格书网页版（带目录与高亮） |
| [docs/implementation.html](docs/implementation.html) | **实现原理（通俗版）**：不打算动手实现时先读这个 |

规格书覆盖：目标与约束、系统架构、7 个模块（采集 / 编排 / 触觉混音 / 原生编码 /
播放路由 / 静音消回声 / 能力探测与 UI）、构建配置、分阶段验证、故障排查手册、
参数总表，以及给 AI 的「从零构建」执行清单。

## 快速开始构建

```groovy
android {
    namespace 'com.gaolou.boneconduction'
    compileSdk 34
    defaultConfig {
        minSdk 31        // ★ Android 12 起才有音频耦合触觉
        targetSdk 34
        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }
    }
}
```

- 原生库依赖 `libogg` + `libvorbis`，**务必静态链接 C++ 运行时**（`-static-libstdc++`），
  否则会出现 `UnsatisfiedLinkError: libc++_shared.so`。
- 需要权限：`RECORD_AUDIO`、`FOREGROUND_SERVICE`、
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`、`WAKE_LOCK`。
- 构建与排错请按规格书 §10 ~ §12 逐步执行。

## 环境版本参考

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 34 |
| NDK | r26 / r27 |
| Gradle | 8.x |
| 最低系统 | Android 12（API 31） |

## 许可

本仓库文档为原创技术规格，以 **MIT License** 发布（见 [LICENSE](LICENSE)）。
规格书引用的 `libogg` / `libvorbis` 遵循 Xiph.Org 的 BSD 3-Clause 许可。

---

### English

**Audio-coupled Haptics** — turn any system audio (music, video, games) into phone
vibration on Android 12+. The platform's audio HAL extracts a dedicated third
channel from an `ANDROID_HAPTIC=1`-tagged Ogg/Vorbis stream and drives the vibration
motor directly — no `Vibrator` calls needed. This repository is a complete,
AI-oriented build blueprint (Chinese) covering capture, haptic channel synthesis,
native Vorbis encoding, gapless playback with earpiece routing, and
troubleshooting.
