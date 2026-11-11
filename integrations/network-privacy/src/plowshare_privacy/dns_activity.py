"""Local, bounded device profiles from DNS queries in observed association windows."""

from dataclasses import dataclass, field
from typing import Literal

from .contracts import DnsObservation

DestinationKind = Literal[
    "advertising",
    "measurement",
    "telemetry",
    "streaming",
    "unclassified",
    "connectivity",
    "local",
]

# Display order: destinations that can carry behavioural data about the
# household come first, then ordinary service use, then network plumbing.
GROUP_ORDER: tuple[DestinationKind, ...] = (
    "advertising",
    "measurement",
    "telemetry",
    "streaming",
    "unclassified",
    "connectivity",
    "local",
)

# When a window holds more destinations than the journal retains, these ranks
# decide what is kept. Behavioural destinations are never dropped in favour of
# connectivity probes or reverse lookups, which are frequent and uninformative.
_RETENTION_RANK: dict[DestinationKind, int] = {
    "advertising": 0,
    "measurement": 0,
    "telemetry": 0,
    "streaming": 1,
    "unclassified": 1,
    "connectivity": 2,
    "local": 2,
}

# Exact suffix boundaries prevent a lookalike such as doubleclick.net.example
# from inheriting a provider's classification. When suffixes nest, the longest
# match wins, so logs.netflix.com is telemetry while netflix.com is streaming.
# Each entry is a provider-operated domain with a published or self-describing
# purpose; anything uncertain stays unclassified. See OPERATIONS.md.
_SUFFIXES: tuple[tuple[str, DestinationKind, str], ...] = (
    # Advertising delivery and ad verification.
    ("doubleclick.net", "advertising", "Google Ads"),
    ("googlesyndication.com", "advertising", "Google Ads"),
    ("googleadservices.com", "advertising", "Google Ads"),
    ("adnxs.com", "advertising", "Microsoft Advertising"),
    ("adnxs-simple.com", "advertising", "Microsoft Advertising"),
    ("adsrvr.org", "advertising", "The Trade Desk"),
    ("amazon-adsystem.com", "advertising", "Amazon Ads"),
    ("criteo.com", "advertising", "Criteo"),
    ("criteo.net", "advertising", "Criteo"),
    ("pubmatic.com", "advertising", "PubMatic"),
    ("rubiconproject.com", "advertising", "Magnite"),
    ("taboola.com", "advertising", "Taboola"),
    ("outbrain.com", "advertising", "Outbrain"),
    ("moatads.com", "advertising", "Oracle Moat"),
    ("samsungads.com", "advertising", "Samsung Ads"),
    ("applovin.com", "advertising", "AppLovin"),
    # Audience measurement, attribution and automatic content recognition.
    ("google-analytics.com", "measurement", "Google Analytics"),
    ("app-measurement.com", "measurement", "Google Analytics for Firebase"),
    ("oztam.com.au", "measurement", "OzTAM"),
    ("imrworldwide.com", "measurement", "Nielsen"),
    ("scorecardresearch.com", "measurement", "Comscore"),
    ("samba.tv", "measurement", "Samba TV"),
    ("alphonso.tv", "measurement", "Alphonso"),
    ("omtrdc.net", "measurement", "Adobe Analytics"),
    ("2o7.net", "measurement", "Adobe Analytics"),
    ("chartbeat.net", "measurement", "Chartbeat"),
    ("conviva.com", "measurement", "Conviva"),
    ("appsflyer.com", "measurement", "AppsFlyer"),
    ("adjust.com", "measurement", "Adjust"),
    ("mixpanel.com", "measurement", "Mixpanel"),
    ("amplitude.com", "measurement", "Amplitude"),
    # Vendor diagnostics, crash reporting and device logging.
    ("events.data.microsoft.com", "telemetry", "Microsoft diagnostic data"),
    ("vortex.data.microsoft.com", "telemetry", "Microsoft diagnostic data"),
    ("logs.netflix.com", "telemetry", "Netflix device logs"),
    ("crashlytics.com", "telemetry", "Firebase Crashlytics"),
    ("sentry.io", "telemetry", "Sentry"),
    ("device-metrics-us.amazon.com", "telemetry", "Amazon device metrics"),
    # Streaming media services and their delivery networks.
    ("youtube.com", "streaming", "YouTube"),
    ("youtu.be", "streaming", "YouTube"),
    ("youtube-nocookie.com", "streaming", "YouTube"),
    ("googlevideo.com", "streaming", "Google video delivery"),
    ("netflix.com", "streaming", "Netflix"),
    ("nflxvideo.net", "streaming", "Netflix"),
    ("nflxso.net", "streaming", "Netflix"),
    ("nflxext.com", "streaming", "Netflix"),
    ("iview.abc.net.au", "streaming", "ABC iview"),
    ("spotify.com", "streaming", "Spotify"),
    ("scdn.co", "streaming", "Spotify"),
    ("primevideo.com", "streaming", "Prime Video"),
    ("aiv-cdn.net", "streaming", "Prime Video"),
    ("disneyplus.com", "streaming", "Disney+"),
    ("bamgrid.com", "streaming", "Disney+"),
    # Connectivity probes and time synchronisation.
    ("connectivitycheck.gstatic.com", "connectivity", "Android"),
    ("connectivitycheck.android.com", "connectivity", "Android"),
    ("clients3.google.com", "connectivity", "Google"),
    ("captive.apple.com", "connectivity", "Apple"),
    ("msftconnecttest.com", "connectivity", "Microsoft"),
    ("msftncsi.com", "connectivity", "Microsoft"),
    ("detectportal.firefox.com", "connectivity", "Mozilla"),
    ("pool.ntp.org", "connectivity", "NTP Pool"),
    ("time.apple.com", "connectivity", "Apple"),
    ("time.windows.com", "connectivity", "Microsoft"),
    ("time.google.com", "connectivity", "Google"),
    # Names that never leave the local network or only map addresses to names.
    ("in-addr.arpa", "local", "Reverse lookup"),
    ("ip6.arpa", "local", "Reverse lookup"),
    ("home.arpa", "local", "Local network"),
    ("lan", "local", "Local network"),
    ("local", "local", "Local network"),
    ("localdomain", "local", "Local network"),
)
# Longest suffix first so nested provider domains resolve to the specific one.
_BY_LENGTH = tuple(sorted(_SUFFIXES, key=lambda entry: -len(entry[0])))
DISPLAY_LIMIT = 8


def classify(domain: str) -> tuple[DestinationKind, str | None]:
    """Return the destination's known purpose, or unclassified when unsure."""
    if domain == "wpad" or domain.startswith("wpad."):
        # Proxy auto-discovery queries go to the local search domain.
        return "local", "Proxy auto-discovery"
    for suffix, kind, provider in _BY_LENGTH:
        if domain == suffix or domain.endswith("." + suffix):
            return kind, provider
    return "unclassified", None


# Devices that resolve rarely can cache answers for hours, so a short quiet
# spell is ordinary. Silence is only reported after this much confirmed
# association: at least six hours between the first and last window, with
# enough windows (quick checks run every 2-5 minutes) to show it was continuous
# rather than two isolated sightings.
MIN_SILENT_SECONDS = 6 * 3600
MIN_SILENT_WINDOWS = 24


def pihole_silent(
    *, windows: int, partial: int, available: int, span_seconds: float
) -> bool:
    """True when a confirmed client sent Pi-hole no queries for hours.

    This is a visibility finding, not proof of misbehaviour: the device may be
    idle, use hard-coded resolvers or DNS over HTTPS, or resolve over IPv6
    through another server. Any partial window could hide queries, so a single
    failed or truncated read withholds the claim. Adopted UniFi infrastructure
    never reaches this check because it is not profiled as a DNS client.
    """
    return (
        windows >= MIN_SILENT_WINDOWS
        and span_seconds >= MIN_SILENT_SECONDS
        and partial == 0
        and available == 0
    )


def retention_rank(domain: str) -> int:
    """Lower ranks are retained first when a window must be truncated."""
    return _RETENTION_RANK[classify(domain)[0]]


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
    # See pihole_silent: confirmed presence with no Pi-hole queries at all.
    pihole_silent: bool = False


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
        for kind, group in sorted(
            groups.items(), key=lambda item: GROUP_ORDER.index(item[0])
        )
    )
