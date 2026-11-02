"""Authenticated, bounded HTTP count endpoint; no inference or proxy routes."""

from __future__ import annotations

import argparse
import hmac
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from contract import Counter, MAX_BODY, Refused, parse_request, strict_json
from runtime import LmStudioBindingReader, MlxCounter, Settings


class CountServer(HTTPServer):
    """At most two connections, no work queue; the processor itself admits one.

    Bounding before creating threads prevents slow clients from accumulating
    unbounded readers. An excess socket is closed with a fixed 503 response.
    Reverse proxies must retain the same bounds and disable request-body logs.
    """

    request_queue_size = 2

    def __init__(self, address, counter: Counter, token: str):
        if not token:
            raise Refused("authentication_required")
        self.counter, self.token = counter, token
        self.slots = threading.BoundedSemaphore(2)
        self.threads = set()
        self.thread_lock = threading.Lock()
        if ":" in address[0]:
            self.address_family = socket.AF_INET6
        super().__init__(address, CountHandler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(0.1)
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                )
            except OSError:
                pass  # The rejected peer may already have disconnected.
            finally:
                self.shutdown_request(request)
            return

        def run():
            try:
                self.finish_request(request, client_address)
            except (OSError, ValueError):
                pass  # A malformed/disconnected connection has no retry or retained effect.
            finally:
                self.shutdown_request(request)
                with self.thread_lock:
                    self.threads.discard(threading.current_thread())
                self.slots.release()

        thread = threading.Thread(target=run, daemon=True)
        with self.thread_lock:
            self.threads.add(thread)
        thread.start()


class CountHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def setup(self):
        self.request.settimeout(5)
        super().setup()

    def log_message(self, *_):
        # BaseHTTPRequestHandler otherwise records request targets and peer details.
        pass

    def reply(self, status, body):
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        offered = self.headers.get_all("Authorization", [])
        if len(offered) != 1 or not hmac.compare_digest(
            offered[0].encode("utf-8"), ("Bearer " + self.server.token).encode("utf-8")
        ):
            self.reply(401, {"error": "authentication_required"})
            return
        if self.path != "/v1/chat/completions/input_tokens":
            self.reply(404, {"error": "unsupported_route"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if (
            self.headers.get("Transfer-Encoding") is not None
            or len(lengths) != 1
            or len(lengths[0]) > 10
            or not lengths[0].isascii()
            or not lengths[0].isdecimal()
            or self.headers.get("Content-Type", "").split(";", 1)[0].strip()
            != "application/json"
        ):
            self.reply(400, {"error": "invalid_request_framing"})
            return
        length = int(lengths[0])
        if not 0 < length <= MAX_BODY:
            self.reply(413, {"error": "request_byte_bound"})
            return
        try:
            deadline = time.monotonic() + 5
            chunks, remaining = [], length
            while remaining:
                budget = deadline - time.monotonic()
                if budget <= 0:
                    raise Refused("request_timeout")
                self.connection.settimeout(budget)
                chunk = self.rfile.read1(min(65536, remaining))
                if not chunk:
                    raise Refused("incomplete_request")
                chunks.append(chunk)
                remaining -= len(chunk)
            raw = b"".join(chunks)
            request = parse_request(raw)
            result = self.server.counter.count(request)
            self.reply(
                200,
                {
                    "object": "response.input_tokens",
                    "model": result.model,
                    "revision": result.revision,
                    "input_tokens": result.input_tokens,
                },
            )
        except Refused as error:
            self.reply(
                (
                    503
                    if str(error)
                    in {"counter_busy", "binding_unavailable", "binding_timeout"}
                    else 422
                ),
                {"error": str(error)},
            )
        except Exception:
            # Never return exception text: native libraries can quote prompt bytes,
            # local paths or credentials. A failure is not a zero-token success.
            self.reply(503, {"error": "counter_unavailable"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument(
        "--inspect",
        action="store_true",
        help="Print the pinned revision without listening",
    )
    args = parser.parse_args()
    try:
        if args.config.stat().st_size > 64 * 1024:
            raise Refused("configuration_byte_bound")
        settings = Settings.parse(strict_json(args.config.read_bytes()))
        counter = MlxCounter(settings, LmStudioBindingReader(settings))
        if args.inspect:
            print(json.dumps({"model": settings.model, "revision": counter.revision}))
            return
        if settings.revision is None:
            raise Refused("explicit_verified_revision_required")
        with CountServer(
            (settings.listen_host, settings.listen_port), counter, settings.token
        ) as server:
            print(
                json.dumps(
                    {
                        "ready": True,
                        "port": server.server_port,
                        "model": settings.model,
                        "revision": counter.revision,
                    }
                ),
                flush=True,
            )
            server.serve_forever()
    except Refused as error:
        parser.exit(1, str(error) + "\n")
    except KeyboardInterrupt:
        pass
    except Exception:
        parser.exit(1, "counter_startup_unavailable\n")


if __name__ == "__main__":
    main()
