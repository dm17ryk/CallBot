package com.essence.callbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for app state, readable three ways:
 *  1. `status` command -> ordered-broadcast result (synchronous over adb)
 *  2. files/status.json (adb shell cat)
 *  3. GUI listeners (live refresh)
 */
public final class StatusStore {
    public interface Listener { void onStatusChanged(); }

    private static StatusStore sInstance;
    private static File sJsonFile;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    // guarded by this
    private String callState = "IDLE";
    private String number = "";
    private long callStartElapsed = 0;   // SystemClock.elapsedRealtime() at ACTIVE, 0 = none
    private boolean muted = false;
    private String route = "unknown";
    private String playbackState = "stopped";
    private String playbackFile = "";
    private String recState = "stopped";
    private String recMode = "";
    private String recFile = "";
    private double recRms = 0;
    private String lastError = null;

    private StatusStore() {}

    static void init(Context ctx) {
        sInstance = new StatusStore();
        sJsonFile = new File(ctx.getExternalFilesDir(null), "status.json");
    }

    public static StatusStore get() { return sInstance; }

    // --- setters -----------------------------------------------------------

    public synchronized void setCallState(String state, String num) {
        callState = state;
        if (num != null) number = num;
        publish();
    }

    public synchronized void setCallStartElapsed(long elapsed) {
        callStartElapsed = elapsed;
        publish();
    }

    public synchronized void setAudio(boolean isMuted, String audioRoute) {
        muted = isMuted;
        route = audioRoute;
        publish();
    }

    public synchronized void setPlayback(String state, String file) {
        playbackState = state;
        if (file != null) playbackFile = file;
        publish();
    }

    public synchronized void setRecording(String state, String mode, String file) {
        recState = state;
        if (mode != null) recMode = mode;
        if (file != null) recFile = file;
        publish();
    }

    public synchronized void setRecRms(double rms) {
        recRms = rms;
        publish();
    }

    public synchronized void setLastError(String err) {
        lastError = err;
        if (err != null) EventLog.log("APP", "ERROR: " + err);
        publish();
    }

    // --- getters used by the GUI ------------------------------------------

    public synchronized String callState()        { return callState; }
    public synchronized String number()           { return number; }
    public synchronized long callStartElapsed()   { return callStartElapsed; }
    public synchronized boolean muted()           { return muted; }
    public synchronized String route()            { return route; }
    public synchronized String playbackState()    { return playbackState; }
    public synchronized String recStateStr()      { return recState; }

    // --- output ------------------------------------------------------------

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("call_state", callState);
            o.put("number", number);
            o.put("in_call_since_elapsed", callStartElapsed);
            o.put("muted", muted);
            o.put("route", route);
            JSONObject aa = new JSONObject();
            aa.put("on", Prefs.autoAnswerOn());
            aa.put("delay_ms", Prefs.autoAnswerDelayMs());
            o.put("auto_answer", aa);
            JSONObject pb = new JSONObject();
            pb.put("state", playbackState);
            pb.put("file", playbackFile);
            o.put("playback", pb);
            JSONObject rec = new JSONObject();
            rec.put("state", recState);
            rec.put("mode", recMode);
            rec.put("file", recFile);
            rec.put("rms", Math.round(recRms * 10) / 10.0);
            o.put("recording", rec);
            o.put("last_error", lastError == null ? JSONObject.NULL : lastError);
            o.put("event_log", EventLog.logFilePath());
            o.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        return o;
    }

    private void publish() {
        // mirror to status.json (small file; called on state changes only)
        if (sJsonFile != null) {
            try (FileWriter w = new FileWriter(sJsonFile, false)) {
                w.write(toJson().toString());
            } catch (IOException ignored) {}
        }
        for (Listener l : mListeners) sMain.post(l::onStatusChanged);
    }

    public void addListener(Listener l)    { mListeners.add(l); }
    public void removeListener(Listener l) { mListeners.remove(l); }
}
