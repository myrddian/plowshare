"""Container composition: file-mounted secrets and explicit listener settings.

The image contains no installation configuration. Only the child application's
environment receives the bearer; it never becomes a Docker environment value.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from .cli import main
from .model import Configuration, text


def required(name: str) -> str:
    return text(os.environ.get(name), name, 4096)


def secret(name: str) -> str:
    path = Path(required(name))
    if not path.is_absolute() or path.stat().st_size > 1024:
        raise ValueError(f"Configure a bounded absolute secret file for {name}")
    value = path.read_text(encoding="utf-8").removesuffix("\n")
    if not value or value != value.strip() or any(c.isspace() for c in value):
        raise ValueError(f"Invalid secret file for {name}")
    return value


def health() -> None:
    """Probe this listener directly, without ambient HTTP proxies or secrets."""
    origin = required("AGILE_HEALTH_ORIGIN")
    url = urlsplit(origin)
    if (
        url.scheme != "http"
        or not url.hostname
        or url.username
        or url.password
        or url.path
        or url.query
        or url.fragment
    ):
        raise ValueError("Configure an explicit internal HTTP health origin")
    public = urlsplit(required("AGILE_PUBLIC_ORIGIN"))
    request = Request(origin + "/health", headers={"Host": public.netloc})
    with build_opener(ProxyHandler({})).open(request, timeout=12) as response:
        if response.status != 204:
            raise ValueError("Application is not ready")


def run() -> None:
    if sys.argv[1:] == ["health"]:
        health()
        return
    if sys.argv[1:]:
        raise ValueError("Container entrypoint takes no arguments (except health)")
    path = Path(required("AGILE_CONFIG"))
    if not path.is_absolute():
        raise ValueError("Configure an absolute AGILE_CONFIG path")
    config = Configuration.read(path)
    if config.token_environment == "AGILE_WEB_ACCESS_KEY":
        raise ValueError("The bearer and web key need separate environment names")
    token = secret("AGILE_TOKEN_FILE")
    access_key = secret("AGILE_WEB_ACCESS_KEY_FILE")
    if token == access_key:
        raise ValueError("Use a separate web access key, not the Plowshare bearer")
    os.environ[config.token_environment] = token
    os.environ["AGILE_WEB_ACCESS_KEY"] = access_key
    sys.argv = [
        "excalibur",
        "--config",
        str(path),
        "serve",
        "--bind",
        required("AGILE_BIND"),
        "--port",
        required("AGILE_PORT"),
        "--public-origin",
        required("AGILE_PUBLIC_ORIGIN"),
        "--access-token-environment",
        "AGILE_WEB_ACCESS_KEY",
    ]
    main()


if __name__ == "__main__":
    try:
        run()
    except (OSError, ValueError):
        # Secret file contents and private paths must not appear in Docker logs.
        print(
            "Excalibur container configuration or readiness check failed",
            file=sys.stderr,
        )
        raise SystemExit(2) from None
