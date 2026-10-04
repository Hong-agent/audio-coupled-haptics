package com.gaolou.boneconduction.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.gaolou.boneconduction.core.HapticParams;

/**
 * 触觉混音器的纯 JVM 测试：验证「安静不丢 / 响段不炸 / 改参数即时生效」。
 */
public class HapticMixerTest {

    private static final int FRAMES = 4800; // 100ms @48k

    /** 可变参数实现：setter 之后下一次 mix 必须立刻使用新值。 */
    private static final class Params implements HapticParams {
        float scale = 0.65f;
        float gate = 0.02f;
        float hpHz = 250f;
        int order = 2;
        float lpHz = 300f;
        boolean carrier = false;
        float carrierHz = 160f;
        float shelf = 0.12f;
        boolean softClip = true;
        float limit = 0.99f;
        boolean compress = true;
        float threshold = 0.35f;
        float ratio = 3.0f;
        float attack = 0.4f;
        float release = 0.08f;
        boolean muteLr = false;

        @Override
        public float outputScale() {
            return scale;
        }

        @Override
        public float gateRms() {
            return gate;
        }

        @Override
        public float highpassHz() {
            return hpHz;
        }

        @Override
        public int highpassOrder() {
            return order;
        }

        @Override
        public float lowpassHz() {
            return lpHz;
        }

        @Override
        public boolean carrierEnabled() {
            return carrier;
        }

        @Override
        public float carrierHz() {
            return carrierHz;
        }

        @Override
        public float lowShelf() {
            return shelf;
        }

        @Override
        public boolean softClipEnabled() {
            return softClip;
        }

        @Override
        public float softClipLimit() {
            return limit;
        }

        @Override
        public boolean compressEnabled() {
            return compress;
        }

        @Override
        public float compressThreshold() {
            return threshold;
        }

        @Override
        public float compressRatio() {
            return ratio;
        }

        @Override
        public float compressAttack() {
            return attack;
        }

        @Override
        public float compressRelease() {
            return release;
        }

        @Override
        public boolean muteLr() {
            return muteLr;
        }
    }

    private static short[] tone(double amplitude, double freqHz) {
        short[] pcm = new short[FRAMES * 2];
        for (int i = 0; i < FRAMES; i++) {
            short v = (short) (amplitude * 32767 * Math.sin(2 * Math.PI * freqHz * i / 48000.0));
            pcm[i * 2] = v;
            pcm[i * 2 + 1] = v;
        }
        return pcm;
    }

    private static int peakOf(short[] out3) {
        int peak = 0;
        for (int i = 0; i < out3.length; i += 3) {
            peak = Math.max(peak, Math.abs(out3[i + 2]));
        }
        return peak;
    }

    /** 只统计后半段：低通状态跨段连续，起始样本还带着上一段的衰减尾巴。 */
    private static int peakOfRange(short[] out3, int fromFrame, int toFrame) {
        int peak = 0;
        for (int i = fromFrame; i < toFrame; i++) {
            peak = Math.max(peak, Math.abs(out3[i * 3 + 2]));
        }
        return peak;
    }

    @Test
    public void silenceIsGatedToZero() {
        HapticMixer mixer = new HapticMixer(new Params());
        short[] out = new short[FRAMES * 3];
        int peak = mixer.mix(new short[FRAMES * 2], FRAMES, out);
        assertEquals("近静音段不应有振动输出", 0, peak);
        for (int i = 2; i < out.length; i += 3) {
            assertEquals(0, out[i]);
        }
    }

    @Test
    public void loudSegmentIsCompressedNotClippedFlat() {
        Params params = new Params();
        params.threshold = 0.2f; // 让 180Hz 测试音确实越过阈值
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];
        mixer.mix(tone(0.95, 180), FRAMES, out);
        assertTrue("响段必须触发压缩", mixer.lastGain() < 1f);
        assertTrue("触觉输出应存在", peakOf(out) > 0);
        assertTrue("软削波后不得越过 16bit 上限", peakOf(out) <= 32767);
    }

    /**
     * 破音的直接原因：马达跟不上高频。4kHz 内容必须被输出级低通大幅压掉，
     * 否则马达会发出沙沙的破音。
     */
    @Test
    public void highFrequencyContentIsAttenuatedForTheMotor() {
        Params params = new Params();
        params.compress = false;
        params.carrier = false;
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];

        params.lpHz = 300f;
        mixer.mix(tone(0.9, 180), FRAMES, out);
        int lowTonePeak = peakOfRange(out, FRAMES / 2, FRAMES);

        params.lpHz = 300f;
        mixer.mix(tone(0.9, 4000), FRAMES, out);
        int highTonePeak = peakOfRange(out, FRAMES / 2, FRAMES);

        assertTrue("4kHz 必须被显著衰减（低音 " + lowTonePeak + " vs 高音 " + highTonePeak + "）",
                highTonePeak < lowTonePeak / 5);
    }

    /** 谐振载波模式：输出被重新合成为谐振频率正弦，峰值必须有界。 */
    @Test
    public void carrierModeResynthesizesBoundedWaveform() {
        Params params = new Params();
        params.carrier = true;
        params.carrierHz = 160f;
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];
        mixer.mix(tone(0.9, 3000), FRAMES, out);
        int peak = peakOf(out);
        assertTrue("载波模式在高频输入下仍应有输出", peak > 1000);
        assertTrue("载波模式不得越界", peak <= 32767);
    }

    @Test
    public void outputScaleChangeTakesEffectOnNextSegment() {
        Params params = new Params();
        params.compress = false; // 隔离压缩，只观察总电平
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];
        short[] pcm = tone(0.5, 400);

        params.scale = 0.2f;
        int small = mixer.mix(pcm, FRAMES, out);
        params.scale = 1.2f;
        int large = mixer.mix(pcm, FRAMES, out);

        assertTrue("同一段音频下电平调大必须立即更响", large > small);
    }

    @Test
    public void gateRaiseSilencesQuietSegmentImmediately() {
        Params params = new Params();
        params.compress = false;
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];
        short[] pcm = tone(0.03, 180);

        params.gate = 0.001f;
        mixer.mix(pcm, FRAMES, out);
        int audible = peakOfRange(out, FRAMES / 2, FRAMES);
        params.gate = 0.5f; // 抬高门槛，同一段音频应立刻被判为静音
        mixer.mix(pcm, FRAMES, out);
        int gated = peakOfRange(out, FRAMES / 2, FRAMES);

        assertTrue(audible > 0);
        assertEquals("抬高门槛后触觉输出必须归零（" + audible + " -> " + gated + "）", 0, gated);
    }

    @Test
    public void highpassOrderSwitchKeepsPipelineStable() {
        Params params = new Params();
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];
        short[] pcm = tone(0.6, 200);
        for (int order = 1; order <= 4; order++) {
            params.order = order;
            int peak = mixer.mix(pcm, FRAMES, out);
            assertTrue("阶数=" + order + " 时应有输出", peak > 0);
            assertTrue(peak <= 32767);
        }
    }

    @Test
    public void muteLrKeepsHapticChannelOnly() {
        Params params = new Params();
        params.muteLr = true;
        HapticMixer mixer = new HapticMixer(params);
        short[] out = new short[FRAMES * 3];
        mixer.mix(tone(0.8, 200), FRAMES, out);
        for (int i = 0; i < out.length; i += 3) {
            // 只保留振动时 L/R 留了 -46dB 的极小电平，保证流「有内容」
            assertTrue("左声道应接近静音：" + out[i], Math.abs(out[i]) < 300);
            assertTrue("右声道应接近静音：" + out[i + 1], Math.abs(out[i + 1]) < 300);
        }
        assertTrue(peakOf(out) > 0);
    }
}
