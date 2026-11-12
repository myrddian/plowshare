"""Dossier retention revises one source per device; uncertainty is settled by reading."""

from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import uuid4

from plowshare import Client, Delivery, Refusal, Reply, TransportError
from plowshare.contracts import (
    InformationAdmissionDto,
    InformationListRequest,
    InformationReviseRequest,
    InformationRevisionDto,
    InformationUploadRequest,
)
from support import FixturePort, configuration
from test_device_dossier import activity, card

from plowshare_privacy.device_dossier import (
    DeviceDossier,
    build_dossier,
    encode_dossier,
)
from plowshare_privacy.dossier_port import (
    DeviceDossiers,
    DossierDocument,
    DossierVersion,
    FileDossierIntents,
    SdkDossierPort,
    source_name,
)
from plowshare_privacy.worker import ReconciliationRequired

STAMP = "2026-01-01T00:00:00Z"
DEVICE = "mac:02:00:00:00:00:01"
OTHER = "mac:02:00:00:00:00:02"


def dossier(queries: int = 3, *, device: str = DEVICE) -> DeviceDossier:
    value = build_dossier(
        card(dns=activity([("oztam.com.au", queries, 0), ("sony.net", 40, 0)]))
    )
    mac = device.removeprefix("mac:")
    return replace(value, identity=replace(value.identity, device_id=device, mac=mac))


class Sources:
    """An in-memory project catalogue; a lost reply still commits the revision."""

    def __init__(self) -> None:
        self.rows: list[DossierVersion] = []
        self.texts: dict[str, str] = {}
        self.writes: list[tuple[str, str, str | None]] = []
        self.lost = False
        self.unreadable: set[str] = set()

    async def versions(self, device_id: str) -> tuple[DossierVersion, ...]:
        return tuple(row for row in self.rows if row.device_id == device_id)

    async def read(self, revision: str) -> str:
        if revision in self.unreadable:
            raise Refusal("NOT_READY", "Extraction pending")
        return self.texts[revision]

    async def save(
        self, device_id: str, request_id: str, text: str, previous: str | None
    ) -> tuple[str, str]:
        self.writes.append((device_id, request_id, previous))
        old = [row for row in self.rows if row.device_id == device_id]
        resource = old[-1].resource if old else str(uuid4())
        revision = str(uuid4())
        self.rows.append(
            DossierVersion(device_id, revision, resource, len(old) + 1, STAMP)
        )
        self.texts[revision] = text
        if self.lost:
            raise TransportError(Delivery.UNKNOWN, "Lost response")
        return revision, resource


class DossierRetentionTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.config = configuration(self.root)
        self.remote = Sources()
        self.intents = FileDossierIntents(
            self.root, self.config.project, self.config.collector
        )
        self.dossiers = DeviceDossiers(self.remote, self.intents)

    def restart(self) -> DeviceDossiers:
        return DeviceDossiers(
            self.remote,
            FileDossierIntents(self.root, self.config.project, self.config.collector),
        )

    async def test_first_retention_uploads_and_returns_the_citable_revision(
        self,
    ) -> None:
        retained = await self.dossiers.retain(dossier())
        self.assertTrue(retained.changed)
        self.assertEqual(retained.device_id, DEVICE)
        self.assertEqual(len(self.remote.writes), 1)
        self.assertIsNone(self.remote.writes[0][2])
        self.assertEqual(retained.revision, self.remote.rows[0].revision)
        document = DossierDocument.read(self.remote.texts[retained.revision])
        self.assertEqual(document.device_id, DEVICE)
        self.assertEqual(document.payload, encode_dossier(dossier()))
        self.assertEqual(document.write_id, self.remote.writes[0][1])
        self.assertEqual(self.intents.all()[0].phase, "confirmed")
        self.assertEqual(self.intents.path.stat().st_mode & 0o777, 0o600)

    async def test_a_changed_dossier_revises_the_same_resource(self) -> None:
        first = await self.dossiers.retain(dossier())
        second = await self.dossiers.retain(dossier(9))
        self.assertTrue(second.changed)
        self.assertEqual(self.remote.writes[1][2], first.revision)
        self.assertEqual(second.resource, first.resource)
        self.assertNotEqual(second.revision, first.revision)

    async def test_unchanged_text_reuses_the_retained_revision_without_a_write(
        self,
    ) -> None:
        first = await self.dossiers.retain(dossier())
        again = await self.restart().retain(dossier())
        self.assertFalse(again.changed)
        self.assertEqual(again.revision, first.revision)
        self.assertEqual(len(self.remote.writes), 1)

    async def test_latest_ordinal_is_compared_not_an_older_revision(self) -> None:
        await self.dossiers.retain(dossier())
        changed = await self.dossiers.retain(dossier(9))
        back = await self.dossiers.retain(dossier())
        self.assertTrue(back.changed)
        self.assertEqual(self.remote.writes[2][2], changed.revision)

    async def test_lost_reply_stays_pending_and_is_settled_by_reading_not_resent(
        self,
    ) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.dossiers.retain(dossier())
        self.assertEqual(self.intents.all()[0].phase, "pending")
        self.remote.lost = False
        restored = self.restart()
        retained = await restored.retain(dossier())
        self.assertEqual(len(self.remote.writes), 1)
        self.assertFalse(retained.changed)
        self.assertEqual(retained.revision, self.remote.rows[0].revision)
        self.assertEqual(restored.intents.all()[0].phase, "confirmed")

    async def test_unconfirmed_write_fences_only_its_own_device(self) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.dossiers.retain(dossier())
        self.remote.lost = False
        # Nothing positive is visible: the write may still commit, so it is not resent.
        self.remote.rows.clear()
        with self.assertRaises(ReconciliationRequired):
            await self.dossiers.retain(dossier(9))
        with self.assertRaises(ReconciliationRequired):
            await self.dossiers.reconcile()
        self.assertEqual(len(self.remote.writes), 1)
        self.assertEqual(self.intents.all()[0].phase, "pending")
        other = await self.dossiers.retain(dossier(device=OTHER))
        self.assertTrue(other.changed)
        self.assertEqual(len(self.remote.writes), 2)

    async def test_unreadable_or_different_text_does_not_settle_a_write(
        self,
    ) -> None:
        self.remote.lost = True
        with self.assertRaises(TransportError):
            await self.dossiers.retain(dossier())
        revision = self.remote.rows[0].revision
        self.remote.unreadable.add(revision)
        with self.assertRaises(ReconciliationRequired):
            await self.dossiers.reconcile()
        self.remote.unreadable.clear()
        written = DossierDocument.read(self.remote.texts[revision])
        self.remote.texts[revision] = DossierDocument(
            str(uuid4()), DEVICE, written.payload
        ).text()
        with self.assertRaises(ReconciliationRequired):
            await self.dossiers.reconcile()
        self.assertEqual(self.intents.all()[0].phase, "pending")

    async def test_not_submitted_or_refused_writes_settle_as_refused(self) -> None:
        self.remote.save = AsyncMock(  # type: ignore[method-assign]
            side_effect=TransportError(Delivery.NOT_SUBMITTED, "Not delivered")
        )
        with self.assertRaises(TransportError):
            await self.dossiers.retain(dossier())
        self.remote.save = AsyncMock(  # type: ignore[method-assign]
            side_effect=Refusal("FORBIDDEN", "No write access")
        )
        with self.assertRaises(Refusal):
            await self.dossiers.retain(dossier())
        self.assertEqual([v.phase for v in self.intents.all()], ["refused", "refused"])

    async def test_foreign_or_unchanged_receipt_keeps_the_write_pending(self) -> None:
        first = await self.dossiers.retain(dossier())
        original = self.remote.save

        async def foreign(
            device_id: str, request_id: str, text: str, previous: str | None
        ) -> tuple[str, str]:
            revision, _ = await original(device_id, request_id, text, previous)
            return revision, str(uuid4())

        self.remote.save = foreign  # type: ignore[method-assign]
        with self.assertRaises(ValueError):
            await self.dossiers.retain(dossier(9))
        self.assertEqual(self.intents.all()[-1].phase, "pending")
        self.remote.save = original  # type: ignore[method-assign]
        await self.dossiers.reconcile()
        self.assertEqual(self.intents.all()[-1].phase, "confirmed")
        self.assertNotEqual(self.intents.all()[-1].revision, first.revision)

    async def test_retained_text_for_another_device_is_refused(self) -> None:
        await self.dossiers.retain(dossier())
        revision = self.remote.rows[0].revision
        self.remote.texts[revision] = DossierDocument(
            str(uuid4()), OTHER, encode_dossier(dossier(device=OTHER))
        ).text()
        with self.assertRaises(ValueError):
            await self.dossiers.retain(dossier(9))
        self.assertEqual(len(self.remote.writes), 1)

    def test_document_round_trip_is_exact_and_strict(self) -> None:
        payload = encode_dossier(dossier())
        document = DossierDocument(str(uuid4()), DEVICE, payload)
        self.assertEqual(DossierDocument.read(document.text()), document)
        self.assertEqual(json.loads(document.text())["dossier"], json.loads(payload))
        self.assertTrue(document.text().startswith('{"version":3,'))
        for invalid in (
            # Version 2 has no clock; version 4 is unknown.
            document.text().replace('"version":3', '"version":2'),
            document.text().replace('"version":3', '"version":4'),
            document.text()[:-1] + ',"extra":true}',
            document.text().replace(',"', ', "', 1),
            json.dumps({**json.loads(document.text()), "device_id": "ip:10.0.0.1"}),
        ):
            with self.assertRaises(ValueError):
                DossierDocument.read(invalid)
        with self.assertRaises(ValueError):
            DossierDocument(str(uuid4()), OTHER, payload)
        with self.assertRaises(ValueError):
            DossierDocument(str(uuid4()), DEVICE, payload, zone="Not/AZone")

    def test_the_clock_writes_each_printed_time_in_the_household_zone(self) -> None:
        value = json.loads(encode_dossier(dossier()))
        value["traffic"] = {
            "from_at": "2026-11-11T13:30:00Z",
            "until_at": "2026-11-12T01:00:00Z",
            "samples": 2,
            "to_device_bytes": 1,
            "from_device_bytes": 2,
            "resets": 0,
        }
        payload = json.dumps(value, separators=(",", ":"))
        document = DossierDocument(
            str(uuid4()), DEVICE, payload, zone="Australia/Sydney"
        )
        clock = json.loads(document.text())["clock"]
        self.assertEqual(clock["zone"], "Australia/Sydney")
        # Sydney is UTC+11 in November: the evening of the 11th UTC is the 12th.
        self.assertEqual(
            clock["times"]["2026-11-11T13:30:00Z"], "2026-11-12 00:30 AEDT"
        )
        self.assertEqual(
            clock["times"]["2026-11-12T01:00:00Z"], "2026-11-12 12:00 AEDT"
        )
        self.assertEqual(DossierDocument.read(document.text()), document)
        # A clock that does not derive from the payload and zone is rejected.
        for tampered in (
            document.text().replace("00:30 AEDT", "00:31 AEDT"),
            document.text().replace('"zone":"Australia/Sydney"', '"zone":"UTC"'),
        ):
            with self.assertRaises(ValueError):
                DossierDocument.read(tampered)

    async def test_a_new_zone_revises_an_otherwise_unchanged_dossier(self) -> None:
        first = await self.dossiers.retain(dossier())
        again = await self.dossiers.retain(dossier())
        self.assertFalse(again.changed)
        moved = await self.dossiers.retain(dossier(), "Australia/Sydney")
        self.assertTrue(moved.changed)
        self.assertNotEqual(moved.revision, first.revision)
        written = DossierDocument.read(self.remote.texts[moved.revision])
        self.assertEqual(written.zone, "Australia/Sydney")

    async def test_a_version_1_revision_is_read_and_revised_to_version_3(
        self,
    ) -> None:
        current = json.loads(encode_dossier(dossier()))
        del current["supplemental"]
        legacy = DossierDocument(
            str(uuid4()), DEVICE, json.dumps(current, separators=(",", ":")), 1
        )
        self.assertTrue(legacy.text().startswith('{"version":1,'))
        self.assertEqual(DossierDocument.read(legacy.text()), legacy)
        revision, resource = str(uuid4()), str(uuid4())
        self.remote.rows.append(DossierVersion(DEVICE, revision, resource, 1, STAMP))
        self.remote.texts[revision] = legacy.text()
        retained = await self.dossiers.retain(dossier())
        self.assertTrue(retained.changed)
        self.assertEqual(self.remote.writes[-1][2], revision)
        written = DossierDocument.read(self.remote.texts[retained.revision])
        self.assertEqual(written.version, 3)

    def test_journal_is_bound_to_project_collector_and_settled_receipts(self) -> None:
        document = DossierDocument(str(uuid4()), DEVICE, encode_dossier(dossier()))
        from plowshare_privacy.dossier_port import DossierIntent

        intent = DossierIntent(
            document.write_id, DEVICE, None, document.text(), "pending"
        )
        self.intents.save(intent)
        with self.assertRaises(ValueError):
            self.intents.save(replace(intent, device_id=OTHER))
        settled = replace(intent, phase="confirmed", revision=str(uuid4()))
        self.intents.save(settled)
        with self.assertRaises(ValueError):
            self.intents.save(replace(settled, revision=str(uuid4())))
        with self.assertRaises(ValueError):
            FileDossierIntents(
                self.root, "another-project", self.config.collector
            ).all()
        with self.assertRaises(ValueError):
            self.intents.save(
                replace(intent, request_id=str(uuid4()), phase="confirmed")
            )

    def test_source_names_accept_only_canonical_device_ids(self) -> None:
        self.assertEqual(
            source_name(DEVICE), "network-privacy-dossier/" + DEVICE + ".json"
        )
        for invalid in ("ip:10.0.0.1", "mac:02-00-00-00-00-01", "../" + DEVICE):
            with self.assertRaises(ValueError):
                source_name(invalid)


class SdkDossierPortTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.config = configuration(Path(directory.name).resolve())
        self.client = AsyncMock(spec=Client)
        self.reader = AsyncMock(spec=FixturePort)
        self.port = SdkDossierPort(self.client, self.config, self.reader)

    async def test_upload_and_revise_use_project_scope_and_the_stable_name(
        self,
    ) -> None:
        revision, resource, request = str(uuid4()), str(uuid4()), str(uuid4())
        self.client.request.return_value = Reply(
            "ACCEPTED",
            None,
            InformationAdmissionDto(created=True, resource=resource, revision=revision),
        )
        self.assertEqual(
            await self.port.save(DEVICE, request, "{}", None), (revision, resource)
        )
        upload = self.client.request.call_args.args[0]
        self.assertIsInstance(upload, InformationUploadRequest)
        self.assertEqual(upload.name, source_name(DEVICE))
        self.assertEqual(upload.request_id, request)
        self.assertEqual(upload.scope.project, self.config.project)
        self.assertFalse(upload.scope.include_global)
        await self.port.save(DEVICE, request, "{}", revision)
        revise = self.client.request.call_args.args[0]
        self.assertIsInstance(revise, InformationReviseRequest)
        self.assertEqual(revise.revision, revision)

    async def test_versions_keep_only_this_devices_exact_source(self) -> None:
        row = InformationRevisionDto(
            id=str(uuid4()),
            resource_id=str(uuid4()),
            source_name=source_name(DEVICE),
            kind="source",
            ordinal=2,
            created_at=STAMP,
        )
        self.client.request.return_value = Reply(
            "OK",
            None,
            (
                row,
                replace(row, id=str(uuid4()), source_name=source_name(OTHER)),
                replace(row, id=str(uuid4()), source_name=source_name(DEVICE) + ".old"),
            ),
        )
        versions = await self.port.versions(DEVICE)
        self.assertEqual([v.revision for v in versions], [row.id])
        self.assertEqual(versions[0].ordinal, 2)
        listing = self.client.request.call_args.args[0]
        self.assertIsInstance(listing, InformationListRequest)
        self.assertEqual(listing.filter.search, source_name(DEVICE))
        self.assertFalse(listing.scope.include_global)
        for invalid in (
            replace(row, ordinal=1.5),
            replace(row, kind="report"),
            replace(row, resource_id="not-a-uuid"),
        ):
            self.client.request.return_value = Reply("OK", None, (invalid,))
            with self.assertRaises(ValueError):
                await self.port.versions(DEVICE)

    async def test_reads_go_through_the_bounded_source_reader(self) -> None:
        revision = str(uuid4())
        self.reader.read_source.return_value = "text"
        self.assertEqual(await self.port.read(revision), "text")
        self.reader.read_source.assert_awaited_once_with(revision)
        with self.assertRaises(ValueError):
            await self.port.read("not-a-uuid")


if __name__ == "__main__":
    unittest.main()
