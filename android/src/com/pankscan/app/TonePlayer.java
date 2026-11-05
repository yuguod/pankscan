package com.pankscan.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * 提示音播放器。
 *
 * 不用 ToneGenerator：各机型对 TONE_PROP_BEEP 的时长处理不一致，加长时长在部分手机上无效。
 * 这里直接合成 PCM，音高、音长、节奏都精确可控。
 */
final class TonePlayer {

    private static final int RATE = 44100;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 成功：单声，比原来略长（190ms），音高 1318Hz，听感清脆 */
    private static final byte[] SUCCESS = burst(1318, 190, 0.55);

    /** 重复：连续三声，每声 70ms，间隔 65ms，音高 988Hz，明显区别与成功音 */
    private static final byte[] DUPLICATE = join(
            burst(988, 70, 0.55),
            silence(65),
            burst(988, 70, 0.55),
            silence(65),
            burst(988, 70, 0.55));

    private boolean enabled = true;

    void setEnabled(boolean on) {
        enabled = on;
    }

    void success() {
        play(SUCCESS);
    }

    void duplicate() {
        play(DUPLICATE);
    }

    private void play(final byte[] pcm) {
        if (!enabled || pcm == null || pcm.length == 0) return;
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();

            final AudioTrack ref = track;
            track = null; // 交给回收任务释放
            long hold = pcm.length * 1000L / (RATE * 2) + 220L;
            UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        ref.stop();
                    } catch (Throwable ignore) {
                    }
                    try {
                        ref.release();
                    } catch (Throwable ignore) {
                    }
                }
            }, hold);
        } catch (Throwable t) {
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 生成一段 16bit 单声道 PCM，带 5ms 淡入淡出避免爆音 */
    private static byte[] burst(double freq, int ms, double amp) {
        int n = RATE * ms / 1000;
        byte[] buf = new byte[n * 2];
        int fade = Math.min(n / 6, RATE / 200);
        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (fade > 0) {
                if (i < fade) env = (double) i / fade;
                else if (i > n - fade) env = (double) (n - i) / fade;
            }
            short v = (short) (Math.sin(2 * Math.PI * freq * i / RATE) * amp * env * Short.MAX_VALUE);
            buf[i * 2] = (byte) (v & 0xFF);
            buf[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return buf;
    }

    private static byte[] silence(int ms) {
        return new byte[RATE * ms / 1000 * 2];
    }

    private static byte[] join(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
