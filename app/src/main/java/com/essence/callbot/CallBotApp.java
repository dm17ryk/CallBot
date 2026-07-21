package com.essence.callbot;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

/** Process-wide init: singletons + notification channel for the recorder FGS. */
public class CallBotApp extends Application {
    public static final String REC_CHANNEL_ID = "callbot_rec";

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        EventLog.init(this);
        StatusStore.init(this);

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                REC_CHANNEL_ID, "CallBot recording",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown while CallBot records call audio");
        nm.createNotificationChannel(ch);

        EventLog.log("APP", "CallBot process started");
    }
}
