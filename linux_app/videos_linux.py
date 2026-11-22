#!/usr/bin/env python3
"""Videos - a VLC-style desktop player for the Videos phone streaming server.

The application mirrors VLC's layout and controls:

* menu bar (Media, Playback, Audio, Video, Subtitle, Tools, Help)
* transport toolbar with playlist, previous, play/pause, stop, next,
  fullscreen, record, audio/subtitle track pickers and speed control
* seek slider with elapsed/total time, volume slider with mute
* playlist panel listing the phone library
* phone discovery over UDP (port 8099) and manual address entry
* live screen mirroring (MediaProjection -> RTP -> libVLC)
* snapshots, local recording via ffmpeg stream copy and live recording

Run it with ``python3 videos_linux.py``.  ``--self-test`` performs an
automated connectivity/library/playback check and ``--screenshot FILE``
saves a screenshot of the window (handy for headless verification).
"""

from __future__ import annotations

import argparse
import json
import os
import random
import signal
import subprocess
import sys
import threading
import time
import uuid

# libVLC renders into an X11 window id, so make sure GTK uses X11 even when
# the session runs on Wayland.
if (
    sys.platform.startswith("linux")
    and os.environ.get("DISPLAY")
    and "GDK_BACKEND" not in os.environ
):
    os.environ["GDK_BACKEND"] = "x11"

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("Pango", "1.0")
from gi.repository import Gdk, GLib, Gtk, Pango  # noqa: E402

import vlc as vlc_wrap  # noqa: E402
from phone_client import (  # noqa: E402
    DEFAULT_USB_PORT,
    PhoneClient,
    PhoneError,
    adb_remove_forward,
    connect_usb,
    discover_phones,
    format_duration,
    format_size,
)

APP_NAME = "Videos"
APP_ID = "com.naveen.videos.linux"
CONFIG_DIR = os.path.expanduser("~/.config/videos")
CACHE_DIR = os.path.expanduser("~/.cache/videos")
SETTINGS_FILE = os.path.join(CONFIG_DIR, "settings.json")
SNAPSHOT_DIR = os.path.expanduser("~/Pictures/Videos")
RECORD_DIR = os.path.expanduser("~/.local/share/videos/recordings")

LIVE_VIDEO_PORT = 5004
LIVE_AUDIO_PORT = 5006
LIVE_BITRATE = 6_000_000
USB_FORWARD_PORT = DEFAULT_USB_PORT

SPEEDS = (0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0)


def ensure_dirs():
    for directory in (CONFIG_DIR, CACHE_DIR, SNAPSHOT_DIR, RECORD_DIR):
        try:
            os.makedirs(directory, exist_ok=True)
        except OSError:
            pass


def load_settings() -> dict:
    try:
        with open(SETTINGS_FILE, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def save_settings(settings: dict):
    ensure_dirs()
    try:
        with open(SETTINGS_FILE, "w", encoding="utf-8") as handle:
            json.dump(settings, handle, indent=2)
    except OSError:
        pass


def live_sdp_path() -> str:
    ensure_dirs()
    return os.path.join(CACHE_DIR, "live.sdp")


def timestamped(folder: str, prefix: str, suffix: str) -> str:
    ensure_dirs()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return os.path.join(folder, f"{prefix}-{stamp}{suffix}")


class VideosWindow(Gtk.Window):
    def __init__(self, args):
        super().__init__(title=APP_NAME)
        self.args = args
        self.self_test = bool(args.self_test)
        self.screenshot_path = args.screenshot
        self.auto_quit_at = None
        self.auto_play_done = False

        self.settings = load_settings()
        self.client: PhoneClient | None = None
        self.phone_info: dict = {}
        self.library: list[dict] = []

        self.libvlc = vlc_wrap.LibVLC(
            [
                "--intf=dummy",
                "--no-video-title-show",
                "--network-caching=1500",
                "--file-caching=1500",
                "--no-snapshot-preview",
                "--quiet",
            ]
        )
        self.player = self.libvlc.new_player()
        self.media = None
        self.current: dict | None = None
        self.current_is_live = False
        self.started_live = False
        self.live_owned = False
        self.live_sdp_file: str | None = None
        self.live_mode: str | None = None
        self.live_control_client: PhoneClient | None = None
        self.adb_forward_port: int | None = None
        self.adb_forward_owned = False
        self.record_proc: subprocess.Popen | None = None
        self.record_path: str | None = None
        self.recording_live = False
        self.loop_enabled = False
        self.shuffle_enabled = False
        self.is_fullscreen = False
        self.seeking = False
        self.volume = int(self.settings.get("volume", 100))
        self.muted = False
        self.speed = 1.0
        self.last_state = -1
        self.xid = None
        self.playlist_visible = True
        self._syncing_playlist = False
        self._playlist_before_fullscreen = True
        self._http_idle = False
        self._status_reset_source = None

        self.set_default_size(1180, 720)
        self.set_size_request(860, 520)
        self.set_icon_name("vlc")
        self.connect("destroy", self.on_destroy)
        self.connect("key-press-event", self.on_key_press)
        self.connect("window-state-event", self.on_window_state)

        self.build_ui()
        self.apply_settings()
        GLib.timeout_add(250, self.on_tick)

        if not args.host:
            GLib.timeout_add(300, self.auto_connect)

        if self.self_test:
            self.auto_quit_at = time.time() + 70.0
        if self.screenshot_path:
            GLib.timeout_add(1800, self.take_ui_screenshot)

    # ------------------------------------------------------------------- UI
    def build_ui(self):
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.add(root)

        self.accel_group = Gtk.AccelGroup()
        self.add_accel_group(self.accel_group)

        self.menubar = self.build_menubar()
        root.pack_start(self.menubar, False, False, 0)

        self.connection_bar = self.build_connection_bar()
        root.pack_start(self.connection_bar, False, False, 0)

        self.paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL)
        root.pack_start(self.paned, True, True, 0)

        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.paned.pack1(left, resize=True, shrink=True)

        video_frame = Gtk.Frame()
        video_frame.set_shadow_type(Gtk.ShadowType.IN)
        self.video_area = Gtk.DrawingArea()
        self.video_area.set_double_buffered(False)
        self.video_area.set_size_request(480, 270)
        self.video_area.add_events(
            Gdk.EventMask.BUTTON_PRESS_MASK | Gdk.EventMask.POINTER_MOTION_MASK
        )
        self.video_area.connect("realize", self.on_video_realize)
        self.video_area.connect("button-press-event", self.on_video_button)
        video_frame.add(self.video_area)
        left.pack_start(video_frame, True, True, 0)
        self.video_frame = video_frame

        self.seek_row = self.build_seek_row()
        left.pack_start(self.seek_row, False, False, 0)

        self.transport = self.build_transport()
        left.pack_start(self.transport, False, False, 0)

        self.playlist_panel = self.build_playlist_panel()
        self.paned.pack2(self.playlist_panel, resize=False, shrink=False)

        self.statusbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.statusbar.set_border_width(4)
        self.connection_label = Gtk.Label(label="Not connected")
        self.connection_label.set_alignment(0.0, 0.5)
        self.connection_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.status_label = Gtk.Label(label="Ready")
        self.status_label.set_alignment(1.0, 0.5)
        self.status_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.statusbar.pack_start(self.connection_label, False, False, 0)
        self.statusbar.pack_end(self.status_label, True, True, 0)
        root.pack_start(self.statusbar, False, False, 0)

    def build_menubar(self) -> Gtk.MenuBar:
        menubar = Gtk.MenuBar()

        media = Gtk.Menu()
        self.add_menu_item(media, "Open phone library", self.on_open_library, "<Control>l")
        self.add_menu_item(media, "Open media file...", self.on_open_file, "<Control>o")
        self.add_menu_item(media, "Open network stream...", self.on_open_network, "<Control>n")
        media.append(Gtk.SeparatorMenuItem())
        self.add_menu_item(media, "Refresh library", self.on_refresh_library, "F5")
        self.add_menu_item(media, "Connect over USB", self.on_usb_connect, "<Control>u")
        self.add_menu_item(media, "Connect to phone...", self.on_connect_dialog, "<Control>k")
        media.append(Gtk.SeparatorMenuItem())
        self.add_menu_item(media, "Quit", self.on_quit, "<Control>q")
        menubar.append(self.submenu_item("Media", media))

        playback = Gtk.Menu()
        self.add_menu_item(playback, "Play / Pause", self.on_play_pause, "space")
        self.add_menu_item(playback, "Stop", self.on_stop, "s")
        self.add_menu_item(playback, "Previous", self.on_previous, "p")
        self.add_menu_item(playback, "Next", self.on_next, "n")
        playback.append(Gtk.SeparatorMenuItem())
        self.add_menu_item(playback, "Faster", lambda *_: self.change_speed(1.25), "<Control>plus")
        self.add_menu_item(playback, "Slower", lambda *_: self.change_speed(0.8), "<Control>minus")
        self.add_menu_item(playback, "Normal speed", lambda *_: self.set_speed(1.0), "equal")
        playback.append(Gtk.SeparatorMenuItem())
        self.playlist_check = self.add_check_item(
            playback, "Show playlist", True, self.on_toggle_playlist
        )
        self.loop_check = self.add_check_item(playback, "Repeat one", False, self.on_toggle_loop)
        self.random_check = self.add_check_item(playback, "Random", False, self.on_toggle_shuffle)
        self.add_menu_item(playback, "Fullscreen", self.on_toggle_fullscreen, "f")
        menubar.append(self.submenu_item("Playback", playback))

        audio = Gtk.Menu()
        self.mute_check = self.add_check_item(audio, "Mute", False, self.on_toggle_mute)
        self.add_menu_item(audio, "Volume up", lambda *_: self.bump_volume(5), "<Control>Up")
        self.add_menu_item(audio, "Volume down", lambda *_: self.bump_volume(-5), "<Control>Down")
        audio.append(Gtk.SeparatorMenuItem())
        self.audio_menu = Gtk.Menu()
        self.audio_menu.connect("show", lambda *_: self.populate_track_menu(self.audio_menu, "audio"))
        audio.append(self.submenu_item("Audio track", self.audio_menu))
        menubar.append(self.submenu_item("Audio", audio))

        video = Gtk.Menu()
        self.add_menu_item(video, "Fullscreen", self.on_toggle_fullscreen, "f")
        self.add_menu_item(video, "Take snapshot", self.on_snapshot, "<Shift>s")
        self.add_menu_item(video, "Record", self.on_record, "<Control>r")
        video.append(Gtk.SeparatorMenuItem())
        aspect = Gtk.Menu()
        for label, value in (
            ("Default", None),
            ("16:9", "16:9"),
            ("4:3", "4:3"),
            ("1:1", "1:1"),
            ("21:9", "21:9"),
            ("2.35:1", "2.35:1"),
        ):
            self.add_menu_item(aspect, label, lambda _w, v=value: self.set_aspect(v))
        video.append(self.submenu_item("Aspect ratio", aspect))
        zoom = Gtk.Menu()
        for label, value in (
            ("Default", None),
            ("Quarter", 0.25),
            ("Half", 0.5),
            ("Original", 1.0),
            ("Double", 2.0),
        ):
            self.add_menu_item(zoom, label, lambda _w, v=value: self.set_zoom(v))
        video.append(self.submenu_item("Zoom", zoom))
        deinterlace = Gtk.Menu()
        for label, value in (
            ("Off", "off"),
            ("Automatic", "auto"),
            ("Blend", "blend"),
            ("Linear", "linear"),
            ("X", "x"),
            ("Yadif", "yadif"),
            ("Yadif 2x", "yadif2x"),
        ):
            self.add_menu_item(deinterlace, label, lambda _w, v=value: self.set_deinterlace(v))
        video.append(self.submenu_item("Deinterlace", deinterlace))
        menubar.append(self.submenu_item("Video", video))

        subtitle = Gtk.Menu()
        self.add_menu_item(subtitle, "Add subtitle file...", self.on_add_subtitle, "<Control><Shift>s")
        subtitle.append(Gtk.SeparatorMenuItem())
        self.subtitle_menu = Gtk.Menu()
        self.subtitle_menu.connect(
            "show", lambda *_: self.populate_track_menu(self.subtitle_menu, "subtitle")
        )
        subtitle.append(self.submenu_item("Subtitle track", self.subtitle_menu))
        menubar.append(self.submenu_item("Subtitle", subtitle))

        tools = Gtk.Menu()
        self.add_menu_item(tools, "Mirror phone screen", self.on_live, "<Control>m")
        self.add_menu_item(
            tools, "Mirror over USB", lambda: self.on_live("usb")
        )
        self.add_menu_item(
            tools, "Mirror over Wi-Fi", lambda: self.on_live("wifi")
        )
        self.add_menu_item(tools, "Stop live mirror", self.on_stop_live)
        tools.append(Gtk.SeparatorMenuItem())
        self.add_menu_item(tools, "Discover phone", self.on_discover)
        self.add_menu_item(tools, "Refresh library", self.on_refresh_library, "F5")
        menubar.append(self.submenu_item("Tools", tools))

        help_menu = Gtk.Menu()
        self.add_menu_item(help_menu, "About", self.on_about)
        menubar.append(self.submenu_item("Help", help_menu))
        return menubar

    def submenu_item(self, label, submenu):
        item = Gtk.MenuItem.new_with_mnemonic(label)
        item.set_submenu(submenu)
        return item

    def add_menu_item(self, menu, label, callback, accelerator=None):
        item = Gtk.MenuItem.new_with_mnemonic(label)
        if accelerator:
            key, mods = Gtk.accelerator_parse(accelerator)
            if key:
                item.add_accelerator(
                    "activate", self.accel_group, key, mods, Gtk.AccelFlags.VISIBLE
                )
        item.connect("activate", lambda *_: callback())
        menu.append(item)
        return item

    def add_check_item(self, menu, label, active, callback):
        item = Gtk.CheckMenuItem.new_with_mnemonic(label)
        item.set_active(active)
        item.connect("toggled", lambda widget: callback(widget.get_active()))
        menu.append(item)
        return item

    def build_connection_bar(self) -> Gtk.Box:
        bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        bar.set_border_width(4)
        self.phone_label = Gtk.Label(label="No phone connected")
        self.phone_label.set_alignment(0.0, 0.5)
        self.phone_label.set_ellipsize(Pango.EllipsizeMode.END)
        bar.pack_start(self.phone_label, True, True, 0)

        self.usb_button = Gtk.Button(label="USB")
        self.usb_button.set_tooltip_text(
            "Connect over USB with adb (no Wi-Fi network needed)"
        )
        self.usb_button.connect("clicked", lambda *_: self.on_usb_connect())
        bar.pack_start(self.usb_button, False, False, 0)
        self.connect_button = Gtk.Button(label="Connect")
        self.connect_button.set_tooltip_text("Connect to a phone on the local network")
        self.connect_button.connect("clicked", lambda *_: self.on_connect_dialog())
        bar.pack_start(self.connect_button, False, False, 0)
        self.discover_button = Gtk.Button(label="Discover")
        self.discover_button.connect("clicked", lambda *_: self.on_discover())
        bar.pack_start(self.discover_button, False, False, 0)
        self.live_button = Gtk.Button(label="Live mirror")
        self.live_button.connect("clicked", lambda *_: self.on_live())
        bar.pack_start(self.live_button, False, False, 0)
        return bar

    def build_seek_row(self) -> Gtk.Box:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        row.set_border_width(6)
        self.elapsed_label = Gtk.Label(label="0:00")
        self.elapsed_label.set_width_chars(7)
        self.elapsed_label.set_xalign(1.0)
        row.pack_start(self.elapsed_label, False, False, 0)

        self.seek = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1000, 1)
        self.seek.set_draw_value(False)
        self.seek.set_sensitive(False)
        self.seek.connect("change-value", self.on_seek_change)
        self.seek.connect("button-press-event", self.on_seek_pressed)
        self.seek.connect("button-release-event", self.on_seek_released)
        row.pack_start(self.seek, True, True, 0)

        self.total_label = Gtk.Label(label="0:00")
        self.total_label.set_width_chars(7)
        self.total_label.set_xalign(0.0)
        row.pack_start(self.total_label, False, False, 0)
        return row

    def toolbar_button(self, label: str, tooltip: str, callback) -> Gtk.Button:
        button = Gtk.Button(label=label)
        button.set_relief(Gtk.ReliefStyle.NONE)
        button.set_tooltip_text(tooltip)
        button.connect("clicked", lambda *_: callback())
        return button

    def toolbar_toggle(self, label: str, tooltip: str, callback) -> Gtk.ToggleButton:
        button = Gtk.ToggleButton(label=label)
        button.set_relief(Gtk.ReliefStyle.NONE)
        button.set_tooltip_text(tooltip)
        button.connect("toggled", lambda widget: callback(widget.get_active()))
        return button

    def build_transport(self) -> Gtk.Box:
        bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=2)
        bar.set_border_width(4)

        self.playlist_button = self.toolbar_button("☰", "Toggle playlist", self.on_toggle_playlist)
        bar.pack_start(self.playlist_button, False, False, 0)
        self.loop_button = self.toolbar_toggle("🔁", "Repeat one", self.on_toggle_loop)
        bar.pack_start(self.loop_button, False, False, 0)
        self.shuffle_button = self.toolbar_toggle("🔀", "Random", self.on_toggle_shuffle)
        bar.pack_start(self.shuffle_button, False, False, 0)
        self.previous_button = self.toolbar_button("⏮", "Previous (P)", self.on_previous)
        bar.pack_start(self.previous_button, False, False, 0)
        self.stop_button = self.toolbar_button("⏹", "Stop (S)", self.on_stop)
        bar.pack_start(self.stop_button, False, False, 0)
        self.play_button = self.toolbar_button("▶", "Play / Pause (Space)", self.on_play_pause)
        bar.pack_start(self.play_button, False, False, 0)
        self.next_button = self.toolbar_button("⏭", "Next (N)", self.on_next)
        bar.pack_start(self.next_button, False, False, 0)
        self.fullscreen_button = self.toolbar_button("⛶", "Fullscreen (F)", self.on_toggle_fullscreen)
        bar.pack_start(self.fullscreen_button, False, False, 0)
        self.record_button = self.toolbar_button("⏺", "Record (Ctrl+R)", self.on_record)
        bar.pack_start(self.record_button, False, False, 0)

        bar.pack_start(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL), False, False, 4)

        self.live_toolbar_button = self.toolbar_button(
            "📱", "Mirror the phone screen (Ctrl+M)", self.on_live
        )
        bar.pack_start(self.live_toolbar_button, False, False, 0)

        bar.pack_start(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL), False, False, 4)

        self.audio_button = self.toolbar_button("Audio", "Audio track", self.on_audio_menu)
        bar.pack_start(self.audio_button, False, False, 0)
        self.subtitle_button = self.toolbar_button("Subs", "Subtitle track", self.on_subtitle_menu)
        bar.pack_start(self.subtitle_button, False, False, 0)
        self.speed_button = self.toolbar_button("1.00x", "Playback speed", self.on_speed_menu)
        bar.pack_start(self.speed_button, False, False, 0)

        volume_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=2)
        volume_box.pack_start(
            Gtk.Separator(orientation=Gtk.Orientation.VERTICAL), False, False, 4
        )
        self.volume_button = Gtk.Button(label="🔊")
        self.volume_button.set_relief(Gtk.ReliefStyle.NONE)
        self.volume_button.set_tooltip_text("Mute (M)")
        self.volume_button.connect("clicked", lambda *_: self.on_toggle_mute())
        volume_box.pack_start(self.volume_button, False, False, 0)
        self.volume_scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 150, 1)
        self.volume_scale.set_draw_value(False)
        self.volume_scale.set_size_request(110, -1)
        self.volume_scale.set_tooltip_text("Volume")
        self.volume_scale.set_value(self.volume)
        self.volume_scale.connect("value-changed", self.on_volume_changed)
        volume_box.pack_start(self.volume_scale, False, False, 0)
        bar.pack_end(volume_box, False, False, 0)
        return bar

    def build_playlist_panel(self) -> Gtk.Box:
        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        panel.set_border_width(6)
        panel.set_size_request(300, -1)

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        title = Gtk.Label(label="Playlist")
        title.set_alignment(0.0, 0.5)
        title.set_markup("<b>Playlist</b>")
        header.pack_start(title, True, True, 0)
        refresh = Gtk.Button(label="Refresh")
        refresh.connect("clicked", lambda *_: self.on_refresh_library())
        header.pack_start(refresh, False, False, 0)
        panel.pack_start(header, False, False, 0)

        self.filter_entry = Gtk.SearchEntry()
        self.filter_entry.set_placeholder_text("Filter library")
        self.filter_entry.connect("search-changed", lambda *_: self.refill_playlist())
        panel.pack_start(self.filter_entry, False, False, 0)

        self.playlist_model = Gtk.ListStore(str, str, str, int)
        self.playlist_view = Gtk.TreeView(model=self.playlist_model)
        self.playlist_view.set_headers_visible(True)
        renderer = Gtk.CellRendererText()
        renderer.set_property("ellipsize", Pango.EllipsizeMode.END)
        column = Gtk.TreeViewColumn("Title", renderer, text=0)
        column.set_expand(True)
        self.playlist_view.append_column(column)
        duration_renderer = Gtk.CellRendererText()
        duration_renderer.set_property("xalign", 1.0)
        self.playlist_view.append_column(
            Gtk.TreeViewColumn("Time", duration_renderer, text=1)
        )
        self.playlist_view.connect("row-activated", self.on_playlist_activated)
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        scroll.add(self.playlist_view)
        panel.pack_start(scroll, True, True, 0)

        self.playlist_count = Gtk.Label(label="0 items")
        self.playlist_count.set_alignment(0.0, 0.5)
        panel.pack_start(self.playlist_count, False, False, 0)
        return panel

    # --------------------------------------------------------------- helpers
    def set_status(self, message: str, timeout_ms: int | None = None):
        self.status_label.set_text(message)
        if timeout_ms:
            if self._status_reset_source:
                GLib.source_remove(self._status_reset_source)
            self._status_reset_source = GLib.timeout_add(
                timeout_ms, self.clear_status_timeout
            )

    def clear_status_timeout(self):
        self._status_reset_source = None
        if not self.player.is_playing():
            self.status_label.set_text("")
        return False

    def apply_settings(self):
        volume = int(self.settings.get("volume", 100))
        self.player.set_volume(volume)
        self.volume_scale.set_value(volume)
        self.volume_button.set_label("🔊" if volume > 0 else "🔇")
        self.player.set_mute(False)

    def update_connection(self):
        if self.client and self.phone_info:
            model = self.phone_info.get("model", "phone")
            brand = self.phone_info.get("brand", "")
            label = f"{brand} {model}".strip()
            url = self.phone_info.get("url") or self.client.base_url
            if self.client.is_local:
                self.phone_label.set_text(
                    f"{label}  -  USB (adb forward {self.client.port})"
                )
                self.connection_label.set_text(
                    f"Connected over USB to {label}  -  {url}"
                )
            else:
                self.phone_label.set_text(f"{label}  -  {url}")
                self.connection_label.set_text(f"Connected to {url}")
            self.set_title(f"{APP_NAME} - {label}")
        else:
            self.phone_label.set_text("No phone connected")
            self.connection_label.set_text("Not connected")
            self.set_title(APP_NAME)

    # ----------------------------------------------------------- connectivity
    def auto_connect(self):
        """Prefer USB (the usual setup), then fall back to the last LAN host."""
        last_host = self.settings.get("last_host", "")
        if self.settings.get("prefer_usb", True):
            self.set_status("Looking for a phone over USB ...")
            threading.Thread(
                target=self.usb_connect_worker, args=(True,), daemon=True
            ).start()
        elif last_host:
            self.set_status(f"Connecting to {last_host} ...")
            threading.Thread(
                target=self.connect_worker, args=(last_host,), daemon=True
            ).start()
        else:
            self.auto_discover()
        return False

    def auto_discover(self):
        threading.Thread(target=self.discover_worker, args=(2.5, True), daemon=True).start()
        return False

    def on_usb_connect(self):
        self.set_status("Looking for a phone over USB ...")
        threading.Thread(target=self.usb_connect_worker, args=(False,), daemon=True).start()

    def usb_connect_worker(self, silent: bool):
        try:
            client, info = connect_usb(local_port=USB_FORWARD_PORT, timeout=8.0)
        except Exception as error:  # noqa: BLE001 - report to UI
            GLib.idle_add(self.usb_connect_failed, error, silent)
            return
        GLib.idle_add(self.usb_connection_ready, client, info)

    def usb_connection_ready(self, client, info):
        self.adb_forward_port = client.port
        self.adb_forward_owned = True
        self.settings["usb_forward_port"] = client.port
        self.connection_ready(client, info, f"127.0.0.1:{client.port}")

    def usb_connect_failed(self, error, silent):
        last_host = self.settings.get("last_host", "")
        if last_host and not last_host.startswith(("127.0.0.1", "localhost")):
            self.set_status(f"USB unavailable ({error}); trying {last_host} ...", 6000)
            threading.Thread(
                target=self.connect_worker, args=(last_host,), daemon=True
            ).start()
            return
        if not silent:
            self.set_status(
                f"No phone over USB: {error}. Connect with a USB cable and "
                "enable USB debugging.",
                9000,
            )
            return
        self.auto_discover()

    def on_discover(self):
        self.set_status("Searching for phones on the network ...")
        threading.Thread(target=self.discover_worker, args=(3.5, False), daemon=True).start()

    def discover_worker(self, timeout: float, silent: bool):
        try:
            found = discover_phones(timeout=timeout)
        except Exception as error:  # noqa: BLE001 - report to UI
            GLib.idle_add(self.set_status, f"Discovery failed: {error}", 5000)
            return
        if not found:
            if not silent:
                GLib.idle_add(self.set_status, "No phones found - use Connect to enter an address", 5000)
            return
        urls = list(found.keys())
        GLib.idle_add(self.discovery_done, urls, found)

    def discovery_done(self, urls, found):
        if len(urls) == 1:
            self.connect_to(urls[0])
        else:
            self.show_discovery_dialog(urls, found)

    def show_discovery_dialog(self, urls, found):
        dialog = Gtk.Dialog(title="Phones found", transient_for=self, modal=True)
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Connect", Gtk.ResponseType.OK)
        box = dialog.get_content_area()
        box.set_spacing(6)
        box.set_border_width(12)
        box.add(Gtk.Label(label="Choose a phone to connect to:"))
        combo = Gtk.ComboBoxText()
        for url in urls:
            info = found.get(url, {})
            combo.append_text(f"{info.get('brand', '')} {info.get('model', url)} - {url}")
        combo.set_active(0)
        box.pack_start(combo, False, False, 0)
        dialog.show_all()
        if dialog.run() == Gtk.ResponseType.OK:
            index = max(0, combo.get_active())
            self.connect_to(urls[index])
        dialog.destroy()

    def on_connect_dialog(self):
        dialog = Gtk.Dialog(title="Connect to phone", transient_for=self, modal=True)
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Connect", Gtk.ResponseType.OK)
        box = dialog.get_content_area()
        box.set_spacing(8)
        box.set_border_width(12)
        box.add(
            Gtk.Label(
                label="Enter the phone address (for example 192.168.1.20 or "
                "127.0.0.1:18080 when using adb forward)."
            )
        )
        entry = Gtk.Entry()
        entry.set_text(self.settings.get("last_host", "192.168.1.20"))
        entry.set_activates_default(True)
        box.pack_start(entry, False, False, 0)
        dialog.set_default_response(Gtk.ResponseType.OK)
        dialog.show_all()
        if dialog.run() == Gtk.ResponseType.OK:
            address = entry.get_text().strip()
            if address:
                self.connect_to(address)
        dialog.destroy()

    def connect_to(self, address: str):
        self.set_status(f"Connecting to {address} ...")
        threading.Thread(target=self.connect_worker, args=(address,), daemon=True).start()

    def connect_worker(self, address: str):
        try:
            client = PhoneClient.from_url(address, timeout=8.0)
            info = client.info()
        except Exception as error:  # noqa: BLE001 - report to UI
            GLib.idle_add(self.connection_failed, address, error)
            return
        GLib.idle_add(self.connection_ready, client, info, address)

    def connection_failed(self, address, error):
        self.set_status(f"Could not connect to {address}: {error}", 6000)
        self.update_connection()
        if self.self_test:
            self.finish_self_test(False, f"connection failed: {error}")

    def connection_ready(self, client, info, address):
        self.client = client
        self.phone_info = info
        if client.is_local:
            self.adb_forward_port = client.port
            self.settings["usb_forward_port"] = client.port
        else:
            self.settings["last_host"] = address
        save_settings(self.settings)
        self.update_connection()
        if client.is_local:
            self.set_status(
                f"Connected over USB to {info.get('model', 'phone')} "
                f"(adb forward {client.port})",
                5000,
            )
        else:
            self.set_status(f"Connected to {info.get('url', address)}", 4000)
        self.on_refresh_library()
        if self.self_test and not self.auto_play_done:
            GLib.timeout_add(2500, self.self_test_play)

    # -------------------------------------------------------------- playlist
    def on_open_library(self):
        if not self.client:
            self.on_connect_dialog()
            return
        self.show_playlist(True)
        self.on_refresh_library()

    def on_refresh_library(self):
        if not self.client:
            self.set_status("Connect to a phone first", 4000)
            return
        self.set_status("Loading phone library ...")
        client = self.client
        threading.Thread(target=self.library_worker, args=(client,), daemon=True).start()

    def library_worker(self, client):
        try:
            items = client.library()
        except Exception as error:  # noqa: BLE001 - report to UI
            GLib.idle_add(self.set_status, f"Library failed: {error}", 6000)
            if self.self_test:
                GLib.idle_add(self.finish_self_test, False, f"library failed: {error}")
            return
        GLib.idle_add(self.library_loaded, items)

    def library_loaded(self, items):
        self.library = items or []
        self.refill_playlist()
        self.set_status(f"{len(self.library)} videos on the phone", 3500)
        if self.self_test and not self.auto_play_done:
            self.self_test_play()

    def refill_playlist(self):
        self.playlist_model.clear()
        needle = ""
        try:
            needle = self.filter_entry.get_text().strip().lower()
        except Exception:
            pass
        shown = 0
        for item in self.library:
            title = item.get("title") or item.get("path") or f"Video {item.get('id')}"
            if needle and needle not in title.lower():
                continue
            self.playlist_model.append(
                [
                    title,
                    format_duration(item.get("durationMs")),
                    format_size(item.get("size")),
                    int(item.get("id", 0)),
                ]
            )
            shown += 1
        self.playlist_count.set_text(f"{shown} item{'s' if shown != 1 else ''}")

    def selected_item(self):
        model, tree_iter = self.playlist_view.get_selection().get_selected()
        if tree_iter is None:
            return None
        video_id = int(model.get_value(tree_iter, 3))
        for item in self.library:
            if int(item.get("id", 0)) == video_id:
                return item
        return None

    def on_playlist_activated(self, view, path, column):
        item = self.selected_item()
        if item:
            self.play_item(item)

    def show_playlist(self, visible: bool):
        self._syncing_playlist = True
        try:
            self.playlist_visible = visible
            self.playlist_panel.set_visible(visible)
            self.playlist_check.set_active(visible)
        finally:
            self._syncing_playlist = False

    def on_toggle_playlist(self, visible=None):
        if self._syncing_playlist:
            return
        self.show_playlist(not self.playlist_visible)

    def on_toggle_loop(self, active):
        self.loop_enabled = bool(active)
        if self.loop_button.get_active() != self.loop_enabled:
            self.loop_button.set_active(self.loop_enabled)
        self.set_status("Repeat one: on" if self.loop_enabled else "Repeat one: off", 2000)

    def on_toggle_shuffle(self, active):
        self.shuffle_enabled = bool(active)
        if self.shuffle_button.get_active() != self.shuffle_enabled:
            self.shuffle_button.set_active(self.shuffle_enabled)
        self.set_status("Random: on" if self.shuffle_enabled else "Random: off", 2000)

    # ---------------------------------------------------------------- playing
    def on_video_realize(self, widget):
        try:
            window = widget.get_window()
            self.xid = window.get_xid()
            self.player.set_xwindow(self.xid)
        except Exception as error:  # noqa: BLE001 - report to UI
            self.set_status(f"Could not embed video output: {error}", 6000)

    def attach_video_output(self):
        if self.xid is None:
            try:
                window = self.video_area.get_window()
                if window is not None:
                    self.xid = window.get_xid()
            except Exception:
                return
        if self.xid is not None:
            self.player.set_xwindow(self.xid)

    def play_item(self, item: dict):
        if not self.client:
            self.set_status("Connect to a phone first", 4000)
            return
        url = self.client.media_url(item.get("id"))
        title = item.get("title") or os.path.basename(item.get("path") or "") or url
        self.attach_video_output()
        self.stop_recording_if_needed()
        self.current = item
        self.current_is_live = False
        self.media = self.libvlc.media(
            mrl=url,
            options=[
                ":http-reconnect",
                ":network-caching=1500",
                ":file-caching=1500",
            ],
        )
        self.player.set_media(self.media)
        self.player.play()
        self.seek.set_sensitive(True)
        self.play_button.set_label("⏸")
        self.set_status(f"Playing {title}")
        self.set_title(f"{APP_NAME} - {title}")

    def play_path(self, path: str):
        self.attach_video_output()
        self.stop_recording_if_needed()
        self.current = {"title": os.path.basename(path), "id": -1}
        self.current_is_live = False
        self.media = self.libvlc.media(path=path)
        self.player.set_media(self.media)
        self.player.play()
        self.seek.set_sensitive(True)
        self.play_button.set_label("⏸")
        self.set_status(f"Playing {os.path.basename(path)}")
        self.set_title(f"{APP_NAME} - {os.path.basename(path)}")

    def play_url(self, url: str):
        self.attach_video_output()
        self.stop_recording_if_needed()
        self.current = {"title": url, "id": -1}
        self.current_is_live = False
        self.media = self.libvlc.media(mrl=url, options=[":network-caching=2000"])
        self.player.set_media(self.media)
        self.player.play()
        self.seek.set_sensitive(True)
        self.set_status(f"Playing {url}")

    # ------------------------------------------------------------------ live
    def on_live(self, mode=None):
        if not self.client:
            self.set_status("Connect to a phone first", 4000)
            self.on_connect_dialog()
            return
        explicit = mode is not None
        if mode is None:
            # A loopback client means the phone was reached with `adb forward`;
            # RTP/UDP cannot travel over that path, so default to USB.
            mode = "usb" if self.client.is_local else "wifi"
        if mode not in ("usb", "wifi"):
            mode = "usb"
        self.live_mode = mode
        self.live_button.set_label("Starting ...")
        # The worker checks whether the phone is already mirroring and attaches
        # to that stream instead of asking for a new capture session.
        if mode == "usb":
            self.set_status(
                "Starting USB mirror - approve the screen-capture request on the phone ..."
            )
        else:
            self.set_status(
                "Starting Wi-Fi mirror - approve the screen-capture request on the phone ..."
            )
        threading.Thread(
            target=self.live_worker, args=(mode, explicit), daemon=True
        ).start()

    def live_worker(self, requested_mode="usb", explicit=False):
        client = self.client
        if client is None:
            return
        control = client
        # A loopback control client already has a working adb forward.
        usb_client = client if client.is_local else None
        mode = requested_mode
        stream_source = None
        try:
            status = control.live_status()
            attached = status.get("state") == "streaming"
            if attached and explicit and status.get("usb") != (requested_mode == "usb"):
                # An explicit "Mirror over USB/Wi-Fi" request switches the
                # transport instead of silently attaching to the other one.
                if self.self_test:
                    print(
                        f"[live] switching stream to {requested_mode}",
                        flush=True,
                    )
                control.live_stop()
                deadline = time.time() + 15.0
                while time.time() < deadline:
                    time.sleep(0.5)
                    status = control.live_status()
                    if status.get("state") not in (
                        "streaming",
                        "stopping",
                        "requesting",
                        "awaiting_permission",
                    ):
                        break
                attached = False
            self.live_owned = False
            if attached:
                # The phone is already mirroring (perhaps started from the
                # phone UI or a previous session): attach to that stream
                # instead of refusing or starting a second capture session.
                mode = "usb" if status.get("usb") else "wifi"
                if self.self_test:
                    print(f"[live] attaching to existing {mode} stream", flush=True)
                deadline = time.time() + 15.0
                while time.time() < deadline:
                    ready = (
                        status.get("streamReady")
                        if mode == "usb"
                        else status.get("sdpReady")
                    )
                    if ready:
                        break
                    time.sleep(0.5)
                    status = control.live_status()
            else:
                if requested_mode == "usb" and usb_client is None:
                    # The GUI is connected over Wi-Fi, but USB mirroring was
                    # requested: set up an adb forward just for the TS stream.
                    usb_client, _usb_info = connect_usb(
                        local_port=USB_FORWARD_PORT, timeout=8.0
                    )
                    GLib.idle_add(self.usb_forward_ready, usb_client.port)
                elif requested_mode == "wifi" and client.is_local:
                    # Wi-Fi RTP must originate from the LAN address, otherwise
                    # the phone would send UDP packets to itself.
                    lan_url = (self.phone_info.get("url") or "").strip()
                    if not lan_url:
                        raise PhoneError(
                            "The phone did not report a Wi-Fi address. "
                            "Use USB mirroring instead."
                        )
                    control = PhoneClient.from_url(lan_url, timeout=8.0)
                if status.get("state") in ("requesting", "awaiting_permission"):
                    # A previous request was never approved; reset the phone so
                    # the new request is not ignored.
                    control.live_stop()
                    time.sleep(1.0)
                if self.self_test:
                    print(f"[live] requesting {requested_mode} mirror", flush=True)
                control.live_start(
                    LIVE_VIDEO_PORT, LIVE_AUDIO_PORT, LIVE_BITRATE, mode=requested_mode
                )
                self.live_owned = True
                deadline = time.time() + 90.0
                while time.time() < deadline:
                    status = control.live_status()
                    state = status.get("state", "")
                    if state == "error":
                        raise PhoneError(
                            status.get("message") or "phone reported an error"
                        )
                    if state == "streaming":
                        # Follow whichever transport actually became active.
                        mode = "usb" if status.get("usb") else "wifi"
                        if mode == "usb" and status.get("streamReady"):
                            break
                        if mode == "wifi" and status.get("sdpReady"):
                            break
                    time.sleep(0.5)
                else:
                    raise PhoneError(
                        "timed out waiting for the phone to start mirroring"
                    )
            if mode == "usb":
                if usb_client is None:
                    # Attached to a USB stream while controlling over Wi-Fi.
                    usb_client, _usb_info = connect_usb(
                        local_port=USB_FORWARD_PORT, timeout=8.0
                    )
                    GLib.idle_add(self.usb_forward_ready, usb_client.port)
                path = status.get("streamPath") or "/api/live/stream.ts"
                if str(path).startswith("http"):
                    stream_source = str(path)
                else:
                    stream_source = usb_client.base_url + str(path)
            else:
                sdp = control.live_sdp()
                stream_source = live_sdp_path()
                with open(stream_source, "w", encoding="utf-8") as handle:
                    handle.write(sdp)
        except Exception as error:  # noqa: BLE001 - report to UI
            GLib.idle_add(self.live_failed, error)
            return
        GLib.idle_add(self.live_ready, stream_source, status, mode, control)

    def usb_forward_ready(self, port):
        self.adb_forward_port = port
        self.adb_forward_owned = True
        self.settings["usb_forward_port"] = port
        save_settings(self.settings)
        return False

    def live_ready(self, source, status, mode="wifi", control=None):
        self.live_button.set_label("Live mirror")
        self.started_live = True
        self.live_mode = mode
        self.live_control_client = control or self.client
        self.live_sdp_file = source if mode == "wifi" else None
        self.attach_video_output()
        self.stop_recording_if_needed()
        self.current = {
            "title": "Phone screen (USB)" if mode == "usb" else "Phone screen",
            "id": -1,
        }
        self.current_is_live = True
        # The phone encodes plain Baseline H.264 (no constraint flags), while
        # Intel VA-API only exposes Constrained Baseline.  Hardware setup then
        # fails and VLC probes VDPAU, which needs an Nvidia driver.  The live
        # mirror is small enough to decode in software, so skip both probes.
        live_options = [":avcodec-hw=none"]
        if mode == "usb":
            self.media = self.libvlc.media(
                mrl=source,
                options=[
                    # VLC 3.0.x builds on some distributions ship without the
                    # native TS demuxer; FFmpeg reads the MPEG-TS stream.
                    ":demux=avformat",
                    ":network-caching=800",
                    ":http-reconnect",
                ] + live_options,
            )
        else:
            self.media = self.libvlc.media(
                path=source,
                options=[
                    ":demux=avformat",
                    ":avformat-format=sdp",
                    ":network-caching=800",
                ] + live_options,
            )
        self.player.set_media(self.media)
        self.player.play()
        self.seek.set_sensitive(False)
        self.elapsed_label.set_text("LIVE")
        self.total_label.set_text("")
        width = status.get("width", 0)
        height = status.get("height", 0)
        detail = f" ({width}x{height})" if width and height else ""
        transport = "USB" if mode == "usb" else "Wi-Fi"
        self.set_status(f"Mirroring the phone screen over {transport}{detail}")
        self.set_title(f"{APP_NAME} - Live phone screen ({transport})")

    def live_failed(self, error):
        self.live_button.set_label("Live mirror")
        self.started_live = False
        self.live_owned = False
        self.live_mode = None
        self.live_control_client = None
        self.set_status(f"Live mirror failed: {error}", 8000)
        if self.self_test:
            self.finish_self_test(False, f"live mirror failed: {error}")

    def on_stop_live(self):
        if self.current_is_live:
            self.on_stop()
            return
        client = self.live_control_client or self.client
        if client:
            threading.Thread(
                target=self.stop_live_worker, args=(client,), daemon=True
            ).start()
        self.started_live = False

    def stop_live_worker(self, client):
        try:
            client.live_stop()
        except Exception:
            pass
        GLib.idle_add(self.live_stopped)

    def live_stopped(self):
        self.live_control_client = None
        self.live_mode = None
        self.started_live = False
        self.live_owned = False
        self.set_status("Live mirror stopped", 4000)
        return False

    # ----------------------------------------------------------- media dialogs
    def on_open_file(self):
        dialog = Gtk.FileChooserDialog(
            title="Open media file",
            transient_for=self,
            action=Gtk.FileChooserAction.OPEN,
        )
        dialog.add_buttons(
            "Cancel", Gtk.ResponseType.CANCEL, "Open", Gtk.ResponseType.OK
        )
        media_filter = Gtk.FileFilter()
        media_filter.set_name("Media files")
        for pattern in ("*.mp4", "*.mkv", "*.avi", "*.mov", "*.webm", "*.mp3", "*.flac", "*.wav", "*.m4a", "*.sdp"):
            media_filter.add_pattern(pattern)
        dialog.add_filter(media_filter)
        if dialog.run() == Gtk.ResponseType.OK:
            path = dialog.get_filename()
            if path:
                self.play_path(path)
        dialog.destroy()

    def on_open_network(self):
        dialog = Gtk.Dialog(title="Open network stream", transient_for=self, modal=True)
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Play", Gtk.ResponseType.OK)
        box = dialog.get_content_area()
        box.set_spacing(8)
        box.set_border_width(12)
        box.add(Gtk.Label(label="Network URL (http, https, rtp or file path):"))
        entry = Gtk.Entry()
        entry.set_text("http://127.0.0.1:18080/media?id=")
        entry.set_activates_default(True)
        box.pack_start(entry, False, False, 0)
        dialog.set_default_response(Gtk.ResponseType.OK)
        dialog.show_all()
        if dialog.run() == Gtk.ResponseType.OK:
            url = entry.get_text().strip()
            if url:
                self.play_url(url)
        dialog.destroy()

    def on_previous(self):
        items = list(self.playlist_model)
        if not items or self.current is None:
            return
        ids = [int(row[3]) for row in items]
        try:
            index = ids.index(int(self.current.get("id", -1)))
        except ValueError:
            index = 0
        index = (index - 1) % len(ids)
        self.play_item(self.item_for_id(ids[index]))

    def on_next(self):
        items = list(self.playlist_model)
        if not items:
            return
        ids = [int(row[3]) for row in items]
        if self.current is None:
            index = 0
        else:
            current_id = int(self.current.get("id", -1))
            if self.shuffle_enabled:
                choices = [i for i, value in enumerate(ids) if value != current_id]
                if choices:
                    index = random.choice(choices)
                else:
                    index = 0
            else:
                try:
                    index = (ids.index(current_id) + 1) % len(ids)
                except ValueError:
                    index = 0
        self.play_item(self.item_for_id(ids[index]))

    def item_for_id(self, video_id):
        for item in self.library:
            if int(item.get("id", 0)) == int(video_id):
                return item
        return {"id": video_id, "title": f"Video {video_id}"}

    # ---------------------------------------------------------------- control
    def on_play_pause(self):
        state = self.player.state()
        if state in (
            vlc_wrap.LibVLC.STATE_PAUSED,
            vlc_wrap.LibVLC.STATE_STOPPED,
            vlc_wrap.LibVLC.STATE_ENDED,
            vlc_wrap.LibVLC.STATE_ERROR,
        ):
            if state in (vlc_wrap.LibVLC.STATE_STOPPED, vlc_wrap.LibVLC.STATE_ENDED):
                if self.current and self.client and not self.current_is_live:
                    self.play_item(self.current)
                    return
            self.player.play()
        else:
            self.player.pause()

    def on_stop(self):
        was_live = self.current_is_live
        self.stop_recording_if_needed()
        self.player.stop()
        self.current_is_live = False
        self.seek.set_value(0)
        self.elapsed_label.set_text("0:00")
        self.total_label.set_text("0:00")
        self.play_button.set_label("▶")
        self.set_status("Stopped", 2000)
        self.set_title(APP_NAME)
        if was_live and self.started_live:
            client = self.live_control_client or self.client
            self.started_live = False
            self.live_owned = False
            if client:
                threading.Thread(
                    target=self.stop_live_worker, args=(client,), daemon=True
                ).start()
            self.live_control_client = None
            self.live_mode = None

    def on_seek_change(self, scale, scroll_type, value):
        if self.seeking and self.player.get_length() > 0:
            target = int(self.player.get_length() * value / 1000.0)
            self.elapsed_label.set_text(format_duration(target))
        return False

    def on_seek_pressed(self, widget, event):
        self.seeking = True
        return False

    def on_seek_released(self, widget, event):
        if self.seeking and self.player.get_length() > 0:
            self.player.set_position(widget.get_value() / 1000.0)
        self.seeking = False
        return False

    def change_speed(self, factor):
        index = min(range(len(SPEEDS)), key=lambda i: abs(SPEEDS[i] - self.speed))
        target = SPEEDS[min(len(SPEEDS) - 1, index + 1 if factor > 1 else max(0, index - 1))]
        self.set_speed(target)

    def set_speed(self, speed: float):
        self.speed = max(0.25, min(2.0, float(speed)))
        self.player.set_rate(self.speed)
        self.speed_button.set_label(f"{self.speed:.2f}x")
        self.set_status(f"Playback speed {self.speed:.2f}x", 2000)

    def on_speed_menu(self):
        menu = Gtk.Menu()
        for speed in SPEEDS:
            item = Gtk.CheckMenuItem(label=f"{speed:.2f}x")
            item.set_active(abs(speed - self.speed) < 0.001)
            item.connect("activate", lambda _w, s=speed: self.set_speed(s))
            menu.append(item)
        menu.show_all()
        menu.popup_at_widget(self.speed_button, Gdk.Gravity.SOUTH_WEST, Gdk.Gravity.NORTH_WEST, None)

    def on_volume_changed(self, scale):
        volume = int(scale.get_value())
        self.volume = volume
        self.settings["volume"] = volume
        self.player.set_volume(volume)
        self.volume_button.set_label("🔇" if volume == 0 or self.muted else "🔊")
        if volume > 0 and self.muted:
            self.muted = False
            self.player.set_mute(False)
            self.mute_check.set_active(False)

    def bump_volume(self, delta):
        self.volume_scale.set_value(max(0, min(150, self.volume + delta)))

    def on_toggle_mute(self, active=None):
        if active is None:
            active = not self.muted
        self.muted = bool(active)
        self.player.set_mute(self.muted)
        self.volume_button.set_label("🔇" if self.muted else "🔊")
        self.mute_check.set_active(self.muted)
        self.set_status("Muted" if self.muted else "Unmuted", 1500)

    # ----------------------------------------------------------------- tracks
    def on_audio_menu(self):
        self.show_track_menu("audio", self.audio_button)

    def on_subtitle_menu(self):
        self.show_track_menu("subtitle", self.subtitle_button)

    def track_list(self, kind):
        try:
            if kind == "audio":
                return self.player.audio_tracks()
            if kind == "subtitle":
                return self.player.subtitle_tracks()
            return self.player.video_tracks()
        except Exception:
            return []

    def current_track(self, kind):
        try:
            if kind == "audio":
                return self.player.current_audio_track()
            if kind == "subtitle":
                return self.player.current_subtitle_track()
            return self.player.current_video_track()
        except Exception:
            return -1

    def populate_track_menu(self, menu, kind):
        for child in list(menu.get_children()):
            menu.remove(child)
        active = self.current_track(kind)
        tracks = self.track_list(kind)
        if not tracks:
            item = Gtk.MenuItem(label="No tracks available")
            item.set_sensitive(False)
            menu.append(item)
        for track_id, name in tracks:
            item = Gtk.CheckMenuItem(label=name or f"Track {track_id}")
            item.set_active(track_id == active)
            item.connect("activate", lambda _w, t=track_id: self.select_track(kind, t))
            menu.append(item)
        menu.show_all()

    def show_track_menu(self, kind, anchor):
        menu = Gtk.Menu()
        self.populate_track_menu(menu, kind)
        menu.popup_at_widget(anchor, Gdk.Gravity.SOUTH_WEST, Gdk.Gravity.NORTH_WEST, None)

    def select_track(self, kind, track_id):
        try:
            if kind == "audio":
                self.player.set_audio_track(track_id)
            elif kind == "subtitle":
                self.player.set_subtitle_track(track_id)
            else:
                self.player.set_video_track(track_id)
            self.set_status(f"Selected {kind} track {track_id}", 2000)
        except Exception as error:
            self.set_status(f"Could not change {kind} track: {error}", 4000)

    def on_add_subtitle(self):
        dialog = Gtk.FileChooserDialog(
            title="Add subtitle file",
            transient_for=self,
            action=Gtk.FileChooserAction.OPEN,
        )
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Open", Gtk.ResponseType.OK)
        subtitle_filter = Gtk.FileFilter()
        subtitle_filter.set_name("Subtitles")
        for pattern in ("*.srt", "*.vtt", "*.ass", "*.ssa", "*.sub"):
            subtitle_filter.add_pattern(pattern)
        dialog.add_filter(subtitle_filter)
        if dialog.run() == Gtk.ResponseType.OK:
            path = dialog.get_filename()
            if path and self.player.add_subtitle(path):
                self.set_status(f"Subtitle added: {os.path.basename(path)}", 3000)
            else:
                self.set_status("Could not load the subtitle file", 4000)
        dialog.destroy()

    # ------------------------------------------------------------------ video
    def set_aspect(self, value):
        self.player.set_aspect_ratio(value)
        self.set_status(f"Aspect ratio: {value or 'default'}", 2000)

    def set_zoom(self, value):
        self.player.set_scale(value)
        self.set_status(f"Zoom: {value or 'default'}", 2000)

    def set_deinterlace(self, mode):
        self.player.set_deinterlace(mode)
        self.set_status(f"Deinterlace: {mode}", 2000)

    def on_snapshot(self):
        if not self.player.has_vout():
            self.set_status("Nothing is playing", 3000)
            return
        path = timestamped(SNAPSHOT_DIR, "videos-snapshot", ".png")
        if self.player.snapshot(path):
            self.set_status(f"Snapshot saved: {path}", 5000)
        else:
            self.set_status("Snapshot failed", 4000)

    def on_record(self):
        if self.record_proc is not None or self.recording_live:
            self.stop_recording_if_needed(manual=True)
            return
        live_client = self.live_control_client or self.client
        if self.current_is_live and live_client:
            self.recording_live = True
            self.record_button.set_label("⏹")
            threading.Thread(
                target=self.set_live_recording, args=(live_client, True), daemon=True
            ).start()
            return
        if not self.client or self.current is None or self.current.get("id", -1) < 0:
            self.set_status("Open a phone video to record it", 4000)
            return
        position = self.player.get_time() // 1000
        url = self.client.media_url(self.current.get("id"))
        self.record_path = timestamped(RECORD_DIR, "videos-record", ".mp4")
        command = [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            str(max(0, position)),
            "-i",
            url,
            "-c",
            "copy",
            "-movflags",
            "+faststart",
            self.record_path,
        ]
        try:
            self.record_proc = subprocess.Popen(
                command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
        except OSError as error:
            self.set_status(f"Recording failed: {error}", 5000)
            return
        self.record_button.set_label("⏹")
        self.set_status(f"Recording to {self.record_path}", 6000)

    def set_live_recording(self, client, enabled):
        try:
            client.live_record(enabled)
            message = "Recording the live mirror" if enabled else "Live recording stopped"
        except Exception as error:  # noqa: BLE001 - report to UI
            message = f"Live recording failed: {error}"
            enabled = False
        GLib.idle_add(self.live_recording_done, message, enabled)

    def live_recording_done(self, message, enabled):
        if not enabled:
            self.recording_live = False
            self.record_button.set_label("⏺")
        self.set_status(message, 5000)

    def stop_recording_if_needed(self, manual=False):
        if self.record_proc is not None:
            process = self.record_proc
            self.record_proc = None
            try:
                process.send_signal(signal.SIGINT)
                process.wait(timeout=6)
            except Exception:
                try:
                    process.terminate()
                except Exception:
                    pass
            saved = self.record_path
            self.record_path = None
            self.record_button.set_label("⏺")
            if manual:
                self.set_status(f"Recording saved: {saved}", 6000)
        live_client = self.live_control_client or self.client
        if self.recording_live and live_client is not None and manual:
            self.recording_live = False
            self.record_button.set_label("⏺")
            threading.Thread(
                target=self.set_live_recording, args=(live_client, False), daemon=True
            ).start()

    # -------------------------------------------------------------- fullscreen
    def on_toggle_fullscreen(self):
        if self.is_fullscreen:
            self.unfullscreen()
        else:
            self.fullscreen()

    def on_window_state(self, widget, event):
        is_full = bool(event.new_window_state & Gdk.WindowState.FULLSCREEN)
        if is_full == self.is_fullscreen:
            return
        self.is_fullscreen = is_full
        for widget in (
            self.menubar,
            self.connection_bar,
            self.seek_row,
            self.transport,
            self.playlist_panel,
            self.statusbar,
        ):
            widget.set_visible(not is_full)
        if is_full:
            self._playlist_before_fullscreen = self.playlist_visible
            self.show_playlist(False)
        else:
            self.show_all()
            self.show_playlist(self._playlist_before_fullscreen)
        self.fullscreen_button.set_label("⛶")

    def on_video_button(self, widget, event):
        if event.type == Gdk.EventType._2BUTTON_PRESS:
            self.on_toggle_fullscreen()
        return False

    # ------------------------------------------------------------------ timer
    def on_tick(self):
        if getattr(self, "_destroyed", False):
            return False
        if self.auto_quit_at is not None and time.time() > self.auto_quit_at:
            if self.self_test:
                state = self.player.state_name()
                position = self.player.get_time()
                ok = self.player.is_playing() or position > 0 or state in ("Paused", "Playing")
                self.finish_self_test(
                    ok,
                    f"state={state} time={position}ms library={len(self.library)}",
                )
            else:
                self.close()
            return False

        if self._http_idle:
            return True
        self._http_idle = True
        GLib.timeout_add(250, self._tick_body)
        return True

    def _tick_body(self):
        try:
            if not getattr(self, "_destroyed", False):
                self.update_transport_ui()
        except Exception:
            pass
        finally:
            self._http_idle = False
        return False

    def update_transport_ui(self):
        try:
            length = self.player.get_length()
            state = self.player.state()
        except Exception:
            return
        try:
            playing = self.player.is_playing()
        except Exception:
            playing = False
        if self.current_is_live:
            self.elapsed_label.set_text("LIVE")
            self.total_label.set_text("")
            self.seek.set_sensitive(False)
        else:
            position = self.player.get_time()
            if not self.seeking:
                if length > 0:
                    self.seek.set_value(min(1000.0, position * 1000.0 / length))
                else:
                    self.seek.set_value(0)
                self.elapsed_label.set_text(format_duration(position))
            self.total_label.set_text(format_duration(length))
        self.play_button.set_label("⏸" if playing else "▶")
        if state != self.last_state:
            self.last_state = state
            if state == vlc_wrap.LibVLC.STATE_ENDED and not self.current_is_live:
                self.play_button.set_label("▶")
                if (
                    self.loop_enabled
                    and self.current
                    and self.client
                    and self.current.get("id", -1) >= 0
                ):
                    GLib.idle_add(self.replay_current)
        if self.record_proc is not None and self.record_proc.poll() is not None:
            self.record_proc = None
            self.record_button.set_label("⏺")
            self.set_status(f"Recording finished: {self.record_path}", 5000)

    def replay_current(self):
        if self.current and self.client:
            self.play_item(self.current)
        return False

    # ---------------------------------------------------------------- self test
    def self_test_play(self):
        if self.auto_play_done:
            return False
        self.auto_play_done = True
        if not self.library:
            self.finish_self_test(False, "library is empty")
            return False
        item = self.library[0]
        print(f"[self-test] playing {item.get('title')} ({item.get('id')})", flush=True)
        self.play_item(item)
        GLib.timeout_add(9000, self.self_test_live)
        return False

    def self_test_live(self):
        print("[self-test] testing live mirror", flush=True)
        if not self.client:
            self.finish_self_test(False, "no client for live test")
            return False
        self.on_live()
        GLib.timeout_add(20000, self.self_test_check_live)
        return False

    def self_test_check_live(self):
        if self.current_is_live:
            print("[self-test] live mirror playing", flush=True)
        self.finish_self_test(
            self.current_is_live,
            f"live={self.current_is_live} state={self.player.state_name()}",
        )
        return False

    def finish_self_test(self, ok: bool, detail: str):
        if getattr(self, "_self_test_finished", False):
            return
        self._self_test_finished = True
        print(f"[self-test] {'PASS' if ok else 'FAIL'}: {detail}", flush=True)
        self.finish_self_test_result = ok
        GLib.timeout_add(300, self._self_test_quit)

    def _self_test_quit(self):
        self.on_destroy(None)
        Gtk.main_quit()
        return False

    # ---------------------------------------------------------------- capture
    def take_ui_screenshot(self):
        try:
            window = self.get_window()
            width = window.get_width()
            height = window.get_height()
            pixbuf = Gdk.pixbuf_get_from_window(window, 0, 0, width, height)
            if pixbuf is not None:
                pixbuf.savev(self.screenshot_path, "png", [], [])
                print(f"[screenshot] saved {self.screenshot_path}", flush=True)
            else:
                print("[screenshot] failed: empty pixbuf", flush=True)
        except Exception as error:  # noqa: BLE001 - report to CLI
            print(f"[screenshot] failed: {error}", flush=True)
        GLib.timeout_add(300, self._screenshot_quit)
        return False

    def _screenshot_quit(self):
        self.on_destroy(None)
        Gtk.main_quit()
        return False

    # ---------------------------------------------------------------- keyboard
    def on_key_press(self, widget, event):
        key = event.keyval
        state = event.state
        control = bool(state & Gdk.ModifierType.CONTROL_MASK)
        shift = bool(state & Gdk.ModifierType.SHIFT_MASK)
        name = Gdk.keyval_name(key) or ""

        if key == Gdk.KEY_Escape and self.is_fullscreen:
            self.unfullscreen()
            return True
        if key == Gdk.KEY_space:
            self.on_play_pause()
            return True
        if name.lower() == "s" and shift:
            self.on_snapshot()
            return True
        if name.lower() == "s" and not control:
            self.on_stop()
            return True
        if name.lower() == "p" and not control:
            self.on_previous()
            return True
        if name.lower() == "n" and not control:
            self.on_next()
            return True
        if name.lower() == "f" and not control:
            self.on_toggle_fullscreen()
            return True
        if name.lower() == "m" and not control:
            self.on_toggle_mute()
            return True
        if name.lower() == "r" and control:
            self.on_record()
            return True
        if name.lower() == "l" and not control:
            self.loop_button.set_active(not self.loop_button.get_active())
            return True
        if name.lower() == "r" and not control:
            self.shuffle_button.set_active(not self.shuffle_button.get_active())
            return True
        if name in ("plus", "KP_Add") and control:
            self.bump_volume(5)
            return True
        if name in ("minus", "KP_Subtract") and control:
            self.bump_volume(-5)
            return True
        if key == Gdk.KEY_Right:
            length = self.player.get_length()
            if length > 0:
                self.player.set_time(min(length, self.player.get_time() + (60000 if shift else 10000)))
            return True
        if key == Gdk.KEY_Left:
            length = self.player.get_length()
            if length > 0:
                self.player.set_time(max(0, self.player.get_time() - (60000 if shift else 10000)))
            return True
        return False

    # ------------------------------------------------------------------ misc
    def on_about(self):
        dialog = Gtk.AboutDialog(transient_for=self, modal=True)
        dialog.set_program_name(APP_NAME)
        dialog.set_version("2.0")
        dialog.set_comments(
            "Stream your phone's media library and screen to your desktop.\n"
            "Library playback keeps full audio and supports seeking.\n"
            "Live screen mirroring uses H.264 over RTP."
        )
        dialog.set_license_type(Gtk.License.MIT_X11)
        dialog.set_authors(["Videos contributors"])
        try:
            dialog.set_logo_icon_name("vlc")
        except Exception:
            pass
        dialog.run()
        dialog.destroy()

    def on_quit(self):
        self.on_destroy(None)
        Gtk.main_quit()

    def on_destroy(self, widget):
        if getattr(self, "_destroyed", False):
            return
        self._destroyed = True
        self.stop_recording_if_needed()
        control = self.live_control_client or self.client
        if self.live_owned and control:
            try:
                control.live_stop()
            except Exception:
                pass
        if self.adb_forward_owned and self.adb_forward_port:
            adb_remove_forward(self.adb_forward_port)
            self.adb_forward_owned = False
        try:
            self.player.stop()
            self.player.release()
        except Exception:
            pass
        try:
            if self.media:
                self.media.release()
        except Exception:
            pass
        try:
            self.libvlc.release()
        except Exception:
            pass
        save_settings(self.settings)
        if self.self_test and not self.auto_play_done:
            self.finish_self_test(False, "window closed early")


def parse_args(argv):
    parser = argparse.ArgumentParser(description="VLC-style player for the Videos phone server")
    parser.add_argument("--host", help="phone address (IP or IP:port)")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run an automated connect/library/playback/live check and exit",
    )
    parser.add_argument(
        "--screenshot",
        metavar="FILE",
        help="save a screenshot of the window after loading the library",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv or sys.argv[1:])
    ensure_dirs()
    if args.host:
        settings = load_settings()
        settings["last_host"] = args.host
        save_settings(settings)

    window = VideosWindow(args)
    window.show_all()
    window.playlist_panel.set_visible(True)
    if args.host:
        window.connect_to(args.host)

    try:
        Gtk.main()
    except KeyboardInterrupt:
        window.on_destroy(None)
    if args.self_test:
        return 0 if getattr(window, "finish_self_test_result", False) else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
