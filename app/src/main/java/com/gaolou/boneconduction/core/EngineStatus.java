package com.gaolou.boneconduction.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 引擎实时状态：由后台线程写、UI 线程读，全部用 volatile / 原子量保证可见性。
 */
public final class EngineStatus {

    public static final int STATE_IDLE = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_ERROR = 3;

    public static final class Snapshot {
        public int state;
        public String message = "";
        public boolean hapticSupported;
        public boolean amplitudeControl;
        public String deviceName = "";

        public long capturedBlocks;
        public long capturedFrames;
        public long encodedSegments;
        public long encodedFailures;
        public long playedSegments;
        public long droppedSegments;

        public int captureQueueDepth;
        public int encodeQueueDepth;
        public int pendingSegments;

        public long lastEncodeMicros;
        public long avgEncodeMicros;

        public float hapticPeak;
        public float hapticRms;
        public float compressionGain;
        public float inputLevel;

        public boolean mutedForeign;
        public int mutedSessionCount;
        public String muteBackend = "未探测";

        public String routeState = "未设置";
        public int routeDeviceId = -1;
        public boolean routeApplied;
        public int voiceCallVolume = -1;
        public int voiceCallMax = -1;

        public long uptimeMs;
        public int segmentMs;
    }

    private static volatile EngineStatus instance;

    public final AtomicLong capturedBlocks = new AtomicLong();
    public final AtomicLong capturedFrames = new AtomicLong();
    public final AtomicLong encodedSegments = new AtomicLong();
    public final AtomicLong encodedFailures = new AtomicLong();
    public final AtomicLong playedSegments = new AtomicLong();
    public final AtomicLong droppedSegments = new AtomicLong();
    public final AtomicLong encodeMicrosSum = new AtomicLong();

    public volatile int state = STATE_IDLE;
    public volatile String message = "未启动";
    public volatile boolean hapticSupported;
    public volatile boolean amplitudeControl;
    public volatile String deviceName = "";

    public volatile int captureQueueDepth;
    public volatile int encodeQueueDepth;
    public volatile int pendingSegments;
    public volatile long lastEncodeMicros;
    public volatile long startedAtMs;

    public volatile float hapticPeak;
    public volatile float hapticRms;
    public volatile float compressionGain = 1f;
    public volatile float inputLevel;

    public volatile boolean mutedForeign;
    public volatile int mutedSessionCount;
    public volatile String muteBackend = "未探测";
    /** 听筒路由状态（规格书决策 2：必须走听筒，否则第 3 声道会被丢弃） */
    public volatile String routeState = "未设置";
    public volatile int routeDeviceId = -1;
    public volatile boolean routeApplied;
    /** 通话音量（触觉流的实际音量；过低时 HAL 不会驱动马达） */
    public volatile int voiceCallVolume = -1;
    public volatile int voiceCallMax = -1;

    private EngineStatus() {
    }

    public static EngineStatus get() {
        EngineStatus local = instance;
        if (local == null) {
            synchronized (EngineStatus.class) {
                local = instance;
                if (local == null) {
                    local = new EngineStatus();
                    instance = local;
                }
            }
        }
        return local;
    }

    public void reset() {
        capturedBlocks.set(0);
        capturedFrames.set(0);
        encodedSegments.set(0);
        encodedFailures.set(0);
        playedSegments.set(0);
        droppedSegments.set(0);
        encodeMicrosSum.set(0);
        captureQueueDepth = 0;
        encodeQueueDepth = 0;
        pendingSegments = 0;
        lastEncodeMicros = 0;
        hapticPeak = 0f;
        hapticRms = 0f;
        compressionGain = 1f;
        inputLevel = 0f;
        mutedSessionCount = 0;
        mutedForeign = false;
    }

    public Snapshot snapshot(int segmentMs) {
        Snapshot s = new Snapshot();
        s.state = state;
        s.message = message;
        s.hapticSupported = hapticSupported;
        s.amplitudeControl = amplitudeControl;
        s.deviceName = deviceName;
        s.capturedBlocks = capturedBlocks.get();
        s.capturedFrames = capturedFrames.get();
        s.encodedSegments = encodedSegments.get();
        s.encodedFailures = encodedFailures.get();
        s.playedSegments = playedSegments.get();
        s.droppedSegments = droppedSegments.get();
        s.captureQueueDepth = captureQueueDepth;
        s.encodeQueueDepth = encodeQueueDepth;
        s.pendingSegments = pendingSegments;
        s.lastEncodeMicros = lastEncodeMicros;
        long n = encodedSegments.get();
        s.avgEncodeMicros = n > 0 ? encodeMicrosSum.get() / n : 0;
        s.hapticPeak = hapticPeak;
        s.hapticRms = hapticRms;
        s.compressionGain = compressionGain;
        s.inputLevel = inputLevel;
        s.mutedForeign = mutedForeign;
        s.mutedSessionCount = mutedSessionCount;
        s.muteBackend = muteBackend;
        s.routeState = routeState;
        s.routeDeviceId = routeDeviceId;
        s.routeApplied = routeApplied;
        s.voiceCallVolume = voiceCallVolume;
        s.voiceCallMax = voiceCallMax;
        s.uptimeMs = startedAtMs > 0 ? System.currentTimeMillis() - startedAtMs : 0;
        s.segmentMs = segmentMs;
        return s;
    }
}
