"""Vendor notes match whole manufacturer words on media devices and cite their sources."""

from __future__ import annotations

import unittest
from urllib.parse import urlsplit

from plowshare_privacy.vendor_notes import _NOTES, media_device, vendor_notes

TV = "TV or streaming player"


class VendorNotesTest(unittest.TestCase):
    def test_manufacturer_is_matched_as_a_whole_word_ignoring_case(self) -> None:
        [note] = vendor_notes(["SONY Corporation"], TV, ())
        self.assertEqual(note.feature, "Samba Interactive TV")
        self.assertEqual(note.confidence, "high")
        # "Sonya" and "Samsungs" are not the manufacturers.
        self.assertEqual(vendor_notes(["Sonya Labs"], TV, ()), ())
        self.assertEqual(vendor_notes(["Samsungs"], TV, ()), ())
        self.assertEqual(
            [n.manufacturer for n in vendor_notes(["LG Electronics"], TV, ())], ["LG"]
        )

    def test_only_media_devices_get_notes(self) -> None:
        self.assertEqual(vendor_notes(["Sony"], "Phone or tablet", ()), ())
        self.assertEqual(vendor_notes(["Sony"], "Unknown", ("printing",)), ())
        self.assertTrue(media_device("Unknown", ("screen casting (DIAL)",)))
        self.assertTrue(media_device("Unknown", ("media rendering (DLNA)",)))
        self.assertEqual(
            len(vendor_notes(["Sony"], "Unknown", ("screen casting (DIAL)",))), 1
        )

    def test_several_names_and_missing_names(self) -> None:
        notes = vendor_notes([None, "Google", "Sony"], TV, ())
        self.assertEqual([n.manufacturer for n in notes], ["Sony", "Google"])
        self.assertEqual(vendor_notes([None, ""], TV, ()), ())

    def test_every_entry_is_sourced_over_https_and_phrased_by_confidence(self) -> None:
        for entry in _NOTES:
            note = entry.note
            self.assertTrue(note.sources, note.feature)
            for source in note.sources:
                parts = urlsplit(source.url)
                self.assertEqual(parts.scheme, "https", source.url)
                self.assertTrue(parts.hostname, source.url)
            if note.confidence == "medium":
                self.assertTrue(note.collects.startswith("Reported by"), note)
            # Reports strip markup characters; notes never need them.
            for value in (note.feature, note.collects, note.turn_off, note.applies_to):
                self.assertIsNone(
                    next((c for c in value if c in "<>|`*_[]#\\"), None), value
                )
        # Amazon entries stay out until a vendor page confirms them.
        self.assertFalse(
            any("amazon" in source.url for e in _NOTES for source in e.note.sources)
        )


if __name__ == "__main__":
    unittest.main()
