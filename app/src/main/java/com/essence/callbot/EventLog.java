package com.essence.callbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Timestamped event journal: every command (ADB or GUI), Telecom state change,
 * playback / recording event. Feeds the GUI live, mirrors to logcat tag CALLBOT,
 * and persists to files/logs/events_<session>.log (adb-pullable).
 */
public final class EventLog {
    public static final String TAG = "CALLBOT";
    private static final int MAX_ENTRIES = 400;

    public interface Listener { void onEvent(String line); }

    private static final ArrayDeque<String> sBuf = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static File sLogFile;
    private static FileWriter sWriter;

    private EventLog() {}

    static void init(Context ctx) {
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name = "events_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".log";
            sLogFile = new File(dir, name);
            sWriter = new FileWriter(sLogFile, true);
        } catch (IOException e) {
            Log.e(TAG, "EventLog init failed: " + e);
        }
    }

    /** src: ADB | GUI | TELECOM | AUDIO | REC | APP */
    public static void log(String src, String msg) {
        String line = FMT.format(new Date()) + " [" + src + "] " + msg;
        Log.i(TAG, line);
        synchronized (sBuf) {
            sBuf.addLast(line);
            while (sBuf.size() > MAX_ENTRIES) sBuf.removeFirst();
        }
        if (sWriter != null) {
            try { sWriter.write(line + "\n"); sWriter.flush(); } catch (IOException ignored) {}
        }
        for (Listener l : sListeners) sMain.post(() -> l.onEvent(line));
    }

    public static List<String> snapshot() {
        synchronized (sBuf) { return new ArrayList<>(sBuf); }
    }

    public static String logFilePath() {
        return sLogFile != null ? sLogFile.getAbsolutePath() : "";
    }

    public static void addListener(Listener l)    { sListeners.add(l); }
    public static void removeListener(Listener l) { sListeners.remove(l); }
}
