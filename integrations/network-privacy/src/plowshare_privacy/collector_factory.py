"""Collector composition: optional external sources never change agent authority."""

from .collection import NetworkCollector
from .contracts import CollectionPlan
from .pihole import PiHoleV6


def configured_collector(collector: str, plan: CollectionPlan) -> NetworkCollector:
    return NetworkCollector(
        collector, plan, PiHoleV6(plan.pihole) if plan.pihole else None
    )
