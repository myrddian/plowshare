# Plowshare application icon

`source.png` is the emblem-only adaptation of the approved black-on-warm-ivory
retrofuturist Plowshare logo, created with the built-in imagegen tool. The atomic
orbits and sweeping ploughshare remain; lettering is omitted at application-icon
sizes. Its rounded ivory tile has a transparent surround.

Approved reference: Library `libfile_498b840198cc81918db9757ff9e40cf0`
(`plowshare-logo-retrofuturist.png`).

Final prompt: Preserve the approved atomic-orbit/ploughshare emblem, its
orientation and black/warm-ivory palette; remove lettering, center it on a
rounded ivory application tile with transparent margins, strengthen delicate
strokes for small sizes, and clean the tile contour. No extra symbols, text,
border or shadow.

Checked-in exports:

- `plowshare.png`: 1024px runtime icon for the macOS Dock and Linux windows.
- `plowshare.icns`: macOS bundle icon, with 16–1024px representations.
- `plowshare.ico`: Windows window icon, with 16, 24, 32, 48, 64, 128 and 256px entries.

Regenerate on macOS with `node scripts/export-icons.mjs` from the desktop
directory. Normal builds copy the exports and do not need image tools.
The existing distribution target packages macOS Apple Silicon only; it embeds
the ICNS in `Plowshare.app` and the runtime PNG/ICO inside its application archive.
