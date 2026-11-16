"""HTTP client and LAN discovery for the Videos phone server."""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

BEACON_PORT = 8099
DEFAULT_HTTP_PORT = 8080
DEFAULT_USB_PORT = 18080


class PhoneError(RuntimeError):
    pass


def format_duration(milliseconds) -> str:
    total = max(0, int((milliseconds or 0) / 1000))
    hours, remainder = divmod(total, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{seconds:02d}"
    return f"{minutes}:{seconds:02d}"


def format_size(size) -> str:
    value = float(size or 0)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{value:.1f} TB"


def discover_phones(timeout: float = 3.0, port: int = BEACON_PORT):
    """Listen for UDP beacons and return {url: info} for each phone found."""
    found = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except (AttributeError, OSError):
            pass
        sock.bind(("", port))
        sock.settimeout(0.4)
        deadline = time.time() + max(0.5, timeout)
        while time.time() < deadline:
            try:
                payload, address = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                info = json.loads(payload.decode("utf-8", "replace"))
            except ValueError:
                continue
            if info.get("app") != "videos":
                continue
            url = info.get("url")
            if not url:
                continue
            info["address"] = address[0]
            found[url] = info
    finally:
        sock.close()
    return found


def probe_host(host: str, port: int = DEFAULT_HTTP_PORT, timeout: float = 4.0):
    client = PhoneClient(host, port, timeout=timeout)
    return client.info()


def parse_host(value: str):
    """Accepts 1.2.3.4, 1.2.3.4:8080 or http://1.2.3.4:8080/."""
    value = (value or "").strip()
    if not value:
        raise ValueError("empty address")
    if "://" not in value:
        value = "http://" + value
    parsed = urllib.parse.urlparse(value)
    if not parsed.hostname:
        raise ValueError("invalid address")
    return parsed.hostname, parsed.port or DEFAULT_HTTP_PORT


# ------------------------------------------------------------------------- usb
def adb_path() -> str:
    """Locate adb (honours $ADB, then $PATH)."""
    configured = os.environ.get("ADB", "adb")
    if os.path.isabs(configured):
        return configured if os.path.isfile(configured) else None
    found = shutil.which(configured)
    if found:
        return found
    return configured if os.path.isfile(configured) else None


def adb_devices() -> list[tuple[str, str]]:
    """Return [(serial, state)] from `adb devices`, state is device/unauthorized/offline."""
    adb = adb_path()
    if not adb:
        raise PhoneError(
            "adb was not found. Install Android platform-tools (or set $ADB) "
            "to use USB streaming."
        )
    try:
        result = subprocess.run(
            [adb, "devices"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise PhoneError(f"adb failed: {error}") from None
    devices = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            devices.append((parts[0], parts[1]))
    return devices


def adb_forward(
    local_port: int = DEFAULT_USB_PORT,
    remote_port: int = DEFAULT_HTTP_PORT,
    serial: str | None = None,
):
    """Forward a local TCP port to the phone's HTTP server over USB."""
    adb = adb_path()
    if not adb:
        raise PhoneError("adb was not found; install Android platform-tools first")
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += ["forward", f"tcp:{int(local_port)}", f"tcp:{int(remote_port)}"]
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise PhoneError(f"adb forward failed: {error}") from None
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "unknown adb error").strip()
        raise PhoneError(f"adb forward failed: {detail}")
    return int(local_port)


def adb_remove_forward(local_port: int = DEFAULT_USB_PORT, serial: str | None = None):
    adb = adb_path()
    if not adb:
        return
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += ["forward", "--remove", f"tcp:{int(local_port)}"]
    try:
        subprocess.run(
            command, capture_output=True, text=True, timeout=10, check=False
        )
    except (OSError, subprocess.SubprocessError):
        pass


def connect_usb(
    local_port: int = DEFAULT_USB_PORT,
    remote_port: int = DEFAULT_HTTP_PORT,
    timeout: float = 8.0,
    serial: str | None = None,
):
    """Prepare `adb forward` and return (PhoneClient, info) for USB streaming."""
    devices = adb_devices()
    if not devices:
        raise PhoneError(
            "No USB device found. Connect the phone with USB debugging enabled."
        )
    if serial is None:
        ready = [item for item in devices if item[1] == "device"]
        if not ready:
            states = ", ".join(f"{name}: {state}" for name, state in devices)
            raise PhoneError(f"Phone is not ready over USB ({states}).")
        serial = ready[0][0]
    matches = [item for item in devices if item[0] == serial]
    if not matches or matches[0][1] != "device":
        raise PhoneError(f"USB device {serial} is not ready.")
    adb_forward(local_port, remote_port, serial)
    client = PhoneClient("127.0.0.1", local_port, timeout=timeout)
    try:
        info = client.info()
    except Exception:
        adb_remove_forward(local_port, serial)
        raise
    info.setdefault("serial", serial)
    info["usb"] = True
    return client, info


class PhoneClient:
    def __init__(self, host: str, port: int = DEFAULT_HTTP_PORT, timeout: float = 8.0):
        self.host = host
        self.port = int(port)
        self.timeout = timeout

    @classmethod
    def from_url(cls, url: str, timeout: float = 8.0):
        host, port = parse_host(url)
        return cls(host, port, timeout=timeout)

    # ------------------------------------------------------------------ urls
    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    @property
    def is_local(self) -> bool:
        """True when the phone is reached through `adb forward` (USB)."""
        return self.host in ("127.0.0.1", "localhost", "::1")

    def media_url(self, video_id) -> str:
        return f"{self.base_url}/media?id={int(video_id)}"

    def thumbnail_url(self, video_id) -> str:
        return f"{self.base_url}/api/thumbnail?id={int(video_id)}"

    @property
    def live_stream_url(self) -> str:
        return f"{self.base_url}/api/live/stream.ts"

    # --------------------------------------------------------------- request
    def _request(self, path: str, method: str = "GET", timeout=None) -> bytes:
        url = self.base_url + path
        request = urllib.request.Request(
            url,
            method=method,
            headers={"User-Agent": "Videos-Linux/2.0", "Accept": "*/*"},
        )
        try:
            with urllib.request.urlopen(
                request, timeout=timeout or self.timeout
            ) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            body = error.read()[:300].decode("utf-8", "replace")
            raise PhoneError(f"{error.code} {error.reason}: {body}") from None
        except urllib.error.URLError as error:
            raise PhoneError(f"Cannot reach {self.host}: {error.reason}") from None
        except OSError as error:
            raise PhoneError(f"Cannot reach {self.host}: {error}") from None

    def _json(self, path: str, method: str = "GET", timeout=None):
        raw = self._request(path, method=method, timeout=timeout)
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError as error:
            raise PhoneError(f"Bad response from phone: {error}") from None

    # -------------------------------------------------------------------- api
    def info(self):
        return self._json("/api/info", timeout=4.0)

    def library(self):
        data = self._json("/api/library", timeout=25.0)
        return data.get("items", [])

    def thumbnail(self, video_id) -> bytes:
        return self._request(f"/api/thumbnail?id={int(video_id)}", timeout=20.0)

    # --------------------------------------------------------------- live api
    def live_status(self):
        return self._json("/api/live/status", timeout=4.0)

    def live_start(
        self,
        video_port: int = 5004,
        audio_port: int = 5006,
        video_bitrate: int = 6_000_000,
        mode: str | None = None,
    ):
        params = {
            "videoPort": int(video_port),
            "audioPort": int(audio_port),
            "videoBitrate": int(video_bitrate),
        }
        if mode:
            params["mode"] = mode
        query = urllib.parse.urlencode(params)
        return self._json(f"/api/live/start?{query}", method="POST", timeout=8.0)

    def live_stop(self):
        return self._json("/api/live/stop", method="POST", timeout=6.0)

    def live_record(self, enabled: bool):
        return self._json(
            f"/api/live/record?on={1 if enabled else 0}", method="POST", timeout=6.0
        )

    def live_sdp(self) -> str:
        raw = self._request("/api/live/sdp", timeout=8.0)
        text = raw.decode("utf-8", "replace")
        if not text.startswith("v=0"):
            raise PhoneError("Phone did not return a valid SDP description")
        return text
