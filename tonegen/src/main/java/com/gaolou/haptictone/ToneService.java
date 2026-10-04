package com.gaolou.haptictone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

/**
 * 播放测试音的前台服务。
 *
 * <p>必须跑在前台服务里：很多 ROM（包括本机的 HyperOS）会把「后台应用的播放」自动静音，
 * 只有声明为 mediaPlayback 的前台服务 + MediaSession 才会被当作音乐应用对待。
 * 测试音在后台也能被「音频耦合触觉」采集到，用户切回主应用看数据时才不会归零。
 */
public final class ToneService extends Service {

    private static final String TAG = "ToneService";

    public static final String ACTION_START = "com.gaolou.haptictone.START";
    public static final String ACTION_STOP = "com.gaolou.haptictone.STOP";

    private static final String CHANNEL_ID = "tone_playback";
    private static final int NOTIFICATION_ID = 2001;
    private static final int SR = 48000;
    private static final int DURATION_S = 120;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private MediaSession session;

    public static void start(Context context) {
        Intent intent = new Intent(context, ToneService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ToneService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "测试音播放",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            Log.i(TAG, "startForeground ok");
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startTone();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        PendingIntent content = PendingIntent.getActivity(this, 0,
                new Intent(this, ToneActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ToneService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("触觉测试音")
                .setContentText("正在播放 55Hz 鼓点 + 440/1200Hz（最多 120 秒）")
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .setOngoing(true)
                .build();
    }

    private void startTone() {
        // MediaSession 让系统把它当作媒体应用（避免后台播放被硬化静音）
        session = new MediaSession(this, "haptic-tone");
        session.setActive(true);

        int minBuf = AudioTrack.getMinBufferSize(SR,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                .build();
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                // 1 秒缓冲：避免测试音自己因调度抖动而断续，干扰对触觉的判断
                .setBufferSizeInBytes(Math.max(minBuf * 4, SR * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        Log.i(TAG, "AudioTrack state=" + track.getState()
                + " playState=" + track.getPlayState()
                + " session=" + track.getAudioSessionId());
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized, state=" + track.getState());
            stopSelf();
            return;
        }
        track.play();

        running = true;
        thread = new Thread(this::pump, "tone-pump");
        thread.start();
    }

    private void pump() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int block = 4800;
        short[] buf = new short[block * 2];
        long written = 0;
        long limit = (long) SR * DURATION_S;
        Log.i(TAG, "pump start");
        while (running && written < limit) {
            for (int i = 0; i < block; i++) {
                double t = (written + i) / (double) SR;
                double beat = 0.7 * Math.sin(2 * Math.PI * 55 * t)
                        * ((t % 0.5) < 0.15 ? 1.0 : 0.12);
                double tone = 0.30 * Math.sin(2 * Math.PI * 440 * t)
                        + 0.15 * Math.sin(2 * Math.PI * 1200 * t);
                short v = (short) (Math.max(-1, Math.min(1, beat + tone)) * 30000);
                buf[i * 2] = v;
                buf[i * 2 + 1] = v;
            }
            int n = track.write(buf, 0, buf.length);
            if (n <= 0) {
                Log.w(TAG, "AudioTrack.write failed n=" + n
                        + " playState=" + track.getPlayState());
                break;
            }
            written += n / 2;
        }
        Log.i(TAG, "pump end written=" + written + " frames (running=" + running + ")");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
