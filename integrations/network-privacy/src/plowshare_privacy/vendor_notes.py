"""Known viewing-tracking features by manufacturer: things worth checking, not observations.

Some TV and streaming-device makers ship a feature that reports what is watched
or how the device is used. A dossier lists the matching entries so that a report
can say "worth checking: <feature>", with where to turn it off and where that
advice comes from. Nothing here says the feature is on, or that this device has
it: the match is by manufacturer name and device kind only, and menus differ by
model year.

Sourcing rule. An entry is added only with a published source. A vendor's own
page or manual is ``high`` confidence and its sentence states the vendor's
description. An entry sourced only from reporting is ``medium`` and its
sentence names the outlets ("Reported by ..."). Entries whose only source is on
an unexpected host are left out until a vendor page confirms them; the Amazon
Fire TV settings are omitted for that reason. Everything uncertain stays out;
an empty result is the honest answer for most devices.

Matching. ``manufacturers`` are matched as whole words, ignoring case, against
the manufacturer the device reports about itself and the vendor recorded for
its network identity (MAC vendor or UniFi). A note applies only to a device that
looks like a TV or streaming player (``media_device``); the classifier cannot
tell a TV from a streaming player, so ``applies_to`` says which products the
feature exists on, and the reader checks.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable, Literal

Confidence = Literal["high", "medium"]

# device_detection's type for TVs and streaming players.
MEDIA_DEVICE_TYPE = "TV or streaming player"
# Service labels from device_describer that only media receivers advertise.
_MEDIA_SERVICES = ("screen casting", "media rendering", "remote control (")


@dataclass(frozen=True)
class NoteSource:
    title: str
    url: str


@dataclass(frozen=True)
class VendorNote:
    """One feature worth checking; every field is fixed, reviewed text."""

    manufacturer: str
    feature: str
    # A lower-case phrase without a full stop: "(some BRAVIA TV models)".
    applies_to: str
    collects: str
    turn_off: str
    confidence: Confidence
    sources: tuple[NoteSource, ...]


@dataclass(frozen=True)
class _Entry:
    manufacturers: tuple[str, ...]
    note: VendorNote


_NOTES: tuple[_Entry, ...] = (
    _Entry(
        ("sony",),
        VendorNote(
            "Sony",
            "Samba Interactive TV",
            "some BRAVIA TV models",
            "Sony describes it as third-party content recognition that uses "
            "viewing history for recommendations and adverts; Sony says it does "
            "not recognise content inside installed apps such as Netflix.",
            "System Preferences → Samba Interactive TV → Disable (older Android "
            "TV: Home → Settings → Preferences → Setup → Network → Samba "
            "Interactive TV).",
            "high",
            (
                NoteSource(
                    "Sony support article",
                    "https://www.sony.com.au/electronics/support/articles/00182856",
                ),
                NoteSource(
                    "Sony BRAVIA Help Guide",
                    "https://helpguide.sony.net/tv/gmxl4/v1/en/060nhm-06-01-06.html",
                ),
            ),
        ),
    ),
    _Entry(
        ("samsung",),
        VendorNote(
            "Samsung",
            "Viewing Information Services",
            "Samsung smart TVs",
            "Samsung describes it as automatic content recognition of what is "
            "watched, which it says is opt-in.",
            "Settings → All Settings → General & Privacy → Terms & Privacy or "
            "Privacy Choices (menu names differ between sources).",
            "high",
            (
                NoteSource(
                    "Samsung support",
                    "https://www.samsung.com/us/support/answer/ANS10010616/",
                ),
            ),
        ),
    ),
    _Entry(
        ("vizio",),
        VendorNote(
            "Vizio",
            "Viewing Data",
            "Vizio smart TVs",
            "Vizio describes it as collecting information about content shown "
            "on screen, including from HDMI inputs.",
            "All Settings → Privacy & Legal → Viewing Data (older models: Admin "
            "& Privacy).",
            "high",
            (
                NoteSource(
                    "Vizio Viewing Data privacy policy",
                    "https://vizio.com/en/terms/privacy-policy/viewing-data-privacy-policy",
                ),
            ),
        ),
    ),
    _Entry(
        ("roku",),
        VendorNote(
            "Roku",
            "Smart TV experience (Use info from TV inputs)",
            "Roku TV models, including TCL and Hisense TVs with Roku built in, "
            "not Roku streaming players",
            "Roku describes it as recognising what is shown from the antenna and "
            "HDMI inputs.",
            "Home → Settings → Privacy → Smart TV experience → clear Use info "
            "from TV inputs.",
            "high",
            (
                NoteSource(
                    "Roku TV User Guide 14.5 (US)",
                    "https://image.roku.com/c3VwcG9ydC1B/Roku-TV-User-Guide---14-5---US.pdf",
                ),
            ),
        ),
    ),
    _Entry(
        ("google",),
        VendorNote(
            "Google",
            "Send device usage and crash reports to Google",
            "Chromecast, Nest and Google TV Streamer devices",
            "Google describes it as sending device usage and crash reports to Google.",
            "Google Home app → the device → Settings → Recognition & sharing.",
            "high",
            (
                NoteSource(
                    "Google Chromecast Help",
                    "https://support.google.com/chromecast/answer/9001232",
                ),
            ),
        ),
    ),
    _Entry(
        ("lg",),
        VendorNote(
            "LG",
            "Live Plus",
            "LG smart TVs",
            "Reported by The Markup and Malwarebytes: Live Plus recognises what "
            "is on screen, including from HDMI inputs. LG publishes no page "
            "with this setting.",
            "Settings → General → System → Additional Settings → Live Plus.",
            "medium",
            (
                NoteSource(
                    "The Markup, 12 December 2023",
                    "https://themarkup.org/privacy/2023/12/12/your-smart-tv-knows-what-youre-watching",
                ),
            ),
        ),
    ),
)


def media_device(device_type: str, services: Iterable[str]) -> bool:
    """True when detection or advertised services say TV or streaming player."""
    return device_type == MEDIA_DEVICE_TYPE or any(
        service.startswith(_MEDIA_SERVICES) for service in services
    )


def vendor_notes(
    names: Iterable[str | None], device_type: str, services: Iterable[str]
) -> tuple[VendorNote, ...]:
    """Notes whose manufacturer appears as a word in ``names``, in table order.

    ``names`` are untrusted (device-reported or registry vendor strings); they
    are only searched, never copied into a note.
    """
    words = {
        word
        for name in names
        if name
        for word in re.findall(r"[a-z0-9]+", name.casefold())
    }
    if not words or not media_device(device_type, services):
        return ()
    return tuple(
        entry.note
        for entry in _NOTES
        if any(manufacturer in words for manufacturer in entry.manufacturers)
    )
