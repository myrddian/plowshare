"""Stable tar/gzip metadata, preserving executable modes and macOS framework links."""
import gzip
from pathlib import Path
import sys
import tarfile

source, destination = map(Path, sys.argv[1:])


def normalized(member):
    member.uid = member.gid = 0
    member.uname = member.gname = ""
    member.mtime = 0
    member.pax_headers = {}
    return member


with destination.open("wb") as output:
    with gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
            archive.add(source, arcname=source.name, filter=normalized)
