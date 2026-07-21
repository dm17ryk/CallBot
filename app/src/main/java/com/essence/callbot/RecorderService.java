package com.essence.callbot;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service (type=microphone) hosting the CallRecorder — Android 14
 * requires an FGS with the microphone type + notification for in-call capture.
 */
public class RecorderService extends Service {
    private static final String ACTION_START = "com.essence.callbot.REC_START";
    private static final String ACTION_STOP = "com.essence.callbot.REC_STOP";
    private static final int NOTIF_ID = 1;

    private static volatile boolean sRunning = false;
    private CallRecorder mRecorder;

    public static void startRec(Context ctx, String mode, String name) {
        if (sRunning) {
            EventLog.log("REC", "recstart ignored: already recording");
            return;
        }
        Intent i = new Intent(ctx, RecorderService.class)
                .setAction(ACTION_START)
                .putExtra("mode", mode)
                .putExtra("name", name);
        ctx.startForegroundService(i);
    }

    public static void stopRec(Context ctx) {
        if (!sRunning) return;
        Intent i = new Intent(ctx, RecorderService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            Notification n = new Notification.Builder(this, CallBotApp.REC_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("CallBot recording")
                    .setContentText("Capturing call audio")
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            sRunning = true;
            mRecorder = new CallRecorder();
            mRecorder.start(this,
                    intent.getStringExtra("mode"),
                    intent.getStringExtra("name"),
                    () -> sRunning = false);
        } else if (ACTION_STOP.equals(action)) {
            if (mRecorder != null) {
                final CallRecorder r = mRecorder;
                mRecorder = null;
                new Thread(() -> {
                    r.stop();
                    sRunning = false;
                }, "callbot-rec-stop").start();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            final CallRecorder r = mRecorder;
            mRecorder = null;
            new Thread(r::stop, "callbot-rec-destroy").start();
        }
        sRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
