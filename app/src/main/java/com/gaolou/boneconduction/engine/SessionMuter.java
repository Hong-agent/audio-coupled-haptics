package com.gaolou.boneconduction.engine;

import android.annotation.SuppressLint;
import android.media.audiofx.DynamicsProcessing;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gaolou.boneconduction.core.EngineSettings;
import com.gaolou.boneconduction.core.EngineStatus;

/**
 * 原声静音（规格书 §8）：把外部应用的原声会话压到 -80dB 来消除「原声 + 延迟重放」的回声。
 *
 * <p>静音手段按优先级：DynamicsProcessing 输入增益 → 反射隐藏的 Volume 效果器 → 降级（保留回声）。
 * 绝不静音自己：调用方传入的 ownSessionIds 会被排除。
 */
public final class SessionMuter {

    private static final String TAG = "SessionMuter";

    private static final Pattern SESSION_PATTERN =
            Pattern.compile("session[:=\\s]+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UID_PATTERN =
            Pattern.compile("uid[:/=\\s]+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final EngineSettings settings;
    private final EngineStatus status;
    private final PrivilegedShell shell;

    private final Map<Integer, DynamicsProcessing> attached = new HashMap<>();
    private final Map<Integer, Object> volumeFallbacks = new HashMap<>();

    public SessionMuter(EngineSettings settings, EngineStatus status, PrivilegedShell shell) {
        this.settings = settings;
        this.status = status;
        this.shell = shell;
    }

    /** 周期性调用：扫描外部会话并静音。 */
    public void poll(Set<Integer> ownSessionIds) {
        status.muteBackend = shell.backendName();
        if (!settings.muteForeign()) {
            releaseAll();
            status.mutedForeign = false;
            return;
        }
        if (shell.backend() == PrivilegedShell.Backend.NONE) {
            status.mutedForeign = false;
            return;
        }

        Set<Integer> foreign = discoverForeignSessions(ownSessionIds);
        if (foreign == null) {
            status.mutedForeign = false;
            return;
        }

        // 释放已消失的会话
        List<Integer> stale = new ArrayList<>();
        for (Integer id : attached.keySet()) {
            if (!foreign.contains(id)) {
                stale.add(id);
            }
        }
        for (Integer id : stale) {
            DynamicsProcessing dp = attached.remove(id);
            releaseEffect(dp);
        }
        for (Integer id : new ArrayList<>(volumeFallbacks.keySet())) {
            if (!foreign.contains(id)) {
                releaseEffect(volumeFallbacks.remove(id));
            }
        }

        // 给新出现的会话挂静音
        for (Integer id : foreign) {
            if (attached.containsKey(id) || volumeFallbacks.containsKey(id)) {
                continue;
            }
            attachMute(id);
        }

        status.mutedSessionCount = attached.size() + volumeFallbacks.size();
        status.mutedForeign = status.mutedSessionCount > 0;
    }

    private Set<Integer> discoverForeignSessions(Set<Integer> ownSessionIds) {
        PrivilegedShell.Result result =
                shell.execPrivileged("dumpsys audio | grep -i -E 'session|AudioPlaybackConfiguration'");
        if (!result.ok()) {
            return null;
        }
        Set<Integer> ids = new HashSet<>();
        for (String line : result.output.split("\n")) {
            if (!line.toLowerCase().contains("session")) {
                continue;
            }
            Matcher sm = SESSION_PATTERN.matcher(line);
            if (!sm.find()) {
                continue;
            }
            int sessionId;
            try {
                sessionId = Integer.parseInt(sm.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (sessionId <= 0 || ownSessionIds.contains(sessionId)) {
                continue;
            }
            Matcher um = UID_PATTERN.matcher(line);
            if (um.find()) {
                try {
                    // uid 0 / 1000（system）通常是系统提示音，不动它
                    int uid = Integer.parseInt(um.group(1));
                    if (uid == 0 || uid == 1000) {
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            ids.add(sessionId);
        }
        return ids;
    }

    private void attachMute(int sessionId) {
        float gainDb = settings.muteGainDb();
        DynamicsProcessing dp = null;
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    false, 0,
                    false, 0,
                    false, 0,
                    false).build();
            dp = new DynamicsProcessing(0, sessionId, config);
            dp.setInputGainAllChannelsTo(gainDb);
            dp.setEnabled(true);
            attached.put(sessionId, dp);
            Log.i(TAG, "muted session " + sessionId + " via DynamicsProcessing @" + gainDb + "dB");
            return;
        } catch (Throwable t) {
            releaseEffect(dp);
            Log.w(TAG, "DynamicsProcessing failed for session " + sessionId, t);
        }

        Object volume = attachVolumeFallback(sessionId);
        if (volume != null) {
            volumeFallbacks.put(sessionId, volume);
            Log.i(TAG, "muted session " + sessionId + " via Volume fallback");
        }
    }

    /** 反射隐藏类 android.media.audiofx.Volume（PARAM_MUTE / PARAM_LEVEL）。 */
    @SuppressLint("PrivateApi")
    private Object attachVolumeFallback(int sessionId) {
        try {
            Class<?> cls = Class.forName("android.media.audiofx.Volume");
            Constructor<?> ctor = cls.getConstructor(int.class, int.class);
            Object effect = ctor.newInstance(0, sessionId);
            Method setParameter = cls.getMethod("setParameter", short.class, short.class);
            Method setEnabled = cls.getMethod("setEnabled", boolean.class);
            setParameter.invoke(effect, (short) 2, (short) 1);   // PARAM_MUTE
            setParameter.invoke(effect, (short) 0, (short) -9600); // PARAM_LEVEL = -96dB
            setEnabled.invoke(effect, true);
            return effect;
        } catch (Throwable t) {
            return null;
        }
    }

    private void releaseEffect(Object effect) {
        if (effect == null) {
            return;
        }
        try {
            if (effect instanceof DynamicsProcessing) {
                ((DynamicsProcessing) effect).setEnabled(false);
            }
            Method release = effect.getClass().getMethod("release");
            release.invoke(effect);
        } catch (Throwable ignored) {
        }
    }

    public void releaseAll() {
        for (DynamicsProcessing dp : attached.values()) {
            releaseEffect(dp);
        }
        attached.clear();
        for (Object v : volumeFallbacks.values()) {
            releaseEffect(v);
        }
        volumeFallbacks.clear();
        status.mutedSessionCount = 0;
    }
}
