"""Explicit public-model searches. The collector never exports device observations."""

import asyncio
import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Protocol
from urllib.parse import urlencode, urlsplit

from aiohttp import ClientError, ClientSession, ClientTimeout
from yarl import URL

from .contracts import integer, items, object_fields, parse_json, text, utc_now

_PAGE_SIZE = 10
_MAX_RESPONSE_BYTES = 1_048_576
_CVE = re.compile(r"CVE-[0-9]{4}-[0-9]{4,19}")
_MODEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9 ._()+/-]{2,127}")


class AdvisoryUnavailable(ValueError):
    """The upstream lookup did not produce a usable page."""


@dataclass(frozen=True)
class Advisory:
    cve: str
    description: str
    published_on: str
    severity: str | None
    known_exploited: bool


@dataclass(frozen=True)
class AdvisoryPage:
    model: str
    checked_at: str
    total: int
    advisories: tuple[Advisory, ...]


class AdvisoryLookup(Protocol):
    """Find public references for a reviewed model name, without deciding applicability."""

    async def search(self, model: str) -> AdvisoryPage: ...


def public_model(value: str) -> str:
    model = value.strip()
    if (
        not _MODEL.fullmatch(model)
        or not re.search(r"[A-Za-z]", model)
        or re.search(
            r"\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b|\.(?:local|lan|home\.arpa)\b"
            r"|\b(?:[0-9a-f]{2}-){5}[0-9a-f]{2}\b",
            model,
            re.I,
        )
    ):
        raise ValueError(
            "Save the public manufacturer and model name before looking up advisories"
        )
    return model


class NvdAdvisories:
    def __init__(self, origin: str):
        endpoint = urlsplit(text(origin, 512))
        if (
            endpoint.scheme != "https"
            or not endpoint.hostname
            or endpoint.username is not None
            or endpoint.password is not None
            or endpoint.path not in ("", "/")
            or endpoint.query
            or endpoint.fragment
            or any(c.isspace() for c in origin)
        ):
            raise ValueError("Set an explicit HTTPS NVD API origin")
        self._endpoint = URL(origin).with_path("/rest/json/cves/2.0")
        self._lookup_lock = asyncio.Lock()

    async def search(self, model: str) -> AdvisoryPage:
        model = public_model(model)
        if self._lookup_lock.locked():
            raise AdvisoryUnavailable(
                "Another advisory lookup is running. Try again after it finishes."
            )
        async with self._lookup_lock:
            query = urlencode({"keywordSearch": model, "resultsPerPage": _PAGE_SIZE})
            # NVD defines these as presence flags. This query comes solely from a
            # reviewed public product name, and redirects never forward it elsewhere.
            url = URL(
                f"{self._endpoint}?{query}&keywordExactMatch&noRejected", encoded=True
            )
            try:
                async with ClientSession(timeout=ClientTimeout(total=10)) as session:
                    async with session.get(url, allow_redirects=False) as response:
                        if response.status != 200:
                            raise AdvisoryUnavailable(
                                "NVD did not answer the lookup. Check connectivity or try again later."
                            )
                        body = bytearray()
                        async for chunk in response.content.iter_chunked(16384):
                            body.extend(chunk)
                            if len(body) > _MAX_RESPONSE_BYTES:
                                raise AdvisoryUnavailable(
                                    "The NVD response exceeded the lookup limit."
                                )
                total, records = _decode_page(parse_json(bytes(body)))
            except AdvisoryUnavailable:
                raise
            except (ClientError, TimeoutError, ValueError):
                raise AdvisoryUnavailable(
                    "The NVD response could not be read or validated."
                ) from None
            return AdvisoryPage(model, utc_now(), total, records)


def _decode_page(value: object) -> tuple[int, tuple[Advisory, ...]]:
    page = object_fields(
        value,
        {"totalResults", "vulnerabilities"},
        {"resultsPerPage", "startIndex", "format", "version", "timestamp"},
    )
    entries = items(page["vulnerabilities"], _PAGE_SIZE)
    total = integer(page["totalResults"], len(entries), 2**31 - 1)
    records = []
    for entry in entries:
        row = object_fields(entry, {"cve"})["cve"]
        # Project only the fields used by the UI. Applicability trees, provider
        # links and all other external metadata stay at this boundary.
        if not isinstance(row, dict):
            raise ValueError("Expected CVE metadata")
        cve = text(row.get("id"), 32)
        if not _CVE.fullmatch(cve):
            raise ValueError("Invalid CVE identifier")
        published = datetime.fromisoformat(text(row.get("published"), 64)).date()
        exploited = row.get("cisaExploitAdd")
        if exploited is not None:
            date.fromisoformat(text(exploited, 10))
        descriptions = [
            object_fields(value, {"lang", "value"})
            for value in items(row.get("descriptions"), 32)
        ]
        summary = next(
            (value["value"] for value in descriptions if value["lang"] == "en"),
            "No English summary supplied.",
        )
        if not isinstance(summary, str) or not summary.strip() or len(summary) > 4096:
            raise ValueError("Invalid advisory summary")
        records.append(
            Advisory(
                cve,
                text(" ".join(summary.split())[:600], 600),
                published.isoformat(),
                _severity(row.get("metrics")),
                exploited is not None,
            )
        )
    return total, tuple(records)


def _severity(value: object) -> str | None:
    if not isinstance(value, dict):
        return None
    for version in ("cvssMetricV40", "cvssMetricV31", "cvssMetricV30"):
        metrics = value.get(version)
        if not isinstance(metrics, list):
            continue
        for metric in metrics:
            if not isinstance(metric, dict) or metric.get("type") != "Primary":
                continue
            data = metric.get("cvssData")
            severity = data.get("baseSeverity") if isinstance(data, dict) else None
            if isinstance(severity, str) and severity in {
                "NONE",
                "LOW",
                "MEDIUM",
                "HIGH",
                "CRITICAL",
            }:
                return severity
    return None
