package com.gaolou.boneconduction.engine;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Process;
import android.util.Log;

import com.gaolou.boneconduction.core.EngineConfig;

/**
 * 采集层（规格书 §3）：MediaProjection + AudioRecord 抓取系统正在播放的音频。
 *
 * <p>只匹配 media/game/unknown/assistant 四种 Usage，刻意排除通话、通知等隐私音频。
 */
public final class CaptureEngine {

    private static final String TAG = "CaptureEngine";

    public interface BlockConsumer {
        void onBlock(short[] pcm, int frames);
    }

    public interface ErrorListener {
        void onCaptureError(String message);
    }

    private volatile boolean running;
    private Thread thread;
    private AudioRecord record;

    /** @return 0 成功，否则返回负的错误码 */
    @SuppressLint("MissingPermission")
    public int start(MediaProjection projection, BlockConsumer consumer, ErrorListener errors) {
        stop();
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build();

            int minBuf = AudioRecord.getMinBufferSize(EngineConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int blockBytes = EngineConfig.CAPTURE_BLOCK_FRAMES * EngineConfig.IN_CHANNELS * 2;
            int bufSize = Math.max(minBuf * 2, blockBytes * 4);

            AudioRecord rec = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(EngineConfig.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();

            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return -1;
            }
            record = rec;
            Log.i(TAG, "capture started, bufSize=" + bufSize);
        } catch (Exception e) {
            Log.e(TAG, "create AudioRecord failed", e);
            return -2;
        }

        running = true;
        thread = new Thread(() -> readLoop(consumer, errors), "capture-read");
        thread.start();
        return 0;
    }

    private void readLoop(BlockConsumer consumer, ErrorListener errors) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int blockFrames = EngineConfig.CAPTURE_BLOCK_FRAMES;
        final short[] block = new short[blockFrames * EngineConfig.IN_CHANNELS];
        AudioRecord rec = record;
        if (rec == null) {
            return;
        }
        try {
            rec.startRecording();
        } catch (IllegalStateException e) {
            errors.onCaptureError("AudioRecord 启动失败：" + e.getMessage());
            return;
        }
        try {
            while (running) {
                int framesRead = readFully(rec, block, blockFrames);
                if (framesRead <= 0) {
                    if (framesRead == AudioRecord.ERROR_INVALID_OPERATION
                            || framesRead == AudioRecord.ERROR_BAD_VALUE) {
                        errors.onCaptureError("采集异常，错误码 " + framesRead);
                        return;
                    }
                    if (framesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                        errors.onCaptureError("音频服务连接断开，请重新开始");
                        return;
                    }
                    // 暂时性失败：睡一下再试，避免空转
                    Thread.sleep(10);
                    continue;
                }
                // 块数组会被复用，必须拷贝副本再交给下游
                short[] copy = new short[framesRead * EngineConfig.IN_CHANNELS];
                System.arraycopy(block, 0, copy, 0, copy.length);
                consumer.onBlock(copy, framesRead);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 采集线程里的任何异常都不允许拖垮整个进程
            errors.onCaptureError("采集线程异常：" + t);
        } finally {
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 循环补齐一个整块（规格书 §3.3）。
     *
     * <p>注意单位：{@link AudioRecord#read(short[], int, int)} 的 offset/size 与返回值
     * 都是 <b>short 个数</b>，不是帧数——这里必须先换算成帧，否则会把块读越界。
     *
     * @return 实际读到的帧数；若首次读取就失败则返回 AudioRecord 的负错误码
     */
    private int readFully(AudioRecord rec, short[] out, int frames) {
        int needFrames = frames;
        int offFrames = 0;
        while (needFrames > 0 && running) {
            int gotShorts = rec.read(out, offFrames * EngineConfig.IN_CHANNELS,
                    needFrames * EngineConfig.IN_CHANNELS);
            if (gotShorts <= 0) {
                return offFrames > 0 ? offFrames : gotShorts;
            }
            int gotFrames = gotShorts / EngineConfig.IN_CHANNELS;
            if (gotFrames <= 0) {
                break;
            }
            offFrames += gotFrames;
            needFrames -= gotFrames;
        }
        return offFrames;
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (record != null) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            record = null;
        }
    }
}
