"""Local, bounded device profiles from DNS queries in observed association windows."""

from dataclasses import dataclass, field
from typing import Literal

from .contracts import DnsObservation

DestinationKind = Literal["streaming", "advertising", "measurement", "unclassified"]

# Exact suffix boundaries prevent a lookalike such as doubleclick.net.example
# from inheriting a provider's classification. See OPERATIONS.md for references.
_SUFFIXES: tuple[tuple[str, DestinationKind, str], ...] = (
    ("youtube.com", "streaming", "YouTube"),
    ("youtu.be", "streaming", "YouTube"),
    ("youtube-nocookie.com", "streaming", "YouTube"),
    ("googlevideo.com", "streaming", "Google video delivery"),
    ("doubleclick.net", "advertising", "Google Ads"),
    ("googlesyndication.com", "advertising", "Google Ads"),
    ("adnxs.com", "advertising", "Microsoft Advertising"),
    ("adnxs-simple.com", "advertising", "Microsoft Advertising"),
    ("adsrvr.org", "advertising", "The Trade Desk"),
    ("google-analytics.com", "measurement", "Google Analytics"),
)
DISPLAY_LIMIT = 8


def classify(domain: str) -> tuple[DestinationKind, str | None]:
    for suffix, kind, provider in _SUFFIXES:
        if domain == suffix or domain.endswith("." + suffix):
            return kind, provider
    return "unclassified", None


@dataclass(frozen=True)
class QuerySample:
    dns: tuple[DnsObservation, ...]
    read: int
    available: int
    issues: tuple[str, ...]
    blocked: dict[str, int] = field(default_factory=dict)


@dataclass(frozen=True)
class DnsActivityWindow:
    device: str
    from_at: str
    sample: QuerySample


@dataclass(frozen=True)
class Destination:
    domain: str
    provider: str | None
    queries: int
    blocked: int


@dataclass(frozen=True)
class DestinationGroup:
    kind: DestinationKind
    queries: int
    distinct: int
    destinations: tuple[Destination, ...]


@dataclass(frozen=True)
class DnsActivity:
    from_at: str
    until_at: str
    windows: int
    partial_windows: int
    queries_read: int
    queries_available: int
    groups: tuple[DestinationGroup, ...]


def group_destinations(
    domains: list[tuple[str, int, int]],
) -> tuple[DestinationGroup, ...]:
    """Preserve category totals even when only the top destinations are displayed."""
    groups: dict[DestinationKind, list[Destination]] = {}
    for domain, count, blocked in sorted(domains, key=lambda item: (-item[1], item[0])):
        kind, provider = classify(domain)
        groups.setdefault(kind, []).append(
            Destination(domain, provider, count, blocked)
        )
    return tuple(
        DestinationGroup(
            kind,
            sum(item.queries for item in group),
            len(group),
            tuple(group[:DISPLAY_LIMIT]),
        )
        for kind, group in sorted(groups.items())
    )
