package com.essence.callbot;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Streaming call-audio capture with a layered source fallback chain.
 *
 * Privileged sources (VOICE_CALL / VOICE_DOWNLINK / VOICE_UPLINK) need
 * CAPTURE_AUDIO_OUTPUT (signature|privileged) and are expected to fail on a
 * stock unrooted device — they are tried first so a future rooted/priv-app
 * install picks them up with zero code change. The guaranteed floor is
 * "acoustic": MIC capture with the call routed to the loudspeaker, so the
 * far end is audible to the mic together with any injected soundtrack.
 *
 * In "auto" mode each candidate is probed for ~500 ms; a source that the
 * concurrent-capture policy silences (all-zero buffers) is rejected and the
 * next one is tried. The probe audio is part of the recording.
 */
public final class CallRecorder {
    private static final int SAMPLE_RATE = 16000;
    private static final double SILENCE_RMS = 1.0;
    private static final int PROBE_MS = 500;

    private volatile boolean mStop = false;
    private Thread mThread;

    private static class Candidate {
        final String label; final int source; final boolean forceSpeaker;
        Candidate(String label, int source, boolean forceSpeaker) {
            this.label = label; this.source = source; this.forceSpeaker = forceSpeaker;
        }
    }

    private static List<Candidate> chain(String mode) {
        List<Candidate> c = new ArrayList<>();
        switch (mode == null ? "auto" : mode.toLowerCase()) {
            case "voicecall": c.add(new Candidate("voicecall", MediaRecorder.AudioSource.VOICE_CALL, false)); break;
            case "downlink":  c.add(new Candidate("downlink", MediaRecorder.AudioSource.VOICE_DOWNLINK, false)); break;
            case "uplink":    c.add(new Candidate("uplink", MediaRecorder.AudioSource.VOICE_UPLINK, false)); break;
            case "voicereco": c.add(new Candidate("voicereco", MediaRecorder.AudioSource.VOICE_RECOGNITION, false)); break;
            case "voicecomm": c.add(new Candidate("voicecomm", MediaRecorder.AudioSource.VOICE_COMMUNICATION, false)); break;
            case "mic":       c.add(new Candidate("mic", MediaRecorder.AudioSource.MIC, false)); break;
            case "acoustic":  c.add(new Candidate("acoustic", MediaRecorder.AudioSource.MIC, true)); break;
            case "auto":
            default:
                c.add(new Candidate("voicecall", MediaRecorder.AudioSource.VOICE_CALL, false));
                c.add(new Candidate("voicereco", MediaRecorder.AudioSource.VOICE_RECOGNITION, false));
                c.add(new Candidate("voicecomm", MediaRecorder.AudioSource.VOICE_COMMUNICATION, false));
                c.add(new Candidate("acoustic", MediaRecorder.AudioSource.MIC, true));
                break;
        }
        return c;
    }

    /** Start capture on a background thread. onDone runs when the file is finalized. */
    public void start(Context ctx, String mode, String name, Runnable onDone) {
        final Context app = ctx.getApplicationContext();
        mStop = false;
        mThread = new Thread(() -> run(app, mode, name, onDone), "callbot-rec");
        mThread.start();
    }

    public void stop() {
        mStop = true;
        Thread t = mThread;
        if (t != null) {
            try { t.join(5000); } catch (InterruptedException ignored) {}
        }
    }

    private void run(Context ctx, String mode, String name, Runnable onDone) {
        boolean auto = mode == null || mode.equalsIgnoreCase("auto");
        AudioRecord rec = null;
        Candidate chosen = null;
        byte[] probe = null;
        int probeLen = 0;

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, SAMPLE_RATE); // >= 0.5 s

        List<Candidate> candidates = chain(mode);
        for (int ci = 0; ci < candidates.size(); ci++) {
            Candidate cand = candidates.get(ci);
            boolean lastResort = ci == candidates.size() - 1;
            AudioRecord r = null;
            try {
                r = new AudioRecord(cand.source, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                    EventLog.log("REC", cand.label + ": not initialized");
                    r.release();
                    continue;
                }
                if (cand.forceSpeaker) CallBotInCallService.setRoute("speaker");
                r.startRecording();
                if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    EventLog.log("REC", cand.label + ": failed to start");
                    r.release();
                    continue;
                }
                // probe ~500 ms
                byte[] pbuf = new byte[SAMPLE_RATE * 2 * PROBE_MS / 1000];
                int got = 0;
                while (got < pbuf.length && !mStop) {
                    int n = r.read(pbuf, got, pbuf.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                double rms = Wav.rms(pbuf, got);
                EventLog.log("REC", cand.label + ": probe rms=" + String.format(Locale.US, "%.1f", rms));
                if (auto && rms < SILENCE_RMS && !lastResort) {
                    // silenced by policy -> try next source (keep last candidate regardless)
                    r.stop(); r.release();
                    continue;
                }
                rec = r;
                chosen = cand;
                probe = pbuf;
                probeLen = got;
                break;
            } catch (Exception e) {
                EventLog.log("REC", cand.label + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
                if (r != null) try { r.release(); } catch (Exception ignored) {}
            }
        }

        if (rec == null) {
            StatusStore.get().setLastError("recording failed: no usable audio source");
            StatusStore.get().setRecording("stopped", mode, "");
            if (onDone != null) onDone.run();
            return;
        }

        File dir = new File(ctx.getExternalFilesDir(null), "rec");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(dir, name + "_" + chosen.label + "_" + stamp + ".wav");

        EventLog.log("REC", "recording with source=" + chosen.label + " -> " + out.getName());
        StatusStore.get().setRecording("recording", chosen.label, out.getAbsolutePath());

        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            Wav.writeHeader(raf, SAMPLE_RATE, 1, 16);
            if (probeLen > 0) raf.write(probe, 0, probeLen);

            byte[] buf = new byte[SAMPLE_RATE * 2 / 25]; // 40 ms chunks
            long lastRmsPush = 0;
            while (!mStop) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) break;
                raf.write(buf, 0, n);
                long now = System.currentTimeMillis();
                if (now - lastRmsPush > 500) {
                    StatusStore.get().setRecRms(Wav.rms(buf, n));
                    lastRmsPush = now;
                }
            }
            Wav.finalizeHeader(raf);
        } catch (IOException e) {
            StatusStore.get().setLastError("recording io error: " + e);
        } finally {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
        }

        String finalPath = exportIfConfigured(ctx, out);
        EventLog.log("REC", "recording saved: " + finalPath);
        StatusStore.get().setRecording("stopped", chosen.label, finalPath);
        if (onDone != null) onDone.run();
    }

    /**
     * If the user picked a custom folder (SAF tree in Settings, or a plain path
     * via `setrecdir`), copy the finished WAV there and report the new location.
     * The original in files/rec/ is kept on copy failure.
     */
    private String exportIfConfigured(Context ctx, File src) {
        String treeUri = Prefs.recDirUri();
        if (!treeUri.isEmpty()) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                Uri tree = Uri.parse(treeUri);
                Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                Uri doc = DocumentsContract.createDocument(cr, parentDoc, "audio/wav", src.getName());
                if (doc != null) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream os = cr.openOutputStream(doc)) {
                        copy(in, os);
                    }
                    return doc.toString() + " (also " + src.getAbsolutePath() + ")";
                }
            } catch (Exception e) {
                StatusStore.get().setLastError("SAF export failed: " + e);
            }
            return src.getAbsolutePath();
        }
        String path = Prefs.recDirPath();
        if (!path.isEmpty()) {
            try {
                File dstDir = new File(path);
                //noinspection ResultOfMethodCallIgnored
                dstDir.mkdirs();
                File dst = new File(dstDir, src.getName());
                try (InputStream in = new FileInputStream(src);
                     OutputStream os = new java.io.FileOutputStream(dst)) {
                    copy(in, os);
                }
                return dst.getAbsolutePath();
            } catch (Exception e) {
                StatusStore.get().setLastError("export to " + path + " failed: " + e);
            }
        }
        return src.getAbsolutePath();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }
}
