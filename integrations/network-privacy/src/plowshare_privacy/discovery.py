"""Operator-only bounded LAN discovery, independent of Plowshare and credentials.

A successful connection or connection refusal is a response, not device identity.
Timeouts are coverage gaps; devices filtering the selected ports may remain unseen.
No banner, HTTP request, packet capture or privileged socket is used.
"""

from __future__ import annotations

import asyncio
import errno
from dataclasses import dataclass
from ipaddress import IPv4Network


@dataclass(frozen=True)
class DiscoveryPlan:
    network: IPv4Network
    ports: tuple[int, ...]
    timeout: float
    concurrency: int

    def __post_init__(self) -> None:
        private = (
            IPv4Network("10.0.0.0/8"),
            IPv4Network("172.16.0.0/12"),
            IPv4Network("192.168.0.0/16"),
        )
        if (
            not any(self.network.subnet_of(block) for block in private)
            or self.network.num_addresses > 256
        ):
            raise ValueError(
                "Discovery requires a private IPv4 subnet of at most 256 addresses"
            )
        if (
            not self.ports
            or len(self.ports) > 8
            or len(set(self.ports)) != len(self.ports)
            or any(
                type(port) is not int or not 1 <= port <= 65535 for port in self.ports
            )
        ):
            raise ValueError(
                "Discovery requires 1-8 unique TCP ports between 1 and 65535"
            )
        if (
            isinstance(self.timeout, bool)
            or not 0.1 <= self.timeout <= 2
            or type(self.concurrency) is not int
            or not 1 <= self.concurrency <= 32
        ):
            raise ValueError(
                "Discovery requires a 0.1-2 second timeout and concurrency 1-32"
            )


@dataclass(frozen=True)
class DiscoveredDevice:
    address: str
    open_ports: tuple[int, ...]
    refused_ports: tuple[int, ...]


@dataclass(frozen=True)
class DiscoveryReport:
    network: str
    ports: tuple[int, ...]
    addresses_checked: int
    devices: tuple[DiscoveredDevice, ...]
    unanswered_probes: int
    coverage: str = "Only responses on the selected TCP ports are observed; silence does not establish device absence."


async def discover(plan: DiscoveryPlan) -> DiscoveryReport:
    """Probe only the validated operator scope; close sockets without sending payloads."""
    addresses = tuple(str(address) for address in plan.network.hosts())
    semaphore = asyncio.Semaphore(plan.concurrency)

    async def probe(address: str, port: int) -> tuple[str, int, str]:
        async with semaphore:
            try:
                _, writer = await asyncio.wait_for(
                    asyncio.open_connection(address, port), plan.timeout
                )
            except TimeoutError:
                return address, port, "unanswered"
            except OSError as error:
                if error.errno == errno.ECONNREFUSED:
                    return address, port, "refused"
                if error.errno in {
                    errno.EHOSTUNREACH,
                    errno.ENETUNREACH,
                    errno.ETIMEDOUT,
                }:
                    return address, port, "unanswered"
                # Permission/socket exhaustion is a scanner failure, not absent devices.
                raise
            try:
                writer.close()
                await asyncio.wait_for(writer.wait_closed(), plan.timeout)
            except (TimeoutError, ConnectionError):
                pass  # The connection itself already established a response.
            return address, port, "open"

    # Tasks are bounded by 256 addresses x 8 ports; cancellation tears down all probes.
    tasks = [
        asyncio.create_task(probe(address, port))
        for address in addresses
        for port in plan.ports
    ]
    try:
        observations = await asyncio.gather(*tasks)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    devices = tuple(
        DiscoveredDevice(
            address,
            tuple(
                port
                for host, port, status in observations
                if host == address and status == "open"
            ),
            tuple(
                port
                for host, port, status in observations
                if host == address and status == "refused"
            ),
        )
        for address in addresses
        if any(
            host == address and status != "unanswered"
            for host, _, status in observations
        )
    )
    return DiscoveryReport(
        str(plan.network),
        plan.ports,
        len(addresses),
        devices,
        sum(status == "unanswered" for _, _, status in observations),
    )
