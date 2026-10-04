package com.gaolou.boneconduction.engine;

import android.util.Log;

import java.io.File;

import com.gaolou.boneconduction.core.EngineConfig;
import com.gaolou.boneconduction.core.EngineSettings;
import com.gaolou.boneconduction.core.EngineStatus;
import com.gaolou.boneconduction.nativebridge.HapticOgg;

/**
 * 流水线编排（规格书 §4）：100ms 块 → 累积成段 → 混音成 3 声道 → 编码 → 入播放队列。
 *
 * <p>三条线程：采集线程只做入队（快），pipeline 线程做累积与混音，encode 线程做 JNI 编码。
 * 每级队列都有界且丢最旧，因此端到端延迟有上限（决策 6）。
 */
public final class OggSink {

    private static final String TAG = "OggSink";

    private static final class Segment {
        final short[] data;
        final int frames;

        Segment(short[] data, int frames) {
            this.data = data;
            this.frames = frames;
        }
    }

    private final EngineSettings settings;
    private final EngineStatus status;
    private final SegmentRing ring;
    private final SegmentPlayer player;
    private final HapticMixer mixer;
    private final AudioDelayLine delayLine = new AudioDelayLine();

    private final BoundedQueue<short[]> captureQueue;
    private final BoundedQueue<Segment> encodeQueue;

    private volatile boolean running;
    private Thread pipelineThread;
    private Thread encodeThread;

    private long segmentSeq;
    private long lastTraceMs;
    private short[] segStereo = new short[0];
    private short[] tmp3 = new short[0];
    private int segFill;

    public OggSink(EngineSettings settings, EngineStatus status, SegmentRing ring,
                   SegmentPlayer player, HapticMixer mixer) {
        this.settings = settings;
        this.status = status;
        this.ring = ring;
        this.player = player;
        this.mixer = mixer;
        this.captureQueue = new BoundedQueue<>(settings::captureQueue);
        this.encodeQueue = new BoundedQueue<>(
                () -> Math.max(2, Math.min(settings.maxPending(), 8)));
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        segFill = 0;
        delayLine.reset();
        mixer.resetState();
        ensureBuffers(settings.segmentFrames());
        pipelineThread = new Thread(this::pipelineLoop, "ogg-pipeline");
        encodeThread = new Thread(this::encodeLoop, "ogg-encode");
        pipelineThread.start();
        encodeThread.start();
    }

    public void stop() {
        running = false;
        captureQueue.wakeAll();
        encodeQueue.wakeAll();
        joinQuietly(pipelineThread);
        joinQuietly(encodeThread);
        pipelineThread = null;
        encodeThread = null;
        captureQueue.clear();
        encodeQueue.clear();
        segFill = 0;
    }

    /** 采集线程调用：只入队，避免在音频线程做重活。 */
    public void onBlock(short[] pcm, int frames) {
        if (!running) {
            return;
        }
        status.capturedBlocks.incrementAndGet();
        status.capturedFrames.addAndGet(frames);
        captureQueue.offer(pcm);
        status.captureQueueDepth = captureQueue.size();
    }

    // ===================== pipeline =====================

    private void pipelineLoop() {
        try {
            while (running) {
                short[] block = captureQueue.poll(EngineConfig.ENCODE_POLL_MS);
                if (block == null) {
                    continue;
                }
                int frames = block.length / EngineConfig.IN_CHANNELS;
                if (frames <= 0) {
                    continue;
                }
                int segFrames = settings.segmentFrames();
                // 段长滑杆调小时 segFill 可能已经超过新的阈值，缓冲必须按两者较大值准备，
                // 否则会写入越界（滑杆可以随时拖动）。
                ensureBuffers(Math.max(segFrames, segFill + frames));

                System.arraycopy(block, 0, segStereo, segFill * EngineConfig.IN_CHANNELS,
                        frames * EngineConfig.IN_CHANNELS);
                segFill += frames;

                if (segFill >= segFrames) {
                    flushSegment(segFill);
                    segFill = 0;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "pipeline loop died", t);
            status.message = "混音线程异常：" + t;
            status.state = EngineStatus.STATE_ERROR;
        }
    }

    private void flushSegment(int frames) {
        ensureBuffers(frames);
        int peak = mixer.mix(segStereo, frames, tmp3);

        // 音/触觉对齐：缓冲区按最大延迟预分配，滑杆调整只改变读取偏移
        short[] out = new short[frames * EngineConfig.OUT_CHANNELS];
        delayLine.process(tmp3, frames, out, settings.alignMs());

        status.hapticPeak = peak / 32768f;
        status.hapticRms = mixer.lastRms();
        status.compressionGain = mixer.lastGain();
        status.inputLevel = mixer.lastInputLevel();

        // 限流诊断：确认采集到的到底是「静音」还是「有声音但被门限吃掉」
        long now = System.currentTimeMillis();
        if (now - lastTraceMs > 2000) {
            lastTraceMs = now;
            Log.i(TAG, String.format(java.util.Locale.US,
                    "trace input=%.4f rms=%.4f gain=%.2f hapticPeak=%.4f frames=%d",
                    mixer.lastInputLevel(), mixer.lastRms(), mixer.lastGain(),
                    peak / 32768f, frames));
        }

        encodeQueue.offer(new Segment(out, frames));
        status.encodeQueueDepth = encodeQueue.size();
    }

    /** @param frames 需要容纳的最大帧数（已包含余量） */
    private void ensureBuffers(int frames) {
        int needStereo = frames * EngineConfig.IN_CHANNELS;
        if (segStereo.length < needStereo) {
            segStereo = new short[needStereo];
        }
        int need3 = frames * EngineConfig.OUT_CHANNELS;
        if (tmp3.length < need3) {
            tmp3 = new short[need3];
        }
    }

    // ===================== encode =====================

    private void encodeLoop() {
        try {
            while (running) {
                Segment segment = encodeQueue.poll(EngineConfig.ENCODE_POLL_MS);
                if (segment == null) {
                    continue;
                }
                long seq = segmentSeq++;
                File file = ring.fileFor(seq);
                long t0 = System.nanoTime();
                // 每段都重新读取 quality：编码质量滑杆改动即时生效
                int rc = HapticOgg.nativeEncode(file.getAbsolutePath(), segment.data,
                        segment.frames, EngineConfig.OUT_CHANNELS,
                        EngineConfig.SAMPLE_RATE, settings.encodeQuality());
                long micros = (System.nanoTime() - t0) / 1000;
                status.lastEncodeMicros = micros;
                status.encodeMicrosSum.addAndGet(micros);

                if (rc == 0) {
                    status.encodedSegments.incrementAndGet();
                    player.enqueue(file);
                } else {
                    status.encodedFailures.incrementAndGet();
                    status.message = "编码失败 rc=" + rc;
                    Log.w(TAG, "encode failed rc=" + rc + " file=" + file);
                }
                status.encodeQueueDepth = encodeQueue.size();
                status.pendingSegments = player.queueDepth();
            }
        } catch (Throwable t) {
            Log.e(TAG, "encode loop died", t);
            status.message = "编码线程异常：" + t;
            status.state = EngineStatus.STATE_ERROR;
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
