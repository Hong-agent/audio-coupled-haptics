package com.gaolou.boneconduction.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行期可调参数集合。
 *
 * <p>设计要点：所有字段都是 {@code volatile}，引擎各线程（采集 / 混音 / 编码 / 播放泵）
 * <b>每段、每块、每帧都重新读取</b>，因此调参不需要重启链路即可即时生效。
 * 修改同时写入 SharedPreferences，重启后保留。
 */
public final class EngineSettings implements HapticParams {

    public interface Listener {
        /** @param key 发生变化的参数键，见 {@link Keys} */
        void onSettingsChanged(String key);
    }

    /** 参数键，UI 与日志共用，避免散落字符串。 */
    public static final class Keys {
        public static final String OUTPUT_SCALE = "haptic_output_scale";
        public static final String GATE_RMS = "haptic_gate_rms";
        public static final String HIGHPASS_HZ = "haptic_highpass_hz";
        public static final String HIGHPASS_ORDER = "haptic_highpass_order";
        public static final String LOWPASS_HZ = "haptic_lowpass_hz";
        public static final String CARRIER = "haptic_carrier_enabled";
        public static final String CARRIER_HZ = "haptic_carrier_hz";
        public static final String LOW_SHELF = "haptic_low_shelf";
        public static final String SOFT_CLIP = "soft_clip_enabled";
        public static final String SOFT_CLIP_LIMIT = "soft_clip_limit";
        public static final String COMPRESS = "compress_enabled";
        public static final String COMPRESS_THRESHOLD = "compress_threshold";
        public static final String COMPRESS_RATIO = "compress_ratio";
        public static final String COMPRESS_ATTACK = "compress_attack";
        public static final String COMPRESS_RELEASE = "compress_release";
        public static final String ENCODE_QUALITY = "encode_quality";
        public static final String SEGMENT_MS = "segment_ms";
        public static final String MAX_PENDING = "max_pending";
        public static final String CAPTURE_QUEUE = "capture_queue";
        public static final String START_PREBUFFER = "start_prebuffer";
        public static final String ALIGN_MS = "align_ms";
        public static final String MUTE_FOREIGN = "mute_foreign";
        public static final String MUTE_POLL_MS = "mute_poll_ms";
        public static final String MUTE_GAIN_DB = "mute_gain_db";
        public static final String MUTE_LR = "mute_lr";
        public static final String PRESET = "preset";

        private Keys() {
        }
    }

    /** 调音预设：一键切换一套互相自洽的参数。 */
    public enum Preset {
        // 所有预设都不启用输出级低通（LOWPASS_HZ_OFF = 不滤波），保持宽频手感；
        // 需要压破音时由用户手动调低「高频上限」或打开「谐振载波驱动」。
        BALANCED("均衡", 0.72f, 0.02f, 250f, 2, EngineConfig.LOWPASS_HZ_OFF, 0.12f, 0.35f, 3.0f),
        PUNCHY("强节奏", 1.00f, 0.015f, 180f, 2, EngineConfig.LOWPASS_HZ_OFF, 0.20f, 0.28f, 4.0f),
        CRISP("清脆", 0.65f, 0.025f, 420f, 3, EngineConfig.LOWPASS_HZ_OFF, 0.07f, 0.40f, 2.5f),
        GENTLE("轻柔", 0.45f, 0.03f, 300f, 2, EngineConfig.LOWPASS_HZ_OFF, 0.10f, 0.45f, 2.0f),
        CUSTOM("自定义", 0f, 0f, 0f, 0, 0f, 0f, 0f, 0f);

        public final String label;
        final float scale;
        final float gate;
        final float hpHz;
        final int hpOrder;
        final float lpHz;
        final float shelf;
        final float threshold;
        final float ratio;

        Preset(String label, float scale, float gate, float hpHz, int hpOrder, float lpHz,
               float shelf, float threshold, float ratio) {
            this.label = label;
            this.scale = scale;
            this.gate = gate;
            this.hpHz = hpHz;
            this.hpOrder = hpOrder;
            this.lpHz = lpHz;
            this.shelf = shelf;
            this.threshold = threshold;
            this.ratio = ratio;
        }
    }

    private static final String PREFS = "engine_settings_v5";
    private static volatile EngineSettings instance;

    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // ==== 触觉塑形 ====
    private volatile float outputScale;
    private volatile float gateRms;
    private volatile float highpassHz;
    private volatile int highpassOrder;
    private volatile float lowpassHz;
    private volatile boolean carrierEnabled;
    private volatile float carrierHz;
    private volatile float lowShelf;
    private volatile boolean softClipEnabled;
    private volatile float softClipLimit;

    // ==== 动态压缩 ====
    private volatile boolean compressEnabled;
    private volatile float compressThreshold;
    private volatile float compressRatio;
    private volatile float compressAttack;
    private volatile float compressRelease;

    // ==== 编码与分片 ====
    private volatile float encodeQuality;
    private volatile int segmentMs;

    // ==== 队列 ====
    private volatile int maxPending;
    private volatile int captureQueue;
    private volatile int startPrebuffer;

    // ==== 对齐与静音 ====
    private volatile int alignMs;
    private volatile boolean muteForeign;
    private volatile int mutePollMs;
    private volatile float muteGainDb;
    private volatile boolean muteLr;
    private volatile String preset;

    private EngineSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        outputScale = prefs.getFloat(Keys.OUTPUT_SCALE, 0.72f);
        gateRms = prefs.getFloat(Keys.GATE_RMS, 0.02f);
        highpassHz = prefs.getFloat(Keys.HIGHPASS_HZ, 250f);
        highpassOrder = prefs.getInt(Keys.HIGHPASS_ORDER, 2);
        // 默认关闭低通与载波驱动，保持原有的宽频手感；
        // 想消除高音破音时再把「高频上限」调低或打开「谐振载波驱动」。
        lowpassHz = prefs.getFloat(Keys.LOWPASS_HZ, EngineConfig.LOWPASS_HZ_OFF);
        carrierEnabled = prefs.getBoolean(Keys.CARRIER, false);
        carrierHz = prefs.getFloat(Keys.CARRIER_HZ, 160f);
        lowShelf = prefs.getFloat(Keys.LOW_SHELF, 0.12f);
        softClipEnabled = prefs.getBoolean(Keys.SOFT_CLIP, true);
        softClipLimit = prefs.getFloat(Keys.SOFT_CLIP_LIMIT, 0.99f);

        compressEnabled = prefs.getBoolean(Keys.COMPRESS, true);
        compressThreshold = prefs.getFloat(Keys.COMPRESS_THRESHOLD, 0.35f);
        compressRatio = prefs.getFloat(Keys.COMPRESS_RATIO, 3.0f);
        compressAttack = prefs.getFloat(Keys.COMPRESS_ATTACK, 0.4f);
        compressRelease = prefs.getFloat(Keys.COMPRESS_RELEASE, 0.08f);

        encodeQuality = prefs.getFloat(Keys.ENCODE_QUALITY, 0.5f);
        segmentMs = prefs.getInt(Keys.SEGMENT_MS, 500);

        maxPending = prefs.getInt(Keys.MAX_PENDING, 8);
        captureQueue = prefs.getInt(Keys.CAPTURE_QUEUE, 4);
        startPrebuffer = prefs.getInt(Keys.START_PREBUFFER, 3);

        alignMs = prefs.getInt(Keys.ALIGN_MS, 0);
        muteForeign = prefs.getBoolean(Keys.MUTE_FOREIGN, true);
        mutePollMs = prefs.getInt(Keys.MUTE_POLL_MS, 1500);
        muteGainDb = prefs.getFloat(Keys.MUTE_GAIN_DB, -80f);
        // 默认只保留振动：没有 root 时无法静音原声，若同时回放延迟 600ms 的听筒声，
        // 用户会听到「原声 + 延迟重放」叠加（听起来发闷、像卡顿）。默认静音左右声道即可避免。
        muteLr = prefs.getBoolean(Keys.MUTE_LR, true);
        preset = prefs.getString(Keys.PRESET, Preset.BALANCED.name());
    }

    public static EngineSettings get(Context context) {
        EngineSettings local = instance;
        if (local == null) {
            synchronized (EngineSettings.class) {
                local = instance;
                if (local == null) {
                    local = new EngineSettings(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged(String key) {
        for (Listener l : listeners) {
            l.onSettingsChanged(key);
        }
    }

    // ================= 读取 =================

    public float outputScale() {
        return outputScale;
    }

    public float gateRms() {
        return gateRms;
    }

    public float highpassHz() {
        return highpassHz;
    }

    public int highpassOrder() {
        return highpassOrder;
    }

    @Override
    public float lowpassHz() {
        return lowpassHz;
    }

    @Override
    public boolean carrierEnabled() {
        return carrierEnabled;
    }

    @Override
    public float carrierHz() {
        return carrierHz;
    }

    public float lowShelf() {
        return lowShelf;
    }

    public boolean softClipEnabled() {
        return softClipEnabled;
    }

    public float softClipLimit() {
        return softClipLimit;
    }

    public boolean compressEnabled() {
        return compressEnabled;
    }

    public float compressThreshold() {
        return compressThreshold;
    }

    public float compressRatio() {
        return compressRatio;
    }

    public float compressAttack() {
        return compressAttack;
    }

    public float compressRelease() {
        return compressRelease;
    }

    public float encodeQuality() {
        return encodeQuality;
    }

    public int segmentMs() {
        return segmentMs;
    }

    public int segmentFrames() {
        return Math.round(EngineConfig.SAMPLE_RATE * (segmentMs / 1000f));
    }

    public int maxPending() {
        return maxPending;
    }

    public int captureQueue() {
        return captureQueue;
    }

    public int startPrebuffer() {
        return startPrebuffer;
    }

    public int alignMs() {
        return alignMs;
    }

    public boolean muteForeign() {
        return muteForeign;
    }

    public int mutePollMs() {
        return mutePollMs;
    }

    public float muteGainDb() {
        return muteGainDb;
    }

    public boolean muteLr() {
        return muteLr;
    }

    public String presetName() {
        return preset;
    }

    // ================= 写入 =================

    public void setOutputScale(float v) {
        outputScale = clamp(v, 0.05f, 2.5f);
        prefs.edit().putFloat(Keys.OUTPUT_SCALE, outputScale).apply();
        markCustom(Keys.OUTPUT_SCALE);
    }

    public void setGateRms(float v) {
        gateRms = clamp(v, 0f, 0.2f);
        prefs.edit().putFloat(Keys.GATE_RMS, gateRms).apply();
        markCustom(Keys.GATE_RMS);
    }

    public void setHighpassHz(float v) {
        highpassHz = clamp(v, 40f, 1200f);
        prefs.edit().putFloat(Keys.HIGHPASS_HZ, highpassHz).apply();
        markCustom(Keys.HIGHPASS_HZ);
    }

    public void setHighpassOrder(int v) {
        highpassOrder = (int) clamp(v, 1, 4);
        prefs.edit().putInt(Keys.HIGHPASS_ORDER, highpassOrder).apply();
        markCustom(Keys.HIGHPASS_ORDER);
    }

    public void setLowpassHz(float v) {
        lowpassHz = clamp(v, EngineConfig.LOWPASS_HZ_MIN, EngineConfig.LOWPASS_HZ_MAX);
        prefs.edit().putFloat(Keys.LOWPASS_HZ, lowpassHz).apply();
        markCustom(Keys.LOWPASS_HZ);
    }

    public void setCarrierEnabled(boolean v) {
        carrierEnabled = v;
        prefs.edit().putBoolean(Keys.CARRIER, v).apply();
        notifyChanged(Keys.CARRIER);
    }

    public void setCarrierHz(float v) {
        carrierHz = clamp(v, EngineConfig.CARRIER_HZ_MIN, EngineConfig.CARRIER_HZ_MAX);
        prefs.edit().putFloat(Keys.CARRIER_HZ, carrierHz).apply();
        markCustom(Keys.CARRIER_HZ);
    }

    public void setLowShelf(float v) {
        lowShelf = clamp(v, 0f, 0.6f);
        prefs.edit().putFloat(Keys.LOW_SHELF, lowShelf).apply();
        markCustom(Keys.LOW_SHELF);
    }

    public void setSoftClipEnabled(boolean v) {
        softClipEnabled = v;
        prefs.edit().putBoolean(Keys.SOFT_CLIP, v).apply();
        notifyChanged(Keys.SOFT_CLIP);
    }

    public void setSoftClipLimit(float v) {
        softClipLimit = clamp(v, 0.3f, 1.0f);
        prefs.edit().putFloat(Keys.SOFT_CLIP_LIMIT, softClipLimit).apply();
        markCustom(Keys.SOFT_CLIP_LIMIT);
    }

    public void setCompressEnabled(boolean v) {
        compressEnabled = v;
        prefs.edit().putBoolean(Keys.COMPRESS, v).apply();
        notifyChanged(Keys.COMPRESS);
    }

    public void setCompressThreshold(float v) {
        compressThreshold = clamp(v, 0.02f, 1.0f);
        prefs.edit().putFloat(Keys.COMPRESS_THRESHOLD, compressThreshold).apply();
        markCustom(Keys.COMPRESS_THRESHOLD);
    }

    public void setCompressRatio(float v) {
        compressRatio = clamp(v, 1f, 12f);
        prefs.edit().putFloat(Keys.COMPRESS_RATIO, compressRatio).apply();
        markCustom(Keys.COMPRESS_RATIO);
    }

    public void setCompressAttack(float v) {
        compressAttack = clamp(v, 0.01f, 1f);
        prefs.edit().putFloat(Keys.COMPRESS_ATTACK, compressAttack).apply();
        markCustom(Keys.COMPRESS_ATTACK);
    }

    public void setCompressRelease(float v) {
        compressRelease = clamp(v, 0.01f, 0.6f);
        prefs.edit().putFloat(Keys.COMPRESS_RELEASE, compressRelease).apply();
        markCustom(Keys.COMPRESS_RELEASE);
    }

    public void setEncodeQuality(float v) {
        encodeQuality = clamp(v, 0.05f, 1.0f);
        prefs.edit().putFloat(Keys.ENCODE_QUALITY, encodeQuality).apply();
        notifyChanged(Keys.ENCODE_QUALITY);
    }

    public void setSegmentMs(int v) {
        segmentMs = (int) clamp(v, EngineConfig.SEGMENT_MS_MIN, EngineConfig.SEGMENT_MS_MAX);
        prefs.edit().putInt(Keys.SEGMENT_MS, segmentMs).apply();
        notifyChanged(Keys.SEGMENT_MS);
    }

    public void setMaxPending(int v) {
        maxPending = (int) clamp(v, 2, 16);
        prefs.edit().putInt(Keys.MAX_PENDING, maxPending).apply();
        notifyChanged(Keys.MAX_PENDING);
    }

    public void setCaptureQueue(int v) {
        captureQueue = (int) clamp(v, 2, 16);
        prefs.edit().putInt(Keys.CAPTURE_QUEUE, captureQueue).apply();
        notifyChanged(Keys.CAPTURE_QUEUE);
    }

    public void setStartPrebuffer(int v) {
        startPrebuffer = (int) clamp(v, 1, 8);
        prefs.edit().putInt(Keys.START_PREBUFFER, startPrebuffer).apply();
        notifyChanged(Keys.START_PREBUFFER);
    }

    public void setAlignMs(int v) {
        alignMs = (int) clamp(v, -EngineConfig.ALIGN_MS_LIMIT, EngineConfig.ALIGN_MS_LIMIT);
        prefs.edit().putInt(Keys.ALIGN_MS, alignMs).apply();
        notifyChanged(Keys.ALIGN_MS);
    }

    public void setMuteForeign(boolean v) {
        muteForeign = v;
        prefs.edit().putBoolean(Keys.MUTE_FOREIGN, v).apply();
        notifyChanged(Keys.MUTE_FOREIGN);
    }

    public void setMutePollMs(int v) {
        mutePollMs = (int) clamp(v, 400, 5000);
        prefs.edit().putInt(Keys.MUTE_POLL_MS, mutePollMs).apply();
        notifyChanged(Keys.MUTE_POLL_MS);
    }

    public void setMuteGainDb(float v) {
        muteGainDb = clamp(v, -96f, -10f);
        prefs.edit().putFloat(Keys.MUTE_GAIN_DB, muteGainDb).apply();
        notifyChanged(Keys.MUTE_GAIN_DB);
    }

    public void setMuteLr(boolean v) {
        muteLr = v;
        prefs.edit().putBoolean(Keys.MUTE_LR, v).apply();
        notifyChanged(Keys.MUTE_LR);
    }

    /** 应用预设：一次性写入一整套互相自洽的触觉/压缩参数，立即生效。 */
    public void applyPreset(Preset p) {
        preset = p.name();
        if (p != Preset.CUSTOM) {
            outputScale = p.scale;
            gateRms = p.gate;
            highpassHz = p.hpHz;
            highpassOrder = p.hpOrder;
            lowpassHz = p.lpHz;
            lowShelf = p.shelf;
            compressThreshold = p.threshold;
            compressRatio = p.ratio;
            prefs.edit()
                    .putString(Keys.PRESET, preset)
                    .putFloat(Keys.OUTPUT_SCALE, outputScale)
                    .putFloat(Keys.GATE_RMS, gateRms)
                    .putFloat(Keys.HIGHPASS_HZ, highpassHz)
                    .putInt(Keys.HIGHPASS_ORDER, highpassOrder)
                    .putFloat(Keys.LOWPASS_HZ, lowpassHz)
                    .putFloat(Keys.LOW_SHELF, lowShelf)
                    .putFloat(Keys.COMPRESS_THRESHOLD, compressThreshold)
                    .putFloat(Keys.COMPRESS_RATIO, compressRatio)
                    .apply();
        } else {
            prefs.edit().putString(Keys.PRESET, preset).apply();
        }
        notifyChanged(Keys.PRESET);
    }

    /** 手动改任一触觉参数后，预设标记回落为"自定义"。 */
    private void markCustom(String key) {
        if (!Preset.CUSTOM.name().equals(preset)) {
            preset = Preset.CUSTOM.name();
            prefs.edit().putString(Keys.PRESET, preset).apply();
            notifyChanged(Keys.PRESET);
        }
        notifyChanged(key);
    }

    /** 恢复 §13 参数总表里的出厂默认。 */
    public void resetToDefaults() {
        prefs.edit().clear().apply();
        outputScale = 0.72f;
        gateRms = 0.02f;
        highpassHz = 250f;
        highpassOrder = 2;
        lowpassHz = EngineConfig.LOWPASS_HZ_OFF;
        carrierEnabled = false;
        carrierHz = 160f;
        lowShelf = 0.12f;
        softClipEnabled = true;
        softClipLimit = 0.99f;
        compressEnabled = true;
        compressThreshold = 0.35f;
        compressRatio = 3.0f;
        compressAttack = 0.4f;
        compressRelease = 0.08f;
        encodeQuality = 0.5f;
        segmentMs = 500;
        maxPending = 8;
        captureQueue = 4;
        startPrebuffer = 3;
        alignMs = 0;
        muteForeign = true;
        mutePollMs = 1500;
        muteGainDb = -80f;
        muteLr = true;
        preset = Preset.BALANCED.name();
        notifyChanged("reset");
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
