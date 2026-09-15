"""Client for the DSH Phone Agent control channel.

The wire protocol is deliberately boring: one JSON request per line, one JSON
reply per line, over a plain TCP socket. That keeps the PC side dependency-free
and makes the channel trivially inspectable with telnet or netcat when something
misbehaves.

    from phone_client import PhoneClient

    with PhoneClient("192.168.1.23") as phone:
        print(phone.info())
        phone.tap(540, 1200)
"""

from __future__ import annotations

import base64
import json
import socket
from typing import Any, Optional

DEFAULT_PORT = 7912


class PhoneAgentError(RuntimeError):
    """Raised when the agent reports a failure or the link breaks."""


class PhoneClient:
    """Synchronous, single-connection client.

    Not thread-safe: the protocol serialises device operations per connection, so
    concurrent callers must supply their own locking or their own client.
    """

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        timeout: float = 30.0,
        token: str = "",
    ) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.token = token
        self._sock: Optional[socket.socket] = None
        self._fp = None
        self._seq = 0

    # ------------------------------------------------------------- lifecycle

    def connect(self) -> "PhoneClient":
        if self._sock is not None:
            return self
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._sock = sock
        self._fp = sock.makefile("rwb")
        return self

    def close(self) -> None:
        if self._fp is not None:
            try:
                self._fp.close()
            except OSError:
                pass
            self._fp = None
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def __enter__(self) -> "PhoneClient":
        return self.connect()

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # ---------------------------------------------------------------- calling

    def call(self, cmd: str, **params: Any) -> dict:
        """Send one command and return its `data` payload."""
        if self._sock is None:
            self.connect()
        self._seq += 1
        request = {"id": self._seq, "cmd": cmd}
        if self.token:
            request["token"] = self.token
        request.update({k: v for k, v in params.items() if v is not None})

        payload = (json.dumps(request, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            assert self._fp is not None
            self._fp.write(payload)
            self._fp.flush()
            line = self._fp.readline()
        except OSError as exc:
            self.close()
            raise PhoneAgentError(f"link failed: {exc}") from exc

        if not line:
            self.close()
            raise PhoneAgentError("phone closed the connection")

        reply = json.loads(line.decode("utf-8"))
        if not reply.get("ok"):
            raise PhoneAgentError(reply.get("error", "unknown error"))
        return reply.get("data", {})

    # --------------------------------------------------------------- commands

    def ping(self) -> dict:
        return self.call("ping")

    def info(self) -> dict:
        return self.call("info")

    def observe(
        self,
        include_ui: bool = True,
        image_format: str = "jpeg",
        quality: int = 82,
    ) -> dict:
        """One round trip for both the frame and the UI tree."""
        return self.call(
            "observe",
            includeUi=include_ui,
            format=image_format,
            quality=quality,
        )

    def screenshot(self, image_format: str = "jpeg", quality: int = 82) -> bytes:
        data = self.call("screenshot", format=image_format, quality=quality)
        return base64.b64decode(data["image"])

    def ui_tree(self, max_depth: int = 30, max_nodes: int = 2000) -> dict:
        return self.call("uitree", maxDepth=max_depth, maxNodes=max_nodes)

    def tap(self, x: float, y: float, human: bool = True) -> dict:
        """Tap at (x, y). `human=False` emits the straight automation stroke, for A/B work."""
        return self.call("tap", x=x, y=y, human=human)

    def long_press(self, x: float, y: float, hold_ms: int = 650) -> dict:
        return self.call("longpress", x=x, y=y, holdMs=hold_ms)

    def swipe(
        self,
        x1: float,
        y1: float,
        x2: float,
        y2: float,
        duration_ms: int = 320,
        human: bool = True,
    ) -> dict:
        return self.call(
            "swipe",
            x1=x1, y1=y1, x2=x2, y2=y2,
            durationMs=duration_ms,
            human=human,
        )

    def key(self, name: str) -> dict:
        """One of BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER, LOCK, SCREENSHOT."""
        return self.call("key", key=name)

    def text(self, value: str) -> dict:
        """Type into the focused editable field. Handles Unicode without an IME."""
        return self.call("text", text=value)

    def launch(self, package: str) -> dict:
        return self.call("launch", package=package)

    def wait(self, ms: int) -> dict:
        return self.call("wait", ms=ms)


def _demo(host: str) -> None:
    """Tiny smoke test: python phone_client.py <phone-ip>"""
    with PhoneClient(host) as phone:
        print("info:", json.dumps(phone.info(), ensure_ascii=False, indent=2))
        frame = phone.observe(include_ui=False)
        print(f"frame: {frame['imageWidth']}x{frame['imageHeight']} ({frame['imageFormat']})")
        tree = phone.ui_tree(max_depth=6, max_nodes=200)
        print(f"ui: {tree['nodeCount']} nodes, truncated={tree['truncated']}")


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit("usage: python phone_client.py <phone-ip> [port]")
    _demo(sys.argv[1])
