package com.gaolou.boneconduction.engine;

import com.gaolou.boneconduction.core.EngineConfig;

/**
 * 音/触觉对齐延迟线（规格书 §2.3 的 AudioDelayLine）。
 *
 * <p>对齐量 {@code alignMs} 为「振动相对声音的时间差」：
 * <ul>
 *   <li>{@code alignMs > 0} → 振动比声音晚，声音走当前帧，触觉取历史帧；</li>
 *   <li>{@code alignMs < 0} → 振动比声音早，触觉走当前帧，声音取历史帧。</li>
 * </ul>
 * 缓冲区按最大 2000ms 预分配，参数滑动时只改变读取偏移，因此可即时生效。
 */
public final class AudioDelayLine {

    private static final int MAX_DELAY_FRAMES =
            EngineConfig.SAMPLE_RATE * EngineConfig.ALIGN_MS_LIMIT / 1000 + 1024;

    private final short[] stereoHist = new short[MAX_DELAY_FRAMES * 2];
    private final short[] hapticHist = new short[MAX_DELAY_FRAMES];
    private int writePos;

    /** 当前生效的延迟（毫秒），用于 UI 回显。 */
    private volatile int activeAlignMs;

    public int activeAlignMs() {
        return activeAlignMs;
    }

    /**
     * @param in3  交错 3 声道输入（L,R,haptic）
     * @param frames 帧数
     * @param out3 交错 3 声道输出，可与 in3 相同
     * @param alignMs 对齐毫秒（正 = 振动滞后）
     */
    public void process(short[] in3, int frames, short[] out3, int alignMs) {
        activeAlignMs = alignMs;
        int delayA = alignMs < 0 ? -alignMs : 0;
        int delayH = alignMs > 0 ? alignMs : 0;

        if (delayA == 0 && delayH == 0) {
            if (in3 != out3) {
                System.arraycopy(in3, 0, out3, 0, frames * 3);
            }
            // 历史依然要推进，否则从「有延迟」切回 0 时会读到过期数据
            writeHistory(in3, frames);
            return;
        }

        int fa = Math.min(delayA * EngineConfig.SAMPLE_RATE / 1000, MAX_DELAY_FRAMES - frames);
        int fh = Math.min(delayH * EngineConfig.SAMPLE_RATE / 1000, MAX_DELAY_FRAMES - frames);
        if (fa < 0) {
            fa = 0;
        }
        if (fh < 0) {
            fh = 0;
        }

        for (int i = 0; i < frames; i++) {
            int w = (writePos + i) % MAX_DELAY_FRAMES;
            stereoHist[w * 2] = in3[i * 3];
            stereoHist[w * 2 + 1] = in3[i * 3 + 1];
            hapticHist[w] = in3[i * 3 + 2];

            int ra = ((w - fa) % MAX_DELAY_FRAMES + MAX_DELAY_FRAMES) % MAX_DELAY_FRAMES;
            int rh = ((w - fh) % MAX_DELAY_FRAMES + MAX_DELAY_FRAMES) % MAX_DELAY_FRAMES;
            out3[i * 3] = stereoHist[ra * 2];
            out3[i * 3 + 1] = stereoHist[ra * 2 + 1];
            out3[i * 3 + 2] = hapticHist[rh];
        }
        writePos = (writePos + frames) % MAX_DELAY_FRAMES;
    }

    private void writeHistory(short[] in3, int frames) {
        for (int i = 0; i < frames; i++) {
            int w = (writePos + i) % MAX_DELAY_FRAMES;
            stereoHist[w * 2] = in3[i * 3];
            stereoHist[w * 2 + 1] = in3[i * 3 + 1];
            hapticHist[w] = in3[i * 3 + 2];
        }
        writePos = (writePos + frames) % MAX_DELAY_FRAMES;
    }

    public void reset() {
        writePos = 0;
        java.util.Arrays.fill(stereoHist, (short) 0);
        java.util.Arrays.fill(hapticHist, (short) 0);
    }
}
