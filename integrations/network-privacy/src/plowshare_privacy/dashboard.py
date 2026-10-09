"""Private local dashboard handoff; contains no Plowshare service credential."""

from __future__ import annotations

import asyncio
import json
import os
import webbrowser
from dataclasses import dataclass
from ipaddress import ip_address
from pathlib import Path
from urllib.parse import urlencode, urlsplit

from aiohttp import ClientConnectionError, ClientSession, ClientTimeout

from .contracts import load_json, object_fields, parse_json, text, uuid
from .monitor import replace_private


@dataclass(frozen=True)
class RunningDashboard:
    instance: str
    origin: str
    token: str

    @classmethod
    def read(cls, path: Path) -> RunningDashboard:
        if (
            path.is_symlink()
            or path.resolve() != path
            or os.name == "posix"
            and path.stat().st_mode & 0o077
        ):
            raise ValueError("Dashboard handoff requires an unlinked private file")
        row = object_fields(load_json(path), {"instance", "origin", "token"})
        origin = text(row["origin"], 2048)
        parsed = urlsplit(origin)
        if (
            parsed.scheme != "http"
            or not parsed.hostname
            or not ip_address(parsed.hostname).is_loopback
            or parsed.port is None
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.path
        ):
            raise ValueError("Dashboard handoff needs an explicit loopback origin")
        token = text(row["token"], 128)
        if len(token) < 24 or any(character.isspace() for character in token):
            raise ValueError("Invalid dashboard handoff credential")
        return cls(uuid(row["instance"]), origin, token)

    def write(self, path: Path) -> None:
        replace_private(
            path,
            json.dumps(
                {"instance": self.instance, "origin": self.origin, "token": self.token}
            )
            + "\n",
        )

    async def open(self) -> None:
        opened = await asyncio.to_thread(
            webbrowser.open, self.origin + "/#" + urlencode({"token": self.token})
        )
        if not opened:
            raise ValueError(
                "No browser opened; configure a browser or use explicit serve mode"
            )


async def reopen(path: Path) -> bool:
    """Reopen only a positively identified live instance; stale state starts nothing.

    Read-only HTTP verifies the per-process nonce before using a saved handoff.
    A normal close removes the file. A crash leaves it private and a later start
    can discover absence without resubmitting any Plowshare mutation.
    """
    if not path.exists():
        return False
    dashboard = RunningDashboard.read(path)
    try:
        async with ClientSession(timeout=ClientTimeout(total=2)) as session:
            async with session.get(
                dashboard.origin + "/api/session/status",
                headers={"Authorization": "Bearer " + dashboard.token},
                allow_redirects=False,
            ) as response:
                if response.status != 200:
                    return False
                row = object_fields(
                    parse_json(await response.content.read(4097)), {"instance"}
                )
                if row["instance"] != dashboard.instance:
                    return False
    except (ClientConnectionError, TimeoutError):
        return False
    await dashboard.open()
    return True
