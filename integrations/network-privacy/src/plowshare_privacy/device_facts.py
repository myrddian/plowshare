"""Supplemental device facts: what a watched device says about itself, and advisories.

After a successful quick presence check, a bounded "facts" step asks a few
eligible devices to describe themselves (see ``device_describer``) and looks up
public advisories for the best available model string. Facts live only in the
collector's private ``device-facts.json`` journal; nothing here publishes to
Plowshare. The only value that leaves the local network is the public model
string sent to the configured NVD origin.

Rate limits are journal-backed so they survive restarts:

- a device (MAC) is described at most once per 24 hours, sooner only when the
  MAC is seen at a different watched address than when it was last described;
- each model is looked up at most once per 7 days, whichever device needs it;
- each quick check describes at most ``DEVICES_PER_CHECK`` devices, oldest
  first, with ``PACE_SECONDS`` between devices.
"""

from __future__ import annotations

import asyncio
import ipaddress
import json
import re
from dataclasses import asdict, dataclass, replace
from datetime import date
from pathlib import Path
from typing import Awaitable, Callable, Literal, Protocol

from .advisories import (
    Advisory,
    AdvisoryLookup,
    AdvisoryPage,
    AdvisoryUnavailable,
    public_model,
)
from .contracts import (
    CollectionPlan,
    DeviceIdentity,
    integer,
    items,
    load_json,
    mac_address,
    object_fields,
    parse_json,
    private_ipv4,
    text,
    timestamp,
    utc_now,
)
from .identity_source import ManagedProductLookup, ManagedProductUnavailable
from .presence import PresenceSample, epoch
from .private_files import replace_private

MAX_DEVICES = 256
MAX_MODELS = 256
MAX_SERVICES = 16
MAX_FACT_TEXT = 128
MAX_SERVICE_LABEL = 64
MAX_JOURNAL_BYTES = 8 * 1024 * 1024
DESCRIBE_INTERVAL_SECONDS = 24 * 60 * 60
ADVISORY_INTERVAL_SECONDS = 7 * 24 * 60 * 60
DEVICES_PER_CHECK = 4
PACE_SECONDS = 0.25
# A describe sends at most four datagrams and one fetch, each bounded at 2 s.
DESCRIBE_TIMEOUT_SECONDS = 15
ADVISORY_TIMEOUT_SECONDS = 15

DescriptionSource = Literal["upnp", "mdns"]
ModelSource = Literal["profile", "unifi", "device"]
AdvisoryStatus = Literal["searched", "unavailable", "model_unknown", "not_configured"]
_SOURCES: tuple[DescriptionSource, ...] = ("upnp", "mdns")
_MODEL_SOURCES = frozenset({"profile", "unifi", "device"})
_STATUSES = frozenset({"searched", "unavailable", "model_unknown", "not_configured"})
_SEVERITIES = frozenset({"NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL"})
_CVE = re.compile(r"CVE-[0-9]{4}-[0-9]{4,19}")
# Values that identify one physical device or one household rather than a
# product: MAC addresses, IPv4 addresses, UUIDs and long hexadecimal tokens.
_IDENTIFIER_LIKE = re.compile(
    r"\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b"
    r"|\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b"
    r"|\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\b"
    r"|\buuid:"
    r"|\b[0-9a-f]{16,}\b",
    re.I,
)


def fact_text(value: object, limit: int = MAX_FACT_TEXT) -> str | None:
    """Normalize one self-reported field, or drop it.

    Whitespace collapses to single spaces. Values that are empty, too long,
    contain control characters or look like a device identifier are dropped
    rather than truncated, so a stored fact is always the whole reported value.
    """
    if not isinstance(value, str):
        return None
    result = " ".join(value.split())
    if (
        not result
        or len(result) > limit
        or any(ord(c) < 32 or ord(c) == 127 for c in result)
        or _IDENTIFIER_LIKE.search(result)
    ):
        return None
    return result


def _optional_fact(value: object, limit: int = MAX_FACT_TEXT) -> str | None:
    if value is None:
        return None
    if fact_text(value, limit) != value:
        raise ValueError("Invalid device fact")
    assert isinstance(value, str)
    return value


@dataclass(frozen=True)
class DeviceDescription:
    """Product fields one device reported about itself over one protocol.

    Instance names, friendly names, serial numbers, UUIDs and addresses never
    reach this record; the readers discard them before construction.
    """

    source: DescriptionSource
    observed_at: str
    manufacturer: str | None = None
    model_name: str | None = None
    model_number: str | None = None
    firmware: str | None = None
    services: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.source not in _SOURCES:
            raise ValueError("Unsupported device description source")
        timestamp(self.observed_at)
        for value in (
            self.manufacturer,
            self.model_name,
            self.model_number,
            self.firmware,
        ):
            _optional_fact(value)
        if len(self.services) > MAX_SERVICES or len(set(self.services)) != len(
            self.services
        ):
            raise ValueError("Expected at most 16 distinct service labels")
        for service in self.services:
            if _optional_fact(service, MAX_SERVICE_LABEL) is None:
                raise ValueError("Invalid service label")
        if not self.services and all(value is None for value in self.content()[1:5]):
            raise ValueError("A device description needs at least one fact")

    def content(self) -> tuple[object, ...]:
        """Everything except the observation time, for change detection."""
        return (
            self.source,
            self.manufacturer,
            self.model_name,
            self.model_number,
            self.firmware,
            self.services,
        )

    @classmethod
    def decode(cls, value: object) -> DeviceDescription:
        row = object_fields(
            value,
            {
                "source",
                "observed_at",
                "manufacturer",
                "model_name",
                "model_number",
                "firmware",
                "services",
            },
        )
        source = row["source"]
        if source not in _SOURCES:
            raise ValueError("Unsupported device description source")
        return cls(
            "upnp" if source == "upnp" else "mdns",
            timestamp(row["observed_at"]),
            _optional_fact(row["manufacturer"]),
            _optional_fact(row["model_name"]),
            _optional_fact(row["model_number"]),
            _optional_fact(row["firmware"]),
            tuple(
                text(item, MAX_SERVICE_LABEL)
                for item in items(row["services"], MAX_SERVICES)
            ),
        )


def decode_advisory_page(value: object) -> AdvisoryPage:
    """Validate a retained NVD page with the same bounds ``advisories`` applies."""
    row = object_fields(value, {"model", "checked_at", "total", "advisories"})
    entries = []
    for entry in items(row["advisories"], 10):
        advisory = object_fields(
            entry,
            {"cve", "description", "published_on", "severity", "known_exploited"},
        )
        cve = text(advisory["cve"], 32)
        severity = advisory["severity"]
        if (
            not _CVE.fullmatch(cve)
            or (severity is not None and severity not in _SEVERITIES)
            or type(advisory["known_exploited"]) is not bool
        ):
            raise ValueError("Invalid retained advisory")
        published = text(advisory["published_on"], 10)
        date.fromisoformat(published)
        entries.append(
            Advisory(
                cve,
                text(advisory["description"], 600),
                published,
                text(severity, 16) if severity is not None else None,
                advisory["known_exploited"],
            )
        )
    model = text(row["model"], 128)
    if public_model(model) != model:
        raise ValueError("Invalid advisory model")
    return AdvisoryPage(
        model,
        timestamp(row["checked_at"]),
        integer(row["total"], len(entries), 2**31 - 1),
        tuple(entries),
    )


@dataclass(frozen=True)
class AdvisoryFacts:
    """Which public model was looked up for a device, from where, and the result.

    ``searched`` carries the NVD page; ``unavailable`` carries a fixed reason;
    ``model_unknown`` means no public model string was available; and
    ``not_configured`` means no NVD origin is configured, so nothing was sent.
    """

    status: AdvisoryStatus
    checked_at: str
    model: str | None = None
    model_source: ModelSource | None = None
    page: AdvisoryPage | None = None
    unavailable: str | None = None

    def __post_init__(self) -> None:
        timestamp(self.checked_at)
        if self.status not in _STATUSES:
            raise ValueError("Unsupported advisory status")
        if (self.model is None) != (self.model_source is None) or (
            self.model_source is not None and self.model_source not in _MODEL_SOURCES
        ):
            raise ValueError("An advisory model names its source")
        if self.model is not None and public_model(self.model) != self.model:
            raise ValueError("Advisory lookups use public model strings")
        if (self.status == "model_unknown") != (self.model is None):
            raise ValueError("Only an unknown model has no model string")
        if (self.status == "searched") != (self.page is not None) or (
            self.page is not None and self.page.model != self.model
        ):
            raise ValueError("Searched advisories carry the page for their model")
        if (self.status == "unavailable") != (self.unavailable is not None):
            raise ValueError("Unavailable advisories carry their reason")
        if self.unavailable is not None:
            text(self.unavailable, 256)

    def content(self) -> tuple[object, ...]:
        cves = (
            tuple(sorted(item.cve for item in self.page.advisories))
            if self.page
            else ()
        )
        return (self.status, self.model, self.model_source, cves)

    @classmethod
    def decode(cls, value: object) -> AdvisoryFacts:
        row = object_fields(
            value,
            {"status", "checked_at", "model", "model_source", "page", "unavailable"},
        )
        status, source = row["status"], row["model_source"]
        if status not in _STATUSES or (
            source is not None and source not in _MODEL_SOURCES
        ):
            raise ValueError("Unsupported advisory facts")
        return cls(
            "searched"
            if status == "searched"
            else "unavailable"
            if status == "unavailable"
            else "model_unknown"
            if status == "model_unknown"
            else "not_configured",
            timestamp(row["checked_at"]),
            text(row["model"], 128) if row["model"] is not None else None,
            None
            if source is None
            else "profile"
            if source == "profile"
            else "unifi"
            if source == "unifi"
            else "device",
            decode_advisory_page(row["page"]) if row["page"] is not None else None,
            text(row["unavailable"], 256) if row["unavailable"] is not None else None,
        )


@dataclass(frozen=True)
class DeviceFacts:
    """The supplemental facts retained for one confirmed MAC.

    ``address`` is the watched address the device was last described at;
    ``described_at`` is the last attempt, answered or not; ``updated_at`` is the
    last time the descriptions or advisory result changed in content. A device
    that stops answering keeps its earlier descriptions with their original
    ``observed_at`` until it is seen at a different address.
    """

    device_id: str
    address: str
    described_at: str
    updated_at: str
    descriptions: tuple[DeviceDescription, ...] = ()
    advisories: AdvisoryFacts | None = None

    def __post_init__(self) -> None:
        if not self.device_id.startswith("mac:") or (
            mac_address(self.device_id[4:]) != self.device_id[4:]
        ):
            raise ValueError("Device facts belong to a confirmed MAC")
        if str(ipaddress.IPv4Address(self.address)) != self.address or not private_ipv4(
            self.address
        ):
            raise ValueError("Device facts require a private IPv4 address")
        timestamp(self.described_at)
        timestamp(self.updated_at)
        sources = [item.source for item in self.descriptions]
        if sources != [source for source in _SOURCES if source in sources]:
            raise ValueError("Expected at most one description per source, in order")

    def content(self) -> tuple[object, ...]:
        return (
            tuple(item.content() for item in self.descriptions),
            self.advisories.content() if self.advisories else None,
        )

    @classmethod
    def decode(cls, value: object) -> DeviceFacts:
        row = object_fields(
            value,
            {
                "device_id",
                "address",
                "described_at",
                "updated_at",
                "descriptions",
                "advisories",
            },
        )
        return cls(
            text(row["device_id"], 64),
            text(row["address"], 64),
            timestamp(row["described_at"]),
            timestamp(row["updated_at"]),
            tuple(
                DeviceDescription.decode(item)
                for item in items(row["descriptions"], len(_SOURCES))
            ),
            AdvisoryFacts.decode(row["advisories"])
            if row["advisories"] is not None
            else None,
        )


@dataclass(frozen=True)
class FactsLedger:
    devices: tuple[DeviceFacts, ...] = ()
    # The advisory cache, one page per model (case-insensitive), newest lookups kept.
    advisories: tuple[AdvisoryPage, ...] = ()
    version: int = 1

    def __post_init__(self) -> None:
        if (
            self.version != 1
            or len(self.devices) > MAX_DEVICES
            or len(self.advisories) > MAX_MODELS
            or len({item.device_id for item in self.devices}) != len(self.devices)
            or len({item.model.casefold() for item in self.advisories})
            != len(self.advisories)
        ):
            raise ValueError("Invalid or oversized device facts journal")

    @classmethod
    def decode(cls, value: object) -> FactsLedger:
        row = object_fields(value, {"version", "devices", "advisories"})
        integer(row["version"], 1, 1)
        return cls(
            tuple(
                DeviceFacts.decode(item) for item in items(row["devices"], MAX_DEVICES)
            ),
            tuple(
                decode_advisory_page(item)
                for item in items(row["advisories"], MAX_MODELS)
            ),
        )


class DeviceFactsJournal:
    """The private facts file; every save is validated at its serialized boundary."""

    def __init__(self, root: Path):
        self.path = root / "device-facts.json"

    def read(self) -> FactsLedger:
        if self.path.is_symlink() or self.path.resolve() != self.path:
            raise ValueError("Device facts journal cannot be linked")
        if not self.path.exists():
            return FactsLedger()
        if self.path.stat().st_mode & 0o077:
            raise ValueError("Device facts journal must be private")
        return FactsLedger.decode(load_json(self.path, maximum_bytes=MAX_JOURNAL_BYTES))

    def save(self, value: FactsLedger) -> None:
        content = json.dumps(asdict(value), ensure_ascii=False, allow_nan=False)
        FactsLedger.decode(parse_json(content, maximum_bytes=MAX_JOURNAL_BYTES))
        replace_private(self.path, content)


@dataclass(frozen=True)
class DescriptionResult:
    """What one describe attempt learned. Silence is normal and has no issue."""

    descriptions: tuple[DeviceDescription, ...] = ()
    # Diagnostic codes for unexpected local failures only, never for silence.
    issues: tuple[str, ...] = ()


class DeviceDescriber(Protocol):
    """Ask exactly one watched private address to describe itself.

    Implementations contact only ``address``, never broadcast or multicast, and
    return an empty result when the device does not answer or answers with
    something unusable. They raise ``ValueError`` for an address outside the
    current watch list.
    """

    async def describe(self, address: str) -> DescriptionResult: ...


class DeviceFactsStep(Protocol):
    """The worker's view: run after a recorded quick check, and read facts."""

    def facts(self, device_id: str) -> DeviceFacts | None: ...
    async def refresh(self, sample: PresenceSample) -> None: ...


def describing(plan: CollectionPlan) -> bool:
    return (
        plan.mode == "tcp"
        and plan.enabled
        and plan.quick_scan
        and plan.device_descriptions
    )


def device_model(description: DeviceDescription) -> str | None:
    """A searchable model string from a self-description, or ``None``.

    The manufacturer's first word is prefixed unless the model already names
    it, for example "Sony" + "KD-55X85K".
    """
    model = description.model_name or description.model_number
    if model is None:
        return None
    maker = description.manufacturer.split()[0] if description.manufacturer else None
    if maker and maker.casefold() not in model.casefold():
        model = maker + " " + model
    try:
        return public_model(model)
    except ValueError:
        return None


class DeviceFactsService:
    """The bounded facts step and its in-memory view of the private journal.

    Only one ``refresh`` runs at a time (the worker never overlaps them). It
    holds no collector lock: it reads the plan through ``plan`` before each
    device so a settings change takes effect between devices. Reads and writes
    of the ledger have no awaits between them.
    """

    def __init__(
        self,
        journal: DeviceFactsJournal,
        describer: DeviceDescriber,
        plan: Callable[[], CollectionPlan],
        advisories: AdvisoryLookup | None,
        managed_products: Callable[[], ManagedProductLookup | None],
        confirmed_model: Callable[[str], str | None],
        *,
        clock: Callable[[], str] = utc_now,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ):
        self.journal = journal
        self.describer = describer
        self.plan = plan
        self.advisories = advisories
        self.managed_products = managed_products
        self.confirmed_model = confirmed_model
        self.clock = clock
        self.sleep = sleep
        self.error: str | None = None
        self.issues: tuple[str, ...] = ()
        self._available = True
        try:
            self._ledger = journal.read()
        except (OSError, ValueError):
            self._available = False
            self._ledger = FactsLedger()
            self.error = "The device facts journal needs inspection. Its retained data was preserved."

    @property
    def ledger(self) -> FactsLedger:
        return self._ledger

    def facts(self, device_id: str) -> DeviceFacts | None:
        return next(
            (item for item in self._ledger.devices if item.device_id == device_id),
            None,
        )

    def due(
        self, sample: PresenceSample, plan: CollectionPlan
    ) -> tuple[DeviceIdentity, ...]:
        """Eligible devices in describe order, at most ``DEVICES_PER_CHECK``.

        Eligible: a confirmed (non-configured) MAC at a watched private address
        that this check did not mark ambiguous or as adopted infrastructure.
        Never-described devices and new associations come first, then the
        oldest description older than a day.
        """
        watched = frozenset(plan.targets)
        now = epoch(self.clock())
        ranked: list[tuple[int, float, str, DeviceIdentity]] = []
        for sighting in sample.sightings:
            identity = sighting.identity
            if (
                identity is None
                or identity.mac is None
                or identity.source == "configured"
                or identity.address != sighting.address
                or sighting.address not in watched
                or sighting.address in sample.ambiguous_addresses
                or sighting.key in sample.infrastructure
                or not private_ipv4(sighting.address)
                or any(item[2] == identity.device_id for item in ranked)
            ):
                continue
            previous = self.facts(identity.device_id)
            if previous is None:
                rank = (0, 0.0)
            elif previous.address != identity.address:
                rank = (0, epoch(previous.described_at))
            else:
                age = now - epoch(previous.described_at)
                # A clock that moved backwards makes the record due again.
                if 0 <= age < DESCRIBE_INTERVAL_SECONDS:
                    continue
                rank = (1, epoch(previous.described_at))
            ranked.append((*rank, identity.device_id, identity))
        ranked.sort(key=lambda item: item[:3])
        return tuple(item[3] for item in ranked[:DEVICES_PER_CHECK])

    async def refresh(self, sample: PresenceSample) -> None:
        if not self._available or not describing(self.plan()):
            return
        issues: list[str] = []
        try:
            for index, identity in enumerate(self.due(sample, self.plan())):
                if index:
                    await self.sleep(PACE_SECONDS)
                plan = self.plan()
                if not describing(plan) or identity.address not in plan.targets:
                    break
                issues.extend(await self._learn(identity))
            self.error = None
        except (OSError, ValueError):
            self.error = (
                "Device facts could not be collected or saved. Earlier facts were kept."
            )
        self.issues = tuple(dict.fromkeys(issues))[:8]

    async def _learn(self, identity: DeviceIdentity) -> tuple[str, ...]:
        try:
            async with asyncio.timeout(DESCRIBE_TIMEOUT_SECONDS):
                result = await self.describer.describe(identity.address)
        except TimeoutError:
            result = DescriptionResult()
        now = self.clock()
        previous = self.facts(identity.device_id)
        descriptions = result.descriptions
        if previous is not None and previous.address == identity.address:
            fresh = {item.source for item in descriptions}
            kept = (
                *descriptions,
                *(item for item in previous.descriptions if item.source not in fresh),
            )
            descriptions = tuple(
                item for source in _SOURCES for item in kept if item.source == source
            )
        advisories = await self._advisories(identity, descriptions, previous, now)
        facts = DeviceFacts(
            identity.device_id, identity.address, now, now, descriptions, advisories
        )
        if previous is not None and previous.content() == facts.content():
            facts = replace(facts, updated_at=previous.updated_at)
        self._put(facts)
        return result.issues

    async def _model(
        self, identity: DeviceIdentity, descriptions: tuple[DeviceDescription, ...]
    ) -> tuple[str, ModelSource] | None:
        confirmed = self.confirmed_model(identity.device_id)
        if confirmed is not None:
            try:
                return public_model(confirmed), "profile"
            except ValueError:
                pass  # A private or malformed profile model is never sent.
        lookup = self.managed_products()
        if lookup is not None:
            try:
                async with asyncio.timeout(ADVISORY_TIMEOUT_SECONDS):
                    product = await lookup.managed_product(identity)
            except (ManagedProductUnavailable, TimeoutError):
                product = None
            if product is not None:
                try:
                    return public_model(
                        product.manufacturer + " " + product.model
                    ), "unifi"
                except ValueError:
                    pass  # A nonpublic controller model string is not a model.
        private = {
            value.casefold() for value in (identity.hostname, identity.label) if value
        }
        for description in descriptions:
            model = device_model(description)
            if model is not None and model.casefold() not in private:
                return model, "device"
        return None

    async def _advisories(
        self,
        identity: DeviceIdentity,
        descriptions: tuple[DeviceDescription, ...],
        previous: DeviceFacts | None,
        now: str,
    ) -> AdvisoryFacts:
        chosen = await self._model(identity, descriptions)
        if chosen is None:
            return AdvisoryFacts("model_unknown", now)
        model, source = chosen
        if self.advisories is None:
            return AdvisoryFacts("not_configured", now, model, source)
        cached = next(
            (
                page
                for page in self._ledger.advisories
                if page.model.casefold() == model.casefold()
            ),
            None,
        )
        if cached is not None and 0 <= epoch(now) - epoch(cached.checked_at) < (
            ADVISORY_INTERVAL_SECONDS
        ):
            return AdvisoryFacts(
                "searched", cached.checked_at, cached.model, source, cached
            )
        try:
            async with asyncio.timeout(ADVISORY_TIMEOUT_SECONDS):
                page = await self.advisories.search(model)
            if page.model != model:
                raise AdvisoryUnavailable("The advisory lookup answered another model.")
        except (AdvisoryUnavailable, TimeoutError) as error:
            # Keep the last known page for the same model rather than flapping.
            earlier = previous.advisories if previous else None
            if (
                earlier is not None
                and earlier.status == "searched"
                and earlier.model == model
            ):
                return replace(earlier, model_source=source)
            reason = (
                text(str(error), 256)
                if isinstance(error, AdvisoryUnavailable) and str(error)
                else "The advisory lookup timed out."
            )
            return AdvisoryFacts("unavailable", now, model, source, unavailable=reason)
        pages = sorted(
            (
                item
                for item in self._ledger.advisories
                if item.model.casefold() != model.casefold()
            ),
            key=lambda item: epoch(item.checked_at),
        )
        self._store(
            replace(self._ledger, advisories=(*pages[-(MAX_MODELS - 1) :], page))
        )
        return AdvisoryFacts("searched", page.checked_at, model, source, page)

    def _put(self, facts: DeviceFacts) -> None:
        others = sorted(
            (
                item
                for item in self._ledger.devices
                if item.device_id != facts.device_id
            ),
            key=lambda item: epoch(item.described_at),
        )
        self._store(
            replace(self._ledger, devices=(*others[-(MAX_DEVICES - 1) :], facts))
        )

    def _store(self, ledger: FactsLedger) -> None:
        self.journal.save(ledger)
        self._ledger = ledger
