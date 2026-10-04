package com.gaolou.boneconduction.core;

/**
 * 触觉声道生成所需参数的最小读取接口。
 *
 * <p>{@link EngineSettings} 是它的正式实现（值来自运行期可调 + 持久化）；
 * 单元测试可以注入自己的实现，从而在没有 Context 的 JVM 上验证 DSP 行为。
 */
public interface HapticParams {

    float outputScale();

    float gateRms();

    float highpassHz();

    int highpassOrder();

    /** 输出级低通截止：滤掉马达跟不上的高频与削波谐波，消除破音。 */
    float lowpassHz();

    /** 谐振载波驱动：用马达谐振频率重新合成振感（最干净、不破音）。 */
    boolean carrierEnabled();

    float carrierHz();

    float lowShelf();

    boolean softClipEnabled();

    float softClipLimit();

    boolean compressEnabled();

    float compressThreshold();

    float compressRatio();

    float compressAttack();

    float compressRelease();

    boolean muteLr();
}
