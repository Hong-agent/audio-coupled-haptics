package com.gaolou.boneconduction.engine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

import com.gaolou.boneconduction.core.EngineConfig;
import com.gaolou.boneconduction.core.EngineSettings;
import com.gaolou.boneconduction.core.EngineStatus;

/**
 * 无缝播放与听筒路由（规格书 §7）。
 *
 * <p>三个不能改的点：双 MediaPlayer 接力、共享同一个 audio session id、
 * 播放路由到内置听筒（{@code USAGE_VOICE_COMMUNICATION}）。
 * 播放泵每 50ms 执行一次：先做路由自愈，再判断是否需要起播新段。
 */
public final class SegmentPlayer {

    private static final String TAG = "SegmentPlayer";

    public interface ErrorListener {
        void onPlaybackError(String message);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final EngineSettings settings;
    private final EngineStatus status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ErrorListener errorListener;

    private final BoundedQueue<File> pending;

    private MediaPlayer cur;
    private MediaPlayer next;
    private int sharedSessionId = 0;
    private AudioDeviceInfo earpiece;
    private boolean running;
    private int lastReportedCommId = Integer.MIN_VALUE;
    private int routeTicks;
    private boolean lastPreferredOk;
    private int savedVoiceCallVolume = -1;

    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            ensureEarpieceRouting();
            advanceIfNeeded();
            status.pendingSegments = pending.size();
            handler.postDelayed(this, EngineConfig.PUMP_INTERVAL_MS);
        }
    };

    public SegmentPlayer(Context context, EngineSettings settings, EngineStatus status,
                         ErrorListener errorListener) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.status = status;
        this.errorListener = errorListener;
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.pending = new BoundedQueue<>(settings::maxPending);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        routeToEarpiece();
        // 起播之前先建立通信设备，避免首段还走在扬声器上
        ensureEarpieceRouting();
        handler.post(pump);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pump);
        releaseQuietly(cur);
        releaseQuietly(next);
        cur = null;
        next = null;
        pending.clear();
        try {
            if (audioManager != null) {
                audioManager.clearCommunicationDevice();
                audioManager.setMode(AudioManager.MODE_NORMAL);
                restoreVoiceCallVolume();
            }
        } catch (Exception ignored) {
        }
    }

    public void enqueue(File file) {
        pending.offer(file);
    }

    public int queueDepth() {
        return pending.size();
    }

    /** 当前播放器的 audio session id：静音器用它排除自己，避免把重放声静音掉。 */
    public int currentSessionId() {
        return sharedSessionId;
    }

    // ===================== 播放泵 =====================

    private void advanceIfNeeded() {
        if (cur != null) {
            return;
        }
        // 起播预缓冲：Vorbis 编码常跟不上实时，1 段起播必断流（决策 4）。
        // 阈值不能超过播放队列上限，否则永远攒不够 → 会一直不起播。
        int prebuffer = Math.max(1, Math.min(settings.startPrebuffer(), settings.maxPending()));
        if (pending.size() < prebuffer) {
            return;
        }
        File f = pending.poll();
        if (f == null) {
            return;
        }
        cur = open(f);
        if (cur == null) {
            return;
        }
        cur.setOnCompletionListener(this::onSegmentCompleted);
        try {
            cur.start();
        } catch (IllegalStateException e) {
            errorListener.onPlaybackError("起播失败：" + e.getMessage());
            releaseQuietly(cur);
            cur = null;
            return;
        }
        status.playedSegments.incrementAndGet();
        preloadNext();
    }

    private void preloadNext() {
        if (next != null || cur == null) {
            return;
        }
        File f = pending.poll();
        if (f == null) {
            return;
        }
        next = open(f);
        if (next == null) {
            return;
        }
        next.setOnCompletionListener(this::onSegmentCompleted);
        try {
            // 平台负责无缝切换，两个播放器必须共享 audio session（决策 3）
            cur.setNextMediaPlayer(next);
        } catch (IllegalStateException e) {
            errorListener.onPlaybackError("接力失败：" + e.getMessage());
            releaseQuietly(next);
            next = null;
        }
    }

    /** 完成回调必须兼容 cur / next 两种身份（规格书 §7.5）。 */
    private void onSegmentCompleted(MediaPlayer completed) {
        if (completed != cur && completed != next) {
            return;
        }
        MediaPlayer old = cur;
        MediaPlayer following = next;
        cur = following;
        next = null;
        releaseQuietly(old);

        if (cur != null) {
            cur.setOnCompletionListener(this::onSegmentCompleted);
            status.playedSegments.incrementAndGet();
            preloadNext();
        }
        if (cur != null && next == null && pending.isEmpty()) {
            // 队列抽空：释放并等待新数据到达时重新起播（保留预缓冲语义）
            releaseQuietly(cur);
            cur = null;
        }
    }

    // ===================== 播放器与路由 =====================

    private MediaPlayer open(File file) {
        MediaPlayer mp = new MediaPlayer();
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    // ★ 规格书决策 2 的必要项：AudioAttributes 默认把触觉声道静音，
                    //   不开这个开关，3 声道里的 haptic 通道不会送到马达。
                    .setHapticChannelsMuted(false)
                    .build();
            mp.setAudioAttributes(attrs);
            // 流音量拉满：触觉声道同样会被流音量缩放
            mp.setVolume(1f, 1f);
            if (sharedSessionId != 0) {
                mp.setAudioSessionId(sharedSessionId);
            }
            mp.setDataSource(file.getAbsolutePath());
            mp.prepare();
            // 逐 track 指定输出设备：比 setCommunicationDevice 更直接。
            // 本机实测遇到过"通信设备已上报为听筒、播放仍走扬声器"的情况。
            if (earpiece != null) {
                try {
                    boolean ok = mp.setPreferredDevice(earpiece);
                    if (ok != lastPreferredOk) {
                        lastPreferredOk = ok;
                        Log.i(TAG, "setPreferredDevice(earpiece id=" + earpiece.getId()
                                + ") => " + ok);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setPreferredDevice failed", t);
                }
            }
            int id = mp.getAudioSessionId();
            if (id != 0) {
                sharedSessionId = id;
            }
            return mp;
        } catch (Exception e) {
            Log.e(TAG, "open failed: " + file, e);
            releaseQuietly(mp);
            errorListener.onPlaybackError("打开分片失败：" + e.getMessage());
            return null;
        }
    }

    private void routeToEarpiece() {
        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
        } catch (Exception ignored) {
        }
    }

    /** 周期巡检：流中断时系统会清掉通信设备，这里自动重设。 */
    private void ensureEarpieceRouting() {
        if (audioManager == null) {
            return;
        }
        try {
            if (earpiece == null || earpiece.getId() == 0) {
                for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        earpiece = d;
                        Log.i(TAG, "earpiece found: id=" + d.getId()
                                + " name=" + d.getProductName());
                        break;
                    }
                }
                if (earpiece == null && routeTicks % 60 == 0) {
                    Log.w(TAG, "no builtin earpiece reported by AudioManager");
                    status.routeState = "未找到听筒设备";
                }
            }
            if (earpiece == null) {
                return;
            }
            if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }
            AudioDeviceInfo active = audioManager.getCommunicationDevice();
            int activeId = active == null ? -1 : active.getId();
            // 周期性强制重设：active 已经"是听筒"但播放仍走扬声器是本机实测到的坑
            boolean force = routeTicks % 40 == 0;
            if (force || activeId != earpiece.getId()) {
                boolean ok = audioManager.setCommunicationDevice(earpiece);
                try {
                    audioManager.setSpeakerphoneOn(false);
                } catch (Throwable ignored) {
                }
                status.routeApplied = ok;
                if (ok) {
                    Log.i(TAG, "media routed to earpiece for haptic output (id="
                            + earpiece.getId() + ")");
                }
            }
            if (activeId != lastReportedCommId) {
                lastReportedCommId = activeId;
                Log.i(TAG, "communication device = "
                        + (active == null ? "null" : active.getType() + "/" + activeId)
                        + " mode=" + audioManager.getMode());
            }
            status.routeDeviceId = earpiece.getId();
            status.routeState = audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION
                    ? ("听筒 id=" + earpiece.getId()) : "模式未生效";
            boostVoiceCallVolume();
        } catch (Throwable e) {
            Log.w(TAG, "route check failed", e);
            status.routeState = "路由异常：" + e.getMessage();
        }
        routeTicks++;
    }

    /**
     * 提升通话音量。
     *
     * <p>本机实测：触觉流走 {@code USAGE_VOICE_COMMUNICATION}，其音量由
     * {@code STREAM_VOICE_CALL} 控制；该流停在最低档（1/11）时 HAL 不驱动马达，
     * 所以引擎运行期间必须把它抬到高位，停止时再还原。
     */
    private void boostVoiceCallVolume() {
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            if (savedVoiceCallVolume < 0) {
                savedVoiceCallVolume = cur;
            }
            // 直接拉满：通话音量会整体缩放触觉流的能量
            int target = Math.max(1, max);
            if (cur < target) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0);
                Log.i(TAG, "voice-call volume " + cur + " -> " + target + " (max " + max + ")");
            }
            status.voiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            status.voiceCallMax = max;
        } catch (Throwable t) {
            Log.w(TAG, "boost voice-call volume failed", t);
        }
    }

    private void restoreVoiceCallVolume() {
        if (savedVoiceCallVolume < 0) {
            return;
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0);
            Log.i(TAG, "voice-call volume restored to " + savedVoiceCallVolume);
        } catch (Throwable ignored) {
        }
        savedVoiceCallVolume = -1;
    }

    private static void releaseQuietly(MediaPlayer mp) {
        if (mp == null) {
            return;
        }
        try {
            mp.reset();
        } catch (Exception ignored) {
        }
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }
}
