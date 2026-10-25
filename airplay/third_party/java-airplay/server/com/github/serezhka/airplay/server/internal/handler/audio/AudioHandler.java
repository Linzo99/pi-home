package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * pi-airplay rewrite. Puts audio packets back in order and passes them on. The original waited
 * forever for a missing packet, so one packet lost on Wi-Fi silenced the rest of the session, and
 * the 16-bit sequence number wrapping around (after about 12 minutes) did the same. Here a missing
 * packet is waited for while at most {@link #MAX_WAIT} newer ones arrive (about 100 ms of
 * AAC-ELD), then skipped. Each packet is decrypted on its own, so skipping one is safe.
 */
@Slf4j
@RequiredArgsConstructor
public class AudioHandler extends ChannelInboundHandlerAdapter {

    private static final int WINDOW = 256;
    private static final int MAX_WAIT = 10;

    private final FairPlaySession airPlay;
    private final Playback dataConsumer;

    private final AudioPacket[] buffer = new AudioPacket[WINDOW];
    /** the next sequence number to play, or -1 before the first packet */
    private int next = -1;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        AudioPacket packet = (AudioPacket) msg;
        int seq = packet.getSequenceNumber() & 0xFFFF;
        if (next < 0) next = seq;
        int ahead = (seq - next) & 0xFFFF;
        if (ahead >= 0x8000) {
            return; // older than what's already played: a late duplicate
        }
        if (ahead >= WINDOW) {
            // far ahead: the sender restarted or a long gap; start again from here
            java.util.Arrays.fill(buffer, null);
            next = seq;
            ahead = 0;
        }
        buffer[seq % WINDOW] = packet;

        while (true) {
            AudioPacket p = buffer[next % WINDOW];
            if (p != null && (p.getSequenceNumber() & 0xFFFF) == next) {
                buffer[next % WINDOW] = null;
                play(p);
                next = (next + 1) & 0xFFFF;
                ahead--;
            } else if (ahead > MAX_WAIT) {
                // waited long enough for this one: skip it
                buffer[next % WINDOW] = null;
                next = (next + 1) & 0xFFFF;
                ahead--;
            } else {
                return;
            }
        }
    }

    private void play(AudioPacket p) throws Exception {
        int size = p.getEncodedAudioSize();
        airPlay.decryptAudio(p.getEncodedAudio(), size);
        dataConsumer.onAudio(p.getEncodedAudio(), 0, size);
    }
}
