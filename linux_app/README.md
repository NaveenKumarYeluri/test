# Videos Linux Companion

Receives screen captures from the Android `Videos` app and plays them back via VLC.

## Run

```bash
python3 server.py --host 0.0.0.0 --port 8000
```

## Endpoints

- `GET /health` -> `{"status": "ok"}` — liveness probe
- `POST /upload?name=<filename>` -> raw body is saved and played
- `POST /stop` -> stop current playback

## Notes

- Playback is delegated to the VLC desktop binary via `vlc --play-and-exit`, so VLC
  exits by itself once the capture finishes.
- Captures are stored in the capture directory (default: `~/Videos/captures/`).
