package com.essence.callbot;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted configuration (SharedPreferences). All accessors are static. */
public final class Prefs {
    private static final String NAME = "callbot_prefs";
    private static SharedPreferences sp;

    private Prefs() {}

    static void init(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // --- auto-answer ---
    public static boolean autoAnswerOn()          { return sp.getBoolean("auto_answer", true); }
    public static int autoAnswerDelayMs()         { return sp.getInt("auto_answer_delay_ms", 0); }
    public static void setAutoAnswer(boolean on, int delayMs) {
        sp.edit().putBoolean("auto_answer", on).putInt("auto_answer_delay_ms", delayMs).apply();
    }

    // --- recording ---
    public static String recMode()                { return sp.getString("rec_mode", "auto"); }
    public static void setRecMode(String m)       { sp.edit().putString("rec_mode", m).apply(); }
    /** SAF tree URI chosen in Settings ("" = none). */
    public static String recDirUri()              { return sp.getString("rec_dir_uri", ""); }
    public static void setRecDirUri(String u)     { sp.edit().putString("rec_dir_uri", u).apply(); }
    /** Plain filesystem path set via adb setrecdir ("" = none). */
    public static String recDirPath()             { return sp.getString("rec_dir_path", ""); }
    public static void setRecDirPath(String p)    { sp.edit().putString("rec_dir_path", p).apply(); }

    // --- soundtrack ---
    /** content:// URI or plain /sdcard path of the default soundtrack file. */
    public static String soundtrack()             { return sp.getString("soundtrack", ""); }
    public static void setSoundtrack(String s)    { sp.edit().putString("soundtrack", s).apply(); }
    public static int talkMs()                    { return sp.getInt("talk_ms", 0); }
    public static int pauseMs()                   { return sp.getInt("pause_ms", 0); }
    public static void setCadence(int talkMs, int pauseMs) {
        sp.edit().putInt("talk_ms", talkMs).putInt("pause_ms", pauseMs).apply();
    }
}
