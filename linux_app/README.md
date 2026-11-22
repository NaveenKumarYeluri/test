# Videos for Linux

A VLC-style desktop player for the Videos Android app.

## Requirements

* Python 3.10+
* GTK3 with PyGObject (`python3-gi`)
* libVLC 3 (`libvlc.so.5`) and the `vlc` package
* `adb` from Android platform-tools for USB streaming (honours `$ADB`)
* `ffmpeg` for recording the currently playing library video

The player embeds libVLC directly through `ctypes` (`vlc.py`), so `python-vlc`
is not required. On Wayland sessions the app forces `GDK_BACKEND=x11` because
libVLC renders into an X11 window id.

## Run

```bash
# USB first: find the phone with adb, forward port 18080, then play
# (falls back to the last LAN host and finally UDP discovery)
python3 videos_linux.py

# connect directly over Wi-Fi
python3 videos_linux.py --host 192.168.1.20

# manual forward (USB); the player also does this by itself
adb forward tcp:18080 tcp:8080
python3 videos_linux.py --host 127.0.0.1:18080
```

USB needs USB debugging enabled on the phone and the computer authorized once.
The player runs `adb forward tcp:18080 tcp:8080` on startup, so port 18080 must
be free; the forward is removed when the player exits.

Useful options:

* `--self-test` - connect, load the library, play one item and test the live
  mirror, then exit with status 0/1 (needs a display).
* `--screenshot FILE` - save a PNG of the window after startup (for headless
  verification).

## Using the player

* The playlist panel lists the phone library. Double-click an item to play.
* The **USB** toolbar button reconnects over ADB; **Connect** takes a LAN
  address. On startup the player tries USB first, then the last LAN host, then
  UDP discovery.
* `Tools -> Mirror phone screen` starts the live screen mirror and attaches the
  stream to the video area, using USB when connected over ADB and Wi-Fi
  otherwise. Approve the screen-capture prompt on the phone.
* `Tools -> Mirror over USB` / `Tools -> Mirror over Wi-Fi` select the transport
  explicitly; if the phone is already mirroring over the other transport, the
  player stops it and restarts the mirror as requested.
* If the phone is already mirroring, the app attaches to that stream; a mirror
  started elsewhere keeps running when the player closes. A mirror started by
  the player is stopped on exit.
* Library playback uses HTTP byte ranges, so the seek bar works and the phone's
  sound is preserved.
* The toolbar and menus mirror VLC's controls: play/pause, stop, previous,
  next, fullscreen, playlist, repeat, random, record, audio/subtitle tracks,
  speed, aspect ratio, zoom and deinterlace.

| Shortcut | Action |
| --- | --- |
| `Space` | Play / pause |
| `S` | Stop |
| `P` / `N` | Previous / next |
| `F` | Fullscreen |
| `M` | Mute |
| `L` | Repeat one |
| `R` | Random |
| `Ctrl+M` | Live mirror |
| `Ctrl+U` | Connect over USB |
| `Ctrl+K` | Connect to a LAN address |
| `Ctrl+R` | Record |
| `Shift+S` | Snapshot |
| Left / Right | Seek 10 s (60 s with Shift) |
| `Ctrl+Q` | Quit |

## How live playback works

* **USB (default):** `adb forward` is TCP-only, so the phone muxes the same
  H.264 frames to MPEG-TS and serves `/api/live/stream.ts`. The app opens that
  URL through the local forward with libVLC's FFmpeg demuxer
  (`:demux=avformat`). This is the recommended path because it does not depend
  on the Wi-Fi network or on inbound UDP.
* **Wi-Fi:** the phone sends H.264 RTP to the laptop; the app fetches
  `/api/live/sdp`, writes it to `~/.cache/videos/live.sdp` and opens it with
  libVLC using the FFmpeg SDP demux.
* The mirror follows the phone's rotation. Turning the phone rebuilds the
  encoder at the new orientation, so landscape video fills the frame instead
  of appearing letterboxed inside a portrait strip. A live recording stops if
  the screen rotates.
* The live stream is decoded in software. The phone emits plain Baseline H.264
  without constraint flags, while Intel VA-API only exposes Constrained
  Baseline. Hardware setup therefore failed and libVLC fell back to VDPAU
  (Nvidia-only), which produced the harmless "Failed to open VDPAU backend"
  message. Software decoding avoids both probes and uses only a few percent of
  one CPU core at 720p.

Snapshots are saved to `~/Pictures/Videos`; recordings of library playback to
`~/.local/share/videos/recordings`.

## Notes

* DRM-protected apps cannot be mirrored: Android blocks capture of secure
  video layers. Local videos and non-DRM apps work.
* Internal audio during the live mirror requires Android 10+. On Android 9 the
  mirror is video-only; library playback keeps full audio.
* If the phone does not show the consent prompt when the mirror is started
  from the desktop, open the "Approve screen mirroring" notification on the
  phone and tap it.
