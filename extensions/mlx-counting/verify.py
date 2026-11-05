"""Explicit live acceptance: counts then real inference, never enables admission.

Run with the serving model's installed Python and a deployment-owned config.
Generates synthetic media in memory and makes eight requests, each capped at
four output tokens. Output contains usage/count metadata only, never media,
prompts, generated content, credentials or private endpoint/path details.
"""

import argparse
import base64
import io
import json
import ipaddress
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

from contract import Count, Refused, strict_json
from runtime import LmStudioBindingReader, MlxCounter, Settings, digest
from service import CountServer


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def verify(settings, base_url):
    parsed, ws = urlsplit(base_url), urlsplit(settings.upstream_ws_url)
    if (
        parsed.scheme not in ("http", "https")
        or not parsed.hostname
        or parsed.hostname != ws.hostname
        or parsed.port != ws.port
        or (parsed.scheme == "https") != (ws.scheme == "wss")
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise Refused("verification_origin_mismatch")
    counter = MlxCounter(settings, LmStudioBindingReader(settings))
    if (
        not ipaddress.ip_address(settings.listen_host).is_loopback
        or settings.listen_port != 0
    ):
        raise Refused("verification_requires_explicit_loopback_and_ephemeral_port")
    with CountServer(
        (settings.listen_host, settings.listen_port), counter, settings.token
    ) as server:
        worker = threading.Thread(target=server.serve_forever)
        worker.start()
        host = settings.listen_host
        authority = f"[{host}]" if ":" in host else host
        count_url = (
            f"http://{authority}:{server.server_port}/v1/chat/completions/input_tokens"
        )
        try:
            return verify_cases(settings, base_url, counter, count_url)
        finally:
            server.shutdown()
            worker.join()


def verify_cases(settings, base_url, counter, count_url):
    from PIL import Image

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    results = []
    cases = [
        ("png", (256, 256), 1),
        ("png", (256, 256), 1),
        ("jpeg", (256, 256), 1),
        ("webp", (256, 256), 1),
        ("png", (1920, 1080), 1),
        ("png", (256, 256), 2),
        ("png", (256, 256), 1),
        ("png", (256, 256), 1),
    ]
    for index, (fmt, size, n) in enumerate(cases):
        encoded = io.BytesIO()
        Image.new("RGB", size, (11, 71, 173)).save(encoded, format=fmt.upper())
        uri = (
            "data:image/"
            + fmt
            + ";base64,"
            + base64.b64encode(encoded.getvalue()).decode()
        )
        parts = [{"type": "image_url", "image_url": {"url": uri}} for _ in range(n)]
        parts.append({"type": "text", "text": "Describe the test image."})
        messages = [{"role": "user", "content": parts}]
        body = {
            "model": settings.model,
            "messages": messages,
            "temperature": 0,
            "max_tokens": 4,
            "chat_template_kwargs": {"enable_thinking": False},
        }
        if index == 6:
            messages.insert(
                0, {"role": "system", "content": "Answer the synthetic test briefly."}
            )
        if index == 7:
            body["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": "inspect_image",
                        "description": "Inspect a test image.",
                        "parameters": {"type": "object", "properties": {}},
                    },
                }
            ]
            body["tool_choice"] = "auto"
        raw = json.dumps(body).encode()
        count_request = urllib.request.Request(
            count_url,
            data=raw,
            headers={
                "Authorization": "Bearer " + settings.token,
                "Content-Type": "application/json",
            },
        )
        with opener.open(count_request, timeout=10) as response:
            observed = strict_json(response.read(4096))
        measured = Count(
            observed["model"], observed["revision"], observed["input_tokens"]
        )
        if measured.revision != counter.revision or measured.model != settings.model:
            raise Refused("verification_count_binding_mismatch")
        request = urllib.request.Request(
            base_url.rstrip("/") + "/chat/completions",
            data=raw,
            headers={
                "Authorization": "Bearer " + settings.token,
                "Content-Type": "application/json",
            },
        )
        started = time.monotonic()
        result = {
            "case": index + 1,
            "format": fmt,
            "dimensions": size,
            "images": n,
            "request_digest": digest(body),
            "counted_input_tokens": measured.input_tokens,
        }
        try:
            with opener.open(request, timeout=45) as response:
                data = strict_json(response.read(1024 * 1024 + 1))
                usage = data.get("usage", {})
                reported = usage.get("prompt_tokens")
                result.update(
                    reported_input_tokens=reported,
                    output_tokens=usage.get("completion_tokens"),
                    cached_input_tokens=usage.get("prompt_tokens_details", {}).get(
                        "cached_tokens"
                    ),
                    equal=type(reported) is int and reported == measured.input_tokens,
                    model_matches=data.get("model") == settings.model,
                )
        except urllib.error.HTTPError as error:
            result.update(equal=False, error_type="HTTPError", http_status=error.code)
            error.close()
        except Exception as error:
            result.update(equal=False, error_type=type(error).__name__)
        result["elapsed_ms"] = round((time.monotonic() - started) * 1000)
        results.append(result)
    passed = all(r.get("equal") and r.get("model_matches") for r in results)
    return {
        "model": settings.model,
        "revision": counter.revision,
        "image_usage_verified": passed,
        "counter_http_verified": True,
        "admission_enabled": False,
        "results": results,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    try:
        settings = Settings.parse(strict_json(args.config.read_bytes()))
        report = verify(settings, args.base_url)
        print(json.dumps(report, indent=2))
        raise SystemExit(0 if report["image_usage_verified"] else 2)
    except Refused as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
