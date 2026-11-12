"""The preview's sample run stays the one its own sample data produces."""

from __future__ import annotations

import json
import unittest

from preview_report import RUN, preview_run
from preview_sample import TV_DEVICE, sample_assessments, sample_devices


class PreviewReportTest(unittest.TestCase):
    def test_the_committed_run_is_regenerated_from_the_sample_data(self) -> None:
        # Regenerate with: PYTHONPATH=../src python preview_report.py
        self.assertEqual(json.loads(RUN.read_text(encoding="utf-8")), preview_run())

    def test_the_sample_view_matches_the_rendered_concerns(self) -> None:
        view, report = sample_assessments()[TV_DEVICE]
        levels = {c["id"]: c["level"] for c in preview_run()["assessor"]["concerns"]}
        self.assertEqual({c["id"]: c["level"] for c in view["concerns"]}, levels)
        assert report is not None
        self.assertIn("## Evidence", report)

    def test_no_sample_detection_claims_more_than_its_clues(self) -> None:
        for device in sample_devices():
            detection = device["detection"]
            if detection["confidence"] == "corroborated":
                self.assertGreater(len(detection["clues"]), 1, device["name"])


if __name__ == "__main__":
    unittest.main()
