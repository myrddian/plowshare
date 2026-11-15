"""Prepare retained local evidence for a product question, without web discovery."""

from __future__ import annotations

from typing import Callable

from .contracts import utc_now
from .device_dossier import build_dossier, parse_device_id
from .device_inventory import DeviceCard
from .dossier_port import DossierRetention
from .identity_source import DeviceIdentitySource
from .product_probe import ProbeObservation, ProductProbe
from .product_research import ProductContext
from .reference_requests import ReferenceRequest
from .tools import monitored_devices
from .worker import Worker


class CurrentProductContext:
    def __init__(
        self,
        worker: Worker,
        dossiers: DossierRetention,
        probe: ProductProbe,
        identities: Callable[[], DeviceIdentitySource | None],
    ):
        self.worker, self.dossiers, self.probe = worker, dossiers, probe
        self.identities = identities

    async def confirm(self, device_id: str, address: str) -> None:
        """Use the same fresh controller/Pi-hole identity capability as deep scans."""
        source = self.identities()
        if source is None:
            raise ValueError(
                "Device checks need a fresh controller or Pi-hole identity"
            )
        sample = await source.identify((address,), utc_now())
        found = [identity for identity in sample.devices if identity.address == address]
        if (
            address in sample.ambiguous_addresses
            or len(found) != 1
            or found[0].mac != device_id.removeprefix("mac:")
        ):
            raise ValueError(
                "The monitored device's current address could not be confirmed"
            )

    def card(self, device_id: str) -> DeviceCard:
        device_id = parse_device_id(device_id)
        card = next(
            (
                c
                for d, c in monitored_devices(self.worker.device_inventory())
                if d == device_id
            ),
            None,
        )
        if card is None:
            raise ValueError("The product question's device is no longer monitored")
        return card

    async def __call__(self, request: ReferenceRequest, probe: bool) -> ProductContext:
        if request.device_id is None:
            if probe:
                raise ValueError("Device checks need a linked monitored device")
            return ProductContext()
        card = self.card(request.device_id)
        plan = self.worker.config.collection
        dossier = build_dossier(card, facts=self.worker.device_facts(request.device_id))
        observations: tuple[ProbeObservation, ...] = ()
        if probe:
            if plan.mode != "tcp" or card.address not in plan.targets:
                raise ValueError(
                    "Device checks require a current explicitly configured TCP target"
                )
            await self.confirm(request.device_id, card.address)
            # Product slugs and questions are model prose, so cannot enable a
            # product-specific probe. Require a Sony manufacturer description or
            # observed vendor; neither proves model or firmware authenticity.
            sony = any(
                description.manufacturer and "sony" in description.manufacturer.lower()
                for description in dossier.supplemental.descriptions
            ) or (
                dossier.identity.vendor is not None
                and "sony" in dossier.identity.vendor.lower()
            )
            observations = await self.probe.inspect(card.address, sony)
            await self.confirm(request.device_id, card.address)
            current = self.card(request.device_id)
            if current.address != card.address or current.identity != card.identity:
                raise ValueError(
                    "Device addressing changed during the check; observations were not used"
                )
            if card.address not in self.worker.config.collection.targets:
                raise ValueError("Device left the configured scope during the check")
        retained = await self.dossiers.retain(dossier, self.worker.config.zone)
        return ProductContext(retained.revision, observations)
