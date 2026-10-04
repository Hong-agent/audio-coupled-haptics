package com.gaolou.boneconduction.engine;

import android.content.Context;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.gaolou.boneconduction.core.EngineStatus;

/**
 * 设备能力探测（规格书 §9.1）。启动时执行，结果直接决定 UI 上的能力提示，
 * 避免用户把「设备不支持」误当成软件故障。
 */
public final class Haptics {

    private Haptics() {
    }

    public static void probe(Context context, EngineStatus status) {
        Vibrator vibrator = null;
        try {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                vibrator = manager.getDefaultVibrator();
            }
        } catch (Throwable ignored) {
        }
        if (vibrator == null) {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }

        boolean primitives = false;
        boolean amplitude = false;
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                primitives = vibrator.areAllPrimitivesSupported();
            } catch (Throwable ignored) {
            }
            try {
                amplitude = vibrator.hasAmplitudeControl();
            } catch (Throwable ignored) {
            }
        }
        status.hapticSupported = primitives;
        status.amplitudeControl = amplitude;
        status.deviceName = Build.MANUFACTURER + " " + Build.MODEL
                + " · API " + Build.VERSION.SDK_INT;
    }
}
