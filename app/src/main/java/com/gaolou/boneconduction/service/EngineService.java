package com.gaolou.boneconduction.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.Set;

import com.gaolou.boneconduction.MainActivity;
import com.gaolou.boneconduction.R;
import com.gaolou.boneconduction.core.EngineSettings;
import com.gaolou.boneconduction.core.EngineStatus;
import com.gaolou.boneconduction.engine.CaptureEngine;
import com.gaolou.boneconduction.engine.HapticMixer;
import com.gaolou.boneconduction.engine.Haptics;
import com.gaolou.boneconduction.engine.OggSink;
import com.gaolou.boneconduction.engine.PrivilegedShell;
import com.gaolou.boneconduction.engine.SegmentPlayer;
import com.gaolou.boneconduction.engine.SegmentRing;
import com.gaolou.boneconduction.engine.SessionMuter;
import com.gaolou.boneconduction.nativebridge.HapticOgg;

/**
 * 前台服务（规格书 §9.2）：总控采集合成播放全链路。
 * 持有 PARTIAL_WAKE_LOCK，息屏继续运行；注册 MediaProjection 回调以便被系统停止时优雅收尾。
 */
public final class EngineService extends Service {

    private static final String TAG = "EngineService";

    public static final String ACTION_START = "com.gaolou.boneconduction.action.START";
    public static final String ACTION_STOP = "com.gaolou.boneconduction.action.STOP";
    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";

    private static final String CHANNEL_ID = "haptic_engine";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EngineStatus status = EngineStatus.get();

    private EngineSettings settings;
    private SegmentRing ring;
    private CaptureEngine capture;
    private OggSink sink;
    private SegmentPlayer player;
    private SessionMuter muter;
    private PrivilegedShell shell;
    private MediaProjection projection;
    private PowerManager.WakeLock wakeLock;
    private boolean muteTickScheduled;

    public static boolean isRunning() {
        return running;
    }

    /** 由 Activity 在拿到屏幕录制授权后调用。 */
    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, EngineService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, data);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, EngineService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = EngineSettings.get(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEngine("用户停止");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android 14+：必须先进入 mediaProjection 类型的前台服务，才能使用 MediaProjection
        startForegroundCompat();

        if (running) {
            return START_NOT_STICKY;
        }
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            status.state = EngineStatus.STATE_ERROR;
            status.message = "缺少屏幕录制授权数据";
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        startEngine(resultCode, resultData);
        return START_NOT_STICKY;
    }

    /** minSdk 31 起三参版本可用：必须显式声明 mediaProjection 类型，否则 Android 14+ 会崩溃。 */
    private void startForegroundCompat() {
        startForeground(NOTIFICATION_ID, buildNotification("准备录制系统内声音…"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void startEngine(int resultCode, Intent resultData) {
        status.reset();
        status.state = EngineStatus.STATE_STARTING;
        status.message = "正在启动…";
        Haptics.probe(this, status);
        status.muteBackend = "未探测";

        if (!HapticOgg.isAvailable()) {
            status.state = EngineStatus.STATE_ERROR;
            status.message = "原生编码库加载失败：" + HapticOgg.loadError();
            stopSelf();
            return;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
        } catch (Exception e) {
            status.state = EngineStatus.STATE_ERROR;
            status.message = "获取 MediaProjection 失败：" + e.getMessage();
            stopSelf();
            return;
        }
        if (projection == null) {
            status.state = EngineStatus.STATE_ERROR;
            status.message = "MediaProjection 为空（授权可能被拒绝）";
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopEngine("系统停止了录屏");
                stopSelf();
            }
        }, handler);

        acquireWakeLock();

        File dir = new File(getCacheDir(), "haptic_segments");
        ring = new SegmentRing(dir);
        ring.clean();

        HapticMixer mixer = new HapticMixer(settings);
        player = new SegmentPlayer(this, settings, status,
                msg -> status.message = msg);
        sink = new OggSink(settings, status, ring, player, mixer);
        capture = new CaptureEngine();
        shell = new PrivilegedShell();
        muter = new SessionMuter(settings, status, shell);

        Thread probe = new Thread(shell::detect, "shell-probe");
        probe.start();

        int rc = capture.start(projection, sink::onBlock, msg -> {
            status.state = EngineStatus.STATE_ERROR;
            status.message = msg;
            handler.post(() -> stopEngine(msg));
        });
        if (rc != 0) {
            status.state = EngineStatus.STATE_ERROR;
            status.message = rc == -1 ? "AudioRecord 初始化失败（该机型可能不支持内录）"
                    : "创建 AudioRecord 失败，错误码 " + rc;
            stopEngine(status.message);
            stopSelf();
            return;
        }

        player.start();
        sink.start();

        running = true;
        status.state = EngineStatus.STATE_RUNNING;
        status.message = "运行中";
        status.startedAtMs = System.currentTimeMillis();
        startMuteTicker();
        updateNotification("正在录制系统内声音并驱动马达");
        Log.i(TAG, "engine started");
    }

    private void stopEngine(String reason) {
        running = false;
        muteTickScheduled = false;
        handler.removeCallbacksAndMessages(null);
        if (sink != null) {
            sink.stop();
            sink = null;
        }
        if (player != null) {
            player.stop();
            player = null;
        }
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        if (muter != null) {
            muter.releaseAll();
            muter = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        releaseWakeLock();
        status.state = EngineStatus.STATE_IDLE;
        status.message = reason == null ? "已停止" : reason;
        status.startedAtMs = 0;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "engine stopped: " + reason);
    }

    @Override
    public void onDestroy() {
        stopEngine("服务结束");
        super.onDestroy();
    }

    // ===================== 静音 ticker =====================

    private void startMuteTicker() {
        if (muteTickScheduled) {
            return;
        }
        muteTickScheduled = true;
        handler.post(muteTicker);
    }

    private final Runnable muteTicker = new Runnable() {
        @Override
        public void run() {
            if (!running || !muteTickScheduled) {
                return;
            }
            if (muter != null) {
                Set<Integer> own = player != null
                        ? Collections.singleton(player.currentSessionId())
                        : Collections.emptySet();
                muter.poll(own);
            }
            // 轮询周期同样可调，改动下一拍生效
            handler.postDelayed(this, Math.max(400, settings.mutePollMs()));
        }
    };

    // ===================== 通知 =====================

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "触觉引擎",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("音频耦合触觉运行状态");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, EngineService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_haptic)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    // ===================== WakeLock =====================

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haptic:engine");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
