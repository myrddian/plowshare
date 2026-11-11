"""The typed start request and result shape of the assess_network_device orchestration."""

from __future__ import annotations

import json
import unittest
from dataclasses import replace
from uuid import uuid4

from plowshare_privacy.assessment_request import (
    CALLER,
    DEFINITION,
    AssessmentRequest,
    AssessmentResult,
    ConcernOutcome,
    report_name,
)

DEVICE = "mac:02:00:00:00:00:01"
DOSSIER = str(uuid4())
REPORT = str(uuid4())
RESOURCE = str(uuid4())
NOW = "2026-11-11T09:30:00.123456Z"


def result(**changes: object) -> dict[str, object]:
    value: dict[str, object] = {
        "version": 1,
        "device_id": DEVICE,
        "dossier": DOSSIER,
        "report": {"revision": REPORT, "resource": RESOURCE},
        "review": "needs_corrections",
        "corrected": True,
        "concerns": [
            {"id": "c1", "level": "worth_checking", "verdict": "weakened"},
            {"id": "c2", "level": "potential_breach", "verdict": "refuted"},
        ],
    }
    value.update(changes)
    return value


class AssessmentRequestTest(unittest.TestCase):
    def test_request_encodes_exactly_the_fields_the_script_validates(self) -> None:
        previous = str(uuid4())
        request = AssessmentRequest(DEVICE, DOSSIER, previous, NOW)
        self.assertEqual(
            json.loads(request.encode()),
            {
                "version": 1,
                "device_id": DEVICE,
                "dossier": DOSSIER,
                "previous": previous,
                "requested_at": NOW,
            },
        )
        self.assertIsNone(
            json.loads(AssessmentRequest(DEVICE, DOSSIER, None, NOW).encode())[
                "previous"
            ]
        )
        self.assertEqual(DEFINITION, "assess_network_device")
        self.assertEqual(CALLER, "privacy_coordinator")

    def test_request_rejects_noncanonical_values(self) -> None:
        valid = AssessmentRequest(DEVICE, DOSSIER, None, NOW)
        for changes in (
            {"device_id": "ip:10.0.0.1"},
            {"dossier": "not-a-uuid"},
            {"previous": DOSSIER},
            {"previous": "x"},
            {"requested_at": "2026-11-11T09:30:00+10:00"},
            {"requested_at": "yesterday"},
        ):
            with self.assertRaises(ValueError, msg=str(changes)):
                replace(valid, **changes)

    def test_report_name_is_stable_per_device(self) -> None:
        self.assertEqual(report_name(DEVICE), "Network privacy assessment / " + DEVICE)
        with self.assertRaises(ValueError):
            report_name("mac:FF:FF:FF:FF:FF:FF")

    def test_result_decodes_the_orchestration_finish_text(self) -> None:
        decoded = AssessmentResult.decode(json.dumps(result()))
        self.assertEqual(decoded.report_revision, REPORT)
        self.assertEqual(decoded.review, "needs_corrections")
        self.assertTrue(decoded.corrected)
        self.assertEqual(
            decoded.concerns[1], ConcernOutcome("c2", "potential_breach", "refuted")
        )

    def test_result_rejects_foreign_or_malformed_shapes(self) -> None:
        for invalid in (
            result(version=2),
            result(review="not_checked"),
            result(corrected="yes"),
            result(extra=True),
            result(report={"revision": REPORT}),
            result(concerns=[{"id": "c1", "level": "worth_checking", "verdict": "x"}]),
            result(concerns=[{"id": "c1", "level": "severe", "verdict": "holds"}]),
            result(
                concerns=[
                    {"id": "c1", "level": "expected", "verdict": "holds"},
                    {"id": "c1", "level": "expected", "verdict": "holds"},
                ]
            ),
        ):
            with self.assertRaises(ValueError, msg=json.dumps(invalid)):
                AssessmentResult.decode(json.dumps(invalid))
        with self.assertRaises(ValueError):
            AssessmentResult.decode("not json")
        decoded = AssessmentResult.decode(json.dumps(result()))
        with self.assertRaises(ValueError):
            decoded.require(AssessmentRequest(DEVICE, str(uuid4()), None, NOW))
        decoded.require(AssessmentRequest(DEVICE, DOSSIER, None, NOW))


if __name__ == "__main__":
    unittest.main()
