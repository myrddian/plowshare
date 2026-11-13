"""Collector composition: optional external sources never change agent authority."""

from .collection import NetworkCollector
from .connectors import bind
from .contracts import CollectionPlan


def configured_collector(collector: str, plan: CollectionPlan) -> NetworkCollector:
    """A collector reading exactly the capabilities ``plan`` enables."""
    return NetworkCollector(collector, plan, bind(plan))
