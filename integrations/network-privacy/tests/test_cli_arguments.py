"""Command-line defaults that change what the dashboard offers an operator."""

from __future__ import annotations

import unittest

from plowshare_privacy.cli import build_parser

SERVE = ["--config", "collector.json", "serve", "--bind", "host", "--port", "8095"]


class ServeArgumentsTest(unittest.TestCase):
    def test_schedule_controls_are_on_by_default(self) -> None:
        args = build_parser().parse_args(SERVE)
        self.assertTrue(args.deployment_controls)

    def test_schedule_controls_can_be_turned_off(self) -> None:
        args = build_parser().parse_args([*SERVE, "--no-deployment-controls"])
        self.assertFalse(args.deployment_controls)

    def test_schedule_controls_can_be_requested_explicitly(self) -> None:
        args = build_parser().parse_args([*SERVE, "--deployment-controls"])
        self.assertTrue(args.deployment_controls)


if __name__ == "__main__":
    unittest.main()
