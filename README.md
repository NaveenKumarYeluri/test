# Videos

Stream an Android phone's media library and screen to a Linux desktop.

The repository contains two applications:

* **Videos for Android** (`app/`) - a foreground HTTP server that exposes the
  phone's video library with byte-range streaming, plus a live screen mirror
  built on `MediaProjection` + `MediaCodec` (H.264).
* **Videos for Linux** (`linux_app/`) - a VLC-style GTK player that discovers
  the phone on the LAN, browses its library, streams video with full sound and
  seeking, and plays the live screen mirror.

## Quick start

### 1. Phone

Install and open the APK, allow video-library access, then tap **Start server**.
The app shows the URL, for example `http://192.168.1.20:8080`.

### 2. Linux

Requirements: Python 3.10+, GTK3/PyGObject, libVLC 3, `adb` (Android
platform-tools) for USB streaming, and `ffmpeg` for local recording.

```bash
# USB (recommended): finds the phone with `adb devices`, runs
# `adb forward tcp:18080 tcp:8080` and selects the USB MPEG-TS path
python3 linux_app/videos_linux.py

# Wi-Fi: connect explicitly
python3 linux_app/videos_linux.py --host 192.168.1.20

# or let UDP discovery (port 8099) find it
python3 linux_app/videos_linux.py
```

If USB is unavailable the player falls back to the last Wi-Fi address and then
to UDP discovery. `adb` is taken from `$PATH`; set `ADB=/path/to/adb` to use a
different copy. Leave USB debugging enabled and authorize the computer on the
phone the first time.

Double-click a video in the playlist to play it. **Tools -> Mirror phone
screen** (or the phone button in the toolbar) starts the live mirror using the
current connection (USB for a loopback/ADB connection, Wi-Fi otherwise).
**Tools -> Mirror over USB** and **Tools -> Mirror over Wi-Fi** choose the
transport explicitly and switch a running mirror when needed. The **USB**
toolbar button reconnects over ADB.

If the phone is already mirroring, the player attaches to the running stream
instead of asking for a new capture session; a stream it did not start is left
running when the player exits. A mirror started by the player, and the
`adb forward` it created, are stopped/removed on exit. The phone must approve
the screen-capture prompt once per session; on ColorOS the app also posts an
"Approve screen mirroring" notification when a background activity launch is
blocked.

## Playback paths

| Path | Transport | What it is |
| --- | --- | --- |
| Library | HTTP byte range, LAN or ADB | `/media?id=<id>` serves the original file with `206 Partial Content`, so VLC can seek and keeps the phone's full audio. |
| Live mirror (Wi-Fi) | RTP/UDP | H.264 from `MediaCodec` is packetized as RTP on port 5004; the SDP served by `/api/live/sdp` is played by libVLC. |
| Live mirror (USB) | HTTP `video/mp2t` | The same H.264 stream is muxed to MPEG-TS and served from `/api/live/stream.ts`; `adb forward` carries it over the USB cable. |

The Linux client tries USB first and chooses the USB path automatically when
the phone address is loopback (`127.0.0.1` / `localhost`); otherwise it uses
Wi-Fi. The explicit **Tools** entries override this choice.

A start request received while the phone is already streaming is treated as a
status poll: it does not change the running transport. Stop the mirror first to
switch paths.

## VLC-style interface

The Linux window follows VLC's layout: menu bar (Media, Playback, Audio, Video,
Subtitle, Tools, Help), transport toolbar, seek slider, volume slider and a
playlist panel. Controls include play/pause, stop, previous/next, fullscreen,
playlist toggle, repeat one, random, record, snapshot, audio/subtitle track
selection, playback speed, aspect ratio, zoom and deinterlace.

Keyboard shortcuts: `Space` play/pause, `S` stop, `P`/`N` previous/next,
`F` fullscreen, `M` mute, `L` repeat, `R` random, `Ctrl+M` live mirror,
`Ctrl+U` connect over USB, `Ctrl+K` connect to a LAN address, `Ctrl+R` record,
`Shift+S` snapshot, `Ctrl+Q` quit, arrow keys seek.

Recordings are written to `~/.local/share/videos/recordings/`, snapshots to
`~/Pictures/Videos/`. Live recordings are saved on the phone under
`Movies/Videos`.

## Known limitations

* **DRM content is intentionally blocked.** Netflix, Prime Video, Hotstar and
  similar apps use Android secure video layers; `MediaProjection` cannot
  capture them, so the mirror is black for those apps. Downloaded/local videos
  and non-DRM apps stream normally.
* **Live internal audio needs Android 10+.** The test device runs Android 9
  (API 28), where `AudioPlaybackCapture` does not exist. The live mirror is
  therefore video-only on that phone. Library playback keeps the original
  audio. Newer phones enable the audio path automatically when the OS allows
  it.
* Live capture is capped at a 1440 px long edge and H.264 Baseline level 4.0
  for the MediaTek encoder on the test device. The resolution and bitrate are
  reported by `/api/live/status`.
* The phone holds a dim screen wake lock while mirroring. Android stops
  producing projection frames when the display turns off, so the lock keeps
  the picture live; stopping the mirror releases it.
* Contact the same Wi-Fi network for the RTP path; UDP ports 5004/5006 must not
  be blocked by a firewall.

## HTTP API

| Endpoint | Method | Description |
| --- | --- | --- |
| `/api/info` | GET | App/device information and current live state |
| `/api/library` | GET | Video library metadata |
| `/api/thumbnail?id=` | GET | JPEG thumbnail |
| `/media?id=` | GET/HEAD | Original file with byte-range support |
| `/api/live/start?mode=usb\|wifi` | POST | Start the mirror (triggers phone approval) |
| `/api/live/status` | GET | Live state, size, transport, clients |
| `/api/live/sdp` | GET | SDP for the Wi-Fi RTP stream |
| `/api/live/stream.ts` | GET | MPEG-TS stream for the USB path |
| `/api/live/record?on=1\|0` | POST | Start/stop recording on the phone |
| `/api/live/stop` | POST | Stop the mirror |

The phone advertises itself with a UDP beacon on port **8099**.

## Build

Android (CI uses the same commands):

```bash
gradle assembleDebug
```

Linux:

```bash
python3 -m py_compile linux_app/*.py
```

CI (`.github/workflows/android.yml`) assembles the debug APK and runs the
Linux syntax checks on every push.

## License

MIT
