package com.gaolou.boneconduction.engine;

import com.gaolou.boneconduction.core.EngineConfig;
import com.gaolou.boneconduction.core.HapticParams;

/**
 * 触觉声道生成器（规格书 §5）。
 *
 * <p>把一段立体声 [L,R] 转成 3 声道 [L,R,haptic]：逐帧塑形（高通 + 低频混回）
 * → 按块算 RMS 并做动态压缩 → 逐帧软削波输出。
 *
 * <p><b>即时生效</b>：所有参数在每次 {@link #mix} 调用开始时重新读取，
 * 也就是说调音滑杆最多一个分片（默认 500ms）就反映到输出波形上。
 */
public final class HapticMixer {

    private final HapticParams settings;

    /** 高阶高通的级联状态（最多 4 阶）。 */
    private float[] hpIn = new float[4];
    private float[] hpOut = new float[4];
    private int activeOrder;
    private float hpCoef = 0.9f;
    private float hpCoefFc = -1f;

    private float compGain = 1f;
    private float[] shaped = new float[0];

    /** 输出级低通（两级级联）：滤掉马达跟不上的高频与削波产生的谐波。 */
    private float lp1;
    private float lp2;
    private float lpCoef = 0.04f;
    private float lpCoefFc = -1f;

    /** 谐振载波模式：包络跟随 + 谐振频率正弦。 */
    private float envState;
    private double carrierPhase;

    private static final double TWO_PI = Math.PI * 2;

    private volatile float lastPeak;
    private volatile float lastRms;
    private volatile float lastGain = 1f;
    private volatile float lastInputLevel;

    public HapticMixer(HapticParams settings) {
        this.settings = settings;
    }

    public float lastPeak() {
        return lastPeak;
    }

    public float lastRms() {
        return lastRms;
    }

    public float lastGain() {
        return lastGain;
    }

    public float lastInputLevel() {
        return lastInputLevel;
    }

    /** 参数改动后重置滤波状态，避免旧阶数的残留在切换瞬间爆音。 */
    public void resetState() {
        for (int i = 0; i < hpIn.length; i++) {
            hpIn[i] = 0f;
            hpOut[i] = 0f;
        }
        compGain = 1f;
        lp1 = 0f;
        lp2 = 0f;
        envState = 0f;
    }

    /**
     * @param stereo 交错立体声，长度 &gt;= frames*2
     * @param frames 帧数
     * @param out3   输出交错 3 声道，长度 &gt;= frames*3
     * @return 本段触觉峰值（0..32767）
     */
    public int mix(short[] stereo, int frames, short[] out3) {
        // ---- 每段读取一次可调参数：滑杆改动在下一个分片即生效 ----
        final float outputScale = settings.outputScale();
        final boolean highpassOn = true;
        final float highpassHz = settings.highpassHz();
        final int order = settings.highpassOrder();
        final float lowpassHz = settings.lowpassHz();
        final boolean carrier = settings.carrierEnabled();
        final float carrierHz = settings.carrierHz();
        final float lowShelf = settings.lowShelf();
        final float gateRms = settings.gateRms();
        final boolean compress = settings.compressEnabled();
        final float threshold = settings.compressThreshold();
        final float ratio = settings.compressRatio();
        final float attack = settings.compressAttack();
        final float release = settings.compressRelease();
        final boolean softClip = settings.softClipEnabled();
        final float softLimit = settings.softClipLimit();
        final boolean muteLr = settings.muteLr();

        if (shaped.length < frames) {
            shaped = new float[frames];
        }
        if (order != activeOrder) {
            if (hpIn.length < order) {
                hpIn = new float[order];
                hpOut = new float[order];
            }
            activeOrder = order;
            for (int i = 0; i < hpIn.length; i++) {
                hpIn[i] = 0f;
                hpOut[i] = 0f;
            }
        }
        if (highpassHz != hpCoefFc) {
            double fs = EngineConfig.SAMPLE_RATE;
            double rc = 1.0 / (2.0 * Math.PI * highpassHz / fs);
            hpCoef = (float) (rc / (rc + 1.0));
            hpCoefFc = highpassHz;
        }
        if (lowpassHz != lpCoefFc) {
            // 一阶 RC 低通系数 a = 2πfc / (2πfc + fs)，两级级联得到 12dB/oct
            double w = 2 * Math.PI * lowpassHz;
            lpCoef = (float) (w / (w + EngineConfig.SAMPLE_RATE));
            lpCoefFc = lowpassHz;
        }
        final float envAttack = envCoef(160f);
        final float envRelease = envCoef(25f);
        final double carrierStep = TWO_PI * carrierHz / EngineConfig.SAMPLE_RATE;

        // ================= 阶段 A：逐帧塑形 =================
        double sumSq = 0;
        double inputSumSq = 0;
        for (int i = 0; i < frames; i++) {
            int l = stereo[i * 2];
            int r = stereo[i * 2 + 1];
            float mono = (l + r) * outputScale;
            inputSumSq += (double) mono * mono;

            float x = mono;
            if (highpassOn) {
                for (int stage = 0; stage < order; stage++) {
                    float y = hpCoef * (hpOut[stage] + x - hpIn[stage]);
                    hpIn[stage] = x;
                    hpOut[stage] = y;
                    x = y;
                }
                // 混回少量原始低频，保留"力度"而不让低音独占马达
                mono = x + lowShelf * (l + r) * outputScale;
            }
            shaped[i] = mono;
            sumSq += (double) mono * mono;
        }

        // ================= 阶段 B：按块计算压缩增益 =================
        double rms = Math.sqrt(sumSq / Math.max(1, frames)) / 32768.0;
        if (compress && rms > threshold && rms > 1e-6) {
            double over = threshold / rms;
            double target = Math.pow(over, 1.0 - 1.0 / ratio);
            float coef = (target < compGain) ? attack : release;
            compGain += coef * (target - compGain);
        } else {
            compGain += release * (1f - compGain);
        }
        if (compGain < 0.02f) {
            compGain = 0.02f;
        }
        if (compGain > 1f) {
            compGain = 1f;
        }
        final boolean gated = rms < gateRms;

        // ================= 阶段 C：逐帧输出 =================
        int peak = 0;
        for (int i = 0; i < frames; i++) {
            float raw = shaped[i] * compGain;
            if (gated) {
                envState *= 0.92f;
            }
            int h;
            if (carrier) {
                // 谐振载波驱动：不论原信号是什么频率，都用「包络 × 谐振正弦」重新合成，
                // 马达始终工作在能跟随的频率上，从根上消除高频破音。
                float rect = Math.abs(raw);
                envState += (rect > envState ? envAttack : envRelease) * (rect - envState);
                if (envState < 0f) {
                    envState = 0f;
                }
                h = hardClip((int) (envState * 1.15f * (float) Math.sin(carrierPhase)));
                carrierPhase += carrierStep;
                if (carrierPhase > TWO_PI) {
                    carrierPhase -= TWO_PI;
                }
            } else if (gated) {
                h = 0;
            } else if (softClip) {
                h = softClip(raw, softLimit);
            } else {
                h = hardClip((int) raw);
            }

            // 输出级低通必须放在削波之后：削波谐波同样会让马达破音
            lp1 += lpCoef * (h - lp1);
            lp2 += lpCoef * (lp1 - lp2);
            h = hardClip(Math.round(lp2));

            // 只保留振动时留一点极低电平的 L/R：-46dB 基本听不见，
            // 但能保证音频流「有内容」，避免个别 HAL 把整条流当成空流跳过。
            out3[i * 3] = muteLr ? (short) (stereo[i * 2] * 0.005f) : stereo[i * 2];
            out3[i * 3 + 1] = muteLr ? (short) (stereo[i * 2 + 1] * 0.005f) : stereo[i * 2 + 1];
            out3[i * 3 + 2] = (short) h;
            int a = Math.abs(h);
            if (a > peak) {
                peak = a;
            }
        }

        lastPeak = peak / 32768f;
        lastRms = (float) rms;
        lastGain = compGain;
        lastInputLevel = (float) (Math.sqrt(inputSumSq / Math.max(1, frames)) / 32768.0);
        return peak;
    }

    /** 有理函数近似 tanh 的软削波（规格书 §5.4）。 */
    private static int softClip(float raw, float limit) {
        float f = raw / 32768f;
        if (f > limit) {
            f = limit;
        } else if (f < -limit) {
            f = -limit;
        }
        float f2 = f * f;
        float y = f * (f2 + 27f) / (9f * f2 + 27f);
        // 归一化：有理近似在 f=limit 处只到 0.77×limit，会白白损失约 23% 的行程。
        // 除以同点的参考值后，f=limit 时输出正好等于 limit，小信号也按同比例抬升。
        float l2 = limit * limit;
        float ref = limit * (l2 + 27f) / (9f * l2 + 27f);
        if (ref > 1e-6f) {
            y = y / ref * limit;
        }
        int v = (int) (y * 32767f);
        if (v > 32767) {
            v = 32767;
        } else if (v < -32768) {
            v = -32768;
        }
        return v;
    }

    private static int hardClip(int v) {
        if (v > 32767) {
            return 32767;
        }
        if (v < -32768) {
            return -32768;
        }
        return v;
    }

    private static float envCoef(float hz) {
        double w = 2 * Math.PI * hz;
        return (float) (w / (w + EngineConfig.SAMPLE_RATE));
    }
}
