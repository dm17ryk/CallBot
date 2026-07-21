package com.essence.callbot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Injects a "person talking" soundtrack into the call uplink.
 *
 * Uses USAGE_VOICE_COMMUNICATION so the audio is mixed into the voice-call
 * TX path and reaches the far end as RTP (pattern proven on the dtmf_apk
 * PlayWavActivity — works even while the call mic is muted).
 *
 * MediaPlayer decodes MP3/AAC/OGG/FLAC/WAV/AMR natively.
 * Optional cadence: play talk_ms, pause pause_ms, repeat — simulates
 * conversational speech instead of a continuous wall of sound.
 */
public final class SoundtrackPlayer {
    private static final SoundtrackPlayer INSTANCE = new SoundtrackPlayer();

    private final Handler mWorker;
    private MediaPlayer mPlayer;
    private boolean mCadenceTalking;
    private int mTalkMs, mPauseMs;
    private final Runnable mCadenceTick = this::cadenceTick;

    private SoundtrackPlayer() {
        HandlerThread t = new HandlerThread("callbot-player");
        t.start();
        mWorker = new Handler(t.getLooper());
    }

    public static SoundtrackPlayer get() { return INSTANCE; }

    /** Start playback. Returns null (errors are async -> status/last_error). */
    public synchronized String start(Context ctx, String fileOrUri, boolean loop,
                                     int talkMs, int pauseMs) {
        final Context app = ctx.getApplicationContext();
        mWorker.post(() -> {
            stopLocked();
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
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
                p.setOnErrorListener((mp, what, extra) -> {
                    StatusStore.get().setLastError("soundtrack error what=" + what + " extra=" + extra);
                    synchronized (SoundtrackPlayer.this) { stopLocked(); }
                    return true;
                });
                p.prepare();
                p.start();
                synchronized (SoundtrackPlayer.this) {
                    mPlayer = p;
                    mTalkMs = talkMs;
                    mPauseMs = pauseMs;
                    if (talkMs > 0 && pauseMs > 0) {
                        mCadenceTalking = true;
                        mWorker.postDelayed(mCadenceTick, talkMs);
                        StatusStore.get().setPlayback("talking", fileOrUri);
                    } else {
                        StatusStore.get().setPlayback(loop ? "looping" : "playing", fileOrUri);
                    }
                }
                EventLog.log("AUDIO", "soundtrack start: " + fileOrUri
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
