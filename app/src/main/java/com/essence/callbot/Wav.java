package com.essence.callbot;

import java.io.IOException;
import java.io.RandomAccessFile;

/** Minimal RIFF/WAV writer helpers for streaming PCM16 capture. */
public final class Wav {
    private Wav() {}

    /** Write a 44-byte PCM WAV header with placeholder sizes (patched by finalizeHeader). */
    public static void writeHeader(RandomAccessFile raf, int sampleRate, int channels, int bitsPerSample)
            throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        raf.setLength(0);
        raf.writeBytes("RIFF");
        raf.write(intLE(0));                 // RIFF size (patched later)
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        raf.write(intLE(16));                // fmt chunk size
        raf.write(shortLE(1));               // PCM
        raf.write(shortLE(channels));
        raf.write(intLE(sampleRate));
        raf.write(intLE(byteRate));
        raf.write(shortLE(blockAlign));
        raf.write(shortLE(bitsPerSample));
        raf.writeBytes("data");
        raf.write(intLE(0));                 // data size (patched later)
    }

    /** Patch RIFF + data chunk sizes from the final file length. */
    public static void finalizeHeader(RandomAccessFile raf) throws IOException {
        long len = raf.length();
        raf.seek(4);
        raf.write(intLE((int) (len - 8)));
        raf.seek(40);
        raf.write(intLE((int) (len - 44)));
    }

    /** RMS of a PCM16LE byte buffer (amplitude units, 0..32767). */
    public static double rms(byte[] buf, int len) {
        if (len < 2) return 0;
        long sumSq = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            int lo = buf[2 * i] & 0xFF;
            int hi = buf[2 * i + 1];
            int s = (hi << 8) | lo;
            sumSq += (long) s * s;
        }
        return Math.sqrt((double) sumSq / n);
    }

    private static byte[] intLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] shortLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }
}
