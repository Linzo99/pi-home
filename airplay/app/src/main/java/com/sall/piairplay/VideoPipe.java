package com.sall.piairplay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * H.264 from the sender (Annex-B, one access unit per call) to the Pi's hardware decoder, drawn
 * straight onto the mirror screen's Surface as soon as each frame is decoded.
 */
final class VideoPipe {
    private static final String TAG = "PiAirPlay";
    /** frames kept for a screen that opens late; beyond this, wait for the next keyframe */
    private static final int MAX_BACKLOG_BYTES = 24 << 20;

    interface Listener {
        /** the picture's size, for the mirror screen's aspect ratio */
        void onSize(int width, int height);
    }

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(240);
    /** latest SPS + PPS, and every frame since the last keyframe */
    private byte[] config;
    private final List<byte[]> backlog = new ArrayList<>();
    private int backlogBytes;
    private Thread thread;
    private volatile Listener listener;

    synchronized void onFrame(byte[] frame) {
        if (hasNal(frame, 7)) {
            config = frame;
            clearBacklog();
        } else {
            if (hasNal(frame, 5)) clearBacklog();
            if (backlogBytes + frame.length > MAX_BACKLOG_BYTES) {
                clearBacklog();
            } else {
                backlog.add(frame);
                backlogBytes += frame.length;
            }
        }
        if (thread != null && !queue.offer(frame)) Log.w(TAG, "video queue full, frame dropped");
    }

    /** the sender stopped: forget its stream */
    synchronized void reset() {
        config = null;
        clearBacklog();
    }

    synchronized void attach(Surface surface, Listener l) {
        detach();
        listener = l;
        queue.clear();
        if (config != null) {
            queue.offer(config);
            for (byte[] f : backlog) if (!queue.offer(f)) break;
        }
        thread = new Thread(() -> decode(surface), "video-decoder");
        thread.start();
    }

    synchronized void detach() {
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        listener = null;
    }

    private void clearBacklog() {
        backlog.clear();
        backlogBytes = 0;
    }

    private void decode(Surface surface) {
        MediaCodec codec = null;
        byte[] current = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] f = queue.poll(20, TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (codec != null) drain(codec, info);
                    continue;
                }
                if (hasNal(f, 7)) {
                    // same stream settings: keep going; new ones (size change): a fresh decoder
                    if (codec != null && Arrays.equals(f, current)) continue;
                    if (codec != null) {
                        codec.stop();
                        codec.release();
                    }
                    codec = create(f, surface);
                    current = f;
                    continue;
                }
                if (codec == null) continue;
                int in = codec.dequeueInputBuffer(50_000);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    b.clear();
                    if (f.length > b.remaining()) {
                        Log.w(TAG, "frame larger than the decoder's buffer: " + f.length);
                        codec.queueInputBuffer(in, 0, 0, 0, 0);
                    } else {
                        b.put(f);
                        codec.queueInputBuffer(in, 0, f.length, System.nanoTime() / 1000, 0);
                    }
                }
                drain(codec, info);
            }
        } catch (InterruptedException ignored) {
            // the mirror screen closed
        } catch (Exception e) {
            Log.e(TAG, "video decoder", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
        }
    }

    /** shows every decoded frame at once, without waiting for its timestamp */
    private void drain(MediaCodec codec, MediaCodec.BufferInfo info) {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out >= 0) {
                codec.releaseOutputBuffer(out, true);
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                    w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                    h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                }
                Log.i(TAG, "video " + w + "x" + h);
                Listener l = listener;
                if (l != null) l.onSize(w, h);
            } else {
                return;
            }
        }
    }

    private static MediaCodec create(byte[] config, Surface surface) throws Exception {
        int split = nextStartCode(config, 4);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
        if (split > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(Arrays.copyOfRange(config, 0, split)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(Arrays.copyOfRange(config, split, config.length)));
        } else {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 << 20);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        MediaCodec codec = decoder();
        MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        codec.configure(format, surface, null, 0);
        codec.start();
        Log.i(TAG, "video decoder " + codec.getName());
        return codec;
    }

    /** the Pi 4's hardware decoder when there is one, the software one otherwise */
    private static MediaCodec decoder() throws Exception {
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (ci.isEncoder() || !ci.isHardwareAccelerated()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) return MediaCodec.createByCodecName(ci.getName());
            }
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    /** whether the access unit contains a NAL unit of this type (5 keyframe, 7 SPS) */
    static boolean hasNal(byte[] f, int type) {
        for (int i = 0; i + 4 < f.length; ) {
            int s = nextStartCode(f, i);
            if (s < 0) return false;
            if ((f[s + 4] & 0x1f) == type) return true;
            i = s + 4;
        }
        return false;
    }

    /** index of the next 00 00 00 01 at or after from, or -1 */
    private static int nextStartCode(byte[] f, int from) {
        for (int i = from; i + 4 < f.length; i++) {
            if (f[i] == 0 && f[i + 1] == 0 && f[i + 2] == 0 && f[i + 3] == 1) return i;
        }
        return -1;
    }
}
