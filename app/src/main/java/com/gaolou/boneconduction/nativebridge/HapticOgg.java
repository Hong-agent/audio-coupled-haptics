package com.gaolou.boneconduction.nativebridge;

import android.util.Log;

/**
 * JNI 声明（规格书 §6.2）。原生库 libhapticogg.so 内联 libogg + libvorbis，
 * 负责把 3 声道 PCM 编码成带 {@code ANDROID_HAPTIC=1} 标记的 Ogg/Vorbis。
 */
public final class HapticOgg {

    private static final String TAG = "HapticOgg";
    private static volatile boolean loaded;
    private static volatile String loadError;

    static {
        try {
            System.loadLibrary("hapticogg");
            loaded = true;
        } catch (Throwable t) {
            loadError = t.getMessage();
            Log.e(TAG, "loadLibrary failed", t);
        }
    }

    private HapticOgg() {
    }

    public static boolean isAvailable() {
        return loaded;
    }

    public static String loadError() {
        return loadError;
    }

    /**
     * @param pcm 交错 PCM，长度 &gt;= frames*channels
     * @return 0 成功，负数失败
     */
    public static native int nativeEncode(String path, short[] pcm, int frames,
                                          int channels, int sampleRate, float quality);

    public static native String nativeVersion();
}
