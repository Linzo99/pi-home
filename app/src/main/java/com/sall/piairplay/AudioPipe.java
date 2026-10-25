package com.sall.piairplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** The sender's sound: AAC-ELD while mirroring, ALAC for music, decoded and played at once. */
final class AudioPipe {
    private static final String TAG = "PiAirPlay";
    /** about 0.3 s of AAC-ELD; older packets are dropped so the sound never lags behind */
    private static final int MAX_QUEUE = 30;
    private static final int RATE = 44100;

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private Thread thread;
    private volatile float volume = 1f;
    private volatile AudioTrack track;

    synchronized void start(AudioStreamInfo info) {
        stop();
        queue.clear();
        thread = new Thread(() -> play(info), "audio-decoder");
        thread.start();
    }

    synchronized void stop() {
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    void onAudio(byte[] packet) {
        while (!queue.offer(packet)) queue.poll();
    }

    void setVolume(float v) {
        volume = v;
        AudioTrack t = track;
        if (t != null) t.setVolume(v);
    }

    float volume() {
        return volume;
    }

    private void play(AudioStreamInfo info) {
        MediaCodec codec = null;
        AudioTrack t = null;
        try {
            AudioStreamInfo.CompressionType type = info.getCompressionType();
            Log.i(TAG, "audio " + info);
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT) * 2)
                    .build();
            t.setVolume(volume);
            t.play();
            track = t;
            if (type == AudioStreamInfo.CompressionType.LPCM) {
                playPcm(t);
                return;
            }
            codec = decoder(type);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long pts = 0;
            while (!Thread.currentThread().isInterrupted()) {
                byte[] p = queue.poll(20, TimeUnit.MILLISECONDS);
                if (p != null) {
                    int in = codec.dequeueInputBuffer(20_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        b.clear();
                        b.put(p, 0, Math.min(p.length, b.remaining()));
                        codec.queueInputBuffer(in, 0, b.position(), pts, 0);
                        pts += 10_000;
                    }
                }
                for (int out; (out = codec.dequeueOutputBuffer(bi, 0)) >= 0 || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED; ) {
                    if (out < 0) continue;
                    ByteBuffer o = codec.getOutputBuffer(out);
                    if (bi.size > 0) t.write(o, bi.size, AudioTrack.WRITE_BLOCKING);
                    codec.releaseOutputBuffer(out, false);
                }
            }
        } catch (InterruptedException ignored) {
            // the sender stopped the sound
        } catch (Exception e) {
            Log.e(TAG, "audio decoder", e);
        } finally {
            track = null;
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            if (t != null) {
                t.pause();
                t.flush();
                t.release();
            }
        }
    }

    /** uncompressed audio arrives big-endian */
    private void playPcm(AudioTrack t) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            byte[] p = queue.poll(20, TimeUnit.MILLISECONDS);
            if (p == null) continue;
            for (int i = 0; i + 1 < p.length; i += 2) {
                byte x = p[i];
                p[i] = p[i + 1];
                p[i + 1] = x;
            }
            t.write(p, 0, p.length & ~1, AudioTrack.WRITE_BLOCKING);
        }
    }

    private static MediaCodec decoder(AudioStreamInfo.CompressionType type) throws Exception {
        MediaFormat f;
        switch (type) {
            case ALAC:
                // ALAC magic cookie for 44.1 kHz, 16-bit stereo, 352 samples per packet
                f = MediaFormat.createAudioFormat("audio/alac", RATE, 2);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(HexFormat.of().parseHex(
                        "00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44")));
                break;
            case AAC:
                f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
                f.setInteger(MediaFormat.KEY_IS_ADTS, 1);
                break;
            case AAC_ELD:
                // AudioSpecificConfig: AAC-ELD, 44.1 kHz, stereo, 480 samples per frame
                f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[] {(byte) 0xf8, (byte) 0xe8, 0x50, 0x00}));
                break;
            default:
                throw new IllegalArgumentException("unsupported audio " + type);
        }
        MediaCodec c = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
        c.configure(f, null, null, 0);
        c.start();
        Log.i(TAG, "audio decoder " + c.getName());
        return c;
    }
}
