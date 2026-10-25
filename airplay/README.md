# Pi AirPlay

A minimal AirPlay screen-mirroring receiver for the Raspberry Pi 4 Android TV box from
[Pi Home](https://github.com/Linzo99/pi-home). No ads, no premium screen, nothing running while
unused.

On the Mac or iPhone, open **Screen Mirroring** and choose **Pi**. The TV switches to the mirrored
screen by itself. On the Mac, "Use as separate display" extends the desktop onto the TV.

- **Video**: the Pi 4's hardware H.264 decoder, drawn as soon as each frame is decoded, with no
  buffering.
- **Sound**: AAC-ELD while mirroring, ALAC for music, through Android's own decoders.
- **Wi-Fi**: power saving is off while a screen is mirrored, for less delay.
- **Remote**: Back leaves the mirrored screen. The sender keeps streaming until you stop it there.
- **Idle**: 0 % CPU, about 35 MB of memory. It starts with the Pi.

Not included: "play this video on the TV" from apps (AirPlay video/HLS), and passwords or PINs.

## Speed

The stream goes Mac → router → Pi. On a 2.4 GHz network both halves share one channel, so the
network sets the limit, not the Pi:

- connect the Pi with an Ethernet cable if you can;
- otherwise set the router to 40 MHz channel width;
- on the Mac, choose 1280 × 720 for the TV in System Settings → Displays.

The receiver asks for 1920 × 1080 at 30 fps (`AirPlayService.serve()`).

## Install

You need Android Studio (for its JDK and SDK) and the Pi on ADB.

```sh
./install.sh          # builds, installs and sets up the Pi at PI_HOST (default 192.168.1.116:5555)
```

Logs: `adb logcat -s PiAirPlay`.

## How it works

| Part | File |
|---|---|
| Announces "Pi" on the network (`_airplay._tcp`, `_raop._tcp`) through Android's mDNS; runs the control server | `AirPlayService.java` |
| Receives what the server decodes and hands it on; opens the mirror screen | `Receiver.java` |
| H.264 to the hardware decoder and the screen; keeps frames since the last keyframe for a screen that opens late | `VideoPipe.java` |
| Sound to the decoder and an `AudioTrack`; drops old packets so it never lags | `AudioPipe.java` |
| The full-screen mirror, sized to the picture | `MirrorActivity.java` |
| Starts the receiver at boot | `BootReceiver.java` |

The AirPlay protocol itself (pairing, FairPlay decryption, RTSP, the mirroring stream) is
[java-airplay](https://github.com/serezhka/java-airplay) by serezhka, MIT licence, vendored in
`third_party/java-airplay/`. `third_party/java-airplay/SOURCE` records the commit and the small
changes made for Android.

Back up `piairplay.keystore`: updates must be signed with the same key. It's left out of Git.
