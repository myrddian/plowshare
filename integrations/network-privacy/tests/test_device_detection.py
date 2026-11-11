"""Exercise local classification and the bounded NVD transport with fixture data."""

import json
import unittest
from collections.abc import AsyncIterator
from dataclasses import replace
from unittest.mock import MagicMock, patch

from plowshare_privacy.advisories import AdvisoryUnavailable, NvdAdvisories
from plowshare_privacy.contracts import DeviceIdentity, TcpObservation
from plowshare_privacy.device_detection import detect_device

ADDRESS = "192.168.90.4"
STAMP = "2026-01-01T00:00:00Z"


class DetectionTest(unittest.TestCase):
    def test_hostname_and_open_port_corroborate_a_type(self) -> None:
        camera = DeviceIdentity(
            ADDRESS,
            "ip:" + ADDRESS,
            None,
            "porch-camera2",
            None,
            None,
            "pihole-v6",
            STAMP,
            STAMP,
        )
        result = detect_device(camera, (TcpObservation(ADDRESS, 554, "open"),))
        self.assertEqual(result.device_type, "Camera or video recorder")
        self.assertEqual(result.confidence, "corroborated")
        self.assertEqual(len(result.clues), 2)
        self.assertEqual(result.exposures[0].possible_service, "Media streaming")

        generic = detect_device(None, (TcpObservation(ADDRESS, 554, "open"),))
        self.assertEqual(generic.confidence, "unknown")
        self.assertEqual(generic.exposures, result.exposures)
        conflicting = detect_device(
            replace(camera, hostname="printer-camera"),
            (TcpObservation(ADDRESS, 631, "open"),),
        )
        self.assertEqual(conflicting.device_type, "Unknown")

    def test_configured_names_and_unanswered_ports_do_not_infer_risk(self) -> None:
        label = DeviceIdentity(
            ADDRESS,
            "ip:" + ADDRESS,
            "printer",
            None,
            None,
            None,
            "configured",
            STAMP,
            None,
        )
        result = detect_device(
            label,
            (
                TcpObservation(ADDRESS, 631, "timeout"),
                TcpObservation(ADDRESS, 23, "closed"),
            ),
        )
        self.assertEqual(result.device_type, "Unknown")
        self.assertEqual(result.exposures, ())
        printer = detect_device(None, (TcpObservation(ADDRESS, 631, "open"),))
        self.assertEqual(
            (printer.device_type, printer.confidence), ("Printer", "tentative")
        )


class AdvisoryTest(unittest.IsolatedAsyncioTestCase):
    async def test_exact_model_query_projects_only_bounded_review_fields(self) -> None:
        source = NvdAdvisories("https://nvd.fixture.invalid")
        response = MagicMock(status=200)
        cve: dict[str, object] = {
            "id": "CVE-2026-1234",
            "published": "2026-01-01T00:00:00.000",
            "descriptions": [{"lang": "en", "value": "A fixture description."}],
            "cisaExploitAdd": "2026-01-02",
            "metrics": {
                "cvssMetricV31": [
                    {
                        "type": "Primary",
                        "cvssData": {"baseSeverity": "HIGH"},
                    }
                ]
            },
            "references": [{"url": "https://untrusted.fixture.invalid/"}],
        }
        body = {"totalResults": 21, "vulnerabilities": [{"cve": cve}]}

        async def chunks(size: int) -> AsyncIterator[bytes]:
            yield json.dumps(body).encode()

        response.content.iter_chunked = chunks
        with patch("plowshare_privacy.advisories.ClientSession") as constructor:
            session = constructor.return_value.__aenter__.return_value
            session.get = MagicMock()
            session.get.return_value.__aenter__.return_value = response
            page = await source.search("Example Model X100")
            url = session.get.call_args.args[0]
            self.assertEqual(url.path, "/rest/json/cves/2.0")
            self.assertEqual(
                dict(url.query),
                {
                    "keywordSearch": "Example Model X100",
                    "resultsPerPage": "10",
                    "keywordExactMatch": "",
                    "noRejected": "",
                },
            )
            self.assertFalse(session.get.call_args.kwargs["allow_redirects"])
            self.assertEqual(page.total, 21)
            self.assertEqual(page.advisories[0].severity, "HIGH")
            self.assertTrue(page.advisories[0].known_exploited)
            cve["id"] = "<script>"
            with self.assertRaises(AdvisoryUnavailable):
                await source.search("Example Model X100")
