package com.gaolou.boneconduction.core;

/**
 * 不可变常量：全链路里不允许运行期修改的结构性参数。
 * 可调项一律放在 {@link EngineSettings}。
 */
public final class EngineConfig {

    private EngineConfig() {
    }

    /** 采集与输出采样率，固定 48kHz。 */
    public static final int SAMPLE_RATE = 48000;

    /** 采集块长度：4800 帧 = 100ms。 */
    public static final int CAPTURE_BLOCK_FRAMES = 4800;

    /** 输出声道数：L / R / haptic。 */
    public static final int OUT_CHANNELS = 3;

    /** 采集声道数：立体声。 */
    public static final int IN_CHANNELS = 2;

    /** 播放泵与路由自愈周期。 */
    public static final int PUMP_INTERVAL_MS = 50;

    /** 编码线程等待段数据的最长时间。 */
    public static final int ENCODE_POLL_MS = 200;

    /** 环形文件槽数量（>= maxPending + 2，保证写入的文件不会被正在播放的段覆盖）。 */
    public static final int RING_SLOTS = 24;

    /** 段时长可调范围。 */
    public static final int SEGMENT_MS_MIN = 200;
    public static final int SEGMENT_MS_MAX = 1000;

    /** 对齐延迟可调范围（毫秒），正 = 振动晚于声音。 */
    public static final int ALIGN_MS_LIMIT = 2000;

    /** 输出级低通（马达高频跟随上限）可调范围；拉到最大值等于关闭。 */
    public static final float LOWPASS_HZ_MIN = 60f;
    public static final float LOWPASS_HZ_MAX = 20000f;
    /** 默认不滤波：保持原来的宽频驱动手感。 */
    public static final float LOWPASS_HZ_OFF = 20000f;

    /** 震动强度（触觉总电平）可调范围。 */
    public static final float OUTPUT_SCALE_MIN = 0.05f;
    public static final float OUTPUT_SCALE_MAX = 2.5f;

    /** 谐振载波频率范围（典型 LRA 谐振 130~200Hz）。 */
    public static final float CARRIER_HZ_MIN = 80f;
    public static final float CARRIER_HZ_MAX = 260f;
}
