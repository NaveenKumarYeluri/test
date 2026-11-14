# Videos

Android application for mirroring Jio Hotstar (and similar OTT) content to desktop Linux machines.

## Status

- [x] Initial Android project scaffold
- [x] MediaProjection-based screen capture (MVP)
- [x] Audio capture + mux
- [x] File transfer to Linux (HTTP upload after capture)
- [ ] Linux playback UI (VLC/libVLC) - receiver implemented in `linux_app/`

## Linux companion app

The Android app POSTs the captured MP4 to a small HTTP receiver on the Linux host:

```bash
python3 linux_app/server.py --host 0.0.0.0 --port 8000
```

The server stores the file (default: `~/Videos/captures/`) and plays it back with VLC.

Point the Android app at the Linux host by setting `LINUX_SERVER_HOST` in `MainActivity.kt`.

## Build

The project targets:

- Android Gradle Plugin `8.2.2`
- Kotlin `1.9.22`
- Compile SDK `34`, Min SDK `24`, Target SDK `34`
- Java/JVM target `17`

> Note: keep builds lightweight — the development machine has RAM/storage constraints.

## License

TBD
