import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path

from support import configuration

from plowshare_privacy.contracts import PiHolePlan, UniFiPlan
from plowshare_privacy.system_view import describe

PIHOLE = PiHolePlan(
    "http://192.0.2.53", None, Path("/private/pihole"), 300, 50, 600, 2.0
)
UNIFI = UniFiPlan(
    "https://192.0.2.1/proxy/network/integration",
    "88f7af54-98f8-306a-a1c7-c9349722b1f6",
    "UNIFI_KEY",
    None,
    "ab" * 32,
)


class SystemViewTest(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.config = configuration(Path(directory.name))

    def test_names_credentials_and_reports_presence_without_values(self) -> None:
        config = replace(
            self.config,
            collection=replace(self.config.collection, pihole=PIHOLE, unifi=UNIFI),
        )
        environment = {"PLOWSHARE_TOKEN": "secret-service-value", "UNIFI_KEY": " "}
        view = describe(config, None, environment, lambda path: True)
        credentials = {c.purpose: c for c in view.deployment.credentials}
        self.assertTrue(credentials["Plowshare service credential"].present)
        self.assertFalse(credentials["Dashboard access token"].present)
        # A blank variable is missing, and a file is never named by its path.
        self.assertFalse(credentials["UniFi API key"].present)
        self.assertEqual(credentials["Pi-hole password"].source, "file")
        self.assertIsNone(credentials["Pi-hole password"].variable)
        self.assertTrue(credentials["Pi-hole password"].present)
        encoded = json.dumps(asdict(view))
        self.assertNotIn("secret-service-value", encoded)
        self.assertNotIn("/private/pihole", encoded)
        assert view.unifi is not None
        self.assertEqual(view.unifi.trust, "pinned")
        self.assertIsNone(view.unifi.traffic_site)
        self.assertIsNone(view.advisory_origin)

    def test_reports_a_missing_password_file_and_byte_counters(self) -> None:
        config = replace(
            self.config,
            collection=replace(
                self.config.collection,
                pihole=PIHOLE,
                unifi=replace(UNIFI, traffic_site="default", ca_file=Path("/ca.pem")),
            ),
        )
        view = describe(config, "https://services.nvd.nist.gov", {}, lambda path: False)
        assert view.pihole is not None and view.unifi is not None
        self.assertFalse(view.pihole.password.present)
        self.assertEqual(
            (view.unifi.trust, view.unifi.traffic_site), ("ca_file", "default")
        )
        self.assertEqual(view.advisory_origin, "https://services.nvd.nist.gov")

    def test_describes_the_deployment_without_optional_connections(self) -> None:
        view = describe(self.config, None, {}, lambda path: True)
        self.assertIsNone(view.pihole)
        self.assertIsNone(view.unifi)
        deployment = view.deployment
        self.assertEqual(deployment.server_origin, "https://plowshare.example.invalid")
        self.assertEqual(
            (deployment.request_topic, deployment.result_topic, deployment.group),
            ("privacy.scan.requests", "privacy.scan.completed", "privacy-home-network"),
        )
        self.assertEqual(
            [c.variable for c in deployment.credentials],
            ["PLOWSHARE_TOKEN", "PRIVACY_WEB_TOKEN"],
        )


if __name__ == "__main__":
    unittest.main()
