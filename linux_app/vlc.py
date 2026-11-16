"""Small ctypes wrapper around libVLC 3.x.

python-vlc is not installable on this machine (PEP 668 / no packages), so the
handful of libVLC entry points the player needs are declared here directly.
Only the stable libVLC 3.x C API is used.
"""

from __future__ import annotations

import ctypes
import ctypes.util
import os
import sys


class TrackDescription(ctypes.Structure):
    pass


TrackDescription._fields_ = [
    ("i_id", ctypes.c_int),
    ("psz_name", ctypes.c_char_p),
    ("p_next", ctypes.POINTER(TrackDescription)),
]


STATE_NAMES = {
    0: "Nothing special",
    1: "Opening",
    2: "Buffering",
    3: "Playing",
    4: "Paused",
    5: "Stopped",
    6: "Ended",
    7: "Error",
}


def _library_path() -> str:
    candidates = ["libvlc.so.5", "libvlc.so", ctypes.util.find_library("vlc")]
    for candidate in candidates:
        if not candidate:
            continue
        if os.path.isabs(candidate):
            if os.path.exists(candidate):
                return candidate
        else:
            try:
                ctypes.CDLL(candidate)
                return candidate
            except OSError:
                continue
    raise OSError("libVLC was not found. Install VLC (libvlc) first.")


class LibVLC:
    STATE_NOTHING_SPECIAL = 0
    STATE_OPENING = 1
    STATE_BUFFERING = 2
    STATE_PLAYING = 3
    STATE_PAUSED = 4
    STATE_STOPPED = 5
    STATE_ENDED = 6
    STATE_ERROR = 7

    def __init__(self, args=None):
        self._lib = ctypes.CDLL(_library_path())
        self._args = list(args or [])
        self._bind()
        encoded = [arg.encode("utf-8") for arg in self._args]
        self._argv = (ctypes.c_char_p * len(encoded))(*encoded) if encoded else None
        self._instance = self._lib.libvlc_new(len(encoded), self._argv)
        if not self._instance:
            raise RuntimeError("libvlc_new() failed")
        self._released = False

    # ------------------------------------------------------------------ setup
    def _fn(self, name, restype, argtypes):
        fn = getattr(self._lib, name)
        fn.restype = restype
        fn.argtypes = argtypes
        return fn

    def _bind(self):
        lib = self._lib
        self.libvlc_new = lib.libvlc_new
        self.libvlc_new.restype = ctypes.c_void_p
        self.libvlc_new.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]

        self.libvlc_release = lib.libvlc_release
        self.libvlc_release.argtypes = [ctypes.c_void_p]
        self.libvlc_release.restype = None

        self.libvlc_errmsg = lib.libvlc_errmsg
        self.libvlc_errmsg.restype = ctypes.c_char_p
        self.libvlc_errmsg.argtypes = []

        self.media_new_location = lib.libvlc_media_new_location
        self.media_new_location.restype = ctypes.c_void_p
        self.media_new_location.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

        self.media_new_path = lib.libvlc_media_new_path
        self.media_new_path.restype = ctypes.c_void_p
        self.media_new_path.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

        self.media_add_option = lib.libvlc_media_add_option
        self.media_add_option.restype = None
        self.media_add_option.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

        self.media_release = lib.libvlc_media_release
        self.media_release.restype = None
        self.media_release.argtypes = [ctypes.c_void_p]

        self.media_get_duration = lib.libvlc_media_get_duration
        self.media_get_duration.restype = ctypes.c_longlong
        self.media_get_duration.argtypes = [ctypes.c_void_p]

        self.player_new = lib.libvlc_media_player_new
        self.player_new.restype = ctypes.c_void_p
        self.player_new.argtypes = [ctypes.c_void_p]

        self.player_release = lib.libvlc_media_player_release
        self.player_release.restype = None
        self.player_release.argtypes = [ctypes.c_void_p]

        self.player_set_media = lib.libvlc_media_player_set_media
        self.player_set_media.restype = None
        self.player_set_media.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

        self.player_set_xwindow = lib.libvlc_media_player_set_xwindow
        self.player_set_xwindow.restype = None
        self.player_set_xwindow.argtypes = [ctypes.c_void_p, ctypes.c_uint32]

        self.player_play = lib.libvlc_media_player_play
        self.player_play.restype = ctypes.c_int
        self.player_play.argtypes = [ctypes.c_void_p]

        self.player_stop = lib.libvlc_media_player_stop
        self.player_stop.restype = None
        self.player_stop.argtypes = [ctypes.c_void_p]

        self.player_pause = lib.libvlc_media_player_pause
        self.player_pause.restype = None
        self.player_pause.argtypes = [ctypes.c_void_p]

        self.player_set_pause = lib.libvlc_media_player_set_pause
        self.player_set_pause.restype = None
        self.player_set_pause.argtypes = [ctypes.c_void_p, ctypes.c_int]

        self.player_is_playing = lib.libvlc_media_player_is_playing
        self.player_is_playing.restype = ctypes.c_int
        self.player_is_playing.argtypes = [ctypes.c_void_p]

        self.player_get_state = lib.libvlc_media_player_get_state
        self.player_get_state.restype = ctypes.c_int
        self.player_get_state.argtypes = [ctypes.c_void_p]

        self.player_get_time = lib.libvlc_media_player_get_time
        self.player_get_time.restype = ctypes.c_longlong
        self.player_get_time.argtypes = [ctypes.c_void_p]

        self.player_set_time = lib.libvlc_media_player_set_time
        self.player_set_time.restype = None
        self.player_set_time.argtypes = [ctypes.c_void_p, ctypes.c_longlong]

        self.player_get_length = lib.libvlc_media_player_get_length
        self.player_get_length.restype = ctypes.c_longlong
        self.player_get_length.argtypes = [ctypes.c_void_p]

        self.player_get_position = lib.libvlc_media_player_get_position
        self.player_get_position.restype = ctypes.c_float
        self.player_get_position.argtypes = [ctypes.c_void_p]

        self.player_set_position = lib.libvlc_media_player_set_position
        self.player_set_position.restype = None
        self.player_set_position.argtypes = [ctypes.c_void_p, ctypes.c_float]

        self.player_set_rate = lib.libvlc_media_player_set_rate
        self.player_set_rate.restype = ctypes.c_int
        self.player_set_rate.argtypes = [ctypes.c_void_p, ctypes.c_float]

        self.player_get_rate = lib.libvlc_media_player_get_rate
        self.player_get_rate.restype = ctypes.c_float
        self.player_get_rate.argtypes = [ctypes.c_void_p]

        self.player_will_play = lib.libvlc_media_player_will_play
        self.player_will_play.restype = ctypes.c_int
        self.player_will_play.argtypes = [ctypes.c_void_p]

        self.player_get_fps = lib.libvlc_media_player_get_fps
        self.player_get_fps.restype = ctypes.c_float
        self.player_get_fps.argtypes = [ctypes.c_void_p]

        self.player_has_vout = lib.libvlc_media_player_has_vout
        self.player_has_vout.restype = ctypes.c_int
        self.player_has_vout.argtypes = [ctypes.c_void_p]

        self.video_get_size = lib.libvlc_video_get_size
        self.video_get_size.restype = ctypes.c_int
        self.video_get_size.argtypes = [
            ctypes.c_void_p,
            ctypes.c_uint,
            ctypes.POINTER(ctypes.c_uint),
            ctypes.POINTER(ctypes.c_uint),
        ]

        self.video_take_snapshot = lib.libvlc_video_take_snapshot
        self.video_take_snapshot.restype = ctypes.c_int
        self.video_take_snapshot.argtypes = [
            ctypes.c_void_p,
            ctypes.c_uint,
            ctypes.c_char_p,
            ctypes.c_uint,
            ctypes.c_uint,
        ]

        self.video_set_aspect_ratio = lib.libvlc_video_set_aspect_ratio
        self.video_set_aspect_ratio.restype = ctypes.c_int
        self.video_set_aspect_ratio.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

        self.video_set_scale = lib.libvlc_video_set_scale
        self.video_set_scale.restype = ctypes.c_int
        self.video_set_scale.argtypes = [ctypes.c_void_p, ctypes.c_float]

        self.video_set_deinterlace = lib.libvlc_video_set_deinterlace
        self.video_set_deinterlace.restype = ctypes.c_int
        self.video_set_deinterlace.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

        self.video_get_track_count = lib.libvlc_video_get_track_count
        self.video_get_track_count.restype = ctypes.c_int
        self.video_get_track_count.argtypes = [ctypes.c_void_p]

        self.video_get_track_description = lib.libvlc_video_get_track_description
        self.video_get_track_description.restype = ctypes.POINTER(TrackDescription)
        self.video_get_track_description.argtypes = [ctypes.c_void_p]

        self.video_get_track = lib.libvlc_video_get_track
        self.video_get_track.restype = ctypes.c_int
        self.video_get_track.argtypes = [ctypes.c_void_p]

        self.video_set_track = lib.libvlc_video_set_track
        self.video_set_track.restype = ctypes.c_int
        self.video_set_track.argtypes = [ctypes.c_void_p, ctypes.c_int]

        self.audio_get_volume = lib.libvlc_audio_get_volume
        self.audio_get_volume.restype = ctypes.c_int
        self.audio_get_volume.argtypes = [ctypes.c_void_p]

        self.audio_set_volume = lib.libvlc_audio_set_volume
        self.audio_set_volume.restype = ctypes.c_int
        self.audio_set_volume.argtypes = [ctypes.c_void_p, ctypes.c_int]

        self.audio_get_mute = lib.libvlc_audio_get_mute
        self.audio_get_mute.restype = ctypes.c_int
        self.audio_get_mute.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]

        self.audio_set_mute = lib.libvlc_audio_set_mute
        self.audio_set_mute.restype = None
        self.audio_set_mute.argtypes = [ctypes.c_void_p, ctypes.c_int]

        self.audio_get_track_count = lib.libvlc_audio_get_track_count
        self.audio_get_track_count.restype = ctypes.c_int
        self.audio_get_track_count.argtypes = [ctypes.c_void_p]

        self.audio_get_track_description = lib.libvlc_audio_get_track_description
        self.audio_get_track_description.restype = ctypes.POINTER(TrackDescription)
        self.audio_get_track_description.argtypes = [ctypes.c_void_p]

        self.audio_get_track = lib.libvlc_audio_get_track
        self.audio_get_track.restype = ctypes.c_int
        self.audio_get_track.argtypes = [ctypes.c_void_p]

        self.audio_set_track = lib.libvlc_audio_set_track
        self.audio_set_track.restype = ctypes.c_int
        self.audio_set_track.argtypes = [ctypes.c_void_p, ctypes.c_int]

        self.video_get_spu_count = lib.libvlc_video_get_spu_count
        self.video_get_spu_count.restype = ctypes.c_int
        self.video_get_spu_count.argtypes = [ctypes.c_void_p]

        self.video_get_spu_description = lib.libvlc_video_get_spu_description
        self.video_get_spu_description.restype = ctypes.POINTER(TrackDescription)
        self.video_get_spu_description.argtypes = [ctypes.c_void_p]

        self.video_get_spu = lib.libvlc_video_get_spu
        self.video_get_spu.restype = ctypes.c_int
        self.video_get_spu.argtypes = [ctypes.c_void_p]

        self.video_set_spu = lib.libvlc_video_set_spu
        self.video_set_spu.restype = ctypes.c_int
        self.video_set_spu.argtypes = [ctypes.c_void_p, ctypes.c_int]

        self.video_add_slave = lib.libvlc_media_player_add_slave
        self.video_add_slave.restype = ctypes.c_int
        self.video_add_slave.argtypes = [
            ctypes.c_void_p,
            ctypes.c_int,  # libvlc_media_slave_type_t
            ctypes.c_char_p,
            ctypes.c_bool,
        ]

        self.track_release = lib.libvlc_track_description_list_release
        self.track_release.restype = None
        self.track_release.argtypes = [ctypes.POINTER(TrackDescription)]

    # -------------------------------------------------------------- lifetime
    @property
    def instance(self):
        return self._instance

    @property
    def last_error(self):
        message = self.libvlc_errmsg()
        return message.decode("utf-8", "replace") if message else ""

    def media(self, mrl=None, path=None, options=()):
        if path is not None:
            handle = self.media_new_path(self._instance, path.encode("utf-8"))
        else:
            handle = self.media_new_location(
                self._instance, str(mrl).encode("utf-8")
            )
        if not handle:
            raise RuntimeError(self.last_error or "could not create media")
        for option in options:
            self.media_add_option(handle, str(option).encode("utf-8"))
        return Media(self, handle)

    def new_player(self):
        handle = self.player_new(self._instance)
        if not handle:
            raise RuntimeError(self.last_error or "could not create player")
        return Player(self, handle)

    def release(self):
        if self._released:
            return
        self._released = True
        self.libvlc_release(self._instance)
        self._instance = None


class Media:
    def __init__(self, lib: LibVLC, handle):
        self._lib = lib
        self._handle = handle
        self._released = False

    @property
    def handle(self):
        return self._handle

    @property
    def duration(self) -> int:
        return int(self._lib.media_get_duration(self._handle))

    def add_option(self, option: str):
        self._lib.media_add_option(self._handle, str(option).encode("utf-8"))

    def release(self):
        if self._released:
            return
        self._released = True
        self._lib.media_release(self._handle)


class Player:
    def __init__(self, lib: LibVLC, handle):
        self._lib = lib
        self._handle = handle
        self._released = False

    # ------------------------------------------------------------- transport
    def set_media(self, media: Media):
        self._lib.player_set_media(self._handle, media.handle)

    def play(self, media: Media | None = None) -> bool:
        if media is not None:
            self.set_media(media)
        return self._lib.player_play(self._handle) == 0

    def stop(self):
        self._lib.player_stop(self._handle)

    def pause(self):
        self._lib.player_pause(self._handle)

    def set_pause(self, paused: bool):
        self._lib.player_set_pause(self._handle, 1 if paused else 0)

    def is_playing(self) -> bool:
        return bool(self._lib.player_is_playing(self._handle))

    def state(self) -> int:
        return int(self._lib.player_get_state(self._handle))

    def state_name(self) -> str:
        return STATE_NAMES.get(self.state(), "Unknown")

    def get_time(self) -> int:
        return max(0, int(self._lib.player_get_time(self._handle)))

    def set_time(self, milliseconds: int):
        self._lib.player_set_time(self._handle, int(milliseconds))

    def get_length(self) -> int:
        return max(0, int(self._lib.player_get_length(self._handle)))

    def get_position(self) -> float:
        return float(self._lib.player_get_position(self._handle))

    def set_position(self, fraction: float):
        self._lib.player_set_position(
            self._handle, min(1.0, max(0.0, float(fraction)))
        )

    def set_rate(self, rate: float):
        return self._lib.player_set_rate(self._handle, float(rate)) == 0

    def get_rate(self) -> float:
        return float(self._lib.player_get_rate(self._handle))

    def will_play(self) -> bool:
        return bool(self._lib.player_will_play(self._handle))

    def get_fps(self) -> float:
        return float(self._lib.player_get_fps(self._handle))

    def has_vout(self) -> bool:
        return bool(self._lib.player_has_vout(self._handle))

    def video_size(self):
        width = ctypes.c_uint()
        height = ctypes.c_uint()
        ok = self._lib.video_get_size(
            self._handle, 0, ctypes.byref(width), ctypes.byref(height)
        )
        return (int(width.value), int(height.value)) if ok == 0 else (0, 0)

    # ---------------------------------------------------------------- window
    def set_xwindow(self, xid: int):
        self._lib.player_set_xwindow(self._handle, int(xid))

    # --------------------------------------------------------------- display
    def snapshot(self, path: str) -> bool:
        return (
            self._lib.video_take_snapshot(
                self._handle, 0, path.encode("utf-8"), 0, 0
            )
            == 0
        )

    def set_aspect_ratio(self, ratio):
        value = None if ratio in (None, "", "default", "Default") else str(ratio)
        return (
            self._lib.video_set_aspect_ratio(
                self._handle, value.encode("utf-8") if value else None
            )
            == 0
        )

    def set_scale(self, scale):
        value = 0.0 if scale in (None, "", "default", "Default") else float(scale)
        return self._lib.video_set_scale(self._handle, value) == 0

    def set_deinterlace(self, mode):
        value = None if mode in (None, "", "off", "Off") else str(mode)
        return (
            self._lib.video_set_deinterlace(
                self._handle, value.encode("utf-8") if value else None
            )
            == 0
        )

    # ---------------------------------------------------------------- tracks
    def _tracks(self, getter):
        head = getter(self._handle)
        result = []
        if not head:
            return result
        try:
            node = head
            while node:
                item = node.contents
                name = item.psz_name.decode("utf-8", "replace") if item.psz_name else ""
                result.append((int(item.i_id), name))
                node = item.p_next
        finally:
            try:
                self._lib.track_release(head)
            except Exception:
                pass
        return result

    def audio_tracks(self):
        return self._tracks(self._lib.audio_get_track_description)

    def video_tracks(self):
        return self._tracks(self._lib.video_get_track_description)

    def subtitle_tracks(self):
        return self._tracks(self._lib.video_get_spu_description)

    def set_audio_track(self, track_id: int):
        self._lib.audio_set_track(self._handle, int(track_id))

    def set_video_track(self, track_id: int):
        self._lib.video_set_track(self._handle, int(track_id))

    def set_subtitle_track(self, track_id: int):
        self._lib.video_set_spu(self._handle, int(track_id))

    def current_audio_track(self) -> int:
        return int(self._lib.audio_get_track(self._handle))

    def current_video_track(self) -> int:
        return int(self._lib.video_get_track(self._handle))

    def current_subtitle_track(self) -> int:
        return int(self._lib.video_get_spu(self._handle))

    def add_subtitle(self, path: str) -> bool:
        uri = path
        if not path.startswith(("file:", "http:", "https:")):
            uri = "file://" + path
        # libvlc_media_slave_type_subtitle == 0
        return (
            self._lib.video_add_slave(
                self._handle, 0, uri.encode("utf-8"), True
            )
            == 0
        )

    # ---------------------------------------------------------------- volume
    def get_volume(self) -> int:
        return int(self._lib.audio_get_volume(self._handle))

    def set_volume(self, volume: int):
        return self._lib.audio_set_volume(
            self._handle, max(0, min(200, int(volume)))
        ) == 0

    def get_mute(self) -> bool:
        value = ctypes.c_int(0)
        if self._lib.audio_get_mute(self._handle, ctypes.byref(value)) == 0:
            return bool(value.value)
        return False

    def set_mute(self, mute: bool):
        self._lib.audio_set_mute(self._handle, 1 if mute else 0)

    # -------------------------------------------------------------- lifetime
    def release(self):
        if self._released:
            return
        self._released = True
        self._lib.player_release(self._handle)

    def __del__(self):
        try:
            self.release()
        except Exception:
            pass


def version() -> str:
    try:
        lib = ctypes.CDLL(_library_path())
        lib.libvlc_get_version.restype = ctypes.c_char_p
        return lib.libvlc_get_version().decode("utf-8", "replace")
    except Exception:
        return "unknown"


if __name__ == "__main__":
    print("libVLC", version())
    vlc = LibVLC(["--intf=dummy", "--quiet"])
    print("instance ok")
    vlc.release()
    sys.exit(0)
