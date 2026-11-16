"""Stable tar/gzip metadata, preserving executable modes and macOS framework links."""
import gzip
import shutil
from pathlib import Path
import sys
import tarfile
import zipfile

source, destination = map(Path, sys.argv[1:])


def normalized(member):
    member.uid = member.gid = 0
    member.uname = member.gname = ""
    member.mtime = 0
    member.pax_headers = {}
    return member


if destination.suffix == ".zip":
    # Windows portable bundles use ordinary files, so service identities need
    # neither symlink privileges nor an installer. Normalize ZIP metadata too.
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_symlink():
                raise ValueError("Windows bundles must not contain symbolic links")
            if path.is_file():
                member = zipfile.ZipInfo(path.relative_to(source.parent).as_posix(), (1980, 1, 1, 0, 0, 0))
                member.compress_type = zipfile.ZIP_DEFLATED
                member.external_attr = 0o100644 << 16
                with path.open("rb") as content, archive.open(member, "w") as output:
                    shutil.copyfileobj(content, output)
else:
    with destination.open("wb") as output:
        with gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                archive.add(source, arcname=source.name, filter=normalized)
