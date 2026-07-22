package com.essence.callbot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Plays the "person talking" soundtrack so the FAR END of the call hears it.
 *
 * Injection reality by device generation:
 *  - Old Poco F1 (Android 10): USAGE_VOICE_COMMUNICATION playback was mixed into
 *    the voice-call TX by the HAL ("voicecomm" mode) — the dtmf_apk trick.
 *  - POCO X5 Pro (Android 14 / HyperOS 2): that path plays only LOCALLY. Two options:
 *      "telephony" — route the player to the AudioDeviceInfo.TYPE_TELEPHONY output
 *                    if the HAL exposes it (clean uplink injection, no echo);
 *      "acoustic"  — unmute the call mic, force speaker route, play as loud media:
 *                    the mic picks it up -> uplink. Always works; same-room echo.
 *
 * via modes: auto (telephony if available, else acoustic) | telephony | voicecomm | acoustic.
 * MediaPlayer decodes MP3/AAC/OGG/FLAC/WAV/AMR natively.
 */
public final class SoundtrackPlayer {
    private static final SoundtrackPlayer INSTANCE = new SoundtrackPlayer();

    private final Handler mWorker;
    private MediaPlayer mPlayer;
    private boolean mCadenceTalking;
    private int mTalkMs, mPauseMs;
    private final Runnable mCadenceTick = this::cadenceTick;
    // last start args, for the auto-mode telephony->acoustic error fallback
    private Context mCtx;
    private String mFile, mMode;
    private boolean mLoop, mAutoRequested;

    private SoundtrackPlayer() {
        HandlerThread t = new HandlerThread("callbot-player");
        t.start();
        mWorker = new Handler(t.getLooper());
    }

    public static SoundtrackPlayer get() { return INSTANCE; }

    private static AudioDeviceInfo findTelephonyOut(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) return d;
        }
        return null;
    }

    /** Start playback. Errors are async -> status/last_error + event log. */
    public synchronized String start(Context ctx, String fileOrUri, boolean loop,
                                     int talkMs, int pauseMs, String via) {
        final Context app = ctx.getApplicationContext();
        final String reqVia = (via == null || via.isEmpty()) ? "auto" : via.toLowerCase();
        mWorker.post(() -> {
            stopLocked();
            try {
                synchronized (SoundtrackPlayer.this) {
                    mCtx = app;
                    mFile = fileOrUri;
                    mLoop = loop;
                    mTalkMs = talkMs;
                    mPauseMs = pauseMs;
                    mAutoRequested = "auto".equals(reqVia);
                }
                AudioManager am = app.getSystemService(AudioManager.class);
                AudioDeviceInfo telephony = findTelephonyOut(am);

                String mode = reqVia;
                if ("auto".equals(mode)) mode = telephony != null ? "telephony" : "acoustic";
                if ("telephony".equals(mode) && telephony == null) {
                    EventLog.log("AUDIO", "no TYPE_TELEPHONY output on this device -> acoustic");
                    mode = "acoustic";
                }

                MediaPlayer p = new MediaPlayer();
                if ("acoustic".equals(mode)) {
                    // far end hears it via the mic: unmute + speaker + loud media stream
                    CallBotInCallService.setMute(false);
                    CallBotInCallService.setRoute("speaker");
                    am.setStreamVolume(AudioManager.STREAM_MUSIC,
                            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
                    p.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                } else {
                    p.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                }
                if (fileOrUri.startsWith("content:")) {
                    p.setDataSource(app, Uri.parse(fileOrUri));
                } else {
                    p.setDataSource(fileOrUri);
                }
                p.setLooping(loop);
                p.setOnCompletionListener(mp -> {
                    if (!mp.isLooping()) {
                        EventLog.log("AUDIO", "soundtrack finished");
                        synchronized (SoundtrackPlayer.this) { stopLocked(); }
                    }
                });
                final String startedMode = mode;
                p.setOnErrorListener((mp, what, extra) -> {
                    synchronized (SoundtrackPlayer.this) {
                        stopLocked();
                        if ("telephony".equals(startedMode) && mAutoRequested) {
                            // HAL refused the telephony TX output (e.g. -19 on HyperOS):
                            // retry with the guaranteed acoustic path
                            EventLog.log("AUDIO", "telephony playback failed (" + what + "/" + extra
                                    + ") -> acoustic fallback");
                            start(mCtx, mFile, mLoop, mTalkMs, mPauseMs, "acoustic");
                        } else {
                            StatusStore.get().setLastError(
                                    "soundtrack error what=" + what + " extra=" + extra);
                        }
                    }
                    return true;
                });
                p.prepare();
                if ("telephony".equals(mode)) {
                    boolean ok = p.setPreferredDevice(telephony);
                    EventLog.log("AUDIO", "telephony TX routing " + (ok ? "accepted" : "REJECTED"));
                }
                p.start();
                synchronized (SoundtrackPlayer.this) {
                    mPlayer = p;
                    mTalkMs = talkMs;
                    mPauseMs = pauseMs;
                    if (talkMs > 0 && pauseMs > 0) {
                        mCadenceTalking = true;
                        mWorker.postDelayed(mCadenceTick, talkMs);
                        StatusStore.get().setPlayback("talking(" + mode + ")", fileOrUri);
                    } else {
                        StatusStore.get().setPlayback((loop ? "looping(" : "playing(") + mode + ")", fileOrUri);
                    }
                }
                EventLog.log("AUDIO", "soundtrack start via=" + mode + ": " + fileOrUri
                        + (loop ? " loop" : "")
                        + (talkMs > 0 && pauseMs > 0 ? " cadence=" + talkMs + "/" + pauseMs : ""));
            } catch (Exception e) {
                StatusStore.get().setLastError("soundtrack start failed: " + e);
                StatusStore.get().setPlayback("stopped", "");
            }
        });
        return null;
    }

    public synchronized void stop() {
        mWorker.post(() -> {
            synchronized (SoundtrackPlayer.this) {
                if (mPlayer != null) EventLog.log("AUDIO", "soundtrack stop");
                stopLocked();
            }
        });
    }

    /** Must run on worker or hold monitor; releases player + cadence. */
    private void stopLocked() {
        mWorker.removeCallbacks(mCadenceTick);
        if (mPlayer != null) {
            try { mPlayer.stop(); } catch (Exception ignored) {}
            try { mPlayer.release(); } catch (Exception ignored) {}
            mPlayer = null;
            StatusStore.get().setPlayback("stopped", "");
        }
    }

    private void cadenceTick() {
        synchronized (this) {
            MediaPlayer p = mPlayer;
            if (p == null) return;
            try {
                if (mCadenceTalking) {
                    p.pause();
                    mCadenceTalking = false;
                    StatusStore.get().setPlayback("cadence_pause", null);
                    mWorker.postDelayed(mCadenceTick, mPauseMs);
                } else {
                    p.start();
                    mCadenceTalking = true;
                    StatusStore.get().setPlayback("talking", null);
                    mWorker.postDelayed(mCadenceTick, mTalkMs);
                }
            } catch (IllegalStateException e) {
                stopLocked();
            }
        }
    }
}
