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
| `app/` | **可构建的 Android 应用工程**（Java + NDK，按本规格书实现，见下节） |
| `tonegen/` | 自检工具 App：前台媒体服务播放测试音，用来验证「外部声音 → 马达」链路 |
| [docs/haptic-playback-spec.md](docs/haptic-playback-spec.md) | 实现规格书正文（Markdown，14 章 + 2 附录） |
| [docs/haptic-playback-spec.html](docs/haptic-playback-spec.html) | 规格书网页版（带目录与高亮） |
| [docs/implementation.html](docs/implementation.html) | **实现原理（通俗版）**：不打算动手实现时先读这个 |
| [docs/app-guide.md](docs/app-guide.md) | 应用使用说明、界面结构与参数即时生效机制 |
| `tools/` | 主机侧自检脚本（编码 + `ANDROID_HAPTIC=1` 标记校验） |

规格书覆盖：目标与约束、系统架构、7 个模块（采集 / 编排 / 触觉混音 / 原生编码 /
播放路由 / 静音消回声 / 能力探测与 UI）、构建配置、分阶段验证、故障排查手册、
参数总表，以及给 AI 的「从零构建」执行清单。

---

## 已经实现：Android 应用

`app/` 是一份按规格书 §14 清单从零落地的完整工程，可构建出可直接安装的 APK，
把规格里的 7 个模块全部写成真实代码，并把「参数可调」做成了**拖动即生效**。

### 界面与交互

单页深色控制台（自绘 + framework 控件，无第三方 UI 依赖）：

| 区域 | 内容 |
|---|---|
| 顶部 | 应用名 + 运行状态徽章（待机 / 启动中 / 运行中 / 异常） |
| 主控球 | 中心为启动/停止按钮，外圈随触觉电平旋转呼吸，内部竖条实时反映驱动波形 |
| 实时状态卡 | 采集时长、编码耗时、平均编码、播放分片、待播队列、触觉峰值、压缩增益、静音会话数 |
| 参数区 | 调音预设（均衡 / 强节奏 / 清脆 / 轻柔 / 自定义）+ 4 组滑杆与开关 |
| 底部日志 | 能力探测、设备型号、各级队列深度、丢段数、静音后端、编码失败数 |

采集方式：**只录制系统内声音**。授权走 Android 要求的 `MediaProjection`（唯一合法入口），
但代码里不创建 `VirtualDisplay`、不读取任何画面；授权页建议选「共享一个应用」，
只把正在放歌的那个 App 的声音交出来。应用内文案、通知栏都写作「录制系统内声音」。

### 参数即时生效机制

没有「应用」按钮：滑杆写入 `EngineSettings`（volatile + SharedPreferences），
各线程下一次读取时自然拿到新值。

| 参数组 | 生效时机 | 实现位置 |
|---|---|---|
| 震动强度 / 高通截止 / 高通阶数 / 低频混回 / 安静门槛 / 软削波 | **下一个分片**（默认 ≤500ms） | `HapticMixer.mix()` 每段重读参数 |
| 动态压缩五参数 + 压缩开关 | 下一个分片 | 同上，压缩增益逐段平滑更新 |
| 编码质量 | 下一个分片 | `OggSink.encodeLoop()` 每段重读 quality |
| 分片时长 | 下一块（≤100ms），缓冲按需扩容 | `OggSink.pipelineLoop()` 每块读 `segmentFrames()` |
| 播放队列上限 / 采集队列上限 | 下一次入队 | `BoundedQueue` 用 `IntSupplier` 动态取容量 |
| 起播预缓冲 | 下一次起播（自动夹到队列上限内） | `SegmentPlayer.advanceIfNeeded()` |
| 震动对齐 ±2000ms | 下一个分片 | `AudioDelayLine.process()` 只改读取偏移 |
| 静音原声 / 轮询周期 / 静音深度 | 下一拍巡检（周期本身可调） | `EngineService.muteTicker` + `SessionMuter` |

切换预设或「恢复出厂默认」会一次性改写整套参数并同步回 UI（滑杆与开关都跟着走）。

### 构建

```bash
# 需要 JDK 17 + Android SDK(platform-34 / build-tools 34.0.0) + NDK r26 + CMake 3.22.1
cd audio-coupled-haptics

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=/home/t/android-sdk \
/home/t/gradle-dist/gradle-8.7/bin/gradle :app:assembleDebug

# 产物与安装
app/build/outputs/apk/debug/app-debug.apk
/home/t/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle` 已配置阿里云 Maven 镜像（本机 `dl.google.com` 极慢/不可用）；
原生库用 `-DANDROID_STL=c++_static` 静态链接 C++ 运行时，产物不依赖 `libc++_shared.so`。

### 验证记录（均在本机执行）

| 项目 | 命令 | 结果 |
|---|---|---|
| 编译 | `gradle :app:assembleDebug` | ✅ 产出 APK（含 arm64-v8a + armeabi-v7a 的 `libhapticogg.so`） |
| SO 自包含 | `llvm-readelf -d libhapticogg.so` | ✅ 仅依赖 `liblog/libm/libdl/libc`，无 `libc++_shared.so` |
| Comment 标记 | `bash tools/run_host_encode_test.sh` | ✅ 找到**独立完整**的一条 `ANDROID_HAPTIC=1` |
| 触觉算法 | `gradle :app:testDebugUnitTest` | ✅ 6/6 通过（安静归零、响段压缩、改参数即时生效、阶数切换稳定、只留振动） |
| 静态检查 | `gradle :app:lintDebug` | ✅ 0 error |

### 真机验证（Redmi 25102RKBEC · Android 16 / API 36）

| 验证项 | 观测到的证据 |
|---|---|
| 崩溃修复 | 授权后不再闪退，采集线程稳定跑满 100ms/块 |
| 内录系统声音 | `OggSink trace input=0.43 rms=0.24`（测试音 App 播放时） |
| 触觉声道生成 | `hapticPeak=0.77`，压缩按阈值自动介入（`gain` 随电平从 1.00 降到 0.73） |
| 3 声道被系统识别 | `dumpsys audio`：我们的 track `channelMask=0x20000003`（FL+FR+**HAPTIC_A**） |
| HAL 分离触觉通道 | `dumpsys media.audio_flinger`：`Haptic channel mask: 0x20000000 (haptic-A)` |
| 设备能力探测 | 应用内 `触觉播放=支持  振幅=支持` |
| 参数即时生效 | 拖动「震动强度」0.65→1.5：输入电平当场 43%→99%、压缩增益自动 0.73×；点「恢复出厂默认」后精确回落 99%→43% |

| **马达振动** | ✅ 已确认有振动（真机手感） |
| 高音破音 | 提供两种可选手段（**默认关闭**，保持原有宽频手感）：输出级低通（削波之后生效）与谐振载波驱动（包络 × 谐振正弦） |

### 让它真正震起来的三处关键修复

1. `AudioAttributes` **默认会静音触觉声道**，必须显式 `.setHapticChannelsMuted(false)`，
   否则 3 声道里的 haptic 通道根本到不了 HAL（表现为「能播不震」）。
2. 听筒路由不能只在「系统上报的通信设备不是听筒」时才下发——HyperOS 会上报"已是听筒"
   而实际仍走扬声器，因此改为周期性强制下发，并逐 track 调 `setPreferredDevice(earpiece)`。
3. `USAGE_VOICE_COMMUNICATION` 的音量由 `STREAM_VOICE_CALL` 决定，停在 1/11 时 HAL
   同样不驱动马达；引擎运行期间自动抬到 90%，停止时还原用户原值。

验证手法（不需要靠手感）：`dumpsys media.audio_flinger` 里找到带
`Haptic channel mask: 0x20000000 (haptic-a)` 的 output thread，它的
`Output devices` 应同时是 `AUDIO_DEVICE_OUT_EARPIECE`，且 `Last write occurred`/`Total writes`
实时增长；`force-stop` 本应用后写入立即停止，即可证明数据来自本应用。

自检步骤：装好两个 APK → 主应用里点「启动」并授权 → 打开「触觉测试音」保持播放
（它跑在前台媒体服务里，可切后台）→ 回到主应用看电平跳动、同时感受振动。
测试音 App 不需要时可卸载：`adb uninstall com.gaolou.haptictone`。

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
