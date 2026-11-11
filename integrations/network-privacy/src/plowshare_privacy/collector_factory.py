"""Collector composition: optional external sources never change agent authority."""

from .collection import NetworkCollector
from .contracts import CollectionPlan
from .pihole import PiHoleV6
from .unifi import UniFiNetwork


def configured_collector(collector: str, plan: CollectionPlan) -> NetworkCollector:
    pihole = PiHoleV6(plan.pihole) if plan.pihole else None
    return NetworkCollector(
        collector,
        plan,
        pihole,
        UniFiNetwork(plan.unifi) if plan.unifi else None,
        pihole.presence if pihole else None,
        pihole.activity if pihole else None,
    )
